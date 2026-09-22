"""Routes mounted in Hermes' existing aiohttp application (no new listener)."""

import asyncio
import contextvars
from datetime import datetime
import json
import uuid

from aiohttp import WSMsgType, web

from .audio import ArtifactStore, MAX_UPLOAD, Speech
from .broker import Broker, Rejected
from .camera import MAX_CONTROL_BYTES, MAX_FRAME_BYTES
from .schema import TOOLS

PREFIX = "/zenbo/{profile}/v1"


def identifier(value):
    return isinstance(value, str) and 0 < len(value) <= 512 and not any(ord(c) < 32 for c in value)


def uuid_value(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value.lower()
    except (ValueError, AttributeError):
        return False


def date_value(value):
    try:
        return isinstance(value, str) and len(value) <= 64 and datetime.fromisoformat(value.replace("Z", "+00:00")).tzinfo is not None
    except ValueError:
        return False


def fields(body, required, optional=()):
    if not isinstance(body, dict) or set(required) - body.keys() or body.keys() - set(required) - set(optional):
        raise Rejected("invalid_request")


class Runtime:
    def __init__(self, compat):
        self.compat = compat
        self.broker = Broker()
        self.artifacts = ArtifactStore()
        self.speech = Speech(compat)
        self.loop = asyncio.get_running_loop()
        self.sockets = set()
        self.closed = False
        self.sweeper = None

    def mount(self, app):
        for method, path, handler in (
                ("GET", "/capabilities", self.capabilities),
                ("GET", "/device-channel", self.channel),
                ("POST", "/audio/transcriptions", self.transcription),
                ("POST", "/audio/speech", self.synthesis),
                ("GET", "/audio/{artifactId}", self.artifact)):
            app.router.add_route(method, PREFIX + path, self.guarded(handler))
        app.on_startup.append(self.startup)
        app.on_shutdown.append(self.shutdown)
        app.on_cleanup.append(self.cleanup)

    def guarded(self, handler):
        async def handle(request):
            try:
                if self.closed:
                    return web.json_response({"error": {"code": "plugin_unavailable", "message": "Zenbo plugin is unavailable"}}, status=503)
                if not self.compat.authenticate(request):
                    return web.json_response({"error": {"code": "unauthorized", "message": "Profile authentication failed"}}, status=401)
                device = request.headers.get("X-Zenbo-Device-Id")
                if not identifier(device):
                    raise Rejected("invalid_device_id")
                request["zenbo_identity"] = (request.match_info["profile"], device, self.compat.principal(request))
                return await handler(request)
            except Rejected as exc:
                status = 404 if exc.code == "artifact_not_found" else (503 if exc.code in {"speech_busy", "speech_timeout"} else 400)
                return web.json_response({"error": {"code": exc.code, "message": exc.message}}, status=status)
            except (ValueError, UnicodeError):
                return web.json_response({"error": {"code": "invalid_request", "message": "Invalid request"}}, status=400)
            except Exception:
                # Never expose provider exceptions, filesystem paths or credential values.
                return web.json_response({"error": {"code": "plugin_error", "message": "Zenbo request failed"}}, status=500)
        return handle

    async def capabilities(self, request):
        return web.json_response({"pluginVersion": "1.0", "tools": list(TOOLS),
                                  "speech": self.compat.speech_configured()})

    async def channel(self, request):
        profile, device, principal = request["zenbo_identity"]
        socket = web.WebSocketResponse(heartbeat=20, receive_timeout=60, max_msg_size=MAX_FRAME_BYTES, compress=False)
        await socket.prepare(request)
        self.sockets.add(socket)
        binding = None
        errors = 0
        try:
            async for frame in socket:
                if frame.type != WSMsgType.TEXT:
                    if frame.type in {WSMsgType.ERROR, WSMsgType.CLOSE, WSMsgType.CLOSED}:
                        break
                    await socket.close(code=1003, message=b"JSON text frames required")
                    break
                try:
                    body = json.loads(frame.data)
                    if not isinstance(body, dict):
                        raise Rejected("invalid_request")
                    kind = body.get("type")
                    if len(frame.data.encode("utf-8")) > MAX_CONTROL_BYTES:
                        pending = binding.pending.get(body.get("callId")) if binding is not None else None
                        if (kind != "tool.result" or body.get("status") != "succeeded" or pending is None
                                or pending.tool != "capture_camera" or pending.future.done()):
                            raise Rejected("frame_too_large")
                    if kind == "device.bind":
                        fields(body, {"type", "sessionId"})
                        if binding is not None or not identifier(body["sessionId"]):
                            raise Rejected("invalid_binding")
                        profile_context = contextvars.copy_context()
                        binding = self.broker.bind(profile, device, body["sessionId"], principal,
                                                   socket.send_json, lambda run: profile_context.run(self.compat.run_status, request, run))
                        await socket.send_json({"type": "device.bound", "deviceId": device, "sessionId": binding.session})
                    elif binding is None:
                        raise Rejected("device_not_bound")
                    elif kind in {"run.activate", "run.deactivate"}:
                        required = {"type", "sessionId", "runId", "turnId"}
                        if kind == "run.deactivate":
                            required.add("reason")
                        fields(body, required)
                        if (body["sessionId"] != binding.session or not identifier(body["runId"])
                                or not uuid_value(body["turnId"])):
                            raise Rejected("correlation_mismatch")
                        if kind == "run.activate":
                            self.broker.activate(binding, body["runId"], body["turnId"])
                            response_type = "run.active"
                        else:
                            if not isinstance(body["reason"], str) or not 0 < len(body["reason"]) <= 128:
                                raise Rejected("invalid_request")
                            self.broker.deactivate(binding, body["runId"], body["turnId"], body["reason"])
                            response_type = "run.inactive"
                        await socket.send_json({"type": response_type, "sessionId": binding.session,
                                                "runId": body["runId"], "turnId": body["turnId"]})
                    elif kind == "tool.result":
                        fields(body, {"type", "callId", "sessionId", "runId", "turnId", "status", "updatedAt"}, {"output", "error"})
                        if not uuid_value(body["callId"]) or not date_value(body["updatedAt"]):
                            raise Rejected("invalid_result")
                        await socket.send_json(self.broker.result(binding, body))
                    else:
                        raise Rejected("unknown_message")
                except (ValueError, TypeError):
                    errors += 1
                    await socket.send_json({"type": "error", "code": "invalid_request", "message": "Invalid device message"})
                except Rejected as exc:
                    errors += 1
                    await socket.send_json({"type": "error", "code": exc.code, "message": exc.message})
                if errors >= 8:
                    await socket.close(code=1008, message=b"Too many invalid messages")
                    break
        finally:
            if binding is not None:
                self.broker.disconnect(binding)
            self.sockets.discard(socket)
        return socket

    async def transcription(self, request):
        if request.content_type != "multipart/form-data" or request.content_length and request.content_length > MAX_UPLOAD + 32768:
            raise Rejected("invalid_audio")
        reader = await request.multipart()
        parts = {}
        total = 0
        async for part in reader:
            if part.name not in {"audio", "language", "sessionId", "turnId", "durationMs"} or part.name in parts:
                raise Rejected("invalid_request")
            data = bytearray()
            while True:
                chunk = await part.read_chunk(8192)
                if not chunk:
                    break
                total += len(chunk)
                data.extend(chunk)
                if total > MAX_UPLOAD + 8192 or len(data) > (MAX_UPLOAD if part.name == "audio" else 1024):
                    raise Rejected("invalid_audio")
            parts[part.name] = bytes(data) if part.name == "audio" else data.decode("utf-8")
        fields(parts, {"audio", "language", "sessionId", "turnId", "durationMs"})
        if not identifier(parts["sessionId"]) or not uuid_value(parts["turnId"]) or not 0 < len(parts["language"]) <= 32:
            raise Rejected("invalid_request")
        duration = int(parts["durationMs"])
        profile, device, principal = request["zenbo_identity"]
        binding = self.broker.speech_binding(profile, device, parts["sessionId"], principal)
        text = await self.speech.transcribe(parts["audio"], duration)
        if not binding.connected:
            raise Rejected("device_disconnected")
        return web.json_response({"text": text, "language": parts["language"]})

    async def synthesis(self, request):
        if request.content_length and request.content_length > 65536:
            raise Rejected("invalid_request")
        raw = bytearray()
        async for chunk in request.content.iter_chunked(8192):
            raw.extend(chunk)
            if len(raw) > 65536:
                raise Rejected("invalid_request")
        body = json.loads(raw)
        fields(body, {"text", "language", "sessionId", "runId", "turnId"})
        if (not isinstance(body["text"], str) or not 0 < len(body["text"]) <= 8000
                or not isinstance(body["language"], str) or not 0 < len(body["language"]) <= 32
                or not identifier(body["sessionId"]) or not identifier(body["runId"]) or not uuid_value(body["turnId"])):
            raise Rejected("invalid_request")
        profile, device, principal = request["zenbo_identity"]
        self.broker.speech_binding(profile, device, body["sessionId"], principal, body["runId"], body["turnId"])
        audio = await self.speech.synthesize(body["text"])
        # A cancellation or next turn while the provider runs revokes publication.
        self.broker.speech_binding(profile, device, body["sessionId"], principal, body["runId"], body["turnId"])
        return web.json_response({"artifacts": self.artifacts.add_many(profile, device, principal, audio)})

    async def artifact(self, request):
        profile, device, principal = request["zenbo_identity"]
        artifact_id = request.match_info["artifactId"]
        if not uuid_value(artifact_id):
            raise Rejected("artifact_not_found")
        item = self.artifacts.get(artifact_id, profile, device, principal)
        return web.Response(body=item.data, content_type=item.mime,
                            headers={"Digest": self.artifacts.digest(item), "Cache-Control": "no-store",
                                     "X-Content-Type-Options": "nosniff"})

    async def startup(self, _app):
        async def sweep():
            while not self.closed:
                await asyncio.sleep(60)
                self.artifacts.purge()
        self.sweeper = asyncio.create_task(sweep())

    def close_now(self):
        self.closed = True
        self.broker.close()
        self.artifacts.items.clear()

    async def shutdown(self, _app):
        self.close_now()
        await asyncio.gather(*(socket.close(code=1001, message=b"Hermes shutting down") for socket in list(self.sockets)), return_exceptions=True)

    async def cleanup(self, _app):
        if self.sweeper is not None:
            self.sweeper.cancel()
            await asyncio.gather(self.sweeper, return_exceptions=True)
        await self.speech.close()

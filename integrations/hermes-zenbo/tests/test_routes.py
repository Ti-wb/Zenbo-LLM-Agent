import asyncio
import importlib
import json
from pathlib import Path
import sys
import tempfile
import threading
import types
import unittest
import uuid

try:
    import aiohttp
    from aiohttp import web
    from aiohttp.test_utils import TestClient, TestServer
except ImportError:
    aiohttp = None

from test_audio import wav_bytes

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "zenbo_route_tests"
package = types.ModuleType(PACKAGE)
package.__path__ = [str(ROOT)]
sys.modules[PACKAGE] = package


class FakeCompat:
    def __init__(self):
        self.statuses = {"run": {"session_id": "session", "status": "running"}}
        self.transcription_paths = []

    def authenticate(self, request):
        return request.match_info["profile"] == "robot" and request.headers.get("Authorization") == "Bearer fixture"

    def principal(self, request):
        return "robot-key-scope"

    def run_status(self, request, run):
        return self.statuses.get(run)

    def speech_configured(self):
        return {"sttConfigured": True, "ttsConfigured": True}

    def transcribe(self, path):
        self.transcription_paths.append(path)
        return {"success": True, "transcript": "你好"}

    def speak(self, text, path):
        path.write_bytes(wav_bytes())
        second = path.with_name("second.mp3")
        second.write_bytes(b"ID3second")
        return json.dumps({"success": True, "file_paths": [str(path), str(second)]})

    def tts_output_dir(self):
        return self.audio_directory

    def check_tts_output_path(self, path):
        if not path.is_relative_to(Path(self.audio_directory).resolve()):
            raise ValueError("Test audio escaped its configured root")


@unittest.skipIf(aiohttp is None, "aiohttp is required for HTTP/WebSocket integration tests")
class RouteTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        routes = importlib.import_module(PACKAGE + ".routes")
        self.compat = FakeCompat()
        self.audio_directory = tempfile.TemporaryDirectory(prefix="zenbo-route-profile-audio-")
        self.compat.audio_directory = self.audio_directory.name
        self.runtime = routes.Runtime(self.compat)
        app = web.Application()
        self.runtime.mount(app)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.headers = {"Authorization": "Bearer fixture", "X-Zenbo-Device-Id": "robot"}
        self.root = "/zenbo/robot/v1"
        self.turn = str(uuid.uuid4())

    async def asyncTearDown(self):
        await self.client.close()
        self.audio_directory.cleanup()

    async def bind(self):
        socket = await self.client.ws_connect(self.root + "/device-channel", headers=self.headers)
        await socket.send_json({"type": "device.bind", "sessionId": "session"})
        self.assertEqual((await socket.receive_json())["type"], "device.bound")
        return socket

    async def activate(self, socket):
        await socket.send_json({"type": "run.activate", "sessionId": "session", "runId": "run", "turnId": self.turn})
        self.assertEqual((await socket.receive_json())["type"], "run.active")

    async def test_discovery_needs_auth_and_device_but_not_binding(self):
        response = await self.client.get(self.root + "/capabilities")
        self.assertEqual(response.status, 401)
        response = await self.client.get(self.root + "/capabilities", headers={"Authorization": "Bearer fixture"})
        self.assertEqual(response.status, 400)
        response = await self.client.get(self.root + "/capabilities", headers=self.headers)
        self.assertEqual(response.status, 200)
        self.assertEqual(len((await response.json())["tools"]), 6)
        response = await self.client.get("/zenbo/default/v1/capabilities", headers=self.headers)
        self.assertEqual(response.status, 401)

    async def test_actual_ws_tool_result_and_cancel(self):
        socket = await self.bind()
        await self.activate(socket)
        task = asyncio.create_task(self.runtime.broker.execute(("robot", "session", "run"), "stop_robot_following", {}))
        call = await socket.receive_json()
        self.assertEqual(call["toolName"], "stop_robot_following")
        response = {"type": "tool.result", "sessionId": "session", "runId": "run", "turnId": self.turn,
                    "callId": call["callId"], "status": "succeeded", "updatedAt": "2026-09-17T00:00:00Z", "output": {"accepted": True}}
        await socket.send_json(response)
        self.assertEqual((await socket.receive_json())["type"], "tool.ack")
        self.assertEqual(await task, {"accepted": True})
        await socket.send_json(response)
        self.assertEqual((await socket.receive_json())["code"], "unknown_or_completed_call")
        task = asyncio.create_task(self.runtime.broker.execute(("robot", "session", "run"), "go_to_sleep", {}))
        await socket.receive_json()
        await socket.send_json({"type": "run.deactivate", "sessionId": "session", "runId": "run", "turnId": self.turn, "reason": "cancelled"})
        self.assertEqual((await socket.receive_json())["type"], "run.inactive")
        self.assertIn("error", await task)
        await socket.close()

    async def test_speech_wav_multipart_chunks_and_scoped_artifact(self):
        socket = await self.bind()
        data = aiohttp.FormData()
        data.add_field("audio", wav_bytes(), filename="ignored.wav", content_type="audio/wav")
        for name, value in {"language": "zh-TW", "sessionId": "session", "turnId": self.turn, "durationMs": "100"}.items():
            data.add_field(name, value)
        response = await self.client.post(self.root + "/audio/transcriptions", headers=self.headers, data=data)
        self.assertEqual(response.status, 200, await response.text())
        self.assertEqual(await response.json(), {"text": "你好", "language": "zh-TW"})
        self.assertFalse(self.compat.transcription_paths[0].exists())
        await self.activate(socket)
        self.compat.statuses["run"]["status"] = "completed"
        payload = {"text": "你好", "language": "zh-TW", "sessionId": "session", "runId": "run", "turnId": self.turn}
        response = await self.client.post(self.root + "/audio/speech", headers=self.headers, json=payload)
        self.assertEqual(response.status, 200, await response.text())
        result = await response.json()
        self.assertEqual(len(result["artifacts"]), 2)
        self.assertNotIn("file_path", json.dumps(result))
        item = result["artifacts"][0]
        response = await self.client.get(self.root + "/audio/" + item["artifactId"], headers=self.headers)
        self.assertEqual(await response.read(), wav_bytes())
        self.assertEqual(response.headers["Content-Length"], str(item["byteLength"]))
        self.assertTrue(response.headers["Digest"].startswith("sha-256="))
        response = await self.client.get(self.root + "/audio/" + item["artifactId"], headers={**self.headers, "X-Zenbo-Device-Id": "other"})
        self.assertEqual(response.status, 404)
        await socket.close()

    async def test_errors_never_return_provider_details(self):
        socket = await self.bind()
        await self.activate(socket)
        self.compat.statuses["run"]["status"] = "completed"
        def fail(text, path):
            raise RuntimeError("/private/provider/path secret-key")
        self.compat.speak = fail
        response = await self.client.post(self.root + "/audio/speech", headers=self.headers,
                                          json={"text": "hello", "language": "en", "sessionId": "session", "runId": "run", "turnId": self.turn})
        self.assertNotEqual(response.status, 200)
        body = await response.text()
        self.assertNotIn("secret", body)
        self.assertNotIn("/private", body)
        await socket.close()

    async def test_completed_before_activation_acknowledges_speech_without_tools(self):
        socket = await self.bind()
        self.compat.statuses["run"]["status"] = "completed"
        await self.activate(socket)
        result = await self.runtime.broker.execute(("robot", "session", "run"), "stop_robot_following", {})
        self.assertEqual(result["error"]["code"], "run_inactive")
        response = await self.client.post(self.root + "/audio/speech", headers=self.headers,
                                          json={"text": "hello", "language": "en", "sessionId": "session", "runId": "run", "turnId": self.turn})
        self.assertEqual(response.status, 200, await response.text())
        self.assertEqual(len((await response.json())["artifacts"]), 2)
        await socket.send_json({"type": "run.activate", "sessionId": "session", "runId": "run", "turnId": self.turn})
        self.assertEqual((await socket.receive_json())["code"], "run_busy")
        await socket.close()

    async def test_revocation_during_speech_discards_prepared_audio(self):
        socket = await self.bind()
        await self.activate(socket)
        self.compat.statuses["run"]["status"] = "completed"
        entered, release = threading.Event(), threading.Event()
        def speak(text, path):
            entered.set()
            release.wait(2)
            path.write_bytes(wav_bytes())
            return {"success": True, "file_path": str(path)}
        self.compat.speak = speak
        response_task = asyncio.create_task(self.client.post(self.root + "/audio/speech", headers=self.headers,
                                            json={"text": "hello", "language": "en", "sessionId": "session", "runId": "run", "turnId": self.turn}))
        try:
            self.assertTrue(await asyncio.to_thread(entered.wait, 1))
            await socket.send_json({"type": "run.deactivate", "sessionId": "session", "runId": "run",
                                    "turnId": self.turn, "reason": "cancelled"})
            self.assertEqual((await socket.receive_json())["type"], "run.inactive")
        finally:
            release.set()
        response = await response_task
        self.assertEqual(response.status, 400)
        self.assertEqual((await response.json())["error"]["code"], "speech_run_not_completed")
        self.assertEqual(self.runtime.artifacts.items, {})
        self.assertEqual(list(Path(self.audio_directory.name).iterdir()), [])
        await socket.close()

    async def test_invalid_later_chunk_does_not_publish_earlier_prepared_audio(self):
        socket = await self.bind()
        await self.activate(socket)
        self.compat.statuses["run"]["status"] = "completed"
        def speak(text, path):
            path.write_bytes(wav_bytes())
            invalid = path.with_name("unsupported.bin")
            invalid.write_bytes(b"unsupported")
            return {"success": True, "file_paths": [str(path), str(invalid)]}
        self.compat.speak = speak
        response = await self.client.post(self.root + "/audio/speech", headers=self.headers,
                                         json={"text": "hello", "language": "en", "sessionId": "session", "runId": "run", "turnId": self.turn})
        self.assertEqual(response.status, 400)
        self.assertEqual((await response.json())["error"]["code"], "unsupported_speech_audio")
        self.assertEqual(self.runtime.artifacts.items, {})
        self.assertEqual(list(Path(self.audio_directory.name).iterdir()), [])
        await socket.close()

    async def test_speech_timeout_discards_late_prepared_audio_after_cleanup(self):
        socket = await self.bind()
        await self.activate(socket)
        self.compat.statuses["run"]["status"] = "completed"
        entered, release = threading.Event(), threading.Event()
        def speak(text, path):
            entered.set()
            release.wait(2)
            path.write_bytes(wav_bytes())
            return {"success": True, "file_path": str(path)}
        self.compat.speak = speak
        work = self.runtime.speech._work
        async def short_work(operation, _timeout):
            return await work(operation, 0.02)
        self.runtime.speech._work = short_work
        try:
            response = await self.client.post(self.root + "/audio/speech", headers=self.headers,
                                             json={"text": "hello", "language": "en", "sessionId": "session", "runId": "run", "turnId": self.turn})
            self.assertEqual(response.status, 503)
            self.assertEqual((await response.json())["error"]["code"], "speech_timeout")
            self.assertTrue(entered.is_set())
            self.assertEqual(len(self.runtime.speech.jobs), 1)
        finally:
            release.set()
            await self.runtime.speech.close()
        self.assertEqual(self.runtime.artifacts.items, {})
        self.assertEqual(list(Path(self.audio_directory.name).iterdir()), [])
        await socket.close()

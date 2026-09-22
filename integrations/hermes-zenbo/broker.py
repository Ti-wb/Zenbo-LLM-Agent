"""In-memory device authority. All state belongs to the API server event loop."""

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
import re
import time
import uuid

from .schema import TOOLS, validate
from .camera import validate_capture

TERMINAL = {"completed", "failed", "cancelled", "canceled"}
DEVICE_FAILURE_MESSAGES = {
    "MOTION_POWER_CONNECTED": "Disconnect the charging cable before moving or following.",
    "MOTION_USB_CONNECTED": "Disconnect the USB data cable before moving or following.",
    "FOLLOW_START_TIMEOUT": "The robot SDK did not start finding a user before the initialization deadline.",
    "FOLLOW_TARGET_NOT_FOUND": "The robot SDK did not find the user within the search deadline.",
    "FOLLOW_ENDED_BEFORE_TARGET": "The robot SDK ended following before confirming the user.",
}


def iso_time(timestamp=None):
    return datetime.fromtimestamp(timestamp or time.time(), timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class Rejected(Exception):
    def __init__(self, code, message="Device request rejected"):
        self.code, self.message = code, message
        super().__init__(message)


@dataclass
class Binding:
    profile: str
    device: str
    session: str
    principal: str
    send: object
    status: object
    connected: bool = True
    run: str = ""
    turn: str = ""
    enabled: bool = False
    reason: str = ""
    pending: dict = field(default_factory=dict)
    seen_runs: set = field(default_factory=set)
    changed: asyncio.Event = field(default_factory=asyncio.Event)


@dataclass
class Pending:
    tool: str
    future: asyncio.Future
    deadline: float
    accepted: bool = False


class Broker:
    def __init__(self, max_bindings=256, timeout=None):
        self.bindings = {}
        self.max_bindings = max_bindings
        # An explicit timeout can shorten tests/administrative waits, never extend a tool's contract.
        self.timeout = timeout

    def _terminal(self, binding):
        if not binding.run:
            return True
        status = binding.status(binding.run)
        return bool(status and status.get("session_id") == binding.session
                    and status.get("status") in TERMINAL)

    def bind(self, profile, device, session, principal, send, status):
        for key, old in list(self.bindings.items()):
            if not old.connected and self._terminal(old) and not old.pending:
                self.bindings.pop(key)
        key = (profile, session)
        old = self.bindings.get(key)
        if old is not None:
            raise Rejected("binding_conflict")
        if any(item.profile == profile and item.device == device for item in self.bindings.values()):
            raise Rejected("device_busy")
        if len(self.bindings) >= self.max_bindings:
            raise Rejected("capacity_exceeded")
        binding = Binding(profile, device, session, principal, send, status)
        self.bindings[key] = binding
        return binding

    def activate(self, binding, run, turn):
        if not binding.connected:
            raise Rejected("device_disconnected")
        if run in binding.seen_runs:
            raise Rejected("run_busy")
        if len(binding.seen_runs) >= 4096:
            raise Rejected("session_capacity_exceeded")
        if binding.run:
            # Even a repeated activate must not reopen a cancelled run.
            if run == binding.run or not self._terminal(binding) or binding.pending:
                raise Rejected("run_busy")
        status = binding.status(run)
        if (not status or status.get("session_id") != binding.session
                or status.get("status") in (TERMINAL - {"completed"}) | {"stopping"}):
            raise Rejected("run_not_active")
        binding.run, binding.turn = run, turn
        binding.seen_runs.add(run)
        # A short answer may finish before Native receives POST /runs and sends
        # its first activation. Bind its speech correlation without granting any
        # device-tool authority. Previously bound/revoked runs cannot reopen.
        completed = status.get("status") == "completed"
        binding.enabled, binding.reason = not completed, "completed" if completed else ""
        binding.changed.set()

    def deactivate(self, binding, run, turn, reason):
        if (binding.run, binding.turn) != (run, turn):
            raise Rejected("correlation_mismatch")
        binding.enabled, binding.reason = False, reason
        self._fail_pending(binding, "run_inactive")
        binding.changed.set()

    def disconnect(self, binding):
        binding.connected, binding.enabled = False, False
        binding.reason = "disconnected"
        self._fail_pending(binding, "device_disconnected")
        binding.changed.set()

    @staticmethod
    def _fail_pending(binding, code):
        for pending in binding.pending.values():
            if not pending.future.done():
                pending.future.set_result({"error": {"code": code, "message": "Device tool did not complete"}})

    def speech_binding(self, profile, device, session, principal, run=None, turn=None):
        binding = self.bindings.get((profile, session))
        if (not binding or not binding.connected or binding.device != device
                or binding.principal != principal):
            raise Rejected("device_not_bound")
        if run is not None:
            if (binding.run, binding.turn) != (run, turn):
                raise Rejected("correlation_mismatch")
            status = binding.status(run)
            if (not status or status.get("session_id") != session
                    or status.get("status") != "completed"
                    or binding.reason not in ("", "completed")):
                raise Rejected("speech_run_not_completed")
        elif binding.run and not self._terminal(binding):
            raise Rejected("run_busy")
        return binding

    async def execute(self, identity, tool, args, interrupted=lambda: False):
        if tool not in TOOLS or not validate(args, TOOLS[tool]["inputSchema"]):
            return {"error": {"code": "invalid_arguments", "message": "Device tool arguments are invalid"}}
        profile, session, run = identity
        timeout_ms = TOOLS[tool]["timeoutMs"]
        timeout = timeout_ms / 1000
        if self.timeout is not None:
            timeout = min(timeout, self.timeout)
        end = time.monotonic() + timeout
        binding = self.bindings.get((profile, session))
        if not binding or not binding.connected:
            return {"error": {"code": "device_not_bound", "message": "No device is bound to this session"}}
        # The run can call a tool before Native receives POST /runs and activates it.
        # Wait within the same tool deadline; never queue across device connections.
        while binding.run != run or not binding.enabled:
            if (not binding.connected or interrupted() or time.monotonic() >= end
                    or binding.run == run and not binding.enabled):
                return {"error": {"code": "run_inactive", "message": "Device run is not active"}}
            if binding.run and not self._terminal(binding):
                return {"error": {"code": "run_busy", "message": "Another run owns the device"}}
            # No await separates the checks above from clearing the event, so
            # loop-owned activation/revocation cannot be lost in this window.
            # Keep a bounded tick for Hermes' worker-thread interrupt flag.
            binding.changed.clear()
            try:
                await asyncio.wait_for(binding.changed.wait(), min(0.025, max(0, end - time.monotonic())))
            except asyncio.TimeoutError:
                pass
        if interrupted():
            return {"error": {"code": "cancelled", "message": "Device tool cancelled"}}
        status = binding.status(run)
        if not status or status.get("session_id") != session or status.get("status") in TERMINAL | {"stopping"}:
            return {"error": {"code": "run_inactive", "message": "Hermes run no longer accepts device tools"}}
        call_id = str(uuid.uuid4())
        future = asyncio.get_running_loop().create_future()
        pending = Pending(tool, future, end)
        binding.pending[call_id] = pending
        try:
            await asyncio.wait_for(binding.send({"type": "tool.call", "callId": call_id,
                                "sessionId": session, "runId": run, "turnId": binding.turn,
                                "toolName": tool, "toolVersion": TOOLS[tool]["version"],
                                "arguments": args, "timeoutMs": timeout_ms,
                                "deadlineAt": iso_time(time.time() + max(0, end - time.monotonic()))}),
                                   timeout=max(0, end - time.monotonic()))
            while not future.done():
                if interrupted() or not binding.enabled or not binding.connected:
                    raise Rejected("cancelled")
                if time.monotonic() >= end:
                    raise Rejected("tool_timeout")
                await asyncio.wait({future}, timeout=min(0.05, max(0, end - time.monotonic())))
            return future.result()
        except asyncio.TimeoutError:
            return {"error": {"code": "tool_timeout", "message": "Device tool did not complete"}}
        except Rejected as exc:
            return {"error": {"code": exc.code, "message": "Device tool did not complete"}}
        except Exception:
            return {"error": {"code": "device_disconnected", "message": "Device tool transport failed"}}
        finally:
            binding.pending.pop(call_id, None)
            if not future.done():
                future.cancel()

    def result(self, binding, message):
        if (message.get("sessionId"), message.get("runId"), message.get("turnId")) != (binding.session, binding.run, binding.turn):
            raise Rejected("correlation_mismatch")
        pending = binding.pending.get(message.get("callId"))
        if not pending or pending.future.done():
            raise Rejected("unknown_or_completed_call")
        if not binding.enabled or time.monotonic() >= pending.deadline:
            raise Rejected("call_expired")
        status = message.get("status")
        if status == "accepted":
            if pending.accepted or "output" in message or "error" in message:
                raise Rejected("invalid_result")
            pending.accepted = True
        elif status == "succeeded":
            if "error" in message or not validate(message.get("output"), TOOLS[pending.tool]["resultSchema"]):
                raise Rejected("invalid_result")
            if pending.tool == "capture_camera":
                try:
                    validate_capture(message["output"])
                except ValueError:
                    raise Rejected("invalid_camera_result") from None
            pending.future.set_result(message["output"])
        elif status in {"failed", "rejected"}:
            error = message.get("error")
            if ("output" in message or not isinstance(error, dict) or set(error) != {"code", "message"}
                    or not isinstance(error["code"], str) or not 0 < len(error["code"]) <= 64
                    or not isinstance(error["message"], str) or not 0 < len(error["message"]) <= 512):
                raise Rejected("invalid_result")
            # Preserve actionable reasons without echoing arbitrary Native text into model/provider logs.
            code = error["code"]
            safe_message = DEVICE_FAILURE_MESSAGES.get(code)
            if safe_message is None and re.fullmatch(r"SDK_[A-Z0-9_]{1,60}", code):
                safe_message = "The robot SDK rejected, cancelled, or failed the requested action."
            pending.future.set_result({"error": {"code": code if safe_message else "device_" + status,
                                                 "message": safe_message or "Device rejected or failed the tool"}})
        else:
            raise Rejected("invalid_result")
        return {"type": "tool.ack", "callId": message["callId"], "status": status}

    def close(self):
        for binding in self.bindings.values():
            self.disconnect(binding)

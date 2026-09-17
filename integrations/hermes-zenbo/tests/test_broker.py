"""Synthetic device-channel fault tests; no Hermes server or hardware required."""

import asyncio
import importlib.util
from pathlib import Path
import sys
import time
import types
import unittest


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "zenbo_broker_tests"
package = types.ModuleType(PACKAGE)
package.__path__ = [str(ROOT)]
sys.modules[PACKAGE] = package
spec = importlib.util.spec_from_file_location(PACKAGE + ".broker", ROOT / "broker.py")
broker_module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = broker_module
spec.loader.exec_module(broker_module)
Broker, Rejected = broker_module.Broker, broker_module.Rejected


class BrokerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.broker = Broker(timeout=0.1)
        self.statuses = {"run-1": {"session_id": "session-1", "status": "running"}}
        self.events = []
        self.sent = asyncio.Event()
        self.tasks = []

        async def send(event):
            self.events.append(event)
            self.sent.set()

        self.send = send
        self.binding = self.broker.bind(
            "robot", "device-1", "session-1", "principal-1", send, self.statuses.get
        )

    async def asyncTearDown(self):
        self.broker.close()
        await asyncio.gather(*self.tasks, return_exceptions=True)

    def reject(self, code, callback, *args):
        with self.assertRaises(Rejected) as caught:
            callback(*args)
        self.assertEqual(caught.exception.code, code)

    def activate(self):
        self.broker.activate(self.binding, "run-1", "turn-1")

    async def start_call(self, identity=("robot", "session-1", "run-1")):
        self.sent.clear()
        task = asyncio.create_task(self.broker.execute(identity, "stop_robot_following", {}))
        self.tasks.append(task)
        await asyncio.wait_for(self.sent.wait(), 0.5)
        return task, self.events[-1]

    @staticmethod
    def result(call, status="succeeded", **fields):
        return {"type": "tool.result", **{key: call[key] for key in
                ("callId", "sessionId", "runId", "turnId")}, "status": status, **fields}

    async def test_activation_requires_owned_active_status(self):
        for status in (None, {"session_id": "other", "status": "running"},
                       *({"session_id": "session-1", "status": state} for state in
                         ("failed", "cancelled", "canceled", "stopping"))):
            with self.subTest(status=status):
                self.statuses["run-1"] = status
                self.reject("run_not_active", self.broker.activate, self.binding, "run-1", "turn-1")
                self.assertFalse(self.binding.enabled)
                self.assertEqual(self.binding.run, "")
        self.assertEqual(self.events, [])

    async def test_completed_before_first_activation_allows_only_speech(self):
        self.statuses["run-1"]["status"] = "completed"
        self.activate()
        self.assertFalse(self.binding.enabled)
        self.assertEqual(self.binding.reason, "completed")
        self.assertIs(self.broker.speech_binding("robot", "device-1", "session-1", "principal-1",
                                                "run-1", "turn-1"), self.binding)
        result = await self.broker.execute(("robot", "session-1", "run-1"), "stop_robot_following", {})
        self.assertEqual(result["error"]["code"], "run_inactive")
        self.assertEqual(self.events, [])
        self.reject("run_busy", self.broker.activate, self.binding, "run-1", "another-turn")

    async def test_completed_run_revoked_then_replaced_cannot_be_resurrected(self):
        self.statuses["run-1"]["status"] = "completed"
        self.activate()
        self.broker.deactivate(self.binding, "run-1", "turn-1", "cancelled")
        self.reject("speech_run_not_completed", self.broker.speech_binding,
                    "robot", "device-1", "session-1", "principal-1", "run-1", "turn-1")
        self.statuses["run-2"] = {"session_id": "session-1", "status": "completed"}
        self.broker.activate(self.binding, "run-2", "turn-2")
        self.assertFalse(self.binding.enabled)
        self.reject("run_busy", self.broker.activate, self.binding, "run-1", "turn-3")

    async def test_binding_run_history_is_bounded_without_forgetting_revocation(self):
        self.binding.seen_runs = {"previous-" + str(i) for i in range(4096)}
        self.reject("session_capacity_exceeded", self.broker.activate, self.binding, "run-1", "turn-1")

    async def test_profile_session_device_and_principal_cannot_be_mixed(self):
        self.activate()
        for identity in (("other", "session-1", "run-1"), ("robot", "other", "run-1")):
            result = await self.broker.execute(identity, "stop_robot_following", {})
            self.assertEqual(result["error"]["code"], "device_not_bound")
        for args in (("other", "device-1", "session-1", "principal-1"),
                     ("robot", "other", "session-1", "principal-1"),
                     ("robot", "device-1", "other", "principal-1"),
                     ("robot", "device-1", "session-1", "other")):
            self.reject("device_not_bound", self.broker.speech_binding, *args)
        self.assertEqual(self.events, [])

    async def test_duplicate_binding_preserves_original_channel(self):
        self.reject("binding_conflict", self.broker.bind, "robot", "device-2", "session-1",
                    "principal-1", self.send, self.statuses.get)
        self.reject("device_busy", self.broker.bind, "robot", "device-1", "session-2",
                    "principal-1", self.send, self.statuses.get)
        self.assertIs(self.broker.bindings[("robot", "session-1")], self.binding)

    async def test_accepted_receipt_is_not_terminal(self):
        self.activate()
        task, call = await self.start_call()
        receipt = self.result(call, "accepted")
        self.assertEqual(self.broker.result(self.binding, receipt)["status"], "accepted")
        await asyncio.sleep(0)
        self.assertFalse(task.done())
        self.reject("invalid_result", self.broker.result, self.binding, receipt)
        self.broker.result(self.binding, self.result(call, output={"accepted": True}))
        self.assertEqual(await task, {"accepted": True})
        self.reject("unknown_or_completed_call", self.broker.result, self.binding,
                    self.result(call, output={"accepted": True}))

    async def test_success_requires_matching_correlation_and_result_schema(self):
        self.activate()
        task, call = await self.start_call()
        for key in ("sessionId", "runId", "turnId"):
            message = self.result(call, output={"accepted": True})
            message[key] = "other"
            self.reject("correlation_mismatch", self.broker.result, self.binding, message)
        for output in ({}, {"accepted": 1}, {"accepted": True, "extra": True}, {"ok": True}):
            self.reject("invalid_result", self.broker.result, self.binding, self.result(call, output=output))
        self.assertFalse(task.done())
        self.broker.result(self.binding, self.result(call, output={"accepted": False}))
        self.assertEqual(await task, {"accepted": False})

    async def test_deadline_expires_without_replaying_call(self):
        self.activate()
        task, call = await self.start_call()
        self.assertEqual((await task)["error"]["code"], "tool_timeout")
        self.assertEqual(len(self.events), 1)
        self.assertFalse(self.binding.pending)
        self.reject("unknown_or_completed_call", self.broker.result, self.binding,
                    self.result(call, output={"accepted": True}))

    async def test_deadline_includes_stalled_transport_send(self):
        self.activate()
        entered = asyncio.Event()

        async def stalled_send(event):
            entered.set()
            await asyncio.Event().wait()

        self.binding.send = stalled_send
        task = asyncio.create_task(self.broker.execute(("robot", "session-1", "run-1"),
                                                       "stop_robot_following", {}))
        self.tasks.append(task)
        result = await asyncio.wait_for(task, 0.5)
        self.assertTrue(entered.is_set())
        self.assertEqual(result["error"]["code"], "tool_timeout")
        self.assertFalse(self.binding.pending)

    async def test_late_result_rejected_before_timeout_loop_runs(self):
        self.activate()
        task, call = await self.start_call()
        self.binding.pending[call["callId"]].deadline = time.monotonic() - 1
        self.reject("call_expired", self.broker.result, self.binding,
                    self.result(call, output={"accepted": True}))
        self.broker.disconnect(self.binding)
        self.assertIn("error", await task)

    async def test_deactivate_fails_pending_and_cannot_reactivate_same_run(self):
        self.activate()
        task, call = await self.start_call()
        self.broker.deactivate(self.binding, "run-1", "turn-1", "cancelled")
        self.assertEqual((await task)["error"]["code"], "run_inactive")
        self.reject("run_busy", self.broker.activate, self.binding, "run-1", "turn-1")
        result = await self.broker.execute(("robot", "session-1", "run-1"), "stop_robot_following", {})
        self.assertEqual(result["error"]["code"], "run_inactive")
        self.assertEqual(len(self.events), 1)

    async def test_interruption_cancels_pending_without_replay(self):
        self.activate()
        interrupted = False
        task = asyncio.create_task(self.broker.execute(
            ("robot", "session-1", "run-1"), "stop_robot_following", {},
            interrupted=lambda: interrupted))
        self.tasks.append(task)
        await asyncio.wait_for(self.sent.wait(), 0.5)
        interrupted = True
        self.assertEqual((await task)["error"]["code"], "cancelled")
        self.assertFalse(self.binding.pending)
        self.assertEqual(len(self.events), 1)

    async def test_disconnect_fails_pending_and_never_replays_on_rebind(self):
        self.activate()
        task, call = await self.start_call()
        self.broker.disconnect(self.binding)
        self.assertEqual((await task)["error"]["code"], "device_disconnected")
        self.reject("binding_conflict", self.broker.bind, "robot", "device-1", "session-1",
                    "principal-1", self.send, self.statuses.get)
        self.statuses["run-1"]["status"] = "cancelled"
        replacement = self.broker.bind("robot", "device-1", "session-1", "principal-1",
                                       self.send, self.statuses.get)
        self.assertIsNot(replacement, self.binding)
        self.assertEqual(len(self.events), 1)
        self.assertFalse(replacement.pending)

    async def test_stopping_run_blocks_next_run_until_authoritative_terminal(self):
        self.activate()
        self.broker.deactivate(self.binding, "run-1", "turn-1", "cancelled")
        self.statuses["run-1"]["status"] = "stopping"
        self.statuses["run-2"] = {"session_id": "session-1", "status": "running"}
        self.reject("run_busy", self.broker.activate, self.binding, "run-2", "turn-2")
        result = await self.broker.execute(("robot", "session-1", "run-2"), "stop_robot_following", {})
        self.assertEqual(result["error"]["code"], "run_busy")
        self.statuses["run-1"]["status"] = "cancelled"
        self.broker.activate(self.binding, "run-2", "turn-2")
        self.assertTrue(self.binding.enabled)

    async def test_activation_race_waits_without_dispatch_then_sends_once(self):
        task = asyncio.create_task(self.broker.execute(("robot", "session-1", "run-1"),
                                                       "stop_robot_following", {}))
        self.tasks.append(task)
        await asyncio.sleep(0)
        self.assertEqual(self.events, [])
        self.activate()
        await asyncio.wait_for(self.sent.wait(), 0.5)
        call = self.events[0]
        self.assertEqual((call["sessionId"], call["runId"], call["turnId"]),
                         ("session-1", "run-1", "turn-1"))
        self.broker.result(self.binding, self.result(call, output={"accepted": True}))
        self.assertEqual(await task, {"accepted": True})
        self.assertEqual(len(self.events), 1)

    async def test_missing_activation_and_invalid_arguments_never_dispatch(self):
        result = await self.broker.execute(("robot", "session-1", "run-1"), "stop_robot_following", {})
        self.assertEqual(result["error"]["code"], "run_inactive")
        self.activate()
        for tool, args in (("unknown", {}), ("look_at_user", {"doa": True}),
                           ("stop_robot_following", {"deviceId": "device-2"})):
            result = await self.broker.execute(("robot", "session-1", "run-1"), tool, args)
            self.assertEqual(result["error"]["code"], "invalid_arguments")
        self.assertEqual(self.events, [])

    async def test_failure_text_is_not_echoed_to_model(self):
        self.activate()
        task, call = await self.start_call()
        self.broker.result(self.binding, self.result(call, "failed", error={
            "code": "private-code", "message": "private device information"}))
        self.assertEqual(await task, {"error": {"code": "device_failed",
                                               "message": "Device rejected or failed the tool"}})

import contextvars
import concurrent.futures
import importlib
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "zenbo_compat_tests"
package = types.ModuleType(PACKAGE)
package.__path__ = [str(ROOT)]
sys.modules[PACKAGE] = package
module = importlib.import_module(PACKAGE + ".compat")


class CompatTests(unittest.TestCase):
    def setUp(self):
        self.profile = contextvars.ContextVar("fake_api_profile", default=None)
        self.compat = object.__new__(module.HermesCompat)
        self.compat.api = types.SimpleNamespace(_api_request_profile=self.profile, _PROFILE_REJECTED=object())
        self.compat.adapter = types.SimpleNamespace(
            _resolve_request_profile=lambda request: request.profile,
            _expected_api_key=lambda: "configured-fixture-key",
            _check_auth=lambda request: None if request.valid else object(),
            _request_owns_run=lambda request, run: request.profile == "robot" and run == "owned",
            _durable_run_status=lambda request, run: {"run_id": run, "session_id": "session", "status": "running"})

    def test_auth_requires_existing_profile_scope_valid_key_and_auth_success(self):
        request = types.SimpleNamespace(profile="robot", valid=True)
        self.assertFalse(self.compat.authenticate(request))
        token = self.profile.set("robot")
        try:
            self.assertTrue(self.compat.authenticate(request))
            request.valid = False
            self.assertFalse(self.compat.authenticate(request))
            request.valid = True
            self.compat.adapter._expected_api_key = lambda: ""
            self.assertFalse(self.compat.authenticate(request))
        finally:
            self.profile.reset(token)

    def test_run_authority_never_uses_unowned_status(self):
        self.assertIsNone(self.compat.run_status(types.SimpleNamespace(profile="default"), "owned"))
        self.assertIsNone(self.compat.run_status(types.SimpleNamespace(profile="robot"), "other"))
        self.assertEqual(self.compat.run_status(types.SimpleNamespace(profile="robot"), "owned")["session_id"], "session")

    def test_context_requires_api_session_and_actual_approval_run(self):
        values = {"HERMES_SESSION_PLATFORM": "api_server", "HERMES_SESSION_PROFILE": "robot",
                  "HERMES_SESSION_ID": "session"}
        self.compat.session = types.SimpleNamespace(session_context_engaged=lambda: True,
                                                     get_session_env=lambda key, default: values.get(key, default))
        self.compat.approval = types.SimpleNamespace(get_current_session_key=lambda default: "actual-run")
        self.assertEqual(self.compat.tool_identity("session"), ("robot", "session", "actual-run"))
        with self.assertRaises(module.IncompatibleHermes):
            self.compat.tool_identity("other-session")
        values["HERMES_SESSION_PLATFORM"] = "telegram"
        with self.assertRaises(module.IncompatibleHermes):
            self.compat.tool_identity("session")
        values["HERMES_SESSION_PLATFORM"] = "api_server"
        self.compat.session.session_context_engaged = lambda: False
        with self.assertRaises(module.IncompatibleHermes):
            self.compat.tool_identity("session")

    def test_missing_private_helper_fails_preflight(self):
        del self.compat.adapter._check_auth
        with self.assertRaises(module.IncompatibleHermes):
            self.compat.preflight()

    def test_tts_uses_only_profile_directory_or_host_approved_roots(self):
        self.compat.tts = types.SimpleNamespace(_default_output_dir=lambda: "/protected-profile/cache/audio")
        self.compat.file_safety = types.SimpleNamespace(
            get_safe_write_roots=lambda: {"/workspace"},
            is_write_denied=lambda path: not path.startswith("/workspace/"),
            is_write_approval_required=lambda path: False)
        self.assertEqual(self.compat.tts_output_dir(), Path("/workspace"))
        self.compat.tts._default_output_dir = lambda: "/workspace/profile/audio"
        self.assertEqual(self.compat.tts_output_dir(), Path("/workspace/profile/audio"))
        self.compat.file_safety.is_write_approval_required = lambda path: True
        with self.assertRaises(module.IncompatibleHermes):
            self.compat.tts_output_dir()

    def test_tts_rejects_actual_protected_path_and_missing_allowed_root(self):
        self.compat.tts = types.SimpleNamespace(_default_output_dir=lambda: "/protected-profile/cache/audio")
        self.compat.file_safety = types.SimpleNamespace(
            get_safe_write_roots=lambda: set(), is_write_denied=lambda path: True,
            is_write_approval_required=lambda path: False)
        with self.assertRaises(module.IncompatibleHermes):
            self.compat.tts_output_dir()
        with self.assertRaises(module.IncompatibleHermes):
            self.compat.check_tts_output_path(Path("/tmp/speech.wav"))


class PluginTests(unittest.TestCase):
    def invoke_with_fake_future(self, name, future, clock, interrupted=False):
        plugin = importlib.import_module(PACKAGE + ".plugin")
        old = getattr(plugin.shared(), "runtime", None)
        runtime = types.SimpleNamespace(closed=False,
            compat=types.SimpleNamespace(tool_identity=lambda session: ("robot", session, "run-1"),
                interrupt=types.SimpleNamespace(is_interrupted=lambda: interrupted)),
            broker=types.SimpleNamespace(execute=Mock(return_value=object())),
            loop=types.SimpleNamespace(is_running=lambda: True))
        plugin.shared().runtime = runtime
        registered = []
        ctx = types.SimpleNamespace(register_tool=lambda **kwargs: registered.append(kwargs),
            register_platform_handler=lambda *args: None, on_unload=lambda callback: None)
        try:
            plugin.register(ctx)
            handler = next(tool["handler"] for tool in registered if tool["name"] == name)
            with patch.object(plugin, "time", types.SimpleNamespace(monotonic=lambda: clock[0])), \
                    patch.object(plugin.asyncio, "run_coroutine_threadsafe", return_value=future):
                result = handler({}, session_id="session-1")
            self.assertEqual(runtime.broker.execute.call_args.args[:3], (("robot", "session-1", "run-1"), name, {}))
            self.assertTrue(runtime.broker.execute.call_args.args[3]())
            return json.loads(result)
        finally:
            plugin.shared().runtime = old

    def test_handler_does_not_cancel_a_broker_result_within_each_full_native_budget(self):
        for name, native_seconds in (("start_robot_following", 6.5), ("move_robot", 5.5)):
            with self.subTest(tool=name):
                clock = [100.0]
                attempts = []

                def result(timeout):
                    attempts.append(timeout)
                    if len(attempts) == 1:
                        clock[0] += native_seconds
                        raise concurrent.futures.TimeoutError()
                    return {"accepted": True}

                future = types.SimpleNamespace(result=result, cancel=Mock())
                self.assertEqual(self.invoke_with_fake_future(name, future, clock), {"accepted": True})
                future.cancel.assert_not_called()
                self.assertEqual(attempts, [0.05, 0.05])

    def test_handler_cancels_a_stalled_broker_after_manifest_budget_plus_handoff_margin(self):
        for name, handler_seconds in (("start_robot_following", 8.0), ("move_robot", 7.0), ("stop_robot_following", 5.5)):
            with self.subTest(tool=name):
                clock = [100.0]

                def result(timeout):
                    clock[0] += handler_seconds
                    raise concurrent.futures.TimeoutError()

                future = types.SimpleNamespace(result=result, cancel=Mock())
                self.assertEqual(self.invoke_with_fake_future(name, future, clock)["error"]["code"], "plugin_unavailable")
                future.cancel.assert_called_once_with()

    def test_handler_still_rejects_a_completed_result_after_worker_interruption(self):
        future = types.SimpleNamespace(result=lambda timeout: {"accepted": True}, cancel=Mock())
        self.assertEqual(self.invoke_with_fake_future("start_robot_following", future, [100.0], interrupted=True)
                         ["error"]["code"], "cancelled")

    def test_eight_tools_register_and_fail_closed_without_listener(self):
        plugin = importlib.import_module(PACKAGE + ".plugin")
        # Isolate the real process-shared slot from other test runtimes.
        old = getattr(plugin.shared(), "runtime", None)
        plugin.shared().runtime = None
        registered, factories, cleanup = [], [], []
        ctx = types.SimpleNamespace(register_tool=lambda **kwargs: registered.append(kwargs),
                                    register_platform_handler=lambda *args: factories.append(args),
                                    on_unload=lambda callback: cleanup.append(callback))
        try:
            plugin.register(ctx)
            self.assertEqual(len(registered), 8)
            self.assertEqual(factories[0][0], "api_server")
            for tool in registered:
                self.assertIn("plugin_unavailable", tool["handler"]({}, session_id="session"))
            self.assertEqual(len(cleanup), 1)
            # A separately namespaced profile import addresses the same holder.
            alternate = types.ModuleType("zenbo_other_profile")
            alternate.__path__ = [str(ROOT)]
            sys.modules[alternate.__name__] = alternate
            other = importlib.import_module(alternate.__name__ + ".plugin")
            self.assertIs(plugin.shared(), other.shared())
        finally:
            plugin.shared().runtime = old

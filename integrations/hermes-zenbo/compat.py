"""Small, fail-closed boundary around Hermes internals used by this plugin.

Hermes' version string alone cannot prove this private integration contract.
The preflight checks the installed implementation, without reading credentials.
"""

import ast
import importlib
import inspect
from pathlib import Path
import textwrap


class IncompatibleHermes(RuntimeError):
    pass


class HermesCompat:
    def __init__(self, adapter):
        self.adapter = adapter
        self.api = importlib.import_module("gateway.platforms.api_server")
        self.runs = importlib.import_module("gateway.platforms.api_server_runs")
        self.session = importlib.import_module("gateway.session_context")
        self.approval = importlib.import_module("tools.approval_context")
        self.interrupt = importlib.import_module("tools.interrupt")
        self.tts = importlib.import_module("tools.tts_tool")
        self.file_safety = importlib.import_module("agent.file_safety")
        self.preflight()

    def preflight(self):
        required = ("_check_auth", "_expected_api_key", "_resolve_request_profile",
                    "_profile_scope", "_request_owns_run", "_durable_run_status", "_run_idempotency_scope")
        if any(not callable(getattr(self.adapter, name, None)) for name in required):
            raise IncompatibleHermes("Hermes API profile/run helpers are unsupported")
        for obj, name in ((self.api, "_api_request_profile"),
                          (self.session, "get_session_env"),
                          (self.session, "session_context_engaged"),
                          (self.approval, "get_current_session_key"),
                          (self.interrupt, "is_interrupted")):
            if not hasattr(obj, name):
                raise IncompatibleHermes("Hermes tool context helpers are unsupported")
        if not callable(getattr(self.tts, "_default_output_dir", None)) or any(
                not callable(getattr(self.file_safety, name, None)) for name in
                ("get_safe_write_roots", "is_write_denied", "is_write_approval_required")):
            raise IncompatibleHermes("Hermes safe audio output helpers are unsupported")
        try:
            getter = self.runs._RunLaunch.approval_session_key.fget
            tree = ast.parse(textwrap.dedent(inspect.getsource(getter)))
            returns = [node for node in ast.walk(tree) if isinstance(node, ast.Return)]
            direct_run_id = (len(returns) == 1 and isinstance(returns[0].value, ast.Attribute)
                             and returns[0].value.attr == "run_id"
                             and isinstance(returns[0].value.value, ast.Name)
                             and returns[0].value.value.id == "self")
            source = inspect.getsource(self.runs._run_agent_sync)
            bound = "set_current_session_key(run.approval_session_key)" in source
            bound = bound and "session_key=run.approval_session_key" in source
            middleware = inspect.getsource(self.adapter._make_profile_prefix_middleware)
            scoped = "_resolve_request_profile(request)" in middleware
            scoped = scoped and "self._profile_scope(profile)" in middleware
        except (AttributeError, OSError, TypeError, SyntaxError):
            direct_run_id = bound = scoped = False
        if not (direct_run_id and bound and scoped):
            raise IncompatibleHermes("Hermes run identity/profile scope needs compatibility review")

    def authenticate(self, request):
        # The host middleware must already have selected the URL profile.
        selected = self.adapter._resolve_request_profile(request)
        if selected is getattr(self.api, "_PROFILE_REJECTED", object()):
            return False
        if self.api._api_request_profile.get() != selected:
            return False
        # Do not inherit the host's no-key test-only bypass.
        return bool(self.adapter._expected_api_key()) and self.adapter._check_auth(request) is None

    def run_status(self, request, run_id):
        if not self.adapter._request_owns_run(request, run_id):
            return None
        value = self.adapter._durable_run_status(request, run_id)
        return dict(value) if isinstance(value, dict) else None

    def principal(self, request):
        return self.adapter._run_idempotency_scope(request)

    def tool_identity(self, session_id):
        if not self.session.session_context_engaged():
            raise IncompatibleHermes("Device tools require a live API run context")
        get = self.session.get_session_env
        if get("HERMES_SESSION_PLATFORM", "") != "api_server":
            raise IncompatibleHermes("Device tools require the API server")
        actual_session = get("HERMES_SESSION_ID", "")
        run_id = self.approval.get_current_session_key(default="")
        if not actual_session or actual_session != session_id or not run_id:
            raise IncompatibleHermes("Device tool context is incomplete")
        profile = get("HERMES_SESSION_PROFILE", "")
        if not profile:
            from hermes_cli.profiles import get_active_profile_name
            profile = get_active_profile_name()
        return str(profile), actual_session, run_id

    def speech_configured(self):
        # Configuration availability is not a provider invocation or readiness proof.
        from hermes_cli.config import load_config
        config = load_config() or {}
        stt = config.get("stt", {}) or {}
        tts = config.get("tts", {}) or {}
        return {"sttConfigured": bool(stt) and stt.get("enabled", True) is not False,
                "ttsConfigured": bool(tts)}

    @staticmethod
    def transcribe(path):
        from tools.transcription_tools import transcribe_audio
        return transcribe_audio(str(path), source="zenbo")

    @staticmethod
    def speak(text, output_path):
        from tools.tts_tool import text_to_speech_tool
        return text_to_speech_tool(text=text, output_path=str(output_path))

    def check_tts_output_path(self, path):
        value = str(Path(path).resolve())
        if self.file_safety.is_write_denied(value) or self.file_safety.is_write_approval_required(value):
            raise IncompatibleHermes("Hermes policy does not permit this audio output path")

    def tts_output_dir(self):
        # Called in the worker's authenticated profile scope. Prefer the profile
        # audio location, then only roots admitted by the host's existing policy.
        candidates = [self.tts._default_output_dir(), *sorted(self.file_safety.get_safe_write_roots())]
        for candidate in candidates:
            path = Path(candidate)
            if not path.is_absolute():
                continue
            path = path.resolve()
            try:
                self.check_tts_output_path(path / ".zenbo-audio-probe" / "speech.wav")
            except IncompatibleHermes:
                continue
            return path
        raise IncompatibleHermes("Hermes has no policy-approved audio output directory")

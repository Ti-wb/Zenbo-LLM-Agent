"""Register profile-scoped tools and one API-server route factory."""

import asyncio
import concurrent.futures
import json
import sys
import threading
import time
import types

from .schema import TOOLS

_SHARED_NAME = "_hermes_zenbo_runtime_v1"


def shared():
    # Hermes loads plugins in per-profile Python namespaces. The listener-owner
    # factory and profile-registered tools must address the same in-process broker.
    module = sys.modules.setdefault(_SHARED_NAME, types.ModuleType(_SHARED_NAME))
    return module


def register(ctx):
    def factory(app, adapter):
        from .compat import HermesCompat
        from .routes import Runtime
        holder = shared()
        if getattr(holder, "runtime", None) is not None and not holder.runtime.closed:
            raise RuntimeError("Zenbo requires exactly one Hermes API listener per process")
        runtime = Runtime(HermesCompat(adapter))
        runtime.mount(app)
        holder.runtime = runtime

    def make_handler(name):
        def handler(args, **kwargs):
            runtime = getattr(shared(), "runtime", None)
            if runtime is None or runtime.closed:
                return json.dumps({"error": {"code": "plugin_unavailable", "message": "Zenbo API plugin is unavailable"}})
            try:
                identity = runtime.compat.tool_identity(kwargs.get("session_id"))
                # The coroutine runs on the HTTP loop, where thread-local Hermes
                # interrupt flags are not those of this tool worker. Capture the
                # worker's interrupt state in a thread-safe cancellation signal.
                cancelled = threading.Event()
                future = asyncio.run_coroutine_threadsafe(runtime.broker.execute(identity, name, args, cancelled.is_set), runtime.loop)
                end = time.monotonic() + 5.5
                try:
                    while True:
                        if runtime.compat.interrupt.is_interrupted():
                            cancelled.set()
                        try:
                            result = future.result(timeout=0.05)
                            return json.dumps(result, ensure_ascii=False, allow_nan=False)
                        except concurrent.futures.TimeoutError:
                            if runtime.closed or not runtime.loop.is_running() or time.monotonic() >= end:
                                cancelled.set()
                                future.cancel()
                                return json.dumps({"error": {"code": "plugin_unavailable", "message": "Zenbo API plugin stopped"}})
                finally:
                    cancelled.set()
            except Exception:
                return json.dumps({"error": {"code": "invalid_run_context", "message": "Device tool requires an authenticated bound Hermes run"}})
        return handler

    for name, tool in TOOLS.items():
        ctx.register_tool(name=name, toolset="zenbo", description=tool["description"],
                          schema={"name": name, "description": tool["description"], "parameters": tool["inputSchema"]},
                          handler=make_handler(name), is_async=False)
    ctx.register_platform_handler("api_server", factory)

    def unload():
        runtime = getattr(shared(), "runtime", None)
        if runtime is not None and runtime.loop.is_running():
            runtime.loop.call_soon_threadsafe(runtime.close_now)
    ctx.on_unload(unload)

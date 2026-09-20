"""Synthetic local hot-path timings; no provider, network or device access."""

import argparse
import asyncio
import importlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import tempfile
import time
import types


ROOT = Path(__file__).resolve().parents[1]


def modules(name, root):
    package = types.ModuleType(name)
    package.__path__ = [str(root)]
    sys.modules[name] = package
    return importlib.import_module(name + ".audio"), importlib.import_module(name + ".broker")


async def measure(audio, broker_module):
    payload = b"ID3" + b"\0" * (8 * 1024 * 1024 - 3)
    # Model the bytes/metadata returned by the existing speech worker. Exclude
    # preparation from publication time: it does not block the HTTP event loop.
    prepared = await asyncio.to_thread(lambda: [audio.prepare_audio(payload) for _ in range(4)]) if hasattr(audio, "prepare_audio") else [payload] * 4
    publication, downloads = [], []
    for _ in range(5):
        store = audio.ArtifactStore()
        start = time.perf_counter()
        manifest = store.add_many("synthetic", "device", "principal", prepared)
        publication.append((time.perf_counter() - start) * 1000)
        start = time.perf_counter()
        for _ in range(16):
            item = store.get(manifest[0]["artifactId"], "synthetic", "device", "principal")
            store.digest(item)
        downloads.append((time.perf_counter() - start) * 1000)

    activation = []
    for _ in range(20):
        broker = broker_module.Broker(timeout=1)
        dispatched = []
        async def send(call):
            dispatched.append(time.perf_counter())
            broker.result(binding, {**call, "status": "succeeded", "output": {"accepted": True}})
        binding = broker.bind("synthetic", "device", "session", "principal", send,
                              lambda run: {"session_id": "session", "status": "running"})
        task = asyncio.create_task(broker.execute(("synthetic", "session", "run"), "stop_robot_following", {}))
        await asyncio.sleep(0.001)
        start = time.perf_counter()
        broker.activate(binding, "run", "turn")
        result = await task
        if result != {"accepted": True} or len(dispatched) != 1:
            raise AssertionError("Synthetic tool did not dispatch exactly once")
        activation.append((dispatched[0] - start) * 1000)
        broker.close()
    return {"publication_32_mib_median_ms": round(statistics.median(publication), 4),
            "get_digest_16_x_8_mib_median_ms": round(statistics.median(downloads), 4),
            "activation_to_dispatch_median_ms": round(statistics.median(activation), 4),
            "publication_samples": 5, "activation_samples": 20}


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", help="Git revision for an optional before/after comparison")
    args = parser.parse_args()
    result = {"kind": "synthetic_local_microbenchmark", "python": sys.version.split()[0]}
    if args.baseline_ref:
        with tempfile.TemporaryDirectory(prefix="zenbo-perf-baseline-") as directory:
            baseline = Path(directory)
            for filename in ("audio.py", "broker.py", "schema.py", "device-tools.json"):
                data = subprocess.check_output(["git", "show", f"{args.baseline_ref}:integrations/hermes-zenbo/{filename}"], cwd=ROOT)
                (baseline / filename).write_bytes(data)
            result["before"] = await measure(*modules("zenbo_perf_before", baseline))
    result["after"] = await measure(*modules("zenbo_perf_after", ROOT))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    asyncio.run(main())

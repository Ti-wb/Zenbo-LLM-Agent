import asyncio
import contextvars
import importlib
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import types
import unittest
import wave

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "zenbo_audio_tests"
package = types.ModuleType(PACKAGE)
package.__path__ = [str(ROOT)]
sys.modules[PACKAGE] = package
audio = importlib.import_module(PACKAGE + ".audio")


def wav_bytes():
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(b"\0\0" * 1600)
    return output.getvalue()


class AudioTests(unittest.IsolatedAsyncioTestCase):
    def test_wav_rejects_wrong_shape_and_truncated_payload(self):
        audio.validate_wav(wav_bytes(), 100)
        for payload, duration in ((wav_bytes()[:-4], 100), (wav_bytes(), 5000), (b"not audio", 100)):
            with self.assertRaises(audio.Rejected):
                audio.validate_wav(payload, duration)

    def test_artifact_scope_digest_ttl_and_atomic_batch(self):
        store = audio.ArtifactStore(ttl=100)
        manifest = store.add_many("grok", "robot", "principal", [wav_bytes(), b"ID3audio"])
        self.assertEqual([item["mimeType"] for item in manifest], ["audio/wav", "audio/mpeg"])
        self.assertNotIn("path", json.dumps(manifest))
        artifact = store.get(manifest[0]["artifactId"], "grok", "robot", "principal")
        self.assertTrue(store.digest(artifact).startswith("sha-256="))
        for identity in (("default", "robot", "principal"), ("grok", "other", "principal"), ("grok", "robot", "other")):
            with self.assertRaises(audio.Rejected):
                store.get(manifest[0]["artifactId"], *identity)
        with self.assertRaises(audio.Rejected):
            store.add_many("grok", "robot", "principal", [wav_bytes(), b"unsupported"])
        self.assertEqual(len(store.items), 2)
        artifact.expires = time.time() - 1
        with self.assertRaises(audio.Rejected):
            store.get(manifest[0]["artifactId"], "grok", "robot", "principal")

    async def test_stt_copies_context_and_deletes_original_on_success_and_failure(self):
        profile = contextvars.ContextVar("test_profile")
        profile.set("grok")
        paths = []
        def transcribe(path):
            self.assertEqual(profile.get(), "grok")
            paths.append(path)
            self.assertTrue(path.exists())
            return {"success": True, "transcript": "你好"}
        speech = audio.Speech(types.SimpleNamespace(transcribe=transcribe))
        self.assertEqual(await speech.transcribe(wav_bytes(), 100), "你好")
        self.assertFalse(paths[0].exists())
        def failure(path):
            paths.append(path)
            raise RuntimeError("secret provider traceback /private/path")
        speech.compat.transcribe = failure
        with self.assertRaises(audio.Rejected) as caught:
            await speech.transcribe(wav_bytes(), 100)
        self.assertNotIn("secret", str(caught.exception))
        self.assertFalse(paths[-1].exists())

    async def test_tts_keeps_chunk_order_and_deletes_private_files(self):
        paths = []
        def speak(text, output_path):
            first = output_path.with_name("first.wav")
            second = output_path.with_name("second.mp3")
            first.write_bytes(wav_bytes())
            second.write_bytes(b"ID3second")
            paths.extend([first, second])
            return json.dumps({"success": True, "file_paths": [str(first), str(second)], "private": "not exported"})
        speech = audio.Speech(types.SimpleNamespace(speak=speak))
        result = await speech.synthesize("你好")
        self.assertEqual(result, [wav_bytes(), b"ID3second"])
        self.assertTrue(all(not path.exists() for path in paths))

    async def test_tts_rejects_path_escape_and_does_not_delete_external_file(self):
        with tempfile.TemporaryDirectory() as external:
            outside = Path(external) / "outside.wav"
            outside.write_bytes(wav_bytes())
            def speak(text, output_path):
                return {"success": True, "file_path": str(outside)}
            with self.assertRaises(audio.Rejected):
                await audio.Speech(types.SimpleNamespace(speak=speak)).synthesize("hello")
            self.assertTrue(outside.exists())

    async def test_timeout_does_not_release_slot_until_worker_cleans_up(self):
        import threading
        entered, release, cleaned = threading.Event(), threading.Event(), threading.Event()
        def work():
            entered.set()
            try:
                release.wait(1)
            finally:
                cleaned.set()
        speech = audio.Speech(None)
        with self.assertRaises(audio.Rejected) as caught:
            await speech._work(work, 0.02)
        self.assertEqual(caught.exception.code, "speech_timeout")
        self.assertTrue(entered.is_set())
        self.assertEqual(len(speech.jobs), 1)
        release.set()
        await speech.close()
        self.assertTrue(cleaned.is_set())


if __name__ == "__main__":
    unittest.main()

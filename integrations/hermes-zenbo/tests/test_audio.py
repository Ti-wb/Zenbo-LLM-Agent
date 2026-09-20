import asyncio
import base64
import contextvars
import hashlib
import importlib
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch
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
    async def asyncSetUp(self):
        self.profile_audio = tempfile.TemporaryDirectory(prefix="zenbo-test-profile-audio-")
        self.addCleanup(self.profile_audio.cleanup)

    def tts_compat(self, speak):
        return types.SimpleNamespace(speak=speak, tts_output_dir=lambda: self.profile_audio.name,
                                     check_tts_output_path=lambda path: None)

    def test_wav_rejects_wrong_shape_and_truncated_payload(self):
        audio.validate_wav(wav_bytes(), 100)
        for payload, duration in ((wav_bytes()[:-4], 100), (wav_bytes(), 5000), (b"not audio", 100)):
            with self.assertRaises(audio.Rejected):
                audio.validate_wav(payload, duration)

    def test_artifact_scope_digest_ttl_and_atomic_batch(self):
        store = audio.ArtifactStore(ttl=100)
        manifest = store.add_many("robot", "device-1", "principal", [audio.prepare_audio(data) for data in [wav_bytes(), b"ID3audio"]])
        self.assertEqual([item["mimeType"] for item in manifest], ["audio/wav", "audio/mpeg"])
        self.assertNotIn("path", json.dumps(manifest))
        artifact = store.get(manifest[0]["artifactId"], "robot", "device-1", "principal")
        expected_hash = hashlib.sha256(wav_bytes()).digest()
        self.assertEqual(manifest[0]["sha256"], expected_hash.hex())
        self.assertEqual(store.digest(artifact), "sha-256=" + base64.b64encode(expected_hash).decode("ascii"))
        for identity in (("default", "device-1", "principal"), ("robot", "other", "principal"), ("robot", "device-1", "other")):
            with self.assertRaises(audio.Rejected):
                store.get(manifest[0]["artifactId"], *identity)
        with self.assertRaises(audio.Rejected):
            store.add_many("robot", "device-1", "principal", [audio.prepare_audio(wav_bytes()), b"unsupported"])
        self.assertEqual(len(store.items), 2)
        artifact.expires = time.time() - 1
        with self.assertRaises(audio.Rejected):
            store.get(manifest[0]["artifactId"], "robot", "device-1", "principal")

    def test_artifact_reads_reuse_digest_without_scanning_other_items(self):
        store = audio.ArtifactStore()
        manifest = store.add_many("robot", "device", "principal", [audio.prepare_audio(wav_bytes())])
        class NoScanDict(dict):
            def items(self):
                raise AssertionError("An artifact GET must not scan the store")
            def values(self):
                raise AssertionError("An artifact GET must not scan the store")
        store.items = NoScanDict(store.items)
        with patch.object(audio.hashlib, "sha256", side_effect=AssertionError("Audio must not be rehashed on GET")):
            for _ in range(3):
                item = store.get(manifest[0]["artifactId"], "robot", "device", "principal")
                self.assertEqual(store.digest(item), "sha-256=" + base64.b64encode(item.sha256).decode("ascii"))

    def test_capacity_rejection_is_atomic_and_expired_items_are_reclaimed(self):
        prepared = audio.prepare_audio(wav_bytes())
        store = audio.ArtifactStore(max_bytes=len(prepared.data) * 2)
        first = store.add_many("robot", "device", "principal", [prepared])[0]
        with self.assertRaises(audio.Rejected) as caught:
            store.add_many("robot", "device", "principal", [prepared, prepared])
        self.assertEqual(caught.exception.code, "audio_capacity_exceeded")
        self.assertEqual(list(store.items), [first["artifactId"]])
        store.items[first["artifactId"]].expires = time.time() - 1
        store.add_many("robot", "device", "principal", [prepared, prepared])
        self.assertEqual(len(store.items), 2)
        self.assertNotIn(first["artifactId"], store.items)

    async def test_stt_copies_context_and_deletes_original_on_success_and_failure(self):
        profile = contextvars.ContextVar("test_profile")
        profile.set("robot")
        paths = []
        def transcribe(path):
            self.assertEqual(profile.get(), "robot")
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
        speech = audio.Speech(self.tts_compat(speak))
        worker_threads = []
        real_hash = hashlib.sha256
        def checked_hash(data):
            worker_threads.append(threading.get_ident())
            self.assertNotEqual(threading.get_ident(), threading.main_thread().ident)
            return real_hash(data)
        with patch.object(audio.hashlib, "sha256", checked_hash):
            result = await speech.synthesize("你好")
        self.assertEqual([item.data for item in result], [wav_bytes(), b"ID3second"])
        self.assertEqual(len(worker_threads), 2)
        self.assertEqual([item.sha256 for item in result], [real_hash(item.data).digest() for item in result])
        self.assertTrue(all(not path.exists() for path in paths))

    async def test_tts_rejects_path_escape_and_does_not_delete_external_file(self):
        with tempfile.TemporaryDirectory() as external:
            outside = Path(external) / "outside.wav"
            outside.write_bytes(wav_bytes())
            def speak(text, output_path):
                return {"success": True, "file_path": str(outside)}
            with self.assertRaises(audio.Rejected):
                await audio.Speech(self.tts_compat(speak)).synthesize("hello")
            self.assertTrue(outside.exists())

    async def test_tts_temp_directory_uses_current_profile_audio_scope_and_is_removed(self):
        profile = contextvars.ContextVar("tts_test_profile")
        profile.set("robot")
        parent = Path(self.profile_audio.name).resolve() / "robot" / "audio_cache"
        paths = []
        def output_dir():
            self.assertEqual(profile.get(), "robot")
            return parent
        def speak(text, output_path):
            self.assertEqual(profile.get(), "robot")
            self.assertEqual(output_path.parent.parent, parent)
            self.assertEqual(output_path.parent.stat().st_mode & 0o777, 0o700)
            paths.append(output_path)
            output_path.write_bytes(wav_bytes())
            return {"success": True, "file_path": str(output_path)}
        checked = []
        speech = audio.Speech(types.SimpleNamespace(tts_output_dir=output_dir, speak=speak,
                                                    check_tts_output_path=lambda path: checked.append(path)))
        self.assertEqual([item.data for item in await speech.synthesize("你好")], [wav_bytes()])
        self.assertTrue(parent.is_dir())
        self.assertFalse(paths[0].parent.exists())
        self.assertEqual(checked, paths)

    async def test_tts_actual_candidate_denied_cleans_up_without_provider_call(self):
        paths, calls = [], []
        def guard(path):
            paths.append(path)
            raise RuntimeError("Denied by Hermes policy")
        compat = self.tts_compat(lambda *args: calls.append(args))
        compat.check_tts_output_path = guard
        with self.assertRaises(audio.Rejected):
            await audio.Speech(compat).synthesize("hello")
        self.assertEqual(calls, [])
        self.assertEqual(len(paths), 1)
        self.assertFalse(paths[0].parent.exists())

    async def test_tts_output_directory_error_has_no_system_temp_fallback(self):
        calls = []
        def output_dir():
            raise RuntimeError("private profile path")
        speech = audio.Speech(types.SimpleNamespace(tts_output_dir=output_dir,
                                                    speak=lambda *args: calls.append(args)))
        with self.assertRaises(audio.Rejected) as caught:
            await speech.synthesize("hello")
        self.assertEqual(caught.exception.code, "speech_failed")
        self.assertNotIn("private", str(caught.exception))
        self.assertEqual(calls, [])

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

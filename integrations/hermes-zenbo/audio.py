"""Reuse Hermes speech functions; expose only bounded opaque audio artifacts."""

import asyncio
import base64
from dataclasses import dataclass
import hashlib
import io
import json
from pathlib import Path
import tempfile
import time
import uuid
import wave

from .broker import Rejected, iso_time

MAX_UPLOAD = 2 * 1024 * 1024
MAX_ARTIFACT = 10 * 1024 * 1024
MAX_AUDIO_TOTAL = 32 * 1024 * 1024


def validate_wav(data, duration_ms):
    if not data or len(data) > MAX_UPLOAD:
        raise Rejected("invalid_audio", "WAV exceeds the upload limit")
    try:
        with wave.open(io.BytesIO(data), "rb") as wav:
            frames = wav.getnframes()
            duration = frames / wav.getframerate()
            valid = (wav.getnchannels() == 1 and wav.getsampwidth() == 2
                     and wav.getframerate() == 16000 and wav.getcomptype() == "NONE"
                     and 0 < duration <= 30 and len(wav.readframes(frames)) == frames * 2)
            if not valid or abs(duration * 1000 - duration_ms) > 100:
                raise ValueError()
    except (ValueError, wave.Error, EOFError, ZeroDivisionError):
        raise Rejected("invalid_audio", "Expected 16 kHz mono PCM16 WAV, at most 30 seconds") from None


def sniff_audio(data):
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        try:
            with wave.open(io.BytesIO(data), "rb") as wav:
                if wav.getnframes() <= 0 or wav.getcomptype() != "NONE":
                    raise ValueError()
        except (wave.Error, EOFError, ValueError):
            raise Rejected("invalid_speech_audio") from None
        return "audio/wav"
    if data.startswith(b"ID3") or len(data) >= 2 and data[0] == 0xFF and data[1] & 0xE0 == 0xE0:
        return "audio/mpeg"
    raise Rejected("unsupported_speech_audio", "Hermes TTS must produce WAV or MP3")


@dataclass
class Artifact:
    profile: str
    device: str
    principal: str
    data: bytes
    mime: str
    expires: float


class ArtifactStore:
    def __init__(self, ttl=1800, max_bytes=64 * 1024 * 1024):
        self.ttl, self.max_bytes = min(ttl, 1800), max_bytes
        self.items = {}

    def purge(self):
        now = time.time()
        self.items = {key: value for key, value in self.items.items() if value.expires > now}

    def add_many(self, profile, device, principal, audio):
        self.purge()
        if (not audio or len(audio) > 32 or any(not data or len(data) > MAX_ARTIFACT for data in audio)
                or sum(map(len, audio)) > MAX_AUDIO_TOTAL):
            raise Rejected("speech_audio_limit")
        if sum(len(item.data) for item in self.items.values()) + sum(map(len, audio)) > self.max_bytes:
            raise Rejected("audio_capacity_exceeded")
        # Validate the entire result before publishing any artifact.
        mimes = [sniff_audio(data) for data in audio]
        expires = time.time() + self.ttl
        output = []
        for data, mime in zip(audio, mimes):
            artifact_id = str(uuid.uuid4())
            self.items[artifact_id] = Artifact(profile, device, principal, data, mime, expires)
            output.append({"artifactId": artifact_id, "mimeType": mime,
                           "byteLength": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                           "expiresAt": iso_time(expires)})
        return output

    def get(self, artifact_id, profile, device, principal):
        self.purge()
        item = self.items.get(artifact_id)
        if not item or (item.profile, item.device, item.principal) != (profile, device, principal):
            raise Rejected("artifact_not_found")
        return item

    @staticmethod
    def digest(item):
        return "sha-256=" + base64.b64encode(hashlib.sha256(item.data).digest()).decode("ascii")


class Speech:
    def __init__(self, compat):
        self.compat = compat
        self.jobs = set()
        self.closed = False

    async def _work(self, operation, timeout):
        # A cancelled HTTP task cannot kill a provider thread. Keep the job slot
        # until its finally block cleans up; discard late results, never publish.
        if self.closed or len(self.jobs) >= 2:
            raise Rejected("speech_busy", "Speech processing is busy")
        task = asyncio.create_task(asyncio.to_thread(operation))
        self.jobs.add(task)
        def finished(done):
            self.jobs.discard(done)
            if not done.cancelled():
                done.exception()  # consume exceptions from timed-out/disconnected requests
        task.add_done_callback(finished)
        try:
            return await asyncio.wait_for(asyncio.shield(task), timeout)
        except asyncio.TimeoutError:
            raise Rejected("speech_timeout", "Speech processing timed out") from None
        except Rejected:
            raise
        except Exception:
            raise Rejected("speech_failed", "Hermes speech processing failed") from None

    async def transcribe(self, data, duration_ms):
        validate_wav(data, duration_ms)
        def work():
            with tempfile.TemporaryDirectory(prefix="hermes-zenbo-stt-") as directory:
                path = Path(directory) / "input.wav"
                path.write_bytes(data)
                try:
                    result = self.compat.transcribe(path)
                    if not isinstance(result, dict) or not result.get("success"):
                        raise Rejected("transcription_failed", "Hermes could not transcribe this audio")
                    text = result.get("transcript", result.get("text"))
                    if not isinstance(text, str) or len(text) > 32000:
                        raise Rejected("transcription_failed")
                    return text
                finally:
                    path.unlink(missing_ok=True)
        return await self._work(work, 90)

    async def synthesize(self, text):
        def work():
            # Resolve inside the worker's inherited profile context. The host's
            # file policy chooses the root; generic OS temp is never substituted.
            parent = Path(self.compat.tts_output_dir()).resolve()
            parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix="zenbo-", dir=parent) as directory:
                root = Path(directory).resolve()
                output_path = root / "speech.wav"
                self.compat.check_tts_output_path(output_path)
                result = self.compat.speak(text, output_path)
                result = json.loads(result) if isinstance(result, str) else result
                if not isinstance(result, dict) or not result.get("success"):
                    raise Rejected("synthesis_failed", "Hermes could not synthesize this answer")
                paths = result.get("file_paths") or [result.get("file_path")]
                if not isinstance(paths, list) or not 1 <= len(paths) <= 32:
                    raise Rejected("invalid_speech_audio")
                audio = []
                seen = set()
                for value in paths:
                    if not isinstance(value, str):
                        raise Rejected("invalid_speech_audio")
                    path = Path(value)
                    path = (root / path if not path.is_absolute() else path).resolve()
                    if not path.is_relative_to(root) or path in seen or not path.is_file():
                        raise Rejected("invalid_speech_audio")
                    seen.add(path)
                    if not 0 < path.stat().st_size <= MAX_ARTIFACT:
                        raise Rejected("speech_audio_limit")
                    # Bound actual read as well as stat, even if a provider rewrites the file.
                    with path.open("rb") as source:
                        data = source.read(MAX_ARTIFACT + 1)
                    if len(data) > MAX_ARTIFACT:
                        raise Rejected("speech_audio_limit")
                    sniff_audio(data)
                    audio.append(data)
                    if sum(map(len, audio)) > MAX_AUDIO_TOTAL:
                        raise Rejected("speech_audio_limit")
                return audio
        return await self._work(work, 120)

    async def close(self):
        self.closed = True
        # Background threads still own their temporary-directory finally cleanup.
        # Never cancel them and abandon the provider's temporary audio files.
        if self.jobs:
            await asyncio.wait(self.jobs, timeout=5)

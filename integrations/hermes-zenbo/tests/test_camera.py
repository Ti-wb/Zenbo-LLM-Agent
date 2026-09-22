"""Synthetic two-pixel JPEG fixture generated from fixed RGB bytes; no camera/user data."""

import base64
import hashlib
import importlib
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "zenbo_camera_tests"
package = types.ModuleType(PACKAGE)
package.__path__ = [str(ROOT)]
sys.modules[PACKAGE] = package
camera = importlib.import_module(PACKAGE + ".camera")
compat = importlib.import_module(PACKAGE + ".compat")
JPEG = base64.b64decode("/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAAqADAAQAAAABAAAAAQAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAAQACAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8PD//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQAAf/aAAwDAQACEQMRAD8A+Ebf/j3i/wB1f5VNUNv/AMe8X+6v8qmoA//Z")


def capture_output(data=JPEG):
    return {"accepted": True, "artifactId": "12345678-1234-4234-8234-123456789abc",
            "mimeType": "image/jpeg", "byteLength": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "width": 2, "height": 1, "capturedAt": "2026-09-22T00:00:00.000Z",
            "imageBase64": base64.b64encode(data).decode("ascii")}


def large_jpeg():
    # A bounded legal APP2 segment makes a real JPEG cross the 32 KiB control limit.
    padding = b"\xff\xe2" + (40002).to_bytes(2, "big") + b"x" * 40000
    return JPEG[:2] + padding + JPEG[2:]


class CameraTests(unittest.TestCase):
    def test_valid_jpeg_and_marker_payload_are_bounded(self):
        for data in (JPEG, large_jpeg()):
            output = capture_output(data)
            self.assertIs(camera.validate_capture(output), output)
            self.assertEqual(camera.jpeg_dimensions(data), (2, 1))

    def test_wrong_digest_size_format_shape_and_jpeg_dimensions_rejected(self):
        output = capture_output()
        for changes in ({"accepted": False}, {"mimeType": "image/png"}, {"artifactId": "arbitrary"},
                        {"capturedAt": "2026-09-22"}, {"sha256": "a" * 64}, {"sha256": "A" * 64},
                        {"byteLength": len(JPEG) + 1}, {"byteLength": True}, {"width": 3}, {"height": 1281},
                        {"imageBase64": output["imageBase64"] + "="}, {"imageBase64": "!" * 4},
                        {"imageBase64": "A" * 699056}, {"url": "https://untrusted.invalid/camera"}):
            with self.subTest(changes=list(changes)):
                with self.assertRaises(ValueError):
                    camera.validate_capture({**output, **changes})
        for data in (b"not an image", JPEG[:-2], JPEG + b"extra", JPEG[:10] + b"\xff\xd9"):
            with self.assertRaises(ValueError):
                camera.validate_capture(capture_output(data))
        scan = JPEG.index(b"\xff\xda")
        scan_end = scan + 2 + int.from_bytes(JPEG[scan + 2:scan + 4], "big")
        with self.assertRaises(ValueError):
            camera.validate_capture(capture_output(JPEG[:scan_end] + b"\xff\xd9"))
        oversized = JPEG[:2] + b"x" * (camera.MAX_IMAGE_BYTES + 1) + JPEG[2:]
        with self.assertRaises(ValueError):
            camera.validate_capture(capture_output(oversized))
        # SOF0 width belongs to the bytes, not to an asserted metadata value.
        malformed = bytearray(JPEG)
        at = malformed.index(b"\xff\xc0")
        malformed[at + 7:at + 9] = (1281).to_bytes(2, "big")
        with self.assertRaises(ValueError):
            camera.validate_capture(capture_output(bytes(malformed)))

    def test_image_is_native_content_and_fallback_never_leaks_bytes(self):
        output = capture_output()
        native = camera.model_result(output, True)
        self.assertIs(native["_multimodal"], True)
        self.assertEqual(native["content"][1], {"type": "image_url", "image_url": {
            "url": "data:image/jpeg;base64," + output["imageBase64"]}})
        fallback = camera.model_result(output, False)
        self.assertEqual(json.loads(fallback)["imageDelivery"], "image_not_delivered_to_model")
        self.assertNotIn(output["imageBase64"], fallback)
        self.assertNotIn("imageBase64", fallback)

    def test_compatibility_requires_envelope_support_and_current_model_native_vision(self):
        modules = {
            "tools.registry": types.SimpleNamespace(ToolRegistry=types.SimpleNamespace(
                _normalize_handler_result=lambda name, result: result)),
            "agent.tool_dispatch_helpers": types.SimpleNamespace(_is_multimodal_tool_result=lambda result: True),
            "tools.vision_tools": types.SimpleNamespace(_should_use_native_vision_fast_path=lambda: True),
        }
        with patch.object(compat.importlib, "import_module", side_effect=modules.__getitem__):
            self.assertTrue(compat.HermesCompat.camera_vision_supported())
            modules["tools.vision_tools"]._should_use_native_vision_fast_path = lambda: False
            self.assertFalse(compat.HermesCompat.camera_vision_supported())
            modules["tools.vision_tools"]._should_use_native_vision_fast_path = lambda: True
            modules["tools.registry"].ToolRegistry._normalize_handler_result = lambda name, result: json.dumps(result)
            self.assertFalse(compat.HermesCompat.camera_vision_supported())
        with patch.object(compat.importlib, "import_module", side_effect=ImportError):
            self.assertFalse(compat.HermesCompat.camera_vision_supported())

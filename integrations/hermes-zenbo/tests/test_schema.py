"""Check bundled allowlist semantics and strict validation without dependencies."""

import importlib.util
import json
from pathlib import Path
import unittest
from test_camera import capture_output


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("zenbo_schema_tests", ROOT / "schema.py")
schema = importlib.util.module_from_spec(spec)
spec.loader.exec_module(schema)


class SchemaTests(unittest.TestCase):
    def test_manifest_matches_normative_contract_semantically(self):
        contract = json.loads((ROOT.parents[1] / "contracts/hermes-zenbo/device-tools.json").read_text())
        self.assertEqual(schema.MANIFEST, contract)
        self.assertEqual(set(schema.TOOLS), {"get_system_status", "start_robot_following",
                         "stop_robot_following", "look_at_user", "show_emotion", "go_to_sleep", "move_robot", "capture_camera"})
        self.assertEqual(len(schema.MANIFEST["tools"]), 8)
        self.assertEqual({name for name, tool in schema.TOOLS.items() if tool["owner"] == "web"},
                         {"show_emotion", "go_to_sleep"})

    def test_all_eight_inputs_and_results(self):
        inputs = {"get_system_status": {}, "start_robot_following": {"enablePreview": False},
                  "stop_robot_following": {}, "look_at_user": {"doa": -15.5},
                  "show_emotion": {"emotion": "HAPPY", "durationMs": 30000}, "go_to_sleep": {}, "move_robot": {"direction": "forward"}, "capture_camera": {}}
        outputs = {"get_system_status": {"accepted": True, "robotReady": True, "moving": False,
                                        "androidSdk": 23, "robotModel": "Zenbo K"},
                   "start_robot_following": {"accepted": True}, "stop_robot_following": {"accepted": False},
                   "look_at_user": {"accepted": True},
                   "show_emotion": {"ok": True, "emotion": "HAPPY", "durationMs": 0},
                   "go_to_sleep": {"ok": True, "sleeping": True},
                   "move_robot": {"accepted": True}, "capture_camera": capture_output()}
        for name, tool in schema.TOOLS.items():
            for value, key in ((inputs[name], "inputSchema"), (outputs[name], "resultSchema")):
                with self.subTest(tool=name, schema=key):
                    self.assertTrue(schema.validate(value, tool[key]))
                    self.assertFalse(schema.validate({**value, "unknown": True}, tool[key]))
                    self.assertFalse(schema.validate([], tool[key]))
                    for required in tool[key]["required"]:
                        self.assertFalse(schema.validate({k: v for k, v in value.items() if k != required}, tool[key]))

    def test_booleans_are_not_numbers_or_strings(self):
        for value in (0, 1, "true", "false", None):
            self.assertFalse(schema.validate(value, {"type": "boolean"}))
        for value in (True, False):
            self.assertTrue(schema.validate(value, {"type": "boolean"}))
            self.assertFalse(schema.validate(value, {"type": "number"}))
            self.assertFalse(schema.validate(value, {"type": "integer"}))

    def test_doa_requires_finite_number_in_inclusive_range(self):
        rule = schema.TOOLS["look_at_user"]["inputSchema"]
        for value in (-180, -179.5, 0, 180):
            self.assertTrue(schema.validate({"doa": value}, rule))
        for value in (-180.01, 180.01, 10 ** 400, -(10 ** 400), float("nan"),
                      float("inf"), -float("inf"), True, "0", None):
            self.assertFalse(schema.validate({"doa": value}, rule))

    def test_emotion_enum_and_duration_are_strict(self):
        rule = schema.TOOLS["show_emotion"]["inputSchema"]
        for emotion in ("NEUTRAL", "HAPPY", "CURIOUS", "CONCERNED", "EXCITED"):
            self.assertTrue(schema.validate({"emotion": emotion}, rule))
        for duration in (0, 30000):
            self.assertTrue(schema.validate({"emotion": "HAPPY", "durationMs": duration}, rule))
        for duration in (-1, 30001, 1.0, True, "5"):
            self.assertFalse(schema.validate({"emotion": "HAPPY", "durationMs": duration}, rule))
        for emotion in ("happy", "ANGRY", "", None):
            self.assertFalse(schema.validate({"emotion": emotion}, rule))

    def test_status_sdk_requires_integer_and_strings_are_bounded(self):
        rule = schema.TOOLS["get_system_status"]["resultSchema"]
        valid = {"accepted": True, "robotReady": False, "moving": False,
                 "androidSdk": 23, "robotModel": "Zenbo K"}
        for value in (True, 23.0, "23"):
            self.assertFalse(schema.validate({**valid, "androidSdk": value}, rule))
        self.assertFalse(schema.validate({**valid, "robotModel": "x" * 1025}, rule))

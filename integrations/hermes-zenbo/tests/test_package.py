import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("zenbo_packager", ROOT / "scripts" / "package-hermes-zenbo.py")
packager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packager)


class PackageTests(unittest.TestCase):
    @unittest.skipIf(importlib.util.find_spec("aiohttp") is None,
                     "aiohttp is required for extracted runtime module imports")
    def test_source_only_archive_extracts_and_registers_all_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "plugin.zip"
            packager.build_package(ROOT, output)
            extracted = Path(directory) / "installed"
            with zipfile.ZipFile(output) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(set(archive.namelist()), {"zenbo/" + name for name in packager.FILES} | {"zenbo/LICENSE"})
                self.assertIn("zenbo/camera.py", archive.namelist())
                for name in archive.namelist():
                    self.assertNotIn("__pycache__", name)
                    self.assertFalse(name.endswith((".pyc", ".jar", ".apk")))
                archive.extractall(extracted)
            # A fresh interpreter cannot reuse test imports or resolve modules
            # from the source checkout. Hermes callbacks are synthetic; this
            # verifies packaging, not compatibility with an installed gateway.
            result = subprocess.run([sys.executable, "-I", "-c", """
import importlib
import json
from pathlib import Path
import sys
import types
root = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root))
import zenbo
assert Path(zenbo.__file__).resolve().is_relative_to(root)
for path in sorted((root / "zenbo").glob("*.py")):
    if path.stem != "__init__":
        module = importlib.import_module("zenbo." + path.stem)
        assert Path(module.__file__).resolve().is_relative_to(root)
registered, factories, cleanup = [], [], []
zenbo.register(types.SimpleNamespace(
    register_tool=lambda **kwargs: registered.append(kwargs),
    register_platform_handler=lambda *args: factories.append(args),
    on_unload=lambda callback: cleanup.append(callback)))
expected = json.loads((root / "zenbo" / "device-tools.json").read_text())["tools"]
assert {tool["name"] for tool in registered} == {tool["name"] for tool in expected}
assert len(registered) == 8
assert factories[0][0] == "api_server"
assert len(cleanup) == 1
assert all("plugin_unavailable" in tool["handler"]({}, session_id="fixture") for tool in registered)
""", str(extracted)], cwd=directory, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_direct_or_deferred_runtime_import_rejects_package(self):
        for missing in ("camera.py", "routes.py"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "plugin.zip"
                with patch.object(packager, "FILES", tuple(name for name in packager.FILES if name != missing)):
                    with self.assertRaisesRegex(ValueError, "Relative import .* is missing from package"):
                        packager.build_package(ROOT, output)
                self.assertFalse(output.exists())

    def test_manifest_mismatch_preserves_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(ROOT / "integrations" / "hermes-zenbo", root / "integrations" / "hermes-zenbo")
            contract = root / "contracts" / "hermes-zenbo" / "device-tools.json"
            contract.parent.mkdir(parents=True)
            contract.write_text("{}")
            output = root / "plugin.zip"
            output.write_bytes(b"existing package")
            with self.assertRaisesRegex(ValueError, "differs from the normative contract"):
                packager.build_package(root, output)
            self.assertEqual(output.read_bytes(), b"existing package")

    def test_optional_local_preflight_is_explicitly_bundled(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            preflight = root / "check.py"
            preflight.write_text("# synthetic read-only compatibility helper\n")
            output = root / "plugin.zip"
            packager.build_package(ROOT, output, preflight)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(archive.read("zenbo/preflight.py"), preflight.read_bytes())


if __name__ == "__main__":
    unittest.main()

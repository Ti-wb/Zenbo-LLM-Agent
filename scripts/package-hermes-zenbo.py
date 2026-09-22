#!/usr/bin/env python3
"""Create a source-only Zenbo plugin ZIP after contract and import checks."""

import argparse
import ast
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile

FILES = ("plugin.yaml", "__init__.py", "plugin.py", "compat.py", "schema.py",
         "broker.py", "audio.py", "camera.py", "routes.py", "device-tools.json", "README.md")


def validate_relative_imports(sources):
    """Check deferred imports as well as imports executed by __init__.py."""
    for name, content in sources.items():
        if not name.endswith(".py"):
            continue
        for node in ast.walk(ast.parse(content, filename=name)):
            if not isinstance(node, ast.ImportFrom) or not node.level:
                continue
            modules = [node.module] if node.module else [alias.name for alias in node.names]
            for module in modules:
                path = module.replace(".", "/")
                # Runtime modules currently live directly inside zenbo/. An
                # import outside that package cannot be supplied by this ZIP.
                if node.level != 1 or not ({path + ".py", path + "/__init__.py"} & sources.keys()):
                    raise ValueError(f"Relative import {'.' * node.level}{module} in {name} is missing from package")


def build_package(repository_root, output, preflight=None):
    root = repository_root / "integrations" / "hermes-zenbo"
    sources = {name: (root / name).read_bytes() for name in FILES}
    normative = repository_root / "contracts" / "hermes-zenbo" / "device-tools.json"
    if json.loads(normative.read_bytes()) != json.loads(sources["device-tools.json"]):
        raise ValueError("Bundled device manifest differs from the normative contract")
    validate_relative_imports(sources)
    sources["LICENSE"] = (repository_root / "LICENSE").read_bytes()
    if preflight is not None:
        sources["preflight.py"] = preflight.read_bytes()

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".zip", delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(temporary_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in sources.items():
                archive.writestr("zenbo/" + name, content)
        with zipfile.ZipFile(temporary_path) as archive:
            if archive.testzip() is not None:
                raise ValueError("Plugin ZIP failed its CRC check")
        temporary_path.replace(output)
    finally:
        temporary_path.unlink(missing_ok=True)
    return {"file": str(output.resolve()), "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--preflight", type=Path, help="Optional local read-only compatibility helper to bundle")
    args = parser.parse_args()
    try:
        result = build_package(Path(__file__).resolve().parents[1], args.output, args.preflight)
    except (OSError, ValueError, SyntaxError) as exc:
        parser.exit(1, f"Packaging failed: {exc}\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()

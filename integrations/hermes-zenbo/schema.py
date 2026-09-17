"""The bundled allowlist is checked against the repository's normative manifest."""

import json
import math
from pathlib import Path

MANIFEST = json.loads(Path(__file__).with_name("device-tools.json").read_text())
TOOLS = {tool["name"]: tool for tool in MANIFEST["tools"]}


def validate(value, schema):
    kind = schema["type"]
    if kind == "object":
        if not isinstance(value, dict):
            return False
        properties = schema.get("properties", {})
        return (not (set(schema.get("required", [])) - value.keys())
                and not (value.keys() - properties.keys())
                and all(validate(item, properties[key]) for key, item in value.items()))
    valid_type = {
        "boolean": lambda: type(value) is bool,
        "integer": lambda: type(value) is int,
        "number": lambda: type(value) is int or type(value) is float and math.isfinite(value),
        "string": lambda: isinstance(value, str) and len(value) <= 1024,
    }.get(kind)
    if valid_type is None or not valid_type():
        return False
    return (("enum" not in schema or value in schema["enum"])
            and ("minimum" not in schema or value >= schema["minimum"])
            and ("maximum" not in schema or value <= schema["maximum"]))

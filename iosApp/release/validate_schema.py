#!/usr/bin/env python3
"""Validates the AltStore source against its JSON schema (needs the `jsonschema` package, which CI
installs; altstore_source.py itself needs only the standard library):

    python3 -I iosApp/release/validate_schema.py [source.json [source.schema.json]]
"""

import json
import sys

try:
    import jsonschema
except ImportError:
    raise SystemExit("validate_schema: install jsonschema first (pip install jsonschema)")


def main(argv):
    source_path = argv[0] if argv else "altstore/source.json"
    schema_path = argv[1] if len(argv) > 1 else "altstore/source.schema.json"
    try:
        with open(schema_path, encoding="utf-8") as file:
            schema = json.load(file)
        with open(source_path, encoding="utf-8") as file:
            source = json.load(file)
    except (OSError, ValueError) as error:
        raise SystemExit(f"validate_schema: cannot read the files: {error}")
    jsonschema.Draft7Validator.check_schema(schema)
    errors = sorted(jsonschema.Draft7Validator(schema).iter_errors(source), key=lambda e: list(e.path))
    for error in errors:
        where = "/".join(str(p) for p in error.path) or "(top)"
        print(f"{source_path}: {where}: {error.message}", file=sys.stderr)
    if errors:
        raise SystemExit(f"validate_schema: {len(errors)} problem(s)")
    print(f"{source_path} matches {schema_path}")


if __name__ == "__main__":
    main(sys.argv[1:])

#!/usr/bin/env python3
"""Validate processor contract schemas, verifier manifests, and protocol examples.

This deliberately uses only the Python standard library so the contract can be
checked before the backend or ML dependencies are installed. It implements the
JSON Schema keywords used by this repository, plus project-specific invariants that
are clearer as executable rules than as conditional schema branches.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "backend" / "contracts"
PROCESSORS = ROOT / "backend" / "processors"


class ContractValidationError(Exception):
    pass


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ContractValidationError(
            f"{path.relative_to(ROOT)}: invalid JSON at line {exc.lineno}, column {exc.colno}: {exc.msg}"
        ) from exc


def type_matches(value: Any, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "null":
        return value is None
    raise ContractValidationError(f"validator does not support JSON Schema type {expected!r}")


def resolve_pointer(root_schema: dict[str, Any], reference: str) -> dict[str, Any]:
    if not reference.startswith("#/"):
        raise ContractValidationError(f"validator supports only local JSON pointers, received {reference!r}")
    node: Any = root_schema
    for raw_part in reference[2:].split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            raise ContractValidationError(f"unresolved schema reference {reference!r}")
        node = node[part]
    if not isinstance(node, dict):
        raise ContractValidationError(f"schema reference {reference!r} does not resolve to an object")
    return node


def collect_errors(value: Any, schema: dict[str, Any], path: str, root_schema: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    if "$ref" in schema:
        return collect_errors(value, resolve_pointer(root_schema, schema["$ref"]), path, root_schema)

    if "allOf" in schema:
        for candidate in schema["allOf"]:
            errors.extend(collect_errors(value, candidate, path, root_schema))

    if "anyOf" in schema:
        matches = [collect_errors(value, candidate, path, root_schema) for candidate in schema["anyOf"]]
        if not any(not candidate_errors for candidate_errors in matches):
            errors.append(f"{path}: does not match any allowed schema")
            return errors

    if "oneOf" in schema:
        matches = [collect_errors(value, candidate, path, root_schema) for candidate in schema["oneOf"]]
        if sum(not candidate_errors for candidate_errors in matches) != 1:
            errors.append(f"{path}: must match exactly one allowed schema")
            return errors

    if "const" in schema and value != schema["const"]:
        errors.append(f"{path}: expected constant {schema['const']!r}, received {value!r}")

    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: {value!r} is not one of {schema['enum']!r}")

    expected_type = schema.get("type")
    if expected_type is not None:
        allowed_types = expected_type if isinstance(expected_type, list) else [expected_type]
        if not any(type_matches(value, item) for item in allowed_types):
            errors.append(f"{path}: expected type {allowed_types!r}, received {type(value).__name__}")
            return errors

    if isinstance(value, dict):
        required = schema.get("required", [])
        for name in required:
            if name not in value:
                errors.append(f"{path}: missing required property {name!r}")

        properties = schema.get("properties", {})
        additional = schema.get("additionalProperties", True)
        for name, child in value.items():
            child_path = f"{path}.{name}"
            if name in properties:
                errors.extend(collect_errors(child, properties[name], child_path, root_schema))
            elif additional is False:
                errors.append(f"{child_path}: additional property is not allowed")
            elif isinstance(additional, dict):
                errors.extend(collect_errors(child, additional, child_path, root_schema))

        if "minProperties" in schema and len(value) < schema["minProperties"]:
            errors.append(f"{path}: requires at least {schema['minProperties']} properties")

    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(f"{path}: requires at least {schema['minItems']} items")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            errors.append(f"{path}: permits at most {schema['maxItems']} items")
        if schema.get("uniqueItems"):
            serialised = [json.dumps(item, sort_keys=True) for item in value]
            if len(serialised) != len(set(serialised)):
                errors.append(f"{path}: items must be unique")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, child in enumerate(value):
                errors.extend(collect_errors(child, item_schema, f"{path}[{index}]", root_schema))

    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{path}: must contain at least {schema['minLength']} characters")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append(f"{path}: must contain at most {schema['maxLength']} characters")
        if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
            errors.append(f"{path}: {value!r} does not match pattern {schema['pattern']!r}")

    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{path}: must be >= {schema['minimum']}")
        if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
            errors.append(f"{path}: must be > {schema['exclusiveMinimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{path}: must be <= {schema['maximum']}")
        if "exclusiveMaximum" in schema and value >= schema["exclusiveMaximum"]:
            errors.append(f"{path}: must be < {schema['exclusiveMaximum']}")

    return errors


def validate_instance(instance: Any, schema: dict[str, Any], label: str) -> list[str]:
    return collect_errors(instance, schema, label, schema)


def duplicate_values(items: list[dict[str, Any]], key: str) -> list[str]:
    values = [str(item[key]) for item in items]
    return sorted({value for value in values if values.count(value) > 1})


def manifest_invariants(manifest: dict[str, Any], label: str) -> list[str]:
    errors: list[str] = []
    lifecycle = manifest["lifecycle"]
    if lifecycle["requirements_status"] == "draft" and not lifecycle.get("open_questions"):
        errors.append(f"{label}.lifecycle: draft requirements must list at least one open question")
    if lifecycle["status"] == "available" and lifecycle["requirements_status"] != "verified":
        errors.append(f"{label}.lifecycle: an available processor must have verified requirements")

    for duplicate in duplicate_values(manifest["inputs"], "name"):
        errors.append(f"{label}.inputs: duplicate input name {duplicate!r}")
    for duplicate in duplicate_values(manifest["outputs"], "name"):
        errors.append(f"{label}.outputs: duplicate output name {duplicate!r}")
    for duplicate in duplicate_values(manifest["preprocessing"], "step_id"):
        errors.append(f"{label}.preprocessing: duplicate step_id {duplicate!r}")

    for index, input_spec in enumerate(manifest["inputs"]):
        sampling = input_spec["sampling"]
        input_label = f"{label}.inputs[{index}].sampling"
        if sampling["source_rate_policy"] == "exact" and "source_rate_hz" not in sampling:
            errors.append(f"{input_label}: exact source rate requires source_rate_hz")
        if sampling["source_rate_policy"] == "range":
            if "minimum_source_rate_hz" not in sampling or "maximum_source_rate_hz" not in sampling:
                errors.append(f"{input_label}: range policy requires minimum and maximum source rates")
            elif sampling["minimum_source_rate_hz"] > sampling["maximum_source_rate_hz"]:
                errors.append(f"{input_label}: minimum source rate exceeds maximum source rate")
        if sampling["target_rate_policy"] == "exact" and "target_rate_hz" not in sampling:
            errors.append(f"{input_label}: exact target rate requires target_rate_hz")
        if "target_rate_hz" in sampling and "target_sample_count" in sampling:
            duration_ms = input_spec["window"]["duration_ms"]
            expected = round(sampling["target_rate_hz"] * duration_ms / 1000)
            if expected != sampling["target_sample_count"]:
                errors.append(
                    f"{input_label}: target_sample_count is {sampling['target_sample_count']}, expected {expected} from rate and duration"
                )

    if not all(manifest["reproducibility"].values()):
        errors.append(f"{label}.reproducibility: all declared controls must be enabled for thesis verification")

    return errors


def main() -> int:
    manifest_schema_path = CONTRACTS / "processor-manifest.schema.json"
    request_schema_path = CONTRACTS / "processor-run-request.schema.json"
    result_schema_path = CONTRACTS / "processor-run-result.schema.json"

    errors: list[str] = []
    schemas = {
        "manifest": load_json(manifest_schema_path),
        "request": load_json(request_schema_path),
        "result": load_json(result_schema_path),
    }

    generic_schema_text = json.dumps(schemas["manifest"]).lower()
    for forbidden_name in ("papagei", "moment", "handcrafted"):
        if forbidden_name in generic_schema_text:
            errors.append(f"processor manifest schema must remain implementation-agnostic; found {forbidden_name!r}")

    manifest_files = sorted(PROCESSORS.glob("*.json"))
    if not manifest_files:
        errors.append("no processor manifests found")

    identities: set[tuple[str, str]] = set()
    adapter_ids: set[str] = set()
    for path in manifest_files:
        label = str(path.relative_to(ROOT))
        manifest = load_json(path)
        errors.extend(validate_instance(manifest, schemas["manifest"], label))
        if not validate_instance(manifest, schemas["manifest"], label):
            errors.extend(manifest_invariants(manifest, label))
            identity = (manifest["processor"]["id"], manifest["processor"]["version"])
            if identity in identities:
                errors.append(f"{label}: duplicate processor identity {identity!r}")
            identities.add(identity)
            adapter_id = manifest["execution"]["adapter_id"]
            if adapter_id in adapter_ids:
                errors.append(f"{label}: duplicate adapter_id {adapter_id!r}")
            adapter_ids.add(adapter_id)

    request_path = CONTRACTS / "examples" / "run-request.example.json"
    result_path = CONTRACTS / "examples" / "run-result.example.json"
    request = load_json(request_path)
    result = load_json(result_path)
    errors.extend(validate_instance(request, schemas["request"], str(request_path.relative_to(ROOT))))
    errors.extend(validate_instance(result, schemas["result"], str(result_path.relative_to(ROOT))))

    if request.get("run_id") != result.get("run_id"):
        errors.append("protocol examples: request and result run_id values differ")
    if request.get("processor_ref", {}).get("id") != result.get("processor_ref", {}).get("id"):
        errors.append("protocol examples: request and result processor ids differ")
    if request.get("processor_ref", {}).get("version") != result.get("processor_ref", {}).get("version"):
        errors.append("protocol examples: request and result processor versions differ")

    if errors:
        print(f"Processor contract validation failed with {len(errors)} error(s):", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"Validated {len(schemas)} contract schemas.")
    print(f"Validated {len(manifest_files)} processor manifests.")
    print("Validated generic run request/result examples and cross-envelope identity.")
    print("Confirmed the manifest schema contains no verifier-specific names.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

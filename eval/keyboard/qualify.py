#!/usr/bin/env python3
"""Fail-closed release router. Contract integrity is not consumer qualification.

A criterion cannot pass until a criterion-specific consumer provider is implemented,
negative-controlled and reviewed. Arbitrary pass JSON or layer labels are not proof.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVIDERS = {}  # Add actual consumer verifiers via a recorded Oracle Change Note, never receipt passthrough.


def immutable_criteria(criteria):
    return [{k: v for k, v in row.items() if k not in ("status", "last_verification", "verifier_status")} for row in criteria]


def check_contract(root=ROOT):
    contract = json.loads((root / "loop/acceptance.json").read_text())
    lock = json.loads((root / "loop/contract-lock.json").read_text())
    failures = []
    if contract["goal_version"] != lock["goal_version"]:
        failures.append("goal version differs")
    for name, digest in lock["sha256"].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
            failures.append("scope source changed: " + name)
    digest = hashlib.sha256(json.dumps(immutable_criteria(contract["criteria"]), sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if digest != lock["immutable_criteria_sha256"]:
        failures.append("immutable criteria changed")
    immutable = {**contract, "criteria": immutable_criteria(contract["criteria"])}
    digest = hashlib.sha256(json.dumps(immutable, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if digest != lock["immutable_contract_sha256"]:
        failures.append("immutable root controls or criteria changed")
    return contract, failures


def qualify(criterion, *, root=ROOT):
    contract, failures = check_contract(root)
    rows = [r for r in contract["criteria"] if criterion is None or r["id"] == criterion]
    if not rows:
        failures.append("unknown criterion")
    results = []
    for row in rows:
        provider = PROVIDERS.get(row["id"])
        if provider is None:
            results.append({"criterion": row["id"], "status": "NOT_RUN", "reason": "Consumer verifier not yet implemented/qualified", "required_cases": row["required_cases"]})
        else:
            results.append(provider(row, root))
    passed = bool(results) and not failures and all(r["status"] == "PASS" for r in results)
    return {"goal_version": contract["goal_version"], "qualified": passed, "contract_failures": failures, "results": results}


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true")
    group.add_argument("--criterion")
    args = parser.parse_args()
    result = qualify(args.criterion)
    print(json.dumps(result, indent=2))
    return 0 if result["qualified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

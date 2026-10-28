#!/usr/bin/env python3
"""Bounded read-only archive identity/non-activation check; no legacy imports/jobs."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE = ROOT / "research/legacy"


def main():
    manifest = json.loads((ARCHIVE / "manifest.json").read_text())
    assert manifest["schemaVersion"] == 1
    assert manifest["consolidationMain"] == "13d87e4439d38811a4c82a8bab747f1d7443d88c"
    rows = manifest["files"]
    actual_files = set()
    total = 0
    head_availability = {}
    locally_bound = 0
    for row in rows:
        name = row["archivePath"]
        relative = PurePosixPath(name)
        assert not relative.is_absolute() and ".." not in relative.parts
        assert relative.parts[:2] == ("research", "legacy")
        assert name.endswith(".reference") and relative.parts[2].startswith("pr-")
        assert name not in actual_files
        actual_files.add(name)
        path = ROOT / name
        for parent in (path, *path.parents):
            assert not parent.is_symlink(), name
            if parent == ROOT:
                break
        mode = path.stat().st_mode
        assert stat.S_ISREG(mode) and mode & 0o111 == 0, name
        data = path.read_bytes()
        data.decode("utf-8")
        assert b"\0" not in data
        assert len(data) == row["bytes"], name
        assert hashlib.sha256(data).hexdigest() == row["sha256"], name
        git_blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        assert git_blob == row["gitBlobSha"], name
        head = row["headCommit"]
        if head not in head_availability:
            head_availability[head] = subprocess.run(
                ["git", "cat-file", "-e", head + "^{commit}"], cwd=ROOT,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            ).returncode == 0
        if head_availability[head]:
            bound_blob = subprocess.check_output(
                ["git", "rev-parse", head + ":" + row["originalPath"]], cwd=ROOT
            ).decode().strip()
            assert bound_blob == git_blob, name
            locally_bound += 1
        assert row["redacted"] is False
        total += len(data)
    originals = {row["originalPath"] for row in manifest["changedPathDispositions"]
                 if row["auditBinding"] == "head_path_absent_from_main"}
    absent_variants = [row for row in manifest["changedPathDispositions"]
                       if row["auditBinding"] == "head_path_absent_from_main"]
    assert len(originals) == 45 and len(absent_variants) == 52
    assert all(row["archivePath"] in actual_files for row in absent_variants)
    assert manifest["counts"] == dict(distinctAbsentOriginalPaths=45,
                                     absentHeadVariants=52,
                                     archivedFiles=len(rows), archivedBytes=total)
    expected = actual_files | {"research/legacy/manifest.json", "research/legacy/check_archive.py"}
    observed = {p.relative_to(ROOT).as_posix() for p in ARCHIVE.rglob("*") if p.is_file()}
    assert observed == expected, (observed - expected, expected - observed)
    # Neither extension-based default test discovery nor workflow registration sees
    # references. Only this new checker remains Python, and it is not a test module.
    assert not list(ARCHIVE.rglob("test_*.py"))
    assert not list(ARCHIVE.rglob("*.yml")) and not list(ARCHIVE.rglob("*.yaml"))
    assert not list(ARCHIVE.rglob("__init__.py"))
    configs = [ROOT / "settings.gradle.kts", ROOT / "build.gradle.kts",
               ROOT / "app/build.gradle.kts", ROOT / "app/src/main/cpp/CMakeLists.txt",
               *sorted((ROOT / ".github/workflows").glob("*"))]
    for path in configs:
        if path.is_file():
            assert "research/legacy" not in path.read_text(), path
    print(f"PASS: {len(rows)} exact text references, {total} bytes; all 45 absent paths/52 head variants bound; no active registration. No legacy code/test/job executed.")
    print(f"Original-head Git bindings checked locally: {locally_bound}/{len(rows)} (missing historical objects are not fetched; byte/SHA/blob checks always apply).")


if __name__ == "__main__":
    main()

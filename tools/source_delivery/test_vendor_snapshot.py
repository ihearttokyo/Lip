"""Tiny synthetic source snapshot controls; no model/native build/Git mutation."""
import hashlib
import types
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[2] / "third_party/starling/scripts/verify_lip_vendor.py"
# Compile/execute the trusted helper directly: unlike SourceFileLoader imports,
# this never creates scripts/__pycache__, even when the test runs without -B.
module = types.ModuleType("verify_lip_vendor")
module.__file__ = str(SCRIPT)
exec(compile(SCRIPT.read_bytes(), str(SCRIPT), "exec"), module.__dict__)


class VendorSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lip-vendor-control-")
        self.root = Path(self.temp.name)
        self.files = {name: (name + "\n").encode() for name in module.REQUIRED}
        self.files["cpp/runtime/backend.cpp"] = b"// synthetic source\n"
        for name, data in self.files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.data = {"schema": 1, "upstream": {"starling": "a" * 40, "ggml": "b" * 40},
                     "files": {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                               for name, data in self.files.items()}}
        self.publish()

    def tearDown(self):
        self.temp.cleanup()  # Exact owned synthetic TemporaryDirectory only.

    def publish(self):
        (self.root / module.MANIFEST).write_text(json.dumps(self.data), encoding="utf-8")

    def rejects(self):
        with self.assertRaises((module.VerificationError, OSError)):
            module.verify(self.root)

    def test_valid_and_read_only(self):
        before = {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(module.verify(self.root)[0], len(self.files))
        self.assertEqual(before, {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_missing_member(self):
        (self.root / "cpp/runtime/backend.cpp").unlink(); self.rejects()

    def test_same_size_modified_bytes(self):
        (self.root / "cpp/runtime/backend.cpp").write_bytes(b"x" * len(self.files["cpp/runtime/backend.cpp"])); self.rejects()

    def test_wrong_size(self):
        (self.root / "cpp/runtime/backend.cpp").write_bytes(b"short"); self.rejects()

    def test_added_glob_source_and_header_and_template(self):
        for name in ("cpp/runtime/extra.cpp", "cpp/runtime/extra.hpp", "third_party/ggml/src/extra.in", "extra-no-extension"):
            path = self.root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("synthetic")
            self.rejects(); path.unlink()

    def test_traversal_absolute_and_windows_paths(self):
        for name in ("../outside.cpp", "/outside.cpp", "cpp/../bad.cpp", "cpp//bad.cpp", "C:/outside.cpp", "cpp\\bad.cpp"):
            self.data["files"][name] = {"bytes": 0, "sha256": hashlib.sha256(b"").hexdigest()}
            self.publish(); self.rejects(); del self.data["files"][name]

    def test_leaf_symlink(self):
        path = self.root / "cpp/runtime/backend.cpp"; path.unlink(); path.symlink_to(self.root / "cpp/include/starling_ggml.h"); self.rejects()

    def test_parent_directory_symlink(self):
        runtime = self.root / "cpp/runtime"; (runtime / "backend.cpp").unlink(); runtime.rmdir()
        runtime.symlink_to(self.root / "cpp/include", target_is_directory=True); self.rejects()

    def test_extra_symlink_directory(self):
        (self.root / "injected").symlink_to(self.root / "cpp", target_is_directory=True); self.rejects()

    def test_schema_pins_size_types_and_self_membership(self):
        mutations = [("schema", True), ("upstream", {"starling": "bad", "ggml": "b" * 40})]
        for key, value in mutations:
            old = self.data[key]; self.data[key] = value; self.publish(); self.rejects(); self.data[key] = old
        self.data["files"][module.MANIFEST] = {"bytes": 0, "sha256": "0" * 64}; self.publish(); self.rejects()

    def test_duplicate_json_key(self):
        path = self.root / module.MANIFEST
        path.write_text('{"schema":1,"schema":1,"upstream":{},"files":{}}'); self.rejects()

    def test_scandir_failure_is_not_silently_skipped(self):
        original = module.os.scandir
        def broken(directory):
            if Path(directory).resolve() == (self.root / "cpp").resolve():
                raise OSError("synthetic enumeration failure")
            return original(directory)
        with mock.patch.object(module.os, "scandir", side_effect=broken):
            with self.assertRaisesRegex(module.VerificationError, "cannot enumerate source tree"):
                module.verify(self.root)

    def test_ignored_git_and_output_not_arbitrary_source_build_dir(self):
        for name in (".git/index", "build/CMakeCache.txt", "third_party/ggml/build-debug/generated.cpp"):
            path = self.root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("unpublished output")
        self.assertEqual(module.verify(self.root)[0], len(self.files))
        path = self.root / "cpp/runtime/build/hidden.cpp"; path.parent.mkdir(); path.write_text("unlisted")
        self.rejects()


if __name__ == "__main__":
    unittest.main()

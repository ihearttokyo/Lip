"""Real CI/static-guard fixtures; no SDK, native compilation, or inference."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "app/src/main/cpp/check_wrapper.py"
CMAKE = CHECKER.with_name("CMakeLists.txt")
INCLUDE = "include(patches/whisper-scheduler-abort.cmake)"
TARGETS = {"arm64-v8a": "aarch64-none-linux-android33",
           "x86_64": "x86_64-none-linux-android33"}


class NativeBuildGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        step = workflow.split("      - name: Verify compiled cancellation patch in both ABIs\n", 1)[1]
        cls.guard = textwrap.dedent(step.split("          python3 - <<'PY'\n", 1)[1]
                                   .split("          PY\n", 1)[0])
        cls.checker = CHECKER.read_text()
        cls.cmake = CMAKE.read_text()
        patches = CHECKER.parent / "patches"
        lines = (ROOT / "third_party/whisper.cpp/src/whisper.cpp").read_text().splitlines(keepends=True)
        # shortcut: only the pinned zero-context patch; extend if its format changes.
        hunks = re.split(r"^@@ -(\d+)(?:,(\d+))? \+\d+(?:,\d+)? @@\n",
                         (patches / "whisper-scheduler-abort.patch").read_text(), flags=re.M)
        for start, count, body in reversed(list(zip(hunks[1::3], hunks[2::3], hunks[3::3]))):
            removed = [line[1:] for line in body.splitlines(keepends=True) if line.startswith("-")]
            added = [line[1:] for line in body.splitlines(keepends=True) if line.startswith("+")]
            count = int(count or "1")
            index = int(start) - (count != 0)
            assert len(removed) == count and lines[index:index + count] == removed
            lines[index:index + count] = added
        cls.patched = "".join(lines)
        cls.private = ROOT / "validation/hillclimb/physical-phone/native-guard-repair"
        cls.private.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="eval-", dir=self.private)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        checker = self.root / "app/src/main/cpp/check_wrapper.py"
        checker.parent.mkdir(parents=True)
        checker.symlink_to(CHECKER)
        self.databases = {}
        self.rows = {}
        for abi, target in TARGETS.items():
            database = self.root / "app/.cxx/Debug/fixture" / abi / "compile_commands.json"
            source = database.parent / "whisper-cancel/whisper.cpp"
            source.parent.mkdir(parents=True)
            source.write_text(self.patched)
            self.databases[abi] = database
            self.rows[abi] = [{"directory": str(database.parent), "file": str(source),
                               "arguments": ["clang++", "--target=" + target, "-c", str(source),
                                             "-o", "whisper.o"]}]

    def guard_result(self):
        for abi, rows in self.rows.items():
            self.databases[abi].write_text(json.dumps(rows))
        return subprocess.run([sys.executable, "-B", "-c", self.guard], cwd=self.root,
                              capture_output=True, text=True, timeout=10)

    def reject_guard(self, message):
        result = self.guard_result()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(message, result.stderr)

    def check_activation(self, cmake):
        original = Path.read_text

        def read(path, *args, **kwargs):
            return cmake if path == CMAKE else original(path, *args, **kwargs)

        with patch.object(Path, "read_text", read), patch.object(sys, "argv", [str(CHECKER)]), \
                redirect_stdout(io.StringIO()):
            exec(compile(self.checker, str(CHECKER), "exec"),
                 {"__file__": str(CHECKER), "__name__": "__main__"})

    def test_current_root_and_both_abis(self):
        self.check_activation(self.cmake)
        for spelling in ("arguments", "command"):
            with self.subTest(spelling=spelling):
                if spelling == "command":
                    for rows in self.rows.values():
                        rows[0]["command"] = shlex.join(rows[0].pop("arguments"))
                result = self.guard_result()
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(result.stdout.count("patch scope/hashes"), 2)

    def test_later_short_target_override_rejected(self):
        self.rows["arm64-v8a"][0]["arguments"] += ["-target", TARGETS["x86_64"]]
        self.reject_guard("compiler target")

    def test_later_long_target_override_rejected(self):
        self.rows["arm64-v8a"][0]["arguments"] += ["--target", TARGETS["x86_64"]]
        self.reject_guard("compiler target")

    def test_wrong_abi_or_api_target_rejected(self):
        for target in (TARGETS["x86_64"], "aarch64-none-linux-android32",
                       "aarch64-none-linux-android34"):
            with self.subTest(target=target):
                self.rows["arm64-v8a"][0]["arguments"][1] = "--target=" + target
                self.reject_guard("compiler target")

    def test_duplicate_or_missing_target_rejected(self):
        arguments = self.rows["arm64-v8a"][0]["arguments"]
        for changed in (arguments + [arguments[1]], [arguments[0]] + arguments[2:]):
            with self.subTest(arguments=changed):
                self.rows["arm64-v8a"][0]["arguments"] = changed
                self.reject_guard("compiler target")

    def test_missing_abi_or_databases_rejected(self):
        self.rows.pop("x86_64")
        self.reject_guard("arm64-v8a")
        self.rows.clear()
        self.databases["arm64-v8a"].unlink()
        self.reject_guard("set()")

    def test_duplicate_or_missing_translation_unit_rejected(self):
        row = self.rows["arm64-v8a"][0]
        for rows in ([row, row], []):
            with self.subTest(rows=len(rows)):
                self.rows["arm64-v8a"] = rows
                self.reject_guard("Expected exactly one Whisper translation unit")

    def test_misleading_source_metadata_rejected(self):
        self.rows["arm64-v8a"][0]["arguments"][3] = str(ROOT / "third_party/whisper.cpp/src/whisper.cpp")
        self.reject_guard("Pristine or unrelated source")

    def test_unrelated_source_directory_rejected(self):
        row = self.rows["arm64-v8a"][0]
        row["file"] = row["arguments"][3] = str(ROOT / "third_party/whisper.cpp/src/whisper.cpp")
        self.reject_guard("Pristine or unrelated source")

    def test_duplicate_or_missing_compiler_input_rejected(self):
        arguments = self.rows["arm64-v8a"][0]["arguments"]
        for changed in (arguments + ["-c", arguments[3]], arguments[:2] + arguments[3:]):
            with self.subTest(arguments=changed):
                self.rows["arm64-v8a"][0]["arguments"] = changed
                self.reject_guard("Expected one compiler input")

    def test_missing_source_rejected_by_real_checker(self):
        Path(self.rows["arm64-v8a"][0]["file"]).unlink()
        self.reject_guard("FileNotFoundError")

    def test_source_hash_drift_rejected_by_real_checker(self):
        Path(self.rows["arm64-v8a"][0]["file"]).write_text(self.patched + "\n// fixture drift\n")
        self.reject_guard("output_sha256")

    def test_original_inactive_or_duplicate_include_cases_rejected(self):
        for replacement in ("", "# " + INCLUDE, INCLUDE + "\n" + INCLUDE):
            with self.subTest(replacement=replacement), self.assertRaises(AssertionError):
                self.check_activation(self.cmake.replace(INCLUDE, replacement))

    def test_include_must_stay_between_vendor_and_jni_target(self):
        without_include = self.cmake.replace(INCLUDE + "\n", "")
        for cmake in (INCLUDE + "\n" + without_include, without_include + INCLUDE + "\n"):
            with self.subTest(cmake=cmake), self.assertRaises(AssertionError):
                self.check_activation(cmake)

    def test_bracket_commented_include_rejected(self):
        with self.assertRaises(AssertionError):
            self.check_activation(self.cmake.replace(INCLUDE, "#[[\n" + INCLUDE + "\n]]"))

    def test_false_conditional_include_rejected(self):
        with self.assertRaises(AssertionError):
            self.check_activation(self.cmake.replace(INCLUDE, "if(FALSE)\n" + INCLUDE + "\nendif()"))


if __name__ == "__main__":
    unittest.main()

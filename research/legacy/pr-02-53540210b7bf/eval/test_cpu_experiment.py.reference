"""Controlled CPU experiment source contracts; no compiler or inference."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "app/src/main/cpp"


class CpuExperimentTest(unittest.TestCase):
    def test_pinned_shared_stl_notices_are_bundled(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        notices = {
            "android-ndk-notice.txt": "a3c12eee897f63991da71464b690433a15d0d74a508ce0e275436279bdcaff58",
            "android-ndk-toolchain-notice.txt": "6b8633011d874b75629180e60de13ebfd546a10e56dc7d0380a35f6609f30617",
        }
        for name, digest in notices.items():
            with self.subTest(name=name):
                file = ROOT / "docs/assets" / name
                self.assertTrue(file.is_file())
                self.assertEqual(hashlib.sha256(file.read_bytes()).hexdigest(), digest)
                self.assertIn('rootProject.file("docs/assets/' + name + '")', gradle)

    def fixture(self, directory, abi, enabled):
        # Target operations are seams; source generation and upstream list selection are real.
        fixture = directory / "app/src/main/cpp/patches"
        fixture.mkdir(parents=True, exist_ok=True)
        for path in (CPP / "patches").iterdir():
            shutil.copyfile(path, fixture / path.name)
        vendor = directory / "third_party/whisper.cpp"
        vendor.parent.mkdir(exist_ok=True)
        if not vendor.exists():
            vendor.symlink_to(ROOT / "third_party/whisper.cpp", target_is_directory=True)
        script = directory / "fixture.cmake"
        script.write_text('''cmake_minimum_required(VERSION 3.22)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/agp/lib")
set(GGML_CPU ON)
set(CMAKE_SYSTEM_NAME Android)
function(add_subdirectory source binary)
    if(NOT BUILD_SHARED_LIBS OR NOT GGML_BACKEND_DL OR GGML_CPU_ARM_ARCH)
        message(FATAL_ERROR "Invalid shared backend configuration")
    endif()
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH ARM)
    else()
        set(GGML_SYSTEM_ARCH x86)
    endif()
    file(READ "${source}/src/CMakeLists.txt" code)
    string(FIND "${code}" "ggml_add_backend(CPU)" start)
    string(FIND "${code}" "ggml_add_backend(BLAS)" end)
    math(EXPR length "${end} - ${start}")
    string(SUBSTRING "${code}" ${start} ${length} selection)
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/selection.cmake" "${selection}")
    include("${CMAKE_CURRENT_BINARY_DIR}/selection.cmake")
endfunction()
function(ggml_add_backend)
endfunction()
function(ggml_add_cpu_backend_variant)
    file(APPEND "${CMAKE_CURRENT_BINARY_DIR}/variants.txt" "${ARGV}\\n")
endfunction()
function(ggml_add_cpu_backend_variant_impl)
    file(APPEND "${CMAKE_CURRENT_BINARY_DIR}/variants.txt" "default\\n")
endfunction()
function(target_link_libraries target visibility dependency)
    if(NOT "${ARGV}" STREQUAL "ggml;PRIVATE;dl")
        message(FATAL_ERROR "Missing direct dl link")
    endif()
endfunction()
function(set_target_properties)
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/outputs.txt" "${ARGV}")
endfunction()
function(get_target_property variable target property)
    if(property STREQUAL "SOURCE_DIR")
        set(${variable} "${_lip_vendor}/src" PARENT_SCOPE)
    elseif(property STREQUAL "SOURCES")
        set(${variable} whisper.cpp PARENT_SCOPE)
    endif()
endfunction()
function(set_source_files_properties)
endfunction()
function(get_source_file_property variable)
    set(${variable} TRUE PARENT_SCOPE)
endfunction()
function(target_sources)
endfunction()
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/variants.txt" "")
include("${CMAKE_CURRENT_LIST_DIR}/app/src/main/cpp/patches/ggml-android-dotprod.cmake")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
include("${CMAKE_CURRENT_LIST_DIR}/app/src/main/cpp/patches/whisper-scheduler-abort.cmake")
''')
        return subprocess.run(["cmake", f"-DANDROID_ABI={abi}",
                               f"-DLIP_GGML_DOTPROD={'ON' if enabled else 'OFF'}", "-P", str(script)],
                              cwd=directory, capture_output=True, text=True, timeout=30)

    @unittest.skipUnless(shutil.which("cmake"), "CMake script interpreter is not installed")
    def test_hash_bound_source_copy_and_standard_variant_lists(self):
        expected = {("arm64-v8a", False): ["android_armv8.0_1"],
                    ("arm64-v8a", True): ["android_armv8.0_1", "android_armv8.2_1;DOTPROD"],
                    ("x86_64", False): ["default"], ("x86_64", True): ["default"]}
        with tempfile.TemporaryDirectory(prefix="lip-cpu-") as temp:
            for (abi, enabled), variants in expected.items():
                with self.subTest(abi=abi, dotprod=enabled):
                    directory = Path(temp) / f"{abi}-{enabled}"
                    directory.mkdir()
                    for _ in range(2):
                        result = self.fixture(directory, abi, enabled)
                        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                        self.assertEqual((directory / "variants.txt").read_text().splitlines(), variants)
                    generated = directory / "ggml-dotprod"
                    result = subprocess.run([sys.executable, "-B", str(CPP / "check_wrapper.py"),
                                             "--ggml-source", str(generated), "--cancel-patch",
                                             str(directory / "whisper-cancel/whisper.cpp")],
                                            capture_output=True, text=True, timeout=15)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    for path in (ROOT / "third_party/whisper.cpp/ggml").rglob("*"):
                        if path.is_file() and path.relative_to(ROOT / "third_party/whisper.cpp/ggml").as_posix() != "src/CMakeLists.txt":
                            self.assertEqual(path.read_bytes(), (generated / path.relative_to(ROOT / "third_party/whisper.cpp/ggml")).read_bytes())
                    outputs = (directory / "outputs.txt").read_text()
                    targets = ["ggml-cpu" if value == "default" else "ggml-cpu-" + value.split(";")[0] for value in variants]
                    self.assertTrue(outputs.startswith(";".join(["ggml", "ggml-base"] + targets) + ";PROPERTIES;LIBRARY_OUTPUT_DIRECTORY;"))
                    # Unexpected/stale generated files must fail closed even on reconfigure.
                    (generated / "fixture-drift").write_text("drift")
                    result = self.fixture(directory, abi, enabled)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("output tree SHA256", result.stderr)

    def test_upstream_scoring_stays_separate_baseline_and_no_lto(self):
        cpu = (ROOT / "third_party/whisper.cpp/ggml/src/ggml-cpu/CMakeLists.txt").read_text()
        features = cpu[:cpu.index("function(ggml_add_cpu_backend_variant_impl")]
        self.assertIn("OBJECT ggml-cpu/arch/${arch}/cpu-feats.cpp", features)
        self.assertIn("PRIVATE -fno-lto", features)
        self.assertNotIn("ARCH_FLAGS", features)
        score = (ROOT / "third_party/whisper.cpp/ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp").read_text()
        self.assertIn("has_dotprod = !!(hwcap & HWCAP_ASIMDDP)", score)
        self.assertIn("if (!af.has_dotprod) { return 0; }", score)
        helper = (CPP / "patches/ggml-android-dotprod.cmake").read_text()
        self.assertNotIn("-march", helper)
        self.assertNotIn("-mcpu", helper)

    @unittest.skipUnless(shutil.which("cmake"), "CMake script interpreter is not installed")
    def test_source_patch_license_and_pin_drift_rejected(self):
        with tempfile.TemporaryDirectory(prefix="lip-cpu-drift-") as temp:
            directory = Path(temp)
            result = self.fixture(directory, "arm64-v8a", False)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            manifest = directory / "app/src/main/cpp/patches/ggml-android-dotprod.json"
            original = json.loads(manifest.read_text())
            for key, error in (("pin", "source pin"), ("input_sha256", "input SHA256"),
                               ("patch_sha256", "patch SHA256"), ("license_sha256", "license SHA256"),
                               ("input_tree_sha256", "input tree SHA256"), ("output_tree_sha256", "output tree SHA256")):
                with self.subTest(key=key):
                    manifest.write_text(json.dumps(dict(original, **{key: "0" * len(original[key])})))
                    result = subprocess.run(["cmake", "-DANDROID_ABI=arm64-v8a", "-P", str(directory / "fixture.cmake")],
                                            cwd=directory, capture_output=True, text=True, timeout=30)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(error, result.stderr)

    def test_standard_loader_and_single_switch(self):
        native = (CPP / "whisper_jni.cpp").read_text()
        self.assertIn("ggml_backend_load_all_from_path(library_path.c_str())", native)
        positions = [native.index(token) for token in (
            "whisper_log_set", "ggml_backend_load_all_from_path(library_path.c_str())",
            "ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)",
            "whisper_init_from_file_with_params")]
        self.assertEqual(positions, sorted(positions))
        self.assertIn("std::call_once(initialization,", native)
        self.assertIn("S_ISDIR", native)
        self.assertIn("bytes(env, library_directory, 4096, library_path)", native)
        kotlin = (ROOT / "app/src/main/java/dev/lip/speech/WhisperEngine.kt").read_text()
        self.assertIn("nativeOpen(modelPath: ByteArray, nativeLibraryDir: ByteArray)", kotlin)
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertEqual(gradle.count('gradleProperty("lipDotprod")'), 1)
        self.assertIn('orElse("false")', gradle)
        self.assertIn('"-DANDROID_STL=c++_shared"', gradle)
        self.assertIn("jniLibs.useLegacyPackaging = true", gradle)
        self.assertNotIn("pickFirst", gradle)
        dictation = (ROOT / "app/src/main/java/dev/lip/Dictation.kt").read_text()
        self.assertIn("context.applicationInfo.nativeLibraryDir", dictation)
        runner = (ROOT / "app/src/androidTest/java/dev/lip/LocalAsrRunner.kt").read_text()
        self.assertEqual(runner.count("WhisperEngine(model.absolutePath, targetContext.applicationInfo.nativeLibraryDir)"), 2)


if __name__ == "__main__":
    unittest.main()

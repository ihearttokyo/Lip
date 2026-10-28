"""Narrow source/dependency integration checks; NOT GPU/runtime qualification."""
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AndroidGpuFeatureTest(unittest.TestCase):
    def test_actual_pinned_header_set(self):
        dependency = ROOT / "third_party/vulkan"
        manifest = json.loads((dependency / "source.json").read_text())
        self.assertEqual(manifest["upstream"]["commit"], "2fa203425eb4af9dfc6b03f97ef72b0b5bcb8350")
        self.assertEqual(len(manifest["files"]), 52)
        result = subprocess.run(["cmake", f"-DLIP_VULKAN_HEADER_ROOT={dependency}",
                                 "-P", str(dependency / "verify_headers.cmake")],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("all52 pinned upstream files verified", result.stdout)

    def test_production_wire_keeps_existing_checked_fallback(self):
        cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text()
        session = (ROOT / "app/src/main/cpp/bark_checked_session.hpp").read_text()
        factory = session.split("std::uint64_t create_ticket(int profile_id)", 1)[1].split("\n    }", 1)[0]
        self.assertIn("return create(profile, CheckedSessionOps{}, STARLING_GGML_DEVICE_AUTOMATIC)", factory)
        self.assertIn("set(STARLING_GGML_VULKAN ON CACHE BOOL", cmake)
        self.assertIn("set(STARLING_FAST OFF CACHE BOOL", cmake)
        self.assertIn("LIP_BARK_REQUIRES_ANDROID_16_API36", cmake)
        self.assertIn("LIP_BARK_REQUIRES_ANDROID_ARM64", cmake)
        self.assertLess(cmake.index("lip_prepare_android_vulkan()"), cmake.index('add_subdirectory("${STARLING_ROOT}/backends/native"'))
        self.assertIn("target_link_libraries(ggml-vulkan PRIVATE SPIRV-Headers::SPIRV-Headers)", cmake)
        self.assertNotIn("validation/", cmake)
        self.assertEqual(len(re.findall(r"load_attempt\(STARLING_GGML_DEVICE_CPU,", session)), 2)
        for unchanged_guard in ("failed_kind == STARLING_GGML_BACKEND_ACCELERATOR", "!cpu_retry_used_",
                                "result == 1 && model_ == nullptr", "SnapshotStatus::CopiedSealedHashUnverified",
                                "hash != HashVerification::Verified", "loading_cancel_"):
            self.assertIn(unchanged_guard, session)

    def test_existing_engine_manifest_and_apk_license_text(self):
        result = subprocess.run(["python3", str(ROOT / "third_party/starling/scripts/verify_lip_vendor.py"),
                                 "--root", str(ROOT / "third_party/starling")],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("1618 files", result.stdout)
        self.assertEqual((ROOT / "third_party/vulkan/LICENSES/Apache-2.0.txt").read_bytes(),
                         (ROOT / "docs/assets/apache-2.0.txt").read_bytes())
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('rootProject.file("NOTICE.md")', gradle)
        self.assertIn('rootProject.file("docs/assets/apache-2.0.txt")', gradle)


if __name__ == "__main__":
    unittest.main()

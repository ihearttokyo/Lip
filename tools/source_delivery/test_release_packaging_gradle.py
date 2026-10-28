"""Actual Gradle scheduling/guards with metadata-only doubles, NOT a native/APK build.
Run with LIP_GRADLE_CONTROL_EXECUTABLE pointing to an existing local Gradle8.13;
no wrapper bootstrap/download, Android plugin, compiler, signing or models.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
GRADLE = os.environ.get('LIP_GRADLE_CONTROL_EXECUTABLE')


@unittest.skipUnless(GRADLE and Path(GRADLE).is_file(), 'Explicit existing local Gradle required; never bootstrap/download')
class CanonicalPackagingGradleTest(unittest.TestCase):
    def test_actual_copy_prerequisite_cannot_skip_missing_changed_or_fixture_inputs(self):
        actual = (ROOT / 'app/build.gradle.kts').read_text()
        task_source = actual[actual.index('val canonicalReleaseMetadata ='):]
        with tempfile.TemporaryDirectory(prefix='lip-canonical-gradle-control-') as directory:
            root = Path(directory)
            (root / 'settings.gradle.kts').write_text('rootProject.name = "public-canonical-control"\n')
            prelude = '''
plugins { base }
val historyFixture = providers.gradleProperty("lipHistoryFixture").orNull == "true"
val sizingFixture = providers.gradleProperty("lipSizingFixture").orNull == "true"
val stickerFixture = providers.gradleProperty("lipStickerFixture").orNull == "true"
val lipReleaseVersion = "0.007"
val lipReleaseVersionCode = 50004
val lipReleaseIdentity = mapOf("android_apk" to "Lip-v0.007-android16-arm64.apk")
class FixtureSdk(val sdkDirectory: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>)
class FixtureAndroid(val sdkComponents: FixtureSdk, val buildToolsVersion: String)
val android = FixtureAndroid(FixtureSdk(layout.projectDirectory.dir("sdk").let { providers.provider { it } }), "36.0.0")
tasks.register("assembleRelease") { doLast { println("PUBLIC_SYNTHETIC_ASSEMBLE_ONLY_NO_NATIVE") } }
'''
            (root / 'build.gradle.kts').write_text(prelude + task_source)
            aapt = root / 'sdk/build-tools/36.0.0/aapt'
            aapt.parent.mkdir(parents=True)
            aapt.write_text('#!/bin/sh\nset -eu\nprintf "verified\\n" >> "' + str(root / 'verification-calls.txt') + '"\ncat "$3"\n')
            aapt.chmod(0o700)
            output = root / 'build/outputs/apk/release'; output.mkdir(parents=True)
            metadata = output / 'output-metadata.json'; apk = output / 'app-release.apk'
            def manifest(name='app-release.apk'):
                metadata.write_text(json.dumps({'elements': [{'outputFile': name}]}))
            def run(*flags):
                result = subprocess.run([str(GRADLE), '--offline', '--no-daemon', '--max-workers=1',
                                         '--console=plain', '-p', str(root), 'canonicalReleaseApk', *flags],
                                        capture_output=True, text=True, timeout=90)
                return result.returncode, result.stdout + result.stderr
            manifest()
            code, log = run()
            self.assertNotEqual(code, 0, log)  # Old Copy NO-SOURCE incorrectly returned success.
            apk.write_text("package: name='dev.lip.android.preview' versionCode='50004' versionName='0.007'\n")
            code, log = run(); self.assertEqual(code, 0, log)
            canonical = root / 'build/outputs/canonical-release/Lip-v0.007-android16-arm64.apk'
            self.assertEqual(canonical.read_bytes(), apk.read_bytes())
            count = (root / 'verification-calls.txt').read_text().count('verified')
            code, log = run(); self.assertEqual(code, 0, log)
            self.assertGreater((root / 'verification-calls.txt').read_text().count('verified'), count)
            self.assertIn(':canonicalReleaseApk UP-TO-DATE', log)
            manifest('app-release-unsigned.apk')
            code, log = run(); self.assertNotEqual(code, 0, log)
            self.assertIn('explicitly signed release APK', log)
            metadata.unlink()
            code, log = run(); self.assertNotEqual(code, 0, log)
            manifest()
            for flag in ('lipHistoryFixture', 'lipSizingFixture', 'lipStickerFixture'):
                code, log = run('-P' + flag + '=true')
                self.assertNotEqual(code, 0, log)
                self.assertIn('Canonical release packaging refuses', log)
                self.assertNotIn('PUBLIC_SYNTHETIC_ASSEMBLE_ONLY_NO_NATIVE', log)


if __name__ == '__main__':
    unittest.main()

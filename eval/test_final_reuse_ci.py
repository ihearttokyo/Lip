"""Static hosted-run contract; actual JNI assertions run only on Android."""
import hashlib
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FinalReuseCiTest(unittest.TestCase):
    def test_collector_modes_are_closed_and_reach_instrumentation(self):
        source = (ROOT / 'eval/android/run_cancel_ci.sh').read_text()
        selection = re.search(r'^mode="\$\{1:-cancel_active\}"\n.*\n', source, re.M)
        self.assertIsNotNone(selection, 'Missing closed collector mode selection')
        for args, expected in [((), 0), (('cancel_active',), 0), (('final_reuse',), 0),
                               (('default',), 1), (('final_reuse', 'extra'), 1),
                               (('final_reuse;echo unsafe',), 1)]:
            with self.subTest(args=args):
                result = subprocess.run(['bash', '-c', 'refuse() { exit 1; };\n' + selection.group(),
                                         'fixture', *args], capture_output=True, timeout=5)
                self.assertEqual(result.returncode, expected)
        self.assertIn('printf \'mode=%s\\nmodel=', source)
        self.assertIn('"$mode" "$model_leaf" "$model_sha" "$pcm_sha"', source)
        self.assertIn('shell am instrument -w -r -e mode "$mode"', source)
        self.assertIn('--signal=KILL 450s', source)
        self.assertIn('-cores 2 -memory 2048 -accel on', source)

    def test_runner_is_hash_bound_and_new_mode_is_opt_in(self):
        runner = ROOT / 'app/src/androidTest/java/dev/lip/LocalAsrRunner.kt'
        source = runner.read_text()
        collector = (ROOT / 'eval/android/run_cancel_ci.sh').read_text()
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn(hashlib.sha256(runner.read_bytes()).hexdigest(), collector)
        self.assertIn('"final_reuse"', source)
        self.assertIn('contains(github.event.head_commit.message, \'[android-final-reuse]\')', workflow)
        self.assertIn("&& 'final_reuse' || 'cancel_active'", workflow)
        self.assertIn('reused.indices.all { reused[it] === first[it] }', source)
        self.assertIn('check(first.isNotEmpty())', source)
        self.assertIn('lease.transcribe(FloatArray(samples.size), language, prompt, reuseIdentical = true).isEmpty()', source)
        self.assertIn('lease.cancel()', source)
        self.assertIn('?.javaClass == CancellationException::class.java', source)


if __name__ == '__main__':
    unittest.main()

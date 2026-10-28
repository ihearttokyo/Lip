"""Pure ledger/metadata and tiny owned Git-tree controls; no builds or downloads."""
import copy
import json
from pathlib import Path
import plistlib
import subprocess
import tempfile
import types
import unittest
from unittest import mock
import sys

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/release_identity.py'
r = types.ModuleType('lip_release_identity')
r.__file__ = str(SCRIPT)
exec(compile(SCRIPT.read_bytes(), str(SCRIPT), 'exec'), r.__dict__)


class ReleaseIdentityTest(unittest.TestCase):
    def setUp(self):
        self.actual = r.read_json(ROOT / r.LEDGER)
        # Pure allocation controls retain their fixed bootstrap7 fixture even as
        # real feature candidates advance the authoritative production ledger.
        self.data = copy.deepcopy(self.actual)
        self.data['allocations'] = self.data['allocations'][:1]

    def test_actual_reachable_anchor_audit_is_reproducible(self):
        audit = r.audit(ROOT)
        for key in ('source_commit', 'reachable_commits', 'empty_states', 'distinct_production_states',
                    'first_parent_states', 'additional_branch_states', 'missing_trees', 'projection_identity_set_sha256'):
            self.assertEqual(audit[key], self.data['seed'][key])
        self.assertEqual(sum(len(state['commits']) for state in audit['states']), 10)

    def test_one_canonical_candidate_and_upgrade_floors(self):
        info = r.identity(self.data)
        self.assertEqual(info['display_name'], 'Lip v0.007')
        self.assertEqual(info['version'], '0.007')
        self.assertEqual(info['android_version_code'], 50004)
        self.assertEqual(info['macos_build'], '11')
        self.assertEqual(info['android_application_id'], 'dev.lip.android.preview')
        self.assertEqual(info['macos_bundle_id'], 'dev.lip.local.mac')
        self.assertEqual(info['status'], 'source-candidate-not-published')
        self.assertNotIn('preview', info['android_apk'].lower())

    def test_schema_seed_ids_and_duplicate_allocations_fail_closed(self):
        cases = []
        for key, value in [('schema', 'future'), ('policy', 'changed'), ('extra', 1)]:
            data = copy.deepcopy(self.data); data[key] = value; cases.append(data)
        data = copy.deepcopy(self.data); data['seed']['missing_trees'] = False; cases.append(data)
        data = copy.deepcopy(self.data); data['seed']['projection']['fixed_paths'] = []; cases.append(data)
        data = copy.deepcopy(self.data); data['upgrade_floor']['android_application_id'] = 'new.app'; cases.append(data)
        data = copy.deepcopy(self.data); data['allocations'].append(dict(data['allocations'][0])); cases.append(data)
        for case in cases:
            with self.assertRaises(ValueError): r.validate(case)

    def test_retry_does_not_advance_and_docs_tests_cannot_reserve(self):
        candidate = self.data['allocations'][-1]['candidate']
        with self.assertRaises(ValueError): r.reserve(self.data, candidate, 7)
        self.assertEqual(r.reserve(self.data, candidate, 7, bootstrap=True), self.data)
        prior = {'schema': 'lip-source-artifact-binding-v1', 'ordinal': 7, 'candidate': candidate,
                 'production_state_sha256': 'a' * 64}
        self.assertEqual(r.reserve(self.data, candidate, 7, 'a' * 64, prior), self.data)
        with self.assertRaisesRegex(ValueError, 'Bound source changed'):
            r.reserve(self.data, candidate, 7, 'b' * 64, prior)
        with self.assertRaises(ValueError): r.reserve(self.data, 'docs-only', 7, 'a' * 64, prior)
        advanced = r.reserve(self.data, 'new-production-feature', 7, 'b' * 64, prior)
        self.assertEqual(r.identity(advanced)['ordinal'], 8)
        self.assertEqual(r.identity(advanced)['android_version_code'], 50005)
        self.assertEqual(r.identity(advanced)['macos_build'], '12')
        with self.assertRaises(ValueError): r.reserve(self.data, 'new-production-feature', 6, 'b' * 64, prior)

    def test_failed_ordinals_are_retained_and_never_reused(self):
        candidate = self.data['allocations'][-1]['candidate']
        failed = r.mark_failed(self.data, candidate, 7, 'Synthetic rejected candidate')
        with self.assertRaises(ValueError): r.identity(failed)
        with self.assertRaises(ValueError): r.reserve(failed, candidate, 7)
        with self.assertRaises(ValueError): r.mark_failed(failed, candidate, 7, 'Overwrite original reason')
        prior = {'schema': 'lip-source-artifact-binding-v1', 'ordinal': 7, 'candidate': candidate,
                 'production_state_sha256': 'a' * 64}
        next_value = r.reserve(failed, 'corrected-source', 7, 'b' * 64, prior)
        self.assertEqual(next_value['allocations'][0], failed['allocations'][0])
        self.assertEqual(r.identity(next_value)['ordinal'], 8)

    def test_current_metadata_and_installer_match_ledger(self):
        for path, desired in r.metadata(ROOT, self.actual).items(): self.assertEqual(path.read_text(), desired)
        plist = plistlib.loads((ROOT / 'desktop/Info.plist').read_bytes())
        self.assertEqual(plist['CFBundleDisplayName'], r.identity(self.actual)['display_name'])
        install = (ROOT / 'desktop/scripts/install.sh').read_text()
        self.assertIn('--check --check-source --field display_name', install)
        self.assertIn('--stamp-app "$DEST"', install)
        self.assertIn('DEST="$HOME/Applications/Lip Local.app"', install)
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('check(!offlinePreview)', gradle)
        self.assertNotIn('src/offlinePreviewAndroidTest/java', gradle)
        self.assertNotIn('dev.lip.OfflinePreviewRunner', gradle)
        self.assertIn('check(!bundleSpeechModels)', gradle)
        self.assertIn('withBuildType("release")', gradle)
        self.assertIn('if (!historyFixture && !sizingFixture && !stickerFixture)', gradle)
        self.assertIn('if (historyFixture || sizingFixture) it.enable = false', gradle)
        self.assertIn('val verifyCanonicalReleaseApk = tasks.register("verifyCanonicalReleaseApk")', gradle)
        self.assertIn('outputs.upToDateWhen { false }', gradle)
        self.assertIn('dependsOn(verifyCanonicalReleaseApk)', gradle)
        self.assertEqual(gradle.count('inputs.file(canonicalReleaseMetadata)'), 2)
        self.assertIn('variant.applicationId.set("dev.lip.android.preview")', gradle)
        self.assertIn('lipInstrumentationTestTarget", "dev.lip.android.preview.test"', gradle)

    def test_existing_public_download_is_not_relabelled(self):
        old = 'v0.5.0-preview.3-offline/Lip-0.5.0-preview.3-offline-universal.apk'
        for path in ('README.md', 'docs/index.html'):
            value = (ROOT / path).read_text()
            self.assertIn(old, value)
            self.assertIn('50003', value)
            self.assertNotIn('Lip-v0.007-android16-arm64.apk', value)

    def test_external_postcommit_binding_and_app_stamp_not_source_selfhash(self):
        with tempfile.TemporaryDirectory(prefix='lip-binding-control-') as directory:
            owned = Path(directory); root = owned / 'source'; root.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git('init', '-q', '-b', 'main'); git('config', 'user.name', 'Public Fixture'); git('config', 'user.email', 'fixture@example.invalid')
            for name in ('app/build.gradle.kts', 'desktop/Info.plist', 'docs/build-iterations.json'):
                target = root / name; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / name).read_bytes())
            git('add', '.'); git('commit', '-qm', 'synthetic committed source')
            commit = git('rev-parse', 'HEAD')
            info = r.identity(self.data)
            artifact = owned / info['android_apk']; artifact.write_bytes(b'PUBLIC_SYNTHETIC_BINDING_BYTES_NOT_AN_APK')
            binding = r.build_binding(root, self.data, commit, [artifact])
            self.assertEqual(binding['source_commit'], commit)
            self.assertEqual(binding['production_state_sha256'], r.source_identity(root, commit))
            self.assertEqual(binding['artifacts'][0]['name'], info['android_apk'])
            self.assertIn('Not publication', binding['qualification'])
            output = owned / 'external-binding.json'
            run = subprocess.run(['python3', str(SCRIPT), '--root', str(root), '--bind', commit,
                                  '--artifact', str(artifact), '--output', str(output)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertTrue(output.is_file())
            again = subprocess.run(['python3', str(SCRIPT), '--root', str(root), '--bind', commit,
                                    '--output', str(output)], capture_output=True, text=True)
            self.assertNotEqual(again.returncode, 0)
            self.assertEqual(r.read_json(output), binding)
            app = owned / info['macos_app']; (app / 'Contents/Resources').mkdir(parents=True)
            (app / 'Contents/Info.plist').write_bytes((root / 'desktop/Info.plist').read_bytes())
            stamped = subprocess.run(['python3', str(SCRIPT), '--root', str(root), '--stamp-app', str(app)], capture_output=True, text=True)
            self.assertEqual(stamped.returncode, 0, stamped.stderr)
            self.assertEqual(r.read_json(app / 'Contents/Resources/LipBuildIdentity.json')['source_commit'], commit)
            self.assertEqual(git('status', '--porcelain'), '')
            (root / 'app/untracked-source.kt').write_text('PUBLIC_SYNTHETIC')
            # This path is not part of the explicit main production projection.
            (root / 'app/src/main').mkdir(parents=True)
            (root / 'app/src/main/Injected.kt').write_text('PUBLIC_SYNTHETIC')
            with self.assertRaises(ValueError): r.build_binding(root, self.data, commit)

    def test_mutators_read_ledger_only_after_exclusive_lock(self):
        # Deterministic interleaving: B reaches lock acquisition; A has just
        # committed allocation8/released its lock. B must read8, never its old7.
        for operation in ('reserve', 'fail', 'sync'):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory(prefix='lip-stale-reader-control-') as directory:
                root = Path(directory).resolve()
                for name in ('app/build.gradle.kts', 'desktop/Info.plist', 'docs/build-iterations.json'):
                    path = root / name; path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes((ROOT / name).read_bytes())
                advanced = copy.deepcopy(self.data)
                advanced['allocations'].append({'ordinal': 8, 'candidate': 'concurrent-admitted-source', 'status': 'reserved'})
                ledger = root / r.LEDGER
                lock_path = str(ledger) + '.lock'
                original_open = r.os.open
                interleavings = []
                def interleaved_open(path, flags, *args, **kwargs):
                    if str(path) == lock_path:
                        interleavings.append('A wrote8 before B acquired')
                        ledger.write_text(json.dumps(advanced))
                        # Sync's harmful stale write is the metadata rollback;
                        # reserve/fail exercise the ledger rollback window itself.
                        if operation == 'sync':
                            for target, desired in r.metadata(root, advanced).items(): target.write_text(desired)
                    return original_open(path, flags, *args, **kwargs)
                arguments = ['release_identity.py', '--root', str(root), '--expect-last', '7']
                if operation == 'reserve': arguments += ['--reserve', 'canonical-release-identity', '--bootstrap-unbound']
                elif operation == 'fail': arguments += ['--fail', 'canonical-release-identity', '--reason', 'Synthetic stale failure']
                else: arguments += ['--sync']
                with mock.patch.object(r.os, 'open', side_effect=interleaved_open), mock.patch.object(sys, 'argv', arguments):
                    with self.assertRaises(ValueError): r.main()
                self.assertEqual(len(interleavings), 1)
                self.assertEqual(r.read_json(ledger), advanced)
                self.assertFalse(Path(lock_path).exists())
                if operation == 'sync':
                    self.assertEqual(plistlib.loads((root / 'desktop/Info.plist').read_bytes())['CFBundleVersion'], '12')

    def test_mixed_sync_modes_rejected_before_lock_or_write(self):
        for mode in (['--reserve', 'new-source'], ['--fail', 'canonical-release-identity', '--reason', 'Synthetic failure'], ['--bind', 'HEAD']):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(prefix='lip-mixed-mode-control-') as directory:
                root = Path(directory).resolve()
                snapshots = {}
                for name in ('desktop/Info.plist', 'docs/build-iterations.json'):
                    target = root / name; target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes((ROOT / name).read_bytes()); snapshots[target] = target.read_bytes()
                arguments = ['release_identity.py', '--root', str(root), '--expect-last', '7', '--sync', *mode]
                with mock.patch.object(sys, 'argv', arguments), mock.patch.object(r.os, 'open') as opened, mock.patch.object(r, 'atomic_write') as written:
                    with self.assertRaisesRegex(ValueError, 'separate invocations'): r.main()
                    opened.assert_not_called(); written.assert_not_called()
                for target, before in snapshots.items(): self.assertEqual(target.read_bytes(), before)
                self.assertFalse(Path(str(root / r.LEDGER) + '.lock').exists())

    def test_tree_projection_counts_merged_branch_states_not_test_runs(self):
        # Commits exist ONLY in this newly-owned temporary synthetic repository.
        with tempfile.TemporaryDirectory(prefix='lip-identity-git-control-') as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git('init', '-q', '-b', 'main'); git('config', 'user.name', 'Public Fixture'); git('config', 'user.email', 'fixture@example.invalid')
            def commit(path, value, title):
                file = root / path; file.parent.mkdir(parents=True, exist_ok=True); file.write_text(value)
                git('add', '--', path); git('commit', '-qm', title)
            commit('README.md', 'fixture', 'empty production')
            commit('app/src/main/Fixture.kt', 'A', 'production A')
            git('switch', '-qc', 'feature')
            commit('app/src/main/Fixture.kt', 'B1', 'branch intermediate')
            commit('app/src/main/Fixture.kt', 'B2', 'branch final')
            git('switch', '-q', 'main')
            commit('docs/note.md', 'fixture', 'docs not production')
            git('merge', '--no-ff', '-qm', 'merge branch', 'feature')
            commit('app/src/main/Fixture.kt', 'C', 'production C')
            before = r.source_identity(root, 'HEAD')
            commit('app/src/test/FixtureTest.kt', 'test', 'test not production')
            self.assertEqual(before, r.source_identity(root, 'HEAD'))
            audit = r.audit(root, 'HEAD')
            self.assertEqual(audit['reachable_commits'], 8)
            self.assertEqual(audit['empty_states'], 1)
            self.assertEqual(audit['distinct_production_states'], 4)
            self.assertEqual(audit['first_parent_states'], 3)
            self.assertEqual(audit['additional_branch_states'], 1)
            with self.assertRaises(ValueError): r.source_identity(root, 'f' * 40)


if __name__ == '__main__':
    unittest.main()

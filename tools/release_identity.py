#!/usr/bin/env python3
"""Lip's read-only identity/audit gate; explicit allocation/sync/binding only.

No Git mutation, model/blob retrieval, install, signing or publication. Git audits
use tree entries only, including reachable merged-branch development states.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LEDGER = Path('docs/build-iterations.json')
ANCHOR = '13d87e4439d38811a4c82a8bab747f1d7443d88c'
SEED_SET = '26cb9dcf14b2729b5fec1d89bf9e0b9d30a43cb28d40956f9e83e7e1bd0f6a25'
SEED_COUNTS = dict(ordinal=6, reachable_commits=10, empty_states=0,
                   distinct_production_states=6, first_parent_states=4,
                   additional_branch_states=2, missing_trees=0)
SCHEMA = 'lip-build-iterations-v1'
KEY = re.compile(r'[a-z][a-z0-9]*(?:-[a-z0-9]+)*\Z')
HEX40 = re.compile(r'[0-9a-f]{40}\Z')
HEX64 = re.compile(r'[0-9a-f]{64}\Z')
FIXED = {'.gitmodules', 'build.gradle.kts', 'gradle.properties', 'settings.gradle.kts',
         'gradlew', 'gradlew.bat', 'LICENSE', 'NOTICE.md', 'app/build.gradle.kts',
         'app/proguard-rules.pro', 'desktop/Info.plist', 'desktop/Package.swift', 'desktop/Package.resolved'}
PROJECTION = {
    'method': 'All anchor/HEAD-reachable commits, including merged-branch development; sorted raw git ls-tree -r -z mode/type/object/path entries; omit empty projections and globally deduplicate identical projected trees. No blob reads or lazy fetching.',
    'fixed_paths': sorted(FIXED),
    'prefixes': ['app/src/main/', 'desktop/Sources/', 'desktop/scripts/', 'third_party/', 'tools/'],
    'excluded_components': ['test', 'tests', 'androidTest', 'sharedTest', 'offlinePreviewAndroidTest', 'debug', '__pycache__'],
    'excluded_names': ['test_*', '*Tests.swift'],
    'excluded_prefixes': ['tools/source_delivery/', 'tools/site/'],
    'identity': 'SHA256 of path-byte-sorted raw ls-tree records, each followed by NUL; set digest is SHA256 of sorted distinct nonempty identity hex lines with final LF. Gitlinks are opaque pinned identities, not recursively audited upstream history.',
}
SCOPE = 'Distinct committed Lip production-input states through the audited anchor, including branch development. Not a lifetime/uncommitted count, exported-build count, PR number, test count or run count.'
POLICY = 'New admitted production-source candidates consume one ordinal. Retain failed issued ordinals forever. Same-candidate source-identical packaging/signing/CI retries do not advance; docs/tests-only or identical projected states cannot allocate. Minimum three decimal digits, no Alpha/Preview suffix. Final source/artifact binding is external after commit; reservations are not releases.'
UPGRADE = {'android_application_id': 'dev.lip.android.preview', 'android_version_code': 50003,
           'macos_bundle_id': 'dev.lip.local.mac', 'macos_build': 10}


def unique(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ValueError('Duplicate JSON key')
        out[key] = value
    return out


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique)


def production(path):
    parts = path.split('/')
    if any(part in PROJECTION['excluded_components'] for part in parts):
        return False
    if parts[-1].startswith('test_') or parts[-1].endswith('Tests.swift'):
        return False
    return path in FIXED or (any(path.startswith(p) for p in PROJECTION['prefixes'])
                            and not any(path.startswith(p) for p in PROJECTION['excluded_prefixes']))


def git(root, *args):
    env = dict(os.environ, GIT_NO_LAZY_FETCH='1', GIT_TERMINAL_PROMPT='0', GIT_NO_REPLACE_OBJECTS='1')
    # Missing objects must fail, never trigger a promisor/blob/model download.
    result = subprocess.run(['git', '--no-replace-objects', '-c', 'protocol.allow=never',
                             '-c', 'protocol.file.allow=never', '-C', str(root), *args],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise ValueError('Read-only Git command failed (missing history/trees are not fetched): ' + args[0])
    return result.stdout


def source_identity(root, commit):
    records = []
    for entry in git(root, 'ls-tree', '-r', '-z', '--full-tree', commit).split(b'\0'):
        if not entry:
            continue
        header, path = entry.split(b'\t', 1)
        if production(path.decode('utf-8', errors='strict')):
            records.append((path, header + b'\t' + path))
    if not records:
        return None
    return hashlib.sha256(b''.join(row + b'\0' for _, row in sorted(records))).hexdigest()


def audit(root, commit=ANCHOR):
    resolved = git(root, 'rev-parse', '--verify', commit + '^{commit}').decode().strip()
    commits = git(root, 'rev-list', resolved).decode().splitlines()
    first = set(git(root, 'rev-list', '--first-parent', resolved).decode().splitlines())
    states = {}; empty = 0
    for item in commits:
        state = source_identity(root, item)
        if state is None:
            empty += 1
        else:
            states.setdefault(state, []).append(item)
    first_states = {state for state, members in states.items() if any(item in first for item in members)}
    return {'source_commit': resolved, 'reachable_commits': len(commits), 'empty_states': empty,
            'distinct_production_states': len(states), 'first_parent_states': len(first_states),
            'additional_branch_states': len(states) - len(first_states), 'missing_trees': 0,
            'projection_identity_set_sha256': hashlib.sha256(('\n'.join(sorted(states)) + '\n').encode()).hexdigest(),
            'states': [{'sha256': state, 'commits': sorted(members)} for state, members in sorted(states.items())]}


def validate(data):
    if not isinstance(data, dict) or set(data) != {'schema', 'seed', 'policy', 'upgrade_floor', 'allocations'} or data['schema'] != SCHEMA:
        raise ValueError('Unknown release ledger schema/shape')
    seed = data['seed']
    expected_seed = {'source_commit': ANCHOR, 'projection_identity_set_sha256': SEED_SET,
                     'projection': PROJECTION, 'scope': SCOPE, **SEED_COUNTS}
    if (not isinstance(seed, dict) or any(type(seed.get(key)) is not int for key in SEED_COUNTS)
            or seed != expected_seed or data['upgrade_floor'] != UPGRADE or data['policy'] != POLICY):
        raise ValueError('Audited seed, projection, upgrade IDs/floors or policy changed')
    if not isinstance(data['allocations'], list) or not data['allocations']:
        raise ValueError('Missing issued candidate allocation')
    names = set()
    for ordinal, row in enumerate(data['allocations'], SEED_COUNTS['ordinal'] + 1):
        fields = {'ordinal', 'candidate', 'status'}
        if isinstance(row, dict) and row.get('status') == 'failed':
            fields.add('failure_reason')
        if (not isinstance(row, dict) or set(row) != fields or type(row['ordinal']) is not int
                or row['ordinal'] != ordinal or not isinstance(row['candidate'], str)
                or not KEY.fullmatch(row['candidate']) or row['candidate'] in names
                or row['status'] not in ('reserved', 'failed')):
            raise ValueError('Invalid, duplicated or reused allocation')
        if row['status'] == 'failed' and (not isinstance(row['failure_reason'], str) or not row['failure_reason'].strip() or len(row['failure_reason']) > 512):
            raise ValueError('Retired ordinal needs its retained failure reason')
        names.add(row['candidate'])
    if data['allocations'][0]['candidate'] != 'canonical-release-identity':
        raise ValueError('Initial audited allocation changed')
    return data['allocations'][-1]['ordinal']


def identity(data, active=True):
    ordinal = validate(data)
    if active and data['allocations'][-1]['status'] != 'reserved':
        raise ValueError('Failed ordinal is retired, not buildable')
    version = f'0.{ordinal:03d}'
    return {'ordinal': ordinal, 'candidate': data['allocations'][-1]['candidate'],
            'status': ('source-candidate-not-published' if data['allocations'][-1]['status'] == 'reserved' else 'failed-retired-not-published'), 'version': version, 'display_name': 'Lip v' + version,
            'android_version_code': UPGRADE['android_version_code'] + ordinal - SEED_COUNTS['ordinal'],
            'android_application_id': UPGRADE['android_application_id'],
            'macos_build': str(UPGRADE['macos_build'] + ordinal - SEED_COUNTS['ordinal']),
            'macos_bundle_id': UPGRADE['macos_bundle_id'],
            'android_apk': f'Lip-v{version}-android16-arm64.apk', 'macos_app': f'Lip v{version}.app',
            'macos_archive': f'Lip-v{version}-macOS26-apple-silicon.zip'}


def reserve(data, candidate, expected, current_state=None, previous=None, bootstrap=False):
    last = validate(data)
    if expected != last or type(expected) is not int or not isinstance(candidate, str) or not KEY.fullmatch(candidate):
        raise ValueError('Stale ordinal or invalid candidate key')
    existing = next((row for row in data['allocations'] if row['candidate'] == candidate), None)
    if existing and (existing is not data['allocations'][-1] or existing['status'] != 'reserved'):
        raise ValueError('Earlier/failed issued candidate cannot be reused')
    if previous is None and existing and bootstrap:
        # Explicit pre-binding development only; NOT proof of identical source.
        return copy.deepcopy(data)
    if not isinstance(current_state, str) or not HEX64.fullmatch(current_state) or not isinstance(previous, dict):
        raise ValueError('Committed source and prior binding required; explicit --bootstrap-unbound is only for a current unbound reservation')
    if (previous.get('schema') != 'lip-source-artifact-binding-v1' or previous.get('ordinal') != last
            or previous.get('candidate') != data['allocations'][-1]['candidate']
            or not HEX64.fullmatch(str(previous.get('production_state_sha256', '')))):
        raise ValueError('Previous external source binding does not match last issued candidate')
    if existing:
        if current_state != previous['production_state_sha256']:
            raise ValueError('Bound source changed: same-key retry cannot reuse its ordinal')
        return copy.deepcopy(data)
    if current_state == previous['production_state_sha256']:
        raise ValueError('Production state unchanged: documentation/tests/packaging cannot advance')
    result = copy.deepcopy(data)
    result['allocations'].append({'ordinal': last + 1, 'candidate': candidate, 'status': 'reserved'})
    validate(result)
    return result


def mark_failed(data, candidate, expected, reason):
    if type(expected) is not int or validate(data) != expected or data['allocations'][-1]['candidate'] != candidate:
        raise ValueError('Stale ordinal/candidate')
    if not isinstance(reason, str) or not reason.strip() or len(reason) > 512:
        raise ValueError('Explicit short failure reason required')
    row = data['allocations'][-1]
    if row['status'] == 'failed' and row['failure_reason'] != reason:
        raise ValueError('Retain the original failure reason')
    result = copy.deepcopy(data)
    result['allocations'][-1].update(status='failed', failure_reason=reason)
    return result


def replace_plist_string(text, key, value):
    pattern = r'(<key>' + re.escape(key) + r'</key>\s*<string>)[^<]*(</string>)'
    result, count = re.subn(pattern, lambda match: match[1] + value + match[2], text)
    if count != 1:
        raise ValueError('Missing/duplicate version field: ' + key)
    return result


def metadata(root, data):
    info = identity(data)
    path = root / 'desktop/Info.plist'
    raw = path.read_text()
    plist = plistlib.loads(raw.encode())
    if plist.get('CFBundleIdentifier') != info['macos_bundle_id'] or plist.get('CFBundleExecutable') != 'LipLocal' or plist.get('LSMinimumSystemVersion') != '26.0':
        raise ValueError('Stable Mac application ID/executable/hardware scope changed')
    desired = raw
    for key, value in [('CFBundleShortVersionString', info['version']), ('CFBundleVersion', info['macos_build']), ('CFBundleDisplayName', info['display_name'])]:
        desired = replace_plist_string(desired, key, value)
    gradle = (root / 'app/build.gradle.kts').read_text()
    required = ['tools/release_identity.py', 'versionCode = lipReleaseVersionCode', 'versionName = lipReleaseVersion',
                'applicationId.set("dev.lip.android.preview")', 'check(!bundleSpeechModels)', 'minSdk = 36', 'listOf("arm64-v8a")']
    if any(gradle.count(token) != 1 for token in required):
        raise ValueError('Android ledger wiring, stable release ID or small/hardware guard mismatch')
    return {path: desired}


def clean_source(root, commit):
    actual = git(root, 'rev-parse', '--verify', commit + '^{commit}').decode().strip()
    if actual != git(root, 'rev-parse', 'HEAD').decode().strip():
        raise ValueError('External binding must name current committed HEAD')
    if git(root, 'status', '--porcelain', '--untracked-files=no').strip():
        raise ValueError('Commit source/metadata before binding; tracked worktree is dirty')
    for path in git(root, 'ls-files', '--others', '--exclude-standard', '-z').split(b'\0'):
        if path and production(path.decode()):
            raise ValueError('Untracked production input prevents source binding')
    return actual


def build_binding(root, data, commit, artifacts=()):
    info = identity(data, active=False)
    actual = clean_source(root, commit)
    if data['allocations'][-1]['status'] == 'reserved':
        for path, desired in metadata(root, data).items():
            if path.read_text() != desired:
                raise ValueError('Source version metadata mismatch before binding')
    values = []
    for item in artifacts:
        path = Path(item)
        if path.is_symlink() or not path.is_file() or path.name not in (info['android_apk'], info['macos_archive']):
            raise ValueError('Expected an exact canonical, regular artifact name')
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(64 * 1024), b''):
                digest.update(block)
        values.append({'name': path.name, 'bytes': path.stat().st_size, 'sha256': digest.hexdigest()})
    if len({value['name'] for value in values}) != len(values):
        raise ValueError('Duplicate artifact binding')
    return {'schema': 'lip-source-artifact-binding-v1', **info, 'source_commit': actual,
            'production_state_sha256': source_identity(root, actual), 'artifacts': values,
            'qualification': 'Source/identity/checksum binding only. Not publication, signature, runtime or device qualification.'}


def atomic_write(path, value):
    path = Path(path)
    if path.is_symlink():
        raise ValueError('Refuse symlink output')
    fd, staging = tempfile.mkstemp(prefix=path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            stream.write(value); stream.flush(); os.fsync(stream.fileno())
        os.replace(staging, path)
    finally:
        if os.path.exists(staging):
            os.unlink(staging)  # Exact tool-created stage only.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--audit', nargs='?', const=ANCHOR)
    group.add_argument('--reserve')
    group.add_argument('--fail')
    group.add_argument('--bind', metavar='COMMIT')
    group.add_argument('--stamp-app', type=Path)
    parser.add_argument('--identity', action='store_true')
    parser.add_argument('--field')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--check-source', action='store_true')
    parser.add_argument('--sync', action='store_true')
    parser.add_argument('--expect-last', type=int)
    parser.add_argument('--reason')
    parser.add_argument('--previous-binding', type=Path)
    parser.add_argument('--bootstrap-unbound', action='store_true')
    parser.add_argument('--artifact', action='append', default=[])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.audit is not None:
        print(json.dumps(audit(root, args.audit), indent=2, sort_keys=True)); return
    path = root / LEDGER
    lock = None
    try:
        if args.reserve is not None or args.fail is not None or args.sync:
            if args.expect_last is None:
                raise ValueError('Writes require --expect-last')
            lock = os.open(str(path) + '.lock', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        # Every mutator reads AFTER acquiring its exclusive lock. Reading first
        # permits a paused stale allocator to overwrite an already-issued ordinal.
        data = read_json(path)
        if args.reserve is not None:
            if data['allocations'][-1]['status'] == 'reserved':
                for target, desired in metadata(root, data).items():
                    if target.read_text() != desired:
                        raise ValueError('Current version metadata must match before allocating')
            previous = read_json(args.previous_binding) if args.previous_binding else None
            if previous:
                prior_commit = previous.get('source_commit')
                if not isinstance(prior_commit, str) or not HEX40.fullmatch(prior_commit):
                    raise ValueError('Previous binding needs its exact committed source')
                if source_identity(root, prior_commit) != previous.get('production_state_sha256'):
                    raise ValueError('Previous binding source fingerprint is not reproducible')
                git(root, 'merge-base', '--is-ancestor', prior_commit, 'HEAD')
            current = source_identity(root, clean_source(root, 'HEAD')) if previous else None
            data = reserve(data, args.reserve, args.expect_last, current, previous, args.bootstrap_unbound)
            atomic_write(path, json.dumps(data, indent=2) + '\n')
        elif args.fail is not None:
            data = mark_failed(data, args.fail, args.expect_last, args.reason)
            atomic_write(path, json.dumps(data, indent=2) + '\n')
        else:
            validate(data)
        if args.sync or args.check:
            if args.sync and args.expect_last != validate(data):
                raise ValueError('Stale expected ordinal')
            for target, desired in metadata(root, data).items():
                if args.sync:
                    atomic_write(target, desired)
                elif target.read_text() != desired:
                    raise ValueError('Version metadata mismatch: ' + str(target.relative_to(root)))
        if args.check_source:
            clean_source(root, 'HEAD')
        info = identity(data, active=args.fail is None and args.bind is None)
        if args.bind is not None:
            if not args.output or args.output.resolve().is_relative_to(root):
                raise ValueError('Post-commit binding output must be EXTERNAL to source checkout')
            value = build_binding(root, data, args.bind, args.artifact)
            # Bindings are evidence: never overwrite an earlier candidate/receipt.
            with args.output.open('x', encoding='utf-8') as stream:
                json.dump(value, stream, indent=2, sort_keys=True); stream.write('\n')
        if args.stamp_app is not None:
            for target, desired in metadata(root, data).items():
                if target.read_text() != desired:
                    raise ValueError('Sync source version metadata first')
            app = args.stamp_app
            plist = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
            if (plist.get('CFBundleIdentifier'), plist.get('CFBundleShortVersionString'), plist.get('CFBundleVersion')) != (info['macos_bundle_id'], info['version'], info['macos_build']):
                raise ValueError('Built app metadata does not match canonical identity')
            value = build_binding(root, data, 'HEAD')
            with (app / 'Contents/Resources/LipBuildIdentity.json').open('x', encoding='utf-8') as stream:
                json.dump(value, stream, indent=2, sort_keys=True); stream.write('\n')
        if args.field:
            if args.field not in info:
                raise ValueError('Unknown identity field')
            print(info[args.field])
        else:
            print(json.dumps(info, sort_keys=True))
    finally:
        if lock is not None:
            os.close(lock)
            Path(str(path) + '.lock').unlink()


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError) as error:
        raise SystemExit('Lip release identity REJECTED: ' + str(error))

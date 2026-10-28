# One Lip release identity

`docs/build-iterations.json` is the authoritative allocation ledger. The current
**Lip v0.010** is **reserved source, not a published release**. Android versionCode
50007 and macOS build14 are derived from it, above the retained50003/build10 floors.
Minimum-three-digit display ordinals are not semantic-version patch counts.

## Audited seed, not a lifetime counter

The read-only Git audit through
`13d87e4439d38811a4c82a8bab747f1d7443d88c` finds10reachable commits,
0empty projected states and **6distinct production-input states**, including
4first-parent states and2additional merged-branch states. All reachable branch
history is included—not unrelated/unmerged refs or uncommitted development.
This is not an exported-build, PR, test-run or provable lifetime iteration count.
The first identity feature reserved6+1=7; the next distinct Android vocabulary
feature reserves8; the macOS counterpart reserves9; personal neural swipe reserves10. All issued ordinals remain
in the ledger.

The ledger contains the exact production projection. The audit uses sorted raw
`git ls-tree -r -z` mode/type/object/path records, omits empty states and globally
deduplicates identical identities. Tests, docs/site, CI and development fixtures
are excluded. Gitlinks are counted as opaque pinned inputs, not recursively
reconstructed upstream histories. No blobs/models are read or fetched. Missing
objects fail closed. The sorted identity-set SHA256 is:
`26cb9dcf14b2729b5fec1d89bf9e0b9d30a43cb28d40956f9e83e7e1bd0f6a25`.

```sh
python3 tools/release_identity.py --audit
python3 tools/release_identity.py --check --identity
python3 -m unittest tools/source_delivery/test_release_identity.py
```

## Canonical small installers; stable upgrade identities

- Android release: `dev.lip.android.preview` remains the public package ID for
  upgrades and its existing data/permissions/model caches. Namespace/JNI stay
  `dev.lip`. Ordinary debug remains `dev.lip.android`; isolated fixture IDs remain
  separate. New public labels use Lip v0.xxx, not Preview. The historical package
  ID is intentionally **not** renamed.
- `lipOfflinePreview=true` is retired and fails with canonical-release guidance.
  It cannot select the retired WAV/test runner or old naming. The model-bundling
  guard remains fail-closed. Both Clean and Pro remain separate explicit in-app
  downloads; no install/start/update auto-download is introduced.
- macOS ID stays `dev.lip.local.mac`, executable `LipLocal`, installation path
  `~/Applications/Lip Local.app`, macOS26+/Apple Silicon. Changing the displayed
  version does not move history/models or reset grants. The installer still
  refuses to overwrite an existing app and never falls back to ad-hoc signing.
- Android16+/ARM64 scope is unchanged. Actual installer size, signing, native
  behavior and update compatibility still require package qualification.

After an authorized signed release build, `:app:canonicalReleaseApk` verifies the
actual output metadata/package version in an always-executed prerequisite before
copying (even when Copy itself is NO-SOURCE/UP-TO-DATE) to the ledger-derived
`Lip-v0.010-android16-arm64.apk`. No unsigned/stale-output fallback. The corresponding
Mac artifact names are `Lip v0.010.app` and
`Lip-v0.010-macOS26-apple-silicon.zip`. Printing a name does not produce or release it.
The Mac install script checks a clean committed source and writes generated
`Contents/Resources/LipBuildIdentity.json` before local signing. This generated
receipt is not a tracked/self-referential source input or a notarization claim.

**The actual published download remains build50003**, with its original
`0.5.0-preview.3-offline` name,755MB APK, URL, certificate and compatibility.
README/site links are not relabelled as v0.010. There is no new download in this PR.

## Allocation and post-commit binding

Same-candidate packaging/signing/CI retries are idempotent only when the supplied
previous binding reproduces the SAME current production state. A changed bound
state cannot reuse the same key/ordinal. Before the first accepted binding,
`--bootstrap-unbound` explicitly reuses the current reservation for development;
it is not a source-identical claim and cannot revive an earlier/failed key.
A new candidate needs a
new committed production projection, verified against the previous external
source binding; docs/tests-only or identical projections cannot advance. Use
`--expect-last` for allocation/failure/sync writes. A short exclusive ledger lock
prevents simultaneous allocators; stale locks fail, never auto-delete evidence.
Failed issued ordinals and reasons remain forever; no reusing retired names.
Allocation/failure/binding and `--sync` must be separate invocations. Mixed modes
are rejected before a ledger read, lock or write; allocation cannot partially
advance the ledger and then fail a stale sync check. Canonical APK packaging also
refuses every fixture mode; fixture release guards and isolated identities remain
unchanged, never redirected to the public production package.

```sh
# AFTER an authorized source commit, outside the checkout; no publication:
python3 tools/release_identity.py --bind HEAD --output /outside/checkout/lip-source.json
# Optional exact canonical artifact checksums are added with --artifact PATH.
# A later distinct committed production feature, not docs/tests/packaging:
python3 tools/release_identity.py --reserve stable-feature-key --expect-last 10 \
  --previous-binding /outside/checkout/lip-source.json
python3 tools/release_identity.py --sync --expect-last 11
# Rejected issued candidate, never reused:
python3 tools/release_identity.py --fail stable-feature-key --expect-last 11 \
  --reason 'Short concrete failure reason'
```

External bindings name the exact source commit, production-state SHA and optional
artifact bytes/SHA. They are exclusive per-path new files, never overwritten. They
are snapshots, NOT a global immutable binding registry: another external path can
record another HEAD. Final release review must select the single exact accepted
source/artifact binding; merely creating a file does not freeze or publish it. A failed
candidate may be source-bound without making it buildable/released. Bindings do
not establish signatures, runtime success, provider acceptance or publication;
those remain separate release evidence. Source publication and delivery continue
through reviewed PRs and explicit authorization.

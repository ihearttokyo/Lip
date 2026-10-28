# Historical research, not active features

[`legacy/manifest.json`](legacy/manifest.json) preserves code/text missing from
main after the Bark/GPU consolidation at `13d87e4439d38811a4c82a8bab747f1d7443d88c`.
It covers all **45 distinct absent paths**, including **52 historical head
variants**, plus narrowly coupled helpers: **123 exact upstream text files**.
Each record identifies the original PR, immutable head commit, original path,
Git blob SHA, SHA-256 and size. Per-PR directories keep differing versions separate.

## What is preserved

| Historical PR | Reference scope |
|---|---|
| 1 | Original device-check, hill-climb and parity documentation; historical claims only. |
| 2 | Whisper DOTPROD experiment, native patch/provenance, NDK notices and workflow. |
| 3 | Initial-timestamp experiment, frozen pins/receipt and helper closure. |
| 4 | Linux Vulkan preflight, pins/tests and workflow. |
| 5 | Smaller-Whisper quantization screen, pins/receipt and helper closure. |
| 6 | Old Android Vulkan cross-build harness and helper closure. |
| 7 | Moonshine canary, pins/receipt and helper closure. |
| 8 | Physical-metadata probe and its paired missing unit-test source, reference only. |
| 9 | Isolated old Android CPU/Vulkan benchmark-package harness and helper closure. |
| 10 | Full-wave peak-normalization diagnostic and its updated quantization version. |

Already-identical files and intentionally superseded production versions from
PRs 1–14 are classified in `changedPathDispositions`; their exact original
commits/blobs are referenced instead of overwriting current code. In particular,
old cloud login/Whisper/permissions/packaging behavior is **not restored**.
No branch or PR was deleted or closed as part of preservation.

## Deliberately inert

Every copied original ends in **`.reference`**, is non-executable, and retains
its original bytes. Original filenames live in the manifest. Workflows are only
nested reference text, never installed into active `.github/workflows`.
There is no build, test-discovery, source-set, instrumentation or runtime hook.
No models, audio, databases, caches, credentials or SDK binaries are included.
A bounded heuristic sensitive-value scan found no hits; it is not a guarantee.

The archive is **not a runnable standalone checkout**. Historical scripts assume
the original repository root, exact Whisper submodule/tool/source pins, original
branch/marker guards, and sometimes large network/model/native jobs. To study or
run a probe, first use its **original immutable head commit** in a separately
reviewed checkout; retain its pinned dependencies and guards. Simply renaming
references, pointing them at current main, or merging old workflows is unsafe.
A reference or historical receipt is not current Bark/GPU/quality acceptance,
model adoption, phone permission, cloud-login authority or an instruction to run.
Current Android 16+/ARM64 and checked Bark/GPU requirements remain unchanged.

The only local check supplied here reads paths, hashes, Git metadata and active
configuration text; it never imports archived modules or runs their tests.
It additionally checks original-head bindings where those Git objects exist,
reports missing objects without fetching them, and always checks file/blob hashes:

```sh
python3 -B research/legacy/check_archive.py
```

Porting a legacy feature requires a new scoped implementation/review. Phone,
provider, download, CI and release actions retain their own authority gates.

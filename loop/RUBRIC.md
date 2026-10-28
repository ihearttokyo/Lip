# Lip intended-use quality rubric v1

Frozen before candidate tuning, 26 October 2026. User target: **9.2 / 10**.
Review each coherent block separately: (1) shared dictation, (2) Android keyboard,
(3) installation/delivery. Each applicable weighted total must be >=9.2, without
rounding; no dimension below8.5; no critical/material primary-path failure.
No author-assigned acceptance score. Independent reviewer retains ratings privately;
author receives concrete defects/repro or PASS with build/evidence identity.

| Dimension | Weight | Required intended-use evidence |
|---|---:|---|
| Recognition / typing truth | 25 | real held-out speech/swipes; numerical/polarity anchors; honest OOV behavior |
| Editing correctness and reliability | 20 | actual editor/IME paths; original-caret ownership; no duplicate/stale/partial retry; recovery |
| Workflow / learning / feature coverage | 15 | English parity matrix tasks; novice setup and correction; no hidden missing feature |
| Privacy, safety and licensing | 15 | offline inference; password/incognito isolation; consent; original/model/data license receipts |
| UI, accessibility and communication | 10 | intended-size keyboard, contrast/focus, TalkBack, readable task controls; truthful diagrams |
| Performance and efficiency | 10 | physical p50/p95, cold/warm, sustained memory/energy; no forced hardware choice |
| Delivery / procedures / provenance | 5 | source-matched app/APK; update/rollback preserving data; concise repeatable setup |

Per-dimension 0/2/5 anchors, mapped linearly to 0/4/10 for weighted total:
0 = absent, unsafe, demonstrably false or broken primary behavior;
2 = plausible prototype/partial consumer path with substantial gaps;
5 = complete intended-use task with representative, reproducible evidence and
clear failure/recovery behavior. Intermediate values require concrete distinctions,
not extra test counts or another screenshot. Unrun evidence is UNQUALIFIED, not0
and not a guessed high score. Never round 9.19 to9.2.

## Hard gates (cannot be averaged away)

No credentials/audio/text uploads; no automatic sends or automatic clipboard mutation
during dictation/insertion. Explicit user Copy/Paste is allowed; clipboard-history
reads require separate opt-in and are disabled in private fields;
no stale/ambiguous retry, data loss, permission bypass, crash in primary path,
unlicensed redistribution, hidden model fallback or deceptive performance claim.
Held physical-phone/TalkBack/Gboard evidence stays unqualified. A compiler or
synthetic/path replay cannot prove these tasks on a real target device.

## Hill-climb discipline

Prospectively log baseline, hypothesis, exact candidate digests, expected metric,
untouched holdout and rejection rule. At most three materially distinct variants
per block before a structural review; no small-parameter endless polish. Preserve
failed variants. Freeze exact accepted identities and move to the next major gap.
Acceptance criteria/weights/sample admission may only change through a versioned
oracle-change note from new authoritative evidence, never to rescue a result.

The 9.2 quality gate is NOT a claim of92% swipe accuracy, nor proof that Lip is
better than Gboard. Superiority has its own paired benchmark and confidence gate.


## Fixed block applicability (no post-result N/A exclusions)

All seven dimensions and the table's unchanged weights apply to each of the three
blocks: shared dictation, English Android keyboard and installation/delivery.
A dimension is judged at that block's intended use, not excluded (e.g. delivery
accuracy means exact artifacts/procedures, delivery editing-reliability means
migration/rollback data safety). Missing evidence is UNQUALIFIED. No reweighting.
Atomic task/platform/editor requirements are frozen in `docs/keyboard/cases.json`.
Each required case has its own negative control and raw assertion; one generic
layer-labelled receipt cannot qualify a bucket. Physical evidence cannot be
replaced by emulator output and independent judgments must bind to exact artifacts.


Jared's new visual requirement adds a separate fourth gate: the Android material/
reference-fidelity rubric in `docs/keyboard/VISUAL-CONTRACT.md`, independently>=9.2.
Its fixed visual weights apply only to that gate; existing three block weights and
hard gates remain unchanged. Whole-keyboard quality cannot average away visual failure.

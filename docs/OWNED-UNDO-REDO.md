# Lip-owned Undo / Redo

Tap the keyboard's Undo toolbar icon to open adjacent Undo and Redo buttons.
Opening the panel does not edit text. Pending Lip dictation is canceled first.
There is no native-host-undo fallback: host undo can reverse edits Lip did not own.

The journal holds text deltas in RAM for the current visible editor session. It
has **no fixed entry-count limit and no automatic oldest-entry eviction**. This
is not infinite storage. Available process memory is finite; allocation refusal
pauses journaling and clears replay authority, while manual keys still dispatch
once. Reopening the editor resets the paused journal. No journal file, backup,
cloud upload, clipboard write or automatic global typing archive is created.
Closing/hiding the keyboard, changing editors, screen lock or process death
clears it. This is not persistent recovery.

## Supported text operations

Plain typing is coalesced into word/boundary groups; repeated adjacent backward
deletions are grouped. Swipe, dictation, emoji (including variants), saved-clip
insertion, next-word insertion, newline, selected-span deletion and spelling
correction/revert are owned text transactions. New edits discard the redo branch.
Undo restores the original text **and selection direction/range**; Redo restores
the owned result. Android's SurroundingText API normalizes reversed ranges, so
Lip combines its fresh bounded content with stable, corresponding framework
selection observations. Final reverse-selection restoration remains in flight
until an actual framework direction observation strictly newer than the real
pre-dispatch selection baseline plus fresh content confirms it. The baseline is
published after eligibility checks immediately before selection IPC; pre-forward
metadata, normalized bounds and the outgoing command alone never confirm direction.
Completion after the 250ms selection deadline is refused even when a slow call
finally returns matching data. Missing/refused acknowledgement never causes
another selection/text dispatch or rollback. Image/GIF/sticker commits, opaque host clipboard commands,
Send/editor actions, hardware input and DPAD cursor navigation are explicit
barriers rather than fabricated text deltas.

Only ordinary, interactive, unlocked, visible fields are read. Password,
incognito/no-personalized-learning and unknown fields are never journaled.
Unknown composition, a moved caret, editor/connection changes, changed content,
missing/slow snapshots or ambiguous dispatch revoke replay authority. A host can
transform/reject text or fail to supply sufficient surrounding text; then local
Undo is unavailable, not silently redirected to another editor or host history.

Each capture reads only the relevant edited span and at most 32 UTF16 units of
context on each side. A single text delta/selection is limited to 65,536 UTF16
units for bounded editor IPC; this is not an entry-count cap. Larger edits still
dispatch once but form an explicit unsupported journal barrier. Long adjacent
typing/deletion groups split at that per-transaction boundary rather than
silently dropping history. Remote InputConnection calls are ordered, and actual
bounded post-read content/selection—not a Boolean or selection event alone—is
required before an entry becomes undoable. Inverse selection and replacement
are each single-use, observed operations; ambiguous writes are never retried or
rolled back. Pending owned selection acknowledgements may be coalesced by the host, but
coordinate equality is not proof of ownership: each consumes a fresh bounded
actual-head/content observation. Explicit word-drag selections publish their
exact requested content/selection before IPC; unrequested caret departures
invalidate the chain. Reads
are not a proof that an unrelated region of the entire document never changed.

## Evidence boundary

Focused JVM tests execute the actual pure journal/coordinator against a synthetic
editor port: rapid typing/no dropped dispatches, exact Unicode text and selection
restoration, all text kinds, branching, 5,000 retained entries and reverse replay,
bounded reads, synchronous/delayed acknowledgements, ambiguity, reentrancy,
privacy refusal, caret ABA and synthetic allocation failure. They do not prove
real Android editor IPC timing, popup touch/layout/accessibility, IME composition
interoperability, real memory exhaustion, or parity across arbitrary apps. Those
actual-editor checks remain required before release acceptance.

## Recorded scoped evidence — 29 October

- 22 focused coordinator tests and the full 696-case JVM suite (683 executed,
  13 skipped) pass with zero failures/errors. Lint and ARM64 app/test packaging
  pass. Independent static review found and verified fixes for caret ABA,
  controlled word selection, stale directional observations and selection expiry.
- On an owned offline API36/ARM64 emulator, eight named real-touch controls pass:
  adjacent labelled popup open/dismiss without edits; rapid typing grouped
  Undo/Redo; new-edit redo branching; reversed selected emoji-span text and
  direction restoration; real emoji picker Undo/Redo; field-departure revocation;
  password refusal; incognito refusal. 57 touchscreen taps and three explicit
  keyboard-tools navigations were observed; no host Send/action was dispatched.
- Failed variants are retained privately: initial keyboard activation was absent;
  popup roots/navigation and animated target geometry exposed harness gaps; real
  reversed-span replay exposed Android's normalized SurroundingText selection.
  The final controls require fresh actual selection callbacks and content, not
  synthetic direction stamps. No first-show failure or editor-wide gap is waived.
- Runtime uses transitional unbundled v0.010 debug packages, explicit IME rebind
  after app updates, and synthetic text only. It is not cold activation, public
  signing/update, physical-phone, WebView/composition matrix, TalkBack, real memory
  exhaustion, speed/energy or full 9.2 acceptance evidence. No release published.

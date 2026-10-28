# Android extended text-editing panel

Open **Lip keyboard menu → Text editing**. The real native menu item is disabled
for missing/private/no-learning, hidden, busy or composing contexts. The panel
replaces the key area, preserving the toolbar, black background, graphite keys
and white labels. Its scrollable controls remain reachable when the available
height is short. **ABC** returns to letters.

## Available controls and semantics

| Control | Explicit host request |
|---|---|
| Select all | `android.R.id.selectAll` |
| Cut / Copy | Corresponding context-menu action; requires a nonempty selection, including backward selections |
| Paste | `android.R.id.paste`; no clipboard inspection or clipboard-presence probe |
| Left / Right / Up / Down | Paired native DPAD events; host layout/grapheme movement, not guessed UTF16 offsets |
| Select / Select on | Local checkable selection-mode toggle; no host action by itself. Subsequent arrows/endpoints request a bounded native Shift chord |
| Line start / Line end | Native Home / End requests |
| Text start / Text end | Native Ctrl+Home / Ctrl+End requests |

These are **tap-only** controls. A hold does not start repeat timers. No claim is
made for Gboard's full held-repeat behavior. Selection requests first verify that
the actual virtual keyboard reports CHORDED modifier behavior, otherwise refuse
without sending keys. Each selection command then sends Shift DOWN, the paired
movement events, and Shift UP on the same captured connection; no Shift is held
between commands or merely because Select mode is on. A fresh context check after
Shift DOWN refuses movement if the editor departed; Shift UP cleanup still runs.
Ctrl remains movement-event metadata. Android's standard TextView
movement distinguishes line Home/End and Ctrl+Home/End document navigation; other
hosts may ignore or reinterpret these requests. Unsupported behavior is not
replaced with arbitrary offsets, extra commands or text fallbacks.

## Ownership and privacy

Each physical contact captures one metadata-only ticket: actual connection
identity, editor incarnation/edit revision, field/package identity, current
selection, field policy and panel incarnation. An actual accessibility ACTION_CLICK without a physical contact captures at
explicit activation; a duplicate queued native click cannot mint a new command.
Keyboard-button activation similarly captures at its initial key DOWN. A contact ticket is consumed once to form a claim. After potentially reentrant
cleanup, the exact pre-cleanup context is freshly compared and a second CPU-only
one-use admission consumes that claim immediately before the first host command.
Lip's deliberate editor-revision increment occurs only after that comparison;
it is not a wildcard accepting cleanup-induced revision changes. Moved/ABA caret, editor restart, another connection, changed
policy, panel replacement, cancellation, lock or hidden keyboard cannot revive
it. An unrelated old contact cannot cancel a newer ticket. Detaching/closing a panel
also revokes claimed-but-not-yet-dispatched intent; reattaching the same View
cannot revive it. Exact panel-incarnation guards prevent a late old-panel close
from canceling a newer panel's ticket. The parent panel gates the unsplit touch
stream: a second pointer cancels all pending/claimed contacts before child
splitting, sends native child CANCEL, and consumes the remainder until every
pointer lifts (or the stream cancels). A child cannot turn that second pointer
into a fresh destructive activation; the next independent single-pointer,
accessibility or keyboard activation can start only after the block ends. A
separate panel interaction identity advances on cancellation/close and is
captured before external cleanup. Final admission and post-Shift continuation
require that exact identity as well as open/unblocked state: completing an entire
multitouch cancellation stream cannot revive an older already-admitted chord.

Password, private/no-personalized-learning, unknown, composing, disconnected,
hidden and locked contexts refuse commands. Selection mode is RAM-only and
resets on panel/context departure or restriction. The panel has no field-text,
clipboard reader, history-store, provider, dictation-cleanup or Send API. It does
not register a clipboard listener or enable clipboard history. Existing optional
clipboard history retains its separate explicit consent/policy.

Cut/Copy/Paste are manual host commands only. Ordinary dictation still does not
replace the clipboard. Clipboard commands, Select all and navigation are opaque
owned-Undo barriers; Lip never fabricates an exact journal delta from an
unobserved host operation. This does not add a host-undo fallback.

Every native key DOWN is followed by one UP cleanup attempt on the **same old
connection**, even if DOWN returns false, throws or changes focus. UP never
follows a newly focused editor. Selection's outer Shift UP cleanup also runs when
movement UP fails. If Shift DOWN fails or context changes afterward, no movement
DOWN is sent, but cleanup remains mandatory and the result is unconfirmed.
These synthetic events do not attest or restore a pre-existing physical Shift
state; interactions with held hardware modifiers and arbitrary hosts remain
unqualified. False/throw/unknown results never trigger retries,
a second DOWN, another command or a text fallback.

## Honest results and prospective acceptance

"Editing command sent to editor; completion not verified" means dispatch only.
InputConnection Booleans are not content/selection/clipboard acknowledgements.
Unconfirmed requests tell the user to check the editor and are not retried.

The prospective negative controls are fixed before actual GUI results: private
and no-learning fields perform zero new editing/clipboard requests; a held Cut
contact cannot follow a changed field/caret/render; ACTION_CANCEL cannot dispatch;
DOWN failure/reentrancy still attempts old-port UP only; unknown host commands do
not acquire a fallback; selection uses the actual host's grapheme/layout behavior;
line and document endpoints must not be silently conflated; normal Fn/swipe/voice
and clipboard-free dictation remain unchanged. Actual native editor/clipboard,
Unicode/multiline/backward selection, real menu reachability, short-height scroll,
accessibility, refusal, lifecycle and local WebView behavior remain required
intended-use checks. Pure JVM tests and service compilation are not a substitute
for those checks and do not establish arbitrary-app or full Gboard parity.

### Recorded implementation correction

The first actual native editor run demonstrated that Shift metadata alone does
not extend a standard EditText selection (expected backward `4:3`, observed
collapsed `3:3`). Android 36's ArrowKeyMovementMethod reads selecting state from
the Editable buffer, while BaseMovementMethod strips event Shift from movement
modifiers. The bounded native Shift chord replaces that disproven assumption.
The original backward-selection oracle is unchanged; pure tests cover sequencing
and failure cleanup, not a substitute for the subsequent real-editor rerun.

## Recorded partial evidence —29October2026

**PARTIAL / unreleased. The final native driver result is FAIL, not acceptance.**
The full729-case JVM suite passes (716executed/13skipped,0failures/errors), with
32focused controls plus actual service compilation, lint and ARM64 app/test
packaging. Independent static review verified one-shot cleanup admission,
panel-close/multitouch interaction epochs and the bounded native Shift chord.

On an owned offline API36/ARM64 emulator, eight named real-touch/EditText groups
pass: Tools/open/Return without edits; Select all/Copy/Cut/Paste roundtrip;
split-child two-contact refusal followed by a new single Cut; all eight native
arrows/line/document endpoints; exact backward emoji selection/collapse;
host-refusal single dispatch; composition refusal/reset; stale Cut contact across
editor departure. Six actual Shift DOWN/UP pairs are balanced and the observed
normal-editor Shift buffer state returns to zero. The original owned-VM clipboard
payload was verified restored even on failed runs. These are synthetic native
editor controls, not physical/arbitrary-editor/TalkBack/full9.2 evidence.

### Unresolved failures and contrary evidence

- The selected password premise is observed as `0:7` before Tools. In the full
  sequence it becomes `0:0` when the native popup opens, BEFORE the disabled Text
  editing item is touched. Text and clipboard stay unchanged. All observed
  password-port selection/commit/delete/composition/menu/key and bounded-text-read
  counters are zero throughout the menu phases. This does **not** prove cause or
  establish caret noninterference/privacy acceptance.
- A matched fresh diagnostic (same IME, Tools→Back versus Tools→disabled item→Back)
  preserves `0:7` in both cases with zero menu-phase IPC/read deltas. That contrary
  evidence prevents attributing the full-sequence collapse to generic native popup
  behavior. No production caret restoration or weakened expected range was added.
- The final collector retains the password failure and attempts the independent
  incognito case once. Its served-field setup fails before private entry; incognito
  native qualification is therefore NOT RUN/UNQUALIFIED, never a password-counter
  proxy or a skipped pass. Safety/setup failure stops further actions.
- Earlier live input-type restart/menu absence and metadata-only selection failures
  remain retained. The navigation fixture correction used the preconfigured real
  multiline editor with unchanged eight endpoint assertions; it does not repair
  or qualify live restart/initial activation. The successful Shift implementation
  kept the original `4:3` and emoji-range oracles unchanged.

Runtime above uses transitional unbundled v0.012 debug packages and explicit
owned-VM IME rebind after app updates; no public signing/update/cold-start claim.
No microphone/speech inference, model download, private-history/provider/cloud
operation, runtime permission change, physical-phone or installed-Mac action was
performed. Only the owned VM IME was explicitly rebound; ordinary bundled swipe-
model warming was not ruled out by this driver. No release was published. The selected-private-caret and
incognito setup gaps remain OPEN, alongside held repeat, broader editor/RTL/
accessibility, physical modifier interactions and full product/release gates.

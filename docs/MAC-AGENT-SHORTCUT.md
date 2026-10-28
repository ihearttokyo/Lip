# macOS completed-dictation agent finish

## Available in this source iteration

- Ordinary hold/double-tap Fn keeps its existing recording and optional cursor
  insertion behavior.
- **Option+Fn finishes an existing recording for the chosen agent intent.**
  Option-first/Fn-first both work, including pressing Option while Fn is held.
  Both release paths are consumed locally by the gesture state machine so they
  cannot become another recording start or ordinary cursor insertion.
- The alternate chord never starts idle recording or sends a previous transcript.
  During pending recording preparation it cancels that start; during transcription
  it does not redirect the existing operation.
- Settings store a desired provider and Instant / Medium / High / Extra High / Pro,
  with NONE / Medium defaults. These are UI modes, not provider parameters. Pro
  may denote a model/product rather than a reasoning-effort setting.
- Agent-finish output remains in Review and **does not insert at the cursor**,
  including when a route is unavailable, settings change, or validation fails.
  Ordinary Fn finish does not dispatch an agent action.

## Explicitly unavailable

**No real production adapter is admitted.** Selecting ChatGPT, Claude or Paired
Codex does not connect an account or authorize any transport. Alternate finish
currently performs local recognition, keeps its result in Review and reports
that nothing was sent. There is no draft/share/URI substitute, browser/app
launch, clipboard use, Send automation, API call, provider login or automatic
fallback/retry. Installing an app or finding a URL scheme is not admission.

The producer/coordinator seam requires successful current recognition and frozen
model/language metadata. Empty, failed, canceled, superseded or audio-limited
recognition cannot substitute an older successful query. Cancellation, sleep,
permission revocation and settings revisions revoke handoff ownership without
clearing the existing raw/Review text. Equal successful settings saves receive
new opaque identities; failed saves keep the last confirmed process snapshot.
Preferences persist desired UI values only; they contain no query, account,
credential or token. UserDefaults confirmation is an in-process observation,
not an fsync or cloud-sync guarantee.

A saved desired provider is not a privacy capability. With no admitted adapter,
the agent coordinator adds no AX metadata monitor or field-content reads,
and does not contend with the existing single history observer. Existing optional
cursor-insertion capture and separately consented history capture are unchanged;
the former can read its bounded field context before the finish intent is known. The pure seam accepts an
independently verified privacy epoch for a future adapter; none is supplied in
production. That future admission still needs actual capture-to-completion
privacy/permission/lock coverage, exact destination/account disclosure, supported
mode/length mapping and intended-app qualification. Generic macOS private-browser
mode detection is not established; unknown/private context cannot be waived by
an adapter. History's one-use privacy claim must not be consumed or silently
reused as an agent authorization lease.

A future adapter invocation consumes one immutable completed-result attempt
before callbacks. Exact adapter/account-descriptor identity is checked after
external freshness callbacks; account/capability changes require revocation
before mutation. Errors/unknown results are ambiguous and are not retried. A
local dispatch is not recipient acceptance or remote exactly-once execution.
Payloads and completed sources are RAM-only; revoking a String reference is not
claimed to cryptographically erase copies already passed to an invoked adapter.

## Evidence

`--test-mac-agent-shortcut <public-contract-json>` executes the actual pure Swift
coordinator, preferences and gesture state machines using injected synthetic
adapters/privacy validators and memory-only settings. It compares actual Swift
enums/defaults with `docs/contracts/agent-handoff-modes-v1.json`; no provider
parameters are invented. No GUI, model, microphone, production Keychain or actual
route is used by those tests.

Physical modifier-key delivery, installed/signed-app behavior, Settings visual
layout/accessibility, actual privacy leases and destination acceptance remain
separate unrun intended-use checks. No installed app or permission was changed.

## Recorded scoped checks —29October

The actual all-source macOS26/ARM64 executable linked against the resident pinned
FluidAudio objects/modules and ran the explicit CLI mode:55 coordinator/settings/
chord/shared-fixture assertions plus14 original Fn and6 manual-record gesture
assertions pass. Independent static review verified fixes for account changes
inside freshness callbacks and the typed post-invocation outcome boundary.
Four final-executable input controls refuse missing, oversize, FIFO and symlink
fixtures without entering the application branch. No installed application or
build-cache artifact was overwritten; this is an isolated unsigned CLI component,
not a signed app/resource-packaging or GUI/runtime acceptance claim.

The Android shared desired-mode fixture and full697-case JVM suite pass
(684executed/13skipped,0failures/errors). Fifteen retained requirement snapshots
match the original frozen bytes/hashes; this preserves scope, not a quality score.
Actual event-tap/modifier delivery, settings layout, microphone/local recognition,
future privacy leases and real direct/background destination remain UNQUALIFIED.
No real adapter, new account, provider request, permission change or release.

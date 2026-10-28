# Lip Local for macOS

Native menu-bar recorder using **Bark 3.0 Clean (standard) or Pro (optional)** with automatic Core ML acceleration.
Apple silicon only, macOS 26+, Swift 6.2+. No OpenAI login, API key, cloud inference,
telemetry or background model downloads. English-only ASR. Model-native output is now the default; existing explicit
legacy app-rule preferences are preserved. No second model or rewriting stage. Models compile once and stay resident while the app runs.

## Setup

```sh
desktop/scripts/install.sh
open "$HOME/Applications/Lip Local.app"
```

Choose/download models in Settings: Clean 448 MB, optional Pro 1.23 GB.
Downloads are anonymous, revision-pinned and size/hash verified; existing valid
files are reused. Cancellation preserves verified files. No silent model fallback.
CLI setup: `LipLocal --install-model clean` or `--install-model pro`.
The explicit legacy Pro download script remains available.
Installation requires an existing Apple-issued code-signing identity. The installer
uses the sole Developer ID Application identity, or the explicit
`LIP_CODESIGN_IDENTITY`; it refuses an ad-hoc fallback because that invalidates
privacy identity across rebuilds. Local signing does not notarize/upload the app.
The first build downloads the pinned FluidAudio dependency. Inference after
setup needs no network. First Core ML compilation may take several minutes.

Choose **Enable Fn controls / check permissions** in the Lip menu. macOS must
grant **Microphone** and **Input Monitoring** to Lip Local; restart it after
grants if needed. The listener now retries automatically after a grant; if macOS
requires a restart, quit and reopen Lip Local. `Lip !` indicates a permission
blocker, and the menu identifies the missing grant. Microphone approval alone
does not grant Input Monitoring. Permissions cannot be silently granted. Accessibility is required for optional clipboard-free direct insertion. Lip only
inserts into the original unchanged supported field/caret; otherwise Review keeps
the transcript. It never presses Enter/Send or automatically writes the clipboard.

In macOS Keyboard settings, set **Press Globe/Fn key to: Do Nothing**, and
remove any Apple Dictation **double-Fn** shortcut. Lip deliberately listens
without intercepting keys or changing global keyboard preferences. Other
apps binding Fn can conflict. Do not disable security protections to fix this.

## Controls

- Hold Fn for >=220 ms: record while held; release to transcribe.
- Double-tap Fn within 340 ms: keep recording after release.
- Tap Fn in locked mode: stop immediately and transcribe.
- An isolated short tap cancels the tentative recording.
- Menu Start/finish and Cancel work without Input Monitoring.
- Recording stops at 5 minutes; sleep/screen lock cancels active recording.
- The menu displays Recording / Transcribing / Ready. Click **Copy last
  transcript**, then paste where intended. Automatic insertion is not enabled.
  Copy explicitly replaces the clipboard. Original and formatted transcripts
  live only in RAM; **Copy original transcript** retains access to the unmodified
  Bark result. **Review original / formatted transcript…** displays both.

Audio is a temporary private WAV and is removed after success/failure/cancel;
an abrupt crash may leave that file in the OS temporary directory. No audio
retention or transcript history is promised. No app opens at login automatically.

## Verification

```sh
desktop/.build/release/LipLocal --self-test
desktop/.build/release/LipLocal --prepare
desktop/.build/release/LipLocal --transcribe mono-16khz.wav
```

Gesture tests simulate time/key transitions; they do not prove live Fn input,
TCC permission, microphone operation or recognition quality. Use a real-device
recording acceptance test after permissions are granted. CLI input must be mono
16 kHz, nonempty and <=5 minutes. See THIRD_PARTY.md for model attribution.

Content-free operational diagnostics are written only when state changes to
`~/Library/Application Support/LipLocal/fn-diagnostics.json`: permission booleans,
listener state, Fn-only event count, recording/transcribing booleans, cleanup mode
and transcript-availability/character-count metadata. No audio,
transcripts, other keys or editor data are included.

## Installed microphone probe (explicit test only)

`open -n -g "$HOME/Applications/Lip Local.app" --args --test-microphone`
launches a short-lived instance of the installed app. It refuses to record unless
that identity already has microphone approval. With approval it records for
three seconds using the same recorder factory as interactive dictation, invokes
Bark, and removes the audio. Its local `microphone-probe.json` receipt contains
only duration, peak amplitude, inference time and character count, not text/audio.
A passing probe still does not prove physical Fn-event delivery. It neither
requests nor grants permissions, and it does not replace the running menu app.

## Output settings (Mac 0.5.1)

Choose **Lip → Settings…** (Cmd-comma while Lip owns a window):

- **Bark native formatting (no rewriting)** (default): unchanged model output,
  including whatever punctuation, capitalization, numerals and fillers it emits.
- **Light formatting** (optional legacy app rules): normalize spacing, optionally capitalize the
  first word and append a final period when missing. Existing punctuation stays.
- **Filler cleanup (rules only):** optional removal of standalone um/uh/erm in
  unambiguous contexts, plus light formatting. Meaningful cues like 'well',
  'like', negation, repeated words, uppercase acronyms and numeric-unit contexts
  are not stripped. If cleanup would erase all text, keep the original.

These are deterministic local rules, **not a local LLM editor**. They do not
infer commas/questions or sentence boundaries, fix grammar/restarts/spoken
corrections, rewrite phrasing, translate or run while audio is being recorded.
Code, links, emails, paths, mixed-case identifiers and quoted text conservatively
bypass cleanup; that guard is a heuristic, not a full parser. Review output.

Preferences persist in local UserDefaults. Each recording snapshots its settings
at start. Changing settings reprocesses the last original when idle; changes
mid-recording apply to the next session. Originals/results are not persisted as
history or uploaded. CLI `--transcribe` still returns the raw model transcript.

After an installed update, copy any wanted in-memory result, then quit/reopen Lip
to load the new menu/settings. Its stable Developer ID signing requirement is
preserved, so this update matches the existing permission grants.

## Maximized native Bark features (0.5.1)

No extra model, API, Apple Intelligence setup or automatic text editor is used.
The model's punctuation, case and numeral outputs are preserved, not recreated
by regexes in native mode. Existing user-selected optional app-rule modes remain
available but are explicitly labeled as app rules, NOT model capabilities.

- Automatic `short8` encoder for audio <=7.75 seconds plus the evaluated 250 ms
  silence pad; `full15` for longer recordings, with FluidAudio's existing
  long-audio chunk processing. Only this same model's published functions load.
- Hardware selection is internal, automatic and not user-configurable. All Core ML
  components use `.all`, permitting Neural Engine, GPU and CPU placement per
  operation. Old manual accelerator preferences no longer constrain execution.
  Model details describe the allowed policy, not measured device utilization.
  The local Clean plan prefers ANE for the encoder and GPU for preprocessing.
  The Pro plan prefers ANE for the encoder and CPU for preprocessing,
  prediction decoding and joint. CPU participation is not all-CPU inference.
  No power/battery optimum is claimed without energy profiling.
- Review → Model details displays approximate native word timings (padding
  offset removed), processing time and the mean token probability. Probabilities
  are **uncalibrated**, not a percentage of correct words. Text/timings remain
  in memory unless an explicit CLI command writes its output.
- CLI `--transcribe-details mono-16khz.wav` exposes native result metadata.
  Core ML may print its known shape diagnostic to stdout before the final JSON;
  it does not imply inference failed. `--transcribe` remains plain native text.

Native Bark Pro has no configurable filler removal, list formatter, grammar
rewriter, translation, speaker labels, contextual dictionary or beam search in
this published greedy Core ML export. Native punctuation/capitalization/numerals
are learned output behavior, not guaranteed switches. In a synthetic local
formatting test the model retained 'um uh', used lowercase and spelled-out
numbers, and did not make a list. Do not advertise Wispr Flow parity from this
configuration. Automatic-placement short/long file-fed tests pass for both models.
Clean's synthetic test removed um/uh and produced sentence punctuation, but kept
numbers spelled out and did not create a displayed list. This is not live-speech
accuracy, energy, or full Wispr Flow parity evidence.


## 0.6.0 model selection and validation

Clean uses the Fluid Inference Core ML export's 15-second encoder, zero added
silence and its own vocabulary; Pro retains automatic short8/full15 selection
and the evaluated 250 ms pad. Changing model during recording/transcription is
refused. Missing downloads fail explicitly. No extra text model is installed.
All Core ML components allow automatic hardware placement, with CPU retained
where appropriate. First on-device compilation may take minutes.

The disposable insertion fixture tests exact at-caret Unicode insertion, surrounding
text preservation, unchanged clipboard counter, moved-caret refusal and selected-text
refusal. `--insertion-fixture --auto-run --event-route` explicitly tests the Unicode
event fallback. This is not proof of compatibility with every app or the chat composer.
An off-main-thread self-AX test setter crashed during development; AX insertion now
runs on MainActor and the fixture uses TextKit 1 to avoid self-AX layout deadlocks.

`desktop/scripts/inspect_compute_plan.swift MODEL_ROOT [--clean]` reports planned
placement only, not runtime utilization or energy. Core ML can print diagnostics
before JSON; retain stdout and parse the final JSON object. Native outputs stay
in memory unless an explicit CLI invocation or Copy action exports them.

## Encrypted dictation history: unreleased source integration

The installed Mac app is unchanged and still memory-only. The new source wires
**Local dictation history…** into the menu. It is OFF by default; the management
window asks explicit consent before future automatic retention. This is dictation
history, not typed/swiped history or cloud sync. Do not install/restart while the
existing Mac restart/RAM-transcript hold applies.

The source uses device-local Data Protection Keychain/AES-GCM, asynchronous storage
readiness, immediate unclaimed-save revocation, a metadata-only observed-context
lease and generation-fenced history-only status. Unknown/changed/protected context
is not retained. History-ready starts first prepare privacy; a 250ms scheduled
fallback records memory-only, with no late upgrade. Release/cancel before actual
start cancels that start. UI scheduling can take longer; no measured latency claim.
An already-claimed write may finish during Off; its confirmation drains queued IO.
Limits are 50 MiB / 2,000 entries; full storage stops saving without evicting older
records. Selected JSON export is explicitly decrypted; deletion is separately
confirmed. Ordinary text fields can contain secrets; automatic recognition is not
promised. Keybag availability is not proof of every graphical lock state.

`swift build --package-path desktop --skip-update` then
`desktop/.build/debug/LipLocal --test-history` runs synthetic/injected-key checks.
Platform adapters and native UI compile, but actual signed Keychain/AX/editor/UI/
lock/restart/fault behavior is NOT qualified by these tests. Source integration is
not feature acceptance, installed behavior, or full Android/Mac history parity.
See [integration contract](../docs/MAC-HISTORY-INTEGRATION-2026-10-27.md) and
[observed-context privacy contract](../docs/MAC-HISTORY-PRIVACY-2026-10-27.md).

### Recovery audio in development
Failed or input-limited recordings can be preserved in a private local recovery directory. Lip does not encrypt these audio files or upload them. Retention applies even if transcript history is off; manually remove recovered audio when no longer needed. Cancel deletes only the current recording, not prior recovered files. Folder/file permissions and system-backup exclusion are set, but external backup/sync software is outside Lip’s control.

## Portable vocabulary (source candidate, not release acceptance)

Settings → **Portable vocabulary…** opens explicit JSON import/preview/merge and
export. Use the same `lip.learned-words` v1 file as Android; review names/phrases
before confirming. No email, contacts or WhatsApp scan occurs. Coarse provenance
and maximum weights are retained; reimport does not inflate or duplicate entries.

The dedicated local AES-GCM store is private, excluded from automatic backup,
and uses a separate device-only nonsynchronizing Keychain key. Missing ciphertext
reads do not query Keychain. Corrupt/locked/lost-key data is preserved, never
replaced with an invented key or plaintext fallback. Actual production Keychain
access and GUI behavior have not yet been qualified.

Supported imported English words are ignored by spelling checks **inside Lip's
Review text views only**, using document-local spelling tags. Lip does not learn
words into the system/global dictionary, alter another app's spellchecking,
rewrite the transcript or bias Bark. Other languages/phrases remain portable
stored data, not a promise of speech support or external-editor acceptance.

Export produces **plaintext JSON**, deliberately to a new selected file. It can
contain private names; it is not an encrypted cloud backup or a secret detector.
Existing files/symlinks are refused; failure may leave a partial new file, with no
automatic retry. No automatic Drive upload/sync or provider compatibility claim.

Unicode16 behavior is bundled and hash-pinned rather than inferred from newer OS
tables. Source/format details and remaining gates:
[portable Mac vocabulary](../docs/PORTABLE-VOCABULARY-MACOS.md).

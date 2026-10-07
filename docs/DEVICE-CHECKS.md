# Physical-phone acceptance

Status: not run. Do not call functional parity complete from the emulator or
these test instructions. Use the signed v0.2 APK, an eligible ChatGPT account,
Gboard and non-sensitive sample speech. Keep private audio/tokens out of Git.
The instrumentation runner is disposable-emulator-only: it changes that
emulator's accessibility configuration. Never run it on a personal phone.

## Local capture

For English, Japanese and Mandarin separately:

1. Explicitly install the offline language model. Turn network access off,
   choose Verbatim, focus a normal editor and start from the bubble.
2. Speak for three minutes, including a 20-second pause and immediate speech
   after an utterance endpoint. Move through the launcher into another app.
3. Confirm the bubble remains stoppable, partial text appears before Finish,
   and the first/last words and intentional repetitions survive without gaps.
4. Finish, review and explicitly Insert here in the new field. The original
   field must never receive a stale result. Keep Gboard selected throughout.
5. Repeat Cancel, lock-screen, password-field and competing-recording cases.
   Capture must stop and late results must not restart or insert text.

Record phone/OS/provider/model versions, raw transcripts, observed gaps,
first-partial and stop-to-final times, silencing, Bluetooth behavior and
thermal/memory effects locally. An unsupported or early-ending native provider
fails acceptance; test a compatible provider or integrate a licensed local
engine rather than hiding interruptions with a restart loop.

## Authenticated cleanup

Enable network access and follow the system-browser ChatGPT sign-in/consent
flow. Confirm account eligibility, model catalog, completed cleanup, refresh
and local sign-out. New sign-in discloses live segment requests and plan usage.
Existing installations must not enable live preview merely by upgrading.

Test these meanings, not just the presence of prompt instructions:

- English: "Let's meet at four pm, actually three pm. New line. One apples,
  two bananas, three oranges." Result keeps three pm and the ordered list.
- Japanese: "えっと、会議は四時、いや三時です。改行。買うものは一つ目りんご、
  二つ目バナナです。" Result keeps 三時 and the two list items in Japanese.
- Mandarin: "呃，会议四点，不，三点。换行。第一苹果，第二香蕉。" Result keeps
  三点 and both list items in Mandarin.
- Each language: explicit punctuation, ordinary/quoted punctuation words,
  negation, numbers/units, URLs, code identifiers and a saved uncommon name.
- Light retains every word; Verbatim sends no cleanup request. Dictated
  instructions/questions stay text, never tasks for the model to execute.

Observe a spoken correction in live preview before Finish. Turn Live preview
off during its delay/lookup: no new transcript inference may start, while final
cleanup remains available. Cancel cannot recall already-sent requests.
Interrupted/failed cleanup must retain raw text and block automatic insertion.
Review actual output for factual changes; screenshots or prepared examples do
not establish model adherence.

## Editor and history boundaries

Use native, Compose and browser text fields; selected ranges; existing Gboard
composition; cursor/content changes; protected/custom editors; app transitions;
and an unconfirmed commit. Explicit new-target insertion occurs once, never as
a retry of an uncertain commit. Confirm history search, raw/full-text viewing,
retention off, individual deletion and confirmed clear. Upgrading v0.1 history
must preserve entries; the old APK cannot read the migrated format.

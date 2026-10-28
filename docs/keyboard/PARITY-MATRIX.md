# English parity inventory v1 — 26 October 2026

Status is evidence, not an implementation checkbox. Common capabilities require
both platforms; Android-only keyboard capabilities don't imply a Mac virtual IME.
All row IDs stay stable. Later language scope: Japanese and Mandarin.

| ID | User-facing contract | macOS baseline | Android baseline | Required proof |
|---|---|---|---|---|
| SH-01 | Clean standard / optional Pro; no silent model substitution | implemented/file-fed | implemented/host JNI | selected-session identity; missing/install/switch/cancel on actual app |
| SH-02 | Model-native punctuation/case/filler behavior; no extra LLM | synthetic model evidence | host model evidence | representative English speech; facts/numbers/negation preserved |
| SH-03 | Direct insertion, original caret, clipboard unchanged, never auto-send | fixtures PASS; chat UNCONFIRMED | overlay guards; IME absent | actual editor matrix; stale/refused/partial outcomes |
| SH-04 | Hold/locked capture, stop/cancel, limits and sleep/focus recovery | Fn implemented | overlay implemented | lifecycle cases, microphone routing, no stale result |
| SH-05 | Original/output/model details; safe explicit retry | implemented | partial | same result fields and review actions, confidence not accuracy |
| SH-06 | Model verification/download cancel/readiness/errors | implemented | implemented | corrupt/missing/interrupted/low-space/offline cases |
| SH-07 | Dictionary/settings/privacy semantics | partial/dictionary absent | dictionary saved, not model-biased | truthful support labels; same settings vectors, no fake biasing |
| SH-08 | Optional encrypted history and retention/export controls | no persistent history | encrypted history | disabled means no new saves; migration/restore; matching opt-in contract |
| KB-01 | Actual IME, taps, Shift/Caps, symbols/numbers, Unicode edit | not applicable | absent | actual InputConnection, editor actions and rotation |
| KB-02 | Neural swipe and three correction candidates | not applicable | absent | pinned model; normalization parity; real held-out traces and composing ownership |
| KB-03 | Predictions, autocorrect undo, personal dictionary | not applicable | absent | edit/cursor boundaries; incognito; no blind replacements |
| KB-04 | Cursor/delete gestures; long-press/repeat punctuation | not applicable | absent | gestures, selection, emoji clusters and touch exploration |
| KB-05 | Emoji search; one-hand/height/theme/haptics | not applicable | absent | intended-size usability, settings persistence and TalkBack |
| KB-06 | Password, numeric, email, URL, autofill/editor actions | not applicable | absent | no predictions/learning/voice in protected field; manual input works |
| KB-07 | Offline core; no hidden trace collection | not applicable | missing keyboard | network instrumentation, secure-data audit, consent and removal |
| KB-08 | Material measured superiority over Gboard | not applicable | NOT RUN / phone HELD | preregistered paired physical study; no inferred score |
| DV-01 | Google cloud Search/Translate/GIF/account services | out of local-first architecture | not replicated | explicit local substitutes or adopted scope change, never a parity claim |

The Android APK is currently an English dictation development build. None of the
absent keyboard rows or held device rows is satisfied by this document.

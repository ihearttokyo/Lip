# Lip

Open-source Android voice dictation with a floating bubble alongside your existing keyboard. Speech recognition runs on-device; ChatGPT-plan text cleanup is optional.

[Website](https://ihearttokyo.github.io/Lip/) · [Download v0.2.0 APK](https://github.com/ihearttokyo/Lip/releases/download/v0.2.0/Lip-0.2.0.apk) · [Privacy](https://ihearttokyo.github.io/Lip/privacy.html) · [Issues](https://github.com/ihearttokyo/Lip/issues)

**The downloadable v0.2.0 APK and the development source differ.** The published APK uses Android's on-device recognition provider. Development replaces that dependency with bundled whisper.cpp and an explicitly downloaded offline model; that engine is not in the v0.2.0 download. Neither version has verified functional parity. Speech accuracy, Android latency, microphone routing, third-party editors, and authenticated cleanup remain acceptance gates in the [evidence matrix](docs/PARITY.md). Lip is not Play Store-reviewed and does not replace Gboard.

## What it does

| Capability | Development source scope, not the published v0.2.0 APK |
| --- | --- |
| Floating dictation | Android 13+ accessibility overlay; user-triggered capture and current-editor insertion while Gboard remains selected |
| Local speech | AudioRecord PCM decoded locally by whisper.cpp 1.9.2; one downloaded multilingual model for English, Japanese, and Mandarin; no Android recognition-provider dependency |
| Optional cleanup | Official ChatGPT OAuth and public Responses API; eligible account, preview access, and usage limits apply |
| Live preview | Local window results while speaking; opt-in ChatGPT cleanup of stable segments; usable update latency is not yet established |
| Writing controls | Verbatim, Light (punctuation/casing/spacing), and Polished; local command formatting, optional semantic cleanup, dictionary prompts, and review-before-insert |
| Local history | Independent encrypted raw/clean records; paged search, copy, delete, clear, and disable future retention |
| Insertion safeguards | Reject protected fields and stale editor/focus/selection/content state; never send a message |
| Not included | Cloud audio, a swipe/replacement keyboard, cross-device sync, always-listening capture, or autonomous UI navigation |

The website's illustrated phone screens and before/after text are labeled illustrations, not screenshots or live recognition results.

## Installation

1. Use Android 13 (API 33) or newer with a compatible on-device speech-recognition service. Obtain `Lip-0.2.0.apk` from the [v0.2.0 release](https://github.com/ihearttokyo/Lip/releases/tag/v0.2.0). Check the release's SHA-256 and signing-certificate information before installing.
2. Android may request permission for the browser/file manager to install an APK. Allow it only if you trust this source; turn off that install permission afterward. Do not disable Play Protect.
3. Open Lip. Read the microphone disclosure and grant microphone permission. Read the separate accessibility disclosure, then enable Lip in Android accessibility settings. Sideloaded apps may require an additional system-controlled restricted-settings confirmation; proceed only after verifying the app and source.
4. Select English, Japanese, or Mandarin and check the device provider's model availability. This published APK still requires external-audio segmented recognition support. Unsupported or early-ending providers produce an error, not a cloud fallback or a silent restart loop.
5. Keep Gboard selected. Focus a supported text editor, tap Lip's bubble, speak, and stop. By default, successful cleaned or Verbatim text is inserted only into the unchanged original target. After switching apps, review the retained text and tap **Insert here** in the intended field; no silent rebinding occurs. Enable review-before-insert in Settings to approve each result. Use the in-app test composer if the overlay/editor path is unsupported.
6. History is enabled by default. Disable saving new history, delete individual entries, or clear existing history from Lip. Disabling retention does not erase earlier entries.

History no longer has a whole-collection 4 MiB ceiling. Each encrypted record retains a safety size limit; device storage still limits capacity. Existing v0.1 history migrates only after verified copies. The old APK cannot read the new record format: do not uninstall or clear app data to downgrade. Reinstalling this version without clearing data preserves access. Review remains inside the nonfocusable bubble.

A session ends on Finish/Cancel, permission loss, service loss, locking or protected-field focus. A visible 32,768-character safety boundary applies; there is no one-minute timer.

### Set up a development build

After building the current source, open **Microphone → Offline speech models → Install / verify**. This separately requested download is about 574 MB; keep at least 650 MB free and use an unmetered connection. Lip verifies the pinned size and SHA-256 before installation. Cancel or a failed transfer preserves an existing verified model. The model runs offline after installation, without a device speech-recognition service. See the [engine and model pins](docs/IMPLEMENTATION.md#local-speech-backend) and [current evidence](docs/PARITY.md#current-evidence). Important text still needs review; a local model can misrecognize names, numbers, and negation.

Accessibility is a powerful permission. Lip's scope is dictation into a selected editor, not screen scraping for cloud context, autonomous actions, or message sending. Password/protected fields are excluded. Disable Lip in Android accessibility settings to remove the bubble and insertion access.

## ChatGPT cleanup (optional)

Choose **Continue with ChatGPT** in Lip. The system browser handles OpenAI sign-in and user authorization; no API key or shared client secret is needed. Lip follows the official open-source dynamic registration flow, retains the issued client ID for that installation/account, and uses PKCE plus a `127.0.0.1` loopback callback. Return to Lip after browser authorization.

Only the transcript, cleanup instructions, and applicable dictionary terms are sent to OpenAI. New sign-in explicitly discloses live cleanup. When enabled, stable segments may be sent before Finish; Settings can disable Live ChatGPT preview independently. Upgrading does not opt existing installations into live requests. Cancel prevents queued requests and stale results, but cannot recall requests already sent. Preview and final cleanup use plan allowance. Surrounding editor content stays local. Requests use `store:false` and `stream:true`; a partial/failed stream is not a usable cleaned result. These settings do not promise zero provider retention. OpenAI's terms and privacy policies govern its processing.

Preview eligibility, account/workspace restrictions, models, and usage limits can change. This route does not grant audio-transcription access or general API credits. If sign-in, cleanup, or allowance checks fail, local dictation and the raw transcript remain available. Sign out clears local OAuth tokens and attempts provider-side revocation; account/client-registration metadata is retained for reconnection. If revocation fails, use your account controls to revoke authorization.

Sources: [registration/sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [models/inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [preview limits](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

## Build and checks

Pinned toolchain: JDK 17, Android SDK 36, Build Tools 36.0.0, NDK 30.0.16248370, CMake 4.1.2, Android Gradle plugin 8.13.2, Gradle 8.13, Kotlin 2.3.10. The wrapper handles Gradle; install the JDK and SDK components separately. Set `ANDROID_HOME` or your uncommitted `local.properties` SDK path. Clone with `--recurse-submodules`, or initialize the pinned native dependency in an existing checkout:

```sh
git submodule update --init --recursive
./gradlew testDebugUnitTest lintDebug assembleDebug
node docs/app.js --self-test
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. It uses a debug signing key and is distinct from the published signed prerelease APK. Production signing keys must stay outside Git. Toolchain references: [AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html).

To sign a release, set `LIP_SIGNING_PROPERTIES` to an untracked properties file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`, then run `./gradlew assembleRelease`. Without that file, the release build is unsigned and must not be distributed as an installable APK. Never upload signing keys or passwords to this repository.

To inspect the static site locally:

```sh
python3 -m http.server 8080 --directory docs
```

Open `http://localhost:8080/`. The page needs no build step, framework, remote fonts, account, or microphone. Its prepared examples and native HTML controls work without a backend. Clipboard access is user-triggered and falls back to selected text when unavailable.

## Architecture and privacy

```text
AccessibilityService → visible dictation bubble
                       ↓
             local AudioRecord → bundled whisper.cpp (CPU)
                       ↓
             local transcript / optional ChatGPT text cleanup
                       ↓
            unchanged-editor guard → AccessibilityInputConnection
                       ↓
            encrypted local raw/clean history (if enabled)
```

This diagram describes development source. The published v0.2.0 APK instead routes local PCM through Android's on-device segmented SpeechRecognizer. The system-bound accessibility service uses API 33's input-method connection, not destructive full-field `SET_TEXT`. It keeps the existing keyboard selected and captures only after a user action. Lip adds no microphone foreground service, ordinary overlay permission, or replacement IME. Cross-app microphone routing still requires hardware validation; a visible bubble alone does not prove capture works.

Android Keystore-backed AES-GCM protects retained credentials and transcript history in app-private storage excluded from backup. Copying text places it on the system clipboard outside that encrypted store. No audio files, analytics, advertising SDK, cloud history, or transcript logging are included. [Privacy policy source](docs/privacy.html) · [implementation and evidence contract](docs/IMPLEMENTATION.md).

### Failure behavior and known limits

- **Development model missing or invalid:** explicitly install/verify it from Microphone settings. Check free space and retry a failed download manually. The published v0.2.0 APK instead needs its device provider's installed language model and segmented-mode support. Neither uses remote recognition as a fallback.
- **Permission denied or microphone unavailable:** grant permission intentionally, leave calls/competing capture, and retry manually. Do not assume a visible bubble overrides Android's microphone rules.
- **Cleanup fails or allowance is exhausted:** retain/use the local transcript; reconnect or retry later rather than losing the spoken text.
- **Editor or cursor changes:** automatic insertion is blocked; review and explicitly choose Insert here at the intended field before any commit attempt. An unconfirmed dispatched commit is never retried automatically. No automatic focus restoration or simulated taps.
- **Custom/protected editor:** support varies. Test the in-app composer and use manual copy/paste if needed; do not bypass the editor's restrictions.
- **History/key loss:** uninstalling or losing the Keystore key can make retained data unrecoverable. No export/recovery or sync is provided in this release.

### Validation boundary

The [evidence matrix](docs/PARITY.md#current-evidence) is the authoritative home for measured speech results, Android timing, and remaining gates. Actual recorded-human decoding is distinct from deterministic tests, synthetic voices, and emulator screenshots. File-fed JNI decoding does not prove the guest microphone path or physical-phone performance. End-to-end OpenAI authorization and cleanup remain pending. Device checks must cover native/Compose/browser editors, selection replacement, keyboard composition, focus changes, offline use, permission denial, and protected fields. Do not use sensitive transcripts in public bug reports.

## Visual reference

### Actual emulator screen

These v0.1 visual-baseline images show Lip running on the isolated Android 16/API 36 emulator. They show the home screen, not microphone quality or authenticated ChatGPT access.

![Lip home on Android 16 emulator](docs/assets/android-home.png)

The floating bubble below is also a v0.1 emulator capture over a synthetic editor. It is not evidence of the development speech engine, microphone routing, or ChatGPT access.

![Lip floating bubble over the test editor](docs/assets/android-bubble.png)

The retained design input below is AI-generated concept art, **not an Android screenshot or evidence of app behavior**. The static site uses original SVG/CSS illustrations derived from its direction.

![Lip design reference, not a device screenshot](design/lip-reference.png)

## License and attribution

Lip source and original web artwork are [MIT licensed](LICENSE), copyright 2026 Jared Bland. Dependency and tool notices are in [NOTICE.md](NOTICE.md). Lip is not affiliated with or endorsed by Wispr, Google, or OpenAI. It contains no copied Wispr assets, code, logos, or testimonials.

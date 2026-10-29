# Lip

Local-first English dictation for Android and macOS, with an independent Android keyboard. No OpenAI login is required for local speech recognition.

**[Download Android — 0.5.0-preview.3-offline, build 50003](https://github.com/ihearttokyo/Lip/releases/download/v0.5.0-preview.3-offline/Lip-0.5.0-preview.3-offline-universal.apk)**

[Website](https://ihearttokyo.github.io/Lip/) · [Release notes and checksum](https://github.com/ihearttokyo/Lip/releases/tag/v0.5.0-preview.3-offline) · [Privacy](https://ihearttokyo.github.io/Lip/privacy.html) · [Issues](https://github.com/ihearttokyo/Lip/issues)

## Current Android download

- Android 13 or newer; ARM64 and x86_64. Signed APK, **754,958,683 bytes (about 755 MB)**.
- This existing release includes both **Bark 3.0 Clean** and **Bark 3.0 Pro**. No separate model download is needed. Speech recognition runs locally; English only.
- Independent keyboard with dark rounded keycaps, typing/swipe suggestions, emoji, a small local GIF catalog and opt-in clipboard history. This is not a patched Gboard or a claim of full parity/superior accuracy.
- Same application ID and signing certificate as preview.2. Update without uninstalling or clearing app data.

**Download SHA-256**

```text
61ebcf374aaf60005af55614b7215707fcb94b9ff909c79f58e0886db2c15880
```

Open the download link in **Chrome on Wi-Fi**, then check Chrome → Downloads or your file manager. Android may require permission for that browser/file manager to install the APK; keep Play Protect enabled. Follow the app's microphone and optional keyboard/accessibility disclosures. Do not grant accessibility permissions unless you want the corresponding insertion/overlay functions.

## What is verified—and what is not

The exact signed build 50003 was installed on an isolated ARM64 Android emulator with guest Wi-Fi/data disabled. Both packaged speech models passed four actual native, public file-fed transcriptions, idle cancel/rearm and checked close. Native model installation/no-clobber controls and application launch passed.

These checks do **not** establish physical-phone microphone performance, every receiving editor, GPU/NPU efficiency, full Gboard parity or the complete 9.2 product acceptance gate. The site screenshots are actual development-emulator captures with synthetic editors, not physical-phone or exact release-build screenshots.

Direct/background ChatGPT and Claude sending is **not verified or enabled**. No draft-sharing substitute is provided. Translation (including a future optional ChatGPT backend), expanded sticker support and synced typing history remain future work.

macOS Fn dictation is in development. No matching public macOS installer is offered on this page. Some insertion/editor paths remain unconfirmed; a safe failure must retain text for review rather than silently sending it or changing the clipboard.

## Future releases: smaller installer, separate models

Future APKs and macOS installers will **not bundle Clean or Pro weights**. Both models will be explicit in-app download choices, with sizes disclosed before consent. No automatic speech-model download on installation/start/update. Keep already downloaded verified models across updates.

New release identities will follow **Lip v0.xxx**, consistently across the app, package, release notes and site. A production-source iteration ledger will allocate numbers; test runs, PRs and identical packaging retries will not inflate them. The source-history audit and ledger tooling are implemented; **Lip v0.016 is reserved source, not a published release**. See [release identity](docs/RELEASE-IDENTITY.md). Existing release names, URLs and rollback packages stay unchanged.

## Source and release identity

Source reconciliation PRs **17 and 18 are merged** at `13d87e4439d38811a4c82a8bab747f1d7443d88c`. Current source includes the Android IME, checked local Bark, macOS app and automatic Android Vulkan route. New builds target **Android 16+/ARM64** and **macOS 26+/Apple Silicon**; GPU/NPU efficiency and physical-device acceptance remain unqualified. These are current-source targets, not new properties of build 50003.

**The post-release merged source is not established as an exact reproducible source match for the historical build 50003 APK.** GitHub's automatic archives are not retroactive source/artifact attestations. The reserved v0.016 candidate has no new public download. Android source adds [portable vocabulary import/export](docs/PORTABLE-VOCABULARY-ANDROID.md), encrypted local merge and typed-word protection; [macOS source](docs/PORTABLE-VOCABULARY-MACOS.md) adds the matching format/store/UI and Review-local spelling acceptance. [Personal neural swipe](docs/PERSONAL-NEURAL-SWIPE.md) now includes eligible approved words in one decoder; fixed Unicode16 transport is shared. [Owned text Undo/Redo](docs/OWNED-UNDO-REDO.md) adds adjacent controls and confirmed RAM-only replay; scoped offline-emulator controls pass, not an editor-wide or release qualification. [macOS agent finish](docs/MAC-AGENT-SHORTCUT.md) adds a distinct Option+Fn intent and desired modes; no real provider is admitted. [Text-editing source](docs/TEXT-EDITING-PANEL.md) adds native clipboard/navigation/selection controls; eight emulator groups pass, but selected-password caret and incognito setup checks remain unresolved and the full driver is RED. [Local sticker source](docs/LOCAL-STICKER-WORKFLOW.md) adds explicit local import, verified original-ZIP export, recoverable removal and receiver-bound original requests; actual scoped emulator workflows pass, not full feature qualification. [Toolbar customization](docs/TOOLBAR-CUSTOMIZATION-RESULT.md) adds five configurable middle shortcuts, inert preview, ordering, Save/Reset and confirmed-state protection; scoped settings/editor navigation checks pass. [Fine-grained docked height](docs/KEYBOARD-HEIGHT-RESULT.md) adds 80–140% draft/Apply/reset; measured portrait spacing changes and resets correctly on the owned emulator. [Frozen requirements and acceptance status](docs/ACCEPTANCE-STATUS.md) preserve the full scope. Actual Mac GUI/Keychain, reliable initial keyboard activation, physical/human swipe quality and full acceptance remain open. See [source delivery and remaining gates](docs/SOURCE-DELIVERY.md).

Older releases remain available under [Releases](https://github.com/ihearttokyo/Lip/releases) for recovery. Do not uninstall or clear data to downgrade without checking data-format compatibility.

## Privacy and credits

Speech recognition is local. No cloud inference or OpenAI login is needed for the current local path. Clipboard history is opt-in; it is not an unrestricted/global typing archive. Review recognized text, especially names, numbers and negation.

[Privacy details](https://ihearttokyo.github.io/Lip/privacy.html) · [Component and screenshot credits](https://ihearttokyo.github.io/Lip/credits.html). Upstream code, model, data and artwork retain their respective licenses. Lip is not affiliated with Google, Apple or OpenAI.

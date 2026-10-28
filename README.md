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

New release identities will follow **Lip v0.xxx**, consistently across the app, package, release notes and site. A production-source iteration ledger will allocate numbers; test runs, PRs and identical packaging retries will not inflate them. The starting ordinal still requires a source-history audit. Existing release names, URLs and rollback packages stay unchanged.

## Source and release identity

**The published APK was built from unreconciled local development source. Repository main and GitHub's automatically generated source archives do not yet represent that exact APK.** Source reconciliation remains pending; do not treat an archive attached to the release as a reproducible source match. Documentation publication does not close that gap.

Older releases remain available under [Releases](https://github.com/ihearttokyo/Lip/releases) for recovery. Do not uninstall or clear data to downgrade without checking data-format compatibility.

## Privacy and credits

Speech recognition is local. No cloud inference or OpenAI login is needed for the current local path. Clipboard history is opt-in; it is not an unrestricted/global typing archive. Review recognized text, especially names, numbers and negation.

[Privacy details](https://ihearttokyo.github.io/Lip/privacy.html) · [Component and screenshot credits](https://ihearttokyo.github.io/Lip/credits.html). Upstream code, model, data and artwork retain their respective licenses. Lip is not affiliated with Google, Apple or OpenAI.

# Experimental portable neural swipe candidate

Six unmodified, SHA-bound MIT engine files from Destin Moss's Swipe Keyboard.
Qt/GPL front ends and wider language-engine components are deliberately excluded.
C++ source can compile independently; this alone does not integrate swipe into Lip.
`source-manifest.json` pins the exact source; MIT.txt/NOTICE.md preserve attribution.
The candidate is under evaluation. No Gboard-superiority or9.2 claim is earned.

The Android adapter now explicitly links this library; see docs/keyboard/NEURAL-INTEGRATION-2026-10-26.md. Actual JNI synthetic controls pass, but production gesture/dictionary integration remains unfinished.

27October update: APK-bundled attributed weights/dictionary now feed real measured-geometry glide input. See docs/keyboard/SWIPE-IMPLEMENTATION-2026-10-27.md for exact scoped evidence and remaining quality/composing gates. Earlier host/JNI-only notes are historical milestones, not current feature completion claims.

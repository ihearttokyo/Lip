# Floating keyboard — scoped source and emulator evidence

The frozen [contract](FLOATING-KEYBOARD-CONTRACT.md) is unchanged. Tools offers
Floating keyboard; the real floating card provides Move, Resize and Dock, with
separate confirmed position/size preferences and accessible settings. Docked
height and one-hand preferences remain separate. This is not full parity or a
9.2 acceptance claim.

Offline unit/lint/app/test-APK builds pass: 802 JUnit cases, 784 executed,
18 explicit skips, zero failures/errors. Independent bounded source review passes.

An owned API36 ARM64 emulator with synthetic separate-UID native EditText showed:
- A transparent full-height IME window with only the card and Android-managed
  navigation strip in its native touch region. Four outside-card taps inside that
  window each reached the host as exactly one native DOWN/UP pair, unchanged text.
- An inside numeric 1 tap produced exactly the precomputed expected text SHA and
  caret, without delivering a host touch.
- Move shifted the actual native card from [73,1277][1007,2253] to
  [113,877][1047,1853], including 400px upward movement.
- Resize changed its actual native region to [113,877][977,2013], a real
  934x976 to 864x1136px layout change, not a bitmap scale.
- Dock restored the ordinary [0,1572][1080,2400] IME window and default visual
  five-row keyboard. Handle and Dock actions did not alter host text or touch counts.

The first post-install show did not bind the IME; explicit warm Gboard/Lip rebind
and one host field tap were required. Both failed initial and successful rebound
receipts are retained. This does not qualify first-install activation. An immediate
post-resize input dump preceded the completed layout; a separately retained settled
dump supplies the actual changed region, not the premature dump.

Resized-key hit testing, rotation/narrow windows, busy recording/ASR transitions,
full accessibility, write-failure and privacy/contact matrices remain unqualified.
The physical-phone hold remains. These development APK observations precede the
feature's next version reservation; no signed release or public download changes.

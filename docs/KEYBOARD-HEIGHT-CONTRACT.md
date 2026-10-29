# Fine-grained keyboard height — acceptance contract v1

Frozen before implementation on29October2026. Additive to the original full
rubric/screenshot contract, not a replacement or a new product qualification.
Baseline: toolbar source candidate d809ed7 (v0.015, not a release).

## Intended task

Gboard feature inventory and KB-05/LAYOUT-HEIGHT/SETTINGS-ROUNDTRIP require actual
height customization. Current Lip has only Compact/Standard/Tall. Add a reachable
Keyboard preferences height control with an integer80–140percent slider, exact
percentage label, accessible one-percent decrement/increment alternatives and
Reset standard. Preserve the original90/100/115percent presets and existing
screenshot-default geometry. This is adjustable docked keyboard height, not a
floating/repositionable keyboard or a font/emoji-size substitute.

## Fixed criteria

1. Versioned local UI-only preferences migrate existing v1/v2 without changing
   legacy preset results, hand, theme, haptics or independent label/emoji sizing.
   New values are bounded integer percentages; unknown/malformed/out-of-range
   values fail safely. No persistence of text, paths, audio or providers.
2. The actual keyboard's shared row-height/layout consumer uses the saved value;
   a slider or label alone is not implementation. Preserve existing device/window,
   inset, landscape and minimum/maximum geometry safeguards. Where capped, explain
   that actual height adapts to the window instead of claiming every requested
   percentage yields a distinct physical height. No offscreen controls or hidden
   full-screen touch interception.
3. A clearly inert local preview shows the applied geometry. Dragging previews a
   bounded draft; releasing or an explicit Apply commits once (not one disk write
   per move). Accessible +/- controls work without dragging. Presets and Reset
   remain understandable. Save failure retains confirmed state and tells the user;
   transient preview must not be confused with the confirmed keyboard.
4. Return to an actual normal editor applies the confirmed height with unchanged
   text/caret and existing input ownership invalidated on layout replacement.
   Changing height cannot type, send, record, activate a model/provider, inspect
   clipboard or eager-read private host text. A canceled/stale settings control
   cannot mutate a new screen or discard unrelated confirmed preferences.
5. Affected migration/bounds/geometry/store controls, actual normal settings →
   altered keyboard geometry → Reset round trip and independent review are needed
   for scoped feature qualification. Portrait/landscape/narrow/one-hand, large-font,
   accessibility/hardware/contact transitions and physical evidence remain distinct;
   no unit/mock/emulator pass silently closes held/unrun full release gates.

## Design

Reuse the user's pure black backdrop, graphite rounded native key surfaces,
true-white labels and restrained existing45-degree glass reflection. The single
height preview and exact percentage are the focal elements, not a new dashboard.
Use native system type,48dp settings controls, visible focus, readable wrapping and
scrolling. Keep the default keyboard silhouette and other size settings unchanged.
No art generation, network dependency, new icon family or unrelated UI redesign.

No full Gboard parity, superior swipe, CPU-free inference, direct agent sending,
physical performance, signed installer or independent9.2 score is established by
this source increment. All original requirements and task holds remain intact.

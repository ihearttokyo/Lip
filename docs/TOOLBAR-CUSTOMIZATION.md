# Configurable keyboard toolbar — acceptance contract v1

Frozen before implementation on29October2026. Additive to the unchanged full
rubrics and screenshot contract; no requirement is waived by a scoped source pass.

## Intended task and baseline

The current toolbar has seven familiar positions but its five middle actions are
hardcoded. User requirements call for shortcuts that can be added to the top bar,
agent settings and Gboard feature parity. Preserve the seven-position DEFAULT
geometry/order and always-reachable Tools and microphone endpoints. A new
**Keyboard preferences → Customize toolbar** path chooses and orders the five
middle positions. Supported local destinations include emoji, GIF, clipboard,
owned Undo/Redo, text editing and local sticker packs. Configuration is not agent
transport admission. Any desired agent action remains honestly disabled when no
supported route/account/capability is admitted; no draft/share/clipboard/URL or
UI-automation substitute. Translation remains future work.

## Fixed functional and safety criteria

1. A bounded, versioned, local five-slot schema rejects unknown/duplicate/invalid
   mappings. Missing data uses the exact established default. Unsupported or
   corrupt saved data must not overwrite the last confirmed settings or silently
   dispatch another action. Save failure preserves the confirmed toolbar.
2. The customization UI offers visible current order, explicit action labels,
   accessible Move up/down (drag is optional), Save and Reset default. No hidden
   gesture is the only route. An actual preview uses the same mapping/geometry as
   the consumer, but preview activation cannot type, record, send or attach.
3. Save → return → actual keyboard presents that order; each supported shortcut
   opens its real existing consumer. Endpoints remain reachable under one-hand,
   small/narrow and landscape constraints. Restore default reproduces the prior
   screenshot layout rather than replacing it with a new design.
4. Old/stale views cannot invoke a new mapping or wrong field. Reconfiguration,
   window/editor/privacy/composition transitions and multitouch retain existing
   ownership barriers. No new eager field, clipboard, media or model reads merely
   from customization/preview. Existing opaque-editor/owned-Undo barriers remain.
5. Agent desired settings, if exposed here, use existing provider/effort values,
   explicit persisted consent and clear unavailable status. They do not log in,
   acquire credentials, call a provider, share a draft or claim automatic sending.
   Long-press configuration does not accidentally dispatch the last dictation.
6. Affected pure/schema/settings controls, actual normal-build editor navigation,
   save/reset/invalid/cancel behavior and independent review are required for
   feature qualification. Compilation/static/unit/emulator/physical evidence stay
   distinct. Existing held physical/first-show/accessibility/9.2 gates stay open.

## Design implementation brief

Match the user's existing black/white rounded-rectangle liquid-glass keyboard,
not a generic card dashboard: black#000000 backing; graphite#292929 key surface;
subtle highlight#555555 from the established offscreen45-degree material;
true white#FFFFFF labels; existing mint#80CBC4 only for an enabled primary action.
Use the existing native type and DarkGlass/GlassKeyButton system, readable wrapping
labels, at least48dp controls, visible focus and scrolling without clipped actions.
One live seven-position toolbar preview is the focal object. Configuration rows
remain quiet, left-aligned, with plain concise labels and explicit persistence.
No art generation, borrowed branding, new decoration stack or proprietary assets.
Review this brief against the requested screenshot-default before implementing;
reuse existing native material rather than inventing an unrelated web aesthetic.

## Remaining overall gates

No full parity, physical performance, swipe superiority, direct agent transport,
translation or product score follows from this feature. Default identity and
source PR delivery remain separate from a signed/qualified full release.

# Floating keyboard — acceptance contract v1

Frozen before implementation on29October2026. Baseline d73572f (v0.016 source,
not a release). Additive to the original full rubric and Gboard gap inventory;
no missing physical/accessibility/privacy evidence becomes a simulated pass.

## Intended user task

Tools → Floating keyboard presents the actual English keyboard in a movable,
resizable black/glass card over the existing editor. A visible move header,
resize handle and Dock control are reachable. It must move in both axes,
including above the original docked strip; merely translating a bottom strip,
scaling a bitmap or adding a mode label is not the requested feature. Settings
also provide accessible move/size/reset alternatives without requiring dragging.
Dock restores the ordinary keyboard and the separately saved docked height/hand.

## Public API basis, not proof of behavior

Android documents customizable IME window layout and a window-local touch region
through [InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)
and [Insets](https://developer.android.com/reference/android/inputmethodservice/InputMethodService.Insets).
The proposed implementation uses a transparent full-viewport IME window, real
measured card and explicit owned touch region. This is an implementation inference,
not an official guarantee of correct pass-through in every editor. The cached
API36 navigation controller may add an OS-managed navigation region after Lip's
callback. Preserve system navigation; never hide it or use a hidden-controller
workaround. No overlay permission, Accessibility change or privileged API is needed
or authorized. Actual platform behavior must be measured before promotion.

## Fixed functional and safety criteria

1. Use actual window/root dimensions and system/cutout/gesture insets, not assumed
   display coordinates. Store only bounded versioned integer normalized geometry
   and mode in a separate local namespace. Preserve docked height/hand/theme and
   unrelated settings. Unknown/corrupt data fails to a safe docked presentation;
   failed writes retain confirmed authority. Rotation/split/narrow fitting cannot
   leave controls offscreen or invent unchecked coordinates. If the minimum card
   genuinely cannot fit, dock with a clear explanation while retaining preferred
   geometry; do not present an invisible full-screen blocker.
2. The full-viewport background is transparent, not a black/dim overlay. Lip's
   touch region is exactly its visible card and handles in WINDOW coordinates.
   Unready/hidden/invalid floating content contributes an empty owned region.
   OS navigation remains OS-managed. Outside the card, host taps must reach the
   original editor through normal platform dispatch; no injection/forwarding,
   clipboard or fake event receipt substitutes. Unexplained expanded interception
   beyond the card/legitimate system navigation is a blocking failure.
3. Move and resize affect real layout, key positions and hit geometry, not just
   pixels. Real five-row keys/toolbar and media/editing pages remain reachable
   within fitted bounds; apply shared minimum/cap geometry and truthful fallback
   only for genuine no-fit windows. Refresh window layout on mode transitions,
   not just on service creation. Dock restores wrap-content layout, background,
   normal insets and existing settings without typing or sending.
4. A handle reserves its original frame/editor/layout/contact metadata BEFORE
   external callbacks. Entering, moving, resizing or docking revokes stale key,
   swipe/candidate, attachment and edit ownership before geometry changes. No
   handle survives editor/privacy/composition/focus/window/geometry departure,
   cancellation or multicontact ABA. Preview geometry stays in RAM; only explicit
   UP/Apply commits once. Cancel/failure restores confirmed geometry. Mode/layout
   changes must not retarget text or a pending external operation; refuse unsafe
   transitions while busy, leaving visible existing Stop/Cancel/navigation recovery.
5. Manual keys remain usable under existing protected-field policy. Geometry is
   UI metadata: do not eager-read host text, clipboard, media, audio, credentials,
   databases or providers to move a keyboard. No automatic account/model/network
   action. Accessible move/size/Dock alternatives have readable labels, focus and
   minimum48dp settings/handle controls; no gesture-only essential feature.
6. Actual owned API36 evidence must show underlying editor visibility, taps at
   multiple outside-card points INSIDE the full IME window reaching the host with
   zero keyboard effects, an inside key producing the exact intended character,
   card movement above the former docked strip by at least one row, actual resize
   geometry and Dock restore. Pure geometry/schema/cancel/store/window-local
   controls, actual failures and independent review remain separate. Capped,
   rotated, narrow, private/composition/busy/multitouch, accessibility, physical
   and arbitrary-editor coverage stay explicitly open where unrun.

## Design and delivery

Reuse pure black card backing, graphite rounded real keycaps, true-white labels
and existing restrained45-degree glass highlights. Only the card/header carries
material; do not blur or snapshot the host. One quiet move affordance and explicit
Dock, not an unrelated dashboard or opaque full-screen scrim. Preserve default
docked screenshot geometry. No proprietary art or Gboard code extraction.

This feature remains unqualified until the real pass-through/move/resize/Dock
primary path works. Source/CI/pure tests alone cannot promote a dangerous overlay.
No full product9.2, physical superiority, direct agent sending or new signed release
is established. Existing phone/installed-Mac/private-data/account/retrieval holds
remain unchanged; parent alone owns builds, owned-emulator tests and Git delivery.

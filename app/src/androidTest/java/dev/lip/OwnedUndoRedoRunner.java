package dev.lip;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Explicit owned-emulator, synthetic EditText consumer controls. All edit/replay
 * actions use real touchscreen keys/toolbar/popup, never journal APIs/reflection.
 * No microphone, model, provider, clipboard, history or preference mutation.
 * This runner never enables/switches an IME or launches an emulator.
 */
public final class OwnedUndoRedoRunner extends Instrumentation {
    private static final String APP = "dev.lip.android", TEST = "dev.lip.android.test";
    private static final String TOOL = "Show adjacent Lip-owned Undo and Redo; cancel pending dictation";
    private static final String UNDO = "Undo last Lip-owned text edit", REDO = "Redo last Lip-owned text edit";
    private static final String DELETE = "Delete previous character; hold to repeat";
    private UiAutomation automation;
    private ImeFixtureActivity fixture;
    private String stage = "guard";
    private String expectDiagnostic = "none";
    private String popupGeometryDiagnostic = "none";
    private String tapGeometryDiagnostic = "none";
    private int touches, controls, toolbarNavigations;
    private boolean authorizedFixture;
    private boolean verifiedFixture;
    private String lastControl = "none";
    private String lastTouchBounds = "none";
    private long lastDownAt, lastUpAt;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        authorizedFixture = args != null && "true".equals(args.getString("isolated_emulator"));
        if (!authorizedFixture) {
            Bundle report = new Bundle(); report.putString("failure", "Explicit isolated_emulator=true is required");
            finish(Activity.RESULT_CANCELED, report); return;
        }
        start();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private <T> T ui(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { value.set(action.get()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError("Owned fixture UI operation failed", error.get());
        return value.get();
    }
    private void collect(AccessibilityNodeInfo node, String pkg, List<AccessibilityNodeInfo> out, int[] visited, int depth) {
        if (node == null || depth > 32 || visited[0]++ >= 4096 || out.size() >= 2048) return;
        // A PopupWindow may have a framework/package-less wrapper. Traverse only
        // bounded structure; never read text, and retain only the exact owned pkg.
        if (pkg.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), pkg, out, visited, depth + 1);
    }
    private List<AccessibilityNodeInfo> ownedNodes(String pkg) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>(); int[] visited = new int[1];
        automation.getWindows().forEach(window -> collect(window.getRoot(), pkg, nodes, visited, 0));
        // Focusable IME popups can become the active root before getWindows catches
        // up. Do not replace the windows path or synthesize a click as a fallback.
        collect(automation.getRootInActiveWindow(), pkg, nodes, new int[1], 0);
        return nodes;
    }
    private List<AccessibilityNodeInfo> matches(String pkg, String description) {
        List<AccessibilityNodeInfo> found = new ArrayList<>(); Set<AccessibilityNodeInfo> seen = new HashSet<>();
        for (AccessibilityNodeInfo node : ownedNodes(pkg)) if (seen.add(node) && node.isVisibleToUser() && description.contentEquals(
                node.getContentDescription() == null ? "" : node.getContentDescription())) found.add(node);
        return found;
    }
    private AccessibilityNodeInfo find(String pkg, String description) {
        long end = SystemClock.uptimeMillis() + 5000;
        do {
            List<AccessibilityNodeInfo> found = matches(pkg, description);
            if (found.size() == 1) return found.get(0);
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Expected one owned fixture/control: " + description);
    }
    private AccessibilityNodeInfo find(String description) { return find(APP, description); }
    private void absent(String description) {
        long end = SystemClock.uptimeMillis() + 3000;
        while (!matches(APP, description).isEmpty() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40);
        require(matches(APP, description).isEmpty(), "Departed editor retained an Undo/Redo surface");
    }
    private void settled() {
        try { automation.waitForIdle(100, 2500); }
        catch (java.util.concurrent.TimeoutException error) { throw new AssertionError("Owned touch geometry did not settle", error); }
    }
    private Rect bounds(AccessibilityNodeInfo node) {
        Rect rect = new Rect(); node.getBoundsInScreen(rect);
        require(!rect.isEmpty() && node.isVisibleToUser(), "Owned touch target is not visible"); return rect;
    }
    private void touch(Rect rect) {
        long downAt = SystemClock.uptimeMillis(); lastDownAt = downAt; lastTouchBounds = rect.toShortString();
        MotionEvent down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(automation.injectInputEvent(down, true), "Owned touch DOWN refused"); } finally { down.recycle(); }
        SystemClock.sleep(12); // Real monotonic timestamps, not future UP times across rapid taps.
        lastUpAt = SystemClock.uptimeMillis();
        MotionEvent up = MotionEvent.obtain(downAt, lastUpAt, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(automation.injectInputEvent(up, true), "Owned touch UP refused"); } finally { up.recycle(); }
        touches++;
    }
    private Rect stableTapBounds(String description, boolean enabled) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        AccessibilityNodeInfo previousNode = null; Rect previousBounds = null;
        do {
            AccessibilityNodeInfo current = null; int count = 0;
            Set<AccessibilityNodeInfo> seen = new HashSet<>();
            for (AccessibilityNodeInfo node : ownedNodes(APP)) {
                if (!seen.add(node) || !description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) continue;
                // Force fresh server-side geometry rather than trusting a cached AX
                // node from a sliding/resizing keyboard's previous window frame.
                if (!node.refresh()) continue;
                if (!APP.contentEquals(node.getPackageName() == null ? "" : node.getPackageName()) ||
                        !description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()) || !node.isVisibleToUser()) continue;
                require(node.isEnabled() == enabled, "Unexpected enabled state on owned control: " + description);
                current = node; count++;
            }
            if (count == 1) {
                Rect area = new Rect(); current.getBoundsInScreen(area);
                tapGeometryDiagnostic = "control=" + description + " window=" + current.getWindowId() + " bounds=" + area.toShortString() + " stable=false";
                if (!area.isEmpty() && current.equals(previousNode) && area.equals(previousBounds)) {
                    tapGeometryDiagnostic = "control=" + description + " window=" + current.getWindowId() + " bounds=" + area.toShortString() + " stable=true";
                    return area;
                }
                previousNode = current; previousBounds = area;
            } else {
                tapGeometryDiagnostic = "control=" + description + " freshUniqueCount=" + count + " stable=false";
                previousNode = null; previousBounds = null;
            }
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Owned touch target did not refresh and stabilize across two consecutive snapshots");
    }
    private void tap(String description) { tap(description, true); }
    private void tap(String description, boolean enabled) {
        settled(); Rect area = stableTapBounds(description, enabled);
        // Exactly one real DOWN/UP after the two fresh, unique, semantically exact
        // observations. Never retap, invoke a listener, or replay a dropped action.
        lastControl = description; touch(area); SystemClock.sleep(60);
    }
    private void back() {
        long time = SystemClock.uptimeMillis();
        require(automation.injectInputEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true), "Popup Back DOWN refused");
        require(automation.injectInputEvent(new KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true), "Popup Back UP refused");
        absent(UNDO); absent(REDO);
    }
    private static final class State {
        final String text; final int start, end;
        State(EditText field) { text = field.getText().toString(); start = field.getSelectionStart(); end = field.getSelectionEnd(); }
        boolean same(State other) { return text.equals(other.text) && start == other.start && end == other.end; }
    }
    private State state(EditText field) { return ui(() -> new State(field)); }
    private void expect(EditText field, String text, int start, int end) {
        long until = SystemClock.uptimeMillis() + 5000;
        State value;
        do {
            value = state(field);
            if (text.equals(value.text) && value.start == start && value.end == end) return;
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < until);
        expectDiagnostic = "expectedSelection=" + start + ":" + end + " actualSelection=" + value.start + ":" + value.end
            + " expectedTextLength=" + text.length() + " actualTextLength=" + value.text.length()
            + " textEquals=" + text.equals(value.text);
        throw new AssertionError("Owned editor text/selection confirmation failed"); // No text in reports.
    }
    private void expect(EditText field, String text) { expect(field, text, text.length(), text.length()); }
    private void activate(EditText field, int start, int end) {
        Rect rect = ui(() -> {
            field.requestFocus(); field.requestRectangleOnScreen(new Rect(0, 0, field.getWidth(), field.getHeight()), true);
            Rect area = new Rect(); require(field.getGlobalVisibleRect(area), "Owned fixture field is not visible"); return area;
        });
        touch(rect); SystemClock.sleep(120);
        ui(() -> { field.setSelection(start, end); fixture.getSystemService(InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT); return null; });
        SystemClock.sleep(200); settled(); find("q");
        require(ui(() -> fixture.getCurrentFocus() == field), "Owned fixture focus changed unexpectedly");
    }
    private void setup(EditText field, String text, int start, int end) {
        ui(() -> { field.setText(text); return null; }); activate(field, start, end); expect(field, text, start, end);
    }
    private void setup(EditText field, String text) { setup(field, text, text.length(), text.length()); }
    private void rapid(String letters) {
        settled(); List<Rect> locations = new ArrayList<>();
        for (char c : letters.toCharArray()) locations.add(bounds(find(String.valueOf(c))));
        // No idle wait, synthetic delay, callback drain or edit API between letters.
        // Ordered synchronous input dispatch only; final field state proves no loss.
        for (Rect rect : locations) touch(rect);
    }
    private static final class PopupSnapshot {
        final AccessibilityNodeInfo undo, redo; final Rect undoBounds, redoBounds;
        PopupSnapshot(AccessibilityNodeInfo undo, AccessibilityNodeInfo redo, Rect undoBounds, Rect redoBounds) {
            this.undo = undo; this.redo = redo; this.undoBounds = undoBounds; this.redoBounds = redoBounds;
        }
    }
    private PopupSnapshot stablePopup() {
        long deadline = SystemClock.uptimeMillis() + 5000;
        Rect previousUndo = null, previousRedo = null;
        do {
            AccessibilityNodeInfo undo = null, redo = null; int undos = 0, redos = 0;
            Set<AccessibilityNodeInfo> seen = new HashSet<>();
            // Obtain both controls in one bounded structural pass, not two separate
            // find() waits that can sample different frames of PopupWindow's fade.
            for (AccessibilityNodeInfo node : ownedNodes(APP)) if (seen.add(node) && node.isVisibleToUser()) {
                CharSequence label = node.getContentDescription();
                if (UNDO.contentEquals(label == null ? "" : label)) { undo = node; undos++; }
                if (REDO.contentEquals(label == null ? "" : label)) { redo = node; redos++; }
            }
            if (undos == 1 && redos == 1) {
                Rect a = new Rect(), b = new Rect(); undo.getBoundsInScreen(a); redo.getBoundsInScreen(b);
                popupGeometryDiagnostic = "undo=" + a.toShortString() + " redo=" + b.toShortString();
                if (!a.isEmpty() && !b.isEmpty() && a.equals(previousUndo) && b.equals(previousRedo))
                    return new PopupSnapshot(undo, redo, a, b);
                previousUndo = a; previousRedo = b;
            } else {
                popupGeometryDiagnostic = "visibleUndoControls=" + undos + " visibleRedoControls=" + redos;
                previousUndo = null; previousRedo = null;
            }
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Undo/Redo popup geometry did not stabilize across two consecutive snapshots");
    }
    private void popup(EditText field, boolean undo, boolean redo) {
        State before = state(field);
        settled();
        if (matches(APP, TOOL).isEmpty()) {
            // Typed candidates intentionally replace the toolbar. Use the one
            // visible production navigation control once; never retap a missing
            // toolbar action, invoke its listener, or substitute a journal API.
            tap("Show keyboard tools"); toolbarNavigations++;
        }
        tap(TOOL);
        settled(); // Observe the actual popup transition; never disable animations or retap.
        PopupSnapshot popup = stablePopup();
        require(popup.undo.isEnabled() == undo && popup.redo.isEnabled() == redo, "Unexpected owned Undo/Redo availability");
        Rect a = popup.undoBounds, b = popup.redoBounds;
        require(a.top == b.top && a.bottom == b.bottom && (a.right <= b.left || b.right <= a.left), "Undo/Redo are not adjacent nonoverlapping buttons");
        require(before.same(state(field)), "Opening Undo/Redo edited text or selection");
    }
    private void passed(Bundle report, String name) { report.putBoolean(name, true); controls++; }

    private void failureEvidence(Bundle report) {
        if (!verifiedFixture || automation == null || fixture == null) return;
        try {
            File directory = getContext().getFilesDir();
            Set<String> publicControls = new HashSet<>(java.util.Arrays.asList(TOOL, UNDO, REDO, DELETE,
                "q", "z", "x", "v", "Show keyboard tools", "Show emoji", "Insert emoji grinning face", "Return from emoji to letters"));
            StringBuilder trace = new StringBuilder("Owned emulator synthetic fixture only; editor/candidate text omitted\n");
            trace.append("stage=").append(stage).append(" last-control=").append(publicControls.contains(lastControl) ? lastControl : "[omitted]").append('\n');
            trace.append("last-touch-bounds=").append(lastTouchBounds).append(" down-uptime=").append(lastDownAt)
                .append(" up-uptime=").append(lastUpAt).append(" capture-uptime=").append(SystemClock.uptimeMillis()).append('\n');
            int windows = 0;
            for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                if (++windows > 64) break;
                AccessibilityNodeInfo root = window.getRoot(); Rect area = new Rect(); window.getBoundsInScreen(area);
                trace.append("window id=").append(window.getId()).append(" type=").append(window.getType())
                    .append(" active=").append(window.isActive()).append(" focused=").append(window.isFocused())
                    .append(" bounds=").append(area.toShortString()).append(" root-package=")
                    .append(root == null ? "null" : String.valueOf(root.getPackageName())).append('\n');
            }
            AccessibilityNodeInfo active = automation.getRootInActiveWindow();
            trace.append("active-root-package=").append(active == null ? "null" : String.valueOf(active.getPackageName())).append('\n');
            Set<AccessibilityNodeInfo> seen = new HashSet<>(); int count = 0;
            for (AccessibilityNodeInfo node : ownedNodes(APP)) {
                if (!seen.add(node) || ++count > 512 || trace.length() > 60000) continue;
                Rect area = new Rect(); node.getBoundsInScreen(area);
                String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
                trace.append("Lip control class=").append(node.getClassName()).append(" visible=").append(node.isVisibleToUser())
                    .append(" enabled=").append(node.isEnabled()).append(" clickable=").append(node.isClickable())
                    .append(" window=").append(node.getWindowId()).append(" bounds=").append(area.toShortString())
                    .append(" description=").append(publicControls.contains(description) ? description : "[omitted]").append('\n');
            }
            File ax = File.createTempFile("owned-undo-failure-", ".ax.txt", directory);
            try (FileOutputStream output = new FileOutputStream(ax)) { output.write(trace.toString().getBytes(StandardCharsets.UTF_8)); }
            report.putString("failure_ax_file", ax.getName());
            Bitmap screenshot = automation.takeScreenshot();
            if (screenshot != null) {
                // Guest display only, after exact synthetic-fixture verification; not a host/macOS capture.
                File png = File.createTempFile("owned-undo-failure-", ".png", directory);
                try (FileOutputStream output = new FileOutputStream(png)) {
                    require(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output), "Owned failure screenshot compression refused");
                } finally { screenshot.recycle(); }
                report.putString("failure_screenshot_file", png.getName());
            }
        } catch (Throwable failure) { report.putString("failure_evidence", "Owned diagnostic capture unavailable"); }
    }

    @Override public void onStart() {
        Bundle report = new Bundle(); int result = Activity.RESULT_CANCELED;
        try {
            require(authorizedFixture && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)) && Build.PRODUCT.contains("sdk") && Build.VERSION.SDK_INT >= 36,
                "Android16+ owned emulator is required; no phone route");
            require(TEST.equals(getContext().getPackageName()) && TEST.equals(getTargetContext().getPackageName()), "Exact owned test package is required");
            require((getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0, "Debug test APK is required");
            require((getContext().getPackageManager().getApplicationInfo(APP, 0).flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0, "Debug Lip app is required");
            ComponentName ime = ComponentName.unflattenFromString(Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
            require(ime != null && APP.equals(ime.getPackageName()) && "dev.lip.keyboard.LipInputMethodService".equals(ime.getClassName()), "Parent must explicitly preselect Lip IME on this owned emulator");
            stage = "fixture";
            String generation = UUID.randomUUID().toString();
            fixture = (ImeFixtureActivity) startActivitySync(new Intent().setClassName(TEST, ImeFixtureActivity.class.getName())
                .putExtra("fixtureGeneration", generation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo(); info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            find(TEST, "Lip synthetic generation " + generation);
            require(generation.equals(ui(() -> fixture.getIntent().getStringExtra("fixtureGeneration"))), "Owned fixture incarnation mismatch");
            verifiedFixture = true;

            stage = "open-no-edit";
            setup(fixture.normal, ""); popup(fixture.normal, false, false); back(); expect(fixture.normal, "");
            passed(report, "POPUP_ADJACENT_OPEN_DISMISS_NO_EDIT");

            stage = "rapid-grouped-typing";
            rapid("qzx"); expect(fixture.normal, "qzx");
            popup(fixture.normal, true, false); tap(UNDO); expect(fixture.normal, "");
            popup(fixture.normal, false, true); tap(REDO); expect(fixture.normal, "qzx");
            passed(report, "REAL_RAPID_TYPING_GROUP_UNDO_REDO");

            stage = "redo-branch";
            popup(fixture.normal, true, false); tap(UNDO); expect(fixture.normal, "");
            tap("v"); expect(fixture.normal, "v"); popup(fixture.normal, true, false);
            tap(REDO, false); expect(fixture.normal, "v"); back();
            popup(fixture.normal, true, false); tap(UNDO); expect(fixture.normal, "");
            popup(fixture.normal, false, true); tap(REDO); expect(fixture.normal, "v");
            passed(report, "NEW_EDIT_INVALIDATES_OLD_REDO_BRANCH");

            stage = "reversed-selected-emoji-span";
            setup(fixture.normal, "LEFT 😀 RIGHT", 7, 5);
            stage = "reversed-selected-emoji-span/delete";
            tap(DELETE); expect(fixture.normal, "LEFT  RIGHT", 5, 5);
            stage = "reversed-selected-emoji-span/undo";
            popup(fixture.normal, true, false); tap(UNDO); expect(fixture.normal, "LEFT 😀 RIGHT", 7, 5);
            stage = "reversed-selected-emoji-span/redo";
            popup(fixture.normal, false, true); tap(REDO); expect(fixture.normal, "LEFT  RIGHT", 5, 5);
            passed(report, "REVERSED_SELECTED_EMOJI_SPAN_TEXT_AND_SELECTION_REPLAY");

            stage = "real-emoji-insertion";
            setup(fixture.normal, ""); tap("Show emoji"); tap("Insert emoji grinning face"); expect(fixture.normal, "😀");
            tap("Return from emoji to letters"); popup(fixture.normal, true, false); tap(UNDO); expect(fixture.normal, "");
            popup(fixture.normal, false, true); tap(REDO); expect(fixture.normal, "😀");
            passed(report, "REAL_EMOJI_PICKER_INSERTION_UNDO_REDO");

            stage = "editor-lifecycle";
            setup(fixture.normal, ""); rapid("qzx"); expect(fixture.normal, "qzx"); popup(fixture.normal, true, false);
            setup(fixture.multiline, "OTHER"); absent(UNDO); absent(REDO); expect(fixture.normal, "qzx");
            popup(fixture.multiline, false, false); tap(UNDO, false); expect(fixture.multiline, "OTHER"); back();
            // No setText on returning: departure alone must revoke the old editor's replay.
            activate(fixture.normal, 3, 3); expect(fixture.normal, "qzx"); popup(fixture.normal, false, false); back();
            passed(report, "FIELD_DEPARTURE_DISMISSES_POPUP_AND_REVOKES_REPLAY");

            stage = "password-refusal";
            setup(fixture.password, ""); tap("q"); expect(fixture.password, "q");
            int privateReads = ui(() -> fixture.passwordCursorContextReads);
            popup(fixture.password, false, false); tap(UNDO, false); tap(REDO, false); expect(fixture.password, "q");
            require(ui(() -> fixture.passwordCursorContextReads) == privateReads, "Private Undo/Redo performed an additional password-context read");
            back(); expect(fixture.normal, "qzx");
            passed(report, "PASSWORD_EDIT_NOT_JOURNALED_NO_POPUP_CONTEXT_READ");

            stage = "incognito-refusal";
            setup(fixture.incognito, ""); tap("q"); expect(fixture.incognito, "q");
            popup(fixture.incognito, false, false); tap(UNDO, false); tap(REDO, false); expect(fixture.incognito, "q"); back();
            activate(fixture.normal, 3, 3); popup(fixture.normal, false, false); back(); expect(fixture.normal, "qzx");
            passed(report, "INCOGNITO_EDIT_NOT_JOURNALED_OR_REPLAYED_AFTER_RETURN");

            require(ui(() -> fixture.actions) == 0 && ui(() -> fixture.lastCustomAction) == 0, "Undo/Redo probe triggered a host Send/action");
            report.putInt("named_controls", controls); report.putInt("real_touch_taps", touches);
            report.putInt("explicit_toolbar_navigations", toolbarNavigations);
            report.putString("result", "PASS named real touchscreen/toolbar/InputConnection controls on owned emulator only; no physical/editor-matrix/full9.2 acceptance");
            result = Activity.RESULT_OK;
        } catch (Throwable failure) {
            report.putString("failure_stage", stage); report.putString("failure_kind", failure.getClass().getSimpleName());
            report.putString("expect_diagnostic", expectDiagnostic); // Positions, UTF16 lengths and equality only.
            report.putString("popup_geometry_diagnostic", popupGeometryDiagnostic); // Final observed control rectangles/counts only.
            report.putString("tap_geometry_diagnostic", tapGeometryDiagnostic); // Fresh target identity/bounds, no editor text.
            // Runner assertions contain only fixed control/check labels, never editor contents.
            if (failure.getClass() == AssertionError.class) report.putString("failure_check", failure.getMessage());
            report.putString("result", "FAIL closed; no automatic action replay or substitute API path attempted");
            failureEvidence(report); // Before finishing the synthetic activity; never on an unverified device.
        } finally {
            if (fixture != null) try { ui(() -> { fixture.finish(); return null; }); }
            catch (Throwable failure) { result = Activity.RESULT_CANCELED; report.putString("cleanup", "Owned fixture finish failed"); }
        }
        finish(result, report);
    }
}

package dev.lip;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
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

/** Real touchscreen-only text editing consumer test on one explicitly owned VM.
 * Direct fixture calls establish host premises only; no production API/reflection,
 * semantic clicks, provider/audio/history calls, IME switching or emulator launch.
 */
public final class TextEditingRunner extends Instrumentation {
    private static final String APP="dev.lip.android", TEST="dev.lip.android.test";
    private static final String MENU="Lip keyboard menu", OPEN="Open text editing", VIEWPORT="Text editing controls", RETURN="Return from text editing to letters";
    private static final String ALL="Select all text in current editor", CUT="Cut selected text using current editor", COPY="Copy selected text using current editor", PASTE="Paste system clipboard using current editor";
    private static final String LEFT="Move cursor left", RIGHT="Move cursor right", UP="Move cursor up", DOWN="Move cursor down", SELECT="Select text with arrow keys";
    private static final String LINE_START="Move to line start", LINE_END="Move to line end", TEXT_START="Move to text start", TEXT_END="Move to text end";
    private static final Set<String> COMMANDS=new HashSet<>(java.util.Arrays.asList(ALL,CUT,COPY,PASTE,LEFT,RIGHT,UP,DOWN,SELECT,LINE_START,LINE_END,TEXT_START,TEXT_END));
    private UiAutomation automation;
    private ImeFixtureActivity fixture;
    private ClipboardManager clipboard;
    private ClipData savedClipboard;
    private boolean clipboardCaptured,authorizedFixture,verifiedFixture,privatePopupBaseline;
    private int touches,controls,toolbarNavigations,scrolls,multiSequences;
    private String stage="guard",expectDiagnostic="none",tapGeometryDiagnostic="none",shiftDiagnostic="none",privateSetupDiagnostic="none",lastControl="none",lastTouchBounds="none";
    private long lastDownAt,lastUpAt;
    @Override public void onCreate(Bundle arguments){
        super.onCreate(arguments);authorizedFixture=arguments!=null&&"true".equals(arguments.getString("isolated_emulator"));
        if(!authorizedFixture){Bundle report=new Bundle();report.putString("failure","Explicit isolated_emulator=true required");finish(Activity.RESULT_CANCELED,report);return;}
        String mode=arguments.getString("mode");privatePopupBaseline="privatePopupBaseline".equals(mode);
        if(mode!=null&&!privatePopupBaseline){Bundle report=new Bundle();report.putString("failure","Unknown explicit diagnostic mode; no full-suite fallback");finish(Activity.RESULT_CANCELED,report);return;}
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
        require(matches(APP, description).isEmpty(), "Departed context retained an editing surface");
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
        if (COMMANDS.contains(description)) reveal(description);
        settled(); Rect area = stableTapBounds(description, enabled);
        // Exactly one real DOWN/UP after the two fresh, unique, semantically exact
        // observations. Never retap, invoke a listener, or replay a dropped action.
        lastControl = description; touch(area); SystemClock.sleep(60);
    }
    private boolean menuPresent(){
        int count=0;Set<AccessibilityNodeInfo> seen=new HashSet<>();
        for(AccessibilityNodeInfo node:ownedNodes(APP))if(seen.add(node)&&OPEN.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())&&node.refresh()&&APP.contentEquals(node.getPackageName()==null?"":node.getPackageName())&&OPEN.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())&&node.isVisibleToUser()){
            Rect area=new Rect();node.getBoundsInScreen(area);if(!area.isEmpty())count++;
        }
        require(count<=1,"Ambiguous owned Tools popup before Back");return count==1;
    }
    private void dismissMenu() {
        require(menuPresent(),"Owned Tools popup already departed; Back was not sent to editor");
        long time = SystemClock.uptimeMillis();
        require(automation.injectInputEvent(new KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true), "Owned menu Back DOWN refused");
        require(automation.injectInputEvent(new KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true), "Owned menu Back UP refused");
        absent(OPEN);settled();
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
    private void setupPrivate(EditText field,String text,int start,int end){
        // Initial premise only. Do not publish a requested selection before the
        // newly focused private editor has actually created/served its connection.
        Rect area=ui(()->{
            field.setText(text);field.requestFocus();field.requestRectangleOnScreen(new Rect(0,0,field.getWidth(),field.getHeight()),true);
            Rect rect=new Rect();require(field.getGlobalVisibleRect(rect),"Private fixture field is not visible");return rect;
        });
        touch(area);
        ui(()->{fixture.getSystemService(InputMethodManager.class).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);return null;});
        long deadline=SystemClock.uptimeMillis()+5000,previous=0,ready=0;
        do{
            long token=ui(()->fixture.editingPrivateConnectionToken(field));
            boolean served=ui(()->fixture.getCurrentFocus()==field&&field.hasFocus()&&fixture.hasWindowFocus()&&fixture.getSystemService(InputMethodManager.class).isActive(field));
            privateSetupDiagnostic="token="+token+" servedAndFocused="+served+" stable="+(token>0&&served&&token==previous);
            if(token>0&&served&&token==previous){ready=token;break;}
            previous=served?token:0;SystemClock.sleep(60);
        }while(SystemClock.uptimeMillis()<deadline);
        require(ready>0,"Private fixture connection did not become stably open/served");
        stableTapBounds("q",true); // Fresh current keyboard geometry; no key action.
        final long admittedToken=ready;
        ui(()->{
            require(fixture.getCurrentFocus()==field&&fixture.editingPrivateConnectionToken(field)==admittedToken&&fixture.getSystemService(InputMethodManager.class).isActive(field),"Private fixture connection changed before final selection premise");
            field.setSelection(start,end);return null; // Exactly once, AFTER readiness.
        });
        privateSetupDiagnostic="admittedToken="+ready+" requestedSelection="+start+":"+end;
        expect(field,text,start,end); // Never repair/reset it after admission.
    }
    private void reveal(String description){
        for(int attempt=0;attempt<6;attempt++){
            AccessibilityNodeInfo target=null;int count=0;Set<AccessibilityNodeInfo> seen=new HashSet<>();
            for(AccessibilityNodeInfo node:ownedNodes(APP))if(seen.add(node)&&description.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())&&node.refresh()){
                require(APP.contentEquals(node.getPackageName()),"Foreign editing control refused");target=node;count++;
            }
            require(count==1,"Expected one actual panel command before touch/scroll");
            Rect area=new Rect();target.getBoundsInScreen(area);Rect viewport=stableTapBounds(VIEWPORT,true);
            if(target.isVisibleToUser()&&!area.isEmpty()&&viewport.contains(area))return;
            require(!area.isEmpty(),"Cannot establish offscreen panel command direction");
            boolean forward=area.bottom>viewport.bottom;
            if(!forward)require(area.top<viewport.top,"Hidden panel control has no safe scroll route");
            State before=state((EditText)ui(()->fixture.getCurrentFocus()));
            scroll(viewport,forward);
            require(before.same(state((EditText)ui(()->fixture.getCurrentFocus()))),"Panel scrolling edited the host");
        }
        throw new AssertionError("Panel command did not become fully visible within bounded real scrolls");
    }
    private void scroll(Rect area,boolean forward){
        float inset=Math.min(area.height()/5f,36*fixture.getResources().getDisplayMetrics().density);
        float x=area.exactCenterX(),start=forward?area.bottom-inset:area.top+inset,end=forward?area.top+inset:area.bottom-inset;
        require(Math.abs(start-end)>20,"Panel scroll viewport too small");long downAt=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(downAt,downAt,MotionEvent.ACTION_DOWN,x,start,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{require(automation.injectInputEvent(event,true),"Panel scroll DOWN refused");}finally{event.recycle();}
        try{for(int i=1;i<=10;i++){SystemClock.sleep(20);event=MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x,start+(end-start)*i/10f,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{require(automation.injectInputEvent(event,true),"Panel scroll MOVE refused");}finally{event.recycle();}}}
        finally{event=MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,end,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{require(automation.injectInputEvent(event,true),"Panel scroll UP refused");}finally{event.recycle();}}
        scrolls++;settled();
    }
    private void multiEvent(long downAt,int action,Rect first,Rect second,int count){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++){
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i]=new MotionEvent.PointerCoords();Rect area=i==0?first:second;
            coordinates[i].x=area.exactCenterX();coordinates[i].y=area.exactCenterY();coordinates[i].pressure=1;coordinates[i].size=1;
        }
        MotionEvent event=MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),action,count,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{require(automation.injectInputEvent(event,true),"Owned two-contact event refused");}finally{event.recycle();}
    }
    private void splitChildContacts(Rect first,Rect second){
        long downAt=SystemClock.uptimeMillis();int active=0;
        try{
            multiEvent(downAt,MotionEvent.ACTION_DOWN,first,second,1);active=1;SystemClock.sleep(30);
            multiEvent(downAt,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),first,second,2);active=2;SystemClock.sleep(30);
            multiEvent(downAt,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),first,second,2);active=1;SystemClock.sleep(30);
            multiEvent(downAt,MotionEvent.ACTION_UP,first,second,1);active=0;multiSequences++;
        }finally{
            // A failed injection never gains a single-contact substitute. Cancel
            // the owned stream once for cleanup, then propagate the original failure.
            if(active!=0)try{multiEvent(downAt,MotionEvent.ACTION_CANCEL,first,second,active);}catch(Throwable ignored){}
        }
        settled();
    }
    private void openMenu(){
        settled();if(matches(APP,MENU).isEmpty()){tap("Show keyboard tools");toolbarNavigations++;}
        tap(MENU);find(OPEN);
    }
    private void openPanel(EditText field){State before=state(field);openMenu();tap(OPEN);find(RETURN);find(VIEWPORT);require(before.same(state(field)),"Opening text editing changed editor text/selection");}
    private void returnLetters(EditText field){State before=state(field);tap(RETURN);find("q");absent(VIEWPORT);require(before.same(state(field)),"Return to letters changed host text/selection");}
    private void range(EditText field,String expected,int low,int high){
        long until=SystemClock.uptimeMillis()+5000;State value;
        do{value=state(field);if(expected.equals(value.text)&&Math.min(value.start,value.end)==low&&Math.max(value.start,value.end)==high)return;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<until);
        expectDiagnostic="expectedRange="+low+":"+high+" actualSelection="+value.start+":"+value.end+" expectedTextLength="+expected.length()+" actualTextLength="+value.text.length()+" textEquals="+expected.equals(value.text);
        throw new AssertionError("Native selected range did not match");
    }
    private void checkedSelectionMode(boolean selected){
        AccessibilityNodeInfo node=find(SELECT);require(node.refresh()&&node.isCheckable()&&node.isChecked()==selected,"Selection-mode native checked state mismatch");
    }
    private void ownShiftCleared(int expectedDowns,int expectedUps){
        long deadline=SystemClock.uptimeMillis()+5000;
        do{
            int[] metadata=ui(()->new int[]{fixture.editingSyntheticShiftDowns,fixture.editingSyntheticShiftUps,fixture.editingShiftMetaState()});
            shiftDiagnostic="expectedDowns="+expectedDowns+" expectedUps="+expectedUps+" actualDowns="+metadata[0]+" actualUps="+metadata[1]+" bufferShiftState="+metadata[2];
            if(metadata[0]==expectedDowns&&metadata[1]==expectedUps&&metadata[0]==metadata[1]&&metadata[2]==0)return;
            SystemClock.sleep(30);
        }while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError("Owned synthetic Shift pair/zero buffer modifier was not confirmed");
    }
    private ClipData clip(){return ui(()->clipboard.getPrimaryClip());}
    private void clipText(String expected){ClipData value=clip();require(value!=null&&value.getItemCount()==1&&expected.contentEquals(value.getItemAt(0).getText()==null?"":value.getItemAt(0).getText()),"Synthetic clipboard roundtrip mismatch");}
    private void sameClip(ClipData expected){require(LipImeRunner.sameClipContent(expected,clip()),"Clipboard payload changed unexpectedly");}
    private void restoreClipboard(){
        if(!clipboardCaptured)return;
        clipboardCaptured=false; // Consume once before a possibly ambiguous OS write; never blindly retry.
        ui(()->{if(savedClipboard==null)clipboard.clearPrimaryClip();else clipboard.setPrimaryClip(savedClipboard);return null;});
        require(LipImeRunner.sameClipContent(savedClipboard,clip()),"Original owned-VM clipboard payload restoration was not observed");
    }
    private void passed(Bundle report,String name){report.putBoolean(name,true);controls++;}
    private void panelEvidence(Bundle report){
        require(verifiedFixture&&automation!=null&&fixture!=null,"Owned fixture must be verified before panel capture");
        // Observe menu departure and fresh stable native geometry, not a guessed
        // sleep or an animation setting change. Pixel review remains a separate gate.
        absent(OPEN);settled();
        Rect viewport=stableTapBounds(VIEWPORT,true),returnButton=stableTapBounds(RETURN,true);
        report.putString("panel_geometry_at_capture","viewport="+viewport.toShortString()+" return="+returnButton.toShortString());
        Bitmap image=automation.takeScreenshot();require(image!=null,"Owned panel screenshot unavailable");
        try{
            File png=File.createTempFile("text-editing-panel-",".png",getContext().getFilesDir());
            try(FileOutputStream output=new FileOutputStream(png)){require(image.compress(Bitmap.CompressFormat.PNG,100,output),"Owned panel screenshot encoding refused");}
            report.putString("panel_screenshot_file",png.getName());
        }catch(java.io.IOException failure){throw new AssertionError("Owned panel screenshot storage failed",failure);}
        finally{image.recycle();}
    }
    private void privatePhase(Bundle report,EditText field,String label){
        String metadata=ui(()->{
            int[] audit=fixture.editingPrivateAuditSnapshot(field);
            int reads=field==fixture.password?fixture.passwordCursorContextReads:fixture.incognitoCursorContextReads;
            return "selection="+field.getSelectionStart()+":"+field.getSelectionEnd()+" textLength="+field.length()+" textEquals="+"PRIVATE".contentEquals(field.getText())+" contextReadCalls="+reads
                +" icSetSelection="+audit[0]+" icCommit="+audit[1]+" icDelete="+audit[2]+" icFinishComposing="+audit[3]+" icComposingRegion="+audit[4]+" icComposingText="+audit[5]+" icMenu="+audit[6]+" icKey="+audit[7]
                +" lastICSelection="+audit[8]+":"+audit[9]+" connectionToken="+fixture.editingPrivateConnectionToken(field)+" hostWindowFocus="+fixture.hasWindowFocus();
        });
        report.putString(label,metadata);
    }
    private static final class PrivateExpectedState extends AssertionError {
        final String originalStage,originalDiagnostic;
        PrivateExpectedState(String stage,String diagnostic){super("Owned editor text/selection confirmation failed");originalStage=stage;originalDiagnostic=diagnostic;}
    }
    private void privateNoSideEffects(EditText field,long token,ClipData beforeClip,int beforeReads,int[] beforeAudit){
        require("PRIVATE".equals(state(field).text),"Private route changed the synthetic body; collection stopped");
        require(ui(()->fixture.getCurrentFocus()==field&&field.hasFocus()&&fixture.hasWindowFocus()&&fixture.editingPrivateConnectionToken(field)==token&&fixture.getSystemService(InputMethodManager.class).isActive(field)),"Private field/connection identity changed; collection stopped");
        sameClip(beforeClip);
        require(ui(()->field==fixture.password?fixture.passwordCursorContextReads:fixture.incognitoCursorContextReads)==beforeReads,"Private route added a context read; collection stopped");
        int[] now=ui(()->fixture.editingPrivateAuditSnapshot(field));
        for(int i=0;i<8;i++)require(now[i]==beforeAudit[i],"Private route dispatched an InputConnection mutation/menu/key; collection stopped");
    }
    private void privateAtomicCase(Bundle report,EditText field,String name){
        stage="private-entry-refusal/"+name+"/setup";setupPrivate(field,"PRIVATE",0,7);
        final long token=ui(()->fixture.editingPrivateConnectionToken(field));require(token>0,"Private premise lacks an admitted connection");
        ClipData beforeClip=clip();int reads=ui(()->field==fixture.password?fixture.passwordCursorContextReads:fixture.incognitoCursorContextReads);
        int[] beforeAudit=ui(()->fixture.editingPrivateAuditSnapshot(field));
        privatePhase(report,field,"private_"+name+"_before_menu");
        stage="private-entry-refusal/"+name+"/entry/menu-open";openMenu();privatePhase(report,field,"private_"+name+"_after_open");
        stage="private-entry-refusal/"+name+"/entry/disabled-touch";tap(OPEN,false);privatePhase(report,field,"private_"+name+"_after_disabled_touch");absent(VIEWPORT);
        stage="private-entry-refusal/"+name+"/entry/back";dismissMenu();privatePhase(report,field,"private_"+name+"_after_back");
        stage="private-entry-refusal/"+name+"/assert";
        privateNoSideEffects(field,token,beforeClip,reads,beforeAudit);
        try{expect(field,"PRIVATE",0,7);} // ORIGINAL oracle, never repaired or retried.
        catch(AssertionError failure){
            if(!"Owned editor text/selection confirmation failed".equals(failure.getMessage()))throw failure;
            // Isolate a selection-only mismatch only AFTER re-confirming that no
            // body/clipboard/IPC/privacy/connection guard has failed during observation.
            privateNoSideEffects(field,token,beforeClip,reads,beforeAudit);
            String originalStage=stage,originalDiagnostic=expectDiagnostic;
            report.putString("private_"+name+"_original_failure_kind",failure.getClass().getSimpleName());
            report.putString("private_"+name+"_original_failure_check",failure.getMessage());
            report.putString("private_"+name+"_original_failure_stage",originalStage);
            report.putString("private_"+name+"_original_expect_diagnostic",originalDiagnostic);
            stage="private-entry-refusal/"+name+"/safe-cleanup";
            if(menuPresent())dismissMenu(); // Fresh owned popup only; never Back to an editor.
            privateNoSideEffects(field,token,beforeClip,reads,beforeAudit);
            privatePhase(report,field,"private_"+name+"_after_safe_cleanup");
            throw new PrivateExpectedState(originalStage,originalDiagnostic);
        }
        privateNoSideEffects(field,token,beforeClip,reads,beforeAudit);
    }
    private void privatePopupCase(Bundle report,String label,boolean disabledTouch){
        stage="private-popup-baseline/"+label+"/setup";setupPrivate(fixture.password,"PRIVATE",0,7);
        privatePhase(report,fixture.password,label+"_before_menu");
        stage="private-popup-baseline/"+label+"/open";openMenu();privatePhase(report,fixture.password,label+"_after_open");
        require(menuPresent(),"Matched private Tools popup did not actually open");
        if(disabledTouch){stage="private-popup-baseline/"+label+"/disabled-touch";tap(OPEN,false);privatePhase(report,fixture.password,label+"_after_disabled_touch");}
        else report.putBoolean(label+"_disabled_touch_performed",false);
        stage="private-popup-baseline/"+label+"/back";
        boolean present=menuPresent();report.putBoolean(label+"_popup_present_before_back",present);
        if(present){dismissMenu();report.putBoolean(label+"_back_sent",true);}
        else{require(disabledTouch,"Control Tools popup departed before matched Back");report.putBoolean(label+"_back_sent",false);}
        privatePhase(report,fixture.password,label+"_after_back_phase");
    }
    private void privatePopupBaseline(Bundle report){
        report.putBoolean("private_popup_baseline_only",true);
        // Exactly two newly admitted premises on the same frozen IME. Never
        // repair their selections after admission or interpret this as acceptance.
        privatePopupCase(report,"control",false);
        privatePopupCase(report,"disabled",true);
        require(ui(()->fixture.actions)==0&&ui(()->fixture.lastCustomAction)==0,"Diagnostic triggered Send/editor action");
        report.putInt("diagnostic_cases",2);
        report.putString("result","INFO: matched private Tools popup observations only; no product/privacy PASS, main 0:7 oracle unchanged");
    }
    private void fixtureMetadata(Bundle report){
        if(!verifiedFixture||fixture==null)return;
        try{ui(()->{
            android.view.View focused=fixture.getCurrentFocus();
            String identity=focused==fixture.normal?"normal":focused==fixture.multiline?"multiline":focused==fixture.password?"password":focused==fixture.incognito?"incognito":"other";
            report.putString("fixture_active_field",identity);
            report.putInt("fixture_normal_connection_creations",fixture.inputConnectionCreations);
            report.putInt("fixture_multiline_connection_creations",fixture.editingMultilineConnectionCreations);
            report.putInt("fixture_password_connection_creations",fixture.editingPasswordConnectionCreations);report.putInt("fixture_incognito_connection_creations",fixture.editingIncognitoConnectionCreations);
            if(focused instanceof EditText)report.putLong("fixture_private_connection_token",fixture.editingPrivateConnectionToken((EditText)focused));
            report.putInt("fixture_synthetic_shift_downs",fixture.editingSyntheticShiftDowns);report.putInt("fixture_synthetic_shift_ups",fixture.editingSyntheticShiftUps);report.putInt("fixture_buffer_shift_state",fixture.editingShiftMetaState());
            if(focused instanceof EditText){EditText field=(EditText)focused;report.putInt("fixture_active_input_type",field.getInputType());report.putInt("fixture_active_ime_options",field.getImeOptions());report.putBoolean("fixture_active_field_served",fixture.getSystemService(InputMethodManager.class).isActive(field));}
            report.putBoolean("fixture_window_focus",fixture.hasWindowFocus());return null;
        });}catch(Throwable failure){report.putString("fixture_metadata","Unavailable");}
    }
    private void failureEvidence(Bundle report){
        if(!verifiedFixture||automation==null||fixture==null)return;
        try{
            Set<String> allowed=new HashSet<>(COMMANDS);allowed.addAll(java.util.Arrays.asList(MENU,OPEN,VIEWPORT,RETURN,"Show keyboard tools","q"));
            StringBuilder trace=new StringBuilder("Owned VM synthetic fixture; no editor/clipboard text\n");trace.append("stage=").append(stage).append(" last-control=").append(allowed.contains(lastControl)?lastControl:"[omitted]").append(" last-touch=").append(lastTouchBounds).append(" down=").append(lastDownAt).append(" up=").append(lastUpAt).append('\n');
            Set<AccessibilityNodeInfo> seen=new HashSet<>();int count=0;
            for(AccessibilityNodeInfo node:ownedNodes(APP))if(seen.add(node)&&++count<=512&&trace.length()<60000){
                Rect area=new Rect();node.getBoundsInScreen(area);String label=node.getContentDescription()==null?"":node.getContentDescription().toString();
                trace.append("class=").append(node.getClassName()).append(" window=").append(node.getWindowId()).append(" visible=").append(node.isVisibleToUser()).append(" enabled=").append(node.isEnabled()).append(" bounds=").append(area.toShortString()).append(" description=").append(allowed.contains(label)?label:"[omitted]").append('\n');
            }
            File file=File.createTempFile("text-editing-failure-",".ax.txt",getContext().getFilesDir());try(FileOutputStream out=new FileOutputStream(file)){out.write(trace.toString().getBytes(StandardCharsets.UTF_8));}report.putString("failure_ax_file",file.getName());
            Bitmap image=automation.takeScreenshot();if(image!=null){File png=File.createTempFile("text-editing-failure-",".png",getContext().getFilesDir());try(FileOutputStream out=new FileOutputStream(png)){require(image.compress(Bitmap.CompressFormat.PNG,100,out),"Owned screenshot encoding refused");}finally{image.recycle();}report.putString("failure_screenshot_file",png.getName());}
        }catch(Throwable failure){report.putString("failure_evidence","Owned diagnostic unavailable");}
    }

    @Override public void onStart(){
        Bundle report=new Bundle();int result=Activity.RESULT_CANCELED;
        try{
            require(authorizedFixture&&("ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE))&&Build.PRODUCT.contains("sdk")&&Build.VERSION.SDK_INT>=36,"Explicit Android16+ owned emulator required; no phone route");
            require(TEST.equals(getContext().getPackageName())&&TEST.equals(getTargetContext().getPackageName()),"Exact test package required");
            require((getContext().getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&(getContext().getPackageManager().getApplicationInfo(APP,0).flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,"Debug app/test packages required");
            ComponentName ime=ComponentName.unflattenFromString(Settings.Secure.getString(getContext().getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD));
            require(ime!=null&&APP.equals(ime.getPackageName())&&"dev.lip.keyboard.LipInputMethodService".equals(ime.getClassName()),"Parent must explicitly preselect Lip on owned VM");
            stage="fixture";String generation=UUID.randomUUID().toString();
            fixture=(ImeFixtureActivity)startActivitySync(new Intent().setClassName(TEST,ImeFixtureActivity.class.getName()).putExtra("fixtureGeneration",generation).putExtra("textEditingFixture",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);AccessibilityServiceInfo service=automation.getServiceInfo();service.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(service);
            find(TEST,"Lip synthetic generation "+generation);require(generation.equals(ui(()->fixture.getIntent().getStringExtra("fixtureGeneration"))),"Fixture incarnation mismatch");verifiedFixture=true;
            if(privatePopupBaseline){privatePopupBaseline(report);result=Activity.RESULT_OK;}
            else{
            setup(fixture.normal,"");clipboard=fixture.getSystemService(ClipboardManager.class);savedClipboard=clip();clipboardCaptured=true;
            ui(()->{clipboard.setPrimaryClip(ClipData.newPlainText("Owned text editing fixture","ROUNDTRIP_SEED"));return null;});clipText("ROUNDTRIP_SEED");

            stage="reachable-open-return";setup(fixture.normal,"UNCHANGED");openPanel(fixture.normal);returnLetters(fixture.normal);expect(fixture.normal,"UNCHANGED");clipText("ROUNDTRIP_SEED");passed(report,"TOOLS_ROUTE_OPEN_RETURN_NO_EDIT");

            stage="clipboard-roundtrip";setup(fixture.normal,"ALPHA BETA");openPanel(fixture.normal);
            int selects=ui(()->fixture.editingSelectAllCommands),copies=ui(()->fixture.editingCopyCommands),cuts=ui(()->fixture.editingCutCommands),pastes=ui(()->fixture.editingPasteCommands);
            tap(ALL);range(fixture.normal,"ALPHA BETA",0,10);tap(COPY);range(fixture.normal,"ALPHA BETA",0,10);clipText("ALPHA BETA");
            tap(CUT);expect(fixture.normal,"");clipText("ALPHA BETA");ClipData copied=clip();tap(PASTE);expect(fixture.normal,"ALPHA BETA");sameClip(copied);panelEvidence(report);
            require(ui(()->fixture.editingSelectAllCommands)==selects+1&&ui(()->fixture.editingCopyCommands)==copies+1&&ui(()->fixture.editingCutCommands)==cuts+1&&ui(()->fixture.editingPasteCommands)==pastes+1,"Clipboard host commands were missing/repeated");returnLetters(fixture.normal);passed(report,"SELECT_ALL_COPY_CUT_PASTE_REAL_HOST_ROUNDTRIP");

            stage="split-child-two-contact-refusal";setup(fixture.normal,"CONTACT");openPanel(fixture.normal);tap(ALL);range(fixture.normal,"CONTACT",0,7);checkedSelectionMode(false);
            reveal(SELECT);reveal(CUT);Rect firstContact=stableTapBounds(SELECT,true),secondContact=stableTapBounds(CUT,true),contactViewport=stableTapBounds(VIEWPORT,true);
            require(contactViewport.contains(firstContact)&&contactViewport.contains(secondContact),"Two-contact fixture controls must be simultaneously visible");
            State contactState=state(fixture.normal);ClipData contactClip=clip();
            int contactAll=ui(()->fixture.editingSelectAllCommands),contactCut=ui(()->fixture.editingCutCommands),contactCopy=ui(()->fixture.editingCopyCommands),contactPaste=ui(()->fixture.editingPasteCommands),contactKeys=ui(()->fixture.editingNavigationCommands);
            splitChildContacts(firstContact,secondContact);
            require(contactState.same(state(fixture.normal)),"Split-child contacts edited text/selection");sameClip(contactClip);checkedSelectionMode(false);
            require(ui(()->fixture.editingSelectAllCommands)==contactAll&&ui(()->fixture.editingCutCommands)==contactCut&&ui(()->fixture.editingCopyCommands)==contactCopy&&ui(()->fixture.editingPasteCommands)==contactPaste&&ui(()->fixture.editingNavigationCommands)==contactKeys,"Split-child contacts dispatched a host editing command");
            // New deliberate contact AFTER every pointer is up, not a retry of B.
            tap(CUT);expect(fixture.normal,"");clipText("CONTACT");require(ui(()->fixture.editingCutCommands)==contactCut+1,"Fresh single contact did not recover after all pointers lifted");returnLetters(fixture.normal);passed(report,"SPLIT_CHILD_TWO_CONTACTS_REFUSED_FRESH_SINGLE_CUT_WORKS");

            stage="four-arrows-line-document";
            // Fixed multiline EditorInfo was created before first focus. Never
            // mutate the live single-line editor's type/restart it as test setup.
            setup(fixture.multiline,"aa\naa\naa",4,4);openPanel(fixture.multiline);
            int nav=ui(()->fixture.editingMultilineNavigationDowns);
            tap(LEFT);expect(fixture.multiline,"aa\naa\naa",3,3);tap(RIGHT);expect(fixture.multiline,"aa\naa\naa",4,4);tap(UP);expect(fixture.multiline,"aa\naa\naa",1,1);tap(DOWN);expect(fixture.multiline,"aa\naa\naa",4,4);
            tap(LINE_START);expect(fixture.multiline,"aa\naa\naa",3,3);tap(LINE_END);expect(fixture.multiline,"aa\naa\naa",5,5);tap(TEXT_START);expect(fixture.multiline,"aa\naa\naa",0,0);tap(TEXT_END);expect(fixture.multiline,"aa\naa\naa",8,8);
            require(ui(()->fixture.editingMultilineNavigationDowns)==nav+8,"Navigation did not dispatch exactly eight native DOWN requests");returnLetters(fixture.multiline);passed(report,"FOUR_NATIVE_ARROWS_AND_DISTINCT_LINE_DOCUMENT_ENDPOINTS");

            stage="backward-emoji-selection";setup(fixture.normal,"A😀B",4,4);openPanel(fixture.normal);int beforeToggle=ui(()->fixture.editingNavigationDowns),shiftDown=ui(()->fixture.editingSyntheticShiftDowns),shiftUp=ui(()->fixture.editingSyntheticShiftUps);ownShiftCleared(shiftDown,shiftUp);
            tap(SELECT);checkedSelectionMode(true);expect(fixture.normal,"A😀B",4,4);require(ui(()->fixture.editingNavigationDowns)==beforeToggle,"Local Select toggle dispatched a host movement");ownShiftCleared(shiftDown,shiftUp);
            tap(LEFT);expect(fixture.normal,"A😀B",4,3);ownShiftCleared(shiftDown+1,shiftUp+1);tap(LEFT);expect(fixture.normal,"A😀B",4,1);ownShiftCleared(shiftDown+2,shiftUp+2);tap(COPY);expect(fixture.normal,"A😀B",4,1);clipText("😀B");ownShiftCleared(shiftDown+2,shiftUp+2);
            tap(RIGHT);expect(fixture.normal,"A😀B",4,3);ownShiftCleared(shiftDown+3,shiftUp+3);tap(RIGHT);expect(fixture.normal,"A😀B",4,4);ownShiftCleared(shiftDown+4,shiftUp+4);tap(LEFT);expect(fixture.normal,"A😀B",4,3);ownShiftCleared(shiftDown+5,shiftUp+5);tap(LEFT);expect(fixture.normal,"A😀B",4,1);ownShiftCleared(shiftDown+6,shiftUp+6);
            tap(SELECT);checkedSelectionMode(false);ownShiftCleared(shiftDown+6,shiftUp+6);tap(LEFT);expect(fixture.normal,"A😀B",1,1);ownShiftCleared(shiftDown+6,shiftUp+6);report.putBoolean("own_shift_modifier_clear",true);report.putInt("select_command_shift_pairs",6);returnLetters(fixture.normal);passed(report,"SELECT_MODE_BACKWARD_EMOJI_RANGE_AND_NATIVE_COLLAPSE");

            stage="host-refuses-no-retry";setup(fixture.normal,"REFUSE");openPanel(fixture.normal);tap(ALL);range(fixture.normal,"REFUSE",0,6);ClipData refusedClip=clip();int refusedCuts=ui(()->fixture.editingCutCommands);
            ui(()->{fixture.refuseEditingContextCommands=true;return null;});tap(CUT);range(fixture.normal,"REFUSE",0,6);sameClip(refusedClip);require(ui(()->fixture.editingCutCommands)==refusedCuts+1,"Refused Cut retried or bypassed host");ui(()->{fixture.refuseEditingContextCommands=false;fixture.refuseEditingNavigation=true;return null;});
            int refusedDown=ui(()->fixture.editingNavigationDowns),refusedKeys=ui(()->fixture.editingNavigationCommands);tap(RIGHT);range(fixture.normal,"REFUSE",0,6);require(ui(()->fixture.editingNavigationDowns)==refusedDown+1&&ui(()->fixture.editingNavigationCommands)==refusedKeys+2,"Refused navigation retried or lost captured UP cleanup");ui(()->{fixture.refuseEditingNavigation=false;return null;});returnLetters(fixture.normal);passed(report,"HOST_REFUSAL_SINGLE_DISPATCH_NO_EDITOR_OR_CLIPBOARD_CHANGE");

            stage="composing-refusal";setup(fixture.normal,"COMPOSE");openPanel(fixture.normal);tap(SELECT);checkedSelectionMode(true);ClipData composingClip=clip();int compositionCuts=ui(()->fixture.editingCutCommands),compositionKeys=ui(()->fixture.editingNavigationCommands);
            ui(()->{fixture.beginEditingComposition(0,4);return null;});SystemClock.sleep(150);checkedSelectionMode(false);
            for(String action:new String[]{ALL,CUT,COPY,PASTE,LEFT,SELECT})tap(action,false);
            expect(fixture.normal,"COMPOSE");require(ui(()->fixture.foreignStart())==0&&ui(()->fixture.foreignEnd())==4,"Disabled editing controls changed host composition");require(ui(()->fixture.editingCutCommands)==compositionCuts&&ui(()->fixture.editingNavigationCommands)==compositionKeys,"Composing context received a host edit/navigation command");sameClip(composingClip);returnLetters(fixture.normal);ui(()->{fixture.clearForeignComposition();return null;});passed(report,"COMPOSING_PAGE_DISABLES_COMMANDS_AND_RESETS_SELECTION_MODE");

            stage="stale-contact-editor-switch";setup(fixture.normal,"KEEP");openPanel(fixture.normal);tap(ALL);range(fixture.normal,"KEEP",0,4);reveal(CUT);Rect oldCut=stableTapBounds(CUT,true);int staleCuts=ui(()->fixture.editingCutCommands);ClipData staleClip=clip();ui(()->{fixture.multiline.setText("NEW");return null;});
            long heldAt=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(heldAt,heldAt,MotionEvent.ACTION_DOWN,oldCut.exactCenterX(),oldCut.exactCenterY(),0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{require(automation.injectInputEvent(down,true),"Stale-contact DOWN refused");}finally{down.recycle();}
            AtomicReference<State> departedNormal=new AtomicReference<>();
            try{ui(()->{fixture.multiline.requestFocus();fixture.multiline.setSelection(3);fixture.multiline.requestRectangleOnScreen(new Rect(0,0,fixture.multiline.getWidth(),fixture.multiline.getHeight()),true);fixture.getSystemService(InputMethodManager.class).showSoftInput(fixture.multiline,InputMethodManager.SHOW_IMPLICIT);return null;});find("q");departedNormal.set(state(fixture.normal));require("KEEP".equals(departedNormal.get().text),"Cut DOWN edited before release");}
            finally{MotionEvent up=MotionEvent.obtain(heldAt,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,oldCut.exactCenterX(),oldCut.exactCenterY(),0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{require(automation.injectInputEvent(up,true),"Stale-contact UP refused");}finally{up.recycle();}}
            require(departedNormal.get()!=null&&departedNormal.get().same(state(fixture.normal)),"Old release changed the departed editor");expect(fixture.multiline,"NEW");require(ui(()->fixture.editingCutCommands)==staleCuts,"Old editing contact dispatched after editor departure");sameClip(staleClip);absent(VIEWPORT);passed(report,"CUT_DOWN_OLD_CONTACT_EDITOR_SWITCH_UP_REFUSED");

            stage="private-entry-refusal";
            report.putString("private_password_outcome","NOT_RUN");report.putString("private_incognito_outcome","NOT_RUN");
            PrivateExpectedState firstPrivateFailure=null;int privateFailures=0,privateCompleted=0;
            for(EditText field:new EditText[]{fixture.password,fixture.incognito}){
                String name=field==fixture.password?"password":"incognito";
                try{
                    privateAtomicCase(report,field,name);
                    report.putString("private_"+name+"_outcome","PASS original 0:7/body/clipboard/read/IPC checks");privateCompleted++;
                }catch(PrivateExpectedState failure){
                    report.putString("private_"+name+"_outcome","FAIL original selection expectation; nonselection guards confirmed");
                    privateFailures++;privateCompleted++;
                    if(firstPrivateFailure==null){firstPrivateFailure=failure;report.putString("original_private_failure_field",name);report.putString("original_private_failure_stage",failure.originalStage);report.putString("original_private_failure_check",failure.getMessage());report.putString("original_private_expect_diagnostic",failure.originalDiagnostic);}
                    // Exactly the next independent field, never a retry/reselection
                    // of this admitted case. Overall failure is retained below.
                }catch(Throwable unsafe){
                    report.putString("private_"+name+"_outcome","STOP guard/identity/side-effect/cleanup failure");
                    report.putString("private_"+name+"_stop_stage",stage);
                    report.putInt("private_atomic_cases_completed",privateCompleted);report.putInt("private_atomic_failures",privateFailures+1);
                    throw unsafe;
                }
            }
            report.putInt("private_atomic_cases_completed",privateCompleted);report.putInt("private_atomic_failures",privateFailures);
            if(firstPrivateFailure!=null){
                stage=firstPrivateFailure.originalStage;expectDiagnostic=firstPrivateFailure.originalDiagnostic;
                report.putString("failure_check",firstPrivateFailure.getMessage());
                report.putString("result","FAIL: original private selection expectation failed; both atomic outcomes retained, no acceptance claim");
                report.putInt("named_controls",controls);
                throw firstPrivateFailure;
            }
            passed(report,"PASSWORD_INCOGNITO_TEXT_EDITING_ENTRY_REFUSED");
            require(ui(()->fixture.actions)==0&&ui(()->fixture.lastCustomAction)==0,"Text editing triggered Send/editor action");
            report.putInt("named_controls",controls);report.putInt("real_touch_taps",touches);report.putInt("multi_contact_sequences",multiSequences);report.putInt("real_panel_scrolls",scrolls);report.putInt("explicit_toolbar_navigations",toolbarNavigations);
            report.putString("result","PASS named real-touch owned-emulator EditText controls only; not physical/WebView/RTL/TalkBack/editor-matrix/full9.2 acceptance");result=Activity.RESULT_OK;
            }
        }catch(Throwable failure){report.putString("failure_stage",stage);report.putString("failure_kind",failure.getClass().getSimpleName());if(failure.getClass()==AssertionError.class)report.putString("failure_check",failure.getMessage());report.putString("expect_diagnostic",expectDiagnostic);report.putString("tap_geometry_diagnostic",tapGeometryDiagnostic);report.putString("shift_metadata_diagnostic",shiftDiagnostic);report.putString("private_setup_diagnostic",privateSetupDiagnostic);fixtureMetadata(report);failureEvidence(report);}
        finally{
            if(fixture!=null){try{ui(()->{fixture.refuseEditingContextCommands=false;fixture.refuseEditingNavigation=false;return null;});if(automation!=null&&menuPresent())dismissMenu();boolean restoreNeeded=clipboardCaptured;restoreClipboard();if(restoreNeeded)report.putBoolean("original_clipboard_payload_restored",true);else report.putString("clipboard_restoration","No clipboard mutation/capture occurred");}catch(Throwable failure){result=Activity.RESULT_CANCELED;report.putString("clipboard_cleanup","Original owned-VM clipboard restoration not confirmed; no retry");report.putString("result","FAIL/unconfirmed: clipboard cleanup was not confirmed; preceding controls do not establish a successful run");}
                try{ui(()->{fixture.finish();return null;});}catch(Throwable failure){result=Activity.RESULT_CANCELED;report.putString("fixture_cleanup","Owned fixture finish failed");report.putString("result","FAIL/unconfirmed: owned fixture cleanup failed; preceding controls do not establish a successful run");}}
        }
        finish(result,report);
    }
}

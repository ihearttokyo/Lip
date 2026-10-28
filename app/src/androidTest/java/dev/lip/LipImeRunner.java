package dev.lip;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Own-test-package Java runner: no reliance on target APK's Kotlin classloader. */
public final class LipImeRunner extends Instrumentation {
    private UiAutomation automation;
    private ImeFixtureActivity fixture;
    private String fieldState = "not focused";
    private String visualVariant;
    private boolean glideOnly;
    private boolean candidatesOnly;
    private boolean startupOnly;
    private boolean coldTallOnly;
    private boolean narrowHandOnly;
    private String narrowPhase;
    private boolean requireAbsentInsets;
    private int coldTallSetupPid;
    private boolean clipboardOnly;
    private boolean gifsOnly;
    private boolean typingOnly;
    private boolean typingUndoOnly;
    private boolean punctuationOnly;
    private boolean nextWordsOnly;
    private boolean nextWordProbe;
    private boolean cursorOnly;
    private boolean emojiOnly;
    private boolean emojiVisualOnly;
    private boolean emojiVariantsOnly;
    private boolean emojiDeleteOnly;
    private boolean wordDeleteOnly;
    private boolean deleteCrossingOnly;
    private boolean cursorCapture;
    private String cursorRouting="none";
    private int cursorPopupDismissals;
    private android.content.ClipData savedClipboard;
    private boolean clipboardCaptured;
    private android.content.ClipboardManager clipboard;
    private String lastTouch="none";
    @Override public void onCreate(Bundle args) { super.onCreate(args); visualVariant=args == null ? null : args.getString("visualVariant");narrowHandOnly=args!=null&&"narrowHand".equals(args.getString("mode"));narrowPhase=args==null?null:args.getString("narrowPhase");if(narrowHandOnly&&!java.util.Arrays.asList("fallback","wide").contains(narrowPhase)){Bundle report=new Bundle();report.putString("failure","Explicit narrow phase required");finish(Activity.RESULT_CANCELED,report);return;}requireAbsentInsets=args!=null&&"coldTall".equals(args.getString("mode"));coldTallOnly=requireAbsentInsets||(args!=null&&"firstTall".equals(args.getString("mode")));if(coldTallOnly)coldTallSetupPid=Integer.parseInt(args.getString("setupPid","0")); glideOnly=args!=null&&"glide".equals(args.getString("mode"));candidatesOnly=args!=null&&"candidates".equals(args.getString("mode"));startupOnly=args!=null&&"startup".equals(args.getString("mode"));clipboardOnly=args!=null&&"clipboard".equals(args.getString("mode"));gifsOnly=args!=null&&"gifs".equals(args.getString("mode"));typingOnly=args!=null&&"typing".equals(args.getString("mode"));typingUndoOnly=args!=null&&"typingUndo".equals(args.getString("mode"));punctuationOnly=args!=null&&"punctuation".equals(args.getString("mode"));nextWordsOnly=args!=null&&"nextword".equals(args.getString("mode"));nextWordProbe=args!=null&&"true".equals(args.getString("nextWordProbe"));cursorOnly=args!=null&&"cursor".equals(args.getString("mode"));wordDeleteOnly=args!=null&&"wordDelete".equals(args.getString("mode"));emojiOnly=args!=null&&"emoji".equals(args.getString("mode"));emojiVariantsOnly=args!=null&&"true".equals(args.getString("emojiVariantsOnly"));emojiVisualOnly=args!=null&&"true".equals(args.getString("emojiVisualOnly"));emojiDeleteOnly=args!=null&&"true".equals(args.getString("emojiDeleteOnly"));deleteCrossingOnly=args!=null&&"true".equals(args.getString("deleteCrossingOnly"));
        if(args!=null&&args.containsKey("mode")&&!java.util.Arrays.asList("glide","candidates","startup","clipboard","gifs","typing","typingUndo","punctuation","nextword","cursor","wordDelete","emoji","coldTall","firstTall","narrowHand").contains(args.getString("mode"))){Bundle report=new Bundle();report.putString("failure","Unknown explicit test mode; no default-suite fallback");finish(Activity.RESULT_CANCELED,report);return;}start(); }
    private void restoreClipboard(){
        if(!clipboardCaptured)return;
        clipboardCaptured=false; // Never blindly repeat an ambiguous restoration request.
        ui(()->{if(savedClipboard==null)clipboard.clearPrimaryClip();else clipboard.setPrimaryClip(savedClipboard);return null;});
        android.content.ClipData restored=ui(()->clipboard.getPrimaryClip());
        require(sameClipContent(savedClipboard,restored),"Prior clipboard content restoration was not observed");
    }
    public static boolean sameClipContent(android.content.ClipData a,android.content.ClipData b){
        if(a==null||b==null)return a==b;
        if(a.getItemCount()!=b.getItemCount()||a.getDescription().getMimeTypeCount()!=b.getDescription().getMimeTypeCount()||
            !android.text.TextUtils.equals(a.getDescription().getLabel(),b.getDescription().getLabel()))return false;
        for(int i=0;i<a.getDescription().getMimeTypeCount();i++)if(!a.getDescription().getMimeType(i).equals(b.getDescription().getMimeType(i)))return false;
        for(int i=0;i<a.getItemCount();i++){
            android.content.ClipData.Item x=a.getItemAt(i),y=b.getItemAt(i);
            if(!android.text.TextUtils.equals(x.getText(),y.getText())||!java.util.Objects.equals(x.getHtmlText(),y.getHtmlText())||!java.util.Objects.equals(x.getUri(),y.getUri()))return false;
            String xi=x.getIntent()==null?null:x.getIntent().toUri(android.content.Intent.URI_INTENT_SCHEME);
            String yi=y.getIntent()==null?null:y.getIntent().toUri(android.content.Intent.URI_INTENT_SCHEME);
            if(!java.util.Objects.equals(xi,yi))return false;
        }
        return true; // OS timestamp/ownership are not claimed restored; payload is checked in memory, never logged.
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private <T> T ui(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { result.set(action.get()); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError("Fixture UI operation failed: "+error.get().getMessage(), error.get());
        return result.get();
    }
    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes, int depth) {
        if (node == null || depth > 32 || nodes.size() > 2048) return;
        nodes.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), nodes, depth + 1);
    }
    private AccessibilityNodeInfo find(String description) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            automation.getWindows().forEach(w -> collect(w.getRoot(), nodes, 0));
            AccessibilityNodeInfo match = null; int count = 0;
            for (AccessibilityNodeInfo n : nodes) {
                if ("dev.lip.android".contentEquals(n.getPackageName() == null ? "" : n.getPackageName())
                        && description.contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription())) { match = n; count++; }
            }
            if (count == 1) return match;
            SystemClock.sleep(70);
        } while (SystemClock.uptimeMillis() < deadline);
        if(emojiOnly||narrowHandOnly){
            List<AccessibilityNodeInfo> debug=new ArrayList<>();automation.getWindows().forEach(w->collect(w.getRoot(),debug,0));
            StringBuilder trace=new StringBuilder();for(AccessibilityNodeInfo n:debug){String d=String.valueOf(n.getContentDescription());if("dev.lip.android".contentEquals(n.getPackageName()==null?"":n.getPackageName())&&(n.getContentDescription()!=null||d.contains("emoji"))){android.graphics.Rect r=new android.graphics.Rect();n.getBoundsInScreen(r);trace.append(n.getPackageName()).append("|").append(n.getClassName()).append("|").append(d).append("|").append("[text omitted]").append("|").append(n.isVisibleToUser()).append("|children=").append(n.getChildCount()).append("|").append(r.toShortString()).append("\n");}}
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"emoji-ax-failure.txt"))){out.write(trace.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
            try{image("emoji-control-failure.png");}catch(Exception ignored){}
            try{
                android.os.ParcelFileDescriptor pipe=automation.executeShellCommand("dumpsys input_method");
                try(java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe);FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"emoji-ime-at-failure.txt"))){out.write(input.readNBytes(262144));}
            }catch(Exception ignored){}
        }
        throw new AssertionError("Expected one Lip keyboard control: " + description + " · " + fieldState);
    }
    private void requireFixtureMarker(String description){
        long end=SystemClock.uptimeMillis()+5000;
        do{List<AccessibilityNodeInfo> nodes=new ArrayList<>();automation.getWindows().forEach(w->collect(w.getRoot(),nodes,0));
            if(nodes.stream().filter(n->n.isVisibleToUser()&&"dev.lip.android.test".contentEquals(n.getPackageName()==null?"":n.getPackageName())&&description.contentEquals(n.getContentDescription()==null?"":n.getContentDescription())).count()==1)return;
            SystemClock.sleep(60);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Owned fixture generation marker unavailable");
    }
    private boolean visible(String description){
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();automation.getWindows().forEach(w->collect(w.getRoot(),nodes,0));
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&"dev.lip.android".contentEquals(n.getPackageName()==null?"":n.getPackageName())&&description.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))return true;
        return false;
    }
    private void sameRow(String... descriptions) {
        try { automation.waitForIdle(300,3000); } catch(java.util.concurrent.TimeoutException e) { throw new AssertionError("Key row geometry did not settle",e); }
        android.graphics.Rect first=null;
        for(String description:descriptions){
            android.graphics.Rect rect=new android.graphics.Rect();find(description).getBoundsInScreen(rect);
            require(!rect.isEmpty(),"Empty key bounds: "+description);
            if(first==null)first=rect;
            else require(first.top==rect.top && first.bottom==rect.bottom,"Unequal key row bounds: "+description+" "+first+" vs "+rect);
        }
    }
    private void touchTap(String description) {touchTapAt(description);}
    private long touchTapAt(String description) {
        // Input injection synchronizes animations on DOWN; obtain bounds only after
        // accessibility/window changes settle, not from a sliding-in keyboard frame.
        try { automation.waitForIdle(300,3000); } catch(java.util.concurrent.TimeoutException e) { throw new AssertionError("Touch geometry did not settle",e); }
        if(hostSpellingPopupVisible()){
            try{image("fixture-touch-popup-"+cursorPopupDismissals+".png");}catch(Exception error){throw new AssertionError("Popup precondition capture failed",error);}
            dismissFixtureSpellingPopup(); // Explicit fixture precondition, never replay a dropped key.
        }
        AccessibilityNodeInfo target=find(description);
        long readyDeadline=SystemClock.uptimeMillis()+5000;
        while(!target.isEnabled()&&SystemClock.uptimeMillis()<readyDeadline){SystemClock.sleep(60);target=find(description);}
        require(target.isEnabled()&&target.isVisibleToUser(),"Touch target not ready: "+description);
        android.graphics.Rect rect=new android.graphics.Rect();target.getBoundsInScreen(rect);
        lastTouch=description+" at "+rect;
        long now=SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+25,android.view.MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        boolean downAccepted=automation.injectInputEvent(down,true);
        long beforeUpDispatch=SystemClock.uptimeMillis(); // onClick/correction timer cannot precede this UP.
        require(downAccepted&&automation.injectInputEvent(up,true),"Touch injection refused: "+description);
        down.recycle();up.recycle();SystemClock.sleep(90);
        return beforeUpDispatch;
    }
    private void glide(String word) throws Exception {glide(word,null);}
    private void glide(String word,Runnable duringGlide) throws Exception {
        if(duringGlide==null)automation.waitForIdle(300,3000);
        List<float[]> centers=new ArrayList<>();char previous=0;
        for(char c:word.toCharArray()) {
            if(c==previous)continue;previous=c;
            android.graphics.Rect rect=new android.graphics.Rect();find(String.valueOf(c)).getBoundsInScreen(rect);
            centers.add(new float[]{rect.exactCenterX(),rect.exactCenterY()});
        }
        long start=SystemClock.uptimeMillis();float[] first=centers.get(0);
        android.view.MotionEvent event=android.view.MotionEvent.obtain(start,start,android.view.MotionEvent.ACTION_DOWN,first[0],first[1],0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Glide DOWN refused");event.recycle();
        try {
            for(int i=1;i<centers.size();i++)for(int step=1;step<=20;step++){
                float f=step/20f;float[] from=centers.get(i-1),to=centers.get(i);SystemClock.sleep(10);
                event=android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,from[0]+(to[0]-from[0])*f,from[1]+(to[1]-from[1])*f,0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Glide MOVE refused");event.recycle();
                if(i==1&&step==5&&duringGlide!=null)duringGlide.run();
            }
        } finally {
            float[] last=centers.get(centers.size()-1);
            event=android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,last[0],last[1],0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(event,true);event.recycle();
        }
    }
    private boolean hostSpellingPopupVisible(){
        List<AccessibilityNodeInfo> all=new ArrayList<>();automation.getWindows().forEach(w->collect(w.getRoot(),all,0));
        return all.stream().anyMatch(n->n.isVisibleToUser()&&"dev.lip.android.test".contentEquals(n.getPackageName()==null?"":n.getPackageName())&&"ADD TO DICTIONARY".equalsIgnoreCase(String.valueOf(n.getText())));
    }
    private void dismissFixtureSpellingPopup(){
        if(!hostSpellingPopupVisible())return;
        EditText focused=ui(()->fixture.getCurrentFocus() instanceof EditText?(EditText)fixture.getCurrentFocus():null);
        require(focused!=null,"Host spelling popup lost fixture editor identity");int start=ui(()->focused.getSelectionStart()),end=ui(()->focused.getSelectionEnd());String original=ui(()->focused.getText().toString());
        hardware(android.view.KeyEvent.KEYCODE_BACK);cursorPopupDismissals++;
        long deadline=SystemClock.uptimeMillis()+3000;while(hostSpellingPopupVisible()&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(40);
        require(!hostSpellingPopupVisible()&&ui(()->focused.getSelectionStart())==start&&ui(()->focused.getSelectionEnd())==end&&original.equals(ui(()->focused.getText().toString())),"Host popup dismissal changed text/cursor or was not observed");
    }
    private void cursorDrag(float[] units,int finishAction,Runnable midway)throws Exception{
        automation.waitForIdle(300,3000);dismissFixtureSpellingPopup();automation.waitForIdle(300,3000);if(cursorCapture)image("cursor-before-combining.png");AccessibilityNodeInfo space=find("Space");android.graphics.Rect bounds=new android.graphics.Rect();space.getBoundsInScreen(bounds);
        StringBuilder windows=new StringBuilder();for(android.view.accessibility.AccessibilityWindowInfo w:automation.getWindows()){if(w.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD){android.graphics.Rect windowBounds=new android.graphics.Rect();w.getBoundsInScreen(windowBounds);windows.append(w.getId()).append(":").append(windowBounds.toShortString());}}
        cursorRouting="space="+bounds.toShortString()+",visible="+space.isVisibleToUser()+",enabled="+space.isEnabled()+",window="+space.getWindowId()+",IMEs="+windows;
        require(space.isVisibleToUser()&&space.isEnabled()&&!bounds.isEmpty(),"Cursor fixture has no visible enabled Space: "+cursorRouting);
        float quantum=ui(()->Math.max((int)(14*fixture.getResources().getDisplayMetrics().density),android.view.ViewConfiguration.get(fixture).getScaledTouchSlop()+1));
        float x=bounds.exactCenterX(),y=bounds.exactCenterY();final int firstArrowBaseline=ui(()->fixture.cursorArrowDowns);long down=SystemClock.uptimeMillis();
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,x,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        require(automation.injectInputEvent(event,false),"Space contact DOWN refused");event.recycle();float last=x;
        try{
            for(int i=0;i<units.length;i++){
                last=x+units[i]*quantum;require(last>=0&&last<fixture.getResources().getDisplayMetrics().widthPixels,"Cursor test path left the physical display");event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,last,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                if(i==0)require(event.getEventTime()-down<android.view.ViewConfiguration.getLongPressTimeout(),"Cursor fixture missed pre-longpress first movement: "+(event.getEventTime()-down));
                require(automation.injectInputEvent(event,false),"Space contact MOVE refused");event.recycle();SystemClock.sleep(25);
                if(i==0&&midway!=null){long end=SystemClock.uptimeMillis()+2000;while(ui(()->fixture.cursorArrowDowns)<=firstArrowBaseline&&SystemClock.uptimeMillis()<end)SystemClock.sleep(20);require(ui(()->fixture.cursorArrowDowns)>firstArrowBaseline,"First cursor move did not reach original host before editor switch");midway.run();}
            }
        }finally{
            event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),finishAction,last,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            require(automation.injectInputEvent(event,true),"Space contact final event refused");event.recycle();
        }
        SystemClock.sleep(160);
    }
    private void wordDeleteDrag(float[] units,int finishAction,Runnable preview)throws Exception{wordDeleteDrag(units,finishAction,preview,0f);}
    private void wordDeleteDrag(float[] units,int finishAction,Runnable preview,float finalVertical)throws Exception{
        automation.waitForIdle(300,3000);dismissFixtureSpellingPopup();automation.waitForIdle(300,3000);
        android.graphics.Rect bounds=new android.graphics.Rect();AccessibilityNodeInfo key=find("Delete previous character; hold to repeat");key.getBoundsInScreen(bounds);
        require(key.isVisibleToUser()&&key.isEnabled()&&!bounds.isEmpty(),"No visible Backspace target");
        float quantum=Math.max((int)(28*fixture.getResources().getDisplayMetrics().density),android.view.ViewConfiguration.get(fixture).getScaledTouchSlop()+1);
        float x=bounds.exactCenterX(),y=bounds.exactCenterY(),last=x;long down=SystemClock.uptimeMillis();
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,x,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,false),"Delete DOWN refused");event.recycle();
        try{
            for(int i=0;i<units.length;i++){
                last=x+units[i]*quantum;require(last>=0&&last<fixture.getResources().getDisplayMetrics().widthPixels,"Delete gesture path left display");
                event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,last,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                if(i==0)require(event.getEventTime()-down<android.view.ViewConfiguration.getLongPressTimeout(),"Delete first move missed longpress deadline");
                require(automation.injectInputEvent(event,false),"Delete MOVE refused");event.recycle();SystemClock.sleep(60);
            }
            if(preview!=null)preview.run();
        }finally{
            event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),finishAction,last,y+finalVertical*quantum,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Delete final event refused");event.recycle();
        }
        SystemClock.sleep(250);
    }
    private void deleteLongHold()throws Exception{
        automation.waitForIdle(300,3000);dismissFixtureSpellingPopup();android.graphics.Rect r=new android.graphics.Rect();find("Delete previous character; hold to repeat").getBoundsInScreen(r);
        long down=SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY(),0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,false),"Delete longhold DOWN refused");event.recycle();
        try{SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+220);}
        finally{event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,r.exactCenterX(),r.exactCenterY(),0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Delete longhold UP refused");event.recycle();}
    }
    private void cursorVerticalCancel()throws Exception{
        automation.waitForIdle(300,3000);dismissFixtureSpellingPopup();android.graphics.Rect r=new android.graphics.Rect();find("Space").getBoundsInScreen(r);long down=SystemClock.uptimeMillis();float x=r.exactCenterX(),y=r.exactCenterY(),away=y-4*Math.max((int)(14*fixture.getResources().getDisplayMetrics().density),android.view.ViewConfiguration.get(fixture).getScaledTouchSlop()+1);
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP}){
            android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,action==android.view.MotionEvent.ACTION_DOWN?y:away,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{require(automation.injectInputEvent(event,action==android.view.MotionEvent.ACTION_UP),"Vertical cursor contact refused");}finally{event.recycle();}SystemClock.sleep(25);
        }
        SystemClock.sleep(150);
    }
    private void cursorMultitouch()throws Exception{keyMultitouch("Space");}
    private void keyMultitouch(String description)throws Exception{
        automation.waitForIdle(300,3000);dismissFixtureSpellingPopup();android.graphics.Rect r=new android.graphics.Rect();find(description).getBoundsInScreen(r);long down=SystemClock.uptimeMillis();
        motionPointer(down,android.view.MotionEvent.ACTION_DOWN,r,1,false);
        motionPointer(down,android.view.MotionEvent.ACTION_POINTER_DOWN|(1<<android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),r,2,false);
        motionPointer(down,android.view.MotionEvent.ACTION_POINTER_UP|(1<<android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),r,2,false);
        motionPointer(down,android.view.MotionEvent.ACTION_UP,r,1,true);SystemClock.sleep(150);
    }
    private void motionPointer(long down,int action,android.graphics.Rect r,int count,boolean sync){
        android.view.MotionEvent.PointerProperties[] properties=new android.view.MotionEvent.PointerProperties[count];android.view.MotionEvent.PointerCoords[] coords=new android.view.MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++){properties[i]=new android.view.MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=android.view.MotionEvent.TOOL_TYPE_FINGER;coords[i]=new android.view.MotionEvent.PointerCoords();coords[i].x=r.exactCenterX()+i*8;coords[i].y=r.exactCenterY();coords[i].pressure=1;coords[i].size=1;}
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try{require(automation.injectInputEvent(event,sync),"Cursor multitouch event refused");}finally{event.recycle();}
    }
    private boolean keyboardPickerVisible(){
        List<AccessibilityNodeInfo> all=new ArrayList<>();automation.getWindows().forEach(w->collect(w.getRoot(),all,0));
        boolean lipHeader=false,selectedLip=false,list=false,settings=false;
        for(AccessibilityNodeInfo node:all){
            if(!node.isVisibleToUser()||!"android".contentEquals(node.getPackageName()==null?"":node.getPackageName()))continue;
            String id=node.getViewIdResourceName(),text=String.valueOf(node.getText());
            if("android:id/header_text".equals(id))lipHeader="Lip Keyboard (development)".equals(text);
            if("android:id/list_item".equals(id)&&node.isSelected()&&lipHeader)selectedLip=true;
            if("android:id/list".equals(id))list=true;
            if("android:id/button1".equals(id)&&"Language Settings".contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))settings=true;
            if(text.equalsIgnoreCase("change keyboard")||text.equalsIgnoreCase("select input method"))return true;
        }
        return list&&selectedLip&&settings; // Observed API36 titleless INPUT_METHOD_DIALOG; never choose a row.
    }
    private void cursorLongHold()throws Exception{
        automation.waitForIdle(300,3000);dismissFixtureSpellingPopup();android.graphics.Rect r=new android.graphics.Rect();find("Space").getBoundsInScreen(r);
        String original=ui(()->android.provider.Settings.Secure.getString(fixture.getContentResolver(),android.provider.Settings.Secure.DEFAULT_INPUT_METHOD));require(original!=null&&!original.isEmpty(),"No readable original keyboard identity");
        long down=SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY(),0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,false),"Longhold DOWN refused");event.recycle();
        boolean shown=false;
        try{long end=SystemClock.uptimeMillis()+android.view.ViewConfiguration.getLongPressTimeout()+3000;while(!(shown=keyboardPickerVisible())&&SystemClock.uptimeMillis()<end)SystemClock.sleep(40);require(shown,"Stationary Space hold did not show actual system keyboard chooser");}
        finally{event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_CANCEL,r.exactCenterX(),r.exactCenterY(),0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(event,true);event.recycle();if(keyboardPickerVisible())hardware(android.view.KeyEvent.KEYCODE_BACK);}
        require(!keyboardPickerVisible()&&original.equals(ui(()->android.provider.Settings.Secure.getString(fixture.getContentResolver(),android.provider.Settings.Secure.DEFAULT_INPUT_METHOD))),"Chooser did not close or changed default keyboard");
    }
    private void cursorAt(EditText editor,int expected){
        long end=SystemClock.uptimeMillis()+5000;while(ui(()->editor.getSelectionStart())!=expected&&SystemClock.uptimeMillis()<end)SystemClock.sleep(40);
        require(ui(()->editor.getSelectionStart())==expected&&ui(()->editor.getSelectionEnd())==expected,"Actual host cursor mismatch; expected="+expected+" observed="+ui(()->editor.getSelectionStart())+" arrowDispatches="+ui(()->fixture.cursorArrowDispatches)+" arrowDowns="+ui(()->fixture.cursorArrowDowns)+" connectionCreations="+ui(()->fixture.inputConnectionCreations));
    }
    private void awaitText(String expected) {
        long deadline=SystemClock.uptimeMillis()+15000;
        while(!expected.equals(text())&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        require(expected.equals(text()),"Synthetic glide insertion mismatch; observed="+text());
    }
    private List<AccessibilityNodeInfo> suggestions() {
        long deadline=SystemClock.uptimeMillis()+5000;
        do {
            List<AccessibilityNodeInfo> nodes=new ArrayList<>();automation.getWindows().forEach(w->collect(w.getRoot(),nodes,0));
            List<AccessibilityNodeInfo> result=new ArrayList<>();
            for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&n.isEnabled()&&"dev.lip.android".contentEquals(n.getPackageName()==null?"":n.getPackageName())&&n.getContentDescription()!=null&&n.getContentDescription().toString().startsWith("Choose swipe suggestion "))result.add(n);
            if(result.size()==3)return result;
            SystemClock.sleep(70);
        }while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError("Three verified swipe choices unavailable");
    }
    private void hardware(int code){
        long time=SystemClock.uptimeMillis();
        for(int action:new int[]{android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP}){
            android.view.KeyEvent event=new android.view.KeyEvent(time,SystemClock.uptimeMillis(),action,code,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,android.view.InputDevice.SOURCE_KEYBOARD);
            require(automation.injectInputEvent(event,true),"Hardware-key injection refused");
        }
        SystemClock.sleep(70);
    }
    private String gifHash(String id)throws Exception{
        android.content.Context app=getContext().createPackageContext("dev.lip.android",0);
        try(java.io.InputStream input=app.getAssets().open("gif-manifest.json")){
            org.json.JSONArray items=new org.json.JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("items");
            for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals(id))return items.getJSONObject(i).getString("sha256");
        }
        throw new AssertionError("Unknown owned GIF expectation");
    }
    private void tap(String description) {
        AccessibilityNodeInfo n = find(description);
        require(n.isEnabled() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Control not usable: " + description);
        SystemClock.sleep(90); waitForIdleSync();
    }
    private void field(EditText edit, String text, int start, int end) {
        android.graphics.Rect bounds = ui(() -> {
            edit.requestFocus(); edit.setText(text);
            edit.requestRectangleOnScreen(new android.graphics.Rect(0,0,edit.getWidth(),edit.getHeight()), true);
            android.graphics.Rect rect = new android.graphics.Rect();
            require(edit.getGlobalVisibleRect(rect), "Fixture editor is not visible"); return rect;
        });
        // Exercise the actual editor's touch-to-show path, rather than assuming a
        // programmatic showSoftInput request means the IME became visible.
        long now = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now,now+20,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        require(automation.injectInputEvent(down,true) && automation.injectInputEvent(up,true), "Fixture touch failed"); down.recycle(); up.recycle();
        SystemClock.sleep(120); waitForIdleSync();
        ui(() -> {
            edit.setSelection(start,end);
            InputMethodManager manager = fixture.getSystemService(InputMethodManager.class);
            boolean shown = !startupOnly && manager.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
            fieldState = "focused="+edit.hasFocus()+",window="+fixture.hasWindowFocus()+",active="+manager.isActive(edit)+",showRequest="+shown;
            return null;
        });
        SystemClock.sleep(250); waitForIdleSync();
    }
    private void field(EditText edit, String text) { field(edit, text, text.length(), text.length()); }
    private String text() { return ui(() -> fixture.normal.getText().toString()); }
    private void waitText(String expected){
        long deadline=SystemClock.uptimeMillis()+5000;
        while(!expected.equals(text())&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(60);
        require(expected.equals(text()),"Typed text mismatch; expected="+expected+" actual="+text());
    }
    private void emojiCategory(String description){
        if(!visible(description)){
            require(find("Emoji categories").performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),"Emoji category strip could not scroll back");SystemClock.sleep(150);
        }
        touchTap(description);
    }
    private void longPressEmoji(String description){
        android.graphics.Rect r=new android.graphics.Rect();find(description).getBoundsInScreen(r);
        long down=SystemClock.uptimeMillis();
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY(),0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Emoji longpress DOWN refused");event.recycle();
        try{SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+150);}
        finally{event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,r.exactCenterX(),r.exactCenterY(),0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Emoji longpress UP refused");event.recycle();}
    }
    private void spellEmoji(String query){
        String expected="";
        for(char letter:query.toCharArray()){
            touchTap(letter==' '?"Space":String.valueOf(letter));expected+=letter;final String wanted="Search emoji: "+expected;
            List<AccessibilityNodeInfo> nodes=new ArrayList<>();automation.getWindows().forEach(w->collect(w.getRoot(),nodes,0));
            require(nodes.stream().anyMatch(n->n.isVisibleToUser()&&wanted.contentEquals(n.getText()==null?"":n.getText())),"Actual emoji query differs after touch "+letter+" at "+lastTouch+" expected="+wanted);
        }
    }
    private void spell(String text){for(char letter:text.toCharArray())touchTap(String.valueOf(letter));}
    private void image(String name) throws Exception {
        SystemClock.sleep(100);
        Bitmap bitmap=automation.takeScreenshot();
        require(bitmap!=null,"Screenshot unavailable");
        try(FileOutputStream stream=new FileOutputStream(new File(getTargetContext().getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}
        bitmap.recycle();
    }
    private void requireCompleteTallWitness(String value,int expected) throws Exception {
        org.json.JSONArray keys=new org.json.JSONObject(value).getJSONArray("keys");java.util.Set<String> names=new java.util.HashSet<>();
        for(int i=0;i<keys.length();i++){org.json.JSONObject key=keys.getJSONObject(i);require(names.add(key.getString("key")),"Duplicate key in presentation witness");require(key.getBoolean("complete")&&key.getInt("height")==expected,"Actual native key clipping in first Tall view: "+key);}
        require(names.equals(new java.util.HashSet<>(java.util.Arrays.asList("q","Space","Switch letters and symbols","Emoji keyboard",".","Send in the current editor"))),"Native complete-row witness key set mismatch");
    }
    private String rawPresentationWitness() throws Exception {
        android.os.ParcelFileDescriptor pipe=automation.executeShellCommand("dumpsys activity service dev.lip.android/dev.lip.keyboard.LipInputMethodService --lip-presentation-witness");
        byte[] data;try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)){data=in.readNBytes(65537);}
        require(data.length<=65536,"Presentation reply bound exceeded");List<String> matches=new ArrayList<>();
        for(String line:new String(data,java.nio.charset.StandardCharsets.UTF_8).split("\\n")){String clean=line.trim();if(clean.startsWith("LIP_PRESENTATION_WITNESS "))matches.add(clean.substring("LIP_PRESENTATION_WITNESS ".length()));}
        require(matches.size()==1,"Unique native chrome witness unavailable");return matches.get(0);
    }
    private String presentationWitness() throws Exception {
        android.os.ParcelFileDescriptor pipe=automation.executeShellCommand("dumpsys activity service dev.lip.android/dev.lip.keyboard.LipInputMethodService --lip-presentation-witness");
        byte[] data;try(java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)){data=input.readNBytes(65537);}
        require(data.length<=65536,"Presentation witness exceeded bounded metadata reply");
        String reply=new String(data,java.nio.charset.StandardCharsets.UTF_8);List<String> matches=new ArrayList<>();
        for(String line:reply.split("\\n")){String clean=line.trim();if(clean.startsWith("LIP_PRESENTATION_WITNESS "))matches.add(clean.substring("LIP_PRESENTATION_WITNESS ".length()));}
        require(matches.size()==1,"Unique metadata-only service witness unavailable");String value=matches.get(0);org.json.JSONObject item=new org.json.JSONObject(value).getJSONObject("creation");
        require(item.getInt("creates")==1&&item.getString("height").equals("TALL")&&item.getString("hand").equals("FULL")&&item.getString("theme").equals("GLASS")&&(!requireAbsentInsets||(!item.getBoolean("priorInsetsKnown")&&item.getInt("initialBottom")==0))&&coldTallSetupPid>0&&item.getInt("pid")!=coldTallSetupPid,"First-view/null-insets/persisted-Tall provenance not established: "+value);
        return value;
    }
    @Override public void onStart() {
        Bundle report = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        try {
            require("dev.lip.android.test".equals(getContext().getPackageName())
                    && "dev.lip.android.test".equals(getTargetContext().getPackageName()), "Own test package required");
            if(coldTallOnly)require("ranchu".equals(android.os.Build.HARDWARE),"Owned emulator required for cold Tall probe");
            final String fixtureGeneration=java.util.UUID.randomUUID().toString();
            fixture = (ImeFixtureActivity)startActivitySync(new Intent().setClassName(getContext().getPackageName(), ImeFixtureActivity.class.getName()).putExtra("fixtureGeneration",fixtureGeneration).putExtra("gifEditor",gifsOnly).putExtra("landscapeFixture","landscape".equals(visualVariant)).putExtra("darkFixture","dark-context".equals(visualVariant)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(service);
            long focusDeadline = SystemClock.uptimeMillis() + 5000;
            while (!ui(() -> fixture.hasWindowFocus()) && SystemClock.uptimeMillis() < focusDeadline) SystemClock.sleep(70);
            require(ui(() -> fixture.hasWindowFocus()), "Fixture did not gain window focus");
            require(fixtureGeneration.equals(ui(()->fixture.getIntent().getStringExtra("fixtureGeneration"))),"Fixture incarnation mismatch");
            requireFixtureMarker("Lip synthetic generation "+fixtureGeneration);
            report.putString("actual-configuration",ui(()->fixture.getResources().getConfiguration().toString()));
            field(fixture.normal, ""); find("Record with the selected local Bark model");
            if(narrowHandOnly){
                require("ranchu".equals(android.os.Build.HARDWARE),"Owned emulator required");
                boolean narrow="fallback".equals(narrowPhase);android.util.DisplayMetrics metrics=ui(()->fixture.getResources().getDisplayMetrics());
                require((metrics.widthPixels/metrics.density<320)==narrow,"Actual width premise does not match narrow/wide case");
                String raw=rawPresentationWitness();report.putString("presentation-witness",raw);org.json.JSONObject witness=new org.json.JSONObject(raw);
                require(witness.getJSONObject("creation").getString("hand").equals("LEFT")&&witness.getJSONObject("creation").getString("height").equals("STANDARD"),"Seeded LEFT/STANDARD preference not applied");
                org.json.JSONArray keys=witness.getJSONArray("keys");require(keys.length()==6,"Complete native footer witness required");
                for(int i=0;i<keys.length();i++)require(keys.getJSONObject(i).getBoolean("complete"),"Actual native clipping: "+keys.getJSONObject(i));
                android.graphics.Rect q=new android.graphics.Rect(),p=new android.graphics.Rect();find("q").getBoundsInScreen(q);find("p").getBoundsInScreen(p);
                require(q.left<=8*metrics.density,"LEFT content not left aligned");
                if(narrow){
                    require(!visible("Use full-width keyboard")&&!visible("Move keyboard right"),"Side rail remained visible below usable one-hand width");
                    require(p.right>=metrics.widthPixels-8*metrics.density,"Narrow fallback not full width");
                }else{
                    android.graphics.Rect rail=new android.graphics.Rect();find("Use full-width keyboard").getBoundsInScreen(rail);find("Move keyboard right");
                    require(p.right<=rail.left&&rail.width()>=48*metrics.density,"Saved LEFT did not return with usable rail");
                }
                report.putBoolean(narrow?"NARROW-ACTUAL-FULL-FALLBACK":"WIDE-SAVED-LEFT-RESTORED",true);
                image(narrow?"narrow-hand.png":"wide-hand.png");
                touchTap("q");waitText("q");touchTap("Space");waitText("q ");touchTap("Switch letters and symbols");touchTap("@");waitText("q @");touchTap("Switch letters and symbols");
                require(ui(()->fixture.actions)==0,"Unexpected host Send");report.putBoolean("HAND-NATIVE-COMPLETE-FOOTER-AND-TAPS",true);
                report.putString("result","PASS2 actual narrow/wide branch only; not split-window/device/fullKB05");outcome=Activity.RESULT_OK;return;
            }
            if(coldTallOnly){
                String initialWitness=presentationWitness();report.putString("presentation-before",initialWitness);
                require(ui(()->fixture.getResources().getConfiguration().orientation)==android.content.res.Configuration.ORIENTATION_PORTRAIT,"Portrait witness fixture required");
                android.util.DisplayMetrics metrics=ui(()->fixture.getResources().getDisplayMetrics());
                int base=Math.max((int)(40*metrics.density),Math.min((int)(56*metrics.density),(int)(metrics.widthPixels*.1125f)));
                int expected=Math.max((int)(32*metrics.density),Math.min((int)(65*metrics.density),(int)(base*1.15f)));
                android.graphics.Rect q=new android.graphics.Rect();AccessibilityNodeInfo letter=find("q");letter.getBoundsInScreen(q);
                require(q.height()==expected,"Persisted Tall q height mismatch: expected="+expected+" actual="+q);
                requireCompleteTallWitness(initialWitness,expected);
                for(String name:new String[]{"Space","Switch letters and symbols","Emoji keyboard",".","Send in the current editor"}){
                    AccessibilityNodeInfo key=find(name);android.graphics.Rect r=new android.graphics.Rect();key.getBoundsInScreen(r);
                    require(key.getWindowId()==letter.getWindowId()&&r.height()==expected&&r.left>=0&&r.right<=metrics.widthPixels&&r.top>=0&&r.bottom<=metrics.heightPixels,"Tall bottom key not completely visible: "+name+" bounds="+r+" expectedheight="+expected);
                }
                report.putBoolean(requireAbsentInsets?"COLD-TALL-EXACT-ROW-AND-BOTTOM-BOUNDS":"FIRST-TALL-EXACT-ROW-AND-BOTTOM-BOUNDS",true);image("cold-tall-first-view.png");
                touchTap("q");waitText("q");touchTap("Space");waitText("q ");touchTap("Switch letters and symbols");touchTap("@");waitText("q @");touchTap("Switch letters and symbols");
                report.putBoolean(requireAbsentInsets?"COLD-TALL-REAL-FIRST-AND-BOTTOM-TAPS":"FIRST-TALL-REAL-FIRST-AND-BOTTOM-TAPS",true);
                int actions=ui(()->fixture.actions);touchTap("Send in the current editor");waitText("q @");require(ui(()->fixture.actions)==actions+1,"Synthetic action handler did not receive exactly one deliberate tap");
                report.putBoolean(requireAbsentInsets?"COLD-TALL-DELIBERATE-HOST-ACTION":"FIRST-TALL-DELIBERATE-HOST-ACTION",true);
                String finalWitness=presentationWitness();report.putString("presentation-after",finalWitness);require(new org.json.JSONObject(initialWitness).getJSONObject("creation").toString().equals(new org.json.JSONObject(finalWitness).getJSONObject("creation").toString()),"Presentation service/view changed during first-view probe");requireCompleteTallWitness(finalWitness,expected);
                report.putBoolean(requireAbsentInsets?"COLD-TALL-FIRST-CREATION-NULL-INSETS-PROVEN":"FIRST-TALL-FIRST-CREATION-RECORDED-INSETS-PROVEN",true);
                report.putInt("expected-row-px",expected);report.putString("actual-configuration",ui(()->fixture.getResources().getConfiguration().toString()));
                report.putString("result",requireAbsentInsets?"PASS actual first Tall/null-prior-insets geometry/taps; not startup reliability/physical9.2":"PASS actual first Tall/recorded-prior-insets geometry/taps; strict absent-insets case remains separate, no startup/physical9.2");outcome=Activity.RESULT_OK;return;
            }
            if(emojiOnly){
                if(emojiVariantsOnly){
                    field(fixture.normal,"LEFT RIGHT",5,5);touchTap("Show emoji");touchTap("Search offline emoji");spellEmoji("thumbs up");touchTap("Show offline emoji search results");
                    int reads=ui(()->fixture.cursorContextReads),actions=ui(()->fixture.actions);
                    AccessibilityNodeInfo base=find("Insert emoji thumbs up");require(base.isLongClickable(),"Tone family does not expose native long-click accessibility action");
                    longPressEmoji("Insert emoji thumbs up");find("Cancel emoji skin tone choices");waitText("LEFT RIGHT");
                    AccessibilityNodeInfo cancelled=find("Choose emoji thumbs up: medium-dark skin tone");
                    image("emoji-tone-choices.png");
                    touchTap("Cancel emoji skin tone choices");find("Insert emoji thumbs up");cancelled.performAction(AccessibilityNodeInfo.ACTION_CLICK);waitText("LEFT RIGHT");
                    report.putBoolean("EMOJI-TONE-LONGPRESS-AND-CANCEL-NO-WRITE",true);
                    longPressEmoji("Insert emoji thumbs up");find("Choose emoji thumbs up: medium-dark skin tone");
                    base.performAction(AccessibilityNodeInfo.ACTION_CLICK);waitText("LEFT RIGHT"); // Hidden base cannot write underneath chooser.
                    touchTap("Choose emoji thumbs up: medium-dark skin tone");waitText("LEFT 👍🏾RIGHT");
                    require(ui(()->fixture.actions)==actions,"Emoji choice triggered Send");require(ui(()->fixture.cursorContextReads)==reads,"Tone chooser read host text");
                    report.putBoolean("EMOJI-TONE-EXACT-ONCE-NO-READ-NO-SEND",true);
                    touchTap("Insert emoji thumbs up");waitText("LEFT 👍🏾👍RIGHT");report.putBoolean("EMOJI-BASE-TAP-UNCHANGED",true);
                    longPressEmoji("Insert emoji thumbs up");AccessibilityNodeInfo oldChoice=find("Choose emoji thumbs up: dark skin tone");
                    field(fixture.normal,"NEW FIELD");find("q");oldChoice.performAction(AccessibilityNodeInfo.ACTION_CLICK);waitText("NEW FIELD");
                    report.putBoolean("EMOJI-TONE-STALE-FIELD-REFUSED",true);
                    report.putString("result","PASS4 native emoji skin-tone choices only; no device/TalkBack/full9.2 acceptance");outcome=Activity.RESULT_OK;return;
                }
                if(emojiDeleteOnly){
                    field(fixture.normal,"one two three");touchTap("Show emoji");wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);waitText("one two ");
                    report.putBoolean("EMOJI-WORD-DELETE-COEXIST",true);report.putString("result","PASS emoji-page word-delete integration only");outcome=Activity.RESULT_OK;return;
                }
                if(emojiVisualOnly){
                    field(fixture.normal,"Emoji browser preview");touchTap("Show emoji");AccessibilityNodeInfo first=find("Insert emoji grinning face");
                    android.graphics.Rect bounds=new android.graphics.Rect();first.getBoundsInScreen(bounds);require(!bounds.isEmpty()&&first.isVisibleToUser(),"Emoji tile not visible");
                    image("emoji-current-config.png");report.putBoolean("EMOJI-VISUAL-NATIVE-CAPTURE",true);
                    report.putString("actual-configuration",ui(()->fixture.getResources().getConfiguration().toString()));report.putString("tile-bounds",bounds.toShortString());
                    report.putString("result","PASS visual capture preconditions only; not emoji functional/visual9.2/device acceptance");outcome=Activity.RESULT_OK;return;
                }
                clipboard=fixture.getSystemService(android.content.ClipboardManager.class);savedClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
                field(fixture.normal,"LEFT RIGHT",5,5);touchTap("Show emoji");find("Search offline emoji");touchTap("Search offline emoji");
                int reads=ui(()->fixture.cursorContextReads);AccessibilityNodeInfo capturedKey=find("c");spellEmoji("coffee");require("LEFT RIGHT".equals(text()),"Emoji query leaked into host");
                touchTap("Show offline emoji search results");find("Insert emoji hot beverage");require(reads==ui(()->fixture.cursorContextReads),"Emoji search read host context");
                capturedKey.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(150);waitText("LEFT RIGHT");
                touchTap("Insert emoji hot beverage");waitText("LEFT ☕RIGHT");report.putBoolean("EMOJI-SEARCH",true);report.putBoolean("EMOJI-QUERY-NO-HOST-READ-OR-WRITE",true);report.putBoolean("EMOJI-STALE-QUERY-KEY-REFUSED",true);
                touchTap("Return from emoji to letters");field(fixture.normal,"SAFE");touchTap("Show emoji");touchTap("Search offline emoji");
                hardware(android.view.KeyEvent.KEYCODE_J);hardware(android.view.KeyEvent.KEYCODE_A);hardware(android.view.KeyEvent.KEYCODE_P);hardware(android.view.KeyEvent.KEYCODE_X);hardware(android.view.KeyEvent.KEYCODE_DEL);hardware(android.view.KeyEvent.KEYCODE_A);hardware(android.view.KeyEvent.KEYCODE_N);hardware(android.view.KeyEvent.KEYCODE_ENTER);
                waitText("SAFE");touchTap("Insert emoji flag: Japan");waitText("SAFE🇯🇵");report.putBoolean("EMOJI-HARDWARE-QUERY-ENTER-NO-SEND",true);
                touchTap("Return from emoji to letters");field(fixture.normal,"SAFE");touchTap("Show emoji");touchTap("Search offline emoji");spellEmoji("coffee");touchTap("Show offline emoji search results");AccessibilityNodeInfo staleRow=find("Insert emoji hot beverage");
                touchTap("Return from emoji to letters");field(fixture.password,"SAFE");staleRow.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(150);require("SAFE".equals(ui(()->fixture.password.getText().toString()))&&"SAFE".equals(text()),"Stale emoji row followed editor");report.putBoolean("EMOJI-STALE-ROW-NEW-EDITOR-REFUSED",true);
                int privateReads=ui(()->fixture.passwordCursorContextReads);touchTap("Show emoji");touchTap("Search offline emoji");spellEmoji("coffee");touchTap("Show offline emoji search results");touchTap("Insert emoji hot beverage");long privateDeadline=SystemClock.uptimeMillis()+5000;
                while(!"SAFE☕".equals(ui(()->fixture.password.getText().toString()))&&SystemClock.uptimeMillis()<privateDeadline)SystemClock.sleep(40);
                require("SAFE☕".equals(ui(()->fixture.password.getText().toString())),"Explicit private emoji output mismatch; synthetic length="+ui(()->fixture.password.length()));
                require(privateReads==ui(()->fixture.passwordCursorContextReads),"Explicit private emoji host-read counter changed "+privateReads+" -> "+ui(()->fixture.passwordCursorContextReads));report.putBoolean("EMOJI-PRIVATE-MANUAL-NO-READ",true);
                touchTap("Return from emoji to letters");field(fixture.normal,"SAFE");touchTap("Show emoji");touchTap("Search offline emoji");spellEmoji("unlikelyzz");touchTap("Show offline emoji search results");require(!visible("Insert emoji hot beverage"),"No-match emoji search broadened to all");waitText("SAFE");report.putBoolean("EMOJI-NO-MATCH-NO-FALLBACK",true);
                touchTap("Return from emoji to letters");field(fixture.normal,"SAFE");touchTap("Show emoji");touchTap("Search offline emoji");spellEmoji("red heart");touchTap("Show offline emoji search results");touchTap("Insert emoji red heart");waitText("SAFE❤️");report.putBoolean("EMOJI-VARIATION-SELECTOR-PRESERVED",true);
                touchTap("Return from emoji to letters");field(fixture.normal,"SAFE");touchTap("Show emoji");touchTap("Search offline emoji");spellEmoji("family man woman girl boy");touchTap("Show offline emoji search results");touchTap("Insert emoji family: man, woman, girl, boy");waitText("SAFE👨‍👩‍👧‍👦");report.putBoolean("EMOJI-FAMILY-ZWJ-PRESERVED",true);
                touchTap("Return from emoji to letters");field(fixture.normal,"SAFE");touchTap("Show emoji");touchTap("Show emoji category Food & Drink");AccessibilityNodeInfo firstTile=find("Insert emoji grapes");require(find("Offline emoji results").performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),"Continuous emoji grid did not scroll");SystemClock.sleep(200);require(!visible("Insert emoji grapes"),"Emoji grid did not change actual visible content");require(find("Offline emoji results").performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),"Emoji grid did not scroll back");SystemClock.sleep(200);find("Insert emoji grapes");touchTap("Show emoji category Travel & Places");firstTile.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(100);waitText("SAFE");touchTap("Show emoji category Food & Drink");find("Insert emoji grapes");report.putBoolean("EMOJI-CATEGORY-SCROLL-STALE-MODE-ROW-REFUSED",true);
                emojiCategory("Show session recent emoji");touchTap("Enable emoji recents for this field only");touchTap("Show emoji category Food & Drink");touchTap("Insert emoji grapes");waitText("SAFE🍇");emojiCategory("Show session recent emoji");find("Insert emoji grapes");report.putBoolean("EMOJI-EXPLICIT-FIELD-RECENTS",true);
                touchTap("Return from emoji to letters");field(fixture.password,"SAFE");touchTap("Show emoji");emojiCategory("Show session recent emoji");require(!visible("Insert emoji grapes")&&!visible("Enable emoji recents for this field only"),"Recent picks leaked into private/new field");report.putBoolean("EMOJI-RECENTS-NEW-PRIVATE-FIELD-CLEARED",true);
                touchTap("Return from emoji to letters");field(fixture.normal,"SAFE");touchTap("Show emoji");touchTap("Search offline emoji");spellEmoji("coffee");AccessibilityNodeInfo oldQueryKey=find("c");ui(()->{fixture.normal.setSelection(0);return null;});SystemClock.sleep(200);oldQueryKey.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(100);waitText("SAFE");require(!visible("Show offline emoji search results"),"Caret change retained emoji query target");report.putBoolean("EMOJI-CARET-CHANGE-CANCELS-QUERY",true);
                touchTap("Show emoji");image("emoji-current-config.png");
                require(ui(()->fixture.actions)==0&&sameClipContent(savedClipboard,ui(()->clipboard.getPrimaryClip())),"Emoji submitted or changed clipboard");report.putBoolean("EMOJI-NO-SEND-CLIPBOARD",true);clipboardCaptured=false;
                report.putString("result","PASS named owned native emoji search controls only; no full emoji/Gboard/phone/font/quality qualification");outcome=Activity.RESULT_OK;return;
            }
            if(wordDeleteOnly){
                if(deleteCrossingOnly){
                    field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f,1.1f,0f},android.view.MotionEvent.ACTION_UP,null);waitText("one two three");cursorAt(fixture.normal,13);
                    report.putBoolean("DELETE-CROSS-ORIGIN-NO-DELETE",true);report.putString("result","PASS crossing-origin discriminator only");outcome=Activity.RESULT_OK;return;
                }
                clipboard=fixture.getSystemService(android.content.ClipboardManager.class);savedClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,()->{
                    require("one two three".equals(text()),"Preview deleted before release");
                    long previewDeadline=SystemClock.uptimeMillis()+1000;
                    while((ui(()->fixture.normal.getSelectionStart())!=8||ui(()->fixture.normal.getSelectionEnd())!=13)&&SystemClock.uptimeMillis()<previewDeadline)SystemClock.sleep(15);
                    require(ui(()->fixture.normal.getSelectionStart())==8&&ui(()->fixture.normal.getSelectionEnd())==13,"Word selection not observed before release; actual="+ui(()->fixture.normal.getSelectionStart())+":"+ui(()->fixture.normal.getSelectionEnd())+",selections="+ui(()->fixture.wordSelectRequests));
                });waitText("one two ");report.putBoolean("GESTURE-DELETE",true);
                touchTap("Undo edit; cancel pending Lip dictation");waitText("one two three");report.putBoolean("DELETE-NATIVE-HOST-UNDO",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f,-2.1f},android.view.MotionEvent.ACTION_UP,null);waitText("one ");report.putBoolean("DELETE-TWO-WORDS",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f,-2.1f,-1.1f},android.view.MotionEvent.ACTION_UP,null);waitText("one two ");report.putBoolean("DELETE-REVERSE-REDUCES-SELECTION",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f,0f},android.view.MotionEvent.ACTION_UP,null);waitText("one two three");cursorAt(fixture.normal,13);report.putBoolean("DELETE-REVERSE-ZERO-NO-DELETE",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f,1.1f,0f},android.view.MotionEvent.ACTION_UP,null);waitText("one two three");cursorAt(fixture.normal,13);report.putBoolean("DELETE-CROSS-ORIGIN-NO-DELETE",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_CANCEL,null);waitText("one two three");report.putBoolean("DELETE-CANCEL-NO-TEXT-WRITE",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null,-4f);waitText("one two three");report.putBoolean("DELETE-INVALID-RELEASE-NO-DELETE",true);
                for(String original:new String[]{"one 👨‍👩‍👧‍👦","one é","one don't!  "}){
                    field(fixture.normal,original);wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);waitText("one ");
                }report.putBoolean("DELETE-UNICODE-APOSTROPHE-PUNCTUATION",true);
                String longToken="x".repeat(80);field(fixture.normal,longToken);wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);waitText(longToken);report.putBoolean("DELETE-TRUNCATED-TOKEN-REFUSED",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,()->ui(()->{fixture.normal.setSelection(0);return null;}));waitText("one two three");report.putBoolean("DELETE-EXTERNAL-CARET-CANCEL",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,()->ui(()->{fixture.normal.getText().replace(0,3,"ONE");return null;}));waitText("ONE two three");report.putBoolean("DELETE-EXTERNAL-CONTENT-CANCEL",true);
                field(fixture.normal,"one two three");wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,()->ui(()->{fixture.password.setText("SAFE");fixture.password.requestFocus();fixture.password.setSelection(4);fixture.getSystemService(InputMethodManager.class).showSoftInput(fixture.password,InputMethodManager.SHOW_IMPLICIT);return null;}));require("one two three".equals(text())&&"SAFE".equals(ui(()->fixture.password.getText().toString())),"Delete followed changed editor");report.putBoolean("DELETE-EDITOR-CHANGE-CANCEL",true);
                field(fixture.password,"SAFE");int reads=ui(()->fixture.passwordCursorContextReads);wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);require("SAFE".equals(ui(()->fixture.password.getText().toString()))&&reads==ui(()->fixture.passwordCursorContextReads),"Delete private gesture read/wrote text");report.putBoolean("DELETE-PRIVATE-REFUSED-NO-READ",true);
                field(fixture.incognito,"one two");wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);require("one two".equals(ui(()->fixture.incognito.getText().toString())),"Delete incognito gesture read/wrote text");report.putBoolean("DELETE-INCOGNITO-REFUSED",true);
                field(fixture.normal,"one two three",0,3);int beforeReads=ui(()->fixture.cursorContextReads);wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);waitText("one two three");require(ui(()->fixture.cursorContextReads)==beforeReads,"Gesture read an existing selection");report.putBoolean("DELETE-EXISTING-SELECTION-REFUSED-NO-READ",true);
                field(fixture.normal,"one two three");keyMultitouch("Delete previous character; hold to repeat");waitText("one two three");report.putBoolean("DELETE-MULTITOUCH-NO-DELETE",true);
                for(int fault=0;fault<4;fault++){
                    field(fixture.normal,"one two three");final int f=fault;
                    int beforeSelect=ui(()->fixture.wordSelectRequests),beforeCommit=ui(()->fixture.wordDeleteCommits);
                    ui(()->{fixture.refuseWordSelection=f==0;fixture.delayWordSelection=f==1;fixture.refuseWordCommit=f==2;fixture.ambiguousWordCommit=f==3;return null;});
                    try{wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);SystemClock.sleep(500);
                        waitText(f==3?"one two ":"one two three");require(ui(()->fixture.wordSelectRequests)==beforeSelect+1,"Selection request retried after fault");require(ui(()->fixture.wordDeleteCommits)==beforeCommit+(f>=2?1:0),"Ambiguous/refused/timedout commit retried or dispatched");
                    }finally{ui(()->{fixture.refuseWordSelection=false;fixture.delayWordSelection=false;fixture.refuseWordCommit=false;fixture.ambiguousWordCommit=false;return null;});}
                }report.putBoolean("DELETE-REFUSED-DELAYED-AMBIGUOUS-NO-RETRY",true);
                field(fixture.normal,"one two three");int selectsBeforeSlow=ui(()->fixture.wordSelectRequests),commitsBeforeSlow=ui(()->fixture.wordDeleteCommits);
                ui(()->{fixture.slowWordReadAfter=fixture.wordSelectRequests+1;return null;});
                try{wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);SystemClock.sleep(400);waitText("one two three");require(ui(()->fixture.wordSelectRequests)==selectsBeforeSlow+1&&ui(()->fixture.wordDeleteCommits)==commitsBeforeSlow,"Slow actual host ACK was retried/deleted");}
                finally{ui(()->{fixture.slowWordReadAfter=Integer.MAX_VALUE;return null;});}
                report.putBoolean("DELETE-SLOW-ACTUAL-HOST-ACK-NO-DELETE",true);
                field(fixture.normal,"one two three");int beforeInitialSelect=ui(()->fixture.wordSelectRequests),beforeInitialCommit=ui(()->fixture.wordDeleteCommits);
                ui(()->{fixture.slowInitialWordRead=true;return null;});
                try{wordDeleteDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);SystemClock.sleep(400);waitText("one two three");require(ui(()->fixture.wordSelectRequests)==beforeInitialSelect&&ui(()->fixture.wordDeleteCommits)==beforeInitialCommit,"Slow initial capture selected/deleted text");}
                finally{ui(()->{fixture.slowInitialWordRead=false;return null;});}
                report.putBoolean("DELETE-SLOW-INITIAL-CAPTURE-NO-SELECTION",true);
                field(fixture.normal,"abc");touchTap("Delete previous character; hold to repeat");waitText("ab");report.putBoolean("DELETE-TAP-REMAINS-GRAPHEME",true);
                field(fixture.normal,"abcdefghijklmnopqrstuvwxyz");deleteLongHold();String afterHold=text();require(afterHold.length()<25&&!afterHold.isEmpty(),"Stationary Delete hold did not repeat");SystemClock.sleep(250);require(afterHold.equals(text()),"Repeat Delete continued after release");report.putBoolean("DELETE-STATIONARY-HOLD-REPEAT-THEN-STOP",true);
                require(ui(()->fixture.actions)==0,"Delete gesture performed editor Send");require(sameClipContent(savedClipboard,ui(()->clipboard.getPrimaryClip())),"Delete gesture altered clipboard");report.putBoolean("DELETE-NO-SEND-CLIPBOARD",true);
                report.putString("result","PASS owned native word-delete controls only; no phone/remote editor/reliability/quality qualification");outcome=Activity.RESULT_OK;return;
            }
            if(cursorOnly){
                clipboard=fixture.getSystemService(android.content.ClipboardManager.class);savedClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
                field(fixture.normal,"abcd");final int reads=ui(()->fixture.cursorContextReads);
                cursorDrag(new float[]{-1.1f,-2.1f},android.view.MotionEvent.ACTION_UP,null);waitText("abcd");cursorAt(fixture.normal,2);require(ui(()->fixture.cursorContextReads)==reads,"Space drag read editor context");report.putBoolean("GESTURE-CURSOR-SPACEBAR",true);
                cursorDrag(new float[]{1.1f,2.1f},android.view.MotionEvent.ACTION_UP,null);waitText("abcd");cursorAt(fixture.normal,4);
                field(fixture.normal,"abcd",2,2);cursorDrag(new float[]{-1.1f,-2.1f,1.1f},android.view.MotionEvent.ACTION_UP,null);waitText("abcd");cursorAt(fixture.normal,3);report.putBoolean("CURSOR-REVERSE-LEFT-RIGHT",true);
                field(fixture.normal,"abcd",1,3);cursorDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);waitText("abcd");cursorAt(fixture.normal,1);
                field(fixture.normal,"abcd",1,3);cursorDrag(new float[]{1.1f},android.view.MotionEvent.ACTION_UP,null);waitText("abcd");cursorAt(fixture.normal,3);report.putBoolean("CURSOR-HOST-SELECTION-COLLAPSE",true);
                field(fixture.normal,"abcd");cursorVerticalCancel();waitText("abcd");cursorAt(fixture.normal,4);report.putBoolean("CURSOR-VERTICAL-CANCEL-NO-SPACE",true);
                field(fixture.normal,"abcd");cursorDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_CANCEL,null);waitText("abcd");cursorAt(fixture.normal,3);touchTap("Space");waitText("abc d");report.putBoolean("CURSOR-CANCEL-NO-SPACE-THEN-TAP",true);
                String family="A👨‍👩‍👧‍👦B";field(fixture.normal,family);cursorDrag(new float[]{-1.1f,-2.1f},android.view.MotionEvent.ACTION_UP,null);require(family.equals(text()),"Family navigation changed text");cursorAt(fixture.normal,1);report.putBoolean("CURSOR-HOST-FAMILY-GRAPHEME",true);
                String combining="AéB";field(fixture.normal,combining);cursorCapture=true;cursorDrag(new float[]{-1.1f,-2.1f},android.view.MotionEvent.ACTION_UP,null);require(combining.equals(text()),"Combining navigation changed text");cursorAt(fixture.normal,1);report.putBoolean("CURSOR-HOST-COMBINING-GRAPHEME",true);cursorCapture=false;
                field(fixture.normal,"x".repeat(72));for(int i=0;i<6;i++)cursorDrag(new float[]{-4.1f,-8.1f,-12.1f},android.view.MotionEvent.ACTION_UP,null);cursorAt(fixture.normal,0);require(text().equals("x".repeat(72)),"Long navigation changed text");report.putBoolean("CURSOR-REPEATED-SLIDES-BEYOND-CONTEXT-WINDOW",true);
                field(fixture.normal,"abcd");cursorDrag(new float[]{-1.1f,-2.1f},android.view.MotionEvent.ACTION_UP,()->ui(()->{fixture.password.setText("SAFE");fixture.password.requestFocus();fixture.password.setSelection(4);fixture.getSystemService(InputMethodManager.class).showSoftInput(fixture.password,InputMethodManager.SHOW_IMPLICIT);return null;}));
                require("SAFE".equals(ui(()->fixture.password.getText().toString()))&&"abcd".equals(text()),"Old held space followed new editor");cursorAt(fixture.password,4);report.putBoolean("CURSOR-CONTACT-NEW-EDITOR-CANCELED",true);
                field(fixture.normal,"abcd",2,2);cursorMultitouch();waitText("abcd");cursorAt(fixture.normal,2);report.putBoolean("CURSOR-MULTITOUCH-NO-SPACE",true);
                field(fixture.normal,"abcd");cursorLongHold();waitText("abcd");report.putBoolean("CURSOR-STATIONARY-LONGHOLD-PICKER-NO-DEFAULT-CHANGE",true);
                field(fixture.password,"SAFE");int passwordReads=ui(()->fixture.passwordCursorContextReads);cursorDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);cursorAt(fixture.password,3);require("SAFE".equals(ui(()->fixture.password.getText().toString()))&&ui(()->fixture.passwordCursorContextReads)==passwordReads,"Password cursor read/replaced text");report.putBoolean("CURSOR-PRIVATE-MANUAL-NO-CONTEXT-READ",true);
                field(fixture.incognito,"abcd");cursorDrag(new float[]{-1.1f},android.view.MotionEvent.ACTION_UP,null);cursorAt(fixture.incognito,3);require("abcd".equals(ui(()->fixture.incognito.getText().toString())),"Incognito cursor replaced text");report.putBoolean("CURSOR-INCOGNITO-MANUAL",true);
                field(fixture.normal,"abcd",2,2);final int beforeActions=ui(()->fixture.cursorContextReads);
                AccessibilityNodeInfo space=find("Space");require(space.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()),"Accessible cursor left refused");cursorAt(fixture.normal,1);
                require(find("Space").performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()),"Accessible cursor right refused");cursorAt(fixture.normal,2);
                require(ui(()->fixture.cursorContextReads)==beforeActions,"Accessible arrows read editor context");report.putBoolean("CURSOR-ACCESSIBLE-MANUAL-ARROWS",true);
                require(ui(()->fixture.actions)==0&&sameClipContent(savedClipboard,ui(()->clipboard.getPrimaryClip())),"Cursor submitted or changed clipboard");clipboardCaptured=false;report.putBoolean("CURSOR-NO-SEND-CLIPBOARD",true);report.putInt("fixture-spelling-popup-dismissals",cursorPopupDismissals);
                report.putString("result","PASS owned native cursor controls only; host arrow behavior not phone/TalkBack user/performance/full parity qualification");finish(Activity.RESULT_OK,report);return;
            }
            if(nextWordsOnly){
                clipboard=fixture.getSystemService(android.content.ClipboardManager.class);savedClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
                field(fixture.normal,"LEFT  RIGHT",5,5);spell("i");touchTap("Space");spell("want");touchTap("Space");waitText("LEFT i want  RIGHT");
                AccessibilityNodeInfo used=find("Insert next word to");touchTap("Insert next word to");waitText("LEFT i want to RIGHT");used.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);waitText("LEFT i want to RIGHT");report.putBoolean("NEXT-APPEND-MIDDLE-NO-REPLACE-SINGLE-USE",true);
                if(nextWordProbe){require(sameClipContent(savedClipboard,ui(()->clipboard.getPrimaryClip()))&&ui(()->fixture.actions)==0,"Probe changed clipboard/sent");clipboardCaptured=false;report.putString("result","PASS first native append probe only; not full contextual qualification");finish(Activity.RESULT_OK,report);return;}
                field(fixture.normal,"");spell("thank");touchTap("Space");spell("you");touchTap("Space");waitText("thank you ");
                find("Insert next word for");image("nextword-predictions.png");require(!visible("Insert next word to"),"Context result from old field leaked");touchTap("Insert next word for");waitText("thank you for ");
                report.putBoolean("PREDICT-NEXT-WORD",true);
                field(fixture.normal,"");spell("i");touchTap("Space");spell("want");touchTap("Space");AccessibilityNodeInfo stale=find("Insert next word to");
                ui(()->{fixture.normal.setSelection(0);return null;});SystemClock.sleep(200);stale.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);waitText("i want ");
                require(ui(()->fixture.normal.getSelectionStart())==0,"Stale prediction moved caret");report.putBoolean("NEXT-STALE-CARET-REFUSED",true);
                field(fixture.normal,"");spell("i");touchTap("Space");spell("want");touchTap("Space");AccessibilityNodeInfo aba=find("Insert next word to");
                ui(()->{fixture.normal.setSelection(0);fixture.getSystemService(InputMethodManager.class).updateSelection(fixture.normal,0,0,-1,-1);fixture.normal.setSelection(7);fixture.getSystemService(InputMethodManager.class).updateSelection(fixture.normal,7,7,-1,-1);return null;});SystemClock.sleep(200);aba.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);waitText("i want ");report.putBoolean("NEXT-CARET-ABA-REFUSED",true);
                field(fixture.normal,"");spell("i");touchTap("Space");spell("want");touchTap("Space");stale=find("Insert next word to");touchTap("x");waitText("i want x");stale.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);waitText("i want x");report.putBoolean("NEXT-CONTINUED-TYPING-REFUSED",true);
                field(fixture.normal,"");spell("i");touchTap("Space");spell("want");touchTap(".");touchTap("Space");SystemClock.sleep(250);waitText("i want. ");require(!visible("Insert next word to"),"Sentence punctuation retained context claim");report.putBoolean("NEXT-SENTENCE-BOUNDARY",true);
                field(fixture.normal,"");glide("hello");waitText("hello ");suggestions();touchTap("Show next-word predictions");touchTap("Insert next word to");waitText("hello to ");report.putBoolean("NEXT-SWIPE-ALTERNATIVES-AND-APPEND",true);
                field(fixture.normal,"");spell("teh");touchTap("Space");waitText("the ");find("Show previous word alternatives");touchTap("Show previous word alternatives");
                touchTap("Choose typing suggestion teh");waitText("teh ");report.putBoolean("NEXT-PREVIOUS-CORRECTION-ACCESS",true);
                field(fixture.normal,"");spell("teh");touchTap("Space");waitText("the ");find("Show previous word alternatives");touchTap("Delete previous character; hold to repeat");waitText("teh ");report.putBoolean("NEXT-BACKSPACE-UNDO-PRESERVED",true);
                field(fixture.normal,"");spell("i");touchTap("Space");spell("want");ui(()->{fixture.foreignCompositionAfterSpace=true;return null;});touchTap("Space");SystemClock.sleep(400);
                require(!visible("Insert next word to")&&ui(()->fixture.foreignStart())==1&&ui(()->fixture.foreignEnd())==2,"Next words touched foreign composition");ui(()->{fixture.clearForeignComposition();return null;});report.putBoolean("NEXT-FOREIGN-COMPOSITION-REFUSED",true);
                find("Insert next word to");waitText("i want ");report.putBoolean("NEXT-SAME-CARET-COMPOSITION-REMOVAL-NEW-REQUEST",true);
                for(EditText protectedField:new EditText[]{fixture.password,fixture.incognito}){field(protectedField,"");spell("i");touchTap("Space");spell("want");touchTap("Space");SystemClock.sleep(200);require(!visible("Insert next word to"),"Private field predicted");}
                require(ui(()->fixture.actions)==0&&sameClipContent(savedClipboard,ui(()->clipboard.getPrimaryClip())),"Prediction sent or changed clipboard");clipboardCaptured=false;
                report.putBoolean("NEXT-PRIVATE-NO-SEND-CLIPBOARD",true);report.putString("result","PASS bounded contextual append/caret/privacy owned-emulator controls; no corpus/device/browser/performance/product qualification");finish(Activity.RESULT_OK,report);return;
            }
            if(punctuationOnly){
                clipboard=fixture.getSystemService(android.content.ClipboardManager.class);savedClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
                field(fixture.normal,"LEFT  RIGHT",5,5);spell("teh");touchTap(".");waitText("LEFT the. RIGHT");
                touchTap("Delete previous character; hold to repeat");waitText("LEFT teh. RIGHT");
                touchTap("Delete previous character; hold to repeat");waitText("LEFT teh RIGHT");
                touchTap(".");SystemClock.sleep(500);waitText("LEFT teh. RIGHT");
                report.putBoolean("PUNCT-PERIOD-CONTEXT-UNDO-REJECTION",true);
                touchTap("Delete previous character; hold to repeat");waitText("LEFT teh RIGHT");touchTap("n");touchTap(".");waitText("LEFT then. RIGHT");
                report.putBoolean("PUNCT-LETTER-EDIT-REARMS",true);
                for(String mark:new String[]{",","?","!",":",";"}){
                    field(fixture.normal,"");spell("teh");touchTap("Switch letters and symbols");touchTap(mark);waitText("the"+mark);
                    touchTap("Delete previous character; hold to repeat");waitText("teh"+mark);
                    touchTap("Switch letters and symbols");report.putBoolean("PUNCT-BOUNDARY-"+Integer.toHexString(mark.charAt(0)),true);
                }
                field(fixture.normal,"");spell("don");touchTap("Switch letters and symbols");touchTap("'");touchTap("Switch letters and symbols");touchTap("t");
                find("Choose typing suggestion don't");waitText("don't");report.putBoolean("PUNCT-APOSTROPHE-MODE-CONTINUATION",true);
                field(fixture.normal,"recieve");touchTap("Switch letters and symbols");touchTap(",");waitText("recieve,");
                require(!visible("Choose typing suggestion receive,"),"Mode switch acquired arbitrary host word");touchTap("Switch letters and symbols");report.putBoolean("PUNCT-MODE-NO-FOREIGN-OWNERSHIP",true);
                field(fixture.normal,"");spell("recieve");touchTap(".");touchTap("Choose typing suggestion receive.");waitText("receive.");touchTap(".");waitText("receive..");
                require(!visible("Choose typing suggestion recieve."),"Consecutive punctuation retained word claim");report.putBoolean("PUNCT-CONSECUTIVE-ENDS-OWNERSHIP",true);
                field(fixture.normal,"");ui(()->{fixture.delayComposingRegion=true;return null;});spell("teh");final int punctWrites=ui(()->fixture.composingWrites);final long punctUp=touchTapAt(".");
                long punctDeadline=SystemClock.uptimeMillis()+4000;while(ui(()->fixture.delayedRegion)==null&&SystemClock.uptimeMillis()<punctDeadline)SystemClock.sleep(40);
                require(ui(()->fixture.delayedRegion)!=null,"Punctuation never requested correction composition");
                ui(()->{fixture.normal.setSelection(0);fixture.getSystemService(InputMethodManager.class).updateSelection(fixture.normal,0,0,-1,-1);return null;});SystemClock.sleep(200);
                ui(()->{long elapsed=SystemClock.uptimeMillis()-punctUp;require(elapsed<750,"Punctuation ACK witness missed sub-timeout bound: "+elapsed);fixture.delayedRegion.run();fixture.delayedRegion=null;fixture.delayComposingRegion=false;return null;});SystemClock.sleep(200);waitText("teh.");
                require(ui(()->fixture.composingWrites)==punctWrites,"Old punctuation correction dispatched a text replacement");
                require(ui(()->fixture.normal.getSelectionStart())==0,"Stale punctuation correction moved caret");report.putBoolean("PUNCT-LATE-AUTO-ACK-CARET-REFUSED",true);
                ui(()->{fixture.normal.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT);return null;});
                field(fixture.normal,"LEFT  RIGHT",5,5);spell("recieve");touchTap(".");AccessibilityNodeInfo old=find("Choose typing suggestion receive.");
                ui(()->{fixture.normal.setSelection(0);return null;});SystemClock.sleep(150);old.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);waitText("LEFT recieve. RIGHT");
                report.putBoolean("PUNCT-STALE-CARET-REFUSED",true);
                ui(()->{fixture.normal.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT);return null;});
                field(fixture.normal,"");spell("teh");touchTap(".");SystemClock.sleep(400);waitText("teh.");touchTap("Choose typing suggestion the.");waitText("the.");report.putBoolean("PUNCT-AUTO-OFF-MANUAL",true);
                field(fixture.password,"");spell("teh");touchTap(".");SystemClock.sleep(200);require("teh.".equals(ui(()->fixture.password.getText().toString()))&&!visible("Choose typing suggestion the."),"Password punctuation corrected");
                ui(()->{fixture.normal.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);return null;});field(fixture.normal,"");spell("teh");touchTap(".");SystemClock.sleep(200);waitText("teh.");require(!visible("Choose typing suggestion the."),"Email punctuation corrected");
                require(ui(()->fixture.actions)==0,"Punctuation submitted host");android.content.ClipData now=ui(()->clipboard.getPrimaryClip());
                require(sameClipContent(savedClipboard,now),"Punctuation changed clipboard");report.putBoolean("PUNCT-PRIVATE-NO-SEND-CLIPBOARD",true);
                report.putInt("fixture-popup-dismissals",cursorPopupDismissals);clipboardCaptured=false;report.putString("result","PASS owned-emulator punctuation controls only; no corpus/browser/device/product qualification");finish(Activity.RESULT_OK,report);return;
            }
            if(typingUndoOnly){
                field(fixture.normal,"");spell("teh");touchTap("Space");waitText("the ");
                touchTap("Delete previous character; hold to repeat");waitText("teh ");
                touchTap("Delete previous character; hold to repeat");waitText("teh");
                touchTap("Space");SystemClock.sleep(600);waitText("teh ");
                require(ui(()->fixture.actions)==0,"Undo/edit flow submitted host");
                report.putBoolean("TYPING-UNDO-SPACE-EDIT-KEEPS-REJECTED-SPELLING",true);
                touchTap("Delete previous character; hold to repeat");waitText("teh");touchTap("n");waitText("tehn");touchTap("Space");waitText("then ");
                report.putBoolean("TYPING-WORD-LETTER-EDIT-REARMS-AUTO",true);
                report.putString("result","PASS rejected-spelling delimiter edit control only; not learning/quality/device acceptance");finish(Activity.RESULT_OK,report);return;
            }
            if(typingOnly){
                clipboard=fixture.getSystemService(android.content.ClipboardManager.class);savedClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
                ui(()->{clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Synthetic typing fixture","TYPING_SEED"));return null;});
                field(fixture.normal,"LEFT  RIGHT",5,5);spell("recieve");waitText("LEFT recieve RIGHT");
                touchTap("Choose typing suggestion receive");waitText("LEFT receive RIGHT");
                touchTap("Choose typing suggestion recieve");waitText("LEFT recieve RIGHT");
                report.putBoolean("TYPING-OWNED-CHOICE-AND-ORIGINAL",true);
                AccessibilityNodeInfo stale=find("Choose typing suggestion receive");
                ui(()->{fixture.normal.setSelection(0);return null;});SystemClock.sleep(150);stale.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(150);
                require("LEFT recieve RIGHT".equals(text()),"Stale typing choice changed moved caret");report.putBoolean("TYPING-CARET-STALE-REFUSED",true);
                field(fixture.normal,"");spell("recieve");AccessibilityNodeInfo aba=find("Choose typing suggestion receive");
                ui(()->{fixture.normal.setSelection(0);fixture.getSystemService(InputMethodManager.class).updateSelection(fixture.normal,0,0,-1,-1);fixture.normal.setSelection(7);fixture.getSystemService(InputMethodManager.class).updateSelection(fixture.normal,7,7,-1,-1);return null;});SystemClock.sleep(200);aba.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(150);
                require("recieve".equals(text()),"Observed caret ABA revived typing choice");report.putBoolean("TYPING-CARET-ABA-REFUSED",true);
                field(fixture.normal,"");spell("recievel");touchTap("Delete previous character; hold to repeat");waitText("recieve");
                touchTap("Choose typing suggestion receive");waitText("receive");report.putBoolean("TYPING-BACKSPACE-REFRESH-OWNED-PREFIX",true);
                field(fixture.normal,"recievel");touchTap("Delete previous character; hold to repeat");waitText("recieve");SystemClock.sleep(250);
                require(!visible("Choose typing suggestion receive"),"Delete inferred ownership of arbitrary host word");report.putBoolean("TYPING-DELETE-FOREIGN-WORD-NO-OWNERSHIP",true);
                field(fixture.normal,"");spell("a");touchTap("Delete previous character; hold to repeat");waitText("");
                require(!visible("Choose typing suggestion a"),"Deleting last owned letter retained candidate");report.putBoolean("TYPING-DELETE-LAST-OWNED-LETTER-ENDS",true);
                ui(()->{fixture.normal.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT|android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);return null;});
                field(fixture.normal,"");spell("teh");touchTap("Space");waitText("the ");report.putBoolean("TYPING-ORDINARY-WEB-EDIT-CORRECTION",true);
                ui(()->{fixture.normal.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT);return null;});
                field(fixture.normal,"");spell("teh");touchTap("Space");SystemClock.sleep(250);waitText("teh ");
                touchTap("Choose typing suggestion the");waitText("the ");report.putBoolean("TYPING-WEB-AUTO-OFF-MANUAL-CHOICE",true);
                ui(()->{fixture.normal.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);return null;});
                field(fixture.normal,"");spell("teh");touchTap("Space");SystemClock.sleep(200);require("teh ".equals(text())&&!visible("Choose typing suggestion the"),"Web NO_SUGGESTIONS was ignored");
                report.putBoolean("TYPING-WEB-NO-SUGGESTIONS-REFUSED",true);
                ui(()->{fixture.normal.setInputType(android.text.InputType.TYPE_CLASS_TEXT);return null;});
                field(fixture.normal,"");spell("teh");touchTap("Space");waitText("the ");
                touchTap("Delete previous character; hold to repeat");waitText("teh ");
                report.putBoolean("TYPING-AUTOCORRECT-AND-BACKSPACE-UNDO",true);
                field(fixture.normal,"");touchTap("Shift; double-tap to lock capitals");touchTap("R");spell("ecieve");touchTap("Choose typing suggestion Receive");waitText("Receive");
                report.putBoolean("TYPING-SHIFT-WORD-CONTINUES",true);image("typing-suggestions.png");
                field(fixture.normal,"");ui(()->{fixture.delayComposingRegion=true;return null;});spell("teh");touchTap("Space");
                long deadline=SystemClock.uptimeMillis()+4000;while(ui(()->fixture.delayedRegion)==null&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(40);
                require(ui(()->fixture.delayedRegion)!=null,"Automatic correction never requested real host composition");
                touchTap("x");waitText("teh x");ui(()->{fixture.delayedRegion.run();fixture.delayedRegion=null;fixture.delayComposingRegion=false;return null;});SystemClock.sleep(200);
                require("teh x".equals(text()),"Late auto acknowledgement replaced continued typing");touchTap("y");waitText("teh xy");report.putBoolean("TYPING-LATE-ACK-NEW-TYPE-REFUSED",true);
                field(fixture.normal,"");ui(()->{fixture.delayComposingRegion=true;return null;});spell("teh");touchTap("Space");
                deadline=SystemClock.uptimeMillis()+4000;while(ui(()->fixture.delayedRegion)==null&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(40);
                require(ui(()->fixture.delayedRegion)!=null,"Delete witness never requested correction");touchTap("x");waitText("teh x");
                ui(()->{fixture.delayedRegion.run();fixture.delayedRegion=null;fixture.delayComposingRegion=false;return null;});SystemClock.sleep(150);
                touchTap("Delete previous character; hold to repeat");waitText("teh ");report.putBoolean("TYPING-LATE-MARKER-DELETE-AT-CARET",true);
                field(fixture.normal,"");spell("teh");int priorRegions=ui(()->fixture.regionRequests);
                ui(()->{fixture.foreignCompositionAfterSpace=true;return null;});touchTap("Space");SystemClock.sleep(600);
                require("teh ".equals(text())&&ui(()->fixture.foreignStart())==1&&ui(()->fixture.foreignEnd())==2,"Existing host composition changed during automatic choice");
                require(ui(()->fixture.regionRequests)==priorRegions&&ui(()->fixture.finishCompositionCalls)==ui(()->fixture.finishesAtForeignComposition),"Auto correction touched preexisting host composition");
                ui(()->{fixture.clearForeignComposition();return null;});report.putBoolean("TYPING-EXISTING-HOST-COMPOSITION-REFUSED",true);
                field(fixture.normal,"");spell("teh");ui(()->{fixture.foreignCompositionInsteadOfRegion=true;return null;});touchTap("Space");SystemClock.sleep(600);
                require("teh ".equals(text())&&ui(()->fixture.foreignStart())==1&&ui(()->fixture.foreignEnd())==2,"Mismatched host composition cleared or replaced");
                require(ui(()->fixture.finishCompositionCalls)==ui(()->fixture.finishesAtForeignComposition),"Cancellation finished foreign host markers");
                ui(()->{fixture.clearForeignComposition();return null;});report.putBoolean("TYPING-MISMATCHED-HOST-COMPOSITION-PRESERVED",true);
                field(fixture.password,"");spell("teh");touchTap("Space");SystemClock.sleep(150);
                require("teh ".equals(ui(()->fixture.password.getText().toString()))&&!visible("Choose typing suggestion the"),"Private field suggested or corrected");
                ui(()->{fixture.normal.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);return null;});field(fixture.normal,"");spell("teh");touchTap("Space");SystemClock.sleep(200);
                require("teh ".equals(text())&&!visible("Choose typing suggestion the"),"Email field corrected");
                require(ui(()->fixture.actions)==0&&"TYPING_SEED".equals(ui(()->clipboard.getPrimaryClip().getItemAt(0).getText().toString())),"Typing submitted or overwrote clipboard");
                report.putBoolean("TYPING-PRIVATE-EMAIL-NO-SEND-CLIPBOARD",true);
                restoreClipboard();report.putString("result","PASS native synthetic owned typing controls only; no corpus/device/next-word/product acceptance");finish(Activity.RESULT_OK,report);return;
            }
            if(gifsOnly){
                clipboard=fixture.getSystemService(android.content.ClipboardManager.class);savedClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
                ui(()->{clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Synthetic GIF fixture","GIF_SEED"));return null;});
                boolean denied=false;
                try(android.os.ParcelFileDescriptor ignored=fixture.getContentResolver().openFileDescriptor(android.net.Uri.parse("content://dev.lip.android.gif/gif/happy.gif"),"r")){}
                catch(SecurityException expected){denied=true;}
                require(denied,"Unexported GIF provider allowed read without content grant");report.putBoolean("GIF-PROVIDER-NO-UNGRANTED-READ",true);
                field(fixture.normal,"LEFT RIGHT",5,5);touchTap("Show local GIF catalog");touchTap("Search local GIF catalog");
                AccessibilityNodeInfo oldSearchKey=find("a");
                touchTap("l");touchTap("a");touchTap("u");touchTap("g");touchTap("h");
                require("LEFT RIGHT".equals(text())&&ui(()->fixture.actions)==0,"On-screen GIF query leaked into editor");
                touchTap("Show local GIF search results");find("Attach GIF: Face with tears of joy");
                oldSearchKey.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(150);
                require("LEFT RIGHT".equals(text()),"Stale search key typed into host");report.putBoolean("GIF-OWNED-QUERY-AND-STALE-KEY-REFUSED",true);
                touchTap("Search local GIF catalog");
                for(int i=0;i<5;i++)hardware(android.view.KeyEvent.KEYCODE_DEL);
                for(int code:new int[]{android.view.KeyEvent.KEYCODE_L,android.view.KeyEvent.KEYCODE_A,android.view.KeyEvent.KEYCODE_U,android.view.KeyEvent.KEYCODE_G,android.view.KeyEvent.KEYCODE_H})hardware(code);
                hardware(android.view.KeyEvent.KEYCODE_ENTER);find("Attach GIF: Face with tears of joy");
                require("LEFT RIGHT".equals(text())&&ui(()->fixture.actions)==0,"Hardware GIF query/Enter leaked or submitted editor");
                report.putBoolean("GIF-HARDWARE-QUERY-ENTER-OWNED",true);image("gif-search-results.png");
                touchTap("Attach GIF: Face with tears of joy");
                long deadline=SystemClock.uptimeMillis()+8000;
                while(ui(()->fixture.gifSha)==null&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(70);
                require(gifHash("laugh").equals(ui(()->fixture.gifSha)),"Editor did not read exact licensed GIF bytes: "+ui(()->fixture.gifError));
                require(ui(()->fixture.gifRequests)==1&&ui(()->fixture.gifFlags)==1,"GIF was duplicated or lacked read grant");
                require(ui(()->fixture.gifSource).startsWith("https://fonts.gstatic.com/"),"GIF source credit link missing");
                require("LEFT RIGHT".equals(text())&&"GIF_SEED".equals(ui(()->clipboard.getPrimaryClip().getItemAt(0).getText().toString())),"GIF altered editor text/clipboard");
                report.putBoolean("GIF-ACTUAL-CONTENT-GRANT-AND-BYTES",true);
                ui(()->{fixture.refuseGif=true;return null;});touchTap("Attach GIF: Face with tears of joy");SystemClock.sleep(450);
                require(ui(()->fixture.gifRequests)==2,"Refused GIF was retried or not dispatched");
                require("LEFT RIGHT".equals(text())&&ui(()->fixture.actions)==0,"Refused GIF changed/submitted editor");
                report.putBoolean("GIF-HOST-REFUSAL-NO-FALLBACK-RETRY",true);
                ui(()->{fixture.refuseGif=false;fixture.getIntent().putExtra("gifEditor",false);fixture.getSystemService(InputMethodManager.class).restartInput(fixture.normal);return null;});
                SystemClock.sleep(250);touchTap("Show local GIF catalog");
                require(!find("Attach GIF: Grinning face").isEnabled(),"Unsupported editor offered GIF attachment");
                find("Attach GIF: Grinning face").performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);
                require(ui(()->fixture.gifRequests)==2,"Unsupported editor received GIF");report.putBoolean("GIF-UNSUPPORTED-MIME-REFUSED",true);
                field(fixture.password,"synthetic-secret");require(!find("Show local GIF catalog").isEnabled(),"Protected GIF control enabled");
                require(ui(()->fixture.actions)==0,"GIF flow pressed Send");report.putBoolean("GIF-PRIVATE-AND-NO-SEND",true);
                restoreClipboard();report.putBoolean("GIF-PRIOR-CLIPBOARD-CONTENT-RESTORED",true);
                report.putString("result","PASS owned emulator GIF query/content/grant controls only; not physical/Gboard/product acceptance");finish(Activity.RESULT_OK,report);return;
            }
            if(clipboardOnly){
                clipboard=fixture.getSystemService(android.content.ClipboardManager.class);
                savedClipboard=ui(()->clipboard.getPrimaryClip());clipboardCaptured=true;
                ui(()->{clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Synthetic Lip fixture","fixture-seed"));return null;});
                field(fixture.normal,"COPY VALUE",0,4);
                String panel="Show clipboard actions and optional encrypted text history";
                touchTap(panel);image("clipboard-actions.png");
                require("fixture-seed".equals(ui(()->clipboard.getPrimaryClip().getItemAt(0).getText().toString())),"Opening panel changed clipboard");
                require(ui(()->fixture.clipboardHostCommands)==0,"Opening panel dispatched an edit");
                report.putBoolean("CLIPBOARD-OPEN-NO-AUTOMATIC-ACTION",true);
                touchTap("Copy selected text to the system clipboard");
                long copyDeadline=SystemClock.uptimeMillis()+3000;
                while(!"COPY".equals(ui(()->clipboard.getPrimaryClip().getItemAt(0).getText().toString()))&&SystemClock.uptimeMillis()<copyDeadline)SystemClock.sleep(50);
                require("COPY".equals(ui(()->clipboard.getPrimaryClip().getItemAt(0).getText().toString())),"Explicit copy did not reach system clipboard");
                require("COPY VALUE".equals(text()),"Copy modified editor");report.putBoolean("CLIPBOARD-EXPLICIT-COPY",true);
                touchTap("Return from clipboard to letters");field(fixture.normal,"LEFT RIGHT",5,5);touchTap(panel);
                require(!find("Copy selected text to the system clipboard").isEnabled(),"Copy enabled without selection");
                touchTap("Paste system clipboard using the current editor");awaitText("LEFT COPYRIGHT");
                require("COPY".equals(ui(()->clipboard.getPrimaryClip().getItemAt(0).getText().toString())),"Paste changed clipboard");
                report.putBoolean("CLIPBOARD-EXPLICIT-PASTE-AT-CARET",true);
                touchTap("Return from clipboard to letters");field(fixture.normal,"ONE TWO",4,7);touchTap(panel);
                touchTap("Paste system clipboard using the current editor");awaitText("ONE COPY");
                require(ui(()->fixture.clipboardHostCommands)==3,"Clipboard commands were retried or duplicated");
                report.putBoolean("CLIPBOARD-EXPLICIT-SELECTION-PASTE-ONCE",true);
                final int beforeHideConnections=ui(()->fixture.inputConnectionCreations);
                ui(()->{fixture.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(fixture.normal.getWindowToken(),0);return null;});
                long hideDeadline=SystemClock.uptimeMillis()+3000;
                while(visible("Paste system clipboard using the current editor")&&SystemClock.uptimeMillis()<hideDeadline)SystemClock.sleep(50);
                require(!visible("Paste system clipboard using the current editor"),"Clipboard window never actually hid");
                ui(()->{fixture.getSystemService(InputMethodManager.class).showSoftInput(fixture.normal,InputMethodManager.SHOW_IMPLICIT);return null;});
                touchTap("Paste system clipboard using the current editor");awaitText("ONE COPYCOPY");
                require(ui(()->fixture.clipboardHostCommands)==4,"Reshown panel lost its editor lease");
                require(ui(()->fixture.inputConnectionCreations)==beforeHideConnections,"Hide/show test recreated host connection instead of resuming");
                report.putBoolean("CLIPBOARD-SAME-EDITOR-HIDE-RESHOW",true);
                AccessibilityNodeInfo stalePaste=find("Paste system clipboard using the current editor");
                field(fixture.password,"synthetic-secret");
                require(!find(panel).isEnabled(),"Private clipboard tools enabled");stalePaste.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);
                require("synthetic-secret".equals(ui(()->fixture.password.getText().toString())),"Stale clipboard button changed protected field");
                require(ui(()->fixture.clipboardHostCommands)==4,"Stale clipboard view dispatched another command");
                field(fixture.incognito,"private fixture");require(!find(panel).isEnabled(),"Incognito clipboard tools enabled");
                require("COPY".equals(ui(()->clipboard.getPrimaryClip().getItemAt(0).getText().toString())),"Private session changed clipboard");
                report.putBoolean("CLIPBOARD-PRIVATE-AND-STALE-REFUSED",true);
                field(fixture.normal,"BLOCKED");touchTap(panel);
                ui(()->{fixture.refuseClipboardActions=true;return null;});
                touchTap("Paste system clipboard using the current editor");SystemClock.sleep(300);
                require("BLOCKED".equals(text()),"Refused host clipboard action modified text");
                require(ui(()->fixture.clipboardHostCommands)==5,"Refused clipboard action was retried or never dispatched");
                require("COPY".equals(ui(()->clipboard.getPrimaryClip().getItemAt(0).getText().toString())),"Refused host action changed clipboard");
                ui(()->{fixture.refuseClipboardActions=false;return null;});
                report.putBoolean("CLIPBOARD-HOST-REFUSAL-NO-RETRY",true);
                require(ui(()->fixture.actions)==0,"Clipboard button submitted editor");report.putBoolean("CLIPBOARD-NEVER-SUBMITS",true);
                restoreClipboard();
                report.putBoolean("CLIPBOARD-PRIOR-CONTENT-RESTORED",true);
                report.putString("result","PASS explicit native clipboard commands only; no history/archive/phone or product acceptance");
                finish(Activity.RESULT_OK,report);return;
            }
            if(startupOnly){
                touchTap("a");require("a".equals(text()),"First startup key did not insert exactly once; observed="+text()+"; "+lastTouch);
                require(ui(()->fixture.actions)==0,"Startup submitted editor");
                report.putBoolean("COLD-FIRST-SHOW-AND-REAL-TAP",true);
                report.putString("result","PASS first editor touch alone shows keyboard and first key inserts; owned fixture only");
                finish(Activity.RESULT_OK,report);return;
            }
            if(candidatesOnly) {
                field(fixture.normal,"LEFT RIGHT",5,5);glide("hello");awaitText("LEFT hello RIGHT");
                List<AccessibilityNodeInfo> options=suggestions();image("candidate-choices.png");
                String description=options.get(1).getContentDescription().toString();String word=description.substring("Choose swipe suggestion ".length());
                touchTap(description);awaitText("LEFT "+word+" RIGHT");suggestions();
                report.putBoolean("ALTERNATIVE-REPLACES-ONLY-OWNED-SPAN",true);
                touchTap("Choose swipe suggestion hello");awaitText("LEFT hello RIGHT");suggestions();
                report.putBoolean("ALTERNATIVE-CAN-CHANGE-BACK",true);
                touchTap("Choose swipe suggestion hello");require("LEFT hello RIGHT".equals(text()),"Accepting current suggestion duplicated text");
                report.putBoolean("CURRENT-CHOICE-NO-DUPLICATE",true);
                touchTap("a");require("LEFT hello aRIGHT".equals(text()),"Manual key overwrote old composing span");
                report.putBoolean("COMPOSING-FINISHED-BEFORE-MANUAL-TYPE",true);
                field(fixture.normal,"");glide("hello");awaitText("hello ");AccessibilityNodeInfo stale=suggestions().get(1);
                ui(()->{fixture.normal.setSelection(0);return null;});SystemClock.sleep(200);stale.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);
                require("hello ".equals(text()),"Expired suggestion changed text after caret move");
                report.putBoolean("CARET-MOVE-EXPIRES-CANDIDATE",true);
                field(fixture.normal,"");glide("hello");awaitText("hello ");stale=suggestions().get(1);
                field(fixture.password,"synthetic-secret");stale.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(200);
                require("synthetic-secret".equals(ui(()->fixture.password.getText().toString())),"Expired candidate touched a protected field");
                require(ui(()->fixture.actions)==0,"Candidate action submitted editor");report.putBoolean("NEW-PRIVATE-EDITOR-EXPIRES-CANDIDATE",true);
                field(fixture.normal,"");glide("hello");awaitText("hello ");options=suggestions();
                final int beforeWrites=ui(()->fixture.composingWrites);
                ui(()->{fixture.refuseComposingRegion=true;return null;});
                touchTap(options.get(1).getContentDescription().toString());SystemClock.sleep(1200);
                require(ui(()->fixture.regionRefusals)==1,"Server-side region refusal was not exercised");
                require(ui(()->fixture.composingWrites)==beforeWrites,"Replacement dispatched without editor span acknowledgement");
                require("hello ".equals(text()),"Refused host span appended/replaced text");
                find("Check the editor; correction could not be confirmed · Tap to cancel");
                ui(()->{fixture.refuseComposingRegion=false;return null;});
                report.putBoolean("HOST-REGION-REFUSAL-NO-TEXT-DISPATCH",true);
                // Real server delays region installation until a second physical glide has begun.
                touchTap("Check the editor; correction could not be confirmed · Tap to cancel");
                field(fixture.normal,"");glide("hello");awaitText("hello ");options=suggestions();
                final int writesBeforeDelay=ui(()->fixture.composingWrites);
                ui(()->{fixture.delayComposingRegion=true;return null;});
                final long correctionTap=touchTapAt(options.get(1).getContentDescription().toString());
                require(ui(()->fixture.delayedRegion)!=null,"Delayed host region was not exercised");
                // w→o spans seven key pitches; step5 is1.75 pitches, beyond the half-pitch intercept.
                glide("world",()->ui(()->{
                    long elapsed=SystemClock.uptimeMillis()-correctionTap;
                    require(elapsed<750,"Delayed-ack discriminator exceeded transaction window: "+elapsed);
                    fixture.delayedRegion.run();fixture.delayedRegion=null;fixture.delayComposingRegion=false;return null;
                }));
                awaitText("hello world ");
                require(ui(()->fixture.composingWrites)==writesBeforeDelay,"Old correction wrote after a new glide began");
                report.putBoolean("DELAYED-HOST-ACK-NEW-GLIDE-REVOKES-OLD-CORRECTION",true);
                report.putString("result","PASS actual touchscreen/candidate/composing controls in owned emulator; no human quality/phone or9.2 acceptance");finish(Activity.RESULT_OK,report);return;
            }
            if(glideOnly) {
                glide("hello");awaitText("hello ");report.putBoolean("REAL-GLIDE-NEURAL-INSERT",true);
                field(fixture.normal,"LEFT RIGHT",5,5);glide("hello");awaitText("LEFT hello RIGHT");report.putBoolean("GLIDE-CARET-SURROUNDINGS",true);
                field(fixture.password,"synthetic-secret");glide("hello");SystemClock.sleep(300);
                require("synthetic-secret".equals(ui(() -> fixture.password.getText().toString())),"Protected field mutated by glide");report.putBoolean("PASSWORD-GLIDE-REFUSED",true);
                field(fixture.incognito,"private fixture");glide("hello");SystemClock.sleep(300);
                require("private fixture".equals(ui(() -> fixture.incognito.getText().toString())),"Incognito field mutated by glide");report.putBoolean("INCOGNITO-GLIDE-REFUSED",true);
                field(fixture.normal,"LEFT RIGHT",0,4);glide("hello");SystemClock.sleep(300);
                require("LEFT RIGHT".equals(text()),"Glide replaced an existing selection");report.putBoolean("SELECTED-TEXT-GLIDE-REFUSED",true);
                field(fixture.normal,"");touchTap("a");require("a".equals(text()),"Manual tap regressed after glide");report.putBoolean("GLIDE-THEN-TAP",true);
                require(ui(() -> fixture.actions)==0,"Glide submitted the editor action");report.putBoolean("GLIDE-NEVER-SUBMITS-EDITOR",true);
                report.putString("result","PASS named actual touchscreen/IME/neural/at-caret controls in owned emulator; alternatives/human quality/phone gates not qualified");finish(Activity.RESULT_OK,report);return;
            }

            if (visualVariant != null) {
                require(visualVariant.matches("[a-z0-9-]{1,40}"), "Unsafe evidence name");
                if("landscape".equals(visualVariant)) require(ui(() -> fixture.getResources().getConfiguration().orientation)==android.content.res.Configuration.ORIENTATION_LANDSCAPE,"Landscape configuration not applied");
                field(fixture.normal,"Synthetic visual configuration fixture");
                AccessibilityNodeInfo q=find("q"); android.graphics.Rect keyBounds=new android.graphics.Rect();q.getBoundsInScreen(keyBounds);
                require(!keyBounds.isEmpty(),"Letter geometry missing");
                require(find("Local translation not yet available in this English preview").isEnabled()==false,"Unavailable tool became actionable");
                if("landscape".equals(visualVariant)) {
                    android.graphics.Rect editor=ui(() -> { android.graphics.Rect r=new android.graphics.Rect();require(fixture.normal.getGlobalVisibleRect(r),"Landscape editor hidden");return r; });
                    android.graphics.Rect toolbar=new android.graphics.Rect();find("Lip keyboard menu").getBoundsInScreen(toolbar);
                    require(editor.bottom<=toolbar.top && editor.height()==ui(() -> fixture.normal.getHeight()),"Landscape keyboard obscures the editor");
                }
                image("glass-"+visualVariant+".png");
                AccessibilityNodeInfo menu=find("Lip keyboard menu");
                // Genuine keyboard navigation leaves touch mode; never change production
                // touch focus semantics just to manufacture a focus-ring capture.
                automation.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_DOWN),true);
                automation.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DPAD_DOWN),true);
                boolean focused=menu.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                require(focused || find("Lip keyboard menu").isFocused(),"Keyboard input focus refused");
                image("glass-"+visualVariant+"-focus.png");
                require(find("Lip keyboard menu").isFocused(),"Keyboard input focus state missing");
                menu.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
                tap("q");require(text().endsWith("q"),"Configured keyboard cannot type");
                report.putString("variant",visualVariant);
                report.putString("result","PASS configured emulator visual capture and named interaction only; manual image review still required");
                finish(Activity.RESULT_OK,report);return;
            }
            touchTap("a");require("a".equals(text()),"First real touch on a did not commit exactly once; observed="+text()+"; "+lastTouch);
            touchTap("b");require("ab".equals(text()),"First real touch on b did not commit exactly once");
            touchTap("c");touchTap("c");require("abcc".equals(text()),"Cross-key/repeated real touch mismatch");
            report.putBoolean("TOUCH-FIRST-AND-REPEAT",true);
            field(fixture.normal, "");
            touchTap("Shift; double-tap to lock capitals");require("".equals(text()),"Touch Shift inserted text");
            touchTap("A");require("A".equals(text()),"Touch Shift/first capital failed");
            touchTap("b");require("Ab".equals(text()),"Touch one-shot Shift did not reset");
            touchTap("Delete previous character; hold to repeat");require("A".equals(text()),"First touch Delete did not delete exactly once");
            touchTap("Delete previous character; hold to repeat");require("".equals(text()),"Repeated touch Delete failed");
            report.putBoolean("TOUCH-SHIFT-DELETE",true);
            field(fixture.normal, "");
            tap("a"); tap("b"); tap("c"); require("abc".equals(text()), "ASCII tap mismatch");
            report.putBoolean("IME-TAP-ASCII", true);
            field(fixture.normal, "LEFT RIGHT", 5, 5); tap("a");
            require("LEFT aRIGHT".equals(text()), "Surrounding text changed"); report.putBoolean("INSERT-CARET-SURROUNDINGS", true);
            field(fixture.normal, ""); tap("Shift; double-tap to lock capitals"); tap("A"); tap("b");
            require("Ab".equals(text()), "One-shot Shift mismatch"); report.putBoolean("IME-SHIFT-ONCE", true);
            field(fixture.normal, "left 👨‍👩‍👧‍👦"); tap("Delete previous character; hold to repeat");
            require("left ".equals(text()), "ZWJ cluster was split"); report.putBoolean("IME-DELETE-EMOJI-ZWJ", true);
            field(fixture.normal, "LEFT RIGHT", 0, 4); tap("Delete previous character; hold to repeat");
            require(" RIGHT".equals(text()), "Selected deletion changed surroundings"); report.putBoolean("IME-DELETE-SELECTION", true);
            field(fixture.normal, ""); tap("Switch letters and symbols"); tap("1"); tap("@"); tap("Switch letters and symbols");
            require("1@".equals(text()), "Symbol layout mismatch"); report.putBoolean("IME-SYMBOLS", true);
            field(fixture.normal, ""); require(ui(() -> fixture.actions) == 0, "Automatic action occurred");
            tap("a"); require(ui(() -> fixture.actions) == 0, "Typing sent editor action");
            tap("Send in the current editor"); require(ui(() -> fixture.actions) == 1, "Explicit Send action missing");
            report.putBoolean("IME-ACTION-EXPLICIT", true);
            field(fixture.password, ""); require(!find("Record with the selected local Bark model").isEnabled(), "Password voice enabled");
            tap("a"); require("a".equals(ui(() -> fixture.password.getText().toString())), "Manual password input failed");
            report.putBoolean("PASSWORD-MANUAL-ONLY", true);
            field(fixture.number, ""); tap("1"); tap("2"); require("12".equals(ui(() -> fixture.number.getText().toString())), "Numeric input mismatch");
            require(!find("Record with the selected local Bark model").isEnabled(), "Numeric voice enabled");
            report.putBoolean("IME-NUMBER-DIGITS", true);
            field(fixture.signedNumber, ""); tap("-"); tap("1"); tap("2"); tap("."); tap("5");
            require("-12.5".equals(ui(() -> fixture.signedNumber.getText().toString())), "Signed decimal input mismatch"); report.putBoolean("IME-SIGNED-DECIMAL", true);
            field(fixture.phone, ""); tap("+"); tap("1"); tap("2"); tap("3"); tap("*"); tap("#");
            require("+123*#".equals(ui(() -> fixture.phone.getText().toString())), "Phone character input mismatch"); report.putBoolean("IME-PHONE", true);
            field(fixture.time, ""); tap("1"); tap("2"); tap(":"); tap("3"); tap("0");
            require("12:30".equals(ui(() -> fixture.time.getText().toString())), "Time input mismatch"); report.putBoolean("IME-TIME", true);
            field(fixture.multiline, ""); tap("Enter in the current editor");
            require("\n".equals(ui(() -> fixture.multiline.getText().toString())) && ui(() -> fixture.actions) == 1, "No-enter-action label dispatched an action");
            report.putBoolean("IME-NO-ENTER-ACTION", true);
            field(fixture.custom, ""); tap("Run fixture in the current editor");
            require(ui(() -> fixture.lastCustomAction) == 42, "Custom action id missing"); report.putBoolean("IME-CUSTOM-ACTION", true);
            field(fixture.incognito, ""); require(!find("Record with the selected local Bark model").isEnabled(), "Incognito voice policy differs from overlay");
            tap("a"); require("a".equals(ui(() -> fixture.incognito.getText().toString())), "Incognito manual input failed"); report.putBoolean("IME-INCOGNITO-MANUAL", true);
            field(fixture.normal, "repeat target abcdefghijklmnopqrstuvwxyz");
            require(find("Delete previous character; hold to repeat").performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK), "Long Delete unavailable");
            SystemClock.sleep(120);
            field(fixture.number, "12345");
            SystemClock.sleep(300);
            require("12345".equals(ui(() -> fixture.number.getText().toString())), "Repeat delete crossed editor incarnation");
            report.putBoolean("REPEAT-NEW-EDITOR-REFUSED", true);
            field(fixture.normal, "repeat target abcdefghijklmnopqrstuvwxyz");
            require(find("Delete previous character; hold to repeat").performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK), "Long Delete unavailable");
            SystemClock.sleep(120);
            ui(() -> {
                fixture.normal.setText("refreshed input"); fixture.normal.setSelection(fixture.normal.length());
                fixture.getSystemService(InputMethodManager.class).restartInput(fixture.normal); return null;
            });
            SystemClock.sleep(350);
            require("refreshed input".equals(text()), "Repeat delete crossed restartInput incarnation");
            report.putBoolean("REPEAT-RESTART-REFUSED", true);
            field(fixture.normal, "");
            String letters="qwertyuiopasdfghjklzxcvbnm";
            String[] hints={"%","\\","|","=","[","]","<",">","{","}","@","#","$","_","&","-","+","(",")","*","\"","'",":",";","!","?"};
            for(int i=0;i<letters.length();i++){
                field(fixture.normal,"");
                require(find(letters.substring(i,i+1)).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK),"Long-press hint unavailable");
                SystemClock.sleep(70);waitForIdleSync();
                require(hints[i].equals(text()),"Long-press output mismatch for "+letters.charAt(i));
            }
            report.putBoolean("KEY-LONGPRESS-PUNCTUATION",true);
            field(fixture.normal, "Synthetic fixture; keyboard preview");
            tap("Shift; double-tap to lock capitals");
            AccessibilityNodeInfo shift=find("Shift; double-tap to lock capitals");
            require(shift.isCheckable() && shift.isChecked() && "Shift on".contentEquals(shift.getStateDescription()),"Shift accessibility state missing");
            image("ime-fixture.png");image("glass-uppercase.png");
            AccessibilityNodeInfo letter=find("Q");android.graphics.Rect rect=new android.graphics.Rect();letter.getBoundsInScreen(rect);
            long downTime=SystemClock.uptimeMillis();
            android.view.MotionEvent press=android.view.MotionEvent.obtain(downTime,downTime,android.view.MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0);
            press.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(press,true),"Press injection refused");press.recycle();
            try{image("glass-pressed.png");}finally{
                android.view.MotionEvent release=android.view.MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0);
                release.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(release,true);release.recycle();
            }
            field(fixture.normal, "");
            tap("Shift; double-tap to lock capitals");SystemClock.sleep(400);tap("Shift; double-tap to lock capitals");
            // A single state transition is always legible; explicit long-click locks Caps.
            require(find("Shift; double-tap to lock capitals").performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK),"Caps long-press unavailable");
            SystemClock.sleep(90);
            require("Caps Lock on".contentEquals(find("Shift; double-tap to lock capitals").getStateDescription()),"Caps state missing");
            report.putBoolean("SHIFT-CAPS-ACCESSIBILITY",true);
            field(fixture.number, ""); // Reset shift/page through an actual editor incarnation.
            field(fixture.normal, "");
            tap("Switch letters and symbols");sameRow("Switch letters and symbols",",","Show number pad","Space",".","Send in the current editor");image("glass-symbols.png");
            tap("Show number pad");find("Show symbols");tap("4");tap("2");require("42".equals(text()),"Number-pad input mismatch");sameRow(",","Show symbols","0","=",".");report.putBoolean("ALTERNATE-ROW-BOUNDS",true);image("glass-numberpad.png");
            tap("Switch letters and symbols");
            tap("Show emoji");image("glass-emoji.png");tap("Return from emoji to letters");
            field(fixture.normal, "Synthetic fixture; keyboard preview");
            image("glass-lowercase.png");
            report.putString("evidence", "real Android IME/InputConnection in disposable emulator; touchscreen DOWN/UP plus semantic button actions, not physical swipe/voice/phone evidence");
            report.putString("result", "PASS for named cases only; whole keyboard unqualified"); outcome = Activity.RESULT_OK;
        } catch (Throwable e) {
            report.putString("failure", e.getClass().getSimpleName() + ": " + e.getMessage());
            if(cursorOnly)report.putString("cursor-routing",cursorRouting);
            try {
                if (automation != null) {
                    Bitmap image = automation.takeScreenshot();
                    if (image != null) { try (FileOutputStream stream = new FileOutputStream(new File(getTargetContext().getFilesDir(), emojiOnly?"emoji-failure.png":"ime-failure.png"))) { image.compress(Bitmap.CompressFormat.PNG,100,stream); } image.recycle(); }
                }
            } catch (Exception ignored) { }
        }
        finally {
            try{restoreClipboard();}catch(Throwable cleanup){report.putString("cleanup-failure",cleanup.getClass().getSimpleName());outcome=Activity.RESULT_CANCELED;}
            try{if (fixture != null) ui(() -> { fixture.finish(); return null; });}
            catch(Throwable cleanup){report.putString("fixture-cleanup-failure",cleanup.getClass().getSimpleName());outcome=Activity.RESULT_CANCELED;}
            if(wordDeleteOnly||emojiOnly||coldTallOnly||narrowHandOnly)finish(outcome,report); // Includes success early-return, AFTER cleanup.
        }
        if(!wordDeleteOnly&&!emojiOnly&&!coldTallOnly)finish(outcome, report);
    }
}

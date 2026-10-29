package dev.lip;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Ordinary owned separate-UID host. Records only its fixed synthetic field and
 * actual native dispatch; never forwards/injects IME touches or reads other apps.
 */
public final class FloatingKeyboardEditorActivity extends Activity {
    private static final String PACKAGE="dev.lip.android.test";
    private static final int MAX_EVENTS=256,MAX_TEXT_EVENTS=64;
    private static final String BASELINE=syntheticText();
    private String owner,generation,instance;private File root;private FrameLayout host;private EditText editor;
    private boolean ready,initialFocusRequested;private long sequence;private int downs,ups,cancels,textChanges,selectionChanges;
    private int previousSelectionStart,previousSelectionEnd,lastSelectionBeforeStart,lastSelectionBeforeEnd,lastSelectionAfterStart,lastSelectionAfterEnd;
    private int textSelectionBeforeStart,textSelectionBeforeEnd;private int textSelectionAfterStart,textSelectionAfterEnd;
    private final JSONArray touches=new JSONArray(),changes=new JSONArray();private boolean touchTruncated,textTruncated;
    public static String syntheticText(){
        StringBuilder text=new StringBuilder("SYNTHETIC_FLOATING_HOST\n");
        for(int i=1;i<=64;i++)text.append(String.format(Locale.ROOT,"SYNTHETIC_ROW_%02d abcdefghijklmnopqrstuvwxyz 0123456789\n",i));
        return text.append("SYNTHETIC_END").toString();
    }
    private static String uuid(String raw){try{return raw!=null&&UUID.fromString(raw).toString().equals(raw)?raw:null;}catch(Exception ignored){return null;}}
    private static void require(boolean value){if(!value)throw new IllegalStateException("Owned floating host precondition failed");}
    private static String sha(String text) throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static JSONObject rect(Rect r) throws Exception {return new JSONObject().put("left",r.left).put("top",r.top).put("right",r.right).put("bottom",r.bottom).put("width",r.width()).put("height",r.height());}
    private static JSONObject inset(Insets i) throws Exception {return new JSONObject().put("left",i.left).put("top",i.top).put("right",i.right).put("bottom",i.bottom);}
    private static JSONObject view(View v) throws Exception {
        int[] at=new int[2];v.getLocationOnScreen(at);Rect visible=new Rect();boolean hasVisible=v.getGlobalVisibleRect(visible);
        return new JSONObject().put("screenBounds",rect(new Rect(at[0],at[1],at[0]+v.getWidth(),at[1]+v.getHeight())))
            .put("globalVisible",hasVisible).put("globalVisibleRect",rect(visible)).put("paddingLeft",v.getPaddingLeft()).put("paddingTop",v.getPaddingTop()).put("paddingRight",v.getPaddingRight()).put("paddingBottom",v.getPaddingBottom());
    }
    private void snapshot(String reason){
        if(!ready||root==null||host==null||editor==null)return;
        try{
            String text=editor.getText().toString();require(text.length()<=8192);
            DisplayMetrics display=new DisplayMetrics();getWindowManager().getDefaultDisplay().getRealMetrics(display);
            Rect visibleFrame=new Rect();getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
            WindowInsets wi=host.getRootWindowInsets();JSONObject insets=new JSONObject().put("available",wi!=null);
            if(wi!=null)insets.put("systemBars",inset(wi.getInsets(WindowInsets.Type.systemBars())))
                .put("ime",inset(wi.getInsets(WindowInsets.Type.ime()))).put("imeVisible",wi.isVisible(WindowInsets.Type.ime()))
                .put("displayCutout",inset(wi.getInsets(WindowInsets.Type.displayCutout())))
                .put("systemGestures",inset(wi.getInsets(WindowInsets.Type.systemGestures())))
                .put("mandatorySystemGestures",inset(wi.getInsets(WindowInsets.Type.mandatorySystemGestures())));
            JSONObject report=new JSONObject().put("schema","dev.lip.floating-host.v1").put("owner",owner).put("generation",generation).put("instanceId",instance)
                .put("uid",Process.myUid()).put("pid",Process.myPid()).put("package",getPackageName()).put("sequence",++sequence).put("reason",reason)
                .put("uptimeMillis",android.os.SystemClock.uptimeMillis()).put("windowFocused",hasWindowFocus()).put("editorFocused",editor.hasFocus())
                .put("displayWidth",display.widthPixels).put("displayHeight",display.heightPixels).put("rotation",getWindowManager().getDefaultDisplay().getRotation())
                .put("currentWindowBounds",rect(getWindowManager().getCurrentWindowMetrics().getBounds())).put("visibleDisplayFrame",rect(visibleFrame))
                .put("decor",view(getWindow().getDecorView())).put("host",view(host)).put("editor",view(editor)).put("rootInsets",insets)
                .put("softInputMode",getWindow().getAttributes().softInputMode).put("windowFlags",getWindow().getAttributes().flags)
                .put("nativeDowns",downs).put("nativeUps",ups).put("nativeCancels",cancels).put("touches",touches).put("touchReceiptTruncated",touchTruncated)
                .put("textChanges",textChanges).put("textEvents",changes).put("textReceiptTruncated",textTruncated)
                .put("baselineSha256",sha(BASELINE)).put("textSha256",sha(text)).put("baselineUnchanged",BASELINE.equals(text))
                .put("textUtf16Length",text.length()).put("textCodePoints",text.codePointCount(0,text.length()))
                .put("selectionStart",editor.getSelectionStart()).put("selectionEnd",editor.getSelectionEnd()).put("selectionChanges",selectionChanges)
                .put("lastSelectionBeforeStart",lastSelectionBeforeStart).put("lastSelectionBeforeEnd",lastSelectionBeforeEnd)
                .put("lastSelectionAfterStart",lastSelectionAfterStart).put("lastSelectionAfterEnd",lastSelectionAfterEnd)
                .put("textSelectionBeforeStart",textSelectionBeforeStart).put("textSelectionBeforeEnd",textSelectionBeforeEnd)
                .put("textSelectionAfterStart",textSelectionAfterStart).put("textSelectionAfterEnd",textSelectionAfterEnd)
                .put("scope","owned synthetic host observations; no touch forwarding, private/global input capture or floating-keyboard PASS claim");
            File target=new File(root,"state.json");for(String suffix:new String[]{"",".bak",".new"})require(!Files.isSymbolicLink(new File(target.getPath()+suffix).toPath()));
            AtomicFile atomic=new AtomicFile(target);FileOutputStream output=atomic.startWrite();
            try{Os.fchmod(output.getFD(),0600);output.write(report.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(output);}catch(Exception e){atomic.failWrite(output);throw e;}
        }catch(Exception failure){ready=false;finish();} // No misleading live fixture after a receipt failure.
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        require(PACKAGE.equals(getPackageName())&&(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&Build.VERSION.SDK_INT>=36);
        require(("ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE))&&Build.PRODUCT.contains("sdk"));
        owner=uuid(getIntent().getStringExtra("owner"));generation=uuid(getIntent().getStringExtra("generation"));if(generation==null)generation=owner;require(owner!=null&&generation!=null);
        instance=UUID.randomUUID().toString();File base=getNoBackupFilesDir();require(!Files.isSymbolicLink(base.toPath())&&Files.isDirectory(base.toPath(),LinkOption.NOFOLLOW_LINKS));
        root=new File(base,"floating-keyboard-host-"+owner);Files.createDirectory(root.toPath());Os.chmod(root.getPath(),0700);
        JSONObject immutable=new JSONObject().put("schema","dev.lip.floating-host-owner.v1").put("owner",owner).put("generation",generation).put("instanceId",instance).put("baselineSha256",sha(BASELINE));
        File initial=new File(root,"owner.json");Files.write(initial.toPath(),immutable.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Os.chmod(initial.getPath(),0600);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        getWindow().setDecorFitsSystemWindows(false);
        host=new FrameLayout(this);host.setBackgroundColor(Color.rgb(22,28,34));host.setContentDescription("Owned floating keyboard host");
        editor=new EditText(this){@Override protected void onSelectionChanged(int start,int end){super.onSelectionChanged(start,end);if(ready){lastSelectionBeforeStart=previousSelectionStart;lastSelectionBeforeEnd=previousSelectionEnd;lastSelectionAfterStart=start;lastSelectionAfterEnd=end;previousSelectionStart=start;previousSelectionEnd=end;selectionChanges++;snapshot("selection");}}};
        editor.setContentDescription("Owned floating keyboard host editor");editor.setSaveEnabled(false);editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setGravity(Gravity.TOP|Gravity.START);editor.setTextSize(16);editor.setTextColor(Color.WHITE);editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);editor.setPadding(16,16,16,16);editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});
        editor.setText(BASELINE);editor.setSelection(0);host.addView(editor,new FrameLayout.LayoutParams(-1,-1));
        editor.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){if(ready){textSelectionBeforeStart=editor.getSelectionStart();textSelectionBeforeEnd=editor.getSelectionEnd();}}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(ready){textChanges++;try{if(changes.length()<MAX_TEXT_EVENTS)changes.put(new JSONObject().put("start",start).put("removed",before).put("inserted",count).put("selectionBeforeStart",textSelectionBeforeStart).put("selectionBeforeEnd",textSelectionBeforeEnd));else textTruncated=true;}catch(Exception ignored){}}}
            public void afterTextChanged(Editable e){if(ready){textSelectionAfterStart=editor.getSelectionStart();textSelectionAfterEnd=editor.getSelectionEnd();snapshot("text");}}
        });
        host.setOnApplyWindowInsetsListener((v,insets)->{Insets system=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            if(v.getPaddingLeft()!=system.left||v.getPaddingTop()!=system.top||v.getPaddingRight()!=system.right||v.getPaddingBottom()!=system.bottom)v.setPadding(system.left,system.top,system.right,system.bottom);
            if(ready)v.post(()->snapshot("insets"));return insets; // Do not consume or alter IME insets.
        });
        host.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->snapshot("layout"));setContentView(host);ready=true;host.requestApplyInsets();snapshot("created");
    }catch(Exception ignored){ready=false;finish();}}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);snapshot("window-focus");
        if(focused&&ready&&!initialFocusRequested){initialFocusRequested=true;editor.post(()->{if(ready&&hasWindowFocus()&&!isFinishing()){editor.requestFocus();getSystemService(InputMethodManager.class).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);snapshot("initial-focus");}});}}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(host!=null)host.post(()->snapshot("configuration"));}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        boolean tracked=ready&&event!=null&&(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL||event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN||event.getActionMasked()==MotionEvent.ACTION_POINTER_UP);
        JSONObject row=null;int beforeStart=editor==null?-1:editor.getSelectionStart(),beforeEnd=editor==null?-1:editor.getSelectionEnd();
        if(tracked){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)downs++;if(event.getActionMasked()==MotionEvent.ACTION_UP)ups++;if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)cancels++;
            try{row=new JSONObject().put("action",event.getAction()).put("actionMasked",event.getActionMasked()).put("eventTime",event.getEventTime()).put("downTime",event.getDownTime())
                .put("x",event.getX()).put("y",event.getY()).put("rawX",event.getRawX()).put("rawY",event.getRawY()).put("source",event.getSource()).put("flags",event.getFlags())
                .put("deviceId",event.getDeviceId()).put("pointerCount",event.getPointerCount()).put("pointerId0",event.getPointerId(0)).put("selectionBeforeStart",beforeStart).put("selectionBeforeEnd",beforeEnd);
                if(touches.length()<MAX_EVENTS)touches.put(row);else touchTruncated=true;
            }catch(Exception ignored){}}
        boolean handled=super.dispatchTouchEvent(event); // The ONLY dispatch: actual normal platform delivery, no forwarding.
        if(tracked){try{if(row!=null)row.put("handled",handled).put("selectionAfterStart",editor.getSelectionStart()).put("selectionAfterEnd",editor.getSelectionEnd());}catch(Exception ignored){}snapshot("native-touch");}
        return handled;
    }
    @Override protected void onPause(){snapshot("pause");super.onPause();}
    @Override protected void onDestroy(){snapshot("destroy");ready=false;super.onDestroy();}
}

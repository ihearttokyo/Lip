package dev.lip.testing.localsticker;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;

/** Ordinary separate-UID editor. Actual commitContent/read grants, no fixture adapter or Send. */
public final class LocalStickerReceiverActivity extends Activity {
    private static final String BASELINE="SYNTHETIC_STICKER_EDITOR";
    private String owner,generation,mode,expectedSha,expectedMime;private File root;private EditText editor;private TextView status;
    private InputConnection primary;private Uri lastUri;private int attempts,actions,enters,textChanges,readBytes,requestPermissionCalls,probes;
    private boolean received,hashMatches,readAllowedBefore,readAllowedAfter,probeAllowed;private String actualSha="",actualMime="",error="";
    private void writeReport(){
        if(root==null)return;try{
            JSONObject j=new JSONObject().put("owner",owner).put("generation",generation).put("uid",Process.myUid()).put("package",getPackageName()).put("mode",mode)
                .put("attempts",attempts).put("editorActions",actions).put("enterEvents",enters).put("textChanges",textChanges)
                .put("baselineUnchanged",editor!=null&&BASELINE.contentEquals(editor.getText())).put("received",received)
                .put("hashMatches",hashMatches).put("readBytes",readBytes).put("sha256",actualSha).put("mime",actualMime)
                .put("permissionRequested",requestPermissionCalls).put("readAllowedBefore",readAllowedBefore).put("readAllowedAfter",readAllowedAfter)
                .put("probes",probes).put("probeAllowed",probeAllowed).put("errorType",error)
                .put("composingStart",editor==null?-1:BaseInputConnection.getComposingSpanStart(editor.getText()))
                .put("composingEnd",editor==null?-1:BaseInputConnection.getComposingSpanEnd(editor.getText()))
                .put("scope","owned emulator receiver observations only; no onward send or arbitrary app proof");
            LocalStickerFixtureState.report(root,generation,j);
        }catch(Exception ignored){}
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        LocalStickerFixtureState.guard(this);Intent i=getIntent();owner=LocalStickerFixtureState.token(i.getStringExtra("owner"));root=LocalStickerFixtureState.root(this,owner);
        generation=LocalStickerFixtureState.token(i.getStringExtra("generation"));if(generation==null||LocalStickerFixtureState.child(root,"receiver-"+generation+".json").exists())throw new IllegalStateException("Fresh receiver generation required");
        mode=i.getStringExtra("mode");if(mode==null)mode="accept";
        if(!new HashSet<>(Arrays.asList("accept","refuse","refuse-after-read","unsupported","private","no-learning","composition")).contains(mode))throw new IllegalStateException("Unknown synthetic receiver mode");
        expectedSha=i.getStringExtra("expectedSha");expectedMime=i.getStringExtra("expectedMime");
        if(expectedSha==null||!expectedSha.matches("[a-f0-9]{64}")||!("image/png".equals(expectedMime)||"image/webp".equals(expectedMime)))throw new IllegalStateException("Synthetic image identity required");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);
        status=new TextView(this);status.setText("Owned local sticker receiver · "+mode);layout.addView(status);
        editor=new EditText(this){@Override public InputConnection onCreateInputConnection(EditorInfo attrs){
            InputConnection base=super.onCreateInputConnection(attrs);if(base==null)return null;primary=base;
            attrs.contentMimeTypes="unsupported".equals(mode)?new String[]{"text/plain"}:new String[]{expectedMime};
            if("no-learning".equals(mode))attrs.imeOptions|=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            return new InputConnectionWrapper(base,false){
                @Override public boolean commitContent(InputContentInfo info,int flags,Bundle opts){return receive(info,flags);}
                @Override public boolean performEditorAction(int action){actions++;writeReport();return false;}
                @Override public boolean sendKeyEvent(KeyEvent event){if(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN){enters++;writeReport();}return super.sendKeyEvent(event);}
            };
        }};
        editor.setSaveEnabled(false);editor.setInputType("private".equals(mode)?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setContentDescription("Owned local sticker receiver editor");editor.setText(BASELINE);editor.setSelection(BASELINE.length());
        editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){textChanges++;writeReport();}public void afterTextChanged(Editable e){}});
        layout.addView(editor,new LinearLayout.LayoutParams(-1,120));
        Button probe=new Button(this);probe.setText("Probe previous sticker permission");probe.setContentDescription("Probe previous sticker permission");probe.setFocusable(false);
        probe.setOnClickListener(v->{probes++;probeAllowed=false;if(lastUri!=null)try(InputStream in=getContentResolver().openInputStream(lastUri)){probeAllowed=in!=null&&in.read()>=0;}catch(Exception ignored){}writeReport();status.setText("Permission probe completed · "+probeAllowed);});layout.addView(probe);
        if("composition".equals(mode)){
            Button composing=new Button(this);composing.setText("Begin synthetic composition");composing.setContentDescription("Begin synthetic composition");composing.setFocusable(false);
            composing.setOnClickListener(v->{if(primary!=null){primary.setComposingRegion(0,9);getSystemService(InputMethodManager.class).updateSelection(editor,editor.getSelectionStart(),editor.getSelectionEnd(),BaseInputConnection.getComposingSpanStart(editor.getText()),BaseInputConnection.getComposingSpanEnd(editor.getText()));writeReport();}});layout.addView(composing);
        }
        setContentView(layout);writeReport();
    }catch(Exception e){finish();}}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&editor!=null)editor.post(()->{if(hasWindowFocus()&&!isFinishing()){editor.requestFocus();getSystemService(InputMethodManager.class).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);}});}
    private boolean receive(InputContentInfo info,int flags){
        attempts++;writeReport();if(attempts>1||info==null||"refuse".equals(mode)||"unsupported".equals(mode))return false;
        lastUri=info.getContentUri();
        try{
            if((flags&InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION)==0||lastUri==null||!"content".equals(lastUri.getScheme())||info.getDescription()==null||!info.getDescription().hasMimeType(expectedMime))throw new IllegalStateException("Content contract mismatch");
            readAllowedBefore=checkUriPermission(lastUri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED;
            requestPermissionCalls++;info.requestPermission();
            try{
                actualMime=getContentResolver().getType(lastUri);if(!expectedMime.equals(actualMime))throw new IllegalStateException("MIME mismatch");
                readAllowedAfter=checkUriPermission(lastUri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED;
                ByteArrayOutputStream output=new ByteArrayOutputStream();try(InputStream input=getContentResolver().openInputStream(lastUri)){
                    if(input==null)throw new IllegalStateException("No granted source");byte[] buffer=new byte[8192];int count;
                    while((count=input.read(buffer))!=-1){if(count<=0||output.size()>8*1024*1024-count)throw new IllegalStateException("Bounded read required");output.write(buffer,0,count);}
                }
                byte[] bytes=output.toByteArray();readBytes=bytes.length;actualSha=LocalStickerFixtureState.sha(bytes);hashMatches=actualSha.equals(expectedSha);received=hashMatches&&readAllowedAfter&&bytes.length>0;
            }finally{info.releasePermission();}
        }catch(Exception e){error=e.getClass().getSimpleName();received=false;}
        writeReport();status.setText(received?"Synthetic original received; no Send":"Synthetic original refused; no retry");return received&&!"refuse-after-read".equals(mode);
    }
    @Override protected void onDestroy(){writeReport();super.onDestroy();}
}

package dev.lip;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Button;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.SurroundingText;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Real Android editor fixture for test-only input-content ingress; never sends content onward. */
public final class StickerEditorFixtureActivity extends Activity {
    private static final String TEST_PACKAGE = "dev.lip.android.sizingfixture.test";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PROBE_BYTES = MAX_BYTES + 1;
    private static final int BUFFER_BYTES = 32 * 1024;
    private static final Pattern SAFE_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final String[] IMAGE_MIMES = {"image/png", "image/webp"};

    private final Object stateLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private EditText editor;
    private EditText replacementEditor;
    private EditText preferredEditor;
    private InputConnection primaryConnection;
    private String baseline="";
    private volatile String focusedEditor="OTHER";
    private volatile int inputConnections;
    private volatile int compositionStarts, compositionEnds, imeCompositionFinishes;
    private volatile int composingStart=-1, composingEnd=-1;
    private volatile boolean probeArmed, probeTriggered, unknownOffset;
    private volatile int probeReads, probeUnexpectedReads, probeTriggerOrdinal, probeSelectionDelta, unknownOffsetResponses;
    private String generation = "";
    private String mode = "";
    private boolean valid;
    private boolean destroyed;
    private boolean workerStarted;
    private Runnable delayedRead;
    private Thread worker;
    private volatile Uri lastUri;

    private volatile int invocations;
    private volatile int readStarted;
    private volatile int readCompleted;
    private volatile int bytes;
    private volatile String sha256 = "";
    private volatile String mime = "";
    private volatile String errorClass = "";
    private volatile int flags;
    private volatile boolean grantObserved;
    private volatile Boolean grantBeforeRequest;
    private volatile Boolean grantAbsentAfterRelease;
    private volatile String grantObservationError = "";
    private volatile boolean releaseCompleted;
    private volatile int editorActions;
    private volatile int textChanges;
    private volatile int pendingCount;

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        if (!"ranchu".equals(Build.HARDWARE) || !TEST_PACKAGE.equals(getPackageName()) || !validIntent(getIntent())) {
            finish();
            return;
        }
        valid = true;
        setRequestedOrientation(getIntent().getBooleanExtra("landscapeFixture", false) ?
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        generation = getIntent().getStringExtra("generation");
        mode = getIntent().getStringExtra("mode");
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        root.setContentDescription("Lip sticker editor " + generation);
        editor = new EditText(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                InputConnection base = super.onCreateInputConnection(outAttrs);
                return StickerEditorFixtureActivity.this.wrapInputConnection(base, outAttrs, this);
            }
        };
        editor.setId(android.view.View.generateViewId());
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setContentDescription("Synthetic sticker editor");
        if("composition".equals(mode))baseline="SEED";
        if("stale-caret".equals(mode)||"unknown-offset".equals(mode)){
            char[] repeated=new char[160];java.util.Arrays.fill(repeated,'a');baseline=new String(repeated);
        }
        editor.setText(baseline);editor.setSelection(baseline.isEmpty()?0:baseline.length()/2);
        preferredEditor=editor;
        editor.setOnFocusChangeListener((view,focused)->{if(focused){preferredEditor=editor;focusedEditor="PRIMARY";}});
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { textChanges++; }
            @Override public void afterTextChanged(Editable s) { }
        });
        editor.setOnEditorActionListener((view, actionId, event) -> { editorActions++; return false; });
        root.addView(editor, new LinearLayout.LayoutParams(-1,
                (int) (80 * getResources().getDisplayMetrics().density)));
        if("editor-replacement".equals(mode)){
            replacementEditor=new EditText(this){
                @Override public InputConnection onCreateInputConnection(EditorInfo attrs){
                    return wrapInputConnection(super.onCreateInputConnection(attrs),attrs,this);
                }
            };
            replacementEditor.setId(View.generateViewId());replacementEditor.setInputType(InputType.TYPE_CLASS_TEXT);
            replacementEditor.setContentDescription("Synthetic replacement editor");
            replacementEditor.setOnFocusChangeListener((view,focused)->{if(focused){preferredEditor=replacementEditor;focusedEditor="REPLACEMENT";}});
            replacementEditor.addTextChangedListener(new TextWatcher(){
                @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                @Override public void onTextChanged(CharSequence s,int start,int before,int count){textChanges++;}
                @Override public void afterTextChanged(Editable text){}
            });
            replacementEditor.setOnEditorActionListener((view,action,event)->{editorActions++;return false;});
            root.addView(replacementEditor,new LinearLayout.LayoutParams(-1,(int)(80*getResources().getDisplayMetrics().density)));
        }
        if("composition".equals(mode)){
            addControl(root,"Begin synthetic composition",()->{
                if(primaryConnection==null)return;
                compositionStarts++;primaryConnection.setComposingRegion(0,baseline.length());notifyComposition();
            });
            addControl(root,"End synthetic composition",()->{
                if(primaryConnection==null)return;
                compositionEnds++;primaryConnection.finishComposingText();notifyComposition();
            });
        }
        if("stale-caret".equals(mode)){
            addControl(root,"Arm synthetic revalidation change",this::armSyntheticRevalidationProbe);
            addControl(root,"Disarm synthetic revalidation change",()->{probeArmed=false;});
        }
        if("unknown-offset".equals(mode)){
            addControl(root,"Use unknown surrounding offset",()->{unknownOffsetResponses=0;unknownOffset=true;});
            addControl(root,"Use actual surrounding offset",()->{unknownOffset=false;});
        }
        setContentView(root);
    }

    private boolean validIntent(Intent intent) {
        if (intent == null) return false;
        String owner = intent.getStringExtra("owner");
        String gen = intent.getStringExtra("generation");
        String requestedMode = intent.getStringExtra("mode");
        try {
            if (owner == null || !UUID.fromString(owner).toString().equals(owner)) return false;
        } catch (IllegalArgumentException e) { return false; }
        if (gen == null || gen.trim().isEmpty() || gen.length() > 128) return false;
        if (!"accept".equals(requestedMode) && !"refuse".equals(requestedMode) &&
                !"refuse-after-read".equals(requestedMode) && !"async".equals(requestedMode) &&
                !"unsupported".equals(requestedMode) && !"composition".equals(requestedMode) &&
                !"editor-replacement".equals(requestedMode) && !"window-replacement".equals(requestedMode) &&
                !"stale-caret".equals(requestedMode) && !"unknown-offset".equals(requestedMode)) return false;
        String phase = intent.getStringExtra("phase");
        return phase == null || (!phase.trim().isEmpty() && phase.length() <= 128);
    }

    private void addControl(LinearLayout root,String description,Runnable action){
        Button button=new Button(this);button.setText(description);button.setContentDescription(description);
        button.setFocusable(false);button.setFocusableInTouchMode(false);button.setMinHeight((int)(48*getResources().getDisplayMetrics().density));
        button.setOnClickListener(view->action.run());root.addView(button,new LinearLayout.LayoutParams(-1,-2));
    }
    private void refreshComposition(){
        composingStart=BaseInputConnection.getComposingSpanStart(editor.getText());
        composingEnd=BaseInputConnection.getComposingSpanEnd(editor.getText());
    }
    private void notifyComposition(){
        refreshComposition();getSystemService(InputMethodManager.class).updateSelection(editor,editor.getSelectionStart(),editor.getSelectionEnd(),composingStart,composingEnd);
    }
    private InputConnection wrapInputConnection(InputConnection base, EditorInfo outAttrs,EditText target) {
        if (base == null || !valid) return base;
        inputConnections++;if(target==editor)primaryConnection=base;
        outAttrs.contentMimeTypes = "unsupported".equals(mode) ? null : IMAGE_MIMES.clone();
        return new InputConnectionWrapper(base, false) {
            @Override public boolean finishComposingText(){
                imeCompositionFinishes++;boolean accepted=super.finishComposingText();if(target==editor)refreshComposition();return accepted;
            }
            @Override public SurroundingText getSurroundingText(int before,int after,int flags){
                if(target==editor&&probeArmed){
                    int ordinal=++probeReads;
                    if(before!=32||after!=32||flags!=0)probeUnexpectedReads++;
                    if(ordinal==2){
                        probeArmed=false;
                        if(before==32&&after==32&&flags==0&&Looper.myLooper()==Looper.getMainLooper()){
                            int original=target.getSelectionStart();target.setSelection(Math.min(target.length(),original+1));
                            probeSelectionDelta=target.getSelectionStart()-original;probeTriggerOrdinal=ordinal;probeTriggered=probeSelectionDelta==1;
                        }
                    }
                }
                SurroundingText actual=super.getSurroundingText(before,after,flags);
                if(target==editor&&unknownOffset&&actual!=null){
                    unknownOffsetResponses++;
                    return new SurroundingText(actual.getText(),actual.getSelectionStart(),actual.getSelectionEnd(),-1);
                }
                return actual;
            }
            @Override public boolean commitContent(InputContentInfo inputContentInfo, int inputFlags, Bundle opts) {
                if (!valid || inputContentInfo == null) return false;
                invocations++;
                flags = inputFlags;
                if (pendingCount == 0) lastUri = inputContentInfo.getContentUri();
                String submittedMime = supportedMime(inputContentInfo);
                mime = submittedMime == null ? "" : submittedMime;
                if ("refuse".equals(mode) || "unsupported".equals(mode) || submittedMime == null) return false;
                if ("async".equals(mode)) return enqueueAsync(inputContentInfo, submittedMime);
                ReadOutcome outcome = requestAndRead(inputContentInfo, submittedMime);
                return !"refuse-after-read".equals(mode) && outcome.success;
            }
        };
    }

    private static String supportedMime(InputContentInfo content) {
        try {
            android.content.ClipDescription description = content.getDescription();
            if (description == null) return null;
            for (String supported : IMAGE_MIMES) {
                for (int i = 0; i < description.getMimeTypeCount(); i++) {
                    if (supported.equals(description.getMimeType(i))) return supported;
                }
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private boolean enqueueAsync(InputContentInfo content, String submittedMime) {
        synchronized (stateLock) {
            if (destroyed || pendingCount != 0) return false;
            pendingCount = 1;
            workerStarted = false;
            delayedRead = () -> {
                synchronized (stateLock) {
                    delayedRead = null;
                    if (destroyed) { pendingCount = 0; return; }
                    workerStarted = true;
                    worker = new Thread(() -> {
                        try { requestAndRead(content, submittedMime); }
                        finally {
                            synchronized (stateLock) { pendingCount = 0; stateLock.notifyAll(); }
                        }
                    }, "lip-sticker-editor-read");
                    worker.start();
                }
            };
            mainHandler.postDelayed(delayedRead, 400L);
            return true;
        }
    }

    private ReadOutcome requestAndRead(InputContentInfo content, String submittedMime) {
        boolean ownsPermission = false;
        ReadOutcome outcome = new ReadOutcome();
        Uri requestUri = null;
        mime = submittedMime;
        bytes = 0;
        sha256 = "";
        errorClass = "";
        grantObserved = false;
        grantBeforeRequest = null;
        grantAbsentAfterRelease = null;
        grantObservationError = "";
        releaseCompleted = false;
        try {
            requestUri = content.getContentUri();
            lastUri = requestUri;
            Boolean absentBeforeRequest = observeReadGrantAbsent(requestUri);
            grantBeforeRequest = absentBeforeRequest == null ? null : !absentBeforeRequest;
            content.requestPermission();
            ownsPermission = true;
            Boolean absent = observeReadGrantAbsent(requestUri);
            grantObserved = Boolean.FALSE.equals(absent);
            if (!grantObserved) throw new SecurityException();
            readStarted++;
            outcome = readBounded(requestUri, submittedMime);
            bytes = outcome.bytes;
            sha256 = outcome.sha;
            errorClass = outcome.error;
        } catch (Exception e) {
            outcome = ReadOutcome.failure(bytes, safeErrorClass(e));
            bytes = outcome.bytes;
            sha256 = "";
            errorClass = outcome.error;
        } finally {
            if (ownsPermission) {
                try { content.releasePermission(); releaseCompleted = true; }
                catch (RuntimeException e) { releaseCompleted = false; if (errorClass.isEmpty()) errorClass = safeErrorClass(e); }
                grantAbsentAfterRelease = observeReadGrantAbsent(requestUri);
            }
            readCompleted++;
        }
        return outcome;
    }

    private ReadOutcome readBounded(Uri uri, String submittedMime) {
        int total = 0;
        String failure = "";
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { return ReadOutcome.failure(0, "NoSuchAlgorithmException"); }
        ParcelFileDescriptor descriptor = null;
        InputStream input = null;
        try {
            descriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) throw new IOException();
            input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            descriptor = null;
            byte[] buffer = new byte[BUFFER_BYTES];
            while (total < MAX_PROBE_BYTES) {
                int request = Math.min(buffer.length, MAX_PROBE_BYTES - total);
                int count = input.read(buffer, 0, request);
                if (count < 0) break;
                if (count == 0) throw new IOException();
                total += count;
                if (total > MAX_BYTES) { failure = "IOException"; break; }
                digest.update(buffer, 0, count);
            }
        } catch (Exception e) {
            failure = safeErrorClass(e);
        } finally {
            if (input != null) {
                try { input.close(); }
                catch (Exception e) { if (failure.isEmpty()) failure = safeErrorClass(e); }
            }
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
        }
        if (!failure.isEmpty()) return ReadOutcome.failure(total, failure);
        String hash = hex(digest.digest());
        return new ReadOutcome(true, total, hash, "");
    }

    /** Cancels only work that has not entered the worker; running reader owns its permission/FD until join. */
    public boolean closePending() {
        Thread toJoin = null;
        synchronized (stateLock) {
            if (delayedRead != null && !workerStarted) {
                mainHandler.removeCallbacks(delayedRead);
                delayedRead = null;
                pendingCount = 0;
            }
            toJoin = worker;
        }
        if (toJoin == null || toJoin == Thread.currentThread()) return pendingCount == 0;
        try { toJoin.join(TimeUnit.SECONDS.toMillis(5)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return !toJoin.isAlive();
    }

    @Override protected void onDestroy() {
        synchronized (stateLock) { destroyed = true; }
        closePending();
        super.onDestroy();
    }

    /** TEST-fixture preparation only: called on its UI thread between a real image DOWN and UP. */
    public boolean armSyntheticRevalidationProbe(){
        if(!valid||!"stale-caret".equals(mode)||Looper.myLooper()!=Looper.getMainLooper()||isFinishing()||isDestroyed())return false;
        probeReads=0;probeUnexpectedReads=0;probeTriggerOrdinal=0;probeSelectionDelta=0;probeTriggered=false;probeArmed=true;
        return true;
    }

    public String syntheticText() { return editor == null ? "" : editor.getText().toString(); }
    public boolean syntheticTextUnchanged(){return editor!=null&&baseline.contentEquals(editor.getText())&&(replacementEditor==null||replacementEditor.length()==0);}

    public JSONObject snapshot() {
        JSONObject out = new JSONObject();
        Boolean absentNow = observeReadGrantAbsent(lastUri);
        try {
            out.put("invocations", invocations).put("readStarted", readStarted).put("readCompleted", readCompleted)
                    .put("bytes", bytes).put("sha256", SAFE_HASH.matcher(sha256).matches() ? sha256 : "")
                    .put("mime", "image/png".equals(mime) || "image/webp".equals(mime) ? mime : "")
                    .put("errorClass", safeClassToken(errorClass)).put("flags", flags)
                    .put("grantObserved", grantObserved).put("releaseCompleted", releaseCompleted)
                    .put("hasReceivedUri", lastUri != null)
                    .put("grantBeforeRequest", grantBeforeRequest == null ? JSONObject.NULL : grantBeforeRequest)
                    .put("grantAbsentAfterRelease", grantAbsentAfterRelease == null ? JSONObject.NULL : grantAbsentAfterRelease)
                    .put("grantAbsentNow", absentNow == null ? JSONObject.NULL : absentNow)
                    .put("grantObservationError", safeClassToken(grantObservationError))
                    .put("editorActions", editorActions).put("textChanges", textChanges)
                    .put("inputConnections",inputConnections).put("focusedEditor",focusedEditor)
                    .put("compositionStarts",compositionStarts).put("compositionEnds",compositionEnds).put("imeCompositionFinishes",imeCompositionFinishes)
                    .put("composingStart",composingStart).put("composingEnd",composingEnd)
                    .put("probeArmed",probeArmed).put("probeReads",probeReads).put("probeUnexpectedReads",probeUnexpectedReads)
                    .put("probeTriggered",probeTriggered).put("probeTriggerOrdinal",probeTriggerOrdinal).put("probeSelectionDelta",probeSelectionDelta)
                    .put("unknownOffset",unknownOffset).put("unknownOffsetResponses",unknownOffsetResponses)
                    .put("workerAlive", worker != null && worker.isAlive()).put("pendingCount", pendingCount);
        } catch (org.json.JSONException ignored) { }
        return out;
    }

    /** Returns true only when the OS reports no read grant; null means the query itself failed. */
    private Boolean observeReadGrantAbsent(Uri uri) {
        if (uri == null) return null;
        try {
            boolean granted = checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
            return !granted;
        } catch (RuntimeException e) {
            if (grantObservationError.isEmpty()) grantObservationError = safeErrorClass(e);
            return null;
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && editor != null) {
            EditText target=preferredEditor==null?editor:preferredEditor;
            target.requestFocus();
            target.post(() -> getSystemService(InputMethodManager.class).showSoftInput(target, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private static String safeErrorClass(Throwable error) { return safeClassToken(error.getClass().getSimpleName()); }
    private static String safeClassToken(String value) {
        return value != null && value.matches("[A-Za-z0-9]{1,64}") ? value : "";
    }
    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static final class ReadOutcome {
        final boolean success;
        final int bytes;
        final String sha;
        final String error;
        ReadOutcome() { this(false, 0, "", ""); }
        ReadOutcome(boolean success, int bytes, String sha, String error) {
            this.success = success; this.bytes = bytes; this.sha = sha; this.error = error;
        }
        static ReadOutcome failure(int bytes, String error) { return new ReadOutcome(false, bytes, "", error); }
    }
}

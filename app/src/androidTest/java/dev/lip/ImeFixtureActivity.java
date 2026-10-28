package dev.lip;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Test APK only. No Kotlin dependency, microphone, network, private text or message recipient. */
public final class ImeFixtureActivity extends Activity {
    public EditText normal, password, number, signedNumber, phone, time, multiline, custom, incognito;
    public boolean foreignCompositionAfterSpace, foreignCompositionInsteadOfRegion;
    public int regionRequests, finishesAtForeignComposition;
    private void foreignComposition(){hostConnection.setComposingRegion(1,2);finishesAtForeignComposition=finishCompositionCalls;getSystemService(android.view.inputmethod.InputMethodManager.class).updateSelection(normal,normal.getSelectionStart(),normal.getSelectionEnd(),1,2);}
    public int foreignStart(){return android.view.inputmethod.BaseInputConnection.getComposingSpanStart(normal.getText());}
    public int foreignEnd(){return android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(normal.getText());}
    public void clearForeignComposition(){hostConnection.finishComposingText();}
    public int cursorContextReads,passwordCursorContextReads,incognitoCursorContextReads,cursorArrowDispatches,cursorArrowDowns;
    // Separate text-panel witnesses; existing clipboard/cursor test counters retain their meaning.
    public int editingSelectAllCommands,editingCutCommands,editingCopyCommands,editingPasteCommands,editingNavigationCommands,editingNavigationDowns;
    public boolean refuseEditingContextCommands,refuseEditingNavigation;
    public int editingMultilineConnectionCreations,editingMultilineNavigationCommands,editingMultilineNavigationDowns;
    public int editingSyntheticShiftDowns,editingSyntheticShiftUps;
    public int editingPasswordConnectionCreations,editingIncognitoConnectionCreations;
    private long editingPrivateConnectionRevision;
    private EditText editingPrivateConnectedField;
    private synchronized long editingPrivateConnectionOpened(EditText field){editingPrivateConnectedField=field;return ++editingPrivateConnectionRevision;}
    private synchronized void editingPrivateConnectionClosed(long token){if(token==editingPrivateConnectionRevision)editingPrivateConnectedField=null;}
    public synchronized long editingPrivateConnectionToken(EditText field){return editingPrivateConnectedField==field?editingPrivateConnectionRevision:0;}

    public int editingShiftMetaState(){return android.text.method.MetaKeyKeyListener.getMetaState(normal.getText(),android.view.KeyEvent.META_SHIFT_ON);}
    public static final class EditingPrivateAudit {
        int selection,commit,delete,finishComposing,composingRegion,composingText,menu,key;
        int lastSelectionStart=-1,lastSelectionEnd=-1;
    }
    private final EditingPrivateAudit editingPasswordAudit=new EditingPrivateAudit(),editingIncognitoAudit=new EditingPrivateAudit();
    public int[] editingPrivateAuditSnapshot(EditText field){
        EditingPrivateAudit audit=field==password?editingPasswordAudit:field==incognito?editingIncognitoAudit:null;
        if(audit==null)throw new IllegalArgumentException("Private fixture field required");
        return new int[]{audit.selection,audit.commit,audit.delete,audit.finishComposing,audit.composingRegion,audit.composingText,audit.menu,audit.key,audit.lastSelectionStart,audit.lastSelectionEnd};
    }
    private android.view.inputmethod.InputConnection auditPrivateConnection(android.view.inputmethod.InputConnection base,EditingPrivateAudit audit){
        return new android.view.inputmethod.InputConnectionWrapper(base,false){
            @Override public boolean setSelection(int start,int end){audit.selection++;audit.lastSelectionStart=start;audit.lastSelectionEnd=end;return super.setSelection(start,end);}
            @Override public boolean commitText(CharSequence text,int position){audit.commit++;return super.commitText(text,position);}
            @Override public boolean commitText(CharSequence text,int position,android.view.inputmethod.TextAttribute attributes){audit.commit++;return super.commitText(text,position,attributes);}
            @Override public boolean deleteSurroundingText(int before,int after){audit.delete++;return super.deleteSurroundingText(before,after);}
            @Override public boolean deleteSurroundingTextInCodePoints(int before,int after){audit.delete++;return super.deleteSurroundingTextInCodePoints(before,after);}
            @Override public boolean finishComposingText(){audit.finishComposing++;return super.finishComposingText();}
            @Override public boolean setComposingRegion(int start,int end){audit.composingRegion++;return super.setComposingRegion(start,end);}
            @Override public boolean setComposingRegion(int start,int end,android.view.inputmethod.TextAttribute attributes){audit.composingRegion++;return super.setComposingRegion(start,end,attributes);}
            @Override public boolean setComposingText(CharSequence text,int position){audit.composingText++;return super.setComposingText(text,position);}
            @Override public boolean setComposingText(CharSequence text,int position,android.view.inputmethod.TextAttribute attributes){audit.composingText++;return super.setComposingText(text,position,attributes);}
            @Override public boolean performContextMenuAction(int id){audit.menu++;return super.performContextMenuAction(id);}
            @Override public boolean sendKeyEvent(android.view.KeyEvent event){audit.key++;return super.sendKeyEvent(event);}
        };
    }
    public void beginEditingComposition(int start,int end){
        if(hostConnection==null||getCurrentFocus()!=normal||start<0||end<start||end>normal.length())throw new IllegalStateException("Synthetic composition premise unavailable");
        if(!hostConnection.setComposingRegion(start,end))throw new IllegalStateException("Synthetic composition refused");
        getSystemService(android.view.inputmethod.InputMethodManager.class).updateSelection(normal,normal.getSelectionStart(),normal.getSelectionEnd(),start,end);
    }
    private static boolean editingNavigationKey(int code){return code==android.view.KeyEvent.KEYCODE_DPAD_LEFT||code==android.view.KeyEvent.KEYCODE_DPAD_RIGHT||code==android.view.KeyEvent.KEYCODE_DPAD_UP||code==android.view.KeyEvent.KEYCODE_DPAD_DOWN||code==android.view.KeyEvent.KEYCODE_MOVE_HOME||code==android.view.KeyEvent.KEYCODE_MOVE_END;}
    public boolean refuseWordSelection,delayWordSelection,refuseWordCommit,ambiguousWordCommit;
    public int wordSelectRequests,wordDeleteCommits;
    public int slowWordReadAfter=Integer.MAX_VALUE;
    public boolean slowInitialWordRead;
    public int lastCustomAction;
    public int actions;
    public int gifRequests, gifBytes, gifFlags, finishCompositionCalls;
    public String gifSha, gifError, gifSource;
    public boolean refuseGif;
    public android.net.Uri gifUri;
    private android.view.inputmethod.InputConnection hostConnection;
    // Explicit agent-menu fixture only: metadata witness, never editor text.
    private final String agentFocusActivity = java.util.UUID.randomUUID().toString();
    private long agentFocusConnection = 0;
    private static final String AGENT_FOCUS_ACTION = "dev.lip.agent.TEST_FOCUS_PROBE";

    private TextView compositionStatus;
    private void inspectComposition(){if(compositionStatus!=null)compositionStatus.setContentDescription("Synthetic composition "+android.view.inputmethod.BaseInputConnection.getComposingSpanStart(normal.getText())+":"+android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(normal.getText())+" finishes="+finishCompositionCalls+" gifs="+gifRequests);}
    public boolean refuseComposingRegion, delayComposingRegion, refuseClipboardActions;
    public Runnable delayedRegion;
    public int regionRefusals, composingWrites, clipboardHostCommands, inputConnectionCreations;
    @Override public void onCreate(Bundle state) {
        if (getIntent().getBooleanExtra("darkFixture",false)) setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        if (getIntent().getBooleanExtra("landscapeFixture",false)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(16,16,16,16);
        TextView title = new TextView(this); title.setText("Disposable Lip IME editor · synthetic text only");
        String generation=getIntent().getStringExtra("fixtureGeneration");
        if(generation!=null)title.setContentDescription("Lip synthetic generation "+generation); root.addView(title);
        normal = new EditText(this) {
            @Override public boolean onKeyDown(int code,android.view.KeyEvent event){if(code==android.view.KeyEvent.KEYCODE_DPAD_LEFT||code==android.view.KeyEvent.KEYCODE_DPAD_RIGHT)cursorArrowDowns++;return super.onKeyDown(code,event);}
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(EditorInfo out) {
                inputConnectionCreations++;
                final long agentConnection = ++agentFocusConnection;
                final String agentGeneration = getIntent().getStringExtra("fixtureGeneration");
                android.view.inputmethod.InputConnection base=super.onCreateInputConnection(out);hostConnection=base;
                out.contentMimeTypes=ImeFixtureActivity.this.getIntent().getBooleanExtra("gifEditor",false)?new String[]{"image/gif"}:null;
                return base==null?null:new android.view.inputmethod.InputConnectionWrapper(base,false) {
                    private boolean agentClosed = false;
                    @Override public void closeConnection() {
                        agentClosed = true;
                        if (agentFocusConnection == agentConnection) agentFocusConnection++;
                        super.closeConnection();
                    }
                    @Override public boolean performPrivateCommand(String action, Bundle data) {
                        if (!AGENT_FOCUS_ACTION.equals(action)) {
                            if (data != null && data.containsKey("agentFocusReceiver")) return false;
                            return super.performPrivateCommand(action,data);
                        }
                        if (!getIntent().getBooleanExtra("agentFocusProbe",false) || data == null ||
                            agentGeneration == null || !agentGeneration.equals(data.getString("generation")) ||
                            !agentGeneration.equals(getIntent().getStringExtra("fixtureGeneration"))) return false;
                        final String nonce = data.getString("nonce");
                        final android.os.ResultReceiver receiver = data.getParcelable("agentFocusReceiver",android.os.ResultReceiver.class);
                        if (nonce == null || nonce.isEmpty() || nonce.length() > 128 || receiver == null) return false;
                        Runnable response = () -> {
                            if (agentClosed || agentFocusConnection != agentConnection) return;
                            Bundle reply = new Bundle();
                            reply.putString("nonce",nonce); reply.putString("generation",agentGeneration);
                            reply.putString("activityIncarnation",agentFocusActivity);
                            reply.putLong("connectionIncarnation",agentConnection);
                            reply.putBoolean("attached",normal.isAttachedToWindow());
                            reply.putBoolean("activityAlive",!isFinishing() && !isDestroyed());
                            reply.putBoolean("currentFocusNormal",getCurrentFocus() == normal);
                            reply.putBoolean("rootFocusNormal",normal.getRootView().findFocus() == normal);
                            reply.putBoolean("hasFocus",normal.hasFocus());
                            reply.putInt("inputType",normal.getInputType()); reply.putInt("imeOptions",normal.getImeOptions());
                            receiver.send(1,reply); // Nonblocking metadata-only callback.
                        };
                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) response.run();
                        else normal.post(response);
                        return true;
                    }
                    @Override public boolean sendKeyEvent(android.view.KeyEvent event){
                        if(event.getKeyCode()==android.view.KeyEvent.KEYCODE_DPAD_LEFT||event.getKeyCode()==android.view.KeyEvent.KEYCODE_DPAD_RIGHT)cursorArrowDispatches++;
                        if(event.getDeviceId()==android.view.KeyCharacterMap.VIRTUAL_KEYBOARD&&(event.getKeyCode()==android.view.KeyEvent.KEYCODE_SHIFT_LEFT||event.getKeyCode()==android.view.KeyEvent.KEYCODE_SHIFT_RIGHT)){
                            if(event.getAction()==android.view.KeyEvent.ACTION_DOWN)editingSyntheticShiftDowns++;
                            if(event.getAction()==android.view.KeyEvent.ACTION_UP)editingSyntheticShiftUps++;
                        }
                        if(editingNavigationKey(event.getKeyCode())){editingNavigationCommands++;if(event.getAction()==android.view.KeyEvent.ACTION_DOWN)editingNavigationDowns++;if(refuseEditingNavigation)return false;}
                        return super.sendKeyEvent(event);
                    }
                    @Override public CharSequence getTextBeforeCursor(int n,int flags){cursorContextReads++;return super.getTextBeforeCursor(n,flags);}
                    @Override public CharSequence getTextAfterCursor(int n,int flags){cursorContextReads++;return super.getTextAfterCursor(n,flags);}
                    @Override public android.view.inputmethod.SurroundingText getSurroundingText(int before,int after,int flags){cursorContextReads++;if(slowInitialWordRead){slowInitialWordRead=false;android.os.SystemClock.sleep(300);}if(wordSelectRequests>=slowWordReadAfter)android.os.SystemClock.sleep(300);return super.getSurroundingText(before,after,flags);}
                    @Override public android.view.inputmethod.ExtractedText getExtractedText(android.view.inputmethod.ExtractedTextRequest request,int flags){cursorContextReads++;return super.getExtractedText(request,flags);}
                    @Override public boolean setSelection(int start,int end){
                        if(start!=end){
                            wordSelectRequests++;
                            if(refuseWordSelection)return false;
                            if(delayWordSelection){normal.postDelayed(()->super.setSelection(start,end),400);return true;}
                        }
                        return super.setSelection(start,end);
                    }
                    @Override public boolean commitText(CharSequence text,int position){
                        boolean deletion="".contentEquals(text)&&normal.getSelectionStart()!=normal.getSelectionEnd();
                        if(deletion){wordDeleteCommits++;if(refuseWordCommit)return false;}
                        boolean result=super.commitText(text,position);
                        if(deletion&&ambiguousWordCommit)return false;
                        if(foreignCompositionAfterSpace&&" ".contentEquals(text)){foreignCompositionAfterSpace=false;foreignComposition();}
                        return result;
                    }
                    @Override public boolean setComposingRegion(int start,int end) {
                        regionRequests++;
                        if(foreignCompositionInsteadOfRegion){foreignCompositionInsteadOfRegion=false;foreignComposition();return true;}
                        if(refuseComposingRegion){regionRefusals++;return false;}
                        if(delayComposingRegion){delayedRegion=()->super.setComposingRegion(start,end);return true;}
                        return super.setComposingRegion(start,end);
                    }
                    @Override public boolean finishComposingText(){finishCompositionCalls++;return super.finishComposingText();}
                    @Override public boolean commitContent(android.view.inputmethod.InputContentInfo content,int flags,Bundle opts){
                        gifRequests++;gifUri=content.getContentUri();gifFlags=flags;gifSource=String.valueOf(content.getLinkUri());
                        if(refuseGif)return false;
                        try{
                            content.requestPermission();
                            try(java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(getContentResolver().openFileDescriptor(content.getContentUri(),"r"))){
                                byte[] bytes=input.readNBytes(2_100_001);if(bytes.length>2_100_000)throw new java.io.IOException("Oversized fixture GIF");
                                gifBytes=bytes.length;StringBuilder hash=new StringBuilder();
                                for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",value&255));gifSha=hash.toString();
                            }
                            return true;
                        }catch(Exception error){gifError=error.getClass().getSimpleName()+": "+error.getMessage();return false;}
                        finally{content.releasePermission();}
                    }
                    @Override public boolean performContextMenuAction(int id) {
                        if(id==android.R.id.copy||id==android.R.id.paste){clipboardHostCommands++;if(refuseClipboardActions)return false;}
                        if(id==android.R.id.selectAll)editingSelectAllCommands++;
                        if(id==android.R.id.cut)editingCutCommands++;
                        if(id==android.R.id.copy)editingCopyCommands++;
                        if(id==android.R.id.paste)editingPasteCommands++;
                        if(refuseEditingContextCommands&&(id==android.R.id.selectAll||id==android.R.id.cut||id==android.R.id.copy||id==android.R.id.paste))return false;
                        return super.performContextMenuAction(id);
                    }
                    @Override public boolean setComposingText(CharSequence text,int position) {
                        composingWrites++;return super.setComposingText(text,position);
                    }
                };
            }
        }; normal.setContentDescription("Synthetic ordinary editor"); normal.setInputType(InputType.TYPE_CLASS_TEXT); normal.setImeOptions(EditorInfo.IME_ACTION_SEND);
        normal.setOnEditorActionListener((v, action, event) -> { actions++; return true; });
        password = new EditText(this){
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(EditorInfo out){
                editingPasswordConnectionCreations++;
                android.view.inputmethod.InputConnection base=super.onCreateInputConnection(out);
                final long token=base==null?0:editingPrivateConnectionOpened(this);
                return base==null?null:new android.view.inputmethod.InputConnectionWrapper(auditPrivateConnection(base,editingPasswordAudit),false){
                    @Override public void closeConnection(){editingPrivateConnectionClosed(token);super.closeConnection();}
                    @Override public CharSequence getTextBeforeCursor(int n,int flags){passwordCursorContextReads++;return super.getTextBeforeCursor(n,flags);}
                    @Override public CharSequence getTextAfterCursor(int n,int flags){passwordCursorContextReads++;return super.getTextAfterCursor(n,flags);}
                    @Override public android.view.inputmethod.SurroundingText getSurroundingText(int before,int after,int flags){passwordCursorContextReads++;return super.getSurroundingText(before,after,flags);}
                    @Override public android.view.inputmethod.ExtractedText getExtractedText(android.view.inputmethod.ExtractedTextRequest request,int flags){passwordCursorContextReads++;return super.getExtractedText(request,flags);}
                };
            }
        }; password.setContentDescription("Synthetic password editor"); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        number = new EditText(this); number.setContentDescription("Synthetic number editor"); number.setInputType(InputType.TYPE_CLASS_NUMBER);
        int height = (int)(64 * getResources().getDisplayMetrics().density);
        signedNumber = new EditText(this); signedNumber.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        phone = new EditText(this); phone.setInputType(InputType.TYPE_CLASS_PHONE);
        time = new EditText(this); time.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        multiline = new EditText(this){
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(EditorInfo out){
                editingMultilineConnectionCreations++;
                android.view.inputmethod.InputConnection base=super.onCreateInputConnection(out);
                return base==null?null:new android.view.inputmethod.InputConnectionWrapper(base,false){
                    @Override public boolean sendKeyEvent(android.view.KeyEvent event){
                        if(editingNavigationKey(event.getKeyCode())){editingMultilineNavigationCommands++;if(event.getAction()==android.view.KeyEvent.ACTION_DOWN)editingMultilineNavigationDowns++;}
                        return super.sendKeyEvent(event);
                    }
                };
            }
        }; multiline.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        multiline.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        multiline.setOnEditorActionListener((v, action, event) -> { actions++; return true; });
        custom = new EditText(this); custom.setInputType(InputType.TYPE_CLASS_TEXT); custom.setImeActionLabel("Run fixture", 42);
        custom.setOnEditorActionListener((v, action, event) -> { lastCustomAction = action; return true; });
        incognito = new EditText(this){
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(EditorInfo out){
                editingIncognitoConnectionCreations++;
                android.view.inputmethod.InputConnection base=super.onCreateInputConnection(out);
                final long token=base==null?0:editingPrivateConnectionOpened(this);
                return base==null?null:new android.view.inputmethod.InputConnectionWrapper(auditPrivateConnection(base,editingIncognitoAudit),false){
                    @Override public void closeConnection(){editingPrivateConnectionClosed(token);super.closeConnection();}
                    @Override public CharSequence getTextBeforeCursor(int n,int flags){incognitoCursorContextReads++;return super.getTextBeforeCursor(n,flags);}
                    @Override public CharSequence getTextAfterCursor(int n,int flags){incognitoCursorContextReads++;return super.getTextAfterCursor(n,flags);}
                    @Override public android.view.inputmethod.SurroundingText getSurroundingText(int before,int after,int flags){incognitoCursorContextReads++;return super.getSurroundingText(before,after,flags);}
                    @Override public android.view.inputmethod.ExtractedText getExtractedText(android.view.inputmethod.ExtractedTextRequest request,int flags){incognitoCursorContextReads++;return super.getExtractedText(request,flags);}
                };
            }
        }; incognito.setInputType(InputType.TYPE_CLASS_TEXT); incognito.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        for (EditText edit : new EditText[]{normal,password,number,signedNumber,phone,time,multiline,custom,incognito}) {
            // This explicit test mode makes three short native multiline rows visible;
            // all original fixture dimensions and single-line ordinary cases remain.
            int editHeight=getIntent().getBooleanExtra("textEditingFixture",false)&&edit==multiline?(int)(120*getResources().getDisplayMetrics().density):height;
            root.addView(edit,new LinearLayout.LayoutParams(-1,editHeight));
        }
        android.widget.Button compose=new android.widget.Button(this);compose.setText("Synthetic composition");compose.setContentDescription("Start synthetic host composition");
        compose.setFocusable(false);compose.setFocusableInTouchMode(false);
        compose.setOnClickListener(v->{if(hostConnection!=null)hostConnection.setComposingRegion(0,4);});root.addView(compose);
        android.widget.Button inspect=new android.widget.Button(this);inspect.setText("Inspect composition");inspect.setContentDescription("Inspect synthetic host composition");inspect.setFocusable(false);inspect.setOnClickListener(v->inspectComposition());root.addView(inspect);
        android.widget.Button clearComposition=new android.widget.Button(this);clearComposition.setText("Finish composition");clearComposition.setContentDescription("Finish synthetic host composition");clearComposition.setFocusable(false);clearComposition.setOnClickListener(v->{if(hostConnection!=null)hostConnection.finishComposingText();inspectComposition();});root.addView(clearComposition);
        compositionStatus=new TextView(this);root.addView(compositionStatus);inspectComposition();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.setFillViewport(true); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            android.graphics.Insets keyboard = insets.getInsets(android.view.WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom)); return insets;
        });
        setContentView(scroll);
    }
}

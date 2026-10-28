package dev.lip;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayInputStream;

/** Local synthetic page only. No account, network, file/content access or Javascript interface. */
public final class WebImeFixtureActivity extends Activity {
    public WebView web;
    public volatile boolean ready;
    public volatile android.view.inputmethod.InputConnection lastConnection;
    public volatile int lastInputType, surroundingCalls, lastSurroundingOffset=-999, lastSurroundingSelection=-999;
    public final java.util.concurrent.atomic.AtomicInteger blockedRequests=new java.util.concurrent.atomic.AtomicInteger();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        String generation=getIntent().getStringExtra("fixtureGeneration");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        TextView title=new TextView(this);title.setText("Lip local Chromium editor · synthetic text only");title.setContentDescription("Lip web generation "+generation);
        root.addView(title);
        web=new WebView(this){
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info){
                android.view.inputmethod.InputConnection actual=super.onCreateInputConnection(info);lastInputType=info.inputType;
                lastConnection=actual==null?null:new android.view.inputmethod.InputConnectionWrapper(actual,false){
                    @Override public android.view.inputmethod.SurroundingText getSurroundingText(int before,int after,int flags){
                        android.view.inputmethod.SurroundingText result=super.getSurroundingText(before,after,flags);surroundingCalls++;
                        lastSurroundingOffset=result==null?-999:result.getOffset();lastSurroundingSelection=result==null?-999:result.getSelectionStart();return result;
                    }
                };
                return lastConnection;
            }
        };
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setBlockNetworkLoads(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){ready=true;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){blockedRequests.incrementAndGet();return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                blockedRequests.incrementAndGet();return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1f));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));return insets;
        });
        setContentView(root);
        web.loadDataWithBaseURL("https://lip-fixture.invalid/",PAGE,"text/html","UTF-8",null);
    }
    private static final String PAGE="""
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none';script-src 'unsafe-inline';style-src 'unsafe-inline';form-action 'none';base-uri 'none'">
        <style>body{margin:20px;font:20px sans-serif;background:white;color:black}#editor{box-sizing:border-box;width:100%;min-height:160px;font:24px sans-serif;border:2px solid #555;white-space:pre-wrap;padding:12px}button{margin-top:20px}</style></head>
        <body><form id="form"><div id="slot"></div><button type="submit">Synthetic send control</button></form><p id="identity"></p>
        <script>
        window.sent=0;
        document.getElementById('form').addEventListener('submit',e=>{window.sent++;e.preventDefault()});
        window.fixture={reset:(kind,value,start,end,automatic,identity)=>{
          let e=document.createElement(kind==='contenteditable'?'div':kind==='textarea'?'textarea':'input');
          e.id='editor';e.setAttribute('aria-label','Synthetic Chromium editor');
          e.setAttribute('autocomplete','on');e.setAttribute('autocorrect',automatic?'on':'off');e.spellcheck=automatic;
          if(kind==='contenteditable'){e.contentEditable='true';e.setAttribute('role','textbox');e.textContent=value}
          else{if(kind!=='textarea')e.type=kind;e.value=value}
          document.getElementById('slot').replaceChildren(e);document.getElementById('identity').textContent=identity;
          window.kind=kind;window.identity=identity;e.focus();
          if(kind==='contenteditable'){let r=document.createRange();let n=e.firstChild;if(n){r.setStart(n,start);r.setEnd(n,end)}else{r.selectNodeContents(e);r.collapse(false)}let s=window.getSelection();s.removeAllRanges();s.addRange(r)}
          else if(kind!=='email')e.setSelectionRange(start,end);
          return identity;
        },place:(start,end)=>{
          let e=document.getElementById('editor');e.focus();
          if(e.isContentEditable){let r=document.createRange();let n=e.firstChild;if(n){r.setStart(n,start);r.setEnd(n,end)}else{r.selectNodeContents(e);r.collapse(false)}let s=window.getSelection();s.removeAllRanges();s.addRange(r)}
          else if(window.kind!=='email')e.setSelectionRange(start,end);
          return window.identity;
        },inspect:()=>{let e=document.getElementById('editor');return {text:e.isContentEditable?e.textContent:e.value,sent:window.sent,identity:window.identity,kind:window.kind,start:e.isContentEditable?window.getSelection().anchorOffset:e.selectionStart,end:e.isContentEditable?window.getSelection().focusOffset:e.selectionEnd}}};
        fixture.reset('textarea','',0,0,true,'initial');
        </script></body></html>
        """;
    @Override protected void onDestroy(){if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}

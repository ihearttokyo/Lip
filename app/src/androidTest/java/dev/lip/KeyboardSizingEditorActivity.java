package dev.lip;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Single disposable real editor; explicit normal Android focus/show path, no mock connection. */
public final class KeyboardSizingEditorActivity extends Activity {
    private EditText editor;
    @Override public void onCreate(Bundle state){
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        if(!"ranchu".equals(android.os.Build.HARDWARE)||!"dev.lip.android.sizingfixture.test".equals(getPackageName())){finish();return;}
        if(getIntent().getBooleanExtra("landscapeFixture",false))
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,16,16,16);
        TextView title=new TextView(this);title.setText("Lip sizing · synthetic text only");
        title.setContentDescription("Lip synthetic generation "+getIntent().getStringExtra("fixtureGeneration"));root.addView(title);
        editor=new EditText(this);editor.setInputType(InputType.TYPE_CLASS_TEXT);editor.setContentDescription("Synthetic ordinary editor");
        root.addView(editor,new LinearLayout.LayoutParams(-1,(int)(80*getResources().getDisplayMetrics().density)));
        root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        setContentView(root);
    }
    public String syntheticText(){return editor.getText().toString();}
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        if(focused&&editor!=null){editor.requestFocus();editor.post(()->getSystemService(InputMethodManager.class).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT));}
    }
}

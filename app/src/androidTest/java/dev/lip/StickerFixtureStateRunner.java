package dev.lip;

import android.app.Activity;
import android.app.Instrumentation;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Build;
import android.provider.DocumentsContract;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;

/** Standalone TEST UID bootstrap; pure Java/SDK, no activity or production data. */
public final class StickerFixtureStateRunner extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
    @Override public void onStart(){
        JSONObject result=new JSONObject();boolean passed=false;
        try {
            String pkg="dev.lip.android.sizingfixture.test";
            if(!pkg.equals(getTargetContext().getPackageName())||!pkg.equals(getContext().getPackageName())||!"ranchu".equals(Build.HARDWARE))throw new IllegalStateException("Owned standalone fixture only");
            String owner=args.getString("owner");if(!UUID.fromString(owner).toString().equals(owner))throw new IllegalStateException("Owner required");
            File marker=new File(getTargetContext().getNoBackupFilesDir(),"sticker-ui-active-owner.json");
            if(marker.length()<1||marker.length()>256||Files.isSymbolicLink(marker.toPath()))throw new IllegalStateException("Bounded marker required");
            JSONObject source=new JSONObject(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8));
            if(source.length()!=2||source.getInt("version")!=1||!owner.equals(source.getString("owner")))throw new IllegalStateException("Exact marker required");
            android.net.Uri roots=DocumentsContract.buildRootsUri(pkg+".stickerfixtures");
            getTargetContext().getContentResolver().notifyChange(roots,null);
            try(Cursor cursor=getTargetContext().getContentResolver().query(roots,new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID},null,null,null)){
                if(cursor==null||cursor.getCount()!=1||!cursor.moveToFirst())throw new IllegalStateException("One current root required");
                String expected="lip-sticker-root-"+owner;
                if(!expected.equals(cursor.getString(0))||!expected.equals(cursor.getString(1)))throw new IllegalStateException("Root identity mismatch");
                result.put("rootId",expected);
            }
            result.put("owner",owner).put("testUid",android.os.Process.myUid());passed=true;
        }catch(Throwable e){try{result.put("failure",e.getClass().getSimpleName());}catch(Exception ignored){}}
        try{result.put("passed",passed);}catch(Exception ignored){}
        Bundle output=new Bundle();output.putString("fixtureResult",result.toString());finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,output);
    }
}

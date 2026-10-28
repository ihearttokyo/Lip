package dev.lip.testing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.TextView

/** DEBUG ONLY, non-exported, emulator-only fixture grant receiver. No data IO,
 * permission bypass or production regrant route; the system picker must approve. */
class HistoryGrantActivity:Activity(){
    var completed=false;private set
    var selected:Uri?=null;private set
    override fun onCreate(state:Bundle?){super.onCreate(state)
        check(Build.HARDWARE in listOf("ranchu","goldfish")&&Build.PRODUCT.contains("sdk"))
        setContentView(TextView(this).apply{text="Fixture document permission recovery"})
    }
    fun request(original:Uri){
        check(!completed&&original.scheme=="content"&&original.authority in listOf("com.android.providers.downloads.documents","com.android.externalstorage.documents"))
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI,original),7301)
    }
    @Deprecated("Test-only legacy result receiver")
    override fun onActivityResult(request:Int,result:Int,data:Intent?){super.onActivityResult(request,result,data)
        if(request==7301){selected=if(result==RESULT_OK)data?.data else null;completed=true}
    }
}

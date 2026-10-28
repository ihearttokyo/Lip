package dev.lip.keyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.File

/** Only immutable licensed public assets; never maps caller input onto app-private filesystem paths. */
class GifContentProvider:ContentProvider(){
    private val cacheLock=Any()
    override fun onCreate()=true
    private fun item(uri:Uri):GifItem{
        if(uri.scheme!="content"||uri.authority!=context!!.packageName+".gif"||uri.query!=null||uri.fragment!=null||uri.pathSegments.size!=2||uri.pathSegments[0]!="gif")throw FileNotFoundException("Unknown GIF")
        val name=uri.pathSegments[1]
        return GifCatalog.items.firstOrNull{name==it.id+".gif"}?:throw FileNotFoundException("Unknown GIF")
    }
    override fun getType(uri:Uri):String{item(uri);return "image/gif"}
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{
        if(mode!="r")throw FileNotFoundException("GIF provider is read only")
        val item=item(uri)
        return synchronized(cacheLock){
            val directory=File(context!!.cacheDir,"public-gif");if(!directory.isDirectory&&!directory.mkdirs())throw FileNotFoundException("GIF cache unavailable")
            val file=File(directory,item.sha256+".gif")
            fun valid()=file.isFile&&file.length()==item.bytes.toLong()&&
                java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}==item.sha256
            if(!valid()){
                val bytes=try{GifAssets.read(context!!,item)}catch(_:Exception){throw FileNotFoundException("GIF integrity unavailable")}
                val atomic=AtomicFile(file);val output=atomic.startWrite()
                try{output.write(bytes);atomic.finishWrite(output)}catch(error:Exception){atomic.failWrite(output);throw FileNotFoundException("GIF cache write unavailable")}
                if(!valid())throw FileNotFoundException("GIF cache verification failed")
            }
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
    override fun openAssetFile(uri:Uri,mode:String):AssetFileDescriptor{
        // This descriptor is the entire seekable cache file, not an APK slice.
        // A declared subsection length makes ContentResolver.openFileDescriptor reject it.
        return AssetFileDescriptor(openFile(uri,mode),0,AssetFileDescriptor.UNKNOWN_LENGTH)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor{
        val item=item(uri);val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply{addRow(columns.map{when(it){OpenableColumns.DISPLAY_NAME->item.id+".gif";OpenableColumns.SIZE->item.bytes;else->null}})}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException("Read only")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read only")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read only")
}

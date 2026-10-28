package dev.lip.keyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Disabled default. Only selected private-memory leases, never paths, archives or a public cache. */
class StickerContentProvider:ContentProvider(){
    override fun onCreate()=true
    override fun getTypeAnonymous(uri:Uri):String?=null
    override fun getType(uri:Uri)=StickerPrivateTransport.describe(context!!,uri).mime
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=StickerPrivateTransport.open(context!!,uri,mode,null)
    override fun openFile(uri:Uri,mode:String,signal:CancellationSignal?):ParcelFileDescriptor=StickerPrivateTransport.open(context!!,uri,mode,signal)
    override fun openAssetFile(uri:Uri,mode:String):AssetFileDescriptor=AssetFileDescriptor(openFile(uri,mode),0,AssetFileDescriptor.UNKNOWN_LENGTH)
    override fun openAssetFile(uri:Uri,mode:String,signal:CancellationSignal?):AssetFileDescriptor=AssetFileDescriptor(openFile(uri,mode,signal),0,AssetFileDescriptor.UNKNOWN_LENGTH)
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        if(selection!=null||selectionArgs!=null||sortOrder!=null)throw FileNotFoundException("Private sticker query unavailable.")
        val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        if(columns.isEmpty()||columns.size>2||columns.distinct().size!=columns.size||columns.any{it !in setOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)})throw FileNotFoundException("Private sticker query unavailable.")
        val info=StickerPrivateTransport.describe(context!!,uri)
        return MatrixCursor(columns).apply{addRow(columns.map{when(it){OpenableColumns.SIZE->info.byteLength;else->if(info.mime=="image/png")"sticker.png" else "sticker.webp"}})}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException("Read only.")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read only.")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read only.")
}

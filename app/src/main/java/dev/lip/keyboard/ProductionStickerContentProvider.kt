package dev.lip.keyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Default production route: one opaque, granted original, never a library/path endpoint.
 * Every useful entry point repeats the same UID/grant/incarnation/expiry checks.
 */
class ProductionStickerContentProvider:ContentProvider() {
    private fun refusal():Nothing=throw FileNotFoundException("Local sticker attachment unavailable.")
    override fun onCreate()=true
    override fun getTypeAnonymous(uri:Uri):String?=null
    override fun getType(uri:Uri)=ProductionStickerTransport.describe(context?:refusal(),uri).mime
    override fun getStreamTypes(uri:Uri,mimeTypeFilter:String):Array<String>? {
        val mime=getType(uri)
        return if(mimeTypeFilter==mime||mimeTypeFilter=="image/*"||mimeTypeFilter=="*/*")arrayOf(mime) else null
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=ProductionStickerTransport.open(context?:refusal(),uri,mode,null)
    override fun openFile(uri:Uri,mode:String,signal:CancellationSignal?):ParcelFileDescriptor=ProductionStickerTransport.open(context?:refusal(),uri,mode,signal)
    override fun openAssetFile(uri:Uri,mode:String):AssetFileDescriptor=AssetFileDescriptor(openFile(uri,mode),0,AssetFileDescriptor.UNKNOWN_LENGTH)
    override fun openAssetFile(uri:Uri,mode:String,signal:CancellationSignal?):AssetFileDescriptor=AssetFileDescriptor(openFile(uri,mode,signal),0,AssetFileDescriptor.UNKNOWN_LENGTH)
    override fun openTypedAssetFile(uri:Uri,mimeTypeFilter:String,opts:Bundle?):AssetFileDescriptor = openTypedAssetFile(uri,mimeTypeFilter,opts,null)
    override fun openTypedAssetFile(uri:Uri,mimeTypeFilter:String,opts:Bundle?,signal:CancellationSignal?):AssetFileDescriptor {
        signal?.throwIfCanceled()
        if(opts!=null&&!opts.isEmpty)refusal()
        if(getStreamTypes(uri,mimeTypeFilter)==null)refusal()
        return openAssetFile(uri,"r",signal)
    }
    override fun query(uri:Uri,projection:Array<out String>?,queryArgs:Bundle?,signal:CancellationSignal?):Cursor {
        signal?.throwIfCanceled()
        if(queryArgs!=null&&!queryArgs.isEmpty)refusal()
        return query(uri,projection,null,null,null)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?,signal:CancellationSignal?):Cursor {
        signal?.throwIfCanceled();return query(uri,projection,selection,selectionArgs,sortOrder)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        if(selection!=null||selectionArgs!=null||sortOrder!=null)refusal()
        val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        if(columns.isEmpty()||columns.size>2||columns.distinct().size!=columns.size||columns.any{it!=OpenableColumns.DISPLAY_NAME&&it!=OpenableColumns.SIZE})refusal()
        val info=ProductionStickerTransport.describe(context?:refusal(),uri)
        return MatrixCursor(columns).apply { addRow(columns.map{if(it==OpenableColumns.SIZE)info.byteLength else if(info.mime=="image/png")"sticker.png" else "sticker.webp"}) }
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException("Read only.")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read only.")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read only.")
}

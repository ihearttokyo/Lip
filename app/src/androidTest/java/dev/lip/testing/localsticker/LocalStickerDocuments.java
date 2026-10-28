package dev.lip.testing.localsticker;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.UUID;

/** Local regular-file SAF import/create-export fixture. No arbitrary paths or deletion. */
public final class LocalStickerDocuments extends DocumentsProvider {
    public static final String TITLE="Lip local sticker workflow files";
    private static final String[] ROOTS={Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES};
    private static final String[] DOCS={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE};
    @Override public boolean onCreate(){
        // This test APK also has isolated build identities. Stay inert there,
        // rather than crashing unrelated instrumentation during provider startup.
        try{LocalStickerFixtureState.guard(getContext());}catch(Exception ignored){}return true;
    }
    private String owner() throws Exception {String o=LocalStickerFixtureState.owner(getContext());if(o==null)throw new FileNotFoundException("Fixture inactive");return o;}
    private String rootId(String owner){return "root:"+owner;}
    private File file(String id) throws Exception {
        String o=owner();File root=LocalStickerFixtureState.root(getContext(),o);String[] parts=id==null?new String[0]:id.split(":",-1);
        if(parts.length==2&&"source".equals(parts[0])&&o.equals(parts[1]))return LocalStickerFixtureState.child(root,"source.zip");
        if(parts.length==3&&"export".equals(parts[0])&&o.equals(parts[1])&&LocalStickerFixtureState.token(parts[2])!=null)return LocalStickerFixtureState.child(root,"export-"+parts[2]+".zip");
        throw new FileNotFoundException("Unknown fixture document");
    }
    private MatrixCursor cursor(String[] requested,String[] defaults){return new MatrixCursor(requested==null?defaults:requested);}
    private void row(MatrixCursor c,String id,String name,String mime,int flags,long bytes){
        MatrixCursor.RowBuilder r=c.newRow();for(String column:c.getColumnNames()){
            Object value=null;if(Document.COLUMN_DOCUMENT_ID.equals(column))value=id;else if(Document.COLUMN_DISPLAY_NAME.equals(column))value=name;
            else if(Document.COLUMN_MIME_TYPE.equals(column))value=mime;else if(Document.COLUMN_FLAGS.equals(column))value=flags;else if(Document.COLUMN_SIZE.equals(column))value=bytes;r.add(value);
        }
    }
    private void document(MatrixCursor c,String id) throws Exception {
        String o=owner();if(rootId(o).equals(id)){row(c,id,TITLE,Document.MIME_TYPE_DIR,Document.FLAG_DIR_SUPPORTS_CREATE,0);return;}
        File f=file(id);if(!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS)||f.length()>16L*1024*1024)throw new FileNotFoundException("Fixture unavailable");
        row(c,id,id.startsWith("source:")?"Production synthetic pack.zip":"Exported synthetic pack.zip","application/zip",id.startsWith("export:")?Document.FLAG_SUPPORTS_WRITE:0,f.length());
    }
    @Override public Cursor queryRoots(String[] projection){
        MatrixCursor c=cursor(projection,ROOTS);try{String o=owner();MatrixCursor.RowBuilder r=c.newRow();for(String key:c.getColumnNames()){
            Object value=null;if(Root.COLUMN_ROOT_ID.equals(key)||Root.COLUMN_DOCUMENT_ID.equals(key))value=rootId(o);else if(Root.COLUMN_TITLE.equals(key))value=TITLE;
            else if(Root.COLUMN_FLAGS.equals(key))value=Root.FLAG_LOCAL_ONLY|Root.FLAG_SUPPORTS_CREATE;else if(Root.COLUMN_MIME_TYPES.equals(key))value="application/zip";r.add(value);
        }}catch(Exception ignored){}return c;
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        MatrixCursor c=cursor(projection,DOCS);try{document(c,id);return c;}catch(Exception e){throw new FileNotFoundException("Fixture document unavailable");}
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order) throws FileNotFoundException {
        MatrixCursor c=cursor(projection,DOCS);try{String o=owner();if(!rootId(o).equals(parent))throw new FileNotFoundException("Unknown fixture root");document(c,"source:"+o);
            File[] all=LocalStickerFixtureState.root(getContext(),o).listFiles();if(all==null)throw new FileNotFoundException("Fixture unavailable");
            int count=0;for(File f:all)if(f.getName().matches("export-[a-f0-9-]{36}\\.zip")){if(++count>16)throw new FileNotFoundException("Fixture capacity reached");document(c,"export:"+o+":"+f.getName().substring(7,43));}return c;
        }catch(Exception e){throw new FileNotFoundException("Fixture directory unavailable");}
    }
    @Override public String createDocument(String parent,String mime,String display) throws FileNotFoundException {
        try{String o=owner();if(!rootId(o).equals(parent)||!"application/zip".equals(mime))throw new FileNotFoundException("Unsupported fixture destination");
            File root=LocalStickerFixtureState.root(getContext(),o);File[] all=root.listFiles();if(all==null||all.length>=20)throw new FileNotFoundException("Fixture capacity reached");
            String token=UUID.randomUUID().toString();LocalStickerFixtureState.writeNew(LocalStickerFixtureState.child(root,"export-"+token+".zip"),new byte[0]);return "export:"+o+":"+token;
        }catch(Exception e){throw new FileNotFoundException("New fixture destination unavailable");}
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal cancel) throws FileNotFoundException {
        try{if(cancel!=null)cancel.throwIfCanceled();File f=file(id);boolean source=id.startsWith("source:");if(!"r".equals(mode)&&!("rw".equals(mode)&&!source))throw new FileNotFoundException("Nontruncating fixture mode required");
            if(!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS)||f.length()>16L*1024*1024)throw new FileNotFoundException("Bounded regular fixture required");
            java.io.FileDescriptor fd=Os.open(f.getPath(),("r".equals(mode)?OsConstants.O_RDONLY:OsConstants.O_RDWR)|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0);
            try{return ParcelFileDescriptor.dup(fd);}finally{Os.close(fd);}
        }catch(Exception e){throw new FileNotFoundException("Fixture descriptor unavailable");}
    }
    @Override public boolean isChildDocument(String parent,String child){try{return rootId(owner()).equals(parent)&&file(child).isFile();}catch(Exception e){return false;}}
}

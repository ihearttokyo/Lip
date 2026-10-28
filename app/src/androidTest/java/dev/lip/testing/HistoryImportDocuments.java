package dev.lip.testing;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Test-APK-only separate-UID source. Java/SDK only because a standalone test
 * provider process cannot borrow the target application's Kotlin runtime. */
public final class HistoryImportDocuments extends DocumentsProvider {
    public static final String TITLE="Lip import test files";
    public static final String VALID="{\"schema\":\"dev.lip.dictation-history\",\"version\":1,\"sourcePlatform\":\"macos\",\"record\":{\"id\":\"ui-fixture-20261027-v1\",\"createdAtUnixMs\":1700000000125,\"original\":\"か\\u3099 你好\\n🎙️\",\"output\":\"が 你好\\n🎙️\",\"language\":null,\"model\":null,\"modelDetails\":null,\"legacyCloudCleanup\":false}}";
    private static final String[] NAMES={"valid-transcript.json","conflicting-transcript.json","malformed-transcript.json"};
    private File base(){
        if(getContext()==null||!getContext().getPackageName().equals("dev.lip.android.historyfixture.test")||
            !(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish")))throw new IllegalStateException("Fixture process only");
        return new File(getContext().getNoBackupFilesDir(),"import-ui-sources");
    }
    private File file(String id)throws FileNotFoundException {
        if(!Arrays.asList(NAMES).contains(id))throw new FileNotFoundException("Unknown fixture document");
        File file=new File(base(),id);
        if(Files.isSymbolicLink(file.toPath())||!file.isFile())throw new FileNotFoundException("Fixture source absent");
        return file;
    }
    @Override public boolean onCreate(){
        try {
            File root=base();String[] payloads={VALID,VALID.replace("が 你好","changed 你好"),"{malformed}"};
            if(!root.exists()){
                if(!root.mkdir())throw new IOException("Fixture root creation failed");
                for(int i=0;i<NAMES.length;i++){
                    File file=new File(root,NAMES[i]);if(!file.createNewFile())throw new IOException("Fixture source exists");
                    try(FileOutputStream out=new FileOutputStream(file)){out.write(payloads[i].getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
                }
            }
            String[] listed=root.list();
            if(Files.isSymbolicLink(root.toPath())||listed==null||!new HashSet<>(Arrays.asList(listed)).equals(new HashSet<>(Arrays.asList(NAMES))))throw new IOException("Unexpected fixture root");
            for(int i=0;i<NAMES.length;i++)if(!Arrays.equals(Files.readAllBytes(file(NAMES[i]).toPath()),payloads[i].getBytes(StandardCharsets.UTF_8)))throw new IOException("Fixture source differs");
            return true;
        }catch(IOException e){throw new IllegalStateException("Immutable fixture sources unavailable",e);}
    }
    private static Object[] row(String[] columns,Map<String,Object> values){Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=values.get(columns[i]);return row;}
    @Override public Cursor queryRoots(String[] projection){
        String[] columns=projection!=null?projection:new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES,Root.COLUMN_ICON};
        MatrixCursor cursor=new MatrixCursor(columns);
        if(base().isDirectory()){
            Map<String,Object> values=new HashMap<>();values.put(Root.COLUMN_ROOT_ID,"fixture");values.put(Root.COLUMN_DOCUMENT_ID,"root");values.put(Root.COLUMN_TITLE,TITLE);
            values.put(Root.COLUMN_FLAGS,Root.FLAG_LOCAL_ONLY);values.put(Root.COLUMN_MIME_TYPES,"application/json");values.put(Root.COLUMN_ICON,android.R.drawable.ic_menu_save);cursor.addRow(row(columns,values));
        }
        return cursor;
    }
    private static String[] columns(String[] projection){return projection!=null?projection:new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED};}
    private void add(MatrixCursor cursor,String id)throws FileNotFoundException {
        base();boolean directory=id.equals("root");File source=directory?null:file(id);Map<String,Object> values=new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID,id);values.put(Document.COLUMN_DISPLAY_NAME,directory?TITLE:id);values.put(Document.COLUMN_MIME_TYPE,directory?Document.MIME_TYPE_DIR:"application/json");
        values.put(Document.COLUMN_FLAGS,0);values.put(Document.COLUMN_SIZE,source==null?0L:source.length());values.put(Document.COLUMN_LAST_MODIFIED,1700000000000L);cursor.addRow(row(cursor.getColumnNames(),values));
    }
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {MatrixCursor cursor=new MatrixCursor(columns(projection));add(cursor,id);return cursor;}
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort)throws FileNotFoundException {
        if(!parent.equals("root"))throw new FileNotFoundException("Unknown fixture parent");MatrixCursor cursor=new MatrixCursor(columns(projection));for(String name:NAMES)add(cursor,name);return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        if(!mode.equals("r"))throw new FileNotFoundException("Read-only fixture");if(signal!=null)signal.throwIfCanceled();return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.MODE_READ_ONLY);
    }
}

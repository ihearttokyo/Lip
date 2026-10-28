package dev.lip.testing.localsticker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.system.Os;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;

/** Fixed synthetic test-APK namespace only. Never addresses production/private paths. */
final class LocalStickerFixtureState {
    static final String PACKAGE = "dev.lip.android.test";
    static void guard(Context c) {
        if (!PACKAGE.equals(c.getPackageName()) || (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)==0 ||
                !("ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE)) || !Build.PRODUCT.contains("sdk"))
            throw new IllegalStateException("Owned emulator fixture required");
    }
    static String token(String raw) {
        try { return raw!=null && UUID.fromString(raw).toString().equals(raw) ? raw : null; }
        catch (Exception ignored) { return null; }
    }
    static String owner(Context c) throws Exception {
        guard(c); File f=new File(c.getNoBackupFilesDir(),"local-sticker-owner-v1.txt");
        if(!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(f.toPath())||f.length()!=36)return null;
        return token(new String(Files.readAllBytes(f.toPath()),StandardCharsets.US_ASCII));
    }
    static File root(Context c,String owner) throws Exception {
        guard(c);if(token(owner)==null||!owner.equals(owner(c)))throw new IllegalStateException("Stale fixture owner");
        File root=new File(c.getNoBackupFilesDir(),"local-sticker-fixture-"+owner);
        if(Files.isSymbolicLink(root.toPath())||!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Fixture unavailable");
        return root;
    }
    static File child(File root,String fixed) throws Exception {
        if(!fixed.matches("(?:source\\.zip|seed-report\\.json|receiver-[a-f0-9-]{36}\\.json|export-[a-f0-9-]{36}\\.zip)"))throw new IllegalStateException("Unknown fixture child");
        File f=new File(root,fixed);if(Files.isSymbolicLink(f.toPath()))throw new IllegalStateException("Linked fixture refused");return f;
    }
    static void writeNew(File file,byte[] bytes) throws Exception {
        Files.write(file.toPath(),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Os.chmod(file.getPath(),0600);
    }
    static void report(File root,String generation,JSONObject report) throws Exception {
        if(token(generation)==null)throw new IllegalStateException("Receiver generation required");
        File f=child(root,"receiver-"+generation+".json");
        android.util.AtomicFile a=new android.util.AtomicFile(f);
        for(String suffix:new String[]{".bak",".new"})if(Files.isSymbolicLink(new File(f.getPath()+suffix).toPath()))throw new IllegalStateException("Linked report refused");
        java.io.FileOutputStream out=a.startWrite();try{Os.fchmod(out.getFD(),0600);out.write(report.toString().getBytes(StandardCharsets.UTF_8));a.finishWrite(out);}catch(Exception e){a.failWrite(out);throw e;}
    }
    static String sha(byte[] b) throws Exception {
        StringBuilder s=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();
    }
}

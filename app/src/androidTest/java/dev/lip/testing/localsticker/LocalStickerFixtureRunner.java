package dev.lip.testing.localsticker;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Self/test-APK targeted. Creates only owned synthetic images/ZIP and content-free receipts. */
public final class LocalStickerFixtureRunner extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b==null?new Bundle():b;start();}
    private static byte[] image(Bitmap.CompressFormat format) throws Exception {
        Bitmap b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);try{
            b.eraseColor(Color.rgb(40,190,210));for(int x=8;x<56;x++)for(int y=8;y<56;y++)if((x+y)%7<3)b.setPixel(x,y,Color.WHITE);
            ByteArrayOutputStream out=new ByteArrayOutputStream();if(!b.compress(format,100,out))throw new IllegalStateException("Synthetic image failed");return out.toByteArray();
        }finally{b.recycle();}
    }
    @Override public void onStart(){
        JSONObject report=new JSONObject();boolean passed=false;
        try{
            LocalStickerFixtureState.guard(getTargetContext());String owner=LocalStickerFixtureState.token(args.getString("owner"));
            if(owner==null)throw new IllegalStateException("Owner required");String mode=args.getString("mode","seed");
            if("seed".equals(mode)){
                File active=new File(getTargetContext().getNoBackupFilesDir(),"local-sticker-owner-v1.txt");if(active.exists())throw new IllegalStateException("Existing owner preserved; use inspect");
                File root=new File(getTargetContext().getNoBackupFilesDir(),"local-sticker-fixture-"+owner);if(!root.mkdir())throw new IllegalStateException("Fresh fixture required");Os.chmod(root.getPath(),0700);
                byte[] png=image(Bitmap.CompressFormat.PNG),webp=image(Bitmap.CompressFormat.WEBP_LOSSLESS);
                JSONArray stickers=new JSONArray();String[] names={"png","webp"};byte[][] images={png,webp};
                for(int i=0;i<2;i++)stickers.put(new JSONObject().put("id",names[i]).put("name","Synthetic "+names[i]).put("file","stickers/"+names[i]+"."+names[i]).put("mime","image/"+names[i]).put("sha256",LocalStickerFixtureState.sha(images[i])).put("bytes",images[i].length).put("emoji",new JSONArray()).put("alt","Owned synthetic "+names[i]));
                JSONObject manifest=new JSONObject().put("schema","dev.lip.sticker-pack").put("version",1)
                    .put("pack",new JSONObject().put("id","production-synthetic").put("name","Production synthetic pack").put("author","Synthetic fixture").put("trayImage",JSONObject.NULL))
                    .put("provenance",new JSONObject().put("sourceName","Owned synthetic emulator fixture").put("sourceRevision","v1").put("rightsholder","Fixture author").put("license","CC0-1.0").put("notices",new JSONArray()).put("sourceFormat","synthetic-fixture").put("sourceManifest",JSONObject.NULL)).put("stickers",stickers);
                ByteArrayOutputStream archive=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(archive)){
                    String[] members={"manifest.json","stickers/png.png","stickers/webp.webp"};byte[][] bytes={manifest.toString().getBytes(StandardCharsets.UTF_8),png,webp};
                    for(int i=0;i<members.length;i++){ZipEntry e=new ZipEntry(members[i]);e.setTime(1700000000000L);zip.putNextEntry(e);zip.write(bytes[i]);zip.closeEntry();}
                }
                byte[] source=archive.toByteArray();LocalStickerFixtureState.writeNew(LocalStickerFixtureState.child(root,"source.zip"),source);
                JSONObject seed=new JSONObject().put("owner",owner).put("uid",Process.myUid()).put("sourceSha256",LocalStickerFixtureState.sha(source)).put("sourceBytes",source.length).put("pngSha256",LocalStickerFixtureState.sha(png)).put("pngBytes",png.length).put("webpSha256",LocalStickerFixtureState.sha(webp)).put("webpBytes",webp.length);
                LocalStickerFixtureState.writeNew(LocalStickerFixtureState.child(root,"seed-report.json"),seed.toString().getBytes(StandardCharsets.UTF_8));
                LocalStickerFixtureState.writeNew(active,owner.getBytes(StandardCharsets.US_ASCII));report=seed;
            }else if("inspect".equals(mode)){
                File root=LocalStickerFixtureState.root(getTargetContext(),owner);byte[] seed=Files.readAllBytes(LocalStickerFixtureState.child(root,"seed-report.json").toPath());report=new JSONObject(new String(seed,StandardCharsets.UTF_8));
                report.put("sourceCurrentSha256",LocalStickerFixtureState.sha(Files.readAllBytes(LocalStickerFixtureState.child(root,"source.zip").toPath())));
                JSONArray exports=new JSONArray();File[] all=root.listFiles();if(all==null)throw new IllegalStateException("Fixture unavailable");
                for(File f:all)if(f.getName().matches("export-[a-f0-9-]{36}\\.zip")){LocalStickerFixtureState.child(root,f.getName());if(f.length()>16*1024*1024)throw new IllegalStateException("Bounded export required");exports.put(new JSONObject().put("name",f.getName()).put("bytes",f.length()).put("sha256",LocalStickerFixtureState.sha(Files.readAllBytes(f.toPath()))));}report.put("exports",exports);
            }else throw new IllegalStateException("Unknown fixture mode");
            passed=true;report.put("passed",true).put("scope","synthetic test-APK document seed/inspection only; not production workflow proof");
        }catch(Exception e){try{report.put("passed",false).put("errorType",e.getClass().getSimpleName());}catch(Exception ignored){}}
        Bundle result=new Bundle();result.putString(REPORT_KEY_STREAMRESULT,"\nLOCAL_STICKER_FIXTURE_RESULT "+report+"\n");finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}

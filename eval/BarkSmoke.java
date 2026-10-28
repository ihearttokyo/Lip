package dev.lip.speech;
import java.nio.file.*;
import java.nio.*;
public class BarkSmoke {
 public static void main(String[] args) throws Exception {
  byte[] wav=Files.readAllBytes(Path.of(args[1]));
  int offset=-1, size=0;
  ByteBuffer bytes=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
  for(int p=12;p+8<=wav.length;) {
   int n=bytes.getInt(p+4);
   if(wav[p]=='d'&&wav[p+1]=='a'&&wav[p+2]=='t'&&wav[p+3]=='a'){offset=p+8;size=n;break;}
   p+=8+n+(n%2);
  }
  if(offset<0)throw new AssertionError("No WAV PCM");
  float[] pcm=new float[size/2];
  for(int i=0;i<pcm.length;i++)pcm[i]=bytes.getShort(offset+i*2)/32768f;
  BarkProfile profile=args.length>2 ? BarkProfile.valueOf(args[2]) : BarkProfile.PRO;
  // Explicit legacy host adapter: production checked loading must not weaken
  // Linux sealing merely because this macOS CPU fixture cannot provide memfd.
  try(WhisperEngine engine=new WhisperEngine(args[0],new BarkNativeCalls(profile.getPaddingSamples()))) {
   long start=System.nanoTime();
   var segments=engine.transcribe(pcm,"en","");
   if(segments.isEmpty()) throw new AssertionError("No words");
   long end=0; StringBuilder text=new StringBuilder();
   for(var s:segments){if(s.getStartMs()<end||s.getEndMs()<s.getStartMs()||s.getEndMs()>pcm.length/16) throw new AssertionError("Bad timestamp");end=s.getEndMs();text.append(s.getText());}
   System.out.println("PROFILE="+profile.name());
   System.out.println("AUDIO_SECONDS="+pcm.length/16000.0);
   System.out.println("INFERENCE_SECONDS="+(System.nanoTime()-start)/1e9);
   System.out.println("WORDS="+segments.size());
   System.out.println("TRANSCRIPT="+text);
   if(!text.toString().toLowerCase().contains("friday"))throw new AssertionError("Expected fact missing");
   if(!engine.transcribe(new float[16000],"en","").isEmpty())throw new AssertionError("Zero PCM not empty");
   try {engine.transcribe(pcm,"ja","");throw new AssertionError("Non-English accepted");}catch(IllegalStateException expected){}
  }
  System.out.println("Host JNI Bark model smoke PASS (not Android device evidence)");
 }
}

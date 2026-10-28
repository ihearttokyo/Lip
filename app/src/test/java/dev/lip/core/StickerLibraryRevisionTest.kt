package dev.lip.core
import dev.lip.stickers.StickerLibraryRevision
import org.junit.Test
import org.junit.Assert.*
class StickerLibraryRevisionTest {
 @Test fun mutationInvalidatesOldAndEqualPostStateNeverRevivesIt(){
  val old=StickerLibraryRevision.snapshot()!!;StickerLibraryRevision.beginChange()
  try{assertNull(StickerLibraryRevision.snapshot());assertFalse(StickerLibraryRevision.isCurrent(old))}finally{StickerLibraryRevision.endChange()}
  assertFalse(StickerLibraryRevision.isCurrent(old));assertTrue(StickerLibraryRevision.isCurrent(StickerLibraryRevision.snapshot()!!))
 }
 @Test fun nestedWritersBlockUntilAllReallyFinish(){
  StickerLibraryRevision.beginChange();StickerLibraryRevision.beginChange()
  try{StickerLibraryRevision.endChange();assertNull(StickerLibraryRevision.snapshot())}finally{StickerLibraryRevision.endChange()}
  assertNotNull(StickerLibraryRevision.snapshot())
 }
 @Test fun observersRunWithoutEpochLockAndCanReadStateOnAnotherThread(){
  var calls=0;val listener:()->Unit={
   val thread=Thread{StickerLibraryRevision.snapshot()};thread.start();thread.join(1000);assertFalse(thread.isAlive);calls++
  }
  StickerLibraryRevision.observe(listener)
  try{StickerLibraryRevision.beginChange();StickerLibraryRevision.endChange();assertEquals(2,calls)}finally{StickerLibraryRevision.unobserve(listener)}
 }
 @Test fun unknownOrRecreatedIdentityDoesNotMatch(){assertFalse(StickerLibraryRevision.isCurrent(Any()))}
 @Test fun productionManagerAndExportFencesAreActuallyWired(){
  fun source(path:String)=listOf(java.io.File(path),java.io.File("app/$path")).first{it.isFile}.readText()
  val manifest=source("src/main/AndroidManifest.xml")
  assertTrue(manifest.contains("android:enabled=\"true\" android:theme=\"@style/Theme.Lip.Stickers\""))
  val exporter=source("src/main/java/dev/lip/stickers/StickerPackDocumentExport.kt")
  assertTrue(exporter.contains("openFileDescriptor(uri,\"rw\",cancellation)"));assertTrue(exporter.contains("before.st_size==0L"));assertTrue(exporter.contains("Os.pread"));assertTrue(exporter.contains("output.channel.force(true)"))
  assertTrue(exporter.indexOf("check(Os.pread(fd,ByteArray(1),0,1,0)==0)")<exporter.indexOf("check(admit())"))
  val store=source("src/main/java/dev/lip/stickers/StickerPackTransactionStore.kt")
  val service=source("src/main/java/dev/lip/keyboard/LipInputMethodService.kt")
  assertTrue(service.contains("if(compositionChanged||oldSelStart!=newSelStart||oldSelEnd!=newSelEnd){stickerAttachment?.cancel();productionStickerAttachment?.cancel()}"))
  assertTrue(store.contains("(?:pending|commit|bin)-"));assertTrue(store.contains("RootedStickerRename.move(root,key,from,to)"));assertFalse(store.contains("deleteDirectory"))
 }
}

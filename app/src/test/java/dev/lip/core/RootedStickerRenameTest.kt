package dev.lip.core
import dev.lip.stickers.RootedStickerRename
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Actual public Unix metadata controls, not native rename qualification. */
class RootedStickerRenameTest {
 private fun key(root:java.nio.file.Path)=Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()!!
 @Test fun oneSnapshotBindsActualOriginalDirectoryAndRedactsItsPath(){
  val root=Files.createTempDirectory("lip-root-identity-");val identity=RootedStickerRename.capture(root,key(root))
  assertFalse(identity.toString().contains(root.toString()))
  assertThrows(IllegalStateException::class.java){RootedStickerRename.capture(root,Any())}
 }
 @Test fun substitutedRootCannotPassOriginalOpaqueFileKey(){
  val root=Files.createTempDirectory("lip-root-before-");val originalKey=key(root)
  val retained=root.resolveSibling("lip-root-retained-${UUID.randomUUID()}")
  Files.move(root,retained);Files.createDirectory(root)
  assertThrows(IllegalStateException::class.java){RootedStickerRename.capture(root,originalKey)}
  assertTrue(Files.isDirectory(root));assertTrue(Files.isDirectory(retained))
 }
 @Test fun symlinkRootRefusesWithoutNativeRename(){
  val root=Files.createTempDirectory("lip-root-target-");val link=root.resolveSibling("lip-root-link-${UUID.randomUUID()}")
  Files.createSymbolicLink(link,root)
  assertThrows(IllegalStateException::class.java){RootedStickerRename.capture(link,key(root))}
 }
 @Test fun invalidRelativeNamesRefuseBeforeLoadingNativeLibrary(){
  val root=Files.createTempDirectory("lip-root-names-");val captured=RootedStickerRename.capture(root,key(root));val id=UUID.randomUUID().toString()
  for((from,to) in listOf("../commit-$id" to "bin-$id","commit-$id" to "commit-$id","commit-$id" to "bin-${UUID.randomUUID()}"))
   assertThrows(IllegalStateException::class.java){RootedStickerRename.moveCaptured(captured,from,to)}
  assertTrue(Files.newDirectoryStream(root).use{!it.iterator().hasNext()})
 }
}

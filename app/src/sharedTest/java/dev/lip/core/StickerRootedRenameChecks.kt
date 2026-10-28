package dev.lip.core
import dev.lip.stickers.RootedStickerRename
import org.junit.Assert.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Parent-owned Android runner may call these with an OWNED synthetic directory.
 * No host JVM pass is claimed: these load the real packaged JNI implementation.
 * Evidence is retained, never recursively deleted.
 */
object StickerRootedRenameChecks {
 private fun identity(root:Path)=RootedStickerRename.capture(root,Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()!!)
 fun nativeRootSubstitutionRefusesWithoutMutatingEitherDirectory(root:Path){
  val id=UUID.randomUUID().toString();val source="commit-$id";val target="bin-$id"
  Files.createDirectory(root.resolve(source));Files.write(root.resolve(source).resolve("original.txt"),byteArrayOf(11,22))
  val captured=identity(root);val retained=root.resolveSibling("retained-${UUID.randomUUID()}")
  Files.move(root,retained);Files.createDirectory(root);Files.createDirectory(root.resolve(source));Files.write(root.resolve(source).resolve("replacement.txt"),byteArrayOf(33,44))
  assertThrows(java.io.IOException::class.java){RootedStickerRename.moveCaptured(captured,source,target)}
  assertArrayEquals(byteArrayOf(11,22),Files.readAllBytes(retained.resolve(source).resolve("original.txt")))
  assertArrayEquals(byteArrayOf(33,44),Files.readAllBytes(root.resolve(source).resolve("replacement.txt")))
  assertFalse(Files.exists(root.resolve(target)));assertFalse(Files.exists(retained.resolve(target)))
 }
 fun nativeNoReplaceAndRoundTripRetainOriginalAndCompetingDirectory(root:Path){
  val id=UUID.randomUUID().toString();val source="commit-$id";val target="bin-$id";val captured=identity(root)
  Files.createDirectory(root.resolve(source));Files.write(root.resolve(source).resolve("original.txt"),byteArrayOf(11,22))
  RootedStickerRename.moveCaptured(captured,source,target);assertFalse(Files.exists(root.resolve(source)))
  RootedStickerRename.moveCaptured(captured,target,source);assertFalse(Files.exists(root.resolve(target)))
  Files.createDirectory(root.resolve(target));Files.write(root.resolve(target).resolve("competing.txt"),byteArrayOf(33,44))
  assertThrows(java.nio.file.FileAlreadyExistsException::class.java){RootedStickerRename.moveCaptured(captured,source,target)}
  assertArrayEquals(byteArrayOf(11,22),Files.readAllBytes(root.resolve(source).resolve("original.txt")))
  assertArrayEquals(byteArrayOf(33,44),Files.readAllBytes(root.resolve(target).resolve("competing.txt")))
 }
}

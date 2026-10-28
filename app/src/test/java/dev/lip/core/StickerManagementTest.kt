package dev.lip.core
import org.junit.Before
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
class StickerManagementTest:StickerManagementChecks({Files.createTempDirectory("lip-bin-synthetic-").toRealPath()}) {
 @Before fun requiresActualSecureDirectoryStream(){
  val root=Files.createTempDirectory("lip-bin-capability-")
  assumeTrue("Storage semantics require SecureDirectoryStream; host skips are not passes",Files.newDirectoryStream(root).use{it is SecureDirectoryStream<*>})
 }
}

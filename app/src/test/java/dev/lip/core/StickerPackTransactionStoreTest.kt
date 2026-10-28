package dev.lip.core

import dev.lip.stickers.StickerPackTransactionStore
import dev.lip.stickers.StickerStoreException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

/** Semantic tests explicitly SKIP (never pass) when the host lacks the required capability. */
class StickerPackTransactionStoreTest : StickerPackTransactionChecks({
    Files.createTempDirectory("lip-store-synthetic-").toRealPath()
}) {
    @Before fun requireSecureDirectoryStream() {
        val root=Files.createTempDirectory("lip-store-host-capability-").toRealPath()
        val supported=Files.newDirectoryStream(root).use{it is SecureDirectoryStream<*>}
        assumeTrue("Store semantic cases require SecureDirectoryStream; unsupported host is NOT qualified",supported)
    }
    @Test fun separateJvmHoldingExistingWriterLockExcludesNewTransaction() =
        checkSeparateJvmHoldingExistingWriterLockExcludesNewTransaction()
}

/** Fail-closed capability evidence is distinct from semantic storage qualification. */
class StickerPackTransactionStoreHostCapabilityTest {
    @Test fun storeRequiresTheActualHostSecureDirectoryCapability() {
        val root:Path=Files.createTempDirectory("lip-store-host-refusal-").toRealPath()
        val supported=Files.newDirectoryStream(root).use{it is SecureDirectoryStream<*>}
        val store=StickerPackTransactionStore(root)
        if(supported) assertTrue(store.scan().isEmpty()) else {
            val e=assertThrows(StickerStoreException::class.java){store.scan()}
            assertEquals(StickerStoreException.Reason.STATE,e.reason)
            assertNull(e.publication);assertNull(e.cause)
        }
        assertTrue(Files.newDirectoryStream(root).use{!it.iterator().hasNext()})
    }
}

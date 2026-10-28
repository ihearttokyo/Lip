package dev.lip.core

import java.nio.file.Files
import java.nio.file.Path

/** Parent instrumentation supplies its dedicated synthetic private root before JUnitCore. */
class StickerPackTransactionStoreAndroidTest : StickerPackTransactionChecks({
    Files.createTempDirectory(base,"case-").toRealPath()
}) {
    companion object {
        lateinit var base:Path
    }
}

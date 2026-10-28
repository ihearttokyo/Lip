package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import dev.lip.core.StickerManagementChecks
import dev.lip.core.StickerRootedRenameChecks
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.UUID

/** Exact shared storage controls on Android Unixattrs/dirfds and packaged JNI.
 * Synthetic private roots only; no production library, UI, decoder or cleanup.
 */
class LocalStickerStorageRunner : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(a: Bundle?) { super.onCreate(a); arguments = a ?: Bundle(); start() }
    override fun onStart() {
        val report = JSONObject().put("passed", false).put("phone_tested", false)
            .put("scope", "actual Android synthetic storage/Unixattrs/dirfd/JNI; not SAF/decoder/recipient/UI acceptance")
        val outcomes = JSONArray(); var stage = "guard"
        try {
            check(targetContext.packageName == "dev.lip.android")
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            val owner = arguments.getString("owner") ?: error("Fresh owner required")
            check(UUID.fromString(owner).toString() == owner)
            val base = targetContext.noBackupFilesDir.toPath()
            check(!Files.isSymbolicLink(base) && Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS))
            val root = Files.createDirectory(base.resolve("production-sticker-storage-$owner"))
            report.put("owner", owner).put("retainedRoot", root.fileName.toString())
            stage = "actual-secure-directory-stream"
            val supported = Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }
            report.put("actualSecureDirectoryStream", supported)
            check(supported) { "Android provider lacks required SecureDirectoryStream; not a storage pass" }
            fun fresh(): Path = Files.createDirectory(root.resolve("case-${UUID.randomUUID()}"))
            val management = object : StickerManagementChecks(::fresh) {}
            val cases = listOf<Pair<String, () -> Unit>>(
                "removeRestorePreservesExactArchiveAndAllOriginalBytes" to management::removeRestorePreservesExactArchiveAndAllOriginalBytes,
                "canceledAndExistingTargetNeverInvokeMoveOrOverwriteEitherOriginal" to management::canceledAndExistingTargetNeverInvokeMoveOrOverwriteEitherOriginal,
                "postRenameFailureIsUnconfirmedAndFreshInventoryFindsRetainedBinWithoutReplay" to management::postRenameFailureIsUnconfirmedAndFreshInventoryFindsRetainedBinWithoutReplay,
                "removedAndInterruptedDirectoriesStillConsumeSameQuota" to management::removedAndInterruptedDirectoriesStillConsumeSameQuota,
                "restoredOriginalExportSnapshotIsDefensiveAndKeepsProvenance" to management::restoredOriginalExportSnapshotIsDefensiveAndKeepsProvenance,
                "nativeRootSubstitutionRefusesWithoutMutatingEitherDirectory" to { StickerRootedRenameChecks.nativeRootSubstitutionRefusesWithoutMutatingEitherDirectory(fresh()) },
                "nativeNoReplaceAndRoundTripRetainOriginalAndCompetingDirectory" to { StickerRootedRenameChecks.nativeNoReplaceAndRoundTripRetainOriginalAndCompetingDirectory(fresh()) }
            )
            for ((name, run) in cases) {
                stage = name
                try { run(); outcomes.put(JSONObject().put("case", name).put("passed", true)) }
                catch (failure: Throwable) {
                    outcomes.put(JSONObject().put("case", name).put("passed", false).put("errorType", failure.javaClass.simpleName))
                    throw failure // Material failures stop this run; retained roots are not replayed or deleted.
                }
            }
            check(outcomes.length() == 7)
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("stage", stage).put("errorType", failure.javaClass.simpleName)
        }
        report.put("cases", outcomes)
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\nLOCAL_STICKER_STORAGE_RESULT $report\n") })
    }
}

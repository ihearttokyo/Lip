package dev.lip

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import dev.lip.keyboard.StickerAssetIdentity
import dev.lip.keyboard.StickerOriginalReadBarrier
import dev.lip.keyboard.StickerOriginalReadBarrierPolicy
import dev.lip.keyboard.StickerPrivateTransport
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

/** APP-target, file-coordinated real-loader barrier. Never opens UI or uses UiAutomation. */
class StickerReadBarrierRunner : Instrumentation() {
    companion object {
        private const val APP = "dev.lip.android.sizingfixture"
        private const val TEST = "dev.lip.android.sizingfixture.test"
        private const val VERSION = 1
        private const val RELEASE_WAIT_MS = 50_000L
        private const val DRAIN_WAIT_MS = 10_000L
        private const val RETIRE_WAIT_MS = 30_000L
        private const val DONE_WAIT_MS = 240_000L
        private const val POLL_MS = 25L
    }

    private data class Paths(val dir: File, val ready: File, val release: File, val retire: File,
                             val retired: File, val done: File) {
        fun all() = listOf(ready, release, retire, retired, done)
        fun temps() = all().map { File(it.parentFile, it.name + ".tmp") }
    }

    private class ProtocolFailure(val token: String) : IllegalStateException()

    private var owner: String? = null
    private var stage = "guard"
    private var failureStage = ""
    private var errorClass = ""
    private var handle: StickerOriginalReadBarrierPolicy.Handle? = null
    private var paths: Paths? = null
    private var readyPublished = false
    private var retiredPublished = false
    private var releaseObserved = false
    private var retireObserved = false
    private var doneObserved = false
    private var hostDoneDeadlineExpired = false
    private var retireWaitAttempted = false
    private var doneWaitAttempted = false
    private var loaderDrained = false
    private var barrierRetired = false
    private var passed = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val suppliedOwner = arguments?.getString("owner")
        if (!canonicalOwner(suppliedOwner)) {
            owner = null
            errorClass = "IllegalArgumentException"
            stage = "owner-guard"
            finishResult()
            return
        }
        owner = suppliedOwner
        Thread({ runCoordinator() }, "lip-sticker-read-barrier-coordinator").start()
    }

    private fun canonicalOwner(value: String?): Boolean = try {
        value != null && UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) { false }

    private fun requireGuardedApp() {
        val app = targetContext.applicationContext
        check(app.packageName == APP && Build.HARDWARE == "ranchu")
        val pm = app.packageManager
        val appInfo = getApplicationInfo(pm, APP)
        check(appInfo.uid == Process.myUid() && (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        val fixtureInfo = getApplicationInfo(pm, TEST)
        check(fixtureInfo.uid != appInfo.uid && pm.checkSignatures(APP, TEST) == PackageManager.SIGNATURE_MATCH)
        check(pm.getPackagesForUid(fixtureInfo.uid)?.toSet() == setOf(TEST))
        check(StickerPrivateTransport.enabled(app))
        check(StickerPrivateTransport.receiver(app).packageName == TEST)
    }

    @Suppress("DEPRECATION")
    private fun getApplicationInfo(pm: PackageManager, pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else pm.getApplicationInfo(pkg, 0)

    private fun makePaths(owner: String): Paths {
        val dir = targetContext.applicationContext.filesDir.canonicalFile
        check(Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS))
        val prefix = "sticker-read-barrier-$owner-"
        return Paths(dir, File(dir, prefix + "ready.json"), File(dir, prefix + "release.json"),
            File(dir, prefix + "retire.json"), File(dir, prefix + "retired.json"), File(dir, prefix + "done.json"))
    }

    private fun requireFreshPaths(p: Paths) {
        (p.all() + p.temps()).forEach { check(!Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS)) }
    }

    private fun expectedAsset() = StickerAssetIdentity(
        "8cc8db1d-6255-40df-90f4-815329caa03d",
        "fbb2e463bb624120710566fd8877c49a3c0c045500e562b680739ef3f10cf1ec",
        "red",
        "df75ecf5f620d125e80ae77bf639cecad76878216cdafb71afef124b04bbf5dc",
        "image/png",
        78
    )

    private fun runCoordinator() {
        try {
            requireGuardedApp()
            stage = "paths"
            val p = makePaths(checkNotNull(owner))
            requireFreshPaths(p)
            paths = p

            stage = "arm"
            val armed = StickerOriginalReadBarrier.arm(targetContext.applicationContext, expectedAsset(), checkNotNull(owner))
            handle = armed
            writeStatus(p.ready, JSONObject().put("owner", checkNotNull(owner)).put("ready", true).put("runnerVersion", VERSION))
            readyPublished = true

            stage = "wait-release"
            awaitReleaseAndRelease(armed, p)
            stage = "loader-drain"
            loaderDrained = StickerOriginalReadBarrier.awaitLoaderDrained(armed, DRAIN_WAIT_MS)
            check(loaderDrained)

            stage = "wait-retire"
            retireWaitAttempted = true
            awaitRequest(p.retire, RETIRE_WAIT_MS)
            retireObserved = true
            stage = "retire"
            barrierRetired = StickerOriginalReadBarrier.retire(armed)
            check(barrierRetired)
            writeStatus(p.retired, JSONObject().put("owner", checkNotNull(owner)).put("retired", true).put("runnerVersion", VERSION))
            retiredPublished = true

            stage = "wait-done"
            doneWaitAttempted = true
            try {
                awaitRequest(p.done, DONE_WAIT_MS)
                doneObserved = true
            } catch (t: Throwable) {
                hostDoneDeadlineExpired = true
                recordFailure("wait-done", "HostDoneUnconfirmed")
                throw t
            }
            passed = true
        } catch (t: Throwable) {
            if (errorClass.isEmpty()) {
                errorClass = if (t is ProtocolFailure) t.token else safeClass(t)
                failureStage = stage
            }
            val p = paths
            if (p != null) {
                if (!readyPublished && ownsStatus(p.ready, ready = true)) readyPublished = true
                if (!retiredPublished && ownsStatus(p.retired, ready = false)) retiredPublished = true
            }
        } finally {
            cleanupAndWaitForTerminal()
            finishResult()
        }
    }

    private fun awaitReleaseAndRelease(h: StickerOriginalReadBarrierPolicy.Handle, p: Paths) {
        val deadline = SystemClock.elapsedRealtime() + RELEASE_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val s = StickerOriginalReadBarrier.snapshot(h)
            check(!s.timedOut && !s.interrupted)
            val barrierReady = s.active && s.claimed && s.entered && s.canceledPredicateObserved
            if (barrierReady && requestPresent(p.release)) {
                requireOwnerRequest(p.release)
                releaseObserved = true
                StickerOriginalReadBarrier.release(h)
                check(StickerOriginalReadBarrier.snapshot(h).released)
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        throw ProtocolFailure("ReleaseDeadline")
    }

    private fun requestPresent(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun awaitRequest(file: File, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (requestPresent(file)) {
                requireOwnerRequest(file)
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        throw ProtocolFailure("RequestDeadline")
    }

    private fun requireOwnerRequest(file: File) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ProtocolFailure("RequestPathInvalid")
        if (file.length() !in 1..64) throw ProtocolFailure("RequestSizeInvalid")
        val bytes = FileInputStream(file).use { it.readNBytes(65) }
        if (bytes.size !in 1..64) throw ProtocolFailure("RequestSizeInvalid")
        if (String(bytes, StandardCharsets.UTF_8) != owner) throw ProtocolFailure("RequestOwnerMismatch")
    }

    private fun ownsStatus(file: File, ready: Boolean): Boolean = runCatching {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() !in 1..512) return@runCatching false
        val value = JSONObject(FileInputStream(file).use { String(it.readBytes(), StandardCharsets.UTF_8) })
        value.length() == 3 && value.getString("owner") == owner && value.getInt("runnerVersion") == VERSION &&
            if (ready) value.getBoolean("ready") else value.getBoolean("retired")
    }.getOrDefault(false)

    private fun cleanupAndWaitForTerminal() {
        val h = handle
        val p = paths
        if (h != null && !barrierRetired) {
            stage = if (errorClass.isEmpty()) "finally-release" else stage
            runCatching { StickerOriginalReadBarrier.forceReleaseFinally(h) }
                .onFailure { recordFailure("finally-release", safeClass(it)) }
            val drained = runCatching { StickerOriginalReadBarrier.awaitLoaderDrained(h, DRAIN_WAIT_MS) }.getOrDefault(false)
            loaderDrained = loaderDrained || drained
            if (!drained) recordFailure("finally-drain", "LoaderDrainTimeout")

            if (drained && p != null && !retireObserved && !retireWaitAttempted) {
                stage = "finally-wait-retire"
                retireWaitAttempted = true
                retireObserved = runCatching { awaitRequest(p.retire, RETIRE_WAIT_MS); true }.getOrElse {
                    recordFailure("finally-wait-retire", safeClass(it)); false
                }
            }
            if (drained && retireObserved) {
                stage = "finally-retire"
                barrierRetired = runCatching { StickerOriginalReadBarrier.retire(h) }.getOrElse {
                    recordFailure("finally-retire", safeClass(it)); false
                }
                if (!barrierRetired) recordFailure("finally-retire", "RetireRefused")
                if (barrierRetired && p != null && !retiredPublished) {
                    retiredPublished = runCatching {
                        writeStatus(p.retired, JSONObject().put("owner", checkNotNull(owner)).put("retired", true).put("runnerVersion", VERSION)); true
                    }.getOrElse { recordFailure("finally-publish-retired", safeClass(it)); false }
                }
            }
        }

        if (h != null && p != null && !doneObserved && !doneWaitAttempted) {
            stage = "finally-wait-done"
            doneWaitAttempted = true
            doneObserved = runCatching { awaitRequest(p.done, DONE_WAIT_MS); true }.getOrElse {
                hostDoneDeadlineExpired = true
                recordFailure("finally-wait-done", "HostDoneUnconfirmed"); false
            }
        }
        if (!readyPublished) passed = false
        if (!barrierRetired || !loaderDrained || !doneObserved) passed = false
    }

    private fun writeStatus(target: File, json: JSONObject) {
        check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
        val temp = File(target.parentFile, target.name + ".tmp")
        check(temp.createNewFile())
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(json.toString().toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            check(temp.renameTo(target))
            FileChannel.open(target.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun stateJson(): JSONObject {
        val h = handle ?: return JSONObject()
        val s = StickerOriginalReadBarrier.snapshot(h)
        return JSONObject()
            .put("active", s.active)
            .put("claimed", s.claimed)
            .put("entered", s.entered)
            .put("workerThreadAlive", s.workerThreadAlive)
            .put("canceledPredicateObserved", s.canceledPredicateObserved)
            .put("cancelRequested", s.cancelRequested)
            .put("released", s.released)
            .put("callbackExited", s.callbackExited)
            .put("loaderAdmissionReleased", s.loaderAdmissionReleased)
            .put("storeLiveInvocations", s.storeLiveInvocations)
            .put("timedOut", s.timedOut)
            .put("interrupted", s.interrupted)
    }

    private fun finishResult() {
        val result = JSONObject()
            .put("runnerVersion", VERSION)
            .put("owner", owner ?: "")
            .put("pid", Process.myPid())
            .put("passed", passed && barrierRetired && loaderDrained && doneObserved && errorClass.isEmpty())
            .put("barrierRetired", barrierRetired)
            .put("cleanupConfirmed", loaderDrained && barrierRetired && doneObserved && !hostDoneDeadlineExpired)
            .put("stage", if (failureStage.isEmpty()) stage else failureStage)
            .put("errorClass", errorClass)
            .put("releaseRequestObserved", releaseObserved)
            .put("retireRequestObserved", retireObserved)
            .put("hostDoneObserved", doneObserved)
            .put("hostDoneDeadlineExpired", hostDoneDeadlineExpired)
            .put("readyPublished", readyPublished)
            .put("retiredPublished", retiredPublished)
            .put("state", stateJson())
            .put("strictTerminal", true)
        finish(if (passed && barrierRetired && loaderDrained && doneObserved && errorClass.isEmpty()) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
            Bundle().apply { putString("runnerResult", result.toString()) })
    }

    private fun safeClass(t: Throwable): String = t.javaClass.simpleName
        .takeIf { it.matches(Regex("[A-Za-z0-9]{1,64}")) } ?: "Exception"

    private fun recordFailure(at: String, kind: String) {
        if (errorClass.isEmpty()) {
            failureStage = at
            errorClass = kind.takeIf { it.matches(Regex("[A-Za-z0-9]{1,64}")) } ?: "Exception"
        }
    }
}

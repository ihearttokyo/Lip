package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import dev.lip.auth.SecureStore
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owned-emulator storage seam only. No microphone, editor, history preference or UI. */
class HistoryConsentRunner : Instrumentation() {
    private var forceStageAbort = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); forceStageAbort = arguments?.getString("force_stage_abort") == "true"; start() }
    override fun onStart() {
        val report = JSONObject().put("passed", false).put("scope", "synthetic owned-emulator encrypted history admission")
        val owned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val store = SecureStore(targetContext)
        var prefix: String? = null
        var cleanup = true
        var safeToClean = true
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android")
            prefix = "hc" + UUID.randomUUID().toString().take(4) + "-"
            check(store.names(prefix).isEmpty()) { "Fixture namespace already exists; refusing cleanup or overwrite" }
            for (alreadyClaimed in listOf(false, true)) {
                val gate = HistoryConsentGate(true)
                val ticket = gate.ticket()!!
                val staged = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
                val enteredDrain = CountDownLatch(1); val drained = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val scoped = prefix + if (alreadyClaimed) "a-" else "n-"
                val records = HistoryRecords({ store.read(scoped + it) }, { n,v -> error("Unexpected legacy/plain write") },
                    { store.names(scoped + "history-").map { it.removePrefix(scoped) } }, { n -> error("Unexpected migration/delete") },
                    writeAdmitted = { name, payload, admit ->
                        val actual = scoped + name; owned.add(actual)
                        store.writeAdmitted(actual, payload) {
                            if (alreadyClaimed) admit()
                            staged.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Stage release timed out" }
                            if (!alreadyClaimed) admit()
                        }
                    })
                val entry = Transcript(raw="synthetic native history fixture",clean="Synthetic native history fixture.",language="en-US",usedChatGpt=false)
                val writer = Thread {
                    try { records.save(entry,true) { gate.claim(ticket) } }
                    catch (e: Throwable) { failure.set(e) }
                    finally { done.countDown() }
                }.apply { start() }
                var waiter: Thread? = null
                try {
                    check(staged.await(10,TimeUnit.SECONDS)) { "Native encrypted stage not reached" }
                    check(!forceStageAbort) { "Controlled abort after encrypted stage" }
                    val off = gate.request(false) // Returns without waiting on blocked real encrypted IO.
                    check(gate.ticket() == null)
                    waiter = Thread { enteredDrain.countDown(); HistoryRecords.awaitDrain(); gate.complete(off,true); drained.countDown() }.apply { start() }
                    check(enteredDrain.await(5,TimeUnit.SECONDS)); check(!drained.await(150,TimeUnit.MILLISECONDS)) { "Off falsely confirmed before admitted IO drained" }
                    release.countDown()
                    check(done.await(10,TimeUnit.SECONDS)); check(drained.await(10,TimeUnit.SECONDS))
                    val names = store.names(scoped + "history-")
                    if (alreadyClaimed) {
                        check(failure.get() == null) { "Admitted write failed" }
                        check(names.size == 1 && AppStore.decodeHistory(store.read(names.single())!!).single() == entry)
                        val bytes = File(targetContext.noBackupFilesDir, "encrypted/" + names.single()).readBytes()
                        check(!String(bytes, Charsets.ISO_8859_1).contains(entry.raw)) { "Plaintext found in encrypted fixture" }
                    } else {
                        check(failure.get() != null) { "Revoked stage published" }
                        check(names.isEmpty()) { "Revoked native stage left an active/temporary name" }
                    }
                    check(!gate.snapshot().preparing && !gate.snapshot().requested)
                    report.put(if (alreadyClaimed) "admitted_io_drains_before_off" else "revoked_stage_not_published", true)
                } finally {
                    // Revoke BEFORE releasing any delayed provider reply. Never race a
                    // still-running writer with cleanup or a second storage query.
                    ticket.revoke(); gate.request(false)
                    release.countDown(); writer.join(12_000); waiter?.join(12_000)
                    if (writer.isAlive || waiter?.isAlive == true) {
                        safeToClean = false; cleanup = false
                        report.put("worker_drained",false).put("owned_names_pending_recovery",owned.toList())
                        error("Fixture worker did not drain; cleanup unconfirmed, exact owned names retained")
                    }
                    report.put("worker_drained",true)
                    if (forceStageAbort && !alreadyClaimed) {
                        report.put("abort_no_active_record_before_cleanup", owned.filter { it.startsWith(scoped) }.all { store.read(it) == null })
                    }
                }
            }
            report.put("passed",true).put("cases",2).put("keystore_scope","real emulator AndroidKeyStore, no physical hardware attestation")
        } catch (e: Throwable) { report.put("error", e.javaClass.simpleName + ": " + (e.message ?: "fixture failed")) }
        finally {
            if (safeToClean) {
                for (name in owned) try { store.delete(name) } catch (_: Throwable) { cleanup=false }
                if (prefix != null) try { if (store.names(prefix).isNotEmpty()) cleanup=false } catch (_: Throwable) { cleanup=false }
            }
            report.put("cleanup_ok",cleanup).put("phone_tested",false).put("microphone_or_editor_tested",false)
            if (!cleanup) report.put("passed",false)
        }
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString(REPORT_KEY_STREAMRESULT,"\nHISTORY_CONSENT_RESULT $report\n") })
    }
}

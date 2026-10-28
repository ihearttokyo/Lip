package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import dev.lip.keyboard.*
import dev.lip.vocabulary.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Real APK resource/model/union controls with synthetic paths, not human/editor/phone quality. */
class PersonalSwipeNativeRunner : Instrumentation() {
    private var reuseSynthetic = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        require(arguments?.getString("mode") in listOf(null,"reuseSynthetic"))
        reuseSynthetic=arguments?.getString("mode")=="reuseSynthetic";start()
    }
    override fun onStart() {
        val report = JSONObject().put("passed", false).put("phone_tested", false)
            .put("scope", "APK pinned Unicode16 + full-base personal neural union; synthetic centre paths only")
        var checks = 0
        var phase = "admission"
        fun verify(value: Boolean) { check(value) { "Synthetic personal swipe control failed" }; checks++ }
        try {
            check(targetContext.packageName == "dev.lip.android.historyfixture")
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            phase = "resource"
            verify(PinnedUnicode16.version == "16.0.0")
            verify(PinnedUnicode16.nfc("cafe\u0301").toByteArray().contentEquals("café".toByteArray()))
            verify(PinnedUnicode16.fullCaseFold("Straße") == "strasse")
            val base = SwipeAssets.dictionary(targetContext).admitted()
            val store = AppStore(targetContext)
            if(reuseSynthetic){
                verify(store.dictionary()==listOf("Jaredium") && store.portableVocabularyEntries()==listOf(PortableVocabulary.Entry("Sampleton")))
            }else{
                verify(store.dictionary().isEmpty() && store.portableVocabularyEntries().isEmpty())
                store.saveDictionary(listOf("Jaredium"))
                store.mergePortableVocabulary(listOf(PortableVocabulary.Entry("Sampleton", weight = 1))) { }
            }
            val union = PersonalSwipeLexicon.build(base, store.dictionary(), store.portableVocabularyEntries())
            verify(union.count == base.size + union.addedCount && union.eligibleCount == 2)
            verify(union.display("jaredium") == "Jaredium" && union.display("sampleton") == "Sampleton")
            val geometry = FloatArray(104); var at = 0
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
                val offset = when (row) { 0 -> 0f; 1 -> .5f; else -> 1.5f }
                letters.forEach { geometry[at++] = (offset + letters.indexOf(it) + .5f) / 10
                    geometry[at++] = (row + .5f) / 3; geometry[at++] = .045f; geometry[at++] = .15f }
            }
            val shape = SwipeGeometry(geometry, .45f)
            fun trace(word: String): List<SwipePoint> {
                val out = mutableListOf<SwipePoint>(); var previous: Char? = null
                for (letter in word) {
                    if (letter == previous || letter == '\'') continue
                    previous = letter; val key = SwipeModel.LETTERS.indexOf(letter) * 4
                    val x = geometry[key]; val y = geometry[key + 1]
                    if (out.isEmpty()) out.add(SwipePoint(x, y, 0f)) else {
                        val from = out.last()
                        for (step in 1..20) { val f = step / 20f
                            out.add(SwipePoint(from.x + (x - from.x) * f, from.y + (y - from.y) * f, from.milliseconds + step * 10)) }
                    }
                }
                return out
            }
            phase = "native_union"
            val model = SwipeAssets.model(targetContext)
            SwipeEngine.open(model, shape, union.entries.map { it.word }, IntArray(union.count) { union.entries[it].frequency }).use { engine ->
                for (word in listOf("hello", "jaredium", "sampleton")) {
                    val candidates = engine.decodeForGeometry(shape, trace(word), 3)
                    report.put("synthetic_${word}_top3", word in candidates)
                    verify(word in candidates)
                    verify(candidates.all { union.display(it) != null })
                }
            }
            val verifiedModel=SwipeModel.verifiedBytes(model)
            verify(runCatching { SwipeNative.open(verifiedModel,geometry,.45f,
                Array(SwipeInputBounds.MAX_LEXICON_ENTRIES+1){"hello"},IntArray(SwipeInputBounds.MAX_LEXICON_ENTRIES+1){1}) }.isFailure)
            phase = "worker_epoch"
            lateinit var worker: SwipeDecodeWorker
            val access = SwipeVocabularyAccess() // Synthetic component lease, not real-field eligibility evidence.
            val expected = checkNotNull(SwipeVocabularyEpoch.process.snapshot())
            runOnMainSync { worker = SwipeDecodeWorker(targetContext) }
            try {
                val done = CountDownLatch(1); val result = AtomicReference<Result<List<String>>>()
                runOnMainSync { worker.decode(shape, trace("jaredium"), expected, access) { result.set(it); done.countDown() } }
                verify(done.await(30, TimeUnit.SECONDS))
                verify(result.get().getOrThrow().contains("Jaredium"))
                val field=worker.javaClass.getDeclaredField("engine").apply{isAccessible=true}
                val firstEngine=field.get(worker);verify(firstEngine!=null)
                val secondDone=CountDownLatch(1);val second=AtomicReference<Result<List<String>>>()
                runOnMainSync { worker.decode(shape,trace("sampleton"),expected,access){second.set(it);secondDone.countDown()} }
                verify(secondDone.await(30,TimeUnit.SECONDS)&&second.get().getOrThrow().contains("Sampleton"))
                verify(firstEngine===field.get(worker))
                val mutation = SwipeVocabularyEpoch.process.beginWrite(); mutation.finish()
                verify(!SwipeVocabularyEpoch.process.claim(expected) { true })
                phase="explicit_reopen_recovery"
                val fresh=checkNotNull(SwipeVocabularyEpoch.process.snapshot())
                val file=java.io.File(targetContext.noBackupFilesDir,"portable-vocabulary-v1/vocabulary.enc")
                val original=file.readBytes()
                fun attempt(token:SwipeVocabularyAccess):Result<List<String>>{
                    val latch=CountDownLatch(1);val got=AtomicReference<Result<List<String>>>()
                    runOnMainSync{worker.decode(shape,trace("jaredium"),fresh,token){got.set(it);latch.countDown()}}
                    check(latch.await(30,TimeUnit.SECONDS));return got.get()
                }
                try{file.writeBytes(byteArrayOf(1,2,3));verify(attempt(access).isFailure)}
                finally{file.writeBytes(original)}
                verify(file.readBytes().contentEquals(original))
                verify(attempt(access).isFailure) // Same-access failure is cached even after repair: no per-gesture retry.
                access.revoke()
                val reopened=SwipeVocabularyAccess()
                try{verify(attempt(reopened).getOrThrow().contains("Jaredium"))}finally{reopened.revoke()}
                verify(!access.isCurrent())
            } finally {
                runOnMainSync { worker.close() }
                val executor=worker.javaClass.getDeclaredField("executor").apply{isAccessible=true}.get(worker) as java.util.concurrent.ExecutorService
                verify(executor.awaitTermination(30,TimeUnit.SECONDS))
                verify(worker.javaClass.getDeclaredField("engine").apply{isAccessible=true}.get(worker)==null)
            }
            report.put("union_entries", union.count).put("added", union.addedCount).put("passed", true)
        } catch (failure: Throwable) { report.put("error_type", failure.javaClass.simpleName).put("phase", phase) }
        report.put("checks", checks)
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\nPERSONAL_SWIPE_COMPONENT_RESULT $report\n") })
    }
}

package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import dev.lip.vocabulary.PortableVocabulary
import dev.lip.vocabulary.PortableVocabularyStore
import dev.lip.vocabulary.VocabularyMergeLease
import org.json.JSONObject

/** Synthetic-only actual Android ICU/Keystore component evidence, not SAF/UI/phone acceptance. */
class VocabularyPortableRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = JSONObject().put("passed", false).put("phone_tested", false)
            .put("scope", "actual Android ICU16 and dedicated encrypted-store components; synthetic terms only")
        var phase = "admission"
        var checks = 0
        fun verify(value: Boolean) { check(value) { "Synthetic vocabulary component mismatch" }; checks++ }
        try {
            check(targetContext.packageName == "dev.lip.android.historyfixture")
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            report.put("unicode_runtime", dev.lip.vocabulary.AndroidUnicode16.version)
            phase = "unicode_vectors"
            val codec = PortableVocabulary()
            fun entry(term: String, weight: Int = 1) = PortableVocabulary.Entry(term, weight = weight)
            for ((input, normalized, folded) in listOf(
                Triple("Straße", "Straße", "strasse"), Triple("cafe\u0301", "café", "café"),
                Triple("\uD801\uDD70", "\uD801\uDD70", "\uD801\uDD97"), Triple("東京", "東京", "東京")
            )) {
                val first = codec.decode(codec.encode(listOf(entry(input)))).single()
                verify(first.term == normalized)
                val merged = codec.merge(listOf(first), listOf(entry(folded, 2)))
                verify(merged.size == 1 && merged.single().term == normalized && merged.single().weight == 2)
            }
            for (bad in listOf("abc\u202e", "a@example.com", "123456")) {
                verify(runCatching { codec.encode(listOf(entry(bad))) }.isFailure)
            }
            phase = "encrypted_store"
            val store = PortableVocabularyStore(targetContext)
            verify(store.snapshot().entries.isEmpty()) // Fresh isolated emulator fixture only.
            val incoming = listOf(entry("Jaredium"), entry("Syntheticword"))
            val first = store.merge(incoming, VocabularyMergeLease()::claim)
            verify(first.changed && first.preview.added == 2)
            verify(PortableVocabularyStore(targetContext).snapshot().entries.size == 2)
            val bytes = store.export()
            verify(codec.decode(bytes).size == 2)
            verify(!store.merge(incoming, VocabularyMergeLease()::claim).changed)
            val canceled = VocabularyMergeLease().apply { cancel() }
            verify(runCatching { store.merge(listOf(entry("Canceledword")), canceled::claim) }.isFailure)
            verify(store.export().contentEquals(bytes))
            val protected = java.io.File(targetContext.noBackupFilesDir, "portable-vocabulary-v1/vocabulary.enc").readBytes()
            verify(!protected.toString(Charsets.ISO_8859_1).contains("Jaredium"))
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("error_type", failure.javaClass.simpleName).put("phase", phase) // No private values/messages.
        }
        report.put("checks", checks)
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\nVOCABULARY_COMPONENT_RESULT $report\n") })
    }
}

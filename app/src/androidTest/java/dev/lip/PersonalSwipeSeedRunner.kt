package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import dev.lip.vocabulary.PortableVocabulary

/** Seeds only a fresh owned emulator app for the separate-UID real-touch fixture.
 * No private source/history scan; refuses any pre-existing personal vocabulary.
 */
class PersonalSwipeSeedRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report=Bundle();var status=Activity.RESULT_CANCELED
        try {
            check(targetContext.packageName=="dev.lip.android" && Build.HARDWARE in listOf("ranchu","goldfish") && Build.PRODUCT.contains("sdk"))
            val store=AppStore(targetContext)
            check(store.dictionary().isEmpty()&&store.portableVocabularyEntries().isEmpty())
            val codec=PortableVocabulary()
            val incoming=codec.decode(codec.encode(listOf(PortableVocabulary.Entry("Jaredium"),PortableVocabulary.Entry("Sampleton"))))
            store.mergePortableVocabulary(incoming){}
            check(store.portableVocabularyEntries().size==2)
            report.putBoolean("SYNTHETIC-PORTABLE-TERMS-COMMITTED",true);status=Activity.RESULT_OK
        } catch(failure:Throwable){report.putString("error_type",failure.javaClass.simpleName)}
        finish(status,report)
    }
}

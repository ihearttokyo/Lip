package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class FloatingKeyboardPreferencesTest {
    @Test fun strictNormalizedVersionedSchemaRoundTripsWithoutDockedFields() {
        for(mode in FloatingKeyboardMode.entries)for(position in listOf(0,5000,10000))for(size in listOf(1,5000,10000)){
            val value=FloatingKeyboardPreferences(mode,position,10000-position,size,10001-size)
            assertEquals(value,FloatingKeyboardPreferences.decode(value.encode()))
            assertEquals(setOf("version","mode","xBp","yBp","widthBp","heightBp"),value.encode().keys)
        }
        for(bad in listOf(-1,10001))assertThrows(IllegalArgumentException::class.java){FloatingKeyboardPreferences(xBp=bad)}
        for(bad in listOf(0,-1,10001))assertThrows(IllegalArgumentException::class.java){FloatingKeyboardPreferences(widthBp=bad)}
    }
    @Test fun missingFutureUnknownCorruptAndNonIntegerValuesDockWithoutWrite() {
        val good=FloatingKeyboardPreferences(mode=FloatingKeyboardMode.FLOATING).encode()
        val bad=mutableListOf<Map<String,*>>(mapOf("version" to 2),good+mapOf("version" to 2),good+mapOf("unknown" to 1),good-mapOf("mode" to "").keys)
        for(key in listOf("version","xBp","yBp","widthBp","heightBp"))for(value in listOf<Any>(1L,1.0f,"1",true))bad.add(good+mapOf(key to value))
        bad.add(good+mapOf("mode" to "future"));bad.add(good+mapOf("xBp" to -1));bad.add(good+mapOf("heightBp" to 0))
        for(values in bad){
            var writes=0;val repo=FloatingKeyboardPreferenceRepository({values}){writes++;true}
            assertEquals(FloatingKeyboardPreferences(),repo.load());assertFalse(repo.snapshot().valid);assertEquals(0L,repo.snapshot().revision);assertEquals(0,writes)
        }
        val fresh=FloatingKeyboardPreferenceRepository({emptyMap<String,Any>()}){error("Must not eagerly persist.")}
        assertTrue(fresh.snapshot().valid);assertEquals(FloatingKeyboardMode.DOCKED,fresh.load().mode)
    }
    @Test fun failedMutatingBackendCacheNeverBecomesConfirmedAndRevisionDoesNotAdvance() {
        var cache=FloatingKeyboardPreferences().encode();var success=false;var calls=0
        val repo=FloatingKeyboardPreferenceRepository({cache}){cache=it.encode();calls++;success}
        val original=repo.snapshot();val requested=original.value.copy(mode=FloatingKeyboardMode.FLOATING,xBp=1234)
        assertFalse(repo.save(requested,original.revision));assertEquals(requested,FloatingKeyboardPreferences.decode(cache))
        assertEquals(original,repo.snapshot());assertEquals(1,calls)
        success=true;assertTrue(repo.setMode(FloatingKeyboardMode.FLOATING))
        assertEquals(5000,repo.load().xBp);assertEquals(1L,repo.snapshot().revision);assertEquals(2,calls)
        assertFalse(repo.save(requested,original.revision));assertEquals(2,calls)
    }
    @Test fun fieldAtomicGeometryModeUpdatesAndStaleApplyNeverClobberLatest() {
        val repo=FloatingKeyboardPreferenceRepository({emptyMap<String,Any>()}){true}
        val initial=repo.snapshot()
        assertTrue(repo.setMode(FloatingKeyboardMode.FLOATING));val mode=repo.snapshot()
        assertFalse(repo.saveGeometry(initial.value.copy(xBp=1000),initial.revision))
        assertTrue(repo.saveGeometry(mode.value.copy(mode=FloatingKeyboardMode.DOCKED,xBp=2000,widthBp=8000),mode.revision))
        assertEquals(FloatingKeyboardMode.FLOATING,repo.load().mode);assertEquals(2000,repo.load().xBp)
        assertTrue(repo.setMode(FloatingKeyboardMode.DOCKED));assertEquals(8000,repo.load().widthBp);assertEquals(2000,repo.load().xBp)
        assertTrue(repo.update{it.copy(yBp=1234)});assertEquals(FloatingKeyboardMode.DOCKED,repo.load().mode)
        assertEquals(2000,repo.load().xBp);assertEquals(1234,repo.load().yBp)
    }
    @Test fun readWriteAndTransformErrorsRetainConfirmedAuthorityAndDoNotExposeCauses() {
        val unreadable=FloatingKeyboardPreferenceRepository({error("synthetic read")}){true}
        assertFalse(unreadable.snapshot().valid);assertEquals(FloatingKeyboardMode.DOCKED,unreadable.load().mode)
        val repo=FloatingKeyboardPreferenceRepository({emptyMap<String,Any>()}){throw IllegalStateException("synthetic write")}
        val before=repo.snapshot();assertFalse(repo.setMode(FloatingKeyboardMode.FLOATING));assertEquals(before,repo.snapshot())
        assertFalse(repo.update{throw IllegalArgumentException("synthetic transform")});assertEquals(before,repo.snapshot())
    }
    @Test fun reentrantTransformOrBackendCannotClobberLatestFields() {
        lateinit var repo:FloatingKeyboardPreferenceRepository
        var nested: Boolean?=null
        repo=FloatingKeyboardPreferenceRepository({emptyMap<String,Any>()}){nested=repo.setMode(FloatingKeyboardMode.DOCKED);true}
        assertTrue(repo.update{current->assertFalse(repo.setMode(FloatingKeyboardMode.FLOATING));current.copy(xBp=1200)})
        assertEquals(false,nested);assertEquals(1L,repo.snapshot().revision);assertEquals(1200,repo.load().xBp)
    }
}

package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class NextWordSuggestionsTest {
    private val model=NextWordSuggestions.parse("#lip-nextword-en-v1\ni want\tto\t40\ni want\ta\t10\nwant\tto\t50\nwant\tit\t20\n")
    private fun cursor(before:String)=ImeCursor("fixture",1,before.length,before.length,before,"",false)
    @Test fun contextualTrigramThenDeduplicatedBigram(){assertEquals(listOf("to","a","it"),model.suggest(cursor("i want ")));assertEquals(listOf("to","it"),model.suggest(cursor("they want ")))}
    @Test fun punctuationPartialAndPrivateGates(){
        assertTrue(model.suggest(cursor("i want")).isEmpty());assertTrue(model.suggest(cursor("i want. ")).isEmpty())
        assertEquals(listOf("to","it"),model.suggest(cursor("i. want ")))
        assertTrue(model.suggest(cursor("want ").copy(start=100,end=100)).isEmpty())
        assertEquals(listOf("to","it"),model.suggest(cursor("x want ").copy(start=100,end=100)))
        assertTrue(model.suggest(cursor("want ").copy(protectedField=true)).isEmpty())
    }
    @Test fun fullBundledDerivativeActuallyPredictsContexts(){
        val file=java.io.File("src/main/assets/nextword/en.tsv")
        val bundled=NextWordSuggestions.parse(file.readText())
        assertEquals(listOf("to","you","a"),bundled.suggest(cursor("LEFT i want ")))
        assertEquals(listOf("for","very","so"),bundled.suggest(cursor("thank you ")))
    }
    @Test fun authenticatedFastReadersMatchStrictWholeAssets(){
        val source=java.io.File("src/main/assets/nextword/en.tsv").readText()
        val strict=NextWordSuggestions.parse(source)
        val pinned=NextWordAssets.decodeVerified(source.toByteArray(Charsets.US_ASCII)){it}
        assertTrue(strict.samePublicRowsAs(pinned))
        val corrupt=source.toByteArray(Charsets.US_ASCII);corrupt[corrupt.lastIndex-1]=(corrupt[corrupt.lastIndex-1].toInt() xor 1).toByte()
        try{NextWordAssets.decodeVerified(corrupt){it};fail("Next-word corrupt bytes accepted")}catch(_:IllegalArgumentException){}
        for(context in source.lineSequence().drop(1).filter{it.isNotEmpty()}.map{it.substringBefore('\t')}.distinct())
            assertEquals(context,strict.suggest(cursor(context+" ")),pinned.suggest(cursor(context+" ")))
        val bytes=java.io.File("src/main/assets/swipe/en.tsv").readBytes()
        assertEquals(SwipeLexiconData.parse(bytes.toString(Charsets.US_ASCII)).entries,SwipeLexiconData.verified(bytes).entries)
        bytes[bytes.lastIndex-1]=(bytes[bytes.lastIndex-1].toInt() xor 1).toByte()
        try{SwipeLexiconData.verified(bytes);fail("Corruption bypassed verified fast reader")}catch(_:IllegalArgumentException){}
    }
    @Test fun malformedAndUnsortedAssetsRefused(){
        for(row in listOf("want\tto\t0","want\tto\t1\nwant\ta\t2","want\tto\t1\nwant\tto\t1","want\tto.\t1","Want\tto\t1")){
            try{NextWordSuggestions.parse("#lip-nextword-en-v1\n$row\n");fail(row)}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun singleUseAndAbaNoRevival(){
        for(mode in 0..4){
            val session=ImeSession();session.beginEditor();val owner=NextWordOwner(session);val conn=Any();val at=cursor("i want ")
            val request=owner.begin(at,conn)!!;assertTrue(owner.complete(request,listOf("to"),at,conn))
            when(mode){1->session.editorChanged();2->session.beginEditor();3->owner.clear()}
            val choice=owner.claim(request,0,at,if(mode==4)Any() else conn)
            assertEquals(if(mode==0)"to" else null,choice);assertNull(owner.claim(request,0,at,conn))
        }
    }
    @Test fun asyncReplacementAndContextConflictsRefused(){
        val session=ImeSession();session.beginEditor();val owner=NextWordOwner(session);val conn=Any();val at=cursor("i want ")
        val old=owner.begin(at,conn)!!;val fresh=owner.begin(at,conn)!!
        assertFalse(owner.complete(old,listOf("to"),at,conn));assertTrue(owner.complete(fresh,listOf("to"),at,conn))
        assertNull(owner.claim(fresh,0,at.copy(after="different"),conn))
        val aba=owner.begin(at,conn)!!;session.editorChanged();assertFalse(owner.complete(aba,listOf("to"),at,conn))
    }
}

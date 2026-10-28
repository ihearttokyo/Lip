package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class TypingSuggestionsTest{
    private val engine=TypingSuggestions(listOf(
        SwipeLexeme("the",222,"the",false),SwipeLexeme("teh",82,"teh",false),
        SwipeLexeme("hello",120,"hello",false),SwipeLexeme("help",180,"help",false),
        SwipeLexeme("receive",137,"receive",false),SwipeLexeme("and",212,"and",false),
        SwipeLexeme("form",156,"form",false),SwipeLexeme("from",203,"from",false),
        SwipeLexeme("forbidden",250,"forbidden",true)),listOf("getUser","Teh","Pianoforte"))
    @Test fun savedAndExactSpellingsNeverAutoChange(){assertNull(engine.suggest("teh").automatic);assertNull(engine.suggest("hello").automatic);assertNull(engine.suggest("getUser").automatic);assertNull(engine.suggest("form").automatic)}
    @Test fun editsAndPrefix(){assertTrue("receive" in engine.suggest("recieve").choices);assertTrue("receive" in engine.suggest("recive").choices);assertTrue("and" in engine.suggest("adn").choices);assertTrue("Pianoforte" in engine.suggest("piano").choices);assertTrue("getUser" in engine.suggest("getu").choices)}
    @Test fun confidentUnknownAndCase(){assertEquals("and",engine.suggest("adn").let{it.choices[it.automatic!!]});assertTrue("AND" in engine.suggest("ADN").choices);assertNull(engine.suggest("aDn").automatic)}
    @Test fun knownRareGapAndOffensive(){val public=TypingSuggestions(listOf(SwipeLexeme("the",222,"the",false),SwipeLexeme("teh",82,"teh",false)),emptyList());assertEquals(1,public.suggest("teh").automatic);assertFalse(engine.suggest("forbidde").choices.contains("forbidden"))}
    @Test fun boundedAndOriginal(){for(word in listOf("", "x".repeat(49),"abc123","foo bar","😀")){assertEquals(listOf(word),engine.suggest(word).choices);assertNull(engine.suggest(word).automatic)};assertTrue(engine.suggest("hel").choices.size<=3)}
    @Test fun longSavedTermsArePreservedOutsideEngineButNeverStaged(){
        val engine=TypingSuggestions(emptyList(),listOf("a".repeat(60),"short"))
        assertEquals(listOf("aaa"),engine.suggest("aaa").choices)
        val session=ImeSession();session.beginEditor();val owner=SwipeCandidateOwner(session)
        val base=ImeCursor("fixture",1,0,0,"","",false)
        val choices=engine.suggest("aaa");assertNotNull(owner.stage(base,"aaa",choices.choices,Any(),CandidateOrigin.TYPED))
    }
    @Test fun prefixDoesNotSilentlyLoseFrequentWordsAfter512Neighbours(){
        val rows=(0 until 600).map{n->val suffix=""+('a'+n/26)+('a'+n%26);SwipeLexeme("ab"+suffix,1,"ab"+suffix,false)}+
            SwipeLexeme("abzz",255,"abzz",false)
        assertTrue(TypingSuggestions(rows,emptyList()).suggest("ab").choices.contains("abzz"))
    }
    @Test fun observedChromiumAutocorrectFlagsRemainDistinct(){
        assertTrue(TypingPolicy.forInput(0x2c0a1,0).automatic)
        assertFalse(TypingPolicy.forInput(0x240a1,0).automatic)
        assertTrue(TypingPolicy.forInput(0x240a1,0).suggest)
    }
    @Test fun fieldPolicies(){for(type in listOf(0x81,0x91,0xe1,0x21,0xd1,0x11,2,3,4,0x80001)){assertFalse(TypingPolicy.forInput(type,0).suggest)};assertFalse(TypingPolicy.forInput(1,0x1000000).suggest);assertTrue(TypingPolicy.forInput(1,0).automatic);assertTrue(TypingPolicy.forInput(0x80a1,0).automatic);assertFalse(TypingPolicy.forInput(0xa1,0).automatic);assertTrue(TypingPolicy.forInput(0xa1,0).suggest);assertFalse(TypingPolicy.forInput(0x10001,0).suggest);assertTrue(TypingPolicy.forInput(0x61,0).suggest);assertFalse(TypingPolicy.forInput(0x61,0).automatic)}
}

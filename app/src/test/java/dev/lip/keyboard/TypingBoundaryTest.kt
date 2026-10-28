package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class TypingBoundaryTest {
    @Test fun delimitersRetainExactWordAndSuffix(){
        for(c in " .,!?;:"){
            val text="recieve$c";assertTrue(TypingBoundary.valid(text))
            assertEquals("recieve",TypingBoundary.token(text));assertEquals("$c",TypingBoundary.suffix(text))
        }
        assertTrue(TypingBoundary.valid("don't"));assertTrue(TypingBoundary.valid("don'"))
        assertEquals("don't",TypingBoundary.token("don't"));assertEquals("",TypingBoundary.suffix("don't"))
    }
    @Test fun unrelatedOrRepeatedSuffixesAreNotOwnedWords(){
        for(text in listOf("", ".", "hello..", "hello! ", "hello\n", "hello😀", "a1.", "two words.", "'word", "wo''rd"))assertFalse(text,TypingBoundary.valid(text))
        assertTrue(TypingBoundary.valid("a".repeat(48)+"."));assertFalse(TypingBoundary.valid("a".repeat(49)+"."))
    }
    @Test fun typedClaimsPreserveDelimiterAndExistingFences(){
        for(origin in listOf(CandidateOrigin.TYPED,CandidateOrigin.AUTO)){
            val session=ImeSession();session.beginEditor();val owner=SwipeCandidateOwner(session);val conn=Any()
            val base=ImeCursor("fixture",1,5,5,"LEFT "," RIGHT",false)
            val state=owner.stage(base,"teh.",listOf("teh.","the."),conn,origin)!!
            val cursor=base.copy(start=9,end=9,before="LEFT teh.")
            assertTrue(owner.confirm(state,cursor,"teh.",conn));assertEquals("the.",owner.claim(state,1,cursor,"teh.",conn));assertNull(owner.claim(state,1,cursor,"teh.",conn))
            for(bad in listOf("the!","the","the..","the. ")){
                try{owner.stage(base,"teh.",listOf("teh.",bad),conn,origin);fail("Mixed or invalid delimiter accepted: $bad")}catch(_:IllegalArgumentException){}
            }
        }
    }
}

package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

/** Exact previous public acceptance predicate as an independent oracle, not an admitted-data bypass. */
class SwipeLexiconValidationTest {
    private fun legacy(words:List<String>,frequencies:IntArray):Boolean=runCatching{
        require(words.size in 1..250000&&words.size==frequencies.size)
        require(words.distinct().size==words.size)
        val grammar=Regex("[a-z]+(?:'[a-z]+)*")
        require(words.all{it.length in 1..48&&grammar.matches(it)})
        require(words.all{word->var previous:Char?=null;var steps=0;word.filter{it!='\''}.forEach{letter->if(letter!=previous){steps++;previous=letter}};steps<=40})
        require(frequencies.all{it in 0..255})
    }.isSuccess
    private fun same(words:List<String>,frequencies:IntArray){assertEquals(words.toString(),legacy(words,frequencies),runCatching{SwipeInputBounds.lexicon(words,frequencies)}.isSuccess)}
    @Test fun exactGrammarPathAndFrequencyBoundaries(){
        for(word in listOf("a","a'a","can't","a'b'c","'a","a'","a''b","A","a\nb","é","a-b","a a","", "a".repeat(48),"a".repeat(49),"ab".repeat(20),"ab".repeat(20)+"a","ab".repeat(19)+"a'a"))for(freq in listOf(-1,0,255,256))same(listOf(word),intArrayOf(freq))
        same(emptyList(),intArrayOf());same(listOf("a","a"),intArrayOf(1,2));same(listOf("a","b"),intArrayOf(1));same(listOf("a"),intArrayOf(1,2))
    }
    @Test fun randomizedAcceptanceEqualsOriginal(){
        val random=java.util.Random(271027);val alphabet="abcxyz''A09- é\n\u0000"
        repeat(6000){
            val count=random.nextInt(7)
            val words=List(count){buildString{repeat(random.nextInt(52)){append(alphabet[random.nextInt(alphabet.length)])}}}
            val frequencies=IntArray(if(random.nextInt(8)==0)random.nextInt(8)else count){random.nextInt(260)-2}
            same(words,frequencies)
        }
        repeat(2000){
            val word=buildString{repeat(1+random.nextInt(46)){i->if(i>0&&i%7==0)append('\'');append('a'+random.nextInt(26))}}
            same(listOf(word),intArrayOf(random.nextInt(256)))
        }
    }
    @Test fun completeEntryCountBoundary(){
        fun word(i:Int)=buildString{append('a');var n=i;repeat(4){append('a'+n%26);n/=26}}
        val words=List(250000,::word);same(words,IntArray(words.size){200})
        same(words+"zzzzzz",IntArray(words.size+1){200})
    }
}

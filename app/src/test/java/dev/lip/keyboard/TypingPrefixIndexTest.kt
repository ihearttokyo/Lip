package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class TypingPrefixIndexTest{
    @Test fun exactRankingMatchesExhaustiveAcrossRanges(){
        val random=java.util.Random(17)
        val entries=(0 until 5000).map{i->
            val key=""+('a'+i/(26*26))+('a'+i/26%26)+('a'+i%26)
            SwipeLexeme(key,random.nextInt(256),key,false)
        }
        val index=TypingPrefixIndex(entries)
        val prefixes=listOf("","a","ab","abc","zzz","'","aaaa")+entries.filterIndexed{i,_->i%37==0}.map{it.word.take(2)}
        for(prefix in prefixes){
            val expected=entries.filter{it.word.startsWith(prefix)&&it.word!=prefix}.sortedWith(compareByDescending<SwipeLexeme>{it.frequency}.thenBy{it.word}).take(2).map{it.word}
            assertEquals(prefix,expected,index.topTwo(prefix))
        }
    }
    @Test fun tiesEmptySingletonAndExactOriginal(){
        assertEquals(emptyList<String>(),TypingPrefixIndex(emptyList()).topTwo("a"))
        val rows=listOf("a","ab","abc","abd").map{SwipeLexeme(it,100,it,false)}
        val index=TypingPrefixIndex(rows)
        assertEquals(listOf("ab","abc"),index.topTwo("a"));assertEquals(listOf("abc","abd"),index.topTwo("ab"));assertEquals(emptyList<String>(),index.topTwo("abd"))
    }
}

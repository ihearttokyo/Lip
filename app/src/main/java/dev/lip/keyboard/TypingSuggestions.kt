package dev.lip.keyboard

import java.util.Locale

data class TypingChoices(val original:String,val choices:List<String>,val automatic:Int?)
data class TypingPolicy(val suggest:Boolean,val automatic:Boolean){
    companion object{
        fun forInput(type:Int,options:Int):TypingPolicy{
            val variation=type and 0xff0
            val safe=ImeFieldPolicy.forInput(type,options).voice&&type and 0xf==1&&
                variation in setOf(0,0x40,0x50,0x60,0xa0)&&type and 0x80000==0&&type and 0x10000==0
            // Chromium signals HTML autocorrect off by clearing AUTO_CORRECT, not NO_SUGGESTIONS.
            return TypingPolicy(safe,safe&&variation!=0x60&&(variation!=0xa0||type and 0x8000!=0))
        }
    }
}
/** Local lexical ranking, not a neural language model or automatic learning. */
class TypingSuggestions(entries:List<SwipeLexeme>,personal:List<String>){
    private val words=entries.filterNot{it.offensive}.associateBy{it.word}
    private val prefixes=TypingPrefixIndex(words.values.toList())
    // Portable vocabulary has up to100,000 entries, in addition to the legacy
    // manual list. Index once on the worker; never scan that list per keypress.
    private val saved=buildMap<String,String>{
        require(personal.size<=100_200)
        personal.filter{it.length in 1..48&&word.matches(it)}.forEach{putIfAbsent(it.lowercase(Locale.ROOT),it)}
    }
    private val savedPrefixes=TypingPrefixIndex(saved.map{(key,display)->SwipeLexeme(key,300,display,false)})
    companion object{private val word=Regex("[A-Za-z]+(?:'[A-Za-z]+)*")}
    fun suggest(input:String):TypingChoices{
        if(input.length !in 1..48||!word.matches(input))return TypingChoices(input,listOf(input),null)
        val key=input.lowercase(Locale.ROOT)
        val edits=LinkedHashMap<String,Int>()
        fun add(value:String,cost:Int){if(value!=key&&(value in words||value in saved))edits.merge(value,cost,::minOf)}
        // One edit only, bounded by48*26 candidates; no full-dictionary fuzzy scan.
        if(key.length>=3){
            for(i in key.indices){
                add(key.removeRange(i,i+1),1)
                if(i+1<key.length)add(key.substring(0,i)+key[i+1]+key[i]+key.substring(i+2),1)
                for(c in 'a'..'z')add(key.substring(0,i)+c+key.substring(i+1),1)
            }
            if(key.length<48)for(i in 0..key.length)for(c in 'a'..'z')add(key.substring(0,i)+c+key.substring(i),1)
        }
        // Exact globally ranked prefix completions; no arbitrary lexicographic cutoff.
        prefixes.topTwo(key).forEach{add(it,2)}
        savedPrefixes.topTwo(key).forEach{add(it,2)}
        fun score(value:String)=(if(value in saved)300 else words[value]?.frequency?:0)-(edits[value]?:0)*16
        val ranked=edits.keys.sortedWith(compareByDescending<String>{score(it)}.thenBy{it}).take(2)
        fun display(value:String):String{
            val spelling=saved[value]?:words[value]?.display?:value
            return when{input.all{!it.isLetter()||it.isUpperCase()}->spelling.uppercase(Locale.ROOT)
                input.first().isUpperCase()->spelling.replaceFirstChar{it.uppercaseChar()}
                else->spelling}
        }
        val choices=(listOf(input)+ranked.map(::display)).distinct()
        val best=ranked.firstOrNull()
        val currentFrequency=words[key]?.frequency
        val automatic=best!=null&&best !in saved&&key !in saved&&edits[best]==1&&key.length>=3&&
            (input==input.lowercase(Locale.ROOT)||input==input.uppercase(Locale.ROOT)||input.drop(1).all{it.isLowerCase()||it=='\''})&&
            (if(currentFrequency==null)(words[best]?.frequency?:0)>=100 else (words[best]?.frequency?:0)-currentFrequency>=96)&&
            (ranked.getOrNull(1)?.let{score(best)-score(it)>=16}?:true)
        return TypingChoices(input,choices,if(automatic)choices.indexOf(display(best!!)).takeIf{it>0} else null)
    }
}

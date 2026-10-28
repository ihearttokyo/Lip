package dev.lip.keyboard

import java.util.Locale

/** Public corpus counts only: no personal history, adaptation, text logging or network. */
class NextWordSuggestions private constructor(private val rows:Map<String,List<String>>,private val display:(String)->String) {
    internal fun samePublicRowsAs(other:NextWordSuggestions)=rows==other.rows
    fun suggest(cursor:ImeCursor):List<String> {
        if(!cursor.eligible||!cursor.before.endsWith(" "))return emptyList()
        val end=cursor.before.trimEnd(' ')
        val matches=word.findAll(end).toList()
        val last=matches.lastOrNull()?.takeIf{it.range.last==end.lastIndex}?:return emptyList()
        // If the narrow window begins inside a word, that fragment is not context.
        fun complete(match:MatchResult)=match.value.length<=48&&(match.range.first>0||cursor.start<=cursor.before.length)
        if(!complete(last))return emptyList()
        val one=last.value.lowercase(Locale.ROOT)
        val previous=matches.getOrNull(matches.lastIndex-1)
        val two=previous?.takeIf{complete(it)&&end.substring(it.range.last+1,last.range.first).all{c->c==' '}}
            ?.let{it.value.lowercase(Locale.ROOT)+" "+one}
        return ((two?.let(rows::get)?:emptyList())+(rows[one]?:emptyList())).distinct().take(3).map(display)
    }
    companion object {
        private val word=Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
        /** Only after the exact APK byte count and SHA-256 have passed. Strict parse remains the importer/test oracle. */
        internal fun fromVerifiedText(text:String,display:(String)->String):NextWordSuggestions {
            require(text.startsWith("#lip-nextword-en-v1\n"))
            val rows=HashMap<String,MutableList<String>>(50_000)
            var at=text.indexOf('\n')+1
            while(at<text.length){
                val first=text.indexOf('\t',at);val second=text.indexOf('\t',first+1);val end=text.indexOf('\n',second+1)
                check(first>at&&second>first&&end>second)
                rows.getOrPut(text.substring(at,first)){ArrayList(3)}.add(text.substring(first+1,second));at=end+1
            }
            return NextWordSuggestions(rows.mapValues{it.value.toList()},display)
        }
        internal fun parse(text:String,display:(String)->String={it}):NextWordSuggestions {
            require(text.length<=6_000_000)
            val lines=text.lineSequence().iterator();require(lines.hasNext()&&lines.next()=="#lip-nextword-en-v1")
            val rows=linkedMapOf<String,MutableList<String>>();var previous="";var previousCount=Int.MAX_VALUE;var previousWord="";var count=0
            for(line in lines){
                if(line.isEmpty()){require(!lines.hasNext());break}
                require(line.length<=180)
                val parts=line.split('\t');require(parts.size==3)
                val context=parts[0];val next=parts[1];val frequency=parts[2].toIntOrNull()
                require(context.split(' ').size in 1..2&&context.split(' ').all{it.length in 1..48&&word.matches(it)&&it==it.lowercase(Locale.ROOT)})
                require(next.length in 1..48&&word.matches(next)&&next==next.lowercase(Locale.ROOT)&&frequency!=null&&frequency>0)
                require(context>=previous)
                if(context==previous)require(frequency<previousCount||frequency==previousCount&&next>previousWord)
                val values=rows.getOrPut(context){mutableListOf()};require(next !in values&&values.size<3);values.add(next)
                require(++count<=225_000);previous=context;previousCount=frequency;previousWord=next
            }
            require(rows.isNotEmpty());return NextWordSuggestions(rows.mapValues{it.value.toList()},display)
        }
    }
}

class NextWordRequest internal constructor(internal val ticket:ImeTicket,internal val connection:Any) {
    internal var completed=false
    var choices:List<String> = emptyList();internal set
}
/** Append-only, single-use caret claim. Equality never substitutes for observed edit revision. */
class NextWordOwner(private val session:ImeSession) {
    var current:NextWordRequest?=null;private set
    fun clear(){current=null}
    fun begin(cursor:ImeCursor?,connection:Any?):NextWordRequest? {
        clear();if(connection==null)return null
        return session.capture(cursor)?.let{NextWordRequest(it,connection).also{current=it}}
    }
    fun complete(request:NextWordRequest,words:List<String>,cursor:ImeCursor?,connection:Any?):Boolean {
        if(current!==request||request.completed||connection!==request.connection||!session.isCurrent(request.ticket,cursor)){if(current===request)clear();return false}
        require(words.size<=3&&words.distinct()==words&&words.all{TypingBoundary.valid(it)&&TypingBoundary.suffix(it).isEmpty()&&!it.endsWith("'")})
        request.completed=true;request.choices=words.toList();return true
    }
    fun claim(request:NextWordRequest,index:Int,cursor:ImeCursor?,connection:Any?):String? {
        if(current!==request)return null
        clear()
        return if(connection===request.connection&&index in request.choices.indices&&session.claim(request.ticket,cursor))request.choices[index] else null
    }
}

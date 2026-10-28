package dev.lip.keyboard

import java.text.Normalizer
import java.util.Locale

data class EmojiItem(val id:String,val sequence:String,val title:String,val category:Int,val subgroup:String,val keywords:List<String>)

/** Pinned public metadata only. No network, user history, host text or learned ranking. */
class EmojiCatalog private constructor(val items:List<EmojiItem>,prepared:List<Indexed>?) {
    constructor(items:List<EmojiItem>):this(items,null)
    private data class Indexed(val item:EmojiItem,val title:String,val phrases:Set<String>,val words:Set<String>)
    private val index=prepared?:items.map{item->
        val phrases=item.keywords.map(::normalize).filter{it.isNotEmpty()}.toSet()
        Indexed(item,normalize(item.title),phrases,phrases.flatMap{it.split(' ')}.toSet())
    }
    // Exact Unicode families, not English-title heuristics. Preserve ZWJ, gender,
    // hair and direction: removing them would merge distinct requested emoji.
    private val toneFamilies=items.groupBy{toneFamily(it.sequence)}
    fun skinToneVariants(item:EmojiItem):List<EmojiItem> {
        val family=toneFamilies[toneFamily(item.sequence)]?:return emptyList()
        return if(family.size>1&&family.any{it==item}&&family.any{entry->entry.sequence.codePoints().anyMatch{it in 0x1f3fb..0x1f3ff}})family else emptyList()
    }
    val categories get()=CATEGORIES
    fun search(query:String,category:Int?=null):List<EmojiItem>{
        if(query.length>64||category!=null&&category !in CATEGORIES.indices)return emptyList()
        val exact=query.trim().replace("\ufe0f","")
        val normalized=normalize(query);val words=normalized.split(' ').filter{it.isNotEmpty()}
        if(query.isNotBlank()&&words.isEmpty())return items.filter{(category==null||it.category==category)&&it.sequence.replace("\ufe0f","")==exact}
        return index.asSequence().filter{category==null||it.item.category==category}
            .filter{row->words.all{term->row.words.any{it.startsWith(term)}}}
            .sortedWith(compareBy<Indexed>{if(query.isBlank())0 else when{it.title==normalized->0;normalized in it.phrases->1;words.all{term->term in it.words}->2;else->3}})
            .map{it.item}.toList() // Stable source order breaks ties; no personalized ranking.
    }
    fun available(canRender:(String)->Boolean):EmojiCatalog {
        val selected=index.filter{canRender(it.item.sequence)}
        return EmojiCatalog(selected.map{it.item},selected) // Reuse identical immutable search rows; no second normalization pass.
    }
    companion object {
        private fun toneFamily(sequence:String):String=buildString {
            sequence.codePoints().forEach{if(it !in 0x1f3fb..0x1f3ff&&it!=0xfe0f)appendCodePoint(it)}
        }
        val CATEGORIES=listOf("Smileys & Emotion","People & Body","Animals & Nature","Food & Drink","Travel & Places","Activities","Objects","Symbols","Flags")
        private val marks=Regex("\\p{M}+")
        private val nonWords=Regex("[^a-z0-9]+")
        private val spaces=Regex(" +")
        fun normalize(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKD).replace(marks,"").lowercase(Locale.ROOT)
            .replace(nonWords," ").trim().replace(spaces," ")
        /** Only AFTER exact pinned SHA/size authentication. Strict raw parser remains the source/test oracle. */
        internal fun readAuthenticatedIndex(tsv:String):EmojiCatalog {
            val rows=tsv.lineSequence().filter{it.isNotEmpty()}.map{line->
                val f=line.split('\t');check(f.size==9)
                val item=EmojiItem(f[0],f[1],f[2],f[3].toInt(),f[4],f[5].split('|'))
                Indexed(item,f[6],f[7].split('|').toSet(),f[8].split(' ').toSet())
            }.toList()
            return EmojiCatalog(rows.map{it.item},rows)
        }
        fun parse(tsv:String):EmojiCatalog {
            val items=tsv.lineSequence().filter{it.isNotEmpty()}.map{line->
                val fields=line.split('\t');require(fields.size==6)
                val (id,sequence,title,group,subgroup)=fields
                val keywords=fields[5]
                require(id.matches(Regex("[0-9a-f]+(?:_[0-9a-f]+)*"))&&title.isNotBlank()&&title.length<=160&&subgroup.length<=64&&subgroup.matches(Regex("[a-z0-9 &-]+")))
                require(sequence.codePoints().toArray().joinToString("_"){it.toString(16)}==id&&sequence.length<=64)
                val category=group.toInt();require(category in CATEGORIES.indices)
                val terms=keywords.split('|');require(terms.isNotEmpty()&&terms.size<=64&&terms.all{it.isNotBlank()&&it.length<=160})
                EmojiItem(id,sequence,title,category,subgroup,terms)
            }.toList()
            require(items.size<=5000&&items.map{it.id}.distinct().size==items.size)
            return EmojiCatalog(items)
        }
    }
}

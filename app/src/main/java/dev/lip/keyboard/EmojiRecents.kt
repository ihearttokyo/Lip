package dev.lip.keyboard

/** Explicit opt-in, current-visible-field memory only. No persistence or timestamps. */
class EmojiRecents {
    var enabled=false;private set
    private val ids=ArrayDeque<String>()
    fun enable(){enabled=true}
    fun clear(){enabled=false;ids.clear()}
    fun record(id:String,privateField:Boolean){if(!enabled||privateField)return;ids.remove(id);ids.addFirst(id);while(ids.size>32)ids.removeLast()}
    fun items(catalog:EmojiCatalog)=if(enabled)ids.mapNotNull{id->catalog.items.find{it.id==id}}else emptyList()
}

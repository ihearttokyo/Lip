package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class EmojiCatalogTest {
    private val catalog=EmojiCatalog(listOf(
        EmojiItem("2764_fe0f","❤️","red heart",0,"emotion",listOf("red heart","love","heart","red")),
        EmojiItem("1f49b","💛","yellow heart",0,"emotion",listOf("yellow heart","love","heart")),
        EmojiItem("2615","☕","hot beverage",3,"drink",listOf("hot beverage","coffee","tea","drink")),
        EmojiItem("1f1ef_1f1f5","🇯🇵","flag: Japan",8,"country-flag",listOf("flag: Japan","JP","flag"))
    ))
    @Test fun nameKeywordPrefixAndMultiwordSearch(){assertEquals("☕",catalog.search("COFF").single().sequence);assertEquals("❤️",catalog.search("red heart").single().sequence);assertEquals("🇯🇵",catalog.search("flag jap").single().sequence);assertEquals(2,catalog.search("love").size)}
    @Test fun categoryEmptyQueryAndUnknownDoNotBroaden(){assertEquals(2,catalog.search("",0).size);assertTrue(catalog.search("unlikely").isEmpty());assertTrue(catalog.search("???").isEmpty());assertTrue(catalog.search("coffee",0).isEmpty());assertTrue(catalog.search("",-1).isEmpty());assertTrue(catalog.search("x".repeat(65)).isEmpty())}
    @Test fun literalEmojiAllowsMissingPresentationSelector(){assertEquals("❤️",catalog.search("❤").single().sequence);assertEquals("🇯🇵",catalog.search("🇯🇵").single().sequence)}
    @Test fun filteringDoesNotChangeSequenceOrOrder(){assertEquals(listOf("❤️","💛","☕"),catalog.available{it!="🇯🇵"}.search("").map{it.sequence})}
    @Test fun malformedRowAndDuplicatesRefused(){for(row in listOf("2764\t❤️\tred heart\t0\temotion\tlove", "2764_fe0f\t❤️\tred heart\t9\temotion\tlove", "bad")){try{EmojiCatalog.parse(row);fail()}catch(_:IllegalArgumentException){}};val valid="2764_fe0f\t❤️\tred heart\t0\temotion\tlove";try{EmojiCatalog.parse("$valid\n$valid");fail()}catch(_:IllegalArgumentException){}}
    @Test fun realAdmittedCatalogParsesWithoutLosingSequences(){val all=EmojiCatalog.parse(java.io.File("src/main/assets/emoji/english.tsv").readText());assertEquals(3944,all.items.size);assertTrue(all.search("coffee").any{it.sequence=="☕"});assertTrue(all.search("family").any{it.sequence=="👨‍👩‍👧‍👦"});assertTrue(all.search("japan").any{it.sequence=="🇯🇵"})}
    @Test fun filteredIndexEqualsFreshRebuildAndEveryOriginalSequence(){val all=EmojiCatalog.parse(java.io.File("src/main/assets/emoji/english.tsv").readText());val subset=all.available{it.codePointAt(0)%2==0};val rebuilt=EmojiCatalog(subset.items);val field=EmojiCatalog::class.java.getDeclaredField("index").apply{isAccessible=true};assertEquals(field.get(rebuilt),field.get(subset));for(q in listOf("","coffee","heart","japan","flag","face","family","skin tone","blue"))assertEquals(rebuilt.search(q),subset.search(q))}
    @Test fun assetIntegrityRefusesCorruptionTruncationAndExtension(){val bytes=java.io.File("src/main/assets/emoji/english-indexed.tsv").readBytes();assertEquals(3944,EmojiAssets.verify(bytes).items.size);for(bad in listOf(bytes.copyOf(bytes.size-1),bytes+byteArrayOf(1),bytes.copyOf().apply{this[100]=(this[100].toInt() xor 1).toByte()})){try{EmojiAssets.verify(bad);fail()}catch(_:IllegalStateException){}}}
    @Test fun authenticatedPrecomputedIndexEqualsEveryStrictOriginalRow(){val raw=EmojiCatalog.parse(java.io.File("src/main/assets/emoji/english.tsv").readText());val fast=EmojiAssets.verify(java.io.File("src/main/assets/emoji/english-indexed.tsv").readBytes());assertEquals(raw.items,fast.items);val field=EmojiCatalog::class.java.getDeclaredField("index").apply{isAccessible=true};assertEquals(field.get(raw),field.get(fast));for(q in listOf("","coffee","heart red","japan","family man woman girl boy","skin tone","unlikelyzz"))assertEquals(raw.search(q),fast.search(q))}
}

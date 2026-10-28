package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class EmojiVariantsTest {
    private val catalog by lazy { EmojiCatalog.parse(java.io.File("src/main/assets/emoji/english.tsv").readText()) }
    private fun item(value:String)=catalog.items.single{it.sequence==value}
    @Test fun thumbHasExactSixChoicesFromEveryMember(){
        val expected=listOf("👍","👍🏻","👍🏼","👍🏽","👍🏾","👍🏿")
        for(sequence in expected)assertEquals(expected,catalog.skinToneVariants(item(sequence)).map{it.sequence})
    }
    @Test fun singletonAndForgedItemHaveNoChoices(){
        assertTrue(catalog.skinToneVariants(item("😀")).isEmpty())
        assertTrue(catalog.skinToneVariants(item("👍").copy(title="forged")).isEmpty())
    }
    @Test fun presentationSelectorsMatchButGenderHairAndDirectionNeverCollapse(){
        val variants=catalog.skinToneVariants(item("☝️"));assertEquals(6,variants.size)
        val woman=catalog.items.first{it.title=="woman running"}
        val family=catalog.skinToneVariants(woman)
        assertEquals(6,family.size);assertTrue(family.all{it.title.startsWith("woman running")})
        assertFalse(family.any{it.title.startsWith("man running")||it.title.contains("facing right")})
        val hair=catalog.skinToneVariants(item("👩‍🦰"))
        assertEquals(6,hair.size);assertTrue(hair.all{it.sequence.contains("🦰")})
    }
    @Test fun mixedToneFamiliesRetainExactPinnedMembersNoInventedCombinations(){
        val mixed=item("🫱🏻‍🫲🏿")
        val family=catalog.skinToneVariants(mixed)
        assertTrue(family.size>6);assertTrue(family.contains(mixed))
        assertTrue(family.all{catalog.items.contains(it)&&it.sequence.contains("\u200d")})
        assertFalse(family.any{it.sequence=="🤝"}) // Different Unicode skeleton, not guessed equivalent.
    }
    @Test fun availabilityFilteringCannotResurrectUnsupportedToneOrBase(){
        val selected=catalog.available{it=="👍🏻"||it=="👍🏿"||it=="😀"}
        assertEquals(listOf("👍🏻","👍🏿"),selected.skinToneVariants(item("👍🏻")).map{it.sequence})
        assertTrue(selected.skinToneVariants(item("👍")).isEmpty())
        assertTrue(catalog.available{it=="👍🏻"}.skinToneVariants(item("👍🏻")).isEmpty())
    }
    @Test fun everyFamilyIsSymmetricAndInOriginalSourceOrder(){
        val positions=catalog.items.mapIndexed{index,item->item.id to index}.toMap()
        for(entry in catalog.items){
            val family=catalog.skinToneVariants(entry)
            if(family.isNotEmpty()){
                assertTrue(family.contains(entry));assertEquals(family.size,family.map{it.id}.distinct().size)
                assertEquals(family.map{positions.getValue(it.id)}.sorted(),family.map{positions.getValue(it.id)})
                for(member in family)assertEquals(family,catalog.skinToneVariants(member))
            }
        }
    }
}

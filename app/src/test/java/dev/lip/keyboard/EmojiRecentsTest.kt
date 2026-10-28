package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class EmojiRecentsTest {
    @Test fun offByDefaultBoundedPrivateRefusalAndExplicitClear(){val items=(0..39).map{EmojiItem("$it","😀","face",0,"face",listOf("face"))};val catalog=EmojiCatalog(items);val recent=EmojiRecents();recent.record("1",false);assertTrue(recent.items(catalog).isEmpty());recent.enable();items.forEach{recent.record(it.id,false)};assertEquals(32,recent.items(catalog).size);assertEquals("39",recent.items(catalog).first().id);recent.record("0",true);assertEquals("39",recent.items(catalog).first().id);recent.record("38",false);assertEquals("38",recent.items(catalog).first().id);assertEquals(32,recent.items(catalog).size);recent.clear();assertFalse(recent.enabled);assertTrue(recent.items(catalog).isEmpty())}
}

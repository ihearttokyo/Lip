package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class EmojiSearchTest {
    @Test fun queryIsEphemeralAndModeIncarnationOwned(){val s=EmojiSearch();s.append("no");assertEquals("",s.query);s.begin();val first=s.token;s.append("coffee");s.finish();s.append("host?");assertEquals("coffee",s.query);s.begin();assertNotSame(first,s.token);s.clear();assertEquals("",s.query)}
    @Test fun boundUnicodeAndControlCharacters(){val s=EmojiSearch();s.begin();s.append("x".repeat(63));s.append("😀");assertEquals(63,s.query.length);s.clear();s.begin();s.append("😀");s.delete();assertEquals("",s.query);s.append("\ud83d");s.append("\ude00");s.append("\n");assertEquals("",s.query)}
}

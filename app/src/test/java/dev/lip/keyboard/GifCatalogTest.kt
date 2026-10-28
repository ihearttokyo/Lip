package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class GifCatalogTest{
    @Test fun catalogIsBoundedAndSearchableOffline(){
        assertEquals(12,GifCatalog.items.size);assertEquals(12,GifCatalog.items.map{it.id}.distinct().size)
        assertEquals(listOf("laugh"),GifCatalog.search("LOL").map{it.id})
        assertTrue(GifCatalog.search("thanks").any{it.id=="thanks"})
        assertTrue(GifCatalog.search("no-result-query").isEmpty())
        assertThrows(IllegalArgumentException::class.java){GifCatalog.search("x".repeat(65))}
        assertTrue(GifCatalog.items.all{it.bytes in 1..2_100_000&&it.sha256.matches(Regex("[a-f0-9]{64}"))&&it.source.startsWith("https://fonts.gstatic.com/")})
    }
    @Test fun onlyGifCompatibleEditorsAdmitted(){
        for(mime in listOf("image/gif","image/*","*/*","IMAGE/GIF"))assertTrue(GifCatalog.accepts(arrayOf(mime)))
        for(mime in listOf("text/plain","image/png","video/*"))assertFalse(GifCatalog.accepts(arrayOf(mime)))
        assertFalse(GifCatalog.accepts(null))
    }
    @Test fun oneShotOwnerChecksCursorEditorMimeAndConnection(){
        val session=ImeSession();session.beginEditor();val owner=GifRequestOwner(session);val connection=Any()
        val cursor=ImeCursor("owned.fixture",1,0,0,"","",false);val gif=GifCatalog.items.first();val mime=arrayOf("image/gif")
        val first=owner.begin(gif,cursor,connection,mime)!!
        assertNull(owner.begin(gif,cursor,connection,mime))
        assertTrue(owner.claim(first,cursor,connection,mime));assertFalse(owner.claim(first,cursor,connection,mime))
        val moved=owner.begin(gif,cursor,connection,mime)!!
        assertFalse(owner.claim(moved,cursor.copy(start=1,end=1,before="a"),connection,mime))
        val stale=owner.begin(gif,cursor,connection,mime)!!;session.beginEditor()
        assertFalse(owner.claim(stale,cursor,connection,mime))
        assertNull(owner.begin(gif,cursor.copy(protectedField=true),connection,mime))
        assertNull(owner.begin(gif,cursor.copy(end=1),connection,mime))
    }
    @Test fun cancelAndMimeChangeNeverRetargetOrRetry(){
        val session=ImeSession();session.beginEditor();val owner=GifRequestOwner(session);val connection=Any()
        val cursor=ImeCursor("owned.fixture",1,0,0,"","",false);val gif=GifCatalog.items.first()
        val old=owner.begin(gif,cursor,connection,arrayOf("image/gif"))!!;owner.cancel()
        val current=owner.begin(gif,cursor,connection,arrayOf("image/gif"))!!
        assertFalse(owner.claim(old,cursor,connection,arrayOf("image/gif")));assertSame(current,owner.current)
        assertFalse(owner.claim(current,cursor,connection,arrayOf("image/png")))
    }
}

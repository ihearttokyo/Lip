package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class SwipeRequestOwnerTest {
    private val cursor=ImeCursor("test.disposable",12,4,4,"left"," right",false)
    @Test fun canceledAndSupersededResultsNeverClaim() {
        val session=ImeSession();session.beginEditor();val owner=SwipeRequestOwner(session);val connection=Any()
        val first=owner.begin(cursor,connection)!!;owner.cancel();assertFalse(owner.claim(first,cursor,connection))
        val old=owner.begin(cursor,connection)!!;val current=owner.begin(cursor,connection)!!
        assertFalse(owner.claim(old,cursor,connection));assertTrue(owner.claim(current,cursor,connection));assertFalse(owner.claim(current,cursor,connection))
    }
    @Test fun caretAbaNewEditorAndConnectionChangeRefuse() {
        val session=ImeSession();session.beginEditor();val owner=SwipeRequestOwner(session);val connection=Any()
        val moved=owner.begin(cursor,connection)!!;session.editorChanged();assertFalse(owner.claim(moved,cursor,connection))
        val editor=owner.begin(cursor,connection)!!;session.beginEditor();assertFalse(owner.claim(editor,cursor,connection))
        val replaced=owner.begin(cursor,connection)!!;assertFalse(owner.claim(replaced,cursor,Any()));assertFalse(owner.claim(replaced,cursor,connection))
    }
    @Test fun secureMissingContextAndMalformedSelectionRefuse() {
        val session=ImeSession();session.beginEditor();val owner=SwipeRequestOwner(session)
        assertNull(owner.begin(cursor.copy(protectedField=true),Any()));assertNull(owner.begin(cursor.copy(end=5),Any()))
        assertNull(owner.begin(null,Any()));assertNull(owner.begin(cursor,null))
        val request=owner.begin(cursor,Any())!!;assertFalse(owner.claim(request,cursor.copy(before="changed"),Any()))
    }
}

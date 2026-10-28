package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class EmojiPickOwnerTest {
    @Test fun pickIsSingleUseAndRevokedByEpochRevisionConnectionPanelCaret(){
        for(mode in 0..5){val session=ImeSession();session.beginEditor();val owner=EmojiPickOwner(session);val conn=Any();val panel=Any();val ticket=owner.capture(conn,panel,3,5)!!
            when(mode){0->session.editorChanged();1->session.beginEditor()}
            val okay=owner.claim(ticket,if(mode==2)Any() else conn,if(mode==3)Any() else panel,if(mode==4)4 else 3,5)
            assertEquals(mode==5,okay);if(okay){assertFalse(owner.claim(ticket,conn,panel,3,5));assertTrue(owner.claimedCurrent(ticket,conn,panel,3,5));session.editorChanged();assertFalse(owner.claimedCurrent(ticket,conn,panel,3,5))}
        }
    }
    @Test fun absentUnknownEditorRefused(){val s=ImeSession();val owner=EmojiPickOwner(s);assertNull(owner.capture(Any(),Any(),0,0));s.beginEditor();assertNull(owner.capture(null,Any(),0,0));assertNull(owner.capture(Any(),Any(),-1,0))}
}

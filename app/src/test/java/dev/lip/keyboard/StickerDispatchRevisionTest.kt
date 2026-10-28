package dev.lip.keyboard

import org.junit.Assert.*
import org.junit.Test

class StickerDispatchRevisionTest {
    private class Fixture {
        val session=ImeSession().apply{beginEditor()};val connection=Any();val window=Any()
        val cursor=ImeCursor("owned.fixture",7,0,0,"","",false)
        val owner=StickerHandoffOwner(session)
        val asset=StickerAssetIdentity("00000000-0000-0000-0000-000000000001","a".repeat(64),"synthetic","b".repeat(64),"image/png",78)
        val receiver=StickerReceiverBinding("owned.fixture",12001)
        val request=owner.begin(asset,cursor,connection,window,receiver,arrayOf("image/png"),false)!!
        fun claim()=owner.claim(request,asset,cursor,connection,window,receiver,arrayOf("image/png"),false)
        fun finish(cursor:ImeCursor?=this.cursor,connection:Any?=this.connection,window:Any?=this.window)=StickerDispatchRevision.finish(session,request,cursor,connection,window)
    }
    @Test fun exactClaimedDispatchRevisesOnce(){val f=Fixture();assertTrue(f.claim());assertTrue(f.finish());assertEquals(1L to 1L,f.session.manualGeneration());assertFalse(f.finish())}
    @Test fun unclaimedOrReentrantRevisionCannotBeRevised(){val a=Fixture();assertFalse(a.finish());val f=Fixture();assertTrue(f.claim());f.session.editorChanged();val before=f.session.manualGeneration();assertFalse(f.finish());assertEquals(before,f.session.manualGeneration())}
    @Test fun replacementEpochOrWindowOrConnectionCannotBeRevised(){for(kind in 0..2){val f=Fixture();assertTrue(f.claim());if(kind==0)f.session.beginEditor();val before=f.session.manualGeneration();assertFalse(f.finish(connection=if(kind==1)Any() else f.connection,window=if(kind==2)Any() else f.window));assertEquals(before,f.session.manualGeneration())}}
    @Test fun reentrantCursorChangeCannotBeRevised(){val f=Fixture();assertTrue(f.claim());val before=f.session.manualGeneration();assertFalse(f.finish(cursor=f.cursor.copy(before="changed")));assertEquals(before,f.session.manualGeneration())}
}

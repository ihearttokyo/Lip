package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class ClipboardHistoryTest {
    private fun id(n:Int)="00000000-0000-0000-0000-"+n.toString().padStart(12,'0')
    @Test fun boundedDuplicatesPromoteWithoutDuplicatingPins(){
        var list=emptyList<SavedClip>()
        for(i in 1..25)list=ClipboardHistory.add(list,"clip$i",i.toLong(),id(i))
        assertEquals(20,list.size);assertEquals("clip25",list.first().text);assertEquals("clip6",list.last().text)
        list=ClipboardHistory.pin(list,id(6),30)
        list=ClipboardHistory.add(list,"clip6",40,id(99))
        assertEquals(id(6),list.first().id);assertTrue(list.first().pinned);assertEquals(20,list.size)
    }
    @Test fun ttlAndBackwardClockExpireUnpinnedButPinsAreExplicitlyPersistent(){
        val a=SavedClip(id(1),"a",100);val b=SavedClip(id(2),"b",100,true)
        assertEquals(listOf(b),ClipboardHistory.prune(listOf(a,b),100+ClipboardHistory.TTL_MS))
        assertEquals(listOf(b),ClipboardHistory.prune(listOf(a,b),99))
        assertEquals(listOf(a,b),ClipboardHistory.prune(listOf(a,b),101))
    }
    @Test fun pinLimitDoesNotEvictPinnedItems(){
        var list=(1..20).map{SavedClip(id(it),"text$it",1,it<=10)}
        assertThrows(IllegalStateException::class.java){ClipboardHistory.pin(list,id(11),2)}
        list=ClipboardHistory.add(list,"new",3,id(21))
        assertEquals(10,list.count{it.pinned});assertTrue(list.any{it.id==id(10)})
    }
    @Test fun repeatedStalePinCommandCannotTurnIntoUnpin(){
        val initial=listOf(SavedClip(id(1),"pinned by user",1))
        val pinned=ClipboardHistory.setPinned(initial,id(1),true,2)
        assertEquals(pinned,ClipboardHistory.setPinned(pinned,id(1),true,3))
        assertFalse(ClipboardHistory.setPinned(pinned,id(1),false,4).single().pinned)
    }
    @Test fun boundedWellFormedUnicodeOnly(){
        assertTrue(ClipboardHistory.admissible("日本語\n😀"));assertTrue(ClipboardHistory.admissible("x".repeat(4096)))
        for(value in listOf(""," \n","x".repeat(4097),"\ud800","\udc00","x\ud800y"))assertFalse(ClipboardHistory.admissible(value))
    }
    @Test fun strictRoundTripCorruptionNeverOverwritesOriginal(){
        val list=listOf(SavedClip(id(1),"one\ntwo 😀",123,true));assertEquals(list,ClipboardHistory.decode(ClipboardHistory.encode(list)))
        var payload="{\"version\":2,\"items\":[]}";var writes=0
        val repo=ClipboardHistoryRepository({payload},{payload=it;writes++},{payload=""})
        assertThrows(IllegalArgumentException::class.java){repo.update(100,{true})}
        assertEquals(0,writes);assertTrue(payload.contains("2"))
    }
    @Test fun revokedWorkCannotReadOrWriteAndMidWriteRevokeCannotPublish(){
        var payload:String?=null;var reads=0;var writes=0;var allowed=false
        val repo=ClipboardHistoryRepository({reads++;payload},{writes++;payload=it;allowed=false},{payload=null})
        assertThrows(IllegalStateException::class.java){repo.update(1,{allowed})};assertEquals(0,reads);assertEquals(0,writes)
        allowed=true
        assertThrows(IllegalStateException::class.java){repo.update(1,{allowed}){ClipboardHistory.add(it,"test",1,id(1))}}
        assertEquals(1,writes);repo.clear();assertNull(payload)
    }
    @Test fun capturedUnpinTimeBeforeTransactionKeepsItForFullTtl(){
        var payload:String?=ClipboardHistory.encode(listOf(SavedClip(id(1),"keep after unpin",1,true)))
        val repo=ClipboardHistoryRepository({payload},{payload=it},{payload=null})
        val clickTime=100L
        val result=repo.update(clickTime+1,{true}){ClipboardHistory.setPinned(it,id(1),false,clickTime)}
        assertEquals("keep after unpin",result.single().text);assertFalse(result.single().pinned)
        assertTrue(ClipboardHistory.live(result.single(),clickTime+ClipboardHistory.TTL_MS-1))
    }
    @Test fun startupAndDelayedCaptureArePrunedBeforeExposure(){
        val expired=SavedClip(id(1),"expired",1)
        val pinned=SavedClip(id(2),"pinned",1,true)
        var payload:String?=ClipboardHistory.encode(listOf(expired,pinned))
        val repo=ClipboardHistoryRepository({payload},{payload=it},{payload=null})
        val now=ClipboardHistory.TTL_MS+2
        assertEquals(listOf(pinned),repo.update(now,{true}){ClipboardHistory.add(it,"delayed",1,id(3))})
        assertEquals(listOf(pinned),ClipboardHistory.decode(payload!!))
    }
    @Test fun writeVerificationFailureAndEraseFailureNeverReportSuccess(){
        val repo=ClipboardHistoryRepository({null},{},{})
        assertThrows(IllegalStateException::class.java){repo.update(1,{true})}
        val retained=ClipboardHistory.encode(emptyList())
        val refusing=ClipboardHistoryRepository({retained},{},{})
        assertThrows(IllegalStateException::class.java){refusing.clear()}
    }
}

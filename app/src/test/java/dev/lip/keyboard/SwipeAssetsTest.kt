package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class SwipeAssetsTest {
    private fun parse(vararg lines:String)=SwipeLexiconData.parse("#lip-swipe-english-v1\n"+lines.joinToString("\n")+"\n")
    @Test fun flagsDisplayAndOffensivePolicyArePreserved() {
        val data=parse("hello\t150\tHello\t-","iphone\t100\tiPhone\ttechnical","rude\t1\trude\toffensive")
        assertEquals("iPhone",data.display("iphone"));assertEquals(2,data.admitted().size);assertEquals(3,data.admitted(true).size)
        assertNull(data.display("unknown"))
    }
    @Test fun malformedOrderingAndFlagsRefuse() {
        for(lines in listOf(arrayOf("hello\t150\tHello\t-","hello\t1\thello\t-"),arrayOf("hello\t256\thello\t-"),arrayOf("hello\t100\thello\tnonword"),arrayOf("hello\t100\tDIFFERENT\t-"),arrayOf("hello\t100\thello\tunknown")))
            assertThrows(IllegalArgumentException::class.java){parse(*lines)}
    }
    @Test fun bundledPinsDoNotAcceptAValidLookingUnverifiedFile() {
        assertThrows(IllegalArgumentException::class.java){SwipeLexiconData.verified("#lip-swipe-english-v1\nhello\t1\thello\t-\n".toByteArray())}
    }
}

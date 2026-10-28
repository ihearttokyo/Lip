package dev.lip.keyboard
import org.junit.Assert.*
import org.junit.Test
class KeyboardLayoutSpecTest {
    @Test fun referenceGeometryHasNumberRowAndStableEnglishMuscleMemory() {
        val rows=KeyboardLayoutSpec.rows(KeyboardPage.LETTERS,false)
        assertEquals(5,rows.size);assertEquals("1234567890",rows[0].joinToString(""){it.label})
        assertEquals("qwertyuiop",rows[1].joinToString(""){it.label})
        assertEquals(.05f,KeyboardLayoutSpec.center(rows[1],0),.0001f)
        assertEquals(.10f,KeyboardLayoutSpec.center(rows[2],1),.0001f)
        assertEquals(.075f,KeyboardLayoutSpec.center(rows[3],0),.0001f)
        assertEquals(.925f,KeyboardLayoutSpec.center(rows[3],8),.0001f)
        assertEquals(.5f,KeyboardLayoutSpec.center(rows[4],2),.0001f)
        assertEquals(5f,rows[4][2].units,0f)
        rows.forEach { assertEquals(10f,it.sumOf { k->k.units.toDouble() }.toFloat(),.0001f) }
    }
    @Test fun photoSymbolHintsAndPagesAreRealCharacters() {
        val rows=KeyboardLayoutSpec.rows(KeyboardPage.LETTERS,true)
        assertEquals("Q",rows[1][0].label);assertEquals("%",rows[1][0].hint)
        assertEquals("@",rows[2][1].hint);assertEquals("?",rows[3][7].hint)
        val symbols=KeyboardLayoutSpec.rows(KeyboardPage.SYMBOLS,false)
        assertEquals("@#\$_&-+()/",symbols[1].joinToString(""){it.label})
        assertTrue(symbols.last().any { it.action==KeyAction.NUMBER_PAD })
    }
    @Test fun alternatePagesKeepTheSameTotalHeightWithoutRoundingDrift() {
        for(total in 200..650) for(rows in listOf(4,5)) {
            val heights=KeyboardLayoutSpec.rowHeights(total,rows)
            assertEquals(total,heights.sum());assertTrue(heights.max()-heights.min()<=1)
        }
    }
    @Test fun shortLandscapeLeavesRoomForTheEditor() {
        val portrait=KeyboardLayoutSpec.rowHeightPx(1080,2400,2.625f,false)
        val landscape=KeyboardLayoutSpec.rowHeightPx(2400,1080,2.625f,true)
        assertEquals(121,portrait)
        assertTrue(landscape<portrait)
        assertTrue(landscape*5+32*2.625f<1080*.60f)
    }
    @Test fun pureBlackGrayAndWhiteAreFixedNotOffWhite() {
        assertEquals(0xff000000.toInt(),KeyboardVisualTokens.BACKDROP)
        assertEquals(0xffffffff.toInt(),KeyboardVisualTokens.FOREGROUND)
        assertEquals(0xff2c2c2e.toInt(),KeyboardVisualTokens.KEY_SURFACE)
    }
}

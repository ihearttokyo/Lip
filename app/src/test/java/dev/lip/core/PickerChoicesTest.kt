package dev.lip.core
import dev.lip.PickerChoices
import org.junit.Assert.*
import org.junit.Test
class PickerChoicesTest {
    @Test fun initialKnownChoiceDoesNotPersistADefault(){val p=PickerChoices(listOf("a","b"),"a");assertFalse(p.unavailable);assertEquals(0,p.initialIndex);assertNull(p.selected(0))}
    @Test fun unknownValueShowsPlaceholderUntilExplicitChoice(){val p=PickerChoices(listOf("en-US"),"ja-JP");assertTrue(p.unavailable);assertNull(p.selected(0));assertEquals("en-US",p.selected(1));assertNull(p.selected(1))}
    @Test fun duplicateFrameworkNotificationsAreNotNewChoices(){val p=PickerChoices(listOf("a","b"),"a");assertEquals("b",p.selected(1));assertNull(p.selected(1));assertEquals("a",p.selected(0))}
    @Test fun invalidPositionsDoNotChangeSelection(){val p=PickerChoices(listOf("a"),"a");assertNull(p.selected(-1));assertNull(p.selected(20));assertNull(p.selected(0))}
}

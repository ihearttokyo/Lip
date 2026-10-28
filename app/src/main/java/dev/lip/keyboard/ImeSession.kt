package dev.lip.keyboard

import java.util.concurrent.atomic.AtomicBoolean

/** Bounded context exists in memory only, for refusing stale asynchronous writes. */
data class ImeCursor(
    val packageName: String,
    val fieldId: Int,
    val start: Int,
    val end: Int,
    val before: String,
    val after: String,
    val protectedField: Boolean,
) {
    val eligible get() = !protectedField && packageName.isNotBlank() && start >= 0 && end == start &&
        before.length <= 64 && after.length <= 64
}

class ImeTicket internal constructor(internal val epoch: Long, internal val revision: Long, val cursor: ImeCursor) {
    internal val attempted = AtomicBoolean(false)
}

/** Session incarnation and edit revision are independent of text equality (ABA protection). */
class ImeSession {
    private var epoch = 0L
    private var revision = 0L
    private var active = false
    @Synchronized fun beginEditor() { epoch++; revision = 0; active = true }
    @Synchronized fun finishEditor() { epoch++; revision++; active = false }
    @Synchronized fun editorChanged() { revision++ }
    @Synchronized fun manualGeneration():Pair<Long,Long>?=if(active)epoch to revision else null
    @Synchronized fun editorGeneration(): Long? = if (active) epoch else null
    @Synchronized fun capture(cursor: ImeCursor?): ImeTicket? =
        if (active && cursor?.eligible == true) ImeTicket(epoch, revision, cursor) else null
    @Synchronized fun isCurrent(ticket: ImeTicket, cursor: ImeCursor?): Boolean =
        active && !ticket.attempted.get() && ticket.epoch == epoch && ticket.revision == revision &&
            cursor?.eligible == true && cursor == ticket.cursor
    /** Continuation guard for an already consumed transaction; never authorizes a second claim. */
    @Synchronized fun claimedContextCurrent(ticket:ImeTicket,cursor:ImeCursor?):Boolean =
        active && ticket.attempted.get() && ticket.epoch==epoch && ticket.revision==revision && cursor?.eligible==true && cursor==ticket.cursor
    @Synchronized fun sameEditor(ticket: ImeTicket): Boolean = active && ticket.epoch == epoch
    @Synchronized fun claim(ticket: ImeTicket, cursor: ImeCursor?): Boolean =
        isCurrent(ticket, cursor) && ticket.attempted.compareAndSet(false, true)
}

/** Android's stable InputType/IME constants, kept pure for host safety tests. */
data class ImeFieldPolicy(val protected: Boolean, val voice: Boolean, val swipe: Boolean, val learn: Boolean) {
    val manualTyping = true
    companion object {
        fun manualCursor(inputType:Int,start:Int,end:Int)=inputType and 0xf in 1..4&&start>=0&&end>=0
        fun forInput(inputType: Int, imeOptions: Int): ImeFieldPolicy {
            val clazz = inputType and 0xf
            val variation = inputType and 0xff0
            val secure = (clazz == 1 && variation in setOf(0x80, 0x90, 0xe0)) ||
                (clazz == 2 && variation == 0x10)
            val text = clazz == 1 && !secure
            val noPersonalizedLearning = imeOptions and 0x1000000 != 0
            return ImeFieldPolicy(secure, text && !noPersonalizedLearning, text && !noPersonalizedLearning, false)
        }
    }
}

/** Revoke the ticket before interrupting cleanup/ASR, so reentrant observers cannot dispatch it. */
class ImeVoiceOwner(private val session: ImeSession) {
    var ticket: ImeTicket? = null
    fun cancel(interrupt: () -> Unit): Boolean {
        if (ticket == null) return false
        ticket = null
        session.editorChanged()
        interrupt()
        return true
    }
}

/** Repeat is a held user action, never an unbound timer that follows focus. */
class ImeRepeatLease(private val session: ImeSession, private val connection: Any) {
    private val epoch = session.editorGeneration()
    private var closed = false
    fun canRun(currentConnection: Any?): Boolean = !closed && epoch != null &&
        currentConnection === connection && session.editorGeneration() == epoch
    fun close() { closed = true }
}

/** InputType-specific characters, not one keypad mislabeled as phone/date support. */
object ImeKeyLayout {
    fun numericRows(inputType: Int): List<List<String>>? {
        val clazz = inputType and 0xf
        if (clazz !in setOf(2, 3, 4)) return null
        val digits = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        return when (clazz) {
            3 -> digits + listOf(listOf("*", "0", "#"), listOf("+", "(", ")", "-"))
            4 -> {
                val variation = inputType and 0xff0
                when (variation) {
                    0x20 -> digits + listOf(listOf(":", "0", "."), listOf("a", "p", "m"))
                    0x10 -> digits + listOf(listOf("/", "0", "-"), listOf("."))
                    else -> digits + listOf(listOf("/", "0", ":"), listOf("-", "."))
                }
            }
            else -> digits + listOf(buildList {
                if (inputType and 0x1000 != 0) add("-")
                add("0")
                if (inputType and 0x2000 != 0) add(".")
                if (inputType and 0x1000 != 0) add("+")
            })
        }
    }
}

data class ImeResolvedAction(val label: String, val actionId: Int?) {
    companion object {
        fun resolve(options: Int, customLabel: String?, customId: Int): ImeResolvedAction {
            if (options and 0x40000000 != 0) return ImeResolvedAction("Enter", null)
            if (!customLabel.isNullOrBlank() && customId != 0) return ImeResolvedAction(customLabel, customId)
            val action = options and 0xff
            val name = mapOf(2 to "Go", 3 to "Search", 4 to "Send", 5 to "Next", 6 to "Done", 7 to "Previous")[action]
            return if (name == null) ImeResolvedAction("Enter", null) else ImeResolvedAction(name, action)
        }
    }
}

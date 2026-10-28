package dev.lip.keyboard

/** Revoke a payload fetched across a private/lock/default-IME transition before it reaches persistence. */
object ClipboardHistoryRead {
    fun read(allowed:()->Boolean,payload:()->String?):String? {
        if(!allowed())return null
        val text=payload()?:return null
        return text.takeIf{allowed()&&ClipboardHistory.admissible(it)}
    }
}

package dev.lip.keyboard

/** In-memory lease for bounded background decoding. Cancellation discards results, not CPU work. */
class SwipeRequest internal constructor(internal val ticket: ImeTicket,internal val connection: Any)
class SwipeRequestOwner(private val session: ImeSession) {
    private var active: SwipeRequest?=null
    @Synchronized fun begin(cursor: ImeCursor?,connection: Any?): SwipeRequest? {
        active=null
        if(connection==null)return null
        return session.capture(cursor)?.let { SwipeRequest(it,connection).also { request->active=request } }
    }
    @Synchronized fun cancel() { active=null }
    /** Claim once immediately before editor mutation; a refused or ambiguous commit is never retried. */
    @Synchronized fun claim(request: SwipeRequest,cursor: ImeCursor?,connection: Any?): Boolean {
        if(active!==request)return false
        active=null
        return connection===request.connection && session.claim(request.ticket,cursor)
    }
}

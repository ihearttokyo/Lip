package dev.lip.keyboard

/** Initial bounded host read has the same failure/time policy as later reads. No writes. */
object WordDeleteCapture {
    fun read(contactStarted:Long,clock:()->Long,eligible:()->Boolean,read:()->WordDeleteWindow?):WordDeleteWindow? {
        val began=clock()
        fun timely(now:Long)=now>=began&&began>=contactStarted&&now-contactStarted<=30_000&&now-began<250
        return try {
            if(!timely(began)||!eligible()||!timely(clock()))return null
            val value=read()
            if(!timely(clock())||!eligible()||!timely(clock()))null else value
        }catch(_:RuntimeException){null} // Initial refusal is not permission to retry or use another reader.
    }
}

package dev.gmitch215.drift.why

/** Where a conversation keeps its [Session] between two messages. */
interface SessionStore {
    fun load(): Session?

    fun save(session: Session)

    fun clear()
}

/** A store that lives as long as the object: nothing is written anywhere. */
class MemorySessionStore : SessionStore {
    private var session: Session? = null

    override fun load(): Session? = session

    override fun save(session: Session) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

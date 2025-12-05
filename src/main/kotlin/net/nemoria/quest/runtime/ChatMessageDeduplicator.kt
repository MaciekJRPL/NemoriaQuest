package net.nemoria.quest.runtime

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ChatMessageDeduplicator {
    private data class Entry(val token: String, val time: Long)

    private const val WINDOW_MS = 80L
    private const val MAX_SIZE = 64

    private val recent: MutableMap<UUID, ArrayDeque<Entry>> = ConcurrentHashMap()

    fun shouldProcess(playerId: UUID, token: String): Boolean {
        val now = System.currentTimeMillis()
        val queue = recent.computeIfAbsent(playerId) { ArrayDeque() }
        while (queue.isNotEmpty() && now - queue.peekFirst().time > WINDOW_MS) {
            queue.removeFirst()
        }
        if (queue.any { it.token == token }) return false
        queue.addLast(Entry(token, now))
        while (queue.size > MAX_SIZE) {
            queue.removeFirst()
        }
        return true
    }

    fun clear(playerId: UUID) {
        recent.remove(playerId)
    }
}

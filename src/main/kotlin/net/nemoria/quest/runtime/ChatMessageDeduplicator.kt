package net.nemoria.quest.runtime

import net.nemoria.quest.core.DebugLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

object ChatMessageDeduplicator {
    private data class Entry(val token: String, val time: Long)

    private const val WINDOW_MS = 80L
    private const val MAX_SIZE = 64

    private val recent: MutableMap<UUID, ConcurrentLinkedDeque<Entry>> = ConcurrentHashMap()

    fun shouldProcess(playerId: UUID, token: String): Boolean {
        val now = System.currentTimeMillis()
        val queue = recent.computeIfAbsent(playerId) { ConcurrentLinkedDeque() }
        while (true) {
            val first = queue.peekFirst() ?: break
            if (now - first.time > WINDOW_MS) {
                queue.pollFirst()
            } else {
                break
            }
        }
        if (queue.any { it.token == token }) {
            DebugLog.logToFile("debug-session", "run1", "DEDUP", "ChatMessageDeduplicator.kt:21", "shouldProcess duplicate found", mapOf("playerUuid" to playerId.toString(), "token" to token.take(50), "queueSize" to queue.size))
            return false
        }
        queue.addLast(Entry(token, now))
        while (queue.size > MAX_SIZE) {
            queue.pollFirst()
        }
        return true
    }

    fun clear(playerId: UUID) {
        DebugLog.logToFile("debug-session", "run1", "DEDUP", "ChatMessageDeduplicator.kt:29", "clear entry", mapOf("playerUuid" to playerId.toString()))
        recent.remove(playerId)
    }
}

package net.nemoria.quest.runtime

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.nemoria.quest.core.DebugLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

object ChatHistoryManager {
    private data class Entry(val seq: Long, val component: Component)

    private const val MAX_SIZE = 100
    private val history: MutableMap<UUID, ConcurrentLinkedDeque<Entry>> = ConcurrentHashMap()
    private val skipNext: MutableMap<UUID, Int> = ConcurrentHashMap()
    private val counters: MutableMap<UUID, Long> = ConcurrentHashMap()

    fun append(playerId: UUID, component: Component) {
        if (consumeSkip(playerId)) {
            return
        }
        val nextSeq = counters.compute(playerId) { _, prev -> (prev ?: 0L) + 1L }!!
        val deque = history.computeIfAbsent(playerId) { ConcurrentLinkedDeque() }
        deque.addLast(Entry(nextSeq, component))
        while (deque.size > MAX_SIZE) {
            deque.pollFirst()
        }
    }

    fun history(playerId: UUID): List<Component> = history[playerId]?.map { it.component } ?: emptyList()

    fun lastSequence(playerId: UUID): Long = counters[playerId] ?: 0L

    fun historySince(playerId: UUID, sequence: Long): List<Component> =
        history[playerId]?.filter { it.seq > sequence }?.map { it.component } ?: emptyList()

    fun skipNextMessages(playerId: UUID, count: Int = 1) {
        if (count <= 0) return
        skipNext.compute(playerId) { _, prev -> (prev ?: 0) + count }
    }

    fun clear(playerId: UUID) {
        history.remove(playerId)
        skipNext.remove(playerId)
        counters.remove(playerId)
    }

    private fun consumeSkip(playerId: UUID): Boolean {
        val remaining = skipNext[playerId] ?: return false
        if (remaining <= 1) skipNext.remove(playerId) else skipNext[playerId] = remaining - 1
        return true
    }

    fun greyOut(component: Component): Component {
        val recolored = component.color(NamedTextColor.DARK_GRAY)
        if (component.children().isEmpty()) return recolored
        val children = component.children().map { greyOut(it) }
        return recolored.children(children)
    }
}

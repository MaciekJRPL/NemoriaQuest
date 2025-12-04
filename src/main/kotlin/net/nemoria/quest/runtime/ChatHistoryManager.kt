package net.nemoria.quest.runtime

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ChatHistoryManager {
    private const val MAX_SIZE = 100
    private val history: MutableMap<UUID, ArrayDeque<Component>> = ConcurrentHashMap()
    private val skipNext: MutableMap<UUID, Int> = ConcurrentHashMap()

    fun append(playerId: UUID, component: Component) {
        if (consumeSkip(playerId)) return
        val deque = history.computeIfAbsent(playerId) { ArrayDeque() }
        deque.addLast(component)
        while (deque.size > MAX_SIZE) {
            deque.removeFirst()
        }
    }

    fun history(playerId: UUID): List<Component> = ArrayList(history[playerId] ?: emptyList())

    fun skipNextMessages(playerId: UUID, count: Int = 1) {
        if (count <= 0) return
        skipNext.compute(playerId) { _, prev -> (prev ?: 0) + count }
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

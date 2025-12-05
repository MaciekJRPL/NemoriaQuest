package net.nemoria.quest.runtime

import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ChatHideService {
    private val hidden: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val allowOnce: MutableMap<UUID, Int> = ConcurrentHashMap()
    private val allowExact: MutableMap<UUID, Int> = ConcurrentHashMap()
    private val allowJson: MutableMap<UUID, MutableList<String>> = ConcurrentHashMap()
    private val buffered: MutableMap<UUID, MutableList<String>> = ConcurrentHashMap()
    private val dialogPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val gson = GsonComponentSerializer.gson()

    fun hide(playerId: UUID) {
        hidden.add(playerId)
    }

    fun show(playerId: UUID) {
        hidden.remove(playerId)
        allowOnce.remove(playerId)
        allowExact.remove(playerId)
        allowJson.remove(playerId)
        dialogPlayers.remove(playerId)
        ChatMessageDeduplicator.clear(playerId)
    }

    fun isHidden(playerId: UUID): Boolean = hidden.contains(playerId)

    fun allowNext(playerId: UUID, count: Int = 1) {
        if (count <= 0) return
        allowOnce.compute(playerId) { _, prev -> (prev ?: 0) + count }
    }

    fun allowExactNext(playerId: UUID, count: Int = 1) {
        if (count <= 0) return
        allowExact.compute(playerId) { _, prev -> (prev ?: 0) + count }
    }

    fun allowJsonOnce(playerId: UUID, json: String) {
        if (json.isEmpty()) return
        allowJson.computeIfAbsent(playerId) { mutableListOf() }.add(json)
    }

    fun consumeAllowed(playerId: UUID): Boolean {
        val current = allowOnce[playerId] ?: return false
        return if (current <= 1) {
            allowOnce.remove(playerId)
            true
        } else {
            allowOnce[playerId] = current - 1
            true
        }
    }

    fun consumeExact(playerId: UUID): Boolean {
        val current = allowExact[playerId] ?: return false
        return if (current <= 1) {
            allowExact.remove(playerId)
            true
        } else {
            allowExact[playerId] = current - 1
            true
        }
    }

    fun consumeJson(playerId: UUID, json: String?): Boolean {
        if (json.isNullOrEmpty()) return false
        val queue = allowJson[playerId] ?: return false
        val idx = queue.indexOf(json)
        if (idx == -1) return false
        queue.removeAt(idx)
        if (queue.isEmpty()) allowJson.remove(playerId)
        return true
    }

    fun bufferMessage(playerId: UUID, jsonMessage: String) {
        buffered.computeIfAbsent(playerId) { mutableListOf() }.add(jsonMessage)
    }

    fun flushBufferedToHistory(playerId: UUID) {
        val messages = buffered.remove(playerId) ?: return
        messages.forEach { json ->
            runCatching { gson.deserialize(json) }.getOrNull()?.let { component ->
                ChatHistoryManager.append(playerId, component)
            }
        }
    }

    fun clearDedup(playerId: UUID) {
        ChatMessageDeduplicator.clear(playerId)
    }

    fun clear() {
        hidden.clear()
        allowOnce.clear()
        allowExact.clear()
        allowJson.clear()
        dialogPlayers.clear()
        buffered.clear()
        hidden.forEach { ChatMessageDeduplicator.clear(it) }
    }

    fun beginDialog(playerId: UUID) {
        hidden.add(playerId)
        dialogPlayers.add(playerId)
        clearDedup(playerId)
    }

    fun endDialog(playerId: UUID) {
        dialogPlayers.remove(playerId)
        show(playerId)
    }

    fun isDialogActive(playerId: UUID): Boolean = dialogPlayers.contains(playerId)
}

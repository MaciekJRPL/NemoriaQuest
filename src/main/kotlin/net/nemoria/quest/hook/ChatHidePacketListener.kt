package net.nemoria.quest.hook

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.ChatHideService
import net.nemoria.quest.runtime.ChatHistoryManager
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatHidePacketListener : PacketListenerAbstract(PacketListenerPriority.NORMAL) {
    private val gson = GsonComponentSerializer.gson()
    private val plain = PlainTextComponentSerializer.plainText()
    private data class SeenEntry(val key: String, val time: Long)
    private val recent: MutableMap<UUID, ArrayDeque<SeenEntry>> = ConcurrentHashMap()
    private val dedupWindowMs = 250L
    private val maxSeen = 32

    override fun onPacketSend(event: PacketSendEvent) {
        val player = event.getPlayer<Player>() ?: return
        val hidden = ChatHideService.isHidden(player.uniqueId) || Services.questService.hasDiverge(player)
        if (!hidden) return
        val type = event.packetType ?: return
        if (!isServerChat(type)) return

        val result = when (type) {
            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> extractSystem(event)
            PacketType.Play.Server.CHAT_MESSAGE -> extractChat(event)
            else -> null
        } ?: return

        val (component, json) = result
        val textKey = plain.serialize(component)
        if (!recordMessage(player.uniqueId, type.name, textKey)) return

        if (ChatHideService.consumeJson(player.uniqueId, json) ||
            ChatHideService.consumeExact(player.uniqueId) ||
            ChatHideService.consumeAllowed(player.uniqueId)) {
            return
        }

        ChatHistoryManager.append(player.uniqueId, component)
        ChatHideService.bufferMessage(player.uniqueId, json)
        event.isCancelled = true
    }

    private fun extractSystem(event: PacketSendEvent): Pair<net.kyori.adventure.text.Component, String>? {
        val wrapper = WrapperPlayServerSystemChatMessage(event)
        val component = wrapper.message ?: wrapper.messageJson?.let { runCatching { gson.deserialize(it) }.getOrNull() } ?: return null
        val json = wrapper.messageJson ?: gson.serialize(component)
        return component to json
    }

    private fun extractChat(event: PacketSendEvent): Pair<net.kyori.adventure.text.Component, String>? {
        val wrapper = WrapperPlayServerChatMessage(event)
        val component = wrapper.message.chatContent ?: return null
        val json = gson.serialize(component)
        return component to json
    }

    private fun recordMessage(playerId: UUID, typeName: String, textKey: String): Boolean {
        val key = "$typeName:$textKey"
        val now = System.currentTimeMillis()
        val queue = recent.computeIfAbsent(playerId) { ArrayDeque() }
        val duplicate = queue.any { it.key == key && now - it.time <= dedupWindowMs }
        if (duplicate) return false
        queue.addLast(SeenEntry(key, now))
        while (queue.size > maxSeen) queue.removeFirst()
        return true
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val player = event.getPlayer<Player>() ?: return
        val hidden = ChatHideService.isHidden(player.uniqueId)
        val divergeAwaited = Services.questService.hasDiverge(player)
        if (!hidden || divergeAwaited) return
        val type = event.packetType ?: return
        if (isClientChat(type)) {
            event.isCancelled = true
        }
    }

    private fun isClientChat(type: PacketTypeCommon): Boolean = when (type) {
        PacketType.Play.Client.CHAT_MESSAGE,
        PacketType.Play.Client.CHAT_COMMAND,
        PacketType.Play.Client.CHAT_COMMAND_UNSIGNED,
        PacketType.Play.Client.CHAT_SESSION_UPDATE,
        PacketType.Play.Client.CHAT_ACK -> true
        else -> false
    }

    private fun isServerChat(type: PacketTypeCommon): Boolean =
        type == PacketType.Play.Server.CHAT_MESSAGE ||
            type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE
}

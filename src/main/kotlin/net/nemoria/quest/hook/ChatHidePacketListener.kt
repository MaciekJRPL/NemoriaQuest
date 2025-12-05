package net.nemoria.quest.hook

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import net.nemoria.quest.runtime.ChatHideService
import net.nemoria.quest.runtime.ChatHistoryManager
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player

class ChatHidePacketListener : PacketListenerAbstract(PacketListenerPriority.NORMAL) {
    private val gson = GsonComponentSerializer.gson()
    private val plain = PlainTextComponentSerializer.plainText()
    private data class SeenEntry(val key: String, val time: Long)
    private val seen: MutableMap<java.util.UUID, ArrayDeque<SeenEntry>> = java.util.concurrent.ConcurrentHashMap()
    private val dedupWindowMs = 250L
    private val maxSeen = 32

    override fun onPacketSend(event: PacketSendEvent) {
        val player = event.getPlayer<Player>() ?: return
        val hidden = ChatHideService.isHidden(player.uniqueId) || net.nemoria.quest.core.Services.questService.hasDiverge(player)
        if (!hidden) return
        val type = event.packetType ?: return
        if (isServerChat(type)) {
            val json = when (type) {
                PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> WrapperPlayServerSystemChatMessage(event).messageJson
                PacketType.Play.Server.CHAT_MESSAGE -> {
                    val wrapper = WrapperPlayServerChatMessage(event)
                    val gson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                    gson.serialize(wrapper.message.chatContent)
                }
                else -> null
            }
            if (ChatHideService.consumeJson(player.uniqueId, json)) return
            if (ChatHideService.consumeExact(player.uniqueId)) return
            if (ChatHideService.consumeAllowed(player.uniqueId)) return
            when (type) {
                PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> {
                    val wrapper = WrapperPlayServerSystemChatMessage(event)
                    val json = wrapper.messageJson
                    if (json != null) {
                        ChatHideService.bufferMessage(player.uniqueId, json)
                        runCatching { gson.deserialize(json) }.onSuccess { comp ->
                            appendIfFresh(player.uniqueId, "SYSTEM", comp)
                        }
                    }
                }
                PacketType.Play.Server.CHAT_MESSAGE -> {
                    val wrapper = WrapperPlayServerChatMessage(event)
                    val msg = wrapper.message
                    val gson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                    val json = gson.serialize(msg.chatContent)
                    ChatHideService.bufferMessage(player.uniqueId, json)
                    appendIfFresh(player.uniqueId, "CHAT", msg.chatContent)
                }
            }
            event.isCancelled = true
        }
    }

    private fun appendIfFresh(playerId: java.util.UUID, type: String, comp: net.kyori.adventure.text.Component) {
        val key = "$type|${plain.serialize(comp)}"
        val now = System.currentTimeMillis()
        val queue = seen.computeIfAbsent(playerId) { ArrayDeque() }
        val duplicate = queue.any { it.key == key && now - it.time <= dedupWindowMs }
        if (duplicate) return
        ChatHistoryManager.append(playerId, comp)
        queue.addLast(SeenEntry(key, now))
        while (queue.size > maxSeen) queue.removeFirst()
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val player = event.getPlayer<Player>() ?: return
        val hidden = ChatHideService.isHidden(player.uniqueId)
        val divergeAwaited = net.nemoria.quest.core.Services.questService.hasDiverge(player)
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

    private fun isServerChat(type: PacketTypeCommon): Boolean = when (type) {
        PacketType.Play.Server.CHAT_MESSAGE,
        PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> true
        else -> false
    }
}

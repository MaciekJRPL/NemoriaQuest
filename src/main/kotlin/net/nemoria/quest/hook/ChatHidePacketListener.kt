package net.nemoria.quest.hook

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.chat.ChatTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import net.kyori.adventure.text.Component
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
        val id = player.uniqueId
        val hidden = ChatHideService.isHidden(id) || Services.questService.hasDiverge(player)
        if (!hidden) return
        val type = event.packetType ?: return
        val (component, json) = resolveComponent(type, event) ?: return
        val textKey = plain.serialize(component)
        if (!recordMessage(id, type.name, textKey)) return

        if (ChatHideService.consumeJson(id, json) ||
            ChatHideService.consumeExact(id) ||
            ChatHideService.consumeAllowed(id)
        ) {
            return
        }

        ChatHistoryManager.append(id, component)
        ChatHideService.bufferMessage(id, json)
        event.isCancelled = true
    }

    private fun resolveComponent(type: PacketTypeCommon, event: PacketSendEvent): Pair<Component, String>? {
        return when (type) {
            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> {
                val wrapper = WrapperPlayServerSystemChatMessage(event)
                if (wrapper.type == ChatTypes.GAME_INFO) return null
                val component = wrapper.message ?: wrapper.messageJson?.let { runCatching { gson.deserialize(it) }.getOrNull() }
                component?.let { it to (wrapper.messageJson ?: gson.serialize(it)) }
            }

            PacketType.Play.Server.CHAT_MESSAGE -> {
                val wrapper = WrapperPlayServerChatMessage(event)
                val component = wrapper.message.chatContent ?: return null
                component to gson.serialize(component)
            }

            PacketType.Play.Server.DISGUISED_CHAT -> {
                val wrapper = WrapperPlayServerDisguisedChat(event)
                val component = wrapper.message ?: return null
                component to gson.serialize(component)
            }

            else -> null
        }
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

    private fun isClientChat(type: PacketTypeCommon): Boolean =
        when (type) {
            PacketType.Play.Client.CHAT_MESSAGE,
            PacketType.Play.Client.CHAT_COMMAND,
            PacketType.Play.Client.CHAT_COMMAND_UNSIGNED,
            PacketType.Play.Client.CHAT_SESSION_UPDATE,
            PacketType.Play.Client.CHAT_ACK -> true

            else -> false
        }
}

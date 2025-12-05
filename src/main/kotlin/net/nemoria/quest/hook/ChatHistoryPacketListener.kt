package net.nemoria.quest.hook

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
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
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.ChatHideService
import net.nemoria.quest.runtime.ChatHistoryManager
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatHistoryPacketListener : PacketListenerAbstract(PacketListenerPriority.LOWEST) {
    private val gson = GsonComponentSerializer.gson()
    private val plain = PlainTextComponentSerializer.plainText()
    private data class SeenEntry(val key: String, val time: Long)
    private val recent: MutableMap<UUID, ArrayDeque<SeenEntry>> = ConcurrentHashMap()
    private val dedupWindowMs = 250L
    private val maxSeen = 32

    override fun onPacketSend(event: PacketSendEvent) {
        val player = event.getPlayer<Player>() ?: return
        if (event.isCancelled) return
        if (ChatHideService.isHidden(player.uniqueId) || Services.questService.hasDiverge(player)) return
        val type = event.packetType ?: return
        val component = resolveComponent(type, event) ?: return
        val text = plain.serialize(component)
        if (!record(player.uniqueId, type.name, text)) {
            DebugLog.log("ChatHistory dedup skip player=${player.name} text='$text'")
            return
        }

        ChatHistoryManager.append(player.uniqueId, component)
        DebugLog.log("ChatHistory store type=${type.name} player=${player.name} text='$text'")
    }

    private fun resolveComponent(type: PacketTypeCommon, event: PacketSendEvent): Component? {
        return when (type) {
            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> {
                val wrapper = WrapperPlayServerSystemChatMessage(event)
                if (wrapper.type == ChatTypes.GAME_INFO) return null
                wrapper.message ?: wrapper.messageJson?.let { runCatching { gson.deserialize(it) }.getOrNull() }
            }

            PacketType.Play.Server.CHAT_MESSAGE -> {
                val wrapper = WrapperPlayServerChatMessage(event)
                wrapper.message.chatContent
            }

            PacketType.Play.Server.DISGUISED_CHAT -> {
                val wrapper = WrapperPlayServerDisguisedChat(event)
                wrapper.message
            }

            else -> null
        }
    }

    private fun record(playerId: UUID, typeName: String, text: String): Boolean {
        val key = "$typeName:$text"
        val now = System.currentTimeMillis()
        val queue = recent.computeIfAbsent(playerId) { ArrayDeque() }
        val duplicate = queue.any { it.key == key && now - it.time <= dedupWindowMs }
        if (duplicate) return false
        queue.addLast(SeenEntry(key, now))
        while (queue.size > maxSeen) queue.removeFirst()
        return true
    }
}

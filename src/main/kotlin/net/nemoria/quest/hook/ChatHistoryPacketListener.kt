package net.nemoria.quest.hook

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.chat.ChatTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.nemoria.quest.runtime.ChatHistoryManager
import org.bukkit.entity.Player

class ChatHistoryPacketListener : PacketListenerAbstract(PacketListenerPriority.LOWEST) {
    private val gson = GsonComponentSerializer.gson()

    override fun onPacketSend(event: PacketSendEvent) {
        val player = event.getPlayer<Player>() ?: return
        val type = event.packetType ?: return
        when (type) {
            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> {
                val wrapper = WrapperPlayServerSystemChatMessage(event)
                val chatType = wrapper.type
                if (chatType == ChatTypes.GAME_INFO) return
                val comp = wrapper.message ?: wrapper.messageJson?.let { runCatching { gson.deserialize(it) }.getOrNull() }
                comp?.let { ChatHistoryManager.append(player.uniqueId, it) }
            }
            PacketType.Play.Server.CHAT_MESSAGE -> {
                val wrapper = WrapperPlayServerChatMessage(event)
                val msg = wrapper.message
                val comp = msg.chatContent
                comp?.let { ChatHistoryManager.append(player.uniqueId, it) }
            }
            else -> {}
        }
    }
}

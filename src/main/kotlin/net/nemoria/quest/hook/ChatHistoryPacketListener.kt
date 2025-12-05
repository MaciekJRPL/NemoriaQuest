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
import net.nemoria.quest.runtime.ChatMessageDeduplicator
import org.bukkit.entity.Player

class ChatHistoryPacketListener : PacketListenerAbstract(PacketListenerPriority.LOWEST) {
    private val gson = GsonComponentSerializer.gson()
    private val plain = PlainTextComponentSerializer.plainText()

    override fun onPacketSend(event: PacketSendEvent) {
        val player = event.getPlayer<Player>() ?: return
        if (event.isCancelled) return
        if (ChatHideService.isHidden(player.uniqueId) ||
            ChatHideService.isDialogActive(player.uniqueId) ||
            Services.questService.hasDiverge(player)
        ) return
        val type = event.packetType ?: return
        val component = resolveComponent(type, event) ?: return
        val plainText = plain.serialize(component)
        val token = if (plainText.isNotBlank()) {
            "${type.name}:$plainText"
        } else {
            val json = gson.serialize(component)
            "${type.name}:$json"
        }
        if (!ChatMessageDeduplicator.shouldProcess(player.uniqueId, token)) {
            DebugLog.log("ChatHistory dedup skip player=${player.name} text='$plainText'")
            return
        }

        ChatHistoryManager.append(player.uniqueId, component)
        DebugLog.log("ChatHistory store type=${type.name} player=${player.name} text='$plainText'")
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
}

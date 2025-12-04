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
import org.bukkit.entity.Player

class ChatHidePacketListener : PacketListenerAbstract(PacketListenerPriority.NORMAL) {

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
                    }
                }
                PacketType.Play.Server.CHAT_MESSAGE -> {
                    val wrapper = WrapperPlayServerChatMessage(event)
                    val msg = wrapper.message
                    val gson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                    val json = gson.serialize(msg.chatContent)
                    ChatHideService.bufferMessage(player.uniqueId, json)
                }
            }
            event.isCancelled = true
        }
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

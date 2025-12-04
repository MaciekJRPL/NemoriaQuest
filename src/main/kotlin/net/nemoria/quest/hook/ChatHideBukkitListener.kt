package net.nemoria.quest.hook

import net.nemoria.quest.runtime.ChatHideService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

class ChatHideBukkitListener : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val hidden = ChatHideService.isHidden(event.player.uniqueId) || net.nemoria.quest.core.Services.questService.hasDiverge(event.player)
        if (hidden) {
            event.isCancelled = true
            return
        }
        event.recipients.removeIf { ChatHideService.isHidden(it.uniqueId) }
    }
}

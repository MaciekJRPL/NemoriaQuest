package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.BranchRuntimeManager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent

class PlayerMiscListener : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val cancel = Services.questService.handlePlayerMiscEvent(
            event.player,
            BranchRuntimeManager.MiscEventType.CHAT,
            event.message
        )
        if (cancel) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val msg = event.message.removePrefix("/").trim()
        val cancel = Services.questService.handlePlayerMiscEvent(
            event.player,
            BranchRuntimeManager.MiscEventType.CHAT,
            msg
        )
        if (cancel) event.isCancelled = true
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        Services.questService.handlePlayerMiscEvent(event.player, BranchRuntimeManager.MiscEventType.CONNECT, null)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        Services.questService.handlePlayerMiscEvent(event.player, BranchRuntimeManager.MiscEventType.DISCONNECT, null)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        Services.questService.handlePlayerMiscEvent(event.player, BranchRuntimeManager.MiscEventType.RESPAWN, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val key = event.advancement.key().toString()
        Services.questService.handlePlayerMiscEvent(
            event.player,
            BranchRuntimeManager.MiscEventType.ACHIEVEMENT,
            key
        )
    }
}

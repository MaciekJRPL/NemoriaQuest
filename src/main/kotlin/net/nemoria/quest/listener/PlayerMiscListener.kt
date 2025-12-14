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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerMiscListener : Listener {
    private val chatBypass: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val commandBypass: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        if (chatBypass.remove(event.player.uniqueId)) return
        if (!Services.hasPlugin() || !Services.hasQuestService()) return
        val player = event.player
        val message = event.message
        event.isCancelled = true
        Services.plugin.server.scheduler.runTask(Services.plugin, Runnable {
            val cancel = Services.questService.handlePlayerMiscEvent(
                player,
                BranchRuntimeManager.MiscEventType.CHAT,
                message
            )
            if (cancel) return@Runnable
            chatBypass.add(player.uniqueId)
            try {
                player.chat(message)
            } finally {
                chatBypass.remove(player.uniqueId)
            }
        })
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (commandBypass.remove(event.player.uniqueId)) return
        if (!Services.hasPlugin() || !Services.hasQuestService()) return
        val player = event.player
        val msg = event.message.removePrefix("/").trim()
        event.isCancelled = true
        if (!Services.questService.isCommandAllowed(player, msg)) {
            return
        }
        Services.plugin.server.scheduler.runTask(Services.plugin, Runnable {
            val cancel = Services.questService.handlePlayerMiscEvent(
                player,
                BranchRuntimeManager.MiscEventType.CHAT,
                msg
            )
            if (cancel) return@Runnable
            commandBypass.add(player.uniqueId)
            try {
                player.performCommand(msg)
            } finally {
                commandBypass.remove(player.uniqueId)
            }
        })
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!Services.hasQuestService()) return
        Services.questService.handlePlayerMiscEvent(event.player, BranchRuntimeManager.MiscEventType.CONNECT, null)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (!Services.hasQuestService()) return
        Services.questService.handlePlayerMiscEvent(event.player, BranchRuntimeManager.MiscEventType.DISCONNECT, null)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        if (!Services.hasQuestService()) return
        Services.questService.handlePlayerMiscEvent(event.player, BranchRuntimeManager.MiscEventType.RESPAWN, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        if (!Services.hasQuestService()) return
        val key = event.advancement.key().toString()
        Services.questService.handlePlayerMiscEvent(
            event.player,
            BranchRuntimeManager.MiscEventType.ACHIEVEMENT,
            key
        )
    }
}

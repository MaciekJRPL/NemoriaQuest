package net.nemoria.quest.gui

import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.core.Services
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

class GuiListener : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:11", "onClick entry", mapOf<String, Any?>("playerUuid" to ((event.whoClicked as? Player)?.uniqueId?.toString() ?: "null"), "clickType" to event.click.name, "slot" to event.slot))
        val player = event.whoClicked as? Player ?: return
        val topHolder = event.view.topInventory.holder
        val manager = Services.guiManager
        when (topHolder) {
            is GuiManager.ListHolder -> {
                DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:16", "onClick ListHolder", mapOf<String, Any?>("playerUuid" to player.uniqueId.toString(), "clickType" to event.click.name, "filterActive" to topHolder.filterActive, "page" to topHolder.page))
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                val item = event.currentItem ?: return
                val questId = manager.questFromItem(item) ?: return
                if (event.click.isRightClick) {
                    val active = Services.questService.activeQuests(player).contains(questId)
                    DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:22", "onClick right click", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "active" to active))
                    if (active) Services.questService.stopQuest(player, questId, complete = false) else Services.questService.startQuest(player, questId)
                    manager.openList(player, topHolder.config, topHolder.filterActive, topHolder.page)
                } else if (event.click.isLeftClick) {
                    DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:26", "onClick left click", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId))
                    Services.storage.questModelRepo.findById(questId)?.let { manager.openDetail(player, it) }
                }
            }
            is GuiManager.DetailHolder -> {
                DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:30", "onClick DetailHolder", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to topHolder.questId))
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                // LPM anywhere wraca do listy
                manager.openList(player, Services.guiDefault, filterActive = false)
            }
            else -> return
        }
    }
}

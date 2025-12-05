package net.nemoria.quest.gui

import net.nemoria.quest.core.Services
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

class GuiListener : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val topHolder = event.view.topInventory.holder
        val manager = Services.guiManager
        when (topHolder) {
            is GuiManager.ListHolder -> {
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                val item = event.currentItem ?: return
                val questId = manager.questFromItem(item) ?: return
                if (event.click.isRightClick) {
                    val active = Services.questService.activeQuests(player).contains(questId)
                    if (active) Services.questService.stopQuest(player, questId, complete = false) else Services.questService.startQuest(player, questId)
                    manager.openList(player, topHolder.config, topHolder.filterActive, topHolder.page)
                } else if (event.click.isLeftClick) {
                    Services.storage.questModelRepo.findById(questId)?.let { manager.openDetail(player, it) }
                }
            }
            is GuiManager.DetailHolder -> {
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                // LPM anywhere wraca do listy
                manager.openList(player, Services.guiDefault, filterActive = false)
            }
            else -> return
        }
    }
}

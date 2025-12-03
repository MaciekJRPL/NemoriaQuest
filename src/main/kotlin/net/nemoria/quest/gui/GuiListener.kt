package net.nemoria.quest.gui

import net.nemoria.quest.core.Services
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.InventoryHolder

class GuiListener : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder !is InventoryHolder || holder.javaClass.name.startsWith("org.bukkit.inventory")) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val manager = Services.guiManager
        when (holder) {
            is GuiManager.ListHolder -> {
                val item = event.currentItem ?: return
                val questId = manager.questFromItem(item) ?: return
                if (event.click.isRightClick) {
                    val active = Services.questService.activeQuests(player).contains(questId)
                    if (active) Services.questService.stopQuest(player, questId, complete = false) else Services.questService.startQuest(player, questId)
                    manager.openList(player, holder.config, holder.filterActive, holder.page)
                } else if (event.click.isLeftClick) {
                    Services.storage.questModelRepo.findById(questId)?.let { manager.openDetail(player, it) }
                }
            }
            is GuiManager.DetailHolder -> {
                // LPM anywhere wraca do listy
                manager.openList(player, Services.guiDefault, filterActive = false)
            }
        }
    }
}

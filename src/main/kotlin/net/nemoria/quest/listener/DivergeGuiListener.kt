package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent

class DivergeGuiListener : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        val runtime = Services.questService.runtime()
        val handled = runtime.handleDivergeGuiClick(player, event.inventory, event.rawSlot)
        if (handled) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? org.bukkit.entity.Player ?: return
        Services.questService.runtime().handleDivergeGuiClose(player, event.inventory)
    }
}

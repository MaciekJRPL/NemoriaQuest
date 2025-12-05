package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.BranchRuntimeManager.ItemEventType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ProjectileLaunchEvent

class PlayerItemListener : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        Services.questService.handlePlayerItemEvent(player, ItemEventType.PICKUP, event.item.itemStack)
        Services.questService.handlePlayerItemEvent(player, ItemEventType.ACQUIRE, event.item.itemStack)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        Services.questService.handlePlayerItemEvent(event.player, ItemEventType.DROP, event.itemDrop.itemStack)
    }

    @EventHandler(ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        Services.questService.handlePlayerItemEvent(event.player, ItemEventType.CONSUME, event.item)
    }

    @EventHandler(ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state == PlayerFishEvent.State.CAUGHT_FISH) {
            val item = event.caught as? org.bukkit.entity.Item ?: return
            Services.questService.handlePlayerItemEvent(event.player, ItemEventType.FISH, item.itemStack)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        Services.questService.handlePlayerItemEvent(player, ItemEventType.CRAFT, event.currentItem)
    }

    @EventHandler(ignoreCancelled = true)
    fun onRepair(event: PrepareAnvilEvent) {
        val player = event.view.player as? Player ?: return
        val result = event.result ?: return
        Services.questService.handlePlayerItemEvent(player, ItemEventType.REPAIR, result)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        Services.questService.handlePlayerItemEvent(event.enchanter, ItemEventType.ENCHANT, event.item)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val item = event.item ?: return
        val click = when (event.action) {
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> "RIGHT_CLICK"
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> "LEFT_CLICK"
            else -> event.action.name
        }
        Services.questService.handlePlayerItemEvent(event.player, ItemEventType.INTERACT, item, inventoryType = click)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val topType = event.view.topInventory.type.name
        val clickedInv = if (event.rawSlot < event.view.topInventory.size) "TOP" else "BOTTOM"
        val stack = event.currentItem
        if (clickedInv == "TOP") {
            Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_TAKE, stack, inventoryType = topType, slot = event.rawSlot)
        } else {
            Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_PUT, event.cursor ?: stack, inventoryType = topType, slot = event.rawSlot)
        }
        if (topType == InventoryType.MERCHANT.name) {
            val villagerId = (event.view.topInventory.holder as? org.bukkit.entity.Villager)?.uniqueId
            Services.questService.handlePlayerItemEvent(player, ItemEventType.TRADE, stack, villagerId = villagerId)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val topType = event.view.topInventory.type.name
        event.rawSlots.forEach { slot ->
            if (slot < event.view.topInventory.size) {
                Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_TAKE, event.oldCursor, inventoryType = topType, slot = slot)
            } else {
                Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_PUT, event.oldCursor, inventoryType = topType, slot = slot)
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onThrow(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Player ?: return
        val item = shooter.inventory.itemInMainHand
        Services.questService.handlePlayerItemEvent(shooter, ItemEventType.THROW, item)
    }

    @EventHandler(ignoreCancelled = true)
    fun onAcquireFromDeath(event: PlayerDeathEvent) {
        val player = event.entity
        event.drops.forEach { stack ->
            Services.questService.handlePlayerItemEvent(player, ItemEventType.ACQUIRE, stack)
        }
    }
}

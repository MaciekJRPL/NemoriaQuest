package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.BranchRuntimeManager.ItemEventType
import org.bukkit.Location
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
import org.bukkit.NamespacedKey
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerItemListener : Listener {
    private val runtime by lazy { Services.questService.runtime() }
    private val pickupAcquireSkip: MutableMap<UUID, Int> = ConcurrentHashMap()
    private val brewerOwner: MutableMap<Location, Pair<UUID, Long>> = ConcurrentHashMap()
    private val brewerTtlMs = 2 * 60 * 1000L
    private var brewerPruneTask: BukkitTask? = null
    private val countedPutKey by lazy { NamespacedKey(Services.plugin, "nq_counted_put") }
    private val countedTakeKey by lazy { NamespacedKey(Services.plugin, "nq_counted_take") }

    init {
        Services.plugin?.let { startPruneTask() }
    }

    private fun startPruneTask() {
        brewerPruneTask?.cancel()
        brewerPruneTask = Services.plugin.server.scheduler.runTaskTimerAsynchronously(
            Services.plugin,
            Runnable { pruneBrewers() },
            20L * 60,
            20L * 60
        )
    }

    private fun pruneBrewers(now: Long = System.currentTimeMillis()) {
        val expired = brewerOwner.filterValues { now - it.second > brewerTtlMs }.keys
        expired.forEach { brewerOwner.remove(it) }
    }

    fun shutdown() {
        brewerPruneTask?.cancel()
        brewerPruneTask = null
        brewerOwner.clear()
        pickupAcquireSkip.clear()
    }

    private fun isCounted(stack: org.bukkit.inventory.ItemStack?, key: NamespacedKey): Boolean {
        val meta = stack?.itemMeta ?: return false
        return meta.persistentDataContainer.has(key, org.bukkit.persistence.PersistentDataType.BYTE)
    }

    private fun markCounted(stack: org.bukkit.inventory.ItemStack?, key: NamespacedKey) {
        val meta = stack?.itemMeta ?: return
        meta.persistentDataContainer.set(key, org.bukkit.persistence.PersistentDataType.BYTE, 1)
        stack.itemMeta = meta
    }

    private fun hasActiveSession(player: Player): Boolean =
        runtime.getQuestId(player) != null

    private fun currentNodeType(player: Player): net.nemoria.quest.quest.QuestObjectNodeType? {
        val questId = runtime.getQuestId(player) ?: return null
        val model = Services.storage.questModelRepo.findById(questId) ?: return null
        val progress = Services.questService.progress(player)[questId]
        val branchId = progress?.currentBranchId ?: model.mainBranch ?: model.branches.keys.firstOrNull() ?: return null
        val nodeId = progress?.currentNodeId ?: model.branches[branchId]?.startsAt ?: model.branches[branchId]?.objects?.keys?.firstOrNull()
        return model.branches[branchId]?.objects?.get(nodeId)?.type
    }

    @EventHandler(ignoreCancelled = true)
    fun onBrewerOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (event.inventory.type != InventoryType.BREWING) return
        val holder = event.inventory.holder as? org.bukkit.block.BrewingStand ?: return
        val loc = holder.location.toBlockLocation()
        brewerOwner[loc] = player.uniqueId to System.currentTimeMillis()
        pruneBrewers()
    }

    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        Services.questService.handlePlayerItemEvent(player, ItemEventType.PICKUP, event.item.itemStack)
        pickupAcquireSkip[player.uniqueId] = pickupAcquireSkip.getOrDefault(player.uniqueId, 0) + 1
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
    fun onBrew(event: BrewEvent) {
        val player = event.contents.viewers.firstOrNull { it is Player } as? Player
            ?: run {
                val loc = event.block.location.toBlockLocation()
                pruneBrewers()
                val entry = brewerOwner[loc] ?: return
                brewerOwner[loc] = entry.first to System.currentTimeMillis()
                val ownerId = entry.first
                org.bukkit.Bukkit.getPlayer(ownerId)
            }
            ?: return
        val inv = event.contents
        val playerId = player.uniqueId
        Services.plugin.server.scheduler.runTask(Services.plugin, Runnable {
            val live = org.bukkit.Bukkit.getPlayer(playerId) ?: return@Runnable
            for (slot in 0..2) {
                val item = inv.getItem(slot) ?: continue
                if (item.type.isAir) continue
                Services.questService.handlePlayerItemEvent(live, ItemEventType.BREW, item)
            }
        })
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
        if (!hasActiveSession(player)) return
        val nodeType = currentNodeType(player)
        val isPutNode = nodeType == net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_PUT
        val isTakeNode = nodeType == net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_TAKE
        val topType = event.view.topInventory.type.name
        val isFurnaceLike = topType == InventoryType.FURNACE.name || topType == InventoryType.BLAST_FURNACE.name || topType == InventoryType.SMOKER.name
        if (event.view.topInventory.type == InventoryType.CRAFTING) return
        val clickedInv = if (event.rawSlot < event.view.topInventory.size) "TOP" else "BOTTOM"
        val cursor = event.cursor
        val stack = event.currentItem
        if (isFurnaceLike && clickedInv == "TOP" && event.rawSlot == 2) {
            val stackAmt = stack?.amount ?: 0
            val taken = when (event.action) {
                InventoryAction.PICKUP_ALL,
                InventoryAction.MOVE_TO_OTHER_INVENTORY,
                InventoryAction.HOTBAR_SWAP,
                InventoryAction.HOTBAR_MOVE_AND_READD,
                InventoryAction.COLLECT_TO_CURSOR,
                InventoryAction.DROP_ALL_SLOT -> stackAmt
                InventoryAction.PICKUP_HALF -> (stackAmt + 1) / 2
                InventoryAction.PICKUP_ONE,
                InventoryAction.DROP_ONE_SLOT -> if (stackAmt > 0) 1 else 0
                else -> 0
            }.coerceAtLeast(0)
            repeat(taken) {
                Services.questService.handlePlayerItemEvent(player, ItemEventType.MELT, stack?.let { org.bukkit.inventory.ItemStack(it.type, 1) })
            }
            return
        }
        if (clickedInv == "TOP") {
            if (cursor != null && !cursor.type.isAir) {
                if (isPutNode && isCounted(cursor, countedPutKey)) return
                val amt = cursor.amount.coerceAtLeast(1)
                repeat(amt) {
                    Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_PUT, org.bukkit.inventory.ItemStack(cursor.type, 1), inventoryType = topType, slot = event.rawSlot)
                }
                if (isPutNode) markCounted(cursor, countedPutKey)
            } else if (stack != null && !stack.type.isAir && event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                if (isTakeNode && isCounted(stack, countedTakeKey)) return
                val amt = stack.amount.coerceAtLeast(1)
                repeat(amt) {
                    Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_TAKE, org.bukkit.inventory.ItemStack(stack.type, 1), inventoryType = topType, slot = event.rawSlot)
                }
                if (isTakeNode) markCounted(stack, countedTakeKey)
            }
        } else {
            val putSource = cursor ?: stack
            val isShiftMove = event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY
            if (putSource != null && !putSource.type.isAir) {
                if (isPutNode && isCounted(putSource, countedPutKey)) return
                val amt = putSource.amount.coerceAtLeast(1)
                repeat(amt) {
                    Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_PUT, org.bukkit.inventory.ItemStack(putSource.type, 1), inventoryType = topType, slot = event.rawSlot)
                }
                if (isPutNode) markCounted(putSource, countedPutKey)
            } else if (isShiftMove && stack != null && !stack.type.isAir) {
                if (isPutNode && isCounted(stack, countedPutKey)) return
                val amt = stack.amount.coerceAtLeast(1)
                repeat(amt) {
                    Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_PUT, org.bukkit.inventory.ItemStack(stack.type, 1), inventoryType = topType, slot = event.rawSlot)
                }
                if (isPutNode) markCounted(stack, countedPutKey)
            }
        }
        if (topType == InventoryType.MERCHANT.name) {
            val villagerId = (event.view.topInventory.holder as? org.bukkit.entity.Villager)?.uniqueId
            Services.questService.handlePlayerItemEvent(player, ItemEventType.TRADE, stack, villagerId = villagerId)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!hasActiveSession(player)) return
        val nodeType = currentNodeType(player)
        val isPutNode = nodeType == net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_PUT
        val topType = event.view.topInventory.type.name
        val isFurnaceLike = topType == InventoryType.FURNACE.name || topType == InventoryType.BLAST_FURNACE.name || topType == InventoryType.SMOKER.name
        if (event.view.topInventory.type == InventoryType.CRAFTING) return
        val cursor = event.oldCursor
        if (cursor.type.isAir) return
        if (isPutNode && isCounted(cursor, countedPutKey)) return
        val topSize = event.view.topInventory.size
        if (isFurnaceLike) {
            return
        }
        event.newItems.forEach { (slot, stack) ->
            if (slot < topSize) {
                val amt = stack.amount.coerceAtLeast(1)
                repeat(amt) {
                    Services.questService.handlePlayerItemEvent(player, ItemEventType.CONTAINER_PUT, org.bukkit.inventory.ItemStack(stack.type, 1), inventoryType = topType, slot = slot)
                }
            }
        }
        if (isPutNode) markCounted(cursor, countedPutKey)
    }

    @EventHandler
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        // Obsługa przeniesiona do onInventoryClick/onInventoryDrag dla slotu wynikowego pieca
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

    @EventHandler(ignoreCancelled = true)
    fun onSlotChange(event: PlayerInventorySlotChangeEvent) {
        val skip = pickupAcquireSkip[event.player.uniqueId]
        if (skip != null && skip > 0) {
            if (skip <= 1) pickupAcquireSkip.remove(event.player.uniqueId) else pickupAcquireSkip[event.player.uniqueId] = skip - 1
            return
        }
        val old = event.oldItemStack
        val new = event.newItemStack ?: return
        if (new.type.isAir) return
        val sameType = old != null && !old.type.isAir && old.type == new.type
        val oldAmount = if (sameType) old.amount else 0
        val delta = new.amount - oldAmount
        if (delta <= 0) return
        Services.questService.handlePlayerItemEvent(event.player, ItemEventType.ACQUIRE, new)
    }
}

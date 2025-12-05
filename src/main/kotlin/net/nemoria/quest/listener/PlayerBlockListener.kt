package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.BranchRuntimeManager.BlockEventType
import net.nemoria.quest.runtime.PlayerBlockTracker
import org.bukkit.Material
import org.bukkit.block.CreatureSpawner
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.block.Action
import org.bukkit.event.world.StructureGrowEvent
import java.util.UUID

class PlayerBlockListener : Listener {
    private val bonemealTracker: MutableMap<String, Pair<UUID, Long>> = mutableMapOf()

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        Services.questService.handlePlayerBlockEvent(
            event.player,
            BlockEventType.BREAK,
            block,
            action = null,
            item = event.player.inventory.itemInMainHand,
            placedByPlayer = PlayerBlockTracker.isPlayerPlaced(block)
        )
        PlayerBlockTracker.remove(block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        PlayerBlockTracker.markPlaced(block, event.player.uniqueId)
        val spawnerTypeFromBlock = (event.blockPlaced.state as? CreatureSpawner)?.spawnedType?.name
        val spawnerTypeFromItem = (event.itemInHand.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)
            ?.blockState
            ?.let { it as? CreatureSpawner }
            ?.spawnedType
            ?.name
        val resolvedSpawnerType = spawnerTypeFromBlock ?: spawnerTypeFromItem
        Services.questService.handlePlayerBlockEvent(
            event.player,
            BlockEventType.PLACE,
            block,
            action = null,
            item = event.itemInHand,
            placedByPlayer = true
        )
        if (block.type == Material.SPAWNER) {
            Services.questService.handlePlayerBlockEvent(
                event.player,
                BlockEventType.SPAWNER_PLACE,
                block,
                action = null,
                item = event.itemInHand,
                placedByPlayer = true,
                spawnerType = resolvedSpawnerType
            )
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        val player = event.player ?: return
        val block = event.block
        Services.questService.handlePlayerBlockEvent(
            player,
            BlockEventType.IGNITE,
            block,
            action = null,
            item = player.inventory.itemInMainHand,
            placedByPlayer = PlayerBlockTracker.isPlayerPlaced(block)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val actLabel = actionLabel(event.action)
        val item = event.item
        if (item != null && item.type == Material.BONE_MEAL && block.type.name.endsWith("_SAPLING")) {
            bonemealTracker[locationKey(block)] = event.player.uniqueId to System.currentTimeMillis()
        }

        val toolKind = when {
            item != null && item.type.name.endsWith("_HOE") -> BlockEventType.FARM
            item != null && item.type.name.endsWith("_AXE") -> BlockEventType.STRIP
            item != null && (item.type.name.endsWith("_SHOVEL") || item.type.name.endsWith("_SPADE")) -> BlockEventType.MAKE_PATH
            else -> BlockEventType.INTERACT
        }

        Services.questService.handlePlayerBlockEvent(
            event.player,
            toolKind,
            block,
            action = actLabel,
            item = item,
            placedByPlayer = PlayerBlockTracker.isPlayerPlaced(block)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onFrostWalker(event: EntityBlockFormEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        val block = event.block
        Services.questService.handlePlayerBlockEvent(
            player,
            BlockEventType.FROST_WALK,
            block,
            action = null,
            item = player.inventory.itemInMainHand,
            placedByPlayer = PlayerBlockTracker.isPlayerPlaced(block)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onTreeGrow(event: StructureGrowEvent) {
        val block = event.location.block
        val key = locationKey(block)
        val bonemealInfo = bonemealTracker[key]
        val bonemealAgeMs = bonemealInfo?.let { System.currentTimeMillis() - it.second }
        val player = event.player
            ?: bonemealInfo?.let { (uuid, ts) ->
                if (System.currentTimeMillis() - ts < 30000) Bukkit.getPlayer(uuid) else null
            }
            ?: PlayerBlockTracker.owner(block)?.let { Bukkit.getPlayer(it) }
            ?: run {
                net.nemoria.quest.core.DebugLog.log(
                    "TREE_GROW no player found loc=$key species=${event.species} bonemeal=$bonemealInfo ageMs=$bonemealAgeMs owner=${PlayerBlockTracker.owner(block)}"
                )
                return
            }
        net.nemoria.quest.core.DebugLog.log(
            "TREE_GROW player=${player.name} loc=$key species=${event.species} bonemeal=$bonemealInfo ageMs=$bonemealAgeMs owner=${PlayerBlockTracker.owner(block)}"
        )
        Services.questService.handlePlayerBlockEvent(
            player,
            BlockEventType.TREE_GROW,
            block,
            action = null,
            item = player.inventory.itemInMainHand,
            placedByPlayer = PlayerBlockTracker.isPlayerPlaced(block),
            treeType = event.species.name
        )
        bonemealTracker.remove(key)
    }

    private fun actionLabel(action: Action): String? = when (action) {
        Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> "RIGHT_CLICK"
        Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> "LEFT_CLICK"
        Action.PHYSICAL -> "PHYSICAL"
        else -> action.name
    }

    private fun locationKey(block: org.bukkit.block.Block): String =
        "${block.world.name}:${block.x}:${block.y}:${block.z}"
}

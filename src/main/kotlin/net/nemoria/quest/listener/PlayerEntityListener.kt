package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.BranchRuntimeManager.EntityEventType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.meta.SpawnEggMeta

class PlayerEntityListener : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        Services.questService.handlePlayerEntityEvent(event.player, EntityEventType.INTERACT, event.rightClicked)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteractAt(event: PlayerInteractAtEntityEvent) {
        Services.questService.handlePlayerEntityEvent(event.player, EntityEventType.INTERACT, event.rightClicked)
    }

    @EventHandler(ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state == PlayerFishEvent.State.CAUGHT_ENTITY) {
            val caught = event.caught ?: return
            Services.questService.handlePlayerEntityEvent(event.player, EntityEventType.CATCH, caught)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damagerPlayer = event.damager as? Player
        val victimPlayer = event.entity as? Player
        if (damagerPlayer != null) {
            Services.questService.handlePlayerEntityEvent(damagerPlayer, EntityEventType.DAMAGE, event.entity)
        }
        if (victimPlayer != null) {
            Services.questService.handlePlayerEntityEvent(victimPlayer, EntityEventType.GET_DAMAGED, event.entity, damager = event.damager)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer
        if (killer != null) {
            Services.questService.handlePlayerEntityEvent(killer, EntityEventType.KILL, event.entity)
        }
        val players = event.entity.world.players
        players.forEach { p ->
            Services.questService.handlePlayerEntityEvent(p, EntityEventType.DEATH_NEARBY, event.entity)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onMount(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        Services.questService.handlePlayerEntityEvent(player, EntityEventType.MOUNT, event.vehicle)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDismount(event: VehicleExitEvent) {
        val player = event.exited as? Player ?: return
        Services.questService.handlePlayerEntityEvent(player, EntityEventType.DISMOUNT, event.vehicle)
    }

    @EventHandler(ignoreCancelled = true)
    fun onShear(event: PlayerShearEntityEvent) {
        Services.questService.handlePlayerEntityEvent(event.player, EntityEventType.SHEAR, event.entity)
    }

    @EventHandler(ignoreCancelled = true)
    fun onSpawnEgg(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        val item = event.item ?: return
        if (!item.type.name.endsWith("_SPAWN_EGG")) return
        val meta = item.itemMeta as? SpawnEggMeta ?: return
        val typeName = runCatching { meta.spawnedType?.name }.getOrNull() ?: return
        Services.questService.handlePlayerEntityEvent(event.player, EntityEventType.SPAWN, null, entityTypeHint = typeName)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val owner = event.owner as? Player ?: return
        Services.questService.handlePlayerEntityEvent(owner, EntityEventType.TAME, event.entity)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val breeder = event.breeder as? Player ?: return
        val child = event.entity
        val type = if (child.type.name.equals("TURTLE", true)) EntityEventType.TURTLE_BREED else EntityEventType.BREED
        Services.questService.handlePlayerEntityEvent(breeder, type, child)
    }
}

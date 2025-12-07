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
import com.destroystokyo.paper.event.entity.TurtleLayEggEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.meta.SpawnEggMeta
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerEntityListener : Listener {
    private val feedTracker: MutableMap<UUID, Pair<UUID, Long>> = ConcurrentHashMap()
    // turtles potrzebują czasu, aby złożyć jaja; dłuższe TTL pozwala zachować właściciela karmienia
    private val feedTtlMs = 120_000L

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        Services.questService.handlePlayerEntityEvent(event.player, EntityEventType.INTERACT, event.rightClicked)
        trackFeed(event.player, event.rightClicked)
        net.nemoria.quest.core.DebugLog.log(
            "INTERACT_ENTITY player=${event.player.name} target=${event.rightClicked.type} loc=${event.rightClicked.location}"
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteractAt(event: PlayerInteractAtEntityEvent) {
        Services.questService.handlePlayerEntityEvent(event.player, EntityEventType.INTERACT, event.rightClicked)
        trackFeed(event.player, event.rightClicked)
        net.nemoria.quest.core.DebugLog.log(
            "INTERACT_AT_ENTITY player=${event.player.name} target=${event.rightClicked.type} loc=${event.rightClicked.location}"
        )
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
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        val item = event.item ?: return
        if (!item.type.name.endsWith("_SPAWN_EGG")) return
        val meta = item.itemMeta as? SpawnEggMeta ?: return
        val typeName = runCatching { meta.spawnedType?.name }.getOrNull()
            ?: item.type.name.removeSuffix("_SPAWN_EGG")
        net.nemoria.quest.core.DebugLog.log("SPAWN_EGG player=${event.player.name} type=$typeName")
        Services.questService.handlePlayerEntityEvent(event.player, EntityEventType.SPAWN, null, entityTypeHint = typeName)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val owner = event.owner as? Player ?: return
        Services.questService.handlePlayerEntityEvent(owner, EntityEventType.TAME, event.entity)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTurtleLayEgg(event: TurtleLayEggEvent) {
        val feeder = resolveFeeder(event.entity.uniqueId) ?: return
        net.nemoria.quest.core.DebugLog.log(
            "TURTLE_EGG player=${feeder.name} turtle=${event.entity.uniqueId} loc=${event.location}"
        )
        Services.questService.handlePlayerEntityEvent(feeder, EntityEventType.TURTLE_BREED, event.entity)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val breeder = event.breeder as? Player
            ?: resolveFeeder(event.mother?.uniqueId, event.father?.uniqueId)
            ?: run {
                net.nemoria.quest.core.DebugLog.log(
                    "BREED_SKIP no player breeder=${event.breeder} mother=${event.mother?.uniqueId} father=${event.father?.uniqueId} child=${event.entity.type}"
                )
                return
            }
        val child = event.entity
        val type = if (child.type.name.equals("TURTLE", true)) EntityEventType.TURTLE_BREED else EntityEventType.BREED
        net.nemoria.quest.core.DebugLog.log(
            "BREED_EVENT player=${breeder.name} child=${child.type} mother=${event.mother?.type} father=${event.father?.type}"
        )
        Services.questService.handlePlayerEntityEvent(breeder, type, child)
    }

    private fun trackFeed(player: Player, entity: org.bukkit.entity.Entity) {
        if (entity !is org.bukkit.entity.LivingEntity) return
        purgeFeedTracker()
        feedTracker[entity.uniqueId] = player.uniqueId to System.currentTimeMillis()
        net.nemoria.quest.core.DebugLog.log("FEED_TRACK entity=${entity.type} owner=${player.name}")
    }

    private fun resolveFeeder(vararg ids: UUID?): Player? {
        val now = System.currentTimeMillis()
        purgeFeedTracker(now)
        ids.filterNotNull().forEach { id ->
            val entry = feedTracker[id]
            if (entry != null && now - entry.second <= feedTtlMs) {
                val player = org.bukkit.Bukkit.getPlayer(entry.first)
                if (player != null) return player
            }
        }
        return null
    }

    private fun purgeFeedTracker(now: Long = System.currentTimeMillis()) {
        feedTracker.entries.removeIf { now - it.value.second > feedTtlMs }
    }
}

package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.BranchRuntimeManager.PhysicalEventType
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerBedLeaveEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerPhysicalListener : Listener {
    private val sneakStart: MutableMap<UUID, Long> = ConcurrentHashMap()

    @EventHandler(ignoreCancelled = true)
    fun onBedEnter(event: PlayerBedEnterEvent) {
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.BED_ENTER, 1.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBedLeave(event: PlayerBedLeaveEvent) {
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.BED_LEAVE, 1.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.BUCKET_FILL, 1.0, event.bucket.name)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBurn(event: EntityCombustEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        val seconds = event.duration.toDouble()
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.BURN, seconds, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDie(event: EntityDeathEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.DIE, 1.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onRegain(event: EntityRegainHealthEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.GAIN_HEALTH, event.amount, event.regainReason.name)
    }

    @EventHandler(ignoreCancelled = true)
    fun onXp(event: PlayerExpChangeEvent) {
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.GAIN_XP, event.amount.toDouble(), null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPortalEnter(event: EntityPortalEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.PORTAL_ENTER, 1.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.PORTAL_LEAVE, 1.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onProjectile(event: EntityShootBowEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        val projType = event.projectile.type.name
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.SHOOT_PROJECTILE, 1.0, projType)
    }

    @EventHandler(ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? org.bukkit.entity.Player ?: return
        val projType = event.entity.type.name
        Services.questService.handlePlayerPhysicalEvent(shooter, PhysicalEventType.SHOOT_PROJECTILE, 1.0, projType)
    }

    @EventHandler(ignoreCancelled = true)
    fun onSneakToggle(event: PlayerToggleSneakEvent) {
        val now = System.currentTimeMillis()
        if (event.isSneaking) {
            sneakStart[event.player.uniqueId] = now
        } else {
            val start = sneakStart.remove(event.player.uniqueId)
            val durationSec = if (start != null) (now - start) / 1000.0 else 0.0
            Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.SNEAK_TIME, durationSec, null)
        }
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.TOGGLE_SNEAK, 1.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.TAKE_DAMAGE, event.damage, event.cause.name)
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? org.bukkit.entity.Player ?: return
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.VEHICLE_ENTER, 1.0, event.vehicle.type.name)
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) {
        val player = event.exited as? org.bukkit.entity.Player ?: return
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.VEHICLE_LEAVE, 1.0, event.vehicle.type.name)
    }
}

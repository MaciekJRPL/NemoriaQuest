package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.runtime.BranchRuntimeManager.MovementEventType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.entity.HorseJumpEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.event.player.PlayerStatisticIncrementEvent
import org.bukkit.Statistic

class PlayerMoveListener : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val from = event.from
        val to = event.to ?: return
        if (from.toVector() == to.toVector()) return
        Services.questService.handleCitizensNpcActivatorMove(player, from, to)
        val delta = from.distance(to)

        val inVehicle = player.vehicle != null
        val isGlide = player.isGliding
        val isSwim = player.isSwimming
        val isSprint = player.isSprinting && !isGlide && !isSwim && !inVehicle
        val onFoot = !inVehicle && !isGlide && !isSwim

        Services.questService.handlePlayerMovementEvent(player, MovementEventType.MOVE, delta, null)
        if (onFoot) Services.questService.handlePlayerMovementEvent(player, MovementEventType.FOOT, delta, null)
        if (player.isSwimming) Services.questService.handlePlayerMovementEvent(player, MovementEventType.SWIM, delta, null)
        if (isGlide) Services.questService.handlePlayerMovementEvent(player, MovementEventType.GLIDE, delta, null)
        if (inVehicle) {
            val vehicleType = player.vehicle?.type?.name
            Services.questService.handlePlayerMovementEvent(player, MovementEventType.VEHICLE, delta, vehicleType)
            // Boat movement is not always covered by VehicleMoveEvent when only passenger moves view;
            // send BOAT movement when riding a boat.
            if (player.vehicle is org.bukkit.entity.Boat) {
                Services.questService.handlePlayerMovementEvent(player, MovementEventType.VEHICLE, delta, "BOAT")
            }
        }
        if (isSprint) Services.questService.handlePlayerMovementEvent(player, MovementEventType.SPRINT, delta, null)
        if (onFoot && !isSprint) Services.questService.handlePlayerMovementEvent(player, MovementEventType.WALK, delta, null)
        if (from.y > to.y) {
            Services.questService.handlePlayerMovementEvent(player, MovementEventType.FALL, from.y - to.y, null)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onGlideToggle(event: EntityToggleGlideEvent) {
        val player = event.entity as? Player ?: return
        if (!event.isGliding) {
            Services.questService.handlePlayerMovementEvent(player, MovementEventType.LAND, 1.0, null)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onHorseJump(event: HorseJumpEvent) {
        val player = event.entity.passengers.firstOrNull { it is Player } as? Player ?: return
        Services.questService.handlePlayerMovementEvent(player, MovementEventType.HORSE_JUMP, 1.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onJumpStat(event: PlayerStatisticIncrementEvent) {
        if (event.statistic == Statistic.JUMP) {
            Services.questService.handlePlayerMovementEvent(event.player, MovementEventType.JUMP, event.newValue.toDouble() - event.previousValue.toDouble(), null)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleMove(event: VehicleMoveEvent) {
        if (event.from.toVector() == event.to.toVector()) return
        val passengers = event.vehicle.passengers.filterIsInstance<Player>()
        if (passengers.isEmpty()) return
        val delta = event.from.distance(event.to)
        passengers.forEach { p ->
            Services.questService.handlePlayerMovementEvent(p, MovementEventType.VEHICLE, delta, event.vehicle.type.name)
            if (event.vehicle is org.bukkit.entity.Boat) {
                Services.questService.handlePlayerMovementEvent(p, MovementEventType.VEHICLE, delta, "BOAT")
            }
        }
    }
}

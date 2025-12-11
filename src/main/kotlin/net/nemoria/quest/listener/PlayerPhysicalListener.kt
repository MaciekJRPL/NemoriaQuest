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
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Boat

class PlayerPhysicalListener : Listener {
    private val sneakStart: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val portalEnterMarks: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val sneakTrack: MutableMap<UUID, SneakSession> = ConcurrentHashMap()
    private val vehicleEnterCooldown: MutableMap<UUID, Long> = ConcurrentHashMap()

    private data class SneakSession(
        val start: Long,
        var counted: Int = 0,
        var task: BukkitTask? = null
    )

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
        val bucketName = event.bucket.name
        net.nemoria.quest.core.DebugLog.log(
            "BUCKET_FILL evt player=${event.player.name} block=${event.blockClicked?.type?.name} bucket=$bucketName cancelled=${event.isCancelled}"
        )
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.BUCKET_FILL, 1.0, bucketName)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBurn(event: EntityCombustEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        // Nie naliczamy tu postępu – realny czas palenia zliczamy z ticków obrażeń ognia.
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.BURN, 0.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDie(event: EntityDeathEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.DIE, 1.0, null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onRegain(event: EntityRegainHealthEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        net.nemoria.quest.core.DebugLog.log(
            "GAIN_EVT player=${player.name} amount=${event.amount} cause=${event.regainReason} cancelled=${event.isCancelled}"
        )
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.GAIN_HEALTH, event.amount, event.regainReason.name)
    }

    @EventHandler(ignoreCancelled = true)
    fun onXp(event: PlayerExpChangeEvent) {
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.GAIN_XP, event.amount.toDouble(), null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPortalEnter(event: EntityPortalEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        net.nemoria.quest.core.DebugLog.log(
            "PORTAL_ENTER_EVT player={player.name} from={player.world.name} cancelled={event.isCancelled}"
        )
        // Liczymy wejście na PlayerPortalEvent/Teleport, tu tylko debug gdy nieanulowane.
    }

    @EventHandler(ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        net.nemoria.quest.core.DebugLog.log(
            "PORTAL_ENTER_EVT player={event.player.name} from={event.from.world?.name} to={event.to?.world?.name} cancelled={event.isCancelled}"
        )
        Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.PORTAL_ENTER, 1.0, null)
        portalEnterMarks[event.player.uniqueId] = System.currentTimeMillis()
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val mark = portalEnterMarks.remove(event.player.uniqueId)
        val isRecentPortal = mark != null && (System.currentTimeMillis() - mark) <= 10_000
        net.nemoria.quest.core.DebugLog.log(
            "PORTAL_LEAVE_EVT player={event.player.name} from={event.from.name} to={event.player.world.name} portalRecent=isRecentPortal"
        )
        if (isRecentPortal) {
            Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.PORTAL_LEAVE, 1.0, null)
        }
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
            // start okresowe naliczanie w trakcie kucania
            val session = SneakSession(start = now)
            session.task = object : BukkitRunnable() {
                override fun run() {
                    Services.questService.handlePlayerPhysicalEvent(
                        event.player,
                        PhysicalEventType.SNEAK_TIME,
                        1.0,
                        null
                    )
                    session.counted += 1
                }
            }.runTaskTimer(Services.plugin, 20L, 20L)
            sneakTrack[event.player.uniqueId] = session
        } else {
            val start = sneakStart.remove(event.player.uniqueId)
            val session = sneakTrack.remove(event.player.uniqueId)
            session?.task?.cancel()
            val totalElapsed = if (start != null) (now - start) / 1000.0 else 0.0
            val remainder = totalElapsed - (session?.counted ?: 0)
            if (remainder > 0) {
                Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.SNEAK_TIME, remainder, null)
            }
        }
        if (event.isSneaking) {
            Services.questService.handlePlayerPhysicalEvent(event.player, PhysicalEventType.TOGGLE_SNEAK, 1.0, null)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        sneakStart.remove(uuid)
        portalEnterMarks.remove(uuid)
        vehicleEnterCooldown.remove(uuid)
        sneakTrack.remove(uuid)?.task?.cancel()
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        if (event.cause == EntityDamageEvent.DamageCause.FIRE ||
            event.cause == EntityDamageEvent.DamageCause.FIRE_TICK ||
            event.cause == EntityDamageEvent.DamageCause.LAVA ||
            event.cause == EntityDamageEvent.DamageCause.HOT_FLOOR
        ) {
            net.nemoria.quest.core.DebugLog.log(
                "BURN_EVT player=${player.name} cause=${event.cause} dmg=${event.damage} cancelled=${event.isCancelled}"
            )
            Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.BURN, 1.0, event.cause.name)
        }
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.TAKE_DAMAGE, event.damage, event.cause.name)
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? org.bukkit.entity.Player ?: return
        net.nemoria.quest.core.DebugLog.log(
            "VEHICLE_ENTER_EVT player=${player.name} vehicle=${event.vehicle.type} cancelled=${event.isCancelled}"
        )
        if (event.isCancelled) return
        val now = System.currentTimeMillis()
        val last = vehicleEnterCooldown[player.uniqueId]
        if (last != null && now - last < 2000) return
        vehicleEnterCooldown[player.uniqueId] = now
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.VEHICLE_ENTER, 1.0, event.vehicle.type.name)
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) {
        val player = event.exited as? org.bukkit.entity.Player ?: return
        net.nemoria.quest.core.DebugLog.log(
            "VEHICLE_LEAVE_EVT player=${player.name} vehicle=${event.vehicle.type} cancelled=${event.isCancelled}"
        )
        Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.VEHICLE_LEAVE, 1.0, event.vehicle.type.name)
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    fun onBoatInteract(event: PlayerInteractEntityEvent) {
        val boat = event.rightClicked as? Boat ?: return
        val player = event.player
        Services.plugin.server.scheduler.runTask(Services.plugin, Runnable {
            if (player.vehicle?.uniqueId != boat.uniqueId) return@Runnable
            val now = System.currentTimeMillis()
            val last = vehicleEnterCooldown[player.uniqueId]
            if (last != null && now - last < 2000) return@Runnable
            vehicleEnterCooldown[player.uniqueId] = now
            net.nemoria.quest.core.DebugLog.log(
                "VEHICLE_ENTER_FALLBACK player=${player.name} vehicle=${boat.type}"
            )
            Services.questService.handlePlayerPhysicalEvent(player, PhysicalEventType.VEHICLE_ENTER, 1.0, boat.type.name)
        })
    }
}

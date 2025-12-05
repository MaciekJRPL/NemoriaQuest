package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.quest.QuestObjectiveType
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerToggleSneakEvent

class QuestListeners : Listener {
    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        val type = event.entity.type
        val data = Services.storage.userRepo
        val active = data.load(killer.uniqueId).activeQuests
        if (active.isEmpty()) return
        val questService = Services.questService
        active.forEach { questId ->
            val model = Services.storage.questModelRepo.findById(questId) ?: return@forEach
            model.objectives.filter { it.type == QuestObjectiveType.KILL_MOB }.forEach { obj ->
                val match = obj.entityType?.let { eqType(it, type) } ?: true
                if (match) {
                    questService.incrementObjective(killer, questId, obj.id, obj.count)
                }
            }
        }
    }

    @EventHandler
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val mat = event.item.itemStack.type
        val data = Services.storage.userRepo
        val active = data.load(player.uniqueId).activeQuests
        if (active.isEmpty()) return
        val questService = Services.questService
        active.forEach { questId ->
            val model = Services.storage.questModelRepo.findById(questId) ?: return@forEach
            model.objectives.filter { it.type == QuestObjectiveType.COLLECT_ITEM }.forEach { obj ->
                val match = obj.material?.let { eqMat(it, mat) } ?: true
                if (match) {
                    val amount = event.item.itemStack.amount
                    repeat(amount) {
                        questService.incrementObjective(player, questId, obj.id, obj.count)
                    }
                }
            }
        }
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        if (event.from.toVector() == event.to?.toVector()) return
        val player = event.player
        val data = Services.storage.userRepo
        val active = data.load(player.uniqueId).activeQuests
        if (active.isEmpty()) return
        val questService = Services.questService
        val to = event.to ?: return
        active.forEach { questId ->
            val model = Services.storage.questModelRepo.findById(questId) ?: return@forEach
            model.objectives.filter { it.type == QuestObjectiveType.MOVE_TO }.forEach { obj ->
                val targetWorld = obj.world
                if (targetWorld != null && to.world?.name != targetWorld) return@forEach
                val targetLoc = Location(to.world, obj.x ?: return@forEach, obj.y ?: return@forEach, obj.z ?: return@forEach)
                val radius = obj.radius ?: 1.5
                if (to.distanceSquared(targetLoc) <= radius * radius) {
                    questService.completeObjective(player, questId, obj.id)
                }
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val data = Services.storage.userRepo.load(player.uniqueId)
        if (data.activeQuests.isEmpty()) return
        data.activeQuests.forEach { questId ->
            val model = Services.storage.questModelRepo.findById(questId) ?: return@forEach
            val branchId = data.progress[questId]?.currentBranchId
            val nodeId = data.progress[questId]?.currentNodeId
            val nodeType = branchId?.let { bid -> nodeId?.let { nid -> model.branches[bid]?.objects?.get(nid)?.type } }
            val resumableTypes = setOf(
                net.nemoria.quest.quest.QuestObjectNodeType.NPC_INTERACT,
                net.nemoria.quest.quest.QuestObjectNodeType.DIVERGE_CHAT,
                net.nemoria.quest.quest.QuestObjectNodeType.DIVERGE_GUI,
                net.nemoria.quest.quest.QuestObjectNodeType.DIVERGE_OBJECTS,
                net.nemoria.quest.quest.QuestObjectNodeType.NONE,
                net.nemoria.quest.quest.QuestObjectNodeType.GROUP,
                // blocks
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BLOCKS_BREAK,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BLOCKS_PLACE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BLOCKS_INTERACT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BLOCKS_IGNITE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BLOCKS_STRIP,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BLOCK_FARM,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BLOCK_FROST_WALK,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_MAKE_PATHS,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_SPAWNER_PLACE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_TREE_GROW,
                // items
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_ACQUIRE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_BREW,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_CONSUME,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_PUT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_TAKE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_CRAFT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_DROP,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_ENCHANT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_FISH,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_INTERACT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_MELT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_PICKUP,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_REPAIR,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_REQUIRE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_THROW,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ITEMS_TRADE,
                // movement
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_MOVE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_MOVE_BY_FOOT_DISTANCE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_POSITION,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_SWIM_DISTANCE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ELYTRA_FLY_DISTANCE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ELYTRA_LAND,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_FALL_DISTANCE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_HORSE_JUMP,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_JUMP,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_SPRINT_DISTANCE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_VEHICLE_DISTANCE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_WALK_DISTANCE,
                // physical
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BED_ENTER,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BED_LEAVE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BUCKET_FILL,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_BURN,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_DIE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_GAIN_HEALTH,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_GAIN_XP,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_PORTAL_ENTER,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_PORTAL_LEAVE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_SHOOT_PROJECTILE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_SNEAK,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_TAKE_DAMAGE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_TOGGLE_SNEAK,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_VEHICLE_ENTER,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_VEHICLE_LEAVE,
                // misc
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ACHIEVEMENT_AWARD,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_CHAT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_CONNECT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_DISCONNECT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_RESPAWN,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_WAIT,
                // entities
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_BREED,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_INTERACT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_CATCH,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_DAMAGE,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_DEATH_NEARBY,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_DISMOUNT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_GET_DAMAGED,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_KILL,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_MOUNT,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_SHEAR,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_SPAWN,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_ENTITIES_TAME,
                net.nemoria.quest.quest.QuestObjectNodeType.PLAYER_TURTLES_BREED
            )

            if (model.branches.isNotEmpty() && nodeType != null && nodeType in resumableTypes) {
                Services.questService.resumeBranch(player, model)
            }
        }
    }

    @EventHandler
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (event.isSneaking) {
            Services.questService.handleSneak(event.player)
        }
    }

    private fun eqType(config: String, actual: EntityType): Boolean {
        return runCatching { EntityType.valueOf(config.uppercase()) }.getOrNull() == actual
    }

    private fun eqMat(config: String, actual: Material): Boolean {
        return runCatching { Material.valueOf(config.uppercase()) }.getOrNull() == actual
    }
}

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
            val nodeType = branchId?.let { bid ->
                nodeId?.let { nid -> model.branches[bid]?.objects?.get(nid)?.type }
            }
            // Wznawiamy tylko węzły oczekujące na interakcję, by nie odtwarzać akcji po zalogowaniu
            if (model.branches.isNotEmpty() &&
                nodeType in listOf(
                    net.nemoria.quest.quest.QuestObjectNodeType.NPC_INTERACT,
                    net.nemoria.quest.quest.QuestObjectNodeType.DIVERGE_CHAT,
                    net.nemoria.quest.quest.QuestObjectNodeType.DIVERGE_GUI,
                    net.nemoria.quest.quest.QuestObjectNodeType.DIVERGE_OBJECTS,
                    net.nemoria.quest.quest.QuestObjectNodeType.NONE,
                    net.nemoria.quest.quest.QuestObjectNodeType.GROUP
                )
            ) {
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

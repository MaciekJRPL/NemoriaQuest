package net.nemoria.quest.runtime

import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.core.MessageFormatter
import net.nemoria.quest.runtime.ChatHideService
import net.nemoria.quest.runtime.ChatHistoryManager
import net.nemoria.quest.data.user.GroupProgress
import net.nemoria.quest.quest.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Horse
import org.bukkit.entity.Sheep
import org.bukkit.inventory.ItemStack
import org.bukkit.block.Block
import org.bukkit.Material
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.UUID
import kotlin.sequences.asSequence
import kotlin.math.sqrt

class BranchRuntimeManager(
    private val plugin: JavaPlugin,
    private val questService: QuestService
) {
    private val sessions: MutableMap<UUID, BranchSession> = mutableMapOf()
    private val divergeSessions: MutableMap<UUID, DivergeChatSession> = mutableMapOf()
    private val pendingPrompts: MutableMap<String, ActionContinuation> = mutableMapOf()
    private val pendingSneaks: MutableMap<UUID, ActionContinuation> = mutableMapOf()
    private val pendingNavigations: MutableMap<UUID, ActionContinuation> = mutableMapOf()
    private val particleScripts: MutableMap<String, ParticleScript> = mutableMapOf()
    private val guiSessions: MutableMap<UUID, DivergeGuiSession> = mutableMapOf()
    private val gson = GsonComponentSerializer.gson()
    private val legacySerializer = LegacyComponentSerializer.legacySection()
    private val historyNewline = Component.newline()

    companion object {
        private const val GREY_HISTORY_LIMIT = 40
        private fun progressKey(branchId: String, nodeId: String): String = "$branchId:$nodeId"
    }

    internal enum class BlockEventType { BREAK, PLACE, INTERACT, IGNITE, FROST_WALK, FARM, STRIP, MAKE_PATH, SPAWNER_PLACE, TREE_GROW }
    internal enum class EntityEventType {
        BREED,
        INTERACT,
        CATCH,
        DAMAGE,
        DEATH_NEARBY,
        DISMOUNT,
        GET_DAMAGED,
        KILL,
        MOUNT,
        SHEAR,
        SPAWN,
        TAME,
        TURTLE_BREED
    }
    internal enum class ItemEventType {
        ACQUIRE,
        BREW,
        CONSUME,
        CONTAINER_PUT,
        CONTAINER_TAKE,
        CRAFT,
        DROP,
        ENCHANT,
        FISH,
        INTERACT,
        MELT,
        PICKUP,
        REPAIR,
        REQUIRE,
        THROW,
        TRADE
    }

    internal enum class MovementEventType {
        MOVE,
        WALK,
        SPRINT,
        FOOT,
        SWIM,
        GLIDE,
        FALL,
        VEHICLE,
        HORSE_JUMP,
        JUMP,
        LAND
    }

    internal enum class PhysicalEventType {
        BED_ENTER,
        BED_LEAVE,
        BUCKET_FILL,
        BURN,
        DIE,
        GAIN_HEALTH,
        GAIN_XP,
        PORTAL_ENTER,
        PORTAL_LEAVE,
        SHOOT_PROJECTILE,
        SNEAK_TIME,
        TAKE_DAMAGE,
        TOGGLE_SNEAK,
        VEHICLE_ENTER,
        VEHICLE_LEAVE
    }

    internal enum class MiscEventType {
        CONNECT,
        DISCONNECT,
        RESPAWN,
        CHAT,
        ACHIEVEMENT
    }

    fun hasDiverge(player: OfflinePlayer): Boolean = divergeSessions.containsKey(player.uniqueId)

    fun hasDiverge(player: org.bukkit.entity.Player): Boolean = divergeSessions.containsKey(player.uniqueId)
    fun getQuestId(player: OfflinePlayer): String? = sessions[player.uniqueId]?.questId

    fun start(player: OfflinePlayer, model: QuestModel) {
        val saved = questService.progress(player)[model.id]
        val branchId = saved?.currentBranchId ?: model.mainBranch ?: model.branches.keys.firstOrNull() ?: return
        val branch = model.branches[branchId] ?: return
        val startNodeId = saved?.currentNodeId ?: branch.startsAt ?: branch.objects.keys.firstOrNull() ?: return
        net.nemoria.quest.core.DebugLog.log("Branch start quest=${model.id} branch=$branchId node=$startNodeId (resume=${saved?.currentNodeId != null})")
        val session = BranchSession(model.id, branchId, startNodeId)
        sessions[player.uniqueId] = session
        session.nodeId = startNodeId
        runNode(player, model, branchId, startNodeId, 0L)
        model.timeLimit?.let { tl ->
            val startedAt = questService.ensureTimeLimitStart(player, model.id)
            val elapsedMs = System.currentTimeMillis() - startedAt
            val remainingMs = tl.durationSeconds * 1000 - elapsedMs
            if (remainingMs <= 0) {
                stop(player)
                tl.failGoto?.let { goto ->
                    handleGoto(player, model, branchId, goto, 0)
                } ?: questService.finishOutcome(player, model.id, "FAIL")
                return
            }
            val ticks = ((remainingMs + 49) / 50).coerceAtLeast(1)
            session.timeLimitTask = object : BukkitRunnable() {
                override fun run() {
                    stop(player)
                    tl.failGoto?.let { goto ->
                        handleGoto(player, model, branchId, goto, 0)
                    } ?: questService.finishOutcome(player, model.id, "FAIL")
                }
            }.runTaskLater(plugin, ticks)
        }
    }

    fun forceGoto(player: OfflinePlayer, questId: String, branchId: String, nodeId: String): Boolean {
        val model = questService.questInfo(questId) ?: return false
        val branch = model.branches[branchId] ?: return false
        if (!branch.objects.containsKey(nodeId)) return false
        val existing = sessions[player.uniqueId]
        if (existing != null && existing.questId != questId) {
            stop(player)
        }
        val session = sessions.getOrPut(player.uniqueId) { BranchSession(questId, branchId, nodeId) }
        session.branchId = branchId
        session.nodeId = nodeId
        session.timeLimitTask?.cancel()
        session.waitTasks.values.forEach { it.cancel() }
        session.waitTasks.clear()
        // wyczyść progres (debugowy przeskok)
        session.blockProgress.clear()
        session.blockCounted.clear()
        session.movementProgress.clear()
        session.physicalProgress.clear()
        session.miscProgress.clear()
        session.itemProgress.clear()
        session.entityProgress.clear()
        session.positionActionbarHint.clear()
        questService.clearAllNodeProgress(player, model.id)
        // zresetuj bieżący węzeł, aby nie zaliczał się na starcie
        val currentNode = branch.objects[nodeId]
        if (currentNode != null) {
            questService.clearNodeProgress(player, model.id, branchId, nodeId)
            when {
                isMovementNode(currentNode.type) -> session.movementProgress[nodeId] = 0.0
                isPhysicalNode(currentNode.type) -> session.physicalProgress[nodeId] = 0.0
                isMiscNode(currentNode.type) -> session.miscProgress[nodeId] = 0.0
                isPlayerItemNode(currentNode.type) -> session.itemProgress[nodeId] = mutableMapOf()
                isPlayerBlockNode(currentNode.type) -> session.blockProgress[nodeId] = mutableMapOf()
                isPlayerEntityNode(currentNode.type) -> session.entityProgress[nodeId] = mutableMapOf()
            }
        }
        val hadDialog = divergeSessions.remove(player.uniqueId) != null
        if (hadDialog) {
            ChatHideService.flushBufferedToHistory(player.uniqueId)
            ChatHideService.endDialog(player.uniqueId)
        }
        questService.updateBranchState(player, model.id, branchId, nodeId)
        runNode(player, model, branchId, nodeId, 0)
        return true
    }

    fun stop(player: OfflinePlayer) {
        sessions.remove(player.uniqueId)?.let { sess ->
            sess.timeLimitTask?.cancel()
            sess.waitTasks.values.forEach { it.cancel() }
        }
        val hadDialog = divergeSessions.remove(player.uniqueId) != null
        val bukkit = player.player
        if (bukkit != null) {
            ChatHideService.flushBufferedToHistory(bukkit.uniqueId)
            if (hadDialog) ChatHideService.endDialog(bukkit.uniqueId) else ChatHideService.show(bukkit.uniqueId)
        } else {
            ChatHideService.flushBufferedToHistory(player.uniqueId)
            if (hadDialog) ChatHideService.endDialog(player.uniqueId) else ChatHideService.show(player.uniqueId)
        }
        pendingSneaks.remove(player.uniqueId)
        pendingNavigations.remove(player.uniqueId)
        pendingPrompts.entries.removeIf { it.value.playerId == player.uniqueId }
    }

    private fun runNode(player: OfflinePlayer, model: QuestModel, branchId: String, nodeId: String, delayTicks: Long) {
        val branch = model.branches[branchId] ?: return
        val node = branch.objects[nodeId]
        if (node == null) {
            net.nemoria.quest.core.DebugLog.log("Node not found quest=${model.id} branch=$branchId node=$nodeId")
            return
        }
        net.nemoria.quest.core.DebugLog.log("Schedule node quest=${model.id} branch=$branchId node=$nodeId delay=$delayTicks")
        sessions[player.uniqueId]?.nodeId = nodeId
        object : BukkitRunnable() {
            override fun run() {
                net.nemoria.quest.core.DebugLog.log("Execute node quest=${model.id} branch=$branchId node=$nodeId")
                executeNode(player, model, branchId, node)
            }
        }.runTaskLater(plugin, delayTicks)
    }

    private fun executeNode(player: OfflinePlayer, model: QuestModel, branchId: String, node: QuestObjectNode) {
        val p = player.player ?: return
        divergeSessions.remove(player.uniqueId)
        questService.updateBranchState(player, model.id, branchId, node.id)
        if (node.type != QuestObjectNodeType.DIVERGE_CHAT) {
            sendStartNotify(p, node)
        }
        if (node.type == QuestObjectNodeType.PLAYER_TOGGLE_SNEAK) {
            questService.clearNodeProgress(player, model.id, branchId, node.id)
            sessions[player.uniqueId]?.physicalProgress?.put(node.id, 0.0)
        }
        when (node.type) {
            QuestObjectNodeType.NONE -> {
                val pos = node.position
                val p = player.player ?: return
                if (pos != null) {
                    val target = resolvePosition(p, pos)
                    val radiusSq = pos.radius * pos.radius
                    if (p.location.world != target.world || p.location.distanceSquared(target) > radiusSq) {
                        runNode(player, model, branchId, node.id, 20)
                        return
                    }
                }
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.GROUP -> {
                startGroup(player, model, branchId, node)
            }
            QuestObjectNodeType.SERVER_ACTIONS -> {
                runActionQueue(player, model, branchId, node, 0, 0L)
            }
            QuestObjectNodeType.RANDOM -> {
                val targets = when {
                    node.randomGotos.isNotEmpty() -> node.randomGotos
                    node.gotos.isNotEmpty() -> node.gotos
                    node.goto != null -> listOf(node.goto)
                    else -> emptyList()
                }
                val avoidTypes = node.avoidRepeatEndTypes.map { it.uppercase() }
                val history = questService.progress(player)[model.id]?.randomHistory ?: emptySet()
                val filtered = if (avoidTypes.isNotEmpty()) {
                    targets.filterNot { tgt ->
                        val t = normalizeTarget(tgt)
                        if (t.startsWith("QUEST_", true)) {
                            val endType = t.removePrefix("QUEST_").uppercase()
                            avoidTypes.contains(endType) && history.contains(endType)
                        } else false
                    }
                } else targets
                val pick = (filtered.ifEmpty { targets }).randomOrNull() ?: return
                if (pick.startsWith("QUEST_", true)) {
                    val endType = pick.removePrefix("QUEST_").uppercase()
                    questService.mutateProgress(player, model.id) { it.randomHistory.add(endType) }
                }
                handleGoto(player, model, branchId, pick, 0)
            }
            QuestObjectNodeType.SERVER_ITEMS_CLEAR -> {
                p.inventory.clear()
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ITEMS_GIVE -> {
                giveOrDropItems(p, node, drop = false)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ITEMS_DROP -> {
                giveOrDropItems(p, node, drop = true)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ITEMS_MODIFY -> {
                val modified = modifyItems(p, node)
                DebugLog.log("Items modify count=$modified quest=${model.id} node=${node.id}")
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ITEMS_TAKE -> {
                val taken = takeItems(p, node)
                DebugLog.log("Items take count=$taken quest=${model.id} node=${node.id}")
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_COMMANDS_PERFORM -> {
                node.actions.forEach { cmd ->
                    val rendered = cmd.replace("{player}", p.name)
                    if (node.commandsAsPlayer) plugin.server.dispatchCommand(p, rendered)
                    else plugin.server.dispatchCommand(plugin.server.consoleSender, rendered)
                }
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LOGIC_VARIABLE -> {
                val varName = node.variable ?: return
                val value = applyValueFormula(model, player, varName, node.valueFormula)
                questService.updateVariable(player, model.id, varName, value)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LOGIC_MONEY -> {
                // TODO: integrate with economy; for now just run actions as commands
                val amount = evalFormula(node.valueFormula, 0.0)
                if (node.currency.equals("VAULT", true)) {
                    val cmd = "eco give ${p.name} ${amount.toInt()}"
                    plugin.server.dispatchCommand(plugin.server.consoleSender, cmd)
                } else {
                    node.actions.forEach {
                        plugin.server.dispatchCommand(plugin.server.consoleSender, it.replace("{player}", p.name))
                    }
                }
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_DAMAGE -> {
                val processed = processEntities(player, model.id, node) { ent, dmg -> ent.damage(dmg, p) }
                DebugLog.log("Entities damage processed=$processed quest=${model.id} node=${node.id}")
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_KILL -> {
                val processed = processEntities(player, model.id, node) { ent, _ -> ent.remove() }
                DebugLog.log("Entities kill processed=$processed quest=${model.id} node=${node.id}")
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_KILL_LINKED -> {
                val processed = killLinked(player, model.id, node)
                DebugLog.log("Entities kill linked processed=$processed quest=${model.id} node=${node.id}")
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_SPAWN -> {
                val spawned = spawnEntities(player, model.id, node)
                DebugLog.log("Entities spawn spawned=$spawned quest=${model.id} node=${node.id}")
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_TELEPORT -> {
                val processed = teleportEntities(player, model.id, node)
                DebugLog.log("Entities teleport processed=$processed quest=${model.id} node=${node.id}")
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_BLOCKS_PLACE -> {
                placeBlocks(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_EXPLOSIONS_CREATE -> {
                createExplosions(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_FIREWORKS_LAUNCH -> {
                launchFireworks(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LIGHTNING_STRIKE -> {
                lightningStrikes(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_PLAYER_DAMAGE -> {
                val dmg = node.damage ?: 0.0
                if (dmg > 0) p.damage(dmg)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_PLAYER_EFFECTS_GIVE -> {
                giveEffects(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_PLAYER_EFFECTS_REMOVE -> {
                removeEffects(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_PLAYER_TELEPORT -> {
                val target = resolvePosition(p, node.teleportPosition ?: node.position)
                p.teleport(target)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LOGIC_POINTS -> {
                val value = evalFormula(node.valueFormula, current = resolvePoints(player, node.pointsCategory))
                updatePoints(player, node.pointsCategory, value)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LOGIC_MODEL_VARIABLE -> {
                val varName = node.variable ?: return
                val value = applyValueFormula(model, player, varName, node.valueFormula)
                questService.updateVariable(player, model.id, varName, value)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LOGIC_SERVER_VARIABLE -> {
                val name = node.variable ?: return
                val current = net.nemoria.quest.core.Services.variables.server(name)?.toLongOrNull() ?: 0L
                val value = if (node.valueFormula.isNullOrBlank()) current else {
                    node.valueFormula.replace("{value}", current.toString()).split("+").mapNotNull { it.trim().toLongOrNull() }.sum()
                }
                net.nemoria.quest.core.Services.variables.setServer(name, value.toString())
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LOGIC_XP -> {
                val p = player.player ?: return
                val current = p.totalExperience
                val value = evalFormula(node.valueFormula, current.toDouble()).toInt()
                val newTotal = (current + value).coerceAtLeast(0)
                p.totalExperience = 0
                p.giveExp(newTotal)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ACHIEVEMENT_AWARD -> {
                awardAchievement(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_CAMERA_MODE_TOGGLE -> {
                toggleCameraMode(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.DIVERGE_GUI -> {
                openDivergeGui(p, model, branchId, node)
            }
            QuestObjectNodeType.DIVERGE_OBJECTS -> {
                startDivergeObjects(player, model, branchId, node)
            }
            QuestObjectNodeType.LOGIC_SWITCH -> {
                val next = evaluateLogicSwitch(player, model, node) ?: node.goto ?: node.gotos.firstOrNull()
                if (next != null) handleGoto(player, model, branchId, next, 0)
            }
            QuestObjectNodeType.CONDITIONS_SWITCH -> {
                val next = evalCases(player, model, node) ?: node.goto ?: node.gotos.firstOrNull()
                if (next != null) handleGoto(player, model, branchId, next, 0)
            }
            QuestObjectNodeType.NPC_INTERACT -> {
                sessions[player.uniqueId]?.nodeId = node.id
            }
            QuestObjectNodeType.DIVERGE_CHAT -> {
                sessions[player.uniqueId]?.nodeId = node.id
                ChatHideService.flushBufferedToHistory(p.uniqueId)
                val originalHistory = ChatHistoryManager.history(p.uniqueId)
                val limitedHistory = originalHistory.takeLast(GREY_HISTORY_LIMIT)
                val greyHistory = limitedHistory.map { ChatHistoryManager.greyOut(it) }
                val hiding = node.hideChat || node.dialog
                if (hiding) {
                    ChatHideService.beginDialog(player.uniqueId)
                }
                val baselineSeq = ChatHistoryManager.lastSequence(p.uniqueId)
                divergeSessions[player.uniqueId] = DivergeChatSession(
                    node.choices,
                    intro = (node.startNotify?.message ?: emptyList()) + node.message,
                    dialogMode = node.dialog,
                    currentIndex = 1,
                    lastRenderIdx = 1,
                    lastRenderAt = 0L,
                    originalHistory = originalHistory,
                    greyHistory = greyHistory,
                    baselineSeq = baselineSeq
                )
                sendDivergeChoices(p, node.choices, highlightIdx = 1, storeState = true)
            }
            QuestObjectNodeType.PLAYER_ITEMS_ACQUIRE,
            QuestObjectNodeType.PLAYER_ITEMS_BREW,
            QuestObjectNodeType.PLAYER_ITEMS_CONSUME,
            QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_PUT,
            QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_TAKE,
            QuestObjectNodeType.PLAYER_ITEMS_CRAFT,
            QuestObjectNodeType.PLAYER_ITEMS_DROP,
            QuestObjectNodeType.PLAYER_ITEMS_ENCHANT,
            QuestObjectNodeType.PLAYER_ITEMS_FISH,
            QuestObjectNodeType.PLAYER_ITEMS_INTERACT,
            QuestObjectNodeType.PLAYER_ITEMS_MELT,
            QuestObjectNodeType.PLAYER_ITEMS_PICKUP,
            QuestObjectNodeType.PLAYER_ITEMS_REPAIR,
            QuestObjectNodeType.PLAYER_ITEMS_REQUIRE,
            QuestObjectNodeType.PLAYER_ITEMS_THROW,
            QuestObjectNodeType.PLAYER_ITEMS_TRADE,
            QuestObjectNodeType.PLAYER_MOVE,
            QuestObjectNodeType.PLAYER_MOVE_BY_FOOT_DISTANCE,
            QuestObjectNodeType.PLAYER_POSITION,
            QuestObjectNodeType.PLAYER_SWIM_DISTANCE,
            QuestObjectNodeType.PLAYER_ELYTRA_FLY_DISTANCE,
            QuestObjectNodeType.PLAYER_ELYTRA_LAND,
            QuestObjectNodeType.PLAYER_FALL_DISTANCE,
            QuestObjectNodeType.PLAYER_HORSE_JUMP,
            QuestObjectNodeType.PLAYER_JUMP,
            QuestObjectNodeType.PLAYER_SPRINT_DISTANCE,
            QuestObjectNodeType.PLAYER_VEHICLE_DISTANCE,
            QuestObjectNodeType.PLAYER_WALK_DISTANCE,
            QuestObjectNodeType.PLAYER_BED_ENTER,
            QuestObjectNodeType.PLAYER_BED_LEAVE,
            QuestObjectNodeType.PLAYER_BUCKET_FILL,
            QuestObjectNodeType.PLAYER_BURN,
            QuestObjectNodeType.PLAYER_DIE,
            QuestObjectNodeType.PLAYER_GAIN_HEALTH,
            QuestObjectNodeType.PLAYER_GAIN_XP,
            QuestObjectNodeType.PLAYER_PORTAL_ENTER,
            QuestObjectNodeType.PLAYER_PORTAL_LEAVE,
            QuestObjectNodeType.PLAYER_SHOOT_PROJECTILE,
            QuestObjectNodeType.PLAYER_SNEAK,
            QuestObjectNodeType.PLAYER_TAKE_DAMAGE,
            QuestObjectNodeType.PLAYER_TOGGLE_SNEAK,
            QuestObjectNodeType.PLAYER_VEHICLE_ENTER,
            QuestObjectNodeType.PLAYER_VEHICLE_LEAVE,
            QuestObjectNodeType.PLAYER_ACHIEVEMENT_AWARD,
            QuestObjectNodeType.PLAYER_CHAT,
            QuestObjectNodeType.PLAYER_CONNECT,
            QuestObjectNodeType.PLAYER_DISCONNECT,
            QuestObjectNodeType.PLAYER_RESPAWN,
            QuestObjectNodeType.PLAYER_ENTITIES_BREED,
            QuestObjectNodeType.PLAYER_ENTITIES_INTERACT,
            QuestObjectNodeType.PLAYER_ENTITIES_CATCH,
            QuestObjectNodeType.PLAYER_ENTITIES_DAMAGE,
            QuestObjectNodeType.PLAYER_ENTITIES_DEATH_NEARBY,
            QuestObjectNodeType.PLAYER_ENTITIES_DISMOUNT,
            QuestObjectNodeType.PLAYER_ENTITIES_GET_DAMAGED -> {
                sessions[player.uniqueId]?.nodeId = node.id
                preloadNodeProgress(player, model, branchId, node)
            }
            QuestObjectNodeType.PLAYER_ENTITIES_KILL,
            QuestObjectNodeType.PLAYER_ENTITIES_MOUNT,
            QuestObjectNodeType.PLAYER_ENTITIES_SHEAR,
            QuestObjectNodeType.PLAYER_ENTITIES_SPAWN,
            QuestObjectNodeType.PLAYER_ENTITIES_TAME,
            QuestObjectNodeType.PLAYER_TURTLES_BREED,
            QuestObjectNodeType.PLAYER_BLOCKS_BREAK,
            QuestObjectNodeType.PLAYER_BLOCKS_PLACE,
            QuestObjectNodeType.PLAYER_BLOCKS_INTERACT,
            QuestObjectNodeType.PLAYER_BLOCKS_IGNITE,
            QuestObjectNodeType.PLAYER_BLOCKS_STRIP,
            QuestObjectNodeType.PLAYER_BLOCK_FARM,
            QuestObjectNodeType.PLAYER_BLOCK_FROST_WALK,
            QuestObjectNodeType.PLAYER_MAKE_PATHS,
            QuestObjectNodeType.PLAYER_SPAWNER_PLACE,
            QuestObjectNodeType.PLAYER_TREE_GROW -> {
                sessions[player.uniqueId]?.nodeId = node.id
                preloadNodeProgress(player, model, branchId, node)
            }
            QuestObjectNodeType.PLAYER_WAIT -> {
                sessions[player.uniqueId]?.nodeId = node.id
                val seconds = (node.waitGoalSeconds ?: node.count.toLong()).coerceAtLeast(0)
                if (seconds > 0) {
                    val task = object : BukkitRunnable() {
                        override fun run() {
                            val gotoRaw = node.goto ?: return
                            val target = normalizeTarget(gotoRaw)
                            runNode(player, model, branchId, target, 0)
                        }
                    }.runTaskLater(plugin, seconds * 20)
                    sessions[player.uniqueId]?.waitTasks?.put(node.id, task)
                } else {
                    node.goto?.let { handleGoto(player, model, branchId, it, 0) }
                }
            }
        }
    }

    private fun runActionQueue(
        player: OfflinePlayer,
        model: QuestModel,
        branchId: String,
        node: QuestObjectNode,
        startIndex: Int,
        startDelay: Long
    ) {
        val bukkitPlayer = player.player ?: return
        var delay = startDelay
        val actions = node.actions
        for (i in startIndex until actions.size) {
            val action = actions[i].trim()
            if (action.isEmpty()) continue
            val parts = action.split("\\s+".toRegex(), limit = 2)
            val key = parts.getOrNull(0)?.uppercase() ?: continue
            val payload = parts.getOrNull(1) ?: ""
            DebugLog.log("Action node=${node.id} key=$key payload=$payload delay=$delay")
            when (key) {
                "WAIT_TICKS" -> delay += payload.toLongOrNull() ?: 0L
                "SEND_MESSAGE" -> schedule(delay) {
                    MessageFormatter.send(bukkitPlayer, renderRaw(payload, model, player))
                }
                "SEND_SOUND" -> schedule(delay) { playSound(bukkitPlayer, payload) }
                "SEND_TITLE" -> schedule(delay) {
                    val partsTitle = payload.split("\\s+".toRegex(), limit = 5)
                    val fadeIn = partsTitle.getOrNull(0)?.toIntOrNull() ?: 10
                    val stay = partsTitle.getOrNull(1)?.toIntOrNull() ?: 60
                    val fadeOut = partsTitle.getOrNull(2)?.toIntOrNull() ?: 10
                    val rest = payload.substringAfter(partsTitle.take(3).joinToString(" "), "")
                    val titles = rest.split(",", limit = 2)
                    val titleText = MessageFormatter.format(renderRaw(titles.getOrNull(0) ?: "", model, player))
                    val subText = MessageFormatter.format(renderRaw(titles.getOrNull(1) ?: "", model, player))
                    bukkitPlayer.sendTitle(titleText, subText, fadeIn, stay, fadeOut)
                }
                "SEND_PARTICLES" -> schedule(delay) {
                    val params = payload.split("\\s+".toRegex())
                    val questOnly = params.getOrNull(2)?.toBooleanStrictOrNull() ?: true
                    spawnParticles(bukkitPlayer, payload, allPlayers = !questOnly)
                }
                "GIVE_EFFECT" -> schedule(delay) { giveEffect(bukkitPlayer, payload) }
                "PERFORM_COMMAND" -> schedule(delay) {
                    val partsCmd = payload.split("\\s+".toRegex(), limit = 2)
                    val asPlayer = partsCmd.getOrNull(0)?.equals("true", ignoreCase = true) == true ||
                        partsCmd.getOrNull(0)?.equals("player", ignoreCase = true) == true
                    val cmd = if (asPlayer) partsCmd.getOrNull(1) else payload
                    if (!cmd.isNullOrBlank()) {
                        val rendered = renderRaw(cmd, model, player).replace("{player}", bukkitPlayer.name)
                        if (asPlayer) plugin.server.dispatchCommand(bukkitPlayer, rendered)
                        else plugin.server.dispatchCommand(plugin.server.consoleSender, rendered)
                    }
                }
                "PERFORM_COMMAND_AS_PLAYER" -> schedule(delay) {
                    val cmd = renderRaw(payload, model, player).replace("{player}", bukkitPlayer.name)
                    plugin.server.dispatchCommand(bukkitPlayer, cmd)
                }
                "PERFORM_COMMAND_AS_OP_PLAYER" -> schedule(delay) {
                    val cmd = renderRaw(payload, model, player).replace("{player}", bukkitPlayer.name)
                    val prev = bukkitPlayer.isOp
                    bukkitPlayer.isOp = true
                    plugin.server.dispatchCommand(bukkitPlayer, cmd)
                    bukkitPlayer.isOp = prev
                }
                "PERFORM_OBJECT" -> schedule(delay) {
                    val partsObj = payload.split("\\s+".toRegex(), limit = 2)
                    val bId = partsObj.getOrNull(0) ?: return@schedule
                    val nId = normalizeTarget(partsObj.getOrNull(1) ?: return@schedule)
                    runNode(player, model, bId, nId, 0)
                }
                "PERFORM_PARTICLE_SCRIPT" -> schedule(delay) { runParticleScript(bukkitPlayer, payload) }
                "START_BRANCH", "START_INDIVIDUAL_BRANCH" -> schedule(delay) {
                    val bId = payload.trim()
                    val branch = model.branches[bId] ?: return@schedule
                    val startNode = branch.startsAt ?: branch.objects.keys.firstOrNull() ?: return@schedule
                    sessions[bukkitPlayer.uniqueId] = BranchSession(model.id, bId, startNode)
                    runNode(player, model, bId, startNode, 0)
                }
                "STOP_BRANCH" -> schedule(delay) {
                    val session = sessions[bukkitPlayer.uniqueId]
                    if (session != null && (payload.isBlank() || session.branchId.equals(payload.trim(), true))) {
                        sessions.remove(bukkitPlayer.uniqueId)
                    }
                }
                "START_QUEST", "START_FUNCTIONAL_QUEST" -> schedule(delay) {
                    questService.startQuest(bukkitPlayer, payload.trim())
                }
                "STOP_QUEST", "STOP_FUNCTIONAL_QUEST" -> schedule(delay) {
                    questService.stopQuest(bukkitPlayer, payload.trim(), complete = false)
                }
                "PROMPT_NEXT" -> {
                    val token = UUID.randomUUID().toString()
                    schedule(delay) { sendPrompt(bukkitPlayer, renderRaw(payload, model, player), token) }
                    pendingPrompts[token] = ActionContinuation(
                        playerId = bukkitPlayer.uniqueId,
                        questId = model.id,
                        branchId = branchId,
                        nodeId = node.id,
                        nextIndex = i + 1,
                        pendingDelay = 0L
                    )
                    return
                }
                "PROMPT_NEXT_SNEAK" -> {
                    schedule(delay) {
                        if (payload.isNotBlank()) MessageFormatter.send(bukkitPlayer, renderRaw(payload, model, player))
                        MessageFormatter.send(bukkitPlayer, "<gray>Kucnij, aby kontynuować...")
                    }
                    pendingSneaks[bukkitPlayer.uniqueId] = ActionContinuation(
                        playerId = bukkitPlayer.uniqueId,
                        questId = model.id,
                        branchId = branchId,
                        nodeId = node.id,
                        nextIndex = i + 1,
                        pendingDelay = 0L
                    )
                    return
                }
                "CITIZENS_NPC_NAVIGATE" -> {
                    val wait = scheduleCitizensNavigation(bukkitPlayer, payload)
                    if (wait) {
                        pendingNavigations[bukkitPlayer.uniqueId] = ActionContinuation(
                            playerId = bukkitPlayer.uniqueId,
                            questId = model.id,
                            branchId = branchId,
                            nodeId = node.id,
                            nextIndex = i + 1,
                            pendingDelay = 0L
                        )
                        return
                    }
                }
            }
        }
        node.goto?.let { handleGoto(player, model, branchId, it, delay) }
    }

    private fun applyValueFormula(model: QuestModel, player: OfflinePlayer, variable: String, formula: String?): String {
        val data = questService.progress(player)[model.id]
        val current = data?.variables?.get(variable)?.toLongOrNull() ?: 0L
        if (formula.isNullOrBlank()) return current.toString()
        val parts = formula.replace("{value}", current.toString()).split("+")
        return parts.mapNotNull { it.trim().toLongOrNull() }.sum().toString()
    }

    private fun applyUserValueFormula(player: OfflinePlayer, variable: String, formula: String?): String {
        val current = questService.userVariable(player, variable).toLongOrNull() ?: 0L
        if (formula.isNullOrBlank()) return current.toString()
        val parts = formula.replace("{value}", current.toString()).split("+")
        return parts.mapNotNull { it.trim().toLongOrNull() }.sum().toString()
    }

    private fun startGroup(player: OfflinePlayer, model: QuestModel, branchId: String, node: QuestObjectNode) {
        val children = node.groupObjects
        if (children.isEmpty()) {
            node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            return
        }
        val required = (node.groupRequired ?: children.size).coerceAtLeast(1)
        val progress = questService.progress(player)[model.id]
        val existing = progress?.groupState?.get(node.id)
        val gp = if (existing != null) {
            existing
        } else {
            GroupProgress(completed = mutableSetOf(), remaining = children.toMutableList(), required = required, ordered = node.groupOrdered)
        }
        questService.mutateProgress(player, model.id) { it.groupState[node.id] = gp }
        val remaining = gp.remaining.ifEmpty { children.toMutableList() }
        val next = if (gp.ordered) remaining.first() else remaining.random()
        gp.remaining.remove(next)
        questService.mutateProgress(player, model.id) { it.groupState[node.id] = gp }
        runNode(player, model, branchId, next, 0)
    }

    private fun schedule(delay: Long, block: () -> Unit) {
        object : BukkitRunnable() {
            override fun run() {
                block()
            }
        }.runTaskLater(plugin, delay)
    }

    private fun sendPrompt(player: org.bukkit.entity.Player, message: String, token: String) {
        if (message.isNotBlank()) {
            MessageFormatter.send(player, message)
        }
        val prompt = MiniMessage.miniMessage()
            .deserialize("<click:run_command:'/nq prompt $token'><hover:show_text:'Kliknij, aby kontynuować'>[Kliknij, aby kontynuować]</hover></click>")
        player.sendMessage(prompt)
    }

    private fun resumeContinuation(player: org.bukkit.entity.Player, cont: ActionContinuation) {
        if (cont.playerId != player.uniqueId) return
        val model = questService.questInfo(cont.questId) ?: return
        val branch = model.branches[cont.branchId] ?: return
        val node = branch.objects[cont.nodeId] ?: return
        if (!sessions.containsKey(player.uniqueId)) {
            sessions[player.uniqueId] = BranchSession(cont.questId, cont.branchId, cont.nodeId)
        }
        runActionQueue(player, model, cont.branchId, node, cont.nextIndex, cont.pendingDelay)
    }

    fun handlePromptClick(player: org.bukkit.entity.Player, token: String) {
        val cont = pendingPrompts.remove(token) ?: return
        resumeContinuation(player, cont)
    }

    fun handleSneakResume(player: org.bukkit.entity.Player) {
        val cont = pendingSneaks.remove(player.uniqueId) ?: return
        resumeContinuation(player, cont)
    }

    private fun runParticleScript(player: org.bukkit.entity.Player, payload: String) {
        val id = payload.trim()
        val script = particleScripts[id] ?: loadParticleScript(id)?.also { particleScripts[id] = it }
        if (script == null) {
            // fallback: treat payload as direct particle name
            spawnParticles(player, payload, allPlayers = false)
            return
        }
        object : BukkitRunnable() {
            var ticks = 0L
            override fun run() {
                if (!player.isOnline) { cancel(); return }
                if (ticks >= script.duration) { cancel(); return }
                val loc = player.location.clone().add(script.offsetX, script.offsetY, script.offsetZ)
                player.world.spawnParticle(script.particle, loc, script.count, script.spreadX, script.spreadY, script.spreadZ, script.speed)
                ticks += script.interval
            }
        }.runTaskTimer(plugin, 0L, script.interval)
    }

    private fun loadParticleScript(id: String): ParticleScript? {
        val file = File(plugin.dataFolder, "content/particle_scripts/$id.yml")
        if (!file.exists()) return null
        val cfg = YamlConfiguration.loadConfiguration(file)
        val particle = runCatching { Particle.valueOf(cfg.getString("particle")?.uppercase() ?: "") }.getOrNull() ?: return null
        return ParticleScript(
            particle = particle,
            count = cfg.getInt("count", 10),
            offsetX = cfg.getDouble("offset.x", 0.0),
            offsetY = cfg.getDouble("offset.y", 0.0),
            offsetZ = cfg.getDouble("offset.z", 0.0),
            spreadX = cfg.getDouble("spread.x", 0.0),
            spreadY = cfg.getDouble("spread.y", 0.0),
            spreadZ = cfg.getDouble("spread.z", 0.0),
            speed = cfg.getDouble("speed", 0.0),
            interval = cfg.getLong("interval", 5L),
            duration = cfg.getLong("duration", 40L)
        )
    }

    private fun scheduleCitizensNavigation(player: org.bukkit.entity.Player, payload: String): Boolean {
        val parts = payload.split("\\s+".toRegex())
        val npcId = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val locArg = parts.getOrNull(1)
        val wait = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false
        val radius = parts.getOrNull(3)?.toDoubleOrNull()
        val npc = runCatching {
            val api = Class.forName("net.citizensnpcs.api.CitizensAPI")
            val reg = api.getMethod("getNPCRegistry").invoke(null)
            val getNpc = reg.javaClass.getMethod("getById", Int::class.javaPrimitiveType)
            getNpc.invoke(reg, npcId)
        }.getOrNull() ?: return false
        val navigator = runCatching { npc.javaClass.getMethod("getNavigator").invoke(npc) }.getOrNull() ?: return false
        val targetLoc = when {
            locArg.isNullOrBlank() -> player.location
            locArg.equals("player", ignoreCase = true) -> player.location
            locArg.contains(",") -> {
                val coords = locArg.split(",")
                val x = coords.getOrNull(0)?.toDoubleOrNull() ?: player.location.x
                val y = coords.getOrNull(1)?.toDoubleOrNull() ?: player.location.y
                val z = coords.getOrNull(2)?.toDoubleOrNull() ?: player.location.z
                val worldName = coords.getOrNull(3) ?: player.world.name
                val world = plugin.server.getWorld(worldName) ?: player.world
                org.bukkit.Location(world, x, y, z)
            }
            else -> player.location
        }
        runCatching {
            val setTarget = navigator.javaClass.getMethod("setTarget", org.bukkit.Location::class.java)
            setTarget.invoke(navigator, targetLoc)
        }
        if (!wait) return false
        object : BukkitRunnable() {
            override fun run() {
                val navigating = runCatching {
                    navigator.javaClass.getMethod("isNavigating").invoke(navigator) as? Boolean ?: false
                }.getOrDefault(false)
                val arrived = radius?.let {
                    targetLoc.world == player.world && targetLoc.distanceSquared(player.location) <= it * it
                } ?: false
                if (!navigating || arrived) {
                    cancel()
                    pendingNavigations.remove(player.uniqueId)?.let { cont ->
                        resumeContinuation(player, cont)
                    }
                }
            }
        }.runTaskTimer(plugin, 10L, 10L)
        return true
    }

    private fun evaluateLogicSwitch(player: OfflinePlayer, model: QuestModel, node: QuestObjectNode): String? {
        val logic = node.logic?.trim() ?: return null
        val pattern = "\\{(mvariable|gvariable):([A-Za-z0-9_]+)}\\s*([=<>!]+)\\s*(-?\\d+)".toRegex()
        val match = pattern.find(logic) ?: return null
        val scope = match.groupValues[1]
        val varName = match.groupValues[2]
        val op = match.groupValues[3]
        val target = match.groupValues[4].toLongOrNull() ?: return null
        val current = resolveVariable(player, model.id, scope, varName)
        val ok = compareLong(current, target, op)
        return if (ok) node.goto else node.gotos.firstOrNull()
    }

    private fun evalCases(player: OfflinePlayer, model: QuestModel, node: QuestObjectNode): String? {
        val p = player.player ?: return null
        node.cases.forEach { c ->
            val matches = c.conditions.count { questService.checkConditions(p, listOf(it), model.id) }
            val noMatch = c.conditions.size - matches
            if (matches >= c.matchAmount && noMatch <= c.noMatchAmount && c.goto != null) {
                return c.goto
            }
        }
        return null
    }

    private fun resolveVariable(player: OfflinePlayer, questId: String, scope: String, name: String): Long {
        // scope currently treated the same; placeholder for future global variables
        val data = questService.progress(player)[questId]
        return data?.variables?.get(name)?.toLongOrNull() ?: 0L
    }

    private fun compareLong(current: Long, target: Long, op: String): Boolean =
        when (op) {
            "=", "==" -> current == target
            ">" -> current > target
            "<" -> current < target
            ">=" -> current >= target
            "<=" -> current <= target
            "!=" -> current != target
            else -> false
        }

    private fun handleGoto(player: OfflinePlayer, model: QuestModel, branchId: String, goto: String, delay: Long, currentNodeId: String? = null) {
        var target = normalizeTarget(goto)
        val questId = model.id
        // diverge objects mapping
        sessions[player.uniqueId]?.let { sess ->
            if (currentNodeId != null && sess.divergeObjectMap.containsKey(currentNodeId)) {
                val choice = sess.divergeObjectMap.remove(currentNodeId)
                if (choice != null) {
                    val key = "${sess.nodeId}:${choice.id}".lowercase()
                    questService.mutateProgress(player, questId) { prog ->
                        val cur = prog.divergeCounts[key] ?: 0
                        prog.divergeCounts[key] = cur + 1
                    }
                    val mapped = choice.goto
                    if (!mapped.isNullOrBlank()) {
                        target = normalizeTarget(mapped)
                    }
                }
                sess.divergeObjectMap.clear()
            }
        }
        // group progress
        if (currentNodeId != null) {
            val gpEntry = findGroupForChild(player, questId, currentNodeId)
            if (gpEntry != null) {
                val (parentId, gp) = gpEntry
                gp.completed.add(currentNodeId)
                gp.remaining.remove(currentNodeId)
                val required = gp.required.coerceAtLeast(1)
                val remaining = gp.remaining.toList()
                if (gp.completed.size >= required || remaining.isEmpty()) {
                    questService.mutateProgress(player, questId) { it.groupState.remove(parentId) }
                    target = model.branches[branchId]?.objects?.get(parentId)?.goto?.let { normalizeTarget(it) } ?: target
                } else {
                    val next = if (gp.ordered) remaining.first() else remaining.random()
                    questService.mutateProgress(player, questId) { it.groupState[parentId] = gp }
                    target = normalizeTarget(next)
                }
            }
        }
        net.nemoria.quest.core.DebugLog.log("Goto quest=${model.id} branch=$branchId to=$target delay=$delay")
        if (target.startsWith("QUEST_", ignoreCase = true)) {
            val key = target.removePrefix("QUEST_")
            questService.finishOutcome(player, questId, key)
            if (model.branches.isNotEmpty()) {
                questService.mutateProgress(player, questId) { it.randomHistory.add(key.uppercase()) }
            }
            return
        }
        runNode(player, model, branchId, target, delay)
    }

    private fun normalizeTarget(raw: String): String {
        var t = raw.trim()
        if (t.uppercase().startsWith("OBJECT")) {
            t = t.substringAfter("OBJECT").trim()
        }
        return t
    }

    fun handleNpcInteract(player: OfflinePlayer, npcId: Int) {
        val session = sessions[player.uniqueId] ?: return
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (node.type != QuestObjectNodeType.NPC_INTERACT) return
        if (node.npcId != null && node.npcId != 0 && node.npcId != npcId) return
        if (!isClickAllowed(node.clickTypes, "RIGHT_CLICK")) return
        val gotoRaw = node.goto ?: return
        val target = normalizeTarget(gotoRaw)
        net.nemoria.quest.core.DebugLog.log("NPC match quest=${model.id} node=${node.id} npc=$npcId -> $target")
        runNode(player, model, session.branchId, target, 0)
    }

    fun handleDivergeChoice(player: OfflinePlayer, choiceIndex: Int) {
        val sessionChoices = divergeSessions[player.uniqueId] ?: return
        val choice = sessionChoices.choices.getOrNull(choiceIndex - 1) ?: return
        val session = sessions[player.uniqueId] ?: return
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (node.type != QuestObjectNodeType.DIVERGE_CHAT) return
        val diverge = divergeSessions.remove(player.uniqueId)
        val bukkit = player.player
        if (bukkit != null && diverge != null) {
            ChatHideService.flushBufferedToHistory(bukkit.uniqueId)
            val newMessages = ChatHistoryManager.historySince(bukkit.uniqueId, diverge.baselineSeq)
            val mergedHistory = diverge.originalHistory + newMessages
            clearChatWindow(bukkit, 100)
            replayHistory(bukkit, mergedHistory, trackSynthetic = true)
        }
        ChatHideService.endDialog(player.uniqueId)
        val gotoRaw = choice.goto ?: return
        val target = normalizeTarget(gotoRaw)
        net.nemoria.quest.core.DebugLog.log("Diverge choice idx=$choiceIndex quest=${model.id} node=${node.id} -> $target")
        runNode(player, model, session.branchId, target, 0)
    }

    private fun sendDivergeChoices(
        player: org.bukkit.entity.Player,
        choices: List<DivergeChoice>,
        highlightIdx: Int = 1,
        storeState: Boolean = false
    ) {
        val session = divergeSessions[player.uniqueId]
        if (choices.isEmpty()) return
        if (session != null && session.lastRenderIdx == highlightIdx && !storeState) return
        if (session != null) {
            val now = System.currentTimeMillis()
            if (!storeState && now - session.lastRenderAt < 200) return
            session.lastRenderIdx = highlightIdx
            session.lastRenderAt = System.currentTimeMillis()
            val clearLines = 100
            clearChatWindow(player, clearLines)
            val historySlice = session.greyHistory.takeLast(GREY_HISTORY_LIMIT)
            replayHistory(player, historySlice, trackSynthetic = true)
            repeat(2) { sendSyntheticMessage(player, Component.empty(), trackSynthetic = true) }
        }
        val introLines = session?.intro?.map { legacySerializer.deserialize(MessageFormatter.format(it)) } ?: emptyList()
        val optionLines = choices.mapIndexed { idx, ch ->
            val prefixColor = if (idx + 1 == highlightIdx) "<red>> " else "<green>> "
            val formatted = MessageFormatter.format("$prefixColor${idx + 1}. ${ch.text}")
            val comp = legacySerializer.deserialize(formatted)
            comp.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/nq diverge ${idx + 1}"))
        }
        val hintRaw = net.nemoria.quest.core.Services.i18n.msg("chat_hint")
        val hintComp = legacySerializer.deserialize(MessageFormatter.format(hintRaw))

        val allComps = introLines + optionLines + hintComp

        val sendMenu: () -> Unit = {
            allComps.forEach { comp ->
                sendSyntheticMessage(player, comp, trackSynthetic = true)
            }
            session?.lastDialog = allComps.map { legacySerializer.serialize(it) }
        }

        if (storeState) {
            plugin.server.scheduler.runTask(plugin, Runnable { sendMenu() })
        } else {
            sendMenu()
        }
    }

    fun scrollDiverge(player: org.bukkit.entity.Player, delta: Int) {
        val session = divergeSessions[player.uniqueId] ?: return
        if (session.choices.isEmpty()) return
        val size = session.choices.size
        val next = ((session.currentIndex - 1 + delta) % size + size) % size + 1
        session.currentIndex = next
        sendDivergeChoices(player, session.choices, highlightIdx = next)
    }

    fun acceptCurrentDiverge(player: org.bukkit.entity.Player) {
        val session = divergeSessions[player.uniqueId] ?: return
        handleDivergeChoice(player, session.currentIndex)
    }

    private fun clearChatWindow(player: org.bukkit.entity.Player, lines: Int = 100) {
        repeat(lines) {
            sendSyntheticMessage(player, Component.empty())
        }
    }

    private fun sendAllowedComponent(player: org.bukkit.entity.Player, component: Component) {
        player.sendMessage(component)
    }

    internal fun handlePlayerBlockEvent(
        player: org.bukkit.entity.Player,
        kind: BlockEventType,
        block: Block,
        action: String? = null,
        item: ItemStack? = null,
        placedByPlayer: Boolean = false,
        spawnerType: String? = null,
        treeType: String? = null
    ) {
        val session = sessions[player.uniqueId] ?: return
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isPlayerBlockNode(node.type)) return

        val typeMatches = when (node.type) {
            QuestObjectNodeType.PLAYER_BLOCKS_BREAK -> kind == BlockEventType.BREAK
            QuestObjectNodeType.PLAYER_BLOCKS_PLACE -> kind == BlockEventType.PLACE
            QuestObjectNodeType.PLAYER_BLOCKS_INTERACT -> kind == BlockEventType.INTERACT
            QuestObjectNodeType.PLAYER_BLOCKS_IGNITE -> kind == BlockEventType.IGNITE
            QuestObjectNodeType.PLAYER_BLOCKS_STRIP -> kind == BlockEventType.STRIP
            QuestObjectNodeType.PLAYER_BLOCK_FARM -> kind == BlockEventType.FARM
            QuestObjectNodeType.PLAYER_BLOCK_FROST_WALK -> kind == BlockEventType.FROST_WALK
            QuestObjectNodeType.PLAYER_MAKE_PATHS -> kind == BlockEventType.MAKE_PATH
            QuestObjectNodeType.PLAYER_SPAWNER_PLACE -> kind == BlockEventType.SPAWNER_PLACE
            QuestObjectNodeType.PLAYER_TREE_GROW -> kind == BlockEventType.TREE_GROW
            else -> true
        }
        if (!typeMatches) return

        val clickOk = when (node.type) {
            QuestObjectNodeType.PLAYER_BLOCKS_INTERACT -> matchesClick(node.blockClickType, action)
            QuestObjectNodeType.PLAYER_BLOCKS_STRIP,
            QuestObjectNodeType.PLAYER_BLOCK_FARM,
            QuestObjectNodeType.PLAYER_MAKE_PATHS -> matchesClick("RIGHT_CLICK", action)
            else -> true
        }
        if (!clickOk) return

        if (!node.blockAllowPlayerBlocks && placedByPlayer) {
            node.blockAllowPlayerBlocksMessage?.let { MessageFormatter.send(player, it) }
            return
        }

        val locKey = block.locationKey()
        val counted = session.blockCounted.getOrPut(node.id) { mutableSetOf() }
        if (!node.blockAllowSameBlocks && counted.contains(locKey)) {
            node.blockAllowSameBlocksMessage?.let { MessageFormatter.send(player, it) }
            return
        }

        val goals = node.blockGoals.ifEmpty {
            listOf(
                BlockGoal(
                    id = "default",
                    types = emptyList(),
                    states = emptyList(),
                    statesRequiredCount = Int.MAX_VALUE,
                    goal = node.count.toDouble().coerceAtLeast(1.0)
                )
            )
        }

        val targetBlock = if (kind == BlockEventType.IGNITE && block.type == Material.AIR && block.y > block.world.minHeight) {
            block.world.getBlockAt(block.x, block.y - 1, block.z)
        } else block
        val blockType = targetBlock.type.name
        val blockData = targetBlock.blockData.asString.lowercase()
        var progressed = false
        goals.forEach { goal ->
            if (goal.types.isNotEmpty()) {
                val matchType = goal.types.any { it.equals(blockType, true) }
                if (!matchType) {
                    DebugLog.log("BLOCK_SKIP type mismatch block=$blockType goalTypes=${goal.types} node=${node.id}")
                    return@forEach
                }
            }
            if (goal.states.isNotEmpty()) {
                val matchedStates = goal.states.count { st -> blockData.contains(st.lowercase()) }
                val required = if (goal.statesRequiredCount == Int.MAX_VALUE) goal.states.size else goal.statesRequiredCount
                if (matchedStates < required) {
                    DebugLog.log("BLOCK_SKIP state mismatch blockData=$blockData goalStates=${goal.states} node=${node.id}")
                    return@forEach
                }
            }
            if (node.type == QuestObjectNodeType.PLAYER_SPAWNER_PLACE && node.blockSpawnTypes.isNotEmpty()) {
                val sp = spawnerType?.uppercase()
                if (sp == null || node.blockSpawnTypes.none { it.equals(sp, true) }) return@forEach
            }
            if (node.type == QuestObjectNodeType.PLAYER_TREE_GROW && node.blockTreeType != null) {
                val tree = normalizeTreeType(treeType)
                if (tree == null || !node.blockTreeType.equals(tree, true)) return@forEach
            }
            if (node.type == QuestObjectNodeType.PLAYER_BLOCK_FARM && item != null) {
                val matName = item.type.name
                if (!matName.endsWith("_HOE")) return@forEach
            }
            if (node.type == QuestObjectNodeType.PLAYER_BLOCKS_STRIP && item != null) {
                val matName = item.type.name
                if (!matName.endsWith("_AXE")) return@forEach
            }
            if (node.type == QuestObjectNodeType.PLAYER_MAKE_PATHS && item != null) {
                val matName = item.type.name
                if (!matName.endsWith("_SHOVEL") && !matName.endsWith("_SPADE")) return@forEach
            }
            if (node.type == QuestObjectNodeType.PLAYER_BLOCK_FROST_WALK && kind != BlockEventType.FROST_WALK) return@forEach
            if (node.type == QuestObjectNodeType.PLAYER_BLOCK_FARM && goal.types.isEmpty()) {
                if (!isFarmable(blockType)) return@forEach
            }
            if (node.type == QuestObjectNodeType.PLAYER_MAKE_PATHS && goal.types.isEmpty()) {
                if (!isPathable(blockType)) return@forEach
            }

            val progMap = session.blockProgress.getOrPut(node.id) { mutableMapOf() }
            val current = progMap.getOrDefault(goal.id, 0.0)
            progMap[goal.id] = current + 1.0
            questService.saveNodeProgress(player, model.id, session.branchId, node.id, progMap[goal.id]!!, goal.id)
            progressed = true
        }

        if (progressed) {
            counted.add(locKey)
        }

        val progMap = session.blockProgress[node.id] ?: return
        val allDone = goals.all { g -> (progMap[g.id] ?: 0.0) >= g.goal }
        if (allDone) {
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            val gotoRaw = node.goto ?: return
            val target = normalizeTarget(gotoRaw)
            runNode(player, model, session.branchId, target, 0)
        }
    }

    private fun matchesClick(expected: String?, action: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        if (action.isNullOrBlank()) return false
        return expected.equals(action, true)
    }

    private fun isPlayerBlockNode(type: QuestObjectNodeType): Boolean =
        when (type) {
            QuestObjectNodeType.PLAYER_BLOCKS_BREAK,
            QuestObjectNodeType.PLAYER_BLOCKS_PLACE,
            QuestObjectNodeType.PLAYER_BLOCKS_INTERACT,
            QuestObjectNodeType.PLAYER_BLOCKS_IGNITE,
            QuestObjectNodeType.PLAYER_BLOCKS_STRIP,
            QuestObjectNodeType.PLAYER_BLOCK_FARM,
            QuestObjectNodeType.PLAYER_BLOCK_FROST_WALK,
            QuestObjectNodeType.PLAYER_MAKE_PATHS,
            QuestObjectNodeType.PLAYER_SPAWNER_PLACE,
            QuestObjectNodeType.PLAYER_TREE_GROW -> true
            else -> false
        }

    private fun isFarmable(blockType: String): Boolean =
        when (blockType.uppercase()) {
            "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "GRASS_BLOCK", "GRASS_PATH", "DIRT_PATH" -> true
            else -> false
        }

    private fun isPathable(blockType: String): Boolean =
        when (blockType.uppercase()) {
            // tylko bloki, które faktycznie mogą zmienić się w ścieżkę po kliknięciu łopatą
            "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "GRASS_BLOCK" -> true
            else -> false
        }

    private fun Block.locationKey(): String =
        "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"

    internal fun handlePlayerEntityEvent(
        player: org.bukkit.entity.Player,
        kind: EntityEventType,
        entity: org.bukkit.entity.Entity?,
        damager: org.bukkit.entity.Entity? = null,
        entityTypeHint: String? = null
    ) {
        val session = sessions[player.uniqueId] ?: return
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isPlayerEntityNode(node.type)) return

        val counted = session.entityCounted.getOrPut(node.id) { mutableSetOf() }
        val targetEntity = when (kind) {
            EntityEventType.GET_DAMAGED -> damager ?: entity
            else -> entity
        }
        val targetType = targetEntity?.type?.name ?: entityTypeHint
        val entityId = targetEntity?.uniqueId
        if (!node.entityAllowSame && entityId != null && counted.contains(entityId)) {
            node.entityAllowSameMessage?.let { MessageFormatter.send(player, it) }
            return
        }

        if (node.type == QuestObjectNodeType.PLAYER_ENTITIES_DEATH_NEARBY) {
            val maxDist = node.entityMaxDistance ?: return
            val distSq = player.location.distanceSquared((entity ?: return).location)
            if (distSq > maxDist * maxDist) return
        }

        val goals = node.goals.ifEmpty {
            listOf(EntityGoal(goal = node.count.toDouble().coerceAtLeast(1.0), id = "default"))
        }

        if (node.type == QuestObjectNodeType.PLAYER_TURTLES_BREED) {
            net.nemoria.quest.core.DebugLog.log(
                "TURTLE_BREED_CHECK player=${player.name} kind=$kind targetType=$targetType goals=${goals.map { it.id + ':' + it.goal }}"
            )
        }

        var progressed = false
        goals.forEach { goal ->
            val goalId = goal.id.ifBlank { "default" }
            if (goal.types.isNotEmpty() || goal.names.isNotEmpty() || goal.colors.isNotEmpty() || goal.horseColors.isNotEmpty() || goal.horseStyles.isNotEmpty()) {
                val match = when {
                    targetEntity != null -> matchEntity(targetEntity, goal)
                    targetType != null -> goal.types.any { it.equals(targetType, true) }
                    else -> false
                }
                if (!match) return@forEach
            }
            when (node.type) {
                QuestObjectNodeType.PLAYER_ENTITIES_KILL -> if (kind != EntityEventType.KILL) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_DAMAGE -> if (kind != EntityEventType.DAMAGE) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_GET_DAMAGED -> if (kind != EntityEventType.GET_DAMAGED) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_INTERACT -> if (kind != EntityEventType.INTERACT) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_CATCH -> if (kind != EntityEventType.CATCH) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_MOUNT -> if (kind != EntityEventType.MOUNT) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_DISMOUNT -> if (kind != EntityEventType.DISMOUNT) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_SHEAR -> if (kind != EntityEventType.SHEAR) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_SPAWN -> if (kind != EntityEventType.SPAWN) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_TAME -> if (kind != EntityEventType.TAME) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_BREED -> if (kind != EntityEventType.BREED) return@forEach
                QuestObjectNodeType.PLAYER_TURTLES_BREED -> if (kind != EntityEventType.TURTLE_BREED) return@forEach
                QuestObjectNodeType.PLAYER_ENTITIES_DEATH_NEARBY -> if (kind != EntityEventType.DEATH_NEARBY) return@forEach
                else -> {}
            }
            val progMap = session.entityProgress.getOrPut(node.id) { mutableMapOf() }
            val current = progMap.getOrDefault(goalId, 0.0)
            val updated = current + 1.0
            progMap[goalId] = updated
            questService.saveNodeProgress(player, model.id, session.branchId, node.id, updated, goalId)
            progressed = true
        }

        if (node.type == QuestObjectNodeType.PLAYER_TURTLES_BREED && !progressed) {
            net.nemoria.quest.core.DebugLog.log(
                "TURTLE_BREED_NO_PROGRESS player=${player.name} kind=$kind targetType=$targetType counted=${counted.size}"
            )
        }

        if (progressed && entityId != null) counted.add(entityId)

        val progMap = session.entityProgress[node.id] ?: return
        val allDone = goals.all { g -> (progMap[g.id.ifBlank { "default" }] ?: 0.0) >= g.goal }
        if (allDone) {
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            val gotoRaw = node.goto ?: return
            val target = normalizeTarget(gotoRaw)
            runNode(player, model, session.branchId, target, 0)
        }
    }

    private fun isPlayerEntityNode(type: QuestObjectNodeType): Boolean =
        when (type) {
            QuestObjectNodeType.PLAYER_ENTITIES_BREED,
            QuestObjectNodeType.PLAYER_ENTITIES_INTERACT,
            QuestObjectNodeType.PLAYER_ENTITIES_CATCH,
            QuestObjectNodeType.PLAYER_ENTITIES_DAMAGE,
            QuestObjectNodeType.PLAYER_ENTITIES_DEATH_NEARBY,
            QuestObjectNodeType.PLAYER_ENTITIES_DISMOUNT,
            QuestObjectNodeType.PLAYER_ENTITIES_GET_DAMAGED,
            QuestObjectNodeType.PLAYER_ENTITIES_KILL,
            QuestObjectNodeType.PLAYER_ENTITIES_MOUNT,
            QuestObjectNodeType.PLAYER_ENTITIES_SHEAR,
            QuestObjectNodeType.PLAYER_ENTITIES_SPAWN,
            QuestObjectNodeType.PLAYER_ENTITIES_TAME,
            QuestObjectNodeType.PLAYER_TURTLES_BREED -> true
            else -> false
        }

    internal fun handleMovementEvent(
        player: org.bukkit.entity.Player,
        kind: MovementEventType,
        delta: Double = 0.0,
        vehicleType: String? = null
    ) {
        val session = sessions[player.uniqueId] ?: return
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isMovementNode(node.type)) return

        val goal = node.distanceGoal ?: node.count.toDouble().coerceAtLeast(1.0)

        val matches = when (node.type) {
            QuestObjectNodeType.PLAYER_MOVE -> kind == MovementEventType.MOVE
            QuestObjectNodeType.PLAYER_MOVE_BY_FOOT_DISTANCE -> kind == MovementEventType.FOOT
            QuestObjectNodeType.PLAYER_WALK_DISTANCE -> kind == MovementEventType.WALK
            QuestObjectNodeType.PLAYER_SPRINT_DISTANCE -> kind == MovementEventType.SPRINT
            QuestObjectNodeType.PLAYER_SWIM_DISTANCE -> kind == MovementEventType.SWIM
            QuestObjectNodeType.PLAYER_ELYTRA_FLY_DISTANCE -> kind == MovementEventType.GLIDE
            QuestObjectNodeType.PLAYER_FALL_DISTANCE -> kind == MovementEventType.FALL
            QuestObjectNodeType.PLAYER_VEHICLE_DISTANCE -> {
                if (vehicleType != null && node.vehicleType != null && !node.vehicleType.equals(vehicleType, true)) return
                kind == MovementEventType.VEHICLE
            }
            QuestObjectNodeType.PLAYER_HORSE_JUMP -> kind == MovementEventType.HORSE_JUMP
            QuestObjectNodeType.PLAYER_JUMP -> kind == MovementEventType.JUMP
            QuestObjectNodeType.PLAYER_ELYTRA_LAND -> kind == MovementEventType.LAND
            QuestObjectNodeType.PLAYER_POSITION -> true
            else -> false
        }
        if (!matches) return

        if (node.type == QuestObjectNodeType.PLAYER_POSITION) {
            val target = node.position ?: return
            val playerLoc = player.location
            val targetWorld = target.world?.let { plugin.server.getWorld(it) } ?: playerLoc.world
            if (target.world != null && targetWorld == null) return
            if (playerLoc.world != targetWorld) return
            val targetLoc = org.bukkit.Location(targetWorld, target.x ?: playerLoc.x, target.y ?: playerLoc.y, target.z ?: playerLoc.z)
            val distSq = playerLoc.distanceSquared(targetLoc)
            val radiusSq = target.radius * target.radius
            val dist = sqrt(distSq)
            DebugLog.log(
                "POS_CHECK player=${player.name} quest=${model.id} branch=${session.branchId} node=${node.id} " +
                    "playerLoc=${playerLoc.world?.name}:${"%.2f".format(playerLoc.x)},${"%.2f".format(playerLoc.y)},${"%.2f".format(playerLoc.z)} " +
                    "targetLoc=${targetLoc.world?.name}:${"%.2f".format(targetLoc.x)},${"%.2f".format(targetLoc.y)},${"%.2f".format(targetLoc.z)} " +
                    "dist=${"%.2f".format(dist)} radius=${"%.2f".format(target.radius)}"
            )
            if (node.positionDisplayDistance) {
                val now = System.currentTimeMillis()
                val last = session.positionActionbarHint[node.id] ?: 0L
                if (now - last >= 1000L) { // co ok. 20 ticków
                    val remaining = (dist - target.radius).coerceAtLeast(0.0)
                    player.sendActionBar(Component.text("Odległość: %.1fm".format(remaining)))
                    session.positionActionbarHint[node.id] = now
                }
            }
            if (distSq <= radiusSq) {
                questService.clearNodeProgress(player, model.id, session.branchId, node.id)
                val gotoRaw = node.goto ?: return
                runNode(player, model, session.branchId, normalizeTarget(gotoRaw), 0)
            }
            return
        }

        val add = if (node.type in listOf(
                QuestObjectNodeType.PLAYER_HORSE_JUMP,
                QuestObjectNodeType.PLAYER_JUMP,
                QuestObjectNodeType.PLAYER_ELYTRA_LAND
            )
        ) 1.0 else delta

        val current = session.movementProgress.getOrDefault(node.id, 0.0)
        session.movementProgress[node.id] = current + add
        questService.saveNodeProgress(player, model.id, session.branchId, node.id, session.movementProgress[node.id]!!)
        if (session.movementProgress[node.id]!! >= goal) {
            val gotoRaw = node.goto ?: return
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            runNode(player, model, session.branchId, normalizeTarget(gotoRaw), 0)
        }
    }

    private fun isMovementNode(type: QuestObjectNodeType): Boolean =
        when (type) {
            QuestObjectNodeType.PLAYER_MOVE,
            QuestObjectNodeType.PLAYER_MOVE_BY_FOOT_DISTANCE,
            QuestObjectNodeType.PLAYER_POSITION,
            QuestObjectNodeType.PLAYER_SWIM_DISTANCE,
            QuestObjectNodeType.PLAYER_ELYTRA_FLY_DISTANCE,
            QuestObjectNodeType.PLAYER_ELYTRA_LAND,
            QuestObjectNodeType.PLAYER_FALL_DISTANCE,
            QuestObjectNodeType.PLAYER_HORSE_JUMP,
            QuestObjectNodeType.PLAYER_JUMP,
            QuestObjectNodeType.PLAYER_SPRINT_DISTANCE,
            QuestObjectNodeType.PLAYER_VEHICLE_DISTANCE,
            QuestObjectNodeType.PLAYER_WALK_DISTANCE -> true
            else -> false
        }

    internal fun handlePhysicalEvent(
        player: org.bukkit.entity.Player,
        kind: PhysicalEventType,
        amount: Double = 1.0,
        detail: String? = null
    ) {
        val session = sessions[player.uniqueId] ?: return
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isPhysicalNode(node.type)) return
        val typeMatches = when (node.type) {
            QuestObjectNodeType.PLAYER_BED_ENTER -> kind == PhysicalEventType.BED_ENTER
            QuestObjectNodeType.PLAYER_BED_LEAVE -> kind == PhysicalEventType.BED_LEAVE
            QuestObjectNodeType.PLAYER_BUCKET_FILL -> kind == PhysicalEventType.BUCKET_FILL
            QuestObjectNodeType.PLAYER_BURN -> kind == PhysicalEventType.BURN
            QuestObjectNodeType.PLAYER_DIE -> kind == PhysicalEventType.DIE
            QuestObjectNodeType.PLAYER_GAIN_HEALTH -> kind == PhysicalEventType.GAIN_HEALTH
            QuestObjectNodeType.PLAYER_GAIN_XP -> kind == PhysicalEventType.GAIN_XP
            QuestObjectNodeType.PLAYER_PORTAL_ENTER -> kind == PhysicalEventType.PORTAL_ENTER
            QuestObjectNodeType.PLAYER_PORTAL_LEAVE -> kind == PhysicalEventType.PORTAL_LEAVE
            QuestObjectNodeType.PLAYER_SHOOT_PROJECTILE -> kind == PhysicalEventType.SHOOT_PROJECTILE
            QuestObjectNodeType.PLAYER_SNEAK -> kind == PhysicalEventType.SNEAK_TIME
            QuestObjectNodeType.PLAYER_TAKE_DAMAGE -> kind == PhysicalEventType.TAKE_DAMAGE
            QuestObjectNodeType.PLAYER_TOGGLE_SNEAK -> kind == PhysicalEventType.TOGGLE_SNEAK
            QuestObjectNodeType.PLAYER_VEHICLE_ENTER -> kind == PhysicalEventType.VEHICLE_ENTER
            QuestObjectNodeType.PLAYER_VEHICLE_LEAVE -> kind == PhysicalEventType.VEHICLE_LEAVE
            else -> true
        }
        if (!typeMatches && (node.type == QuestObjectNodeType.PLAYER_BED_ENTER || node.type == QuestObjectNodeType.PLAYER_BED_LEAVE)) {
            DebugLog.log(
                "PHYS_BED_SKIP player=${player.name} quest=${model.id} branch=${session.branchId} node=${node.id} type=${node.type} kind=$kind detail=$detail"
            )
            return
        }
        if (!typeMatches) {
            if (node.type == QuestObjectNodeType.PLAYER_VEHICLE_ENTER || node.type == QuestObjectNodeType.PLAYER_VEHICLE_LEAVE) {
                DebugLog.log(
                    "PHYS_VEHICLE_SKIP player=${player.name} quest=${model.id} branch=${session.branchId} node=${node.id} type=${node.type} kind=$kind detail=$detail"
                )
            }
            return
        }

        if (node.type == QuestObjectNodeType.PLAYER_BUCKET_FILL && node.bucketType != null) {
            if (detail == null || !node.bucketType.equals(detail, true)) return
        }
        if (node.type == QuestObjectNodeType.PLAYER_SHOOT_PROJECTILE && node.projectileTypes.isNotEmpty()) {
            if (detail == null || node.projectileTypes.none { it.equals(detail, true) }) return
        }
        val vehicleDetail = if (detail != null && (node.type == QuestObjectNodeType.PLAYER_VEHICLE_ENTER || node.type == QuestObjectNodeType.PLAYER_VEHICLE_LEAVE)) {
            val upper = detail.uppercase()
            if (node.vehicleType?.equals("BOAT", true) == true && upper.endsWith("BOAT")) "BOAT" else upper
        } else detail

        if ((node.type == QuestObjectNodeType.PLAYER_VEHICLE_ENTER || node.type == QuestObjectNodeType.PLAYER_VEHICLE_LEAVE) && node.vehicleType != null) {
            if (vehicleDetail == null || !node.vehicleType.equals(vehicleDetail, true)) return
        }
        if (node.type == QuestObjectNodeType.PLAYER_GAIN_HEALTH && node.regainCauses.isNotEmpty()) {
            if (detail == null || node.regainCauses.none { it.equals(detail, true) }) return
        }
        if (node.type == QuestObjectNodeType.PLAYER_TAKE_DAMAGE && node.damageCauses.isNotEmpty()) {
            if (detail == null || node.damageCauses.none { it.equals(detail, true) }) return
        }

        if (node.type == QuestObjectNodeType.PLAYER_BED_ENTER || node.type == QuestObjectNodeType.PLAYER_BED_LEAVE) {
            DebugLog.log(
                "PHYS_BED player=${player.name} quest=${model.id} branch=${session.branchId} node=${node.id} type=${node.type} kind=$kind detail=$detail"
            )
        }
        if (node.type == QuestObjectNodeType.PLAYER_GAIN_HEALTH) {
            DebugLog.log(
                "PHYS_GAIN_HEALTH player=${player.name} quest=${model.id} branch=${session.branchId} node=${node.id} kind=$kind add=$amount cause=$detail goal=${node.distanceGoal ?: node.count} prev=${session.physicalProgress.getOrDefault(node.id, 0.0)}"
            )
        }
        if (node.type == QuestObjectNodeType.PLAYER_VEHICLE_ENTER || node.type == QuestObjectNodeType.PLAYER_VEHICLE_LEAVE) {
            DebugLog.log(
                "PHYS_VEHICLE player=${player.name} quest=${model.id} branch=${session.branchId} node=${node.id} type=${node.type} kind=$kind detail=$vehicleDetail goal=${node.distanceGoal ?: node.count} prev=${session.physicalProgress.getOrDefault(node.id, 0.0)}"
            )
        }
        if (node.type == QuestObjectNodeType.PLAYER_SHOOT_PROJECTILE) {
            DebugLog.log(
                "PHYS_PROJECTILE player=${player.name} quest=${model.id} branch=${session.branchId} node=${node.id} kind=$kind add=$amount detail=$detail goal=${node.distanceGoal ?: node.count} prev=${session.physicalProgress.getOrDefault(node.id, 0.0)}"
            )
        }
        if (node.type == QuestObjectNodeType.PLAYER_BURN) {
            DebugLog.log(
                "PHYS_BURN player=${player.name} quest=${model.id} branch=${session.branchId} node=${node.id} kind=$kind add=$amount goal=${node.distanceGoal ?: node.count} prev=${session.physicalProgress.getOrDefault(node.id, 0.0)}"
            )
        }

        val goal = node.distanceGoal ?: node.count.toDouble().coerceAtLeast(1.0)
        val add = when (node.type) {
            QuestObjectNodeType.PLAYER_GAIN_HEALTH,
            QuestObjectNodeType.PLAYER_GAIN_XP,
            QuestObjectNodeType.PLAYER_TAKE_DAMAGE -> amount
            QuestObjectNodeType.PLAYER_SNEAK,
            QuestObjectNodeType.PLAYER_BURN -> amount
            else -> 1.0
        }
        val current = session.physicalProgress.getOrDefault(node.id, 0.0)
        session.physicalProgress[node.id] = current + add
        questService.saveNodeProgress(player, model.id, session.branchId, node.id, session.physicalProgress[node.id]!!)
        val targetReached = session.physicalProgress[node.id]!! >= goal
        if (targetReached) {
            val gotoRaw = node.goto ?: return
            val target = normalizeTarget(gotoRaw)
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            runNode(player, model, session.branchId, target, 0)
        }
    }

    private fun isPhysicalNode(type: QuestObjectNodeType): Boolean =
        when (type) {
            QuestObjectNodeType.PLAYER_BED_ENTER,
            QuestObjectNodeType.PLAYER_BED_LEAVE,
            QuestObjectNodeType.PLAYER_BUCKET_FILL,
            QuestObjectNodeType.PLAYER_BURN,
            QuestObjectNodeType.PLAYER_DIE,
            QuestObjectNodeType.PLAYER_GAIN_HEALTH,
            QuestObjectNodeType.PLAYER_GAIN_XP,
            QuestObjectNodeType.PLAYER_PORTAL_ENTER,
            QuestObjectNodeType.PLAYER_PORTAL_LEAVE,
            QuestObjectNodeType.PLAYER_SHOOT_PROJECTILE,
            QuestObjectNodeType.PLAYER_SNEAK,
            QuestObjectNodeType.PLAYER_TAKE_DAMAGE,
            QuestObjectNodeType.PLAYER_TOGGLE_SNEAK,
            QuestObjectNodeType.PLAYER_VEHICLE_ENTER,
            QuestObjectNodeType.PLAYER_VEHICLE_LEAVE -> true
            else -> false
        }

    internal fun handleMiscEvent(
        player: org.bukkit.entity.Player,
        kind: MiscEventType,
        detail: String? = null
    ): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        val model = questService.questInfo(session.questId) ?: return false
        val branch = model.branches[session.branchId] ?: return false
        val node = branch.objects[session.nodeId] ?: return false
        if (!isMiscNode(node.type)) return false

        when (node.type) {
            QuestObjectNodeType.PLAYER_CHAT -> {
                val text = detail ?: return false
                if (!chatAllowed(node, text)) {
                    node.chatErrorMessage?.let { MessageFormatter.send(player, it) }
                    return true // cancel chat
                }
                if (!node.chatStoreVariable.isNullOrBlank()) {
                    questService.updateVariable(player, model.id, node.chatStoreVariable, text)
                }
                incrementMisc(node, session, player, model, 1.0)
                MessageFormatter.send(player, "<primary> [${player.name}]<secondary> $text")
                return true // blokuj publiczny czat
            }
            QuestObjectNodeType.PLAYER_ACHIEVEMENT_AWARD -> {
                if (kind != MiscEventType.ACHIEVEMENT) return false
                val ach = detail
                if (node.achievementType != null && ach != null && !node.achievementType.equals(ach, true)) return false
                incrementMisc(node, session, player, model, 1.0)
                return false
            }
            QuestObjectNodeType.PLAYER_CONNECT,
            QuestObjectNodeType.PLAYER_DISCONNECT,
            QuestObjectNodeType.PLAYER_RESPAWN -> {
                val expected = when (node.type) {
                    QuestObjectNodeType.PLAYER_CONNECT -> MiscEventType.CONNECT
                    QuestObjectNodeType.PLAYER_DISCONNECT -> MiscEventType.DISCONNECT
                    QuestObjectNodeType.PLAYER_RESPAWN -> MiscEventType.RESPAWN
                    else -> kind
                }
                if (kind != expected) return false
                incrementMisc(node, session, player, model, 1.0)
                return false
            }
            else -> return false
        }
    }

    private fun incrementMisc(node: QuestObjectNode, session: BranchSession, player: org.bukkit.entity.Player, model: QuestModel, delta: Double) {
        val goal = node.distanceGoal ?: node.count.toDouble().coerceAtLeast(1.0)
        val current = session.miscProgress.getOrDefault(node.id, 0.0)
        session.miscProgress[node.id] = current + delta
        questService.saveNodeProgress(player, model.id, session.branchId, node.id, session.miscProgress[node.id]!!)
        if (session.miscProgress[node.id]!! >= goal) {
            val gotoRaw = node.goto ?: return
            val target = normalizeTarget(gotoRaw)
            // Ustaw stan następnego węzła nawet jeśli gracz właśnie się rozłącza (np. DISCONNECT)
            session.nodeId = target
            questService.updateBranchState(player, model.id, session.branchId, target)
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            if (player.isOnline) {
                runNode(player, model, session.branchId, target, 0)
            } else {
                // gracz offline: uruchom kolejny węzeł po powrocie na serwer
                val offline = plugin.server.getOfflinePlayer(player.uniqueId)
                val task = object : BukkitRunnable() {
                    override fun run() {
                        val online = offline.player
                        if (online != null && online.isOnline) {
                            runNode(offline, model, session.branchId, target, 0)
                            this.cancel()
                        }
                    }
                }.runTaskTimer(plugin, 20L, 20L)
                session.waitTasks[target] = task
            }
        }
    }

    private fun chatAllowed(node: QuestObjectNode, text: String): Boolean {
        if (node.chatMinLength != null && text.length < node.chatMinLength) return false
        if (node.chatMaxLength != null && text.length > node.chatMaxLength) return false
        if (node.chatWhitelist.isNotEmpty() && node.chatWhitelist.none { it.equals(text, true) }) return false
        if (node.chatBlacklist.isNotEmpty() && node.chatBlacklist.any { it.equals(text, true) }) return false
        if (!node.chatRegex.isNullOrBlank() && !text.matches(node.chatRegex.toRegex())) return false
        return true
    }

    private fun isMiscNode(type: QuestObjectNodeType): Boolean =
        when (type) {
            QuestObjectNodeType.PLAYER_ACHIEVEMENT_AWARD,
            QuestObjectNodeType.PLAYER_CHAT,
            QuestObjectNodeType.PLAYER_CONNECT,
            QuestObjectNodeType.PLAYER_DISCONNECT,
            QuestObjectNodeType.PLAYER_RESPAWN -> true
            else -> false
        }

    internal fun handlePlayerItemEvent(
        player: org.bukkit.entity.Player,
        kind: ItemEventType,
        item: ItemStack?,
        inventoryType: String? = null,
        slot: Int? = null,
        villagerId: UUID? = null
    ) {
        val session = sessions[player.uniqueId] ?: return
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isPlayerItemNode(node.type)) return

        val typeMatches = when (node.type) {
            QuestObjectNodeType.PLAYER_ITEMS_ACQUIRE -> kind == ItemEventType.ACQUIRE
            QuestObjectNodeType.PLAYER_ITEMS_BREW -> kind == ItemEventType.BREW
            QuestObjectNodeType.PLAYER_ITEMS_CONSUME -> kind == ItemEventType.CONSUME
            QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_PUT -> kind == ItemEventType.CONTAINER_PUT
            QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_TAKE -> kind == ItemEventType.CONTAINER_TAKE
            QuestObjectNodeType.PLAYER_ITEMS_CRAFT -> kind == ItemEventType.CRAFT
            QuestObjectNodeType.PLAYER_ITEMS_DROP -> kind == ItemEventType.DROP
            QuestObjectNodeType.PLAYER_ITEMS_ENCHANT -> kind == ItemEventType.ENCHANT
            QuestObjectNodeType.PLAYER_ITEMS_FISH -> kind == ItemEventType.FISH
            QuestObjectNodeType.PLAYER_ITEMS_INTERACT -> kind == ItemEventType.INTERACT
            QuestObjectNodeType.PLAYER_ITEMS_MELT -> kind == ItemEventType.MELT
            QuestObjectNodeType.PLAYER_ITEMS_PICKUP -> kind == ItemEventType.PICKUP
            QuestObjectNodeType.PLAYER_ITEMS_REPAIR -> kind == ItemEventType.REPAIR
            QuestObjectNodeType.PLAYER_ITEMS_REQUIRE -> kind == ItemEventType.ACQUIRE || kind == ItemEventType.PICKUP
            QuestObjectNodeType.PLAYER_ITEMS_THROW -> kind == ItemEventType.THROW
            QuestObjectNodeType.PLAYER_ITEMS_TRADE -> kind == ItemEventType.TRADE
            else -> false
        }
        if (!typeMatches) return

        if (!node.tradeAllowSameVillagers && villagerId != null) {
            val seen = session.villagerCounted.getOrPut(node.id) { mutableSetOf() }
            if (seen.contains(villagerId)) {
                node.tradeAllowSameVillagersMessage?.let { MessageFormatter.send(player, it) }
                return
            }
            seen.add(villagerId)
        }

        if ((kind == ItemEventType.BREW || kind == ItemEventType.MELT) && DebugLog.enabled) {
            val meta = item?.itemMeta as? org.bukkit.inventory.meta.PotionMeta
            val goalsDesc = node.itemGoals.joinToString { g ->
                g.items.joinToString { it.type + ":" + (it.potionType ?: "") }
            }
            DebugLog.log("ITEM_EVT kind=$kind player=${player.name} item=${item?.type} base=${meta?.basePotionType?.name} goals=$goalsDesc")
        }

        if (node.itemInventoryTypes.isNotEmpty() && inventoryType != null && node.itemInventoryTypes.none { it.equals(inventoryType, true) }) {
            return
        }
        if (node.itemInventorySlots.isNotEmpty() && slot != null && !node.itemInventorySlots.contains(slot)) {
            return
        }
        if (node.type == QuestObjectNodeType.PLAYER_ITEMS_INTERACT && node.itemClickType != null) {
            val click = inventoryType ?: ""
            if (!node.itemClickType.equals(click, true)) return
        }

        val goals = node.itemGoals.ifEmpty {
            listOf(ItemGoal(id = "default", items = emptyList(), goal = node.count.toDouble().coerceAtLeast(1.0)))
        }

        val matchedItem = item
        var progressed = false
        goals.forEach { goal ->
            val matchOk = when {
                goal.items.isEmpty() -> matchedItem != null
                matchedItem != null -> goal.items.any { matchesItem(it, matchedItem) }
                else -> false
            }
            if (!matchOk) {
                if ((kind == ItemEventType.BREW || kind == ItemEventType.MELT) && DebugLog.enabled) {
                    val goalDesc = goal.items.joinToString { it.type + ":" + (it.potionType ?: "") }
                    DebugLog.log("ITEM_SKIP kind=$kind player=${player.name} goal=${goal.id} items=$goalDesc stack=${matchedItem?.type}")
                }
                return@forEach
            }

            when (node.type) {
                QuestObjectNodeType.PLAYER_ITEMS_REQUIRE -> {
                    // handled below by inventory check
                }
                else -> {
                    val progMap = session.itemProgress.getOrPut(node.id) { mutableMapOf() }
                    val cur = progMap.getOrDefault(goal.id, 0.0)
                    progMap[goal.id] = cur + 1.0
                    questService.saveNodeProgress(player, model.id, session.branchId, node.id, progMap[goal.id]!!, goal.id)
                    progressed = true
                    if ((kind == ItemEventType.BREW || kind == ItemEventType.MELT) && DebugLog.enabled) {
                        DebugLog.log("ITEM_PROG kind=$kind player=${player.name} goal=${goal.id} new=${progMap[goal.id]} need=${goal.goal}")
                    }
                    if (goal.take && matchedItem != null) {
                        removeOne(player, matchedItem)
                    }
                }
            }
        }

        if (node.type == QuestObjectNodeType.PLAYER_ITEMS_REQUIRE) {
            if (checkRequireSatisfied(player, goals, node.itemsRequired)) {
                node.goto?.let { handleGoto(player, model, session.branchId, it, 0) }
            }
            return
        }

        if (!progressed) return
        val progMap = session.itemProgress[node.id] ?: return
        val allDone = goals.all { g -> (progMap[g.id] ?: 0.0) >= g.goal }
        if (allDone) {
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            val gotoRaw = node.goto ?: return
            val target = normalizeTarget(gotoRaw)
            runNode(player, model, session.branchId, target, 0)
        }
    }

    private fun isPlayerItemNode(type: QuestObjectNodeType): Boolean =
        when (type) {
            QuestObjectNodeType.PLAYER_ITEMS_ACQUIRE,
            QuestObjectNodeType.PLAYER_ITEMS_BREW,
            QuestObjectNodeType.PLAYER_ITEMS_CONSUME,
            QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_PUT,
            QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_TAKE,
            QuestObjectNodeType.PLAYER_ITEMS_CRAFT,
            QuestObjectNodeType.PLAYER_ITEMS_DROP,
            QuestObjectNodeType.PLAYER_ITEMS_ENCHANT,
            QuestObjectNodeType.PLAYER_ITEMS_FISH,
            QuestObjectNodeType.PLAYER_ITEMS_INTERACT,
            QuestObjectNodeType.PLAYER_ITEMS_MELT,
            QuestObjectNodeType.PLAYER_ITEMS_PICKUP,
            QuestObjectNodeType.PLAYER_ITEMS_REPAIR,
            QuestObjectNodeType.PLAYER_ITEMS_REQUIRE,
            QuestObjectNodeType.PLAYER_ITEMS_THROW,
            QuestObjectNodeType.PLAYER_ITEMS_TRADE -> true
            else -> false
        }

    private fun matchesItem(goal: ItemStackConfig, stack: ItemStack): Boolean {
        if (!goal.type.equals(stack.type.name, true)) return false
        if (goal.potionType != null) {
            val meta = stack.itemMeta as? org.bukkit.inventory.meta.PotionMeta ?: return false
            val base = meta.basePotionType
            val baseName = base?.name
            val baseKey = base?.key?.key
            if (!(goal.potionType.equals(baseName, true) || goal.potionType.equals(baseKey, true))) {
                return false
            }
        }
        // shallow checks only; ignore name/lore/customModelData for now
        return true
    }

    private fun removeOne(player: org.bukkit.entity.Player, sample: ItemStack) {
        val inv = player.inventory
        for (i in 0 until inv.size) {
            val it = inv.getItem(i) ?: continue
            if (it.type == sample.type) {
                if (it.amount > 1) {
                    it.amount = it.amount - 1
                } else {
                    inv.clear(i)
                }
                return
            }
        }
    }

    private fun checkRequireSatisfied(player: org.bukkit.entity.Player, goals: List<ItemGoal>, itemsReq: List<ItemStackConfig>): Boolean {
        val inv = player.inventory
        val stacks = inv.contents.filterNotNull()
        if (itemsReq.isNotEmpty()) {
            val ok = itemsReq.all { cfg ->
                stacks.any { it.type.name.equals(cfg.type, true) && it.amount >= 1 }
            }
            DebugLog.log("REQUIRE_CHECK itemsReq=${itemsReq.map { it.type }} ok=$ok")
            return ok
        }
        return goals.all { g ->
            val needed = g.goal.toInt()
            val matchedCount = if (g.items.isEmpty()) stacks.sumOf { it.amount } else {
                stacks.filter { st -> g.items.any { it.type.equals(st.type.name, true) } }.sumOf { it.amount }
            }
            DebugLog.log("REQUIRE_CHECK goal=${g.id} needed=$needed matched=$matchedCount items=${g.items.map { it.type }}")
            matchedCount >= needed
        }
    }

    private fun sendSyntheticMessage(player: org.bukkit.entity.Player, component: Component, trackSynthetic: Boolean = false) {
        ChatHideService.allowNext(player.uniqueId)
        ChatHistoryManager.skipNextMessages(player.uniqueId)
        if (trackSynthetic) {
            divergeSessions[player.uniqueId]?.syntheticMessages?.add(gson.serialize(component))
        }
        player.sendMessage(component)
    }

    private fun replayHistory(
        player: org.bukkit.entity.Player,
        history: List<Component>,
        trackSynthetic: Boolean = false
    ) {
        history.forEach { comp -> sendSyntheticMessage(player, comp, trackSynthetic) }
    }

    private fun pickEntries(entries: List<QuestItemEntry>, count: Int): List<QuestItemEntry> {
        if (entries.isEmpty()) return emptyList()
        if (count <= 0) return emptyList()
        return if (count >= entries.size) entries
        else entries.shuffled().take(count)
    }

    private fun buildItem(entry: QuestItemEntry): ItemStack? {
        val mat = runCatching { org.bukkit.Material.valueOf(entry.type.uppercase()) }.getOrNull() ?: return null
        return ItemStack(mat, entry.amount)
    }

    private fun giveOrDropItems(player: org.bukkit.entity.Player, node: QuestObjectNode, drop: Boolean) {
        val selection = pickEntries(node.items, node.count)
        selection.forEach { entry ->
            val stack = buildItem(entry) ?: return@forEach
            if (drop) {
                player.world.dropItemNaturally(player.location, stack)
            } else {
                player.inventory.addItem(stack)
            }
        }
    }

    private fun evalFormula(formula: String?, current: Double = 0.0): Double {
        if (formula.isNullOrBlank()) return current
        val prepared = formula.replace("{value}", current.toString())
        var total = 0.0
        val parts = prepared.split("+")
        for (part in parts) {
            val num = part.trim().toDoubleOrNull() ?: continue
            total += num
        }
        return total
    }

    private fun resolvePoints(player: OfflinePlayer, category: String?): Double {
        val catKey = category ?: "global"
        val data = questService.progress(player)[sessions[player.uniqueId]?.questId ?: ""]?.variables ?: emptyMap()
        return data["points:$catKey"]?.toDoubleOrNull() ?: 0.0
    }

    private fun updatePoints(player: OfflinePlayer, category: String?, value: Double) {
        val catKey = category ?: "global"
        questService.updateVariable(player, sessions[player.uniqueId]?.questId ?: return, "points:$catKey", value.toLong().toString())
    }

    private fun takeItems(player: org.bukkit.entity.Player, node: QuestObjectNode): Int {
        val selection = pickEntries(node.items, node.count)
        var taken = 0
        selection.forEach { entry ->
            val mat = runCatching { org.bukkit.Material.valueOf(entry.type.uppercase()) }.getOrNull() ?: return@forEach
            val required = entry.amount
            val inv = player.inventory
            val has = inv.contents.filterNotNull().filter { it.type == mat }.sumOf { it.amount }
            if (has < required) {
                DebugLog.log("ItemsTake quest=${node.id} missing material=$mat has=$has need=$required")
                return@forEach
            }
            val removed = inv.removeItemAnySlot(ItemStack(mat, required))
            val leftover = removed.values.sumOf { it.amount }
            taken += (required - leftover)
        }
        return taken
    }

    private fun modifyItems(player: org.bukkit.entity.Player, node: QuestObjectNode): Int {
        val opts = node.modifyOptions ?: return 0
        val selection = pickEntries(node.items, node.count)
        var modified = 0
        val enchAdd = opts.enchantmentsAdd.mapNotNull { (name, level) ->
            val ench = runCatching { org.bukkit.enchantments.Enchantment.getByName(name.uppercase()) }.getOrNull()
            ench?.let { it to level }
        }
        val enchRemove = opts.enchantmentsRemove.mapNotNull { name ->
            runCatching { org.bukkit.enchantments.Enchantment.getByName(name.uppercase()) }.getOrNull()
        }
        selection.forEach { entry ->
            val mat = runCatching { org.bukkit.Material.valueOf(entry.type.uppercase()) }.getOrNull() ?: return@forEach
            val contents = player.inventory.contents
            for (idx in contents.indices) {
                val item = contents[idx] ?: continue
                if (item.type != mat) continue
                val meta = item.itemMeta ?: continue
                opts.customModelDataSet?.let { meta.setCustomModelData(it) }
                opts.durabilitySet?.let { dur ->
                    item.durability = dur.toShort()
                }
                opts.nameSet?.let { nm -> meta.setDisplayName(MessageFormatter.format(nm)) }
                opts.nameRemove?.let { rm ->
                    val current = meta.displayName ?: ""
                    if (current.contains(rm)) meta.setDisplayName(current.replace(rm, ""))
                }
                if (opts.loreSet.isNotEmpty()) {
                    meta.lore = opts.loreSet.map { MessageFormatter.format(it) }
                }
                if (opts.loreAdd.isNotEmpty()) {
                    val lore = (meta.lore ?: mutableListOf()).toMutableList()
                    lore.addAll(opts.loreAdd.map { MessageFormatter.format(it) })
                    meta.lore = lore
                }
                if (opts.loreRemove.isNotEmpty()) {
                    val lore = (meta.lore ?: mutableListOf()).filter { line ->
                        opts.loreRemove.none { rem -> line.contains(rem) }
                    }
                    meta.lore = lore
                }
                enchAdd.forEach { (ench, lvl) -> meta.addEnchant(ench, lvl, true) }
                enchRemove.forEach { ench -> meta.removeEnchant(ench) }
                item.itemMeta = meta
                if (opts.questUnlink) {
                    meta.persistentDataContainer.remove(questLinkKey())
                }
                contents[idx] = item
                modified++
                if (modified >= node.count) break
            }
        }
        player.inventory.contents = player.inventory.contents
        return modified
    }

    private fun placeBlocks(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        val mat = node.blockType?.let { runCatching { org.bukkit.Material.valueOf(it.uppercase()) }.getOrNull() } ?: return
        val target = resolvePosition(player, node.position)
        val block = target.block
        block.type = mat
        // block_states ignored for now
    }

    private fun createExplosions(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        val center = resolvePosition(player, node.position)
        val power = (node.explosionPower ?: 2.0).toFloat()
        val cnt = node.countOverride ?: 1
        repeat(cnt) {
            center.world.createExplosion(center, power, node.allowDamage, node.allowDamage, player)
        }
    }

    private fun launchFireworks(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        val center = resolvePosition(player, node.position)
        val cnt = node.countOverride ?: 1
        repeat(cnt) {
            val fw = center.world.spawn(center, org.bukkit.entity.Firework::class.java)
            val meta = fw.fireworkMeta
            if (node.effectList.isNotEmpty()) {
                node.effectList.forEach { eff ->
                    val parts = eff.split("\\s+".toRegex())
                    val type = runCatching { org.bukkit.FireworkEffect.Type.valueOf(parts.getOrNull(0)?.uppercase() ?: "BALL") }.getOrDefault(org.bukkit.FireworkEffect.Type.BALL)
                    val builder = org.bukkit.FireworkEffect.builder().with(type)
                    builder.withColor(org.bukkit.Color.WHITE)
                    meta.addEffect(builder.build())
                }
            } else {
                meta.addEffect(org.bukkit.FireworkEffect.builder().with(org.bukkit.FireworkEffect.Type.BALL).withColor(org.bukkit.Color.WHITE).build())
            }
            meta.power = 1
            fw.fireworkMeta = meta
        }
    }

    private fun lightningStrikes(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        val center = resolvePosition(player, node.position)
        val cnt = node.countOverride ?: 1
        repeat(cnt) {
            if (node.allowDamage) center.world.strikeLightning(center)
            else center.world.strikeLightningEffect(center)
        }
    }

    private fun awardAchievement(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        val advId = node.achievementType ?: return
        val key = NamespacedKey.fromString(advId) ?: return
        val adv = plugin.server.advancementIterator().asSequence().firstOrNull { it.key() == key }
        if (adv == null) {
            DebugLog.log("Advancement not found id=$advId")
            return
        }
        val progress = player.getAdvancementProgress(adv)
        progress.remainingCriteria.forEach { crit -> progress.awardCriteria(crit) }
    }

    private fun toggleCameraMode(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        val toggle = node.cameraToggle ?: true
        val key = NamespacedKey(plugin, "prev_gamemode")
        if (toggle) {
            if (!player.persistentDataContainer.has(key, PersistentDataType.STRING)) {
                player.persistentDataContainer.set(key, PersistentDataType.STRING, player.gameMode.name)
            }
            player.gameMode = GameMode.SPECTATOR
        } else {
            val prev = player.persistentDataContainer.get(key, PersistentDataType.STRING)
            val gm = prev?.let { runCatching { GameMode.valueOf(it) }.getOrNull() } ?: GameMode.SURVIVAL
            player.gameMode = gm
            player.persistentDataContainer.remove(key)
        }
    }

    private fun openDivergeGui(player: org.bukkit.entity.Player, model: QuestModel, branchId: String, node: QuestObjectNode) {
        val choices = node.divergeChoices
        if (choices.isEmpty()) {
            node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            return
        }
        val maxSlot = choices.maxOf { it.slot }.coerceAtLeast(8)
        val size = (((maxSlot / 9) + 1) * 9).coerceAtMost(54)
        val inv = plugin.server.createInventory(null, size, MessageFormatter.format("Wybierz opcję"))
        val counts = questService.progress(player)?.get(model.id)?.divergeCounts ?: emptyMap()
        choices.forEach { ch ->
            val key = "${node.id}:${ch.id}".lowercase()
            val used = counts[key] ?: 0
            val available = used < ch.maxCompletions && questService.checkConditions(player, ch.conditions, model.id)
            val itemCfg = when {
                !available && ch.unavailableItem != null -> ch.unavailableItem
                used > 0 && ch.redoItem != null -> ch.redoItem
                else -> ch.item
            } ?: ItemStackConfig("BARRIER", "&cNiedostępne", emptyList(), null)
            buildItem(itemCfg)?.let { inv.setItem(ch.slot, it) }
        }
        guiSessions[player.uniqueId] = DivergeGuiSession(model.id, branchId, node.id, choices, node.divergeReopenDelayTicks)
        player.openInventory(inv)
    }

    internal fun handleDivergeGuiClick(player: org.bukkit.entity.Player, inv: org.bukkit.inventory.Inventory, rawSlot: Int): Boolean {
        val sess = guiSessions[player.uniqueId] ?: return false
        if (rawSlot < 0 || rawSlot >= inv.size) return false
        val choice = sess.choices.firstOrNull { it.slot == rawSlot } ?: return false
        val model = questService.questInfo(sess.questId) ?: return false
        val key = "${sess.nodeId}:${choice.id}".lowercase()
        val used = questService.progress(player)[sess.questId]?.divergeCounts?.get(key) ?: 0
        val available = used < choice.maxCompletions && questService.checkConditions(player, choice.conditions, sess.questId)
        if (!available) return true
        sess.selected = true
        questService.mutateProgress(player, sess.questId) { prog ->
            prog.divergeCounts[key] = used + 1
        }
        player.closeInventory()
        val goto = choice.goto ?: model.branches[sess.branchId]?.objects?.get(choice.objRef ?: "")?.goto
        if (goto != null) {
            handleGoto(player, model, sess.branchId, goto, 0, sess.nodeId)
        }
        guiSessions.remove(player.uniqueId)
        return true
    }

    internal fun handleDivergeGuiClose(player: org.bukkit.entity.Player, inv: org.bukkit.inventory.Inventory) {
        val sess = guiSessions[player.uniqueId] ?: return
        if (sess.selected) {
            guiSessions.remove(player.uniqueId)
            return
        }
        val delay = sess.reopenDelay ?: return
        object : BukkitRunnable() {
            override fun run() {
                val model = questService.questInfo(sess.questId) ?: return
                openDivergeGui(player, model, sess.branchId, model.branches[sess.branchId]?.objects?.get(sess.nodeId) ?: return)
            }
        }.runTaskLater(plugin, delay)
    }

    private fun startDivergeObjects(player: OfflinePlayer, model: QuestModel, branchId: String, node: QuestObjectNode) {
        val p = player.player ?: return
        val choices = node.divergeChoices.filter { ch ->
            questService.checkConditions(p, ch.conditions, model.id) &&
                (questService.progress(player)[model.id]?.divergeCounts?.get("${node.id}:${ch.id}".lowercase()) ?: 0) < ch.maxCompletions
        }
        val first = choices.firstOrNull() ?: run {
            node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            return
        }
        val session = sessions[p.uniqueId] ?: BranchSession(model.id, branchId, node.id).also { sessions[p.uniqueId] = it }
        session.divergeObjectMap.clear()
        choices.forEach { ch ->
            ch.objRef?.let { objId -> session.divergeObjectMap[objId] = ch }
        }
        val targetObj = first.objRef ?: return
        runNode(player, model, branchId, targetObj, 0)
    }

    private fun buildItem(cfg: ItemStackConfig): ItemStack? {
        val mat = runCatching { org.bukkit.Material.valueOf(cfg.type.uppercase()) }.getOrNull() ?: return null
        val stack = ItemStack(mat)
        val meta = stack.itemMeta ?: return null
        cfg.name?.let { meta.setDisplayName(MessageFormatter.format(it)) }
        if (cfg.lore.isNotEmpty()) meta.lore = cfg.lore.map { MessageFormatter.format(it) }
        cfg.customModelData?.let { meta.setCustomModelData(it) }
        stack.itemMeta = meta
        return stack
    }

    private fun giveEffects(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        val effects = if (node.effectList.isNotEmpty()) node.effectList else listOf()
        val selection = if (node.countOverride != null && node.countOverride!! < effects.size) effects.shuffled().take(node.countOverride!!) else effects
        selection.forEach { eff ->
            val parts = eff.split("\\s+".toRegex())
            val type = parts.getOrNull(0)?.let { PotionEffectType.getByName(it.uppercase()) } ?: return@forEach
            val amp = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val ticks = parts.getOrNull(2)?.toIntOrNull() ?: 200
            player.addPotionEffect(PotionEffect(type, ticks, amp))
        }
    }

    private fun removeEffects(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        val effects = if (node.effectList.isNotEmpty()) node.effectList else listOf()
        val selection = if (node.countOverride != null && node.countOverride!! < effects.size) effects.shuffled().take(node.countOverride!!) else effects
        selection.forEach { eff ->
            val type = PotionEffectType.getByName(eff.uppercase()) ?: return@forEach
            player.removePotionEffect(type)
        }
    }

    private fun resolvePosition(player: org.bukkit.entity.Player, target: PositionTarget?): org.bukkit.Location {
        if (target == null) return player.location
        val world = target.world?.let { plugin.server.getWorld(it) } ?: player.world
        val x = target.x ?: player.location.x
        val y = target.y ?: player.location.y
        val z = target.z ?: player.location.z
        return org.bukkit.Location(world, x, y, z)
    }

    private fun normalizeTreeType(raw: String?): String? {
        val up = raw?.uppercase() ?: return null
        return when (up) {
            "TREE", "BIG_TREE", "SWAMP" -> "OAK"
            "BIRCH", "TALL_BIRCH" -> "BIRCH"
            "REDWOOD", "PINE" -> "SPRUCE"
            "TALL_REDWOOD", "MEGA_REDWOOD" -> "MEGA_SPRUCE"
            "ACACIA" -> "ACACIA"
            "DARK_OAK" -> "DARK_OAK"
            "JUNGLE", "SMALL_JUNGLE", "COCOA_TREE", "JUNGLE_BUSH" -> "JUNGLE"
            "MANGROVE" -> "MANGROVE"
            "CRIMSON_FUNGUS", "CRIMSON_FUNGI" -> "CRIMSON"
            "WARPED_FUNGUS", "WARPED_FUNGI" -> "WARPED"
            else -> up
        }
    }

    private fun matchEntity(entity: org.bukkit.entity.Entity, goal: EntityGoal): Boolean {
        if (goal.types.isNotEmpty() && goal.types.none { t -> entity.type.name.equals(t, true) }) return false
        if (goal.names.isNotEmpty()) {
            val name = entity.customName()?.let { PlainTextComponentSerializer.plainText().serialize(it) }?.lowercase()
            if (name == null || goal.names.none { it.equals(name, true) }) return false
        }
        if (goal.colors.isNotEmpty()) {
            val allowed = goal.colors.mapNotNull { runCatching { org.bukkit.DyeColor.valueOf(it.uppercase()) }.getOrNull() }
            val entityColor = when (entity) {
                is Sheep -> entity.color
                else -> null
            }
            if (allowed.isNotEmpty() && (entityColor == null || entityColor !in allowed)) return false
        }
        if ((goal.horseColors.isNotEmpty() || goal.horseStyles.isNotEmpty()) && entity is Horse) {
            val colorOk = goal.horseColors.isEmpty() || goal.horseColors.any { runCatching { Horse.Color.valueOf(it.uppercase()) }.getOrNull() == entity.color }
            val styleOk = goal.horseStyles.isEmpty() || goal.horseStyles.any { runCatching { Horse.Style.valueOf(it.uppercase()) }.getOrNull() == entity.style }
            if (!colorOk || !styleOk) return false
        }
        return true
    }

    private fun processEntities(player: OfflinePlayer, questId: String, node: QuestObjectNode, action: (org.bukkit.entity.LivingEntity, Double) -> Unit): Int {
        val p = player.player ?: return 0
        val center = resolvePosition(p, node.position)
        val radius = node.position?.radius ?: 8.0
        var processed = 0
        val goals = if (node.goals.isNotEmpty()) node.goals else listOf(EntityGoal(goal = node.count.toDouble()))
        goals.forEach { goal ->
            val targetCount = goal.goal.toInt().coerceAtLeast(1)
            val nearby = center.world.getNearbyEntities(center, radius, radius, radius)
                .filterIsInstance<org.bukkit.entity.LivingEntity>()
                .filter { matchEntity(it, goal) }
            for (ent in nearby) {
                if (processed >= targetCount) break
                val dmg = node.damage ?: 0.0
                action(ent, dmg)
                if (node.linkToQuest) {
                    tagEntity(ent, questId)
                }
                processed++
            }
        }
        return processed
    }

    private fun spawnEntities(player: OfflinePlayer, questId: String, node: QuestObjectNode): Int {
        val p = player.player ?: return 0
        val center = resolvePosition(p, node.position)
        val goals = if (node.goals.isNotEmpty()) node.goals else listOf(EntityGoal(types = listOf("ZOMBIE"), goal = node.count.toDouble()))
        var spawned = 0
        goals.forEach { goal ->
            val count = goal.goal.toInt().coerceAtLeast(1)
            val types = if (goal.types.isNotEmpty()) goal.types else listOf("ZOMBIE")
            repeat(count) {
                val typeName = types.getOrNull(it % types.size) ?: types.first()
                val etype = runCatching { org.bukkit.entity.EntityType.valueOf(typeName.uppercase()) }.getOrNull() ?: return@repeat
                val ent = center.world.spawnEntity(center, etype)
                if (ent is org.bukkit.entity.LivingEntity) {
                    if (goal.names.isNotEmpty()) ent.customName(Component.text(goal.names.first()))
                    if (node.linkToQuest) tagEntity(ent, questId)
                }
                spawned++
            }
        }
        return spawned
    }

    private fun teleportEntities(player: OfflinePlayer, questId: String, node: QuestObjectNode): Int {
        val p = player.player ?: return 0
        val center = resolvePosition(p, node.position)
        val target = resolvePosition(p, node.teleportPosition ?: node.position)
        val radius = node.position?.radius ?: 8.0
        val goals = if (node.goals.isNotEmpty()) node.goals else listOf(EntityGoal(goal = node.count.toDouble()))
        var processed = 0
        goals.forEach { goal ->
            val limit = goal.goal.toInt().coerceAtLeast(1)
            val nearby = center.world.getNearbyEntities(center, radius, radius, radius)
                .filterIsInstance<org.bukkit.entity.LivingEntity>()
                .filter { matchEntity(it, goal) }
            for (ent in nearby) {
                if (processed >= limit) break
                ent.teleport(target)
                processed++
            }
        }
        return processed
    }

    private fun killLinked(player: OfflinePlayer, questId: String, node: QuestObjectNode): Int {
        val p = player.player ?: return 0
        val center = resolvePosition(p, node.position)
        val radius = node.position?.radius ?: 8.0
        val key = questLinkKey()
        val nearby = center.world.getNearbyEntities(center, radius, radius, radius)
            .filterIsInstance<org.bukkit.entity.LivingEntity>()
            .filter { ent ->
                ent.persistentDataContainer.has(key, PersistentDataType.STRING) &&
                    ent.persistentDataContainer.get(key, PersistentDataType.STRING) == questId
            }
        nearby.forEach { it.remove() }
        return nearby.size
    }

    private fun tagEntity(entity: org.bukkit.entity.LivingEntity, questId: String) {
        entity.persistentDataContainer.set(questLinkKey(), PersistentDataType.STRING, questId)
    }

    private fun questLinkKey(): NamespacedKey =
        NamespacedKey(plugin, "quest_link")

    private fun BranchSession.findGroupByChild(childId: String): GroupState? =
        groupChildMap[childId]

    private fun findGroupForChild(player: OfflinePlayer, questId: String, childId: String): Pair<String, GroupProgress>? {
        val gp = questService.progress(player)[questId]?.groupState ?: return null
        gp.forEach { (parent, state) ->
            if (state.remaining.contains(childId) || state.completed.contains(childId)) return parent to state
        }
        return null
    }

    private fun sendStartNotify(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        node.startNotify?.let { notify ->
            notify.message.forEach { msg -> MessageFormatter.send(player, msg) }
            notify.sound?.let { snd ->
                runCatching { Sound.valueOf(snd.uppercase()) }.onSuccess { player.playSound(player.location, it, 1f, 1f) }
            }
        }
        node.title?.let { t ->
            val title = MessageFormatter.format(t.title ?: "")
            val subtitle = MessageFormatter.format(t.subtitle ?: "")
            player.sendTitle(title, subtitle, t.fadeIn, t.stay, t.fadeOut)
        }
    }

    private fun renderRaw(text: String, model: QuestModel, player: OfflinePlayer): String =
        questService.renderPlaceholders(text, model.id, player)

    private fun playSound(player: org.bukkit.entity.Player, payload: String) {
        val parts = payload.split("\\s+".toRegex())
        val sound = runCatching { Sound.valueOf(parts.getOrNull(0)?.uppercase() ?: "") }.getOrNull() ?: return
        val volume = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
        val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
        player.playSound(player.location, sound, volume, pitch)
    }

    private fun spawnParticles(player: org.bukkit.entity.Player, payload: String, allPlayers: Boolean = false) {
        val parts = payload.split("\\s+".toRegex())
        val particle = runCatching { Particle.valueOf(parts.getOrNull(0)?.uppercase() ?: "") }.getOrNull() ?: return
        val amount = parts.getOrNull(1)?.toIntOrNull() ?: 10
        val targets = if (allPlayers) plugin.server.onlinePlayers else listOf(player)
        targets.forEach { viewer ->
            viewer.world.spawnParticle(particle, player.location, amount, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun preloadNodeProgress(player: OfflinePlayer, model: QuestModel, branchId: String, node: QuestObjectNode) {
        val session = sessions[player.uniqueId] ?: return
        val saved = questService.loadNodeProgress(player, model.id, branchId, node.id)
        when {
            isMovementNode(node.type) -> session.movementProgress.putIfAbsent(node.id, saved)
            isPhysicalNode(node.type) -> session.physicalProgress.putIfAbsent(node.id, saved)
            isMiscNode(node.type) -> session.miscProgress.putIfAbsent(node.id, saved)
            isPlayerBlockNode(node.type) -> {
                val goals = questService.loadNodeGoalProgress(player, model.id, branchId, node.id)
                if (goals.isNotEmpty()) {
                    val map = session.blockProgress.getOrPut(node.id) { mutableMapOf() }
                    goals.forEach { (g, v) -> map[g] = v }
                }
            }
            isPlayerItemNode(node.type) -> {
                val goals = questService.loadNodeGoalProgress(player, model.id, branchId, node.id)
                if (goals.isNotEmpty()) {
                    val map = session.itemProgress.getOrPut(node.id) { mutableMapOf() }
                    goals.forEach { (g, v) -> map[g] = v }
                }
            }
            isPlayerEntityNode(node.type) -> {
                val goals = questService.loadNodeGoalProgress(player, model.id, branchId, node.id)
                if (goals.isNotEmpty()) {
                    val map = session.entityProgress.getOrPut(node.id) { mutableMapOf() }
                    goals.forEach { (g, v) -> map[g] = v }
                }
            }
        }
    }

    private fun giveEffect(player: org.bukkit.entity.Player, payload: String) {
        val parts = payload.split("\\s+".toRegex())
        val type = runCatching { PotionEffectType.getByName(parts.getOrNull(0)?.uppercase() ?: "") }.getOrNull() ?: return
        val amp = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val ticks = parts.getOrNull(2)?.toIntOrNull() ?: 20
        player.addPotionEffect(PotionEffect(type, ticks, amp))
    }

    private fun isClickAllowed(clickTypes: List<String>, attempted: String): Boolean {
        if (clickTypes.isEmpty()) return true
        return clickTypes.any { ct ->
            val normalized = ct.uppercase()
            normalized == "ANY" || normalized == attempted.uppercase() || normalized.contains("RIGHT")
        }
    }

    private data class BranchSession(
        val questId: String,
        var branchId: String,
        var nodeId: String,
        var timeLimitTask: org.bukkit.scheduler.BukkitTask? = null,
        val groups: MutableList<GroupState> = mutableListOf(),
        val groupChildMap: MutableMap<String, GroupState> = mutableMapOf(),
        val divergeObjectMap: MutableMap<String, DivergeChoiceGui> = mutableMapOf(),
        val blockProgress: MutableMap<String, MutableMap<String, Double>> = mutableMapOf(),
        val blockCounted: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        val entityProgress: MutableMap<String, MutableMap<String, Double>> = mutableMapOf(),
        val entityCounted: MutableMap<String, MutableSet<UUID>> = mutableMapOf(),
        val itemProgress: MutableMap<String, MutableMap<String, Double>> = mutableMapOf(),
        val villagerCounted: MutableMap<String, MutableSet<UUID>> = mutableMapOf(),
        val movementProgress: MutableMap<String, Double> = mutableMapOf(),
        val physicalProgress: MutableMap<String, Double> = mutableMapOf(),
        val miscProgress: MutableMap<String, Double> = mutableMapOf(),
        val waitTasks: MutableMap<String, org.bukkit.scheduler.BukkitTask> = mutableMapOf(),
        val positionActionbarHint: MutableMap<String, Long> = mutableMapOf()
    )

    private data class ActionContinuation(
        val playerId: UUID,
        val questId: String,
        val branchId: String,
        val nodeId: String,
        val nextIndex: Int,
        val pendingDelay: Long
    )

    private data class ParticleScript(
        val particle: Particle,
        val count: Int,
        val offsetX: Double,
        val offsetY: Double,
        val offsetZ: Double,
        val spreadX: Double,
        val spreadY: Double,
        val spreadZ: Double,
        val speed: Double,
        val interval: Long,
        val duration: Long
    )

    private data class GroupState(
        val parentId: String,
        val children: List<String>,
        val ordered: Boolean,
        val required: Int,
        val gotoAfter: String?,
        val completed: MutableSet<String> = mutableSetOf()
    )

    internal data class DivergeGuiSession(
        val questId: String,
        val branchId: String,
        val nodeId: String,
        val choices: List<DivergeChoiceGui>,
        val reopenDelay: Long?,
        var selected: Boolean = false
    )
}






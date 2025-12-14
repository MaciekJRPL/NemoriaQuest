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
import java.util.concurrent.ConcurrentHashMap
import kotlin.sequences.asSequence
import kotlin.math.sqrt

class BranchRuntimeManager(
    private val plugin: JavaPlugin,
    private val questService: QuestService
) {
    private val sessions: MutableMap<UUID, BranchSession> = ConcurrentHashMap()
    private val divergeSessions: MutableMap<UUID, DivergeChatSession> = mutableMapOf()
    private val pendingPrompts: MutableMap<String, ActionContinuation> = mutableMapOf()
    private val pendingSneaks: MutableMap<UUID, ActionContinuation> = mutableMapOf()
    private val pendingNavigations: MutableMap<UUID, ActionContinuation> = mutableMapOf()
    private val particleScripts: MutableMap<String, ParticleScript> = java.util.concurrent.ConcurrentHashMap()
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:137", "start entry", mapOf("questId" to model.id, "playerUuid" to player.uniqueId.toString()))
        val saved = questService.progress(player)[model.id]
        val branchId = saved?.currentBranchId ?: model.mainBranch ?: model.branches.keys.firstOrNull()
        if (branchId == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:140", "start no branchId", mapOf("questId" to model.id))
            questService.stopQuest(player, model.id, complete = false)
            return
        }
        val branch = model.branches[branchId]
        if (branch == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:145", "start branch not found", mapOf("questId" to model.id, "branchId" to branchId))
            questService.stopQuest(player, model.id, complete = false)
            return
        }
        val startNodeId = saved?.currentNodeId ?: branch.startsAt ?: branch.objects.keys.firstOrNull()
        if (startNodeId == null || !branch.objects.containsKey(startNodeId)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:150", "start no startNodeId", mapOf("questId" to model.id, "branchId" to branchId, "startNodeId" to (startNodeId ?: "null")))
            questService.stopQuest(player, model.id, complete = false)
            return
        }
        net.nemoria.quest.core.DebugLog.log("Branch start quest=${model.id} branch=$branchId node=$startNodeId (resume=${saved?.currentNodeId != null})")
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:155", "start creating session", mapOf("questId" to model.id, "branchId" to branchId, "startNodeId" to startNodeId, "isResume" to (saved?.currentNodeId != null)))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:197", "forceGoto entry", mapOf("questId" to questId, "branchId" to branchId, "nodeId" to nodeId, "playerUuid" to player.uniqueId.toString()))
        val model = questService.questInfo(questId)
        if (model == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:198", "forceGoto model not found", mapOf("questId" to questId))
            return false
        }
        val branch = model.branches[branchId]
        if (branch == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:199", "forceGoto branch not found", mapOf("questId" to questId, "branchId" to branchId))
            return false
        }
        if (!branch.objects.containsKey(nodeId)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:200", "forceGoto node not found", mapOf("questId" to questId, "branchId" to branchId, "nodeId" to nodeId))
            return false
        }
        val existing = sessions[player.uniqueId]
        if (existing != null && existing.questId != questId) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:201", "forceGoto stopping existing quest", mapOf("existingQuestId" to existing.questId, "newQuestId" to questId))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:220", "forceGoto cleared progress", mapOf("questId" to questId, "branchId" to branchId, "nodeId" to nodeId))
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
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:232", "forceGoto reset node progress", mapOf("questId" to questId, "nodeId" to nodeId, "nodeType" to currentNode.type.name))
        }
        val hadDialog = divergeSessions.remove(player.uniqueId) != null
        if (hadDialog) {
            ChatHideService.flushBufferedToHistory(player.uniqueId)
            ChatHideService.endDialog(player.uniqueId)
        }
        questService.updateBranchState(player, model.id, branchId, nodeId)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:240", "forceGoto running node", mapOf("questId" to questId, "branchId" to branchId, "nodeId" to nodeId))
        runNode(player, model, branchId, nodeId, 0)
        return true
    }

    fun stop(player: OfflinePlayer) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:229", "stop entry", mapOf("playerUuid" to player.uniqueId.toString(), "hasSession" to sessions.containsKey(player.uniqueId)))
        val session = sessions.remove(player.uniqueId)
        session?.let { sess ->
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:232", "stop canceling tasks", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to sess.questId, "waitTasksCount" to sess.waitTasks.size, "transientTasksCount" to sess.transientTasks.size))
            sess.timeLimitTask?.cancel()
            sess.waitTasks.values.forEach { it.cancel() }
            sess.waitTasks.clear()
            sess.transientTasks.forEach { it.cancel() }
            sess.transientTasks.clear()
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

    private fun trackTask(playerId: UUID, task: org.bukkit.scheduler.BukkitTask) {
        sessions[playerId]?.transientTasks?.add(task)
    }

    fun shutdown() {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:309", "shutdown entry", mapOf("sessionsCount" to sessions.size, "divergeSessionsCount" to divergeSessions.size, "pendingPromptsCount" to pendingPrompts.size, "pendingSneaksCount" to pendingSneaks.size, "pendingNavigationsCount" to pendingNavigations.size, "guiSessionsCount" to guiSessions.size))
        sessions.values.forEach { sess ->
            sess.timeLimitTask?.cancel()
            sess.waitTasks.values.forEach { it.cancel() }
            sess.transientTasks.forEach { it.cancel() }
            sess.waitTasks.clear()
            sess.transientTasks.clear()
        }
        sessions.clear()
        divergeSessions.clear()
        pendingPrompts.clear()
        pendingSneaks.clear()
        pendingNavigations.clear()
        guiSessions.clear()
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:323", "shutdown completed", mapOf())
    }

    private fun runNode(player: OfflinePlayer, model: QuestModel, branchId: String, nodeId: String, delayTicks: Long) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:331", "runNode entry", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to nodeId, "delayTicks" to delayTicks, "playerUuid" to player.uniqueId.toString()))
        val branch = model.branches[branchId]
        if (branch == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:335", "runNode branch not found", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to nodeId))
            return
        }
        val node = branch.objects[nodeId]
        if (node == null) {
            net.nemoria.quest.core.DebugLog.log("Node not found quest=${model.id} branch=$branchId node=$nodeId")
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:337", "runNode node not found", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to nodeId))
            return
        }
        net.nemoria.quest.core.DebugLog.log("Schedule node quest=${model.id} branch=$branchId node=$nodeId delay=$delayTicks")
        sessions[player.uniqueId]?.nodeId = nodeId
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:342", "runNode scheduling", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to nodeId, "delayTicks" to delayTicks))
        val task = object : BukkitRunnable() {
            override fun run() {
                net.nemoria.quest.core.DebugLog.log("Execute node quest=${model.id} branch=$branchId node=$nodeId")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:345", "runNode task executing", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to nodeId))
                executeNode(player, model, branchId, node)
            }
        }.runTaskLater(plugin, delayTicks)
        trackTask(player.uniqueId, task)
    }

    private fun executeNode(player: OfflinePlayer, model: QuestModel, branchId: String, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:314", "executeNode entry", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to node.id, "nodeType" to node.type.name, "playerUuid" to player.uniqueId.toString()))
        val p = player.player
        if (p == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:315", "executeNode player offline", mapOf("questId" to model.id, "nodeId" to node.id))
            return
        }
        val hadDiverge = divergeSessions.remove(player.uniqueId) != null
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:363", "executeNode removed diverge session", mapOf("questId" to model.id, "nodeId" to node.id, "hadDiverge" to hadDiverge))
        questService.updateBranchState(player, model.id, branchId, node.id)
        if (node.type != QuestObjectNodeType.DIVERGE_CHAT) {
            sendStartNotify(p, node)
        }
        if (node.type == QuestObjectNodeType.PLAYER_TOGGLE_SNEAK) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:368", "executeNode clearing TOGGLE_SNEAK progress", mapOf("questId" to model.id, "nodeId" to node.id))
            questService.clearNodeProgress(player, model.id, branchId, node.id)
            sessions[player.uniqueId]?.physicalProgress?.put(node.id, 0.0)
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:325", "executeNode processing type", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
        when (node.type) {
            QuestObjectNodeType.NONE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:326", "executeNode NONE", mapOf("questId" to model.id, "nodeId" to node.id, "hasPosition" to (node.position != null), "hasGoto" to (node.goto != null)))
                val pos = node.position
                val bukkitPlayer = player.player ?: return
                if (pos != null) {
                    val target = resolvePosition(bukkitPlayer, pos)
                    val radiusSq = pos.radius * pos.radius
                    val distSq = bukkitPlayer.location.distanceSquared(target)
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:330", "executeNode NONE position check", mapOf("questId" to model.id, "nodeId" to node.id, "worldMatch" to (bukkitPlayer.location.world == target.world), "distSq" to distSq, "radiusSq" to radiusSq, "inRange" to (distSq <= radiusSq)))
                    if (bukkitPlayer.location.world != target.world || distSq > radiusSq) {
                        runNode(player, model, branchId, node.id, 20)
                        return
                    }
                }
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.GROUP -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:339", "executeNode GROUP", mapOf("questId" to model.id, "nodeId" to node.id, "groupObjectsCount" to node.groupObjects.size))
                startGroup(player, model, branchId, node)
            }
            QuestObjectNodeType.SERVER_ACTIONS -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:342", "executeNode SERVER_ACTIONS", mapOf("questId" to model.id, "nodeId" to node.id, "actionsCount" to node.actions.size))
                runActionQueue(player, model, branchId, node, 0, 0L)
            }
            QuestObjectNodeType.RANDOM -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:345", "executeNode RANDOM", mapOf("questId" to model.id, "nodeId" to node.id, "randomGotosCount" to node.randomGotos.size, "gotosCount" to node.gotos.size, "hasGoto" to (node.goto != null)))
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
                val pick = filtered.ifEmpty { targets }.randomOrNull()
                if (pick == null) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:363", "executeNode RANDOM no pick", mapOf("questId" to model.id, "nodeId" to node.id))
                    return
                }
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:363", "executeNode RANDOM picked", mapOf("questId" to model.id, "nodeId" to node.id, "pick" to pick, "filteredCount" to filtered.size, "targetsCount" to targets.size))
                if (pick.startsWith("QUEST_", true)) {
                    val endType = pick.removePrefix("QUEST_").uppercase()
                    questService.mutateProgress(player, model.id) { it.randomHistory.add(endType) }
                }
                handleGoto(player, model, branchId, pick, 0, node.id)
            }
            QuestObjectNodeType.SERVER_ITEMS_CLEAR -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:370", "executeNode SERVER_ITEMS_CLEAR", mapOf("questId" to model.id, "nodeId" to node.id))
                p.inventory.clear()
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ITEMS_GIVE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:374", "executeNode SERVER_ITEMS_GIVE", mapOf("questId" to model.id, "nodeId" to node.id, "itemsCount" to node.items.size))
                giveOrDropItems(p, node, drop = false)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ITEMS_DROP -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:378", "executeNode SERVER_ITEMS_DROP", mapOf("questId" to model.id, "nodeId" to node.id, "itemsCount" to node.items.size))
                giveOrDropItems(p, node, drop = true)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ITEMS_MODIFY -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:382", "executeNode SERVER_ITEMS_MODIFY", mapOf("questId" to model.id, "nodeId" to node.id))
                val modified = modifyItems(p, node)
                DebugLog.log("Items modify count=$modified quest=${model.id} node=${node.id}")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:384", "executeNode SERVER_ITEMS_MODIFY result", mapOf("questId" to model.id, "nodeId" to node.id, "modifiedCount" to modified))
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ITEMS_TAKE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:387", "executeNode SERVER_ITEMS_TAKE", mapOf("questId" to model.id, "nodeId" to node.id, "itemsCount" to node.items.size))
                val taken = takeItems(p, node)
                DebugLog.log("Items take count=$taken quest=${model.id} node=${node.id}")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:389", "executeNode SERVER_ITEMS_TAKE result", mapOf("questId" to model.id, "nodeId" to node.id, "takenCount" to taken))
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_COMMANDS_PERFORM -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:392", "executeNode SERVER_COMMANDS_PERFORM", mapOf("questId" to model.id, "nodeId" to node.id, "actionsCount" to node.actions.size, "asPlayer" to node.commandsAsPlayer))
                node.actions.forEach { cmd ->
                    val rendered = cmd.replace("{player}", p.name)
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:394", "executeNode SERVER_COMMANDS_PERFORM executing", mapOf("questId" to model.id, "nodeId" to node.id, "command" to rendered, "asPlayer" to node.commandsAsPlayer))
                    if (node.commandsAsPlayer) plugin.server.dispatchCommand(p, rendered)
                    else plugin.server.dispatchCommand(plugin.server.consoleSender, rendered)
                }
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_LOGIC_VARIABLE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:400", "executeNode SERVER_LOGIC_VARIABLE", mapOf("questId" to model.id, "nodeId" to node.id, "variable" to (node.variable ?: "null"), "formula" to (node.valueFormula ?: "null")))
                val varName = node.variable
                if (varName == null) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:401", "executeNode SERVER_LOGIC_VARIABLE no variable", mapOf("questId" to model.id, "nodeId" to node.id))
                    return
                }
                val value = applyValueFormula(model, player, varName, node.valueFormula)
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:403", "executeNode SERVER_LOGIC_VARIABLE result", mapOf("questId" to model.id, "nodeId" to node.id, "variable" to varName, "value" to value))
                questService.updateVariable(player, model.id, varName, value)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_LOGIC_MONEY -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:406", "executeNode SERVER_LOGIC_MONEY", mapOf("questId" to model.id, "nodeId" to node.id, "currency" to (node.currency ?: "null"), "formula" to (node.valueFormula ?: "null")))
                val amount = evalFormula(node.valueFormula, 0.0)
                if (node.currency.equals("VAULT", true)) {
                    val cmd = "eco give ${p.name} ${amount.toInt()}"
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:409", "executeNode SERVER_LOGIC_MONEY vault", mapOf("questId" to model.id, "nodeId" to node.id, "amount" to amount.toInt(), "command" to cmd))
                    plugin.server.dispatchCommand(plugin.server.consoleSender, cmd)
                } else {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:412", "executeNode SERVER_LOGIC_MONEY custom", mapOf("questId" to model.id, "nodeId" to node.id, "actionsCount" to node.actions.size))
                    node.actions.forEach {
                        plugin.server.dispatchCommand(plugin.server.consoleSender, it.replace("{player}", p.name))
                    }
                }
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_DAMAGE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:418", "executeNode SERVER_ENTITIES_DAMAGE", mapOf("questId" to model.id, "nodeId" to node.id))
                val processed = processEntities(player, model.id, node) { ent, dmg -> ent.damage(dmg, p) }
                DebugLog.log("Entities damage processed=$processed quest=${model.id} node=${node.id}")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:420", "executeNode SERVER_ENTITIES_DAMAGE result", mapOf("questId" to model.id, "nodeId" to node.id, "processedCount" to processed))
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_KILL -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:423", "executeNode SERVER_ENTITIES_KILL", mapOf("questId" to model.id, "nodeId" to node.id))
                val processed = processEntities(player, model.id, node) { ent, _ -> ent.remove() }
                DebugLog.log("Entities kill processed=$processed quest=${model.id} node=${node.id}")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:425", "executeNode SERVER_ENTITIES_KILL result", mapOf("questId" to model.id, "nodeId" to node.id, "processedCount" to processed))
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_KILL_LINKED -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:428", "executeNode SERVER_ENTITIES_KILL_LINKED", mapOf("questId" to model.id, "nodeId" to node.id))
                val processed = killLinked(player, model.id, node)
                DebugLog.log("Entities kill linked processed=$processed quest=${model.id} node=${node.id}")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:430", "executeNode SERVER_ENTITIES_KILL_LINKED result", mapOf("questId" to model.id, "nodeId" to node.id, "processedCount" to processed))
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_SPAWN -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:433", "executeNode SERVER_ENTITIES_SPAWN", mapOf("questId" to model.id, "nodeId" to node.id))
                val spawned = spawnEntities(player, model.id, node)
                DebugLog.log("Entities spawn spawned=$spawned quest=${model.id} node=${node.id}")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:435", "executeNode SERVER_ENTITIES_SPAWN result", mapOf("questId" to model.id, "nodeId" to node.id, "spawnedCount" to spawned))
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_ENTITIES_TELEPORT -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:438", "executeNode SERVER_ENTITIES_TELEPORT", mapOf("questId" to model.id, "nodeId" to node.id))
                val processed = teleportEntities(player, model.id, node)
                DebugLog.log("Entities teleport processed=$processed quest=${model.id} node=${node.id}")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:440", "executeNode SERVER_ENTITIES_TELEPORT result", mapOf("questId" to model.id, "nodeId" to node.id, "processedCount" to processed))
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_BLOCKS_PLACE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:443", "executeNode SERVER_BLOCKS_PLACE", mapOf("questId" to model.id, "nodeId" to node.id))
                placeBlocks(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_EXPLOSIONS_CREATE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:447", "executeNode SERVER_EXPLOSIONS_CREATE", mapOf("questId" to model.id, "nodeId" to node.id, "explosionPower" to (node.explosionPower ?: "null")))
                createExplosions(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_FIREWORKS_LAUNCH -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:451", "executeNode SERVER_FIREWORKS_LAUNCH", mapOf("questId" to model.id, "nodeId" to node.id))
                launchFireworks(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_LIGHTNING_STRIKE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:455", "executeNode SERVER_LIGHTNING_STRIKE", mapOf("questId" to model.id, "nodeId" to node.id))
                lightningStrikes(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_PLAYER_DAMAGE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:459", "executeNode SERVER_PLAYER_DAMAGE", mapOf("questId" to model.id, "nodeId" to node.id, "damage" to (node.damage ?: 0.0)))
                val dmg = node.damage ?: 0.0
                if (dmg > 0) p.damage(dmg)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_PLAYER_EFFECTS_GIVE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:464", "executeNode SERVER_PLAYER_EFFECTS_GIVE", mapOf("questId" to model.id, "nodeId" to node.id, "effectsCount" to node.effectList.size))
                giveEffects(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_PLAYER_EFFECTS_REMOVE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:468", "executeNode SERVER_PLAYER_EFFECTS_REMOVE", mapOf("questId" to model.id, "nodeId" to node.id, "effectsCount" to node.effectList.size))
                removeEffects(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_PLAYER_TELEPORT -> {
                val teleportPos = node.teleportPosition ?: node.position
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:472", "executeNode SERVER_PLAYER_TELEPORT", mapOf("questId" to model.id, "nodeId" to node.id, "hasPosition" to (teleportPos != null)))
                val target = resolvePosition(p, teleportPos)
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:475", "executeNode SERVER_PLAYER_TELEPORT target", mapOf("questId" to model.id, "nodeId" to node.id, "targetWorld" to (target.world?.name ?: "null"), "targetX" to target.x, "targetY" to target.y, "targetZ" to target.z))
                p.teleport(target)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_LOGIC_POINTS -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:477", "executeNode SERVER_LOGIC_POINTS", mapOf("questId" to model.id, "nodeId" to node.id, "category" to (node.pointsCategory ?: "null"), "formula" to (node.valueFormula ?: "null")))
                val current = resolvePoints(player, node.pointsCategory)
                val value = evalFormula(node.valueFormula, current = current)
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:480", "executeNode SERVER_LOGIC_POINTS result", mapOf("questId" to model.id, "nodeId" to node.id, "current" to current, "value" to value))
                updatePoints(player, node.pointsCategory, value)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_LOGIC_MODEL_VARIABLE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:482", "executeNode SERVER_LOGIC_MODEL_VARIABLE", mapOf("questId" to model.id, "nodeId" to node.id, "variable" to (node.variable ?: "null"), "formula" to (node.valueFormula ?: "null")))
                val varName = node.variable
                if (varName == null) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:483", "executeNode SERVER_LOGIC_MODEL_VARIABLE no variable", mapOf("questId" to model.id, "nodeId" to node.id))
                    return
                }
                val value = applyValueFormula(model, player, varName, node.valueFormula)
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:485", "executeNode SERVER_LOGIC_MODEL_VARIABLE result", mapOf("questId" to model.id, "nodeId" to node.id, "variable" to varName, "value" to value))
                questService.updateVariable(player, model.id, varName, value)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LOGIC_SERVER_VARIABLE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:488", "executeNode SERVER_LOGIC_SERVER_VARIABLE", mapOf("questId" to model.id, "nodeId" to node.id, "variable" to (node.variable ?: "null"), "formula" to (node.valueFormula ?: "null")))
                val name = node.variable
                if (name == null) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:489", "executeNode SERVER_LOGIC_SERVER_VARIABLE no variable", mapOf("questId" to model.id, "nodeId" to node.id))
                    return
                }
                val current = net.nemoria.quest.core.Services.variables.server(name)?.toLongOrNull() ?: 0L
                val value = if (node.valueFormula.isNullOrBlank()) current else {
                    node.valueFormula.replace("{value}", current.toString()).split("+").mapNotNull { it.trim().toLongOrNull() }.sum()
                }
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:494", "executeNode SERVER_LOGIC_SERVER_VARIABLE result", mapOf("questId" to model.id, "nodeId" to node.id, "variable" to name, "current" to current, "value" to value))
                net.nemoria.quest.core.Services.variables.setServer(name, value.toString())
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_LOGIC_XP -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:497", "executeNode SERVER_LOGIC_XP", mapOf("questId" to model.id, "nodeId" to node.id, "formula" to (node.valueFormula ?: "null")))
                val p = player.player ?: return
                val current = p.totalExperience
                val value = evalFormula(node.valueFormula, current.toDouble()).toInt()
                val newTotal = (current + value).coerceAtLeast(0)
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:501", "executeNode SERVER_LOGIC_XP result", mapOf("questId" to model.id, "nodeId" to node.id, "current" to current, "value" to value, "newTotal" to newTotal))
                p.totalExperience = 0
                p.giveExp(newTotal)
                node.goto?.let { handleGoto(player, model, branchId, it, 0) }
            }
            QuestObjectNodeType.SERVER_ACHIEVEMENT_AWARD -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:506", "executeNode SERVER_ACHIEVEMENT_AWARD", mapOf("questId" to model.id, "nodeId" to node.id, "achievementType" to (node.achievementType ?: "null")))
                awardAchievement(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.SERVER_CAMERA_MODE_TOGGLE -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:510", "executeNode SERVER_CAMERA_MODE_TOGGLE", mapOf("questId" to model.id, "nodeId" to node.id, "cameraToggle" to (node.cameraToggle?.toString() ?: "null")))
                toggleCameraMode(p, node)
                node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            }
            QuestObjectNodeType.DIVERGE_GUI -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:514", "executeNode DIVERGE_GUI", mapOf("questId" to model.id, "nodeId" to node.id, "divergeChoicesCount" to node.divergeChoices.size))
                openDivergeGui(p, model, branchId, node)
            }
            QuestObjectNodeType.DIVERGE_OBJECTS -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:517", "executeNode DIVERGE_OBJECTS", mapOf("questId" to model.id, "nodeId" to node.id))
                startDivergeObjects(player, model, branchId, node)
            }
            QuestObjectNodeType.LOGIC_SWITCH -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:520", "executeNode LOGIC_SWITCH", mapOf("questId" to model.id, "nodeId" to node.id, "logic" to (node.logic ?: "null")))
                val next = evaluateLogicSwitch(player, model, node) ?: node.goto ?: node.gotos.firstOrNull()
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:522", "executeNode LOGIC_SWITCH result", mapOf("questId" to model.id, "nodeId" to node.id, "next" to (next ?: "null")))
                if (next != null) handleGoto(player, model, branchId, next, 0, node.id)
            }
            QuestObjectNodeType.CONDITIONS_SWITCH -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:524", "executeNode CONDITIONS_SWITCH", mapOf("questId" to model.id, "nodeId" to node.id, "casesCount" to node.cases.size))
                val next = evalCases(player, model, node) ?: node.goto ?: node.gotos.firstOrNull()
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:526", "executeNode CONDITIONS_SWITCH result", mapOf("questId" to model.id, "nodeId" to node.id, "next" to (next ?: "null")))
                if (next != null) handleGoto(player, model, branchId, next, 0, node.id)
            }
            QuestObjectNodeType.NPC_INTERACT -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:528", "executeNode NPC_INTERACT", mapOf("questId" to model.id, "nodeId" to node.id, "npcId" to (node.npcId ?: "null")))
                sessions[player.uniqueId]?.nodeId = node.id
            }
            QuestObjectNodeType.DIVERGE_CHAT -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:531", "executeNode DIVERGE_CHAT", mapOf("questId" to model.id, "nodeId" to node.id, "choicesCount" to node.choices.size, "hideChat" to node.hideChat, "dialog" to node.dialog))
                sessions[player.uniqueId]?.nodeId = node.id
                ChatHideService.flushBufferedToHistory(p.uniqueId)
                val originalHistory = ChatHistoryManager.history(p.uniqueId)
                val limitedHistory = originalHistory.takeLast(GREY_HISTORY_LIMIT)
                val greyHistory = limitedHistory.map { ChatHistoryManager.greyOut(it) }
                val hiding = node.hideChat || node.dialog
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:536", "executeNode DIVERGE_CHAT setup", mapOf("questId" to model.id, "nodeId" to node.id, "hiding" to hiding, "originalHistorySize" to originalHistory.size, "greyHistorySize" to greyHistory.size))
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
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:553", "executeNode DIVERGE_CHAT sending choices", mapOf("questId" to model.id, "nodeId" to node.id, "choicesCount" to node.choices.size))
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
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:609", "executeNode PLAYER_* waiting", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
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
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:628", "executeNode PLAYER_* waiting", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
                sessions[player.uniqueId]?.nodeId = node.id
                preloadNodeProgress(player, model, branchId, node)
            }
            QuestObjectNodeType.PLAYER_WAIT -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:632", "executeNode PLAYER_WAIT", mapOf("questId" to model.id, "nodeId" to node.id, "waitGoalSeconds" to (node.waitGoalSeconds ?: "null"), "count" to node.count))
                sessions[player.uniqueId]?.nodeId = node.id
                val seconds = (node.waitGoalSeconds ?: node.count.toLong()).coerceAtLeast(0)
                if (seconds > 0) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:636", "executeNode PLAYER_WAIT scheduling", mapOf("questId" to model.id, "nodeId" to node.id, "seconds" to seconds, "ticks" to (seconds * 20)))
                    val task = object : BukkitRunnable() {
                        override fun run() {
                            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:638", "executeNode PLAYER_WAIT task run", mapOf("questId" to model.id, "nodeId" to node.id))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:651", "runActionQueue entry", mapOf("questId" to model.id, "nodeId" to node.id, "startIndex" to startIndex, "startDelay" to startDelay, "actionsCount" to node.actions.size))
        val bukkitPlayer = player.player
        if (bukkitPlayer == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:659", "runActionQueue player offline", mapOf("questId" to model.id, "nodeId" to node.id))
            return
        }
        var delay = startDelay
        val actions = node.actions
        for (i in startIndex until actions.size) {
            val action = actions[i].trim()
            if (action.isEmpty()) continue
            val parts = action.split("\\s+".toRegex(), limit = 2)
            val key = parts.getOrNull(0)?.uppercase() ?: continue
            val payload = parts.getOrNull(1) ?: ""
            DebugLog.log("Action node=${node.id} key=$key payload=$payload delay=$delay")
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:668", "runActionQueue processing action", mapOf("questId" to model.id, "nodeId" to node.id, "index" to i, "key" to key, "payload" to payload.take(100), "delay" to delay))
            when (key) {
                "WAIT_TICKS" -> {
                    val ticks = payload.toLongOrNull() ?: 0L
                    delay += ticks
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:670", "runActionQueue WAIT_TICKS", mapOf("questId" to model.id, "nodeId" to node.id, "ticks" to ticks, "newDelay" to delay))
                }
                "SEND_MESSAGE" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:671", "runActionQueue SEND_MESSAGE", mapOf("questId" to model.id, "nodeId" to node.id, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
                        MessageFormatter.send(bukkitPlayer, renderRaw(payload, model, player))
                    }
                }
                "SEND_SOUND" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:674", "runActionQueue SEND_SOUND", mapOf("questId" to model.id, "nodeId" to node.id, "sound" to payload, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) { playSound(bukkitPlayer, payload) }
                }
                "SEND_TITLE" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:675", "runActionQueue SEND_TITLE", mapOf("questId" to model.id, "nodeId" to node.id, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
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
                }
                "SEND_PARTICLES" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:686", "runActionQueue SEND_PARTICLES", mapOf("questId" to model.id, "nodeId" to node.id, "payload" to payload, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
                        val params = payload.split("\\s+".toRegex())
                        val questOnly = params.getOrNull(2)?.toBooleanStrictOrNull() ?: true
                        spawnParticles(bukkitPlayer, payload, allPlayers = !questOnly)
                    }
                }
                "GIVE_EFFECT" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:691", "runActionQueue GIVE_EFFECT", mapOf("questId" to model.id, "nodeId" to node.id, "effect" to payload, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) { giveEffect(bukkitPlayer, payload) }
                }
                "PERFORM_COMMAND" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:692", "runActionQueue PERFORM_COMMAND", mapOf("questId" to model.id, "nodeId" to node.id, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
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
                }
                "PERFORM_COMMAND_AS_PLAYER" -> schedule(delay, bukkitPlayer.uniqueId) {
                    val cmd = renderRaw(payload, model, player).replace("{player}", bukkitPlayer.name)
                    plugin.server.dispatchCommand(bukkitPlayer, cmd)
                }
                "PERFORM_COMMAND_AS_OP_PLAYER" -> schedule(delay, bukkitPlayer.uniqueId) {
                    val cmd = renderRaw(payload, model, player).replace("{player}", bukkitPlayer.name)
                    val prev = bukkitPlayer.isOp
                    bukkitPlayer.isOp = true
                    plugin.server.dispatchCommand(bukkitPlayer, cmd)
                    bukkitPlayer.isOp = prev
                }
                "PERFORM_OBJECT" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:714", "runActionQueue PERFORM_OBJECT", mapOf("questId" to model.id, "nodeId" to node.id, "payload" to payload, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
                        val partsObj = payload.split("\\s+".toRegex(), limit = 2)
                        val bId = partsObj.getOrNull(0) ?: return@schedule
                        val nId = normalizeTarget(partsObj.getOrNull(1) ?: return@schedule)
                        runNode(player, model, bId, nId, 0)
                    }
                }
                "PERFORM_PARTICLE_SCRIPT" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:720", "runActionQueue PERFORM_PARTICLE_SCRIPT", mapOf("questId" to model.id, "nodeId" to node.id, "script" to payload, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) { runParticleScript(bukkitPlayer, payload) }
                }
                "START_BRANCH", "START_INDIVIDUAL_BRANCH" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:721", "runActionQueue START_BRANCH", mapOf("questId" to model.id, "nodeId" to node.id, "branchId" to payload.trim(), "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
                        val bId = payload.trim()
                        val branch = model.branches[bId] ?: return@schedule
                        val startNode = branch.startsAt ?: branch.objects.keys.firstOrNull() ?: return@schedule
                        sessions[bukkitPlayer.uniqueId] = BranchSession(model.id, bId, startNode)
                        runNode(player, model, bId, startNode, 0)
                    }
                }
                "STOP_BRANCH" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:728", "runActionQueue STOP_BRANCH", mapOf("questId" to model.id, "nodeId" to node.id, "payload" to payload, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
                        val session = sessions[bukkitPlayer.uniqueId]
                        if (session != null && (payload.isBlank() || session.branchId.equals(payload.trim(), true))) {
                            sessions.remove(bukkitPlayer.uniqueId)
                        }
                    }
                }
                "START_QUEST", "START_FUNCTIONAL_QUEST" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:734", "runActionQueue START_QUEST", mapOf("questId" to model.id, "nodeId" to node.id, "targetQuestId" to payload.trim(), "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
                        questService.startQuest(bukkitPlayer, payload.trim())
                    }
                }
                "STOP_QUEST", "STOP_FUNCTIONAL_QUEST" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:737", "runActionQueue STOP_QUEST", mapOf("questId" to model.id, "nodeId" to node.id, "targetQuestId" to payload.trim(), "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
                        questService.stopQuest(bukkitPlayer, payload.trim(), complete = false)
                    }
                }
                "PROMPT_NEXT" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:740", "runActionQueue PROMPT_NEXT", mapOf("questId" to model.id, "nodeId" to node.id, "delay" to delay))
                    val token = UUID.randomUUID().toString()
                    schedule(delay, bukkitPlayer.uniqueId) { sendPrompt(bukkitPlayer, renderRaw(payload, model, player), token) }
                    pendingPrompts[token] = ActionContinuation(
                        playerId = bukkitPlayer.uniqueId,
                        questId = model.id,
                        branchId = branchId,
                        nodeId = node.id,
                        nextIndex = i + 1,
                        pendingDelay = 0L
                    )
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:751", "runActionQueue PROMPT_NEXT waiting", mapOf("questId" to model.id, "nodeId" to node.id, "token" to token, "nextIndex" to (i + 1)))
                    return
                }
                "PROMPT_NEXT_SNEAK" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:753", "runActionQueue PROMPT_NEXT_SNEAK", mapOf("questId" to model.id, "nodeId" to node.id, "delay" to delay))
                    schedule(delay, bukkitPlayer.uniqueId) {
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
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:765", "runActionQueue PROMPT_NEXT_SNEAK waiting", mapOf("questId" to model.id, "nodeId" to node.id, "nextIndex" to (i + 1)))
                    return
                }
                "CITIZENS_NPC_NAVIGATE" -> {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:768", "runActionQueue CITIZENS_NPC_NAVIGATE", mapOf("questId" to model.id, "nodeId" to node.id, "payload" to payload, "delay" to delay))
                    val wait = scheduleCitizensNavigation(bukkitPlayer, payload)
                    if (wait) {
                        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:770", "runActionQueue CITIZENS_NPC_NAVIGATE waiting", mapOf("questId" to model.id, "nodeId" to node.id, "nextIndex" to (i + 1)))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1144", "applyValueFormula entry", mapOf("questId" to model.id, "variable" to variable, "formula" to (formula ?: "null"), "playerUuid" to player.uniqueId.toString()))
        val data = questService.progress(player)[model.id]
        val current = data?.variables?.get(variable)?.toLongOrNull() ?: 0L
        if (formula.isNullOrBlank()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1147", "applyValueFormula no formula", mapOf("questId" to model.id, "variable" to variable, "current" to current))
            return current.toString()
        }
        val parts = formula.replace("{value}", current.toString()).split("+")
        val result = parts.mapNotNull { it.trim().toLongOrNull() }.sum().toString()
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1149", "applyValueFormula result", mapOf("questId" to model.id, "variable" to variable, "current" to current, "formula" to formula, "result" to result))
        return result
    }

    private fun applyUserValueFormula(player: OfflinePlayer, variable: String, formula: String?): String {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1152", "applyUserValueFormula entry", mapOf("variable" to variable, "formula" to (formula ?: "null"), "playerUuid" to player.uniqueId.toString()))
        val current = questService.userVariable(player, variable).toLongOrNull() ?: 0L
        if (formula.isNullOrBlank()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1154", "applyUserValueFormula no formula", mapOf("variable" to variable, "current" to current))
            return current.toString()
        }
        val parts = formula.replace("{value}", current.toString()).split("+")
        val result = parts.mapNotNull { it.trim().toLongOrNull() }.sum().toString()
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1156", "applyUserValueFormula result", mapOf("variable" to variable, "current" to current, "formula" to formula, "result" to result))
        return result
    }

    private fun startGroup(player: OfflinePlayer, model: QuestModel, branchId: String, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1127", "startGroup entry", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to node.id, "childrenCount" to node.groupObjects.size))
        val children = node.groupObjects
        if (children.isEmpty()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1129", "startGroup no children", mapOf("questId" to model.id, "nodeId" to node.id))
            node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            return
        }
        val required = (node.groupRequired ?: children.size).coerceAtLeast(1)
        val progress = questService.progress(player)[model.id]
        val existing = progress?.groupState?.get(node.id)
        val gp = existing ?: GroupProgress(completed = mutableSetOf(), remaining = children.toMutableList(), required = required, ordered = node.groupOrdered)
        questService.mutateProgress(player, model.id) { it.groupState[node.id] = gp }
        val remaining = gp.remaining.ifEmpty { children.toMutableList() }
        val next = if (gp.ordered) remaining.first() else remaining.random()
        net.nemoria.quest.core.DebugLog.log("GROUP start quest=${model.id} parent=${node.id} ordered=${gp.ordered} required=${gp.required} completed=${gp.completed} remaining=${gp.remaining}")
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1140", "startGroup starting next", mapOf("questId" to model.id, "nodeId" to node.id, "next" to next, "ordered" to gp.ordered, "required" to gp.required, "completedCount" to gp.completed.size, "remainingCount" to remaining.size))
        runNode(player, model, branchId, next, 0)
    }

    private fun schedule(delay: Long, playerId: UUID? = null, block: () -> Unit) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1215", "schedule entry", mapOf("delay" to delay, "playerId" to (playerId?.toString() ?: "null")))
        val task = object : BukkitRunnable() {
            override fun run() {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1217", "schedule task running", mapOf("delay" to delay, "playerId" to (playerId?.toString() ?: "null")))
                block()
            }
        }.runTaskLater(plugin, delay)
        if (playerId != null) {
            trackTask(playerId, task)
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1223", "schedule scheduled", mapOf("delay" to delay, "playerId" to (playerId?.toString() ?: "null")))
    }

    private fun sendPrompt(player: org.bukkit.entity.Player, message: String, token: String) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1226", "sendPrompt entry", mapOf("playerUuid" to player.uniqueId.toString(), "token" to token, "hasMessage" to message.isNotBlank()))
        if (message.isNotBlank()) {
            MessageFormatter.send(player, message)
        }
        val prompt = MiniMessage.miniMessage()
            .deserialize("<click:run_command:'/nq prompt $token'><hover:show_text:'Kliknij, aby kontynuować'>[Kliknij, aby kontynuować]</hover></click>")
        player.sendMessage(prompt)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1232", "sendPrompt sent", mapOf("playerUuid" to player.uniqueId.toString(), "token" to token))
    }

    private fun resumeContinuation(player: org.bukkit.entity.Player, cont: ActionContinuation) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1164", "resumeContinuation entry", mapOf("questId" to cont.questId, "branchId" to cont.branchId, "nodeId" to cont.nodeId, "nextIndex" to cont.nextIndex, "pendingDelay" to cont.pendingDelay, "playerUuid" to player.uniqueId.toString()))
        if (cont.playerId != player.uniqueId) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1165", "resumeContinuation player mismatch", mapOf("expectedUuid" to cont.playerId.toString(), "actualUuid" to player.uniqueId.toString()))
            return
        }
        val model = questService.questInfo(cont.questId) ?: return
        val branch = model.branches[cont.branchId] ?: return
        val node = branch.objects[cont.nodeId] ?: return
        if (!sessions.containsKey(player.uniqueId)) {
            sessions[player.uniqueId] = BranchSession(cont.questId, cont.branchId, cont.nodeId)
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1172", "resumeContinuation resuming action queue", mapOf("questId" to cont.questId, "nodeId" to cont.nodeId, "nextIndex" to cont.nextIndex))
        runActionQueue(player, model, cont.branchId, node, cont.nextIndex, cont.pendingDelay)
    }

    fun handlePromptClick(player: org.bukkit.entity.Player, token: String) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1227", "handlePromptClick entry", mapOf("playerUuid" to player.uniqueId.toString(), "token" to token))
        val cont = pendingPrompts.remove(token)
        if (cont == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1228", "handlePromptClick no continuation", mapOf("token" to token))
            return
        }
        resumeContinuation(player, cont)
    }

    fun handleSneakResume(player: org.bukkit.entity.Player) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1232", "handleSneakResume entry", mapOf("playerUuid" to player.uniqueId.toString()))
        val cont = pendingSneaks.remove(player.uniqueId)
        if (cont == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1233", "handleSneakResume no continuation", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        resumeContinuation(player, cont)
    }

    private fun runParticleScript(player: org.bukkit.entity.Player, payload: String) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1255", "runParticleScript entry", mapOf("playerUuid" to player.uniqueId.toString(), "payload" to payload))
        val id = payload.trim()
        val script = particleScripts[id] ?: loadParticleScript(id)?.also { particleScripts[id] = it }
        if (script == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1258", "runParticleScript script not found, using fallback", mapOf("id" to id))
            // fallback: treat payload as direct particle name
            spawnParticles(player, payload, allPlayers = false)
            return
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1263", "runParticleScript starting", mapOf("id" to id, "particle" to script.particle.name, "duration" to script.duration, "interval" to script.interval))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1275", "loadParticleScript entry", mapOf("id" to id))
        val file = File(plugin.dataFolder, "content/particle_scripts/$id.yml")
        if (!file.exists()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1277", "loadParticleScript file not found", mapOf("id" to id, "filePath" to file.absolutePath))
            return null
        }
        val cfg = YamlConfiguration.loadConfiguration(file)
        val particle = runCatching { Particle.valueOf(cfg.getString("particle")?.uppercase() ?: "") }.getOrNull()
        if (particle == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1279", "loadParticleScript invalid particle", mapOf("id" to id, "particleStr" to cfg.getString("particle")))
            return null
        }
        val script = ParticleScript(
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1292", "loadParticleScript success", mapOf("id" to id, "particle" to particle.name, "count" to script.count, "duration" to script.duration))
        return script
    }

    fun preloadParticleScripts() {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1295", "preloadParticleScripts entry", mapOf())
        val dir = File(plugin.dataFolder, "content/particle_scripts")
        if (!dir.exists() || !dir.isDirectory) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1297", "preloadParticleScripts dir not found", mapOf("dirPath" to dir.absolutePath))
            return
        }
        val files = dir.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1298", "preloadParticleScripts found files", mapOf("filesCount" to (files?.size ?: 0)))
        files?.forEach { file ->
            val id = file.nameWithoutExtension
            loadParticleScript(id)?.let { particleScripts[id] = it }
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1302", "preloadParticleScripts done", mapOf("loadedCount" to particleScripts.size))
    }

    private fun scheduleCitizensNavigation(player: org.bukkit.entity.Player, payload: String): Boolean {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1235", "scheduleCitizensNavigation entry", mapOf("playerUuid" to player.uniqueId.toString(), "payload" to payload))
        val parts = payload.split("\\s+".toRegex())
        val npcId = parts.getOrNull(0)?.toIntOrNull()
        if (npcId == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1237", "scheduleCitizensNavigation invalid npcId", mapOf("payload" to payload))
            return false
        }
        val locArg = parts.getOrNull(1)
        val wait = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false
        val radius = parts.getOrNull(3)?.toDoubleOrNull()
        val npc = runCatching {
            val api = Class.forName("net.citizensnpcs.api.CitizensAPI")
            val reg = api.getMethod("getNPCRegistry").invoke(null)
            val getNpc = reg.javaClass.getMethod("getById", Int::class.javaPrimitiveType)
            getNpc.invoke(reg, npcId)
        }.getOrNull()
        if (npc == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1241", "scheduleCitizensNavigation npc not found", mapOf("npcId" to npcId))
            return false
        }
        val navigator = runCatching { npc.javaClass.getMethod("getNavigator").invoke(npc) }.getOrNull()
        if (navigator == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1247", "scheduleCitizensNavigation navigator not found", mapOf("npcId" to npcId))
            return false
        }
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1262", "scheduleCitizensNavigation target set", mapOf("npcId" to npcId, "targetWorld" to (targetLoc.world?.name ?: "null"), "targetX" to targetLoc.x, "targetY" to targetLoc.y, "targetZ" to targetLoc.z, "wait" to wait, "radius" to (radius ?: "null")))
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
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1275", "scheduleCitizensNavigation completed", mapOf("npcId" to npcId, "navigating" to navigating, "arrived" to arrived))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1286", "evaluateLogicSwitch entry", mapOf("questId" to model.id, "nodeId" to node.id, "logic" to (node.logic ?: "null")))
        val logic = node.logic?.trim()
        if (logic == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1287", "evaluateLogicSwitch no logic", mapOf("questId" to model.id, "nodeId" to node.id))
            return null
        }
        val pattern = "\\{(mvariable|gvariable):([A-Za-z0-9_]+)}\\s*([=<>!]+)\\s*(-?\\d+)".toRegex()
        val match = pattern.find(logic)
        if (match == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1289", "evaluateLogicSwitch no match", mapOf("questId" to model.id, "nodeId" to node.id, "logic" to logic))
            return null
        }
        val scope = match.groupValues[1]
        val varName = match.groupValues[2]
        val op = match.groupValues[3]
        val target = match.groupValues[4].toLongOrNull()
        if (target == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1293", "evaluateLogicSwitch invalid target", mapOf("questId" to model.id, "nodeId" to node.id, "targetStr" to match.groupValues[4]))
            return null
        }
        val current = resolveVariable(player, model.id, scope, varName)
        val ok = compareLong(current, target, op)
        val result = if (ok) node.goto else node.gotos.firstOrNull()
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1295", "evaluateLogicSwitch result", mapOf("questId" to model.id, "nodeId" to node.id, "scope" to scope, "varName" to varName, "current" to current, "op" to op, "target" to target, "ok" to ok, "result" to (result ?: "null")))
        return result
    }

    private fun evalCases(player: OfflinePlayer, model: QuestModel, node: QuestObjectNode): String? {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1299", "evalCases entry", mapOf("questId" to model.id, "nodeId" to node.id, "casesCount" to node.cases.size))
        val p = player.player
        if (p == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1300", "evalCases player offline", mapOf("questId" to model.id, "nodeId" to node.id))
            return null
        }
        node.cases.forEach { c ->
            val matches = c.conditions.count { questService.checkConditions(p, listOf(it), model.id) }
            val noMatch = c.conditions.size - matches
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1302", "evalCases checking case", mapOf("questId" to model.id, "nodeId" to node.id, "matches" to matches, "noMatch" to noMatch, "matchAmount" to c.matchAmount, "noMatchAmount" to c.noMatchAmount, "hasGoto" to (c.goto != null)))
            if (matches >= c.matchAmount && noMatch <= c.noMatchAmount && c.goto != null) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1304", "evalCases case matched", mapOf("questId" to model.id, "nodeId" to node.id, "goto" to c.goto))
                return c.goto
            }
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1308", "evalCases no match", mapOf("questId" to model.id, "nodeId" to node.id))
        return null
    }

    private fun resolveVariable(player: OfflinePlayer, questId: String, scope: String, name: String): Long {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1516", "resolveVariable entry", mapOf("questId" to questId, "scope" to scope, "name" to name, "playerUuid" to player.uniqueId.toString()))
        // scope currently treated the same; placeholder for future global variables
        val data = questService.progress(player)[questId]
        val value = data?.variables?.get(name)?.toLongOrNull() ?: 0L
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1519", "resolveVariable result", mapOf("questId" to questId, "scope" to scope, "name" to name, "value" to value))
        return value
    }

    private fun compareLong(current: Long, target: Long, op: String): Boolean {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1522", "compareLong entry", mapOf("current" to current, "target" to target, "op" to op))
        val result = when (op) {
            "=", "==" -> current == target
            ">" -> current > target
            "<" -> current < target
            ">=" -> current >= target
            "<=" -> current <= target
            "!=" -> current != target
            else -> false
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1531", "compareLong result", mapOf("current" to current, "target" to target, "op" to op, "result" to result))
        return result
    }

    private fun handleGoto(player: OfflinePlayer, model: QuestModel, branchId: String, goto: String, delay: Long, currentNodeId: String? = null) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1328", "handleGoto entry", mapOf("questId" to model.id, "branchId" to branchId, "goto" to goto, "delay" to delay, "currentNodeId" to (currentNodeId ?: "null"), "playerUuid" to player.uniqueId.toString()))
        var target = normalizeTarget(goto)
        val questId = model.id
        val session = sessions[player.uniqueId]
        // diverge objects mapping
        session?.let { sess ->
            if (currentNodeId != null && sess.divergeObjectMap.containsKey(currentNodeId)) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1334", "handleGoto diverge object mapping", mapOf("questId" to questId, "currentNodeId" to currentNodeId))
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
                        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1344", "handleGoto mapped target", mapOf("questId" to questId, "originalGoto" to goto, "mappedTarget" to target, "choiceId" to choice.id))
                    }
                }
                sess.divergeObjectMap.clear()
            }
        }
        // group progress
        val childId = currentNodeId ?: session?.nodeId
        if (childId != null) {
            val gpEntry = findGroupForChild(player, questId, childId)
            if (gpEntry != null) {
                val (parentId, gp) = gpEntry
                net.nemoria.quest.core.DebugLog.log("GROUP progress quest=$questId parent=$parentId child=$childId completed=${gp.completed} remaining=${gp.remaining} required=${gp.required} ordered=${gp.ordered}")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1353", "handleGoto group progress", mapOf("questId" to questId, "parentId" to parentId, "childId" to childId, "completedCount" to gp.completed.size, "remainingCount" to gp.remaining.size, "required" to gp.required, "ordered" to gp.ordered))
                gp.completed.add(childId)
                gp.remaining.remove(childId)
                val required = gp.required.coerceAtLeast(1)
                val remaining = gp.remaining.toList()
                if (gp.completed.size >= required || remaining.isEmpty()) {
                    questService.mutateProgress(player, questId) { it.groupState.remove(parentId) }
                    net.nemoria.quest.core.DebugLog.log("GROUP complete quest=$questId parent=$parentId goto=${model.branches[branchId]?.objects?.get(parentId)?.goto}")
                    val parentGoto = model.branches[branchId]?.objects?.get(parentId)?.goto
                    target = parentGoto?.let { normalizeTarget(it) } ?: target
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1361", "handleGoto group complete", mapOf("questId" to questId, "parentId" to parentId, "target" to target, "parentGoto" to (parentGoto ?: "null")))
                } else {
                    val next = if (gp.ordered) remaining.first() else remaining.random()
                    questService.mutateProgress(player, questId) { it.groupState[parentId] = gp }
                    net.nemoria.quest.core.DebugLog.log("GROUP next quest=$questId parent=$parentId -> $next completed=${gp.completed} remaining=${gp.remaining}")
                    target = normalizeTarget(next)
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1367", "handleGoto group next", mapOf("questId" to questId, "parentId" to parentId, "next" to next, "target" to target, "ordered" to gp.ordered))
                }
            }
        }
        net.nemoria.quest.core.DebugLog.log("Goto quest=${model.id} branch=$branchId to=$target delay=$delay")
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1373", "handleGoto final target", mapOf("questId" to model.id, "branchId" to branchId, "target" to target, "delay" to delay))
        if (target.startsWith("QUEST_", ignoreCase = true)) {
            val key = target.removePrefix("QUEST_")
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1375", "handleGoto finishing quest", mapOf("questId" to questId, "outcome" to key))
            questService.finishOutcome(player, questId, key)
            if (model.branches.isNotEmpty()) {
                questService.mutateProgress(player, questId) { it.randomHistory.add(key.uppercase()) }
            }
            return
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1381", "handleGoto running node", mapOf("questId" to model.id, "branchId" to branchId, "target" to target, "delay" to delay))
        runNode(player, model, branchId, target, delay)
    }

    private fun normalizeTarget(raw: String): String {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1639", "normalizeTarget entry", mapOf("raw" to raw))
        var t = raw.trim()
        if (t.uppercase().startsWith("OBJECT")) {
            t = t.substringAfter("OBJECT").trim()
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1644", "normalizeTarget result", mapOf("raw" to raw, "normalized" to t))
        return t
    }

    fun handleNpcInteract(player: OfflinePlayer, npcId: Int) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1043", "handleNpcInteract entry", mapOf("playerUuid" to player.uniqueId.toString(), "npcId" to npcId))
        val session = sessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1044", "handleNpcInteract no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (node.type != QuestObjectNodeType.NPC_INTERACT) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1048", "handleNpcInteract wrong type", mapOf("nodeType" to node.type.name))
            return
        }
        if (node.npcId != null && node.npcId != 0 && node.npcId != npcId) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1049", "handleNpcInteract npcId mismatch", mapOf("expectedNpcId" to node.npcId, "actualNpcId" to npcId))
            return
        }
        if (!isClickAllowed(node.clickTypes, "RIGHT_CLICK")) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1050", "handleNpcInteract click not allowed", mapOf("clickTypes" to node.clickTypes.toString()))
            return
        }
        val gotoRaw = node.goto ?: return
        val target = normalizeTarget(gotoRaw)
        net.nemoria.quest.core.DebugLog.log("NPC match quest=${model.id} node=${node.id} npc=$npcId -> $target")
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1054", "handleNpcInteract running node", mapOf("questId" to model.id, "nodeId" to node.id, "target" to target))
        runNode(player, model, session.branchId, target, 0)
    }

    fun handleDivergeChoice(player: OfflinePlayer, choiceIndex: Int) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1106", "handleDivergeChoice entry", mapOf("playerUuid" to player.uniqueId.toString(), "choiceIndex" to choiceIndex))
        val sessionChoices = divergeSessions[player.uniqueId]
        if (sessionChoices == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1107", "handleDivergeChoice no sessionChoices", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        val choice = sessionChoices.choices.getOrNull(choiceIndex - 1)
        if (choice == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1108", "handleDivergeChoice invalid choiceIndex", mapOf("choiceIndex" to choiceIndex, "choicesCount" to sessionChoices.choices.size))
            return
        }
        val session = sessions[player.uniqueId] ?: return
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (node.type != QuestObjectNodeType.DIVERGE_CHAT) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1113", "handleDivergeChoice wrong node type", mapOf("nodeType" to node.type.name))
            return
        }
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1646", "sendDivergeChoices entry", mapOf("playerUuid" to player.uniqueId.toString(), "choicesCount" to choices.size, "highlightIdx" to highlightIdx, "storeState" to storeState))
        val session = divergeSessions[player.uniqueId]
        if (choices.isEmpty()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1653", "sendDivergeChoices empty choices", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        if (session != null && session.lastRenderIdx == highlightIdx && !storeState) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1654", "sendDivergeChoices already rendered", mapOf("playerUuid" to player.uniqueId.toString(), "lastRenderIdx" to session.lastRenderIdx, "highlightIdx" to highlightIdx))
            return
        }
        if (session != null) {
            val now = System.currentTimeMillis()
            if (!storeState && now - session.lastRenderAt < 200) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1657", "sendDivergeChoices too soon", mapOf("playerUuid" to player.uniqueId.toString(), "timeSinceLastRender" to (now - session.lastRenderAt)))
                return
            }
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1674", "scrollDiverge entry", mapOf("playerUuid" to player.uniqueId.toString(), "delta" to delta))
        val session = divergeSessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1675", "scrollDiverge no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        if (session.choices.isEmpty()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1676", "scrollDiverge no choices", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        val size = session.choices.size
        val next = ((session.currentIndex - 1 + delta) % size + size) % size + 1
        session.currentIndex = next
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1679", "scrollDiverge scrolling", mapOf("playerUuid" to player.uniqueId.toString(), "oldIndex" to (session.currentIndex - delta), "newIndex" to next, "size" to size))
        sendDivergeChoices(player, session.choices, highlightIdx = next)
    }

    fun acceptCurrentDiverge(player: org.bukkit.entity.Player) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1683", "acceptCurrentDiverge entry", mapOf("playerUuid" to player.uniqueId.toString()))
        val session = divergeSessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1684", "acceptCurrentDiverge no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1685", "acceptCurrentDiverge accepting", mapOf("playerUuid" to player.uniqueId.toString(), "choiceIndex" to session.currentIndex))
        handleDivergeChoice(player, session.currentIndex)
    }

    private fun clearChatWindow(player: org.bukkit.entity.Player, lines: Int = 100) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1845", "clearChatWindow entry", mapOf("playerUuid" to player.uniqueId.toString(), "lines" to lines))
        repeat(lines) {
            sendSyntheticMessage(player, Component.empty())
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1849", "clearChatWindow cleared", mapOf("playerUuid" to player.uniqueId.toString(), "lines" to lines))
    }

    private fun sendAllowedComponent(player: org.bukkit.entity.Player, component: Component) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1851", "sendAllowedComponent entry", mapOf("playerUuid" to player.uniqueId.toString(), "componentText" to PlainTextComponentSerializer.plainText().serialize(component).take(100)))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1698", "handlePlayerBlockEvent entry", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "blockType" to block.type.name, "action" to (action ?: "null"), "itemType" to (item?.type?.name ?: "null"), "placedByPlayer" to placedByPlayer, "spawnerType" to (spawnerType ?: "null"), "treeType" to (treeType ?: "null")))
        val session = sessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1708", "handlePlayerBlockEvent no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isPlayerBlockNode(node.type)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1712", "handlePlayerBlockEvent not block node", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
            return
        }

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
        if (!typeMatches) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1727", "handlePlayerBlockEvent type mismatch", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name, "kind" to kind.name))
            return
        }

        val clickOk = when (node.type) {
            QuestObjectNodeType.PLAYER_BLOCKS_INTERACT -> matchesClick(node.blockClickType, action)
            QuestObjectNodeType.PLAYER_BLOCKS_STRIP,
            QuestObjectNodeType.PLAYER_BLOCK_FARM,
            QuestObjectNodeType.PLAYER_MAKE_PATHS -> matchesClick("RIGHT_CLICK", action)
            else -> true
        }
        if (!clickOk) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1736", "handlePlayerBlockEvent click not ok", mapOf("questId" to model.id, "nodeId" to node.id, "expectedClick" to (node.blockClickType ?: "RIGHT_CLICK"), "actualAction" to (action ?: "null")))
            return
        }

        if (!node.blockAllowPlayerBlocks && placedByPlayer) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1738", "handlePlayerBlockEvent player blocks not allowed", mapOf("questId" to model.id, "nodeId" to node.id))
            node.blockAllowPlayerBlocksMessage?.let { MessageFormatter.send(player, it) }
            return
        }

        val locKey = block.locationKey()
        val counted = session.blockCounted.getOrPut(node.id) { mutableSetOf() }
        if (!node.blockAllowSameBlocks && counted.contains(locKey)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1745", "handlePlayerBlockEvent same block not allowed", mapOf("questId" to model.id, "nodeId" to node.id, "locKey" to locKey))
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
            val updated = current + 1.0
            progMap[goal.id] = updated
            questService.saveNodeProgress(player, model.id, session.branchId, node.id, updated, goal.id)
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1800", "handlePlayerBlockEvent progress", mapOf("questId" to model.id, "nodeId" to node.id, "goalId" to goal.id, "current" to current, "updated" to updated, "goal" to goal.goal))
            progressed = true
        }

        if (progressed) {
            counted.add(locKey)
        }

        val progMap = session.blockProgress[node.id] ?: return
        val allDone = goals.all { g -> (progMap[g.id] ?: 0.0) >= g.goal }
        if (allDone) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1810", "handlePlayerBlockEvent all done", mapOf("questId" to model.id, "nodeId" to node.id, "goalsCount" to goals.size))
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            val gotoRaw = node.goto ?: return
            val target = normalizeTarget(gotoRaw)
            runNode(player, model, session.branchId, target, 0)
        }
    }

    private fun matchesClick(expected: String?, action: String?): Boolean {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2027", "matchesClick entry", mapOf("expected" to (expected ?: "null"), "action" to (action ?: "null")))
        if (expected.isNullOrBlank()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2028", "matchesClick expected blank, allowing", mapOf())
            return true
        }
        if (action.isNullOrBlank()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2029", "matchesClick action blank, denying", mapOf())
            return false
        }
        val result = expected.equals(action, true)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2030", "matchesClick result", mapOf("expected" to expected, "action" to action, "result" to result))
        return result
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1870", "handlePlayerEntityEvent entry", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "entityType" to (entity?.type?.name ?: "null"), "damagerType" to (damager?.type?.name ?: "null"), "entityTypeHint" to (entityTypeHint ?: "null")))
        val session = sessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1876", "handlePlayerEntityEvent no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isPlayerEntityNode(node.type)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1882", "handlePlayerEntityEvent not entity node", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
            return
        }

        val counted = session.entityCounted.getOrPut(node.id) { mutableSetOf() }
        val targetEntity = when (kind) {
            EntityEventType.GET_DAMAGED -> damager ?: entity
            else -> entity
        }
        val targetType = targetEntity?.type?.name ?: entityTypeHint
        val entityId = targetEntity?.uniqueId
        if (!node.entityAllowSame && entityId != null && counted.contains(entityId)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1894", "handlePlayerEntityEvent same entity not allowed", mapOf("questId" to model.id, "nodeId" to node.id, "entityId" to entityId.toString()))
            node.entityAllowSameMessage?.let { MessageFormatter.send(player, it) }
            return
        }

        if (node.type == QuestObjectNodeType.PLAYER_ENTITIES_DEATH_NEARBY) {
            val maxDist = node.entityMaxDistance ?: return
            val entityLoc = entity?.location
            if (entityLoc == null) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1900", "handlePlayerEntityEvent DEATH_NEARBY no entity", mapOf("questId" to model.id, "nodeId" to node.id))
                return
            }
            val distSq = player.location.distanceSquared(entityLoc)
            val maxDistSq = maxDist * maxDist
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1905", "handlePlayerEntityEvent DEATH_NEARBY distance check", mapOf("questId" to model.id, "nodeId" to node.id, "distSq" to distSq, "maxDistSq" to maxDistSq, "inRange" to (distSq <= maxDistSq)))
            if (distSq > maxDistSq) return
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
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1942", "handlePlayerEntityEvent progress", mapOf("questId" to model.id, "nodeId" to node.id, "goalId" to goalId, "current" to current, "updated" to updated, "goal" to goal.goal))
            progressed = true
        }

        if (node.type == QuestObjectNodeType.PLAYER_TURTLES_BREED && !progressed) {
            net.nemoria.quest.core.DebugLog.log(
                "TURTLE_BREED_NO_PROGRESS player=${player.name} kind=$kind targetType=$targetType counted=${counted.size}"
            )
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1946", "handlePlayerEntityEvent TURTLE_BREED no progress", mapOf("questId" to model.id, "nodeId" to node.id, "kind" to kind.name, "targetType" to (targetType ?: "null"), "countedSize" to counted.size))
        }

        if (progressed && entityId != null) counted.add(entityId)

        val progMap = session.entityProgress[node.id] ?: return
        val allDone = goals.all { g -> (progMap[g.id.ifBlank { "default" }] ?: 0.0) >= g.goal }
        if (allDone) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1956", "handlePlayerEntityEvent all done", mapOf("questId" to model.id, "nodeId" to node.id, "goalsCount" to goals.size))
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
        val session = sessions[player.uniqueId]
        if (session == null) {
            return
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1982", "handleMovementEvent entry", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "delta" to delta, "vehicleType" to (vehicleType ?: "null"), "questId" to session.questId, "branchId" to session.branchId))
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isMovementNode(node.type)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:1992", "handleMovementEvent not movement node", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
            return
        }

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
        val updated = current + add
        session.movementProgress[node.id] = updated
        questService.saveNodeProgress(player, model.id, session.branchId, node.id, updated)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2057", "handleMovementEvent progress", mapOf("questId" to model.id, "nodeId" to node.id, "current" to current, "add" to add, "updated" to updated, "goal" to goal))
        if (updated >= goal) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2059", "handleMovementEvent goal reached", mapOf("questId" to model.id, "nodeId" to node.id, "updated" to updated, "goal" to goal))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2083", "handlePhysicalEvent entry", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "amount" to amount, "detail" to (detail ?: "null")))
        val session = sessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2089", "handlePhysicalEvent no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isPhysicalNode(node.type)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2093", "handlePhysicalEvent not physical node", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
            return
        }
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
        val updated = current + add
        session.physicalProgress[node.id] = updated
        questService.saveNodeProgress(player, model.id, session.branchId, node.id, updated)
        val targetReached = updated >= goal
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2170", "handlePhysicalEvent progress", mapOf("questId" to model.id, "nodeId" to node.id, "current" to current, "add" to add, "updated" to updated, "goal" to goal, "targetReached" to targetReached))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2215", "handleMiscEvent entry", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "detail" to (detail ?: "null")))
        val session = sessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2221", "handleMiscEvent no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return false
        }
        val model = questService.questInfo(session.questId) ?: return false
        val branch = model.branches[session.branchId] ?: return false
        val node = branch.objects[session.nodeId] ?: return false
        if (!isMiscNode(node.type)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2227", "handleMiscEvent not misc node", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
            return false
        }

        when (node.type) {
            QuestObjectNodeType.PLAYER_CHAT -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2552", "handleMiscEvent PLAYER_CHAT", mapOf("questId" to model.id, "nodeId" to node.id, "hasDetail" to (detail != null)))
                val text = detail
                if (text == null) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2553", "handleMiscEvent PLAYER_CHAT no text", mapOf("questId" to model.id, "nodeId" to node.id))
                    return false
                }
                if (!chatAllowed(node, text)) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2554", "handleMiscEvent PLAYER_CHAT not allowed", mapOf("questId" to model.id, "nodeId" to node.id, "text" to text.take(50)))
                    node.chatErrorMessage?.let { MessageFormatter.send(player, it) }
                    return true // cancel chat
                }
                if (!node.chatStoreVariable.isNullOrBlank()) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2564", "handleMiscEvent PLAYER_CHAT storing variable", mapOf("questId" to model.id, "nodeId" to node.id, "variable" to node.chatStoreVariable, "text" to text.take(50)))
                    questService.updateVariable(player, model.id, node.chatStoreVariable, text)
                }
                incrementMisc(node, session, player, model, 1.0)
                MessageFormatter.send(player, "<primary> [${player.name}]<secondary> $text")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2567", "handleMiscEvent PLAYER_CHAT processed", mapOf("questId" to model.id, "nodeId" to node.id, "text" to text.take(50)))
                return true // blokuj publiczny czat
            }
            QuestObjectNodeType.PLAYER_ACHIEVEMENT_AWARD -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2571", "handleMiscEvent PLAYER_ACHIEVEMENT_AWARD", mapOf("questId" to model.id, "nodeId" to node.id, "kind" to kind.name, "detail" to (detail ?: "null"), "expectedAchievementType" to (node.achievementType ?: "null")))
                if (kind != MiscEventType.ACHIEVEMENT) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2572", "handleMiscEvent PLAYER_ACHIEVEMENT_AWARD wrong kind", mapOf("questId" to model.id, "nodeId" to node.id, "kind" to kind.name))
                    return false
                }
                val ach = detail
                if (node.achievementType != null && ach != null && !node.achievementType.equals(ach, true)) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2574", "handleMiscEvent PLAYER_ACHIEVEMENT_AWARD type mismatch", mapOf("questId" to model.id, "nodeId" to node.id, "expected" to node.achievementType, "actual" to ach))
                    return false
                }
                incrementMisc(node, session, player, model, 1.0)
                return false
            }
            QuestObjectNodeType.PLAYER_CONNECT,
            QuestObjectNodeType.PLAYER_DISCONNECT,
            QuestObjectNodeType.PLAYER_RESPAWN -> {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2580", "handleMiscEvent CONNECT/DISCONNECT/RESPAWN", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name, "kind" to kind.name))
                val expected = when (node.type) {
                    QuestObjectNodeType.PLAYER_CONNECT -> MiscEventType.CONNECT
                    QuestObjectNodeType.PLAYER_DISCONNECT -> MiscEventType.DISCONNECT
                    QuestObjectNodeType.PLAYER_RESPAWN -> MiscEventType.RESPAWN
                    else -> kind
                }
                if (kind != expected) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2587", "handleMiscEvent CONNECT/DISCONNECT/RESPAWN kind mismatch", mapOf("questId" to model.id, "nodeId" to node.id, "expected" to expected.name, "actual" to kind.name))
                    return false
                }
                incrementMisc(node, session, player, model, 1.0)
                return false
            }
            else -> return false
        }
    }

    private fun incrementMisc(node: QuestObjectNode, session: BranchSession, player: org.bukkit.entity.Player, model: QuestModel, delta: Double) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2438", "incrementMisc entry", mapOf("questId" to model.id, "nodeId" to node.id, "delta" to delta, "playerUuid" to player.uniqueId.toString()))
        val goal = node.distanceGoal ?: node.count.toDouble().coerceAtLeast(1.0)
        val current = session.miscProgress.getOrDefault(node.id, 0.0)
        val updated = current + delta
        session.miscProgress[node.id] = updated
        questService.saveNodeProgress(player, model.id, session.branchId, node.id, updated)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2442", "incrementMisc progress", mapOf("questId" to model.id, "nodeId" to node.id, "current" to current, "delta" to delta, "updated" to updated, "goal" to goal))
        if (updated >= goal) {
            val gotoRaw = node.goto
            if (gotoRaw == null) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2443", "incrementMisc goal reached no goto", mapOf("questId" to model.id, "nodeId" to node.id))
                return
            }
            val target = normalizeTarget(gotoRaw)
            // Ustaw stan następnego węzła nawet jeśli gracz właśnie się rozłącza (np. DISCONNECT)
            session.nodeId = target
            questService.updateBranchState(player, model.id, session.branchId, target)
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2450", "incrementMisc goal reached", mapOf("questId" to model.id, "nodeId" to node.id, "target" to target, "playerOnline" to player.isOnline))
            if (player.isOnline) {
                runNode(player, model, session.branchId, target, 0)
            } else {
                // gracz offline: uruchom kolejny węzeł po powrocie na serwer
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2454", "incrementMisc player offline, scheduling", mapOf("questId" to model.id, "nodeId" to node.id, "target" to target))
                val offline = plugin.server.getOfflinePlayer(player.uniqueId)
                val task = object : BukkitRunnable() {
                    override fun run() {
                        val online = offline.player
                        if (online != null && online.isOnline) {
                            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2457", "incrementMisc player back online", mapOf("questId" to model.id, "nodeId" to node.id, "target" to target))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2601", "chatAllowed entry", mapOf("nodeId" to node.id, "textLength" to text.length, "text" to text.take(50), "minLength" to (node.chatMinLength ?: "null"), "maxLength" to (node.chatMaxLength ?: "null"), "whitelistSize" to node.chatWhitelist.size, "blacklistSize" to node.chatBlacklist.size, "hasRegex" to !node.chatRegex.isNullOrBlank()))
        if (node.chatMinLength != null && text.length < node.chatMinLength) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2602", "chatAllowed minLength failed", mapOf("nodeId" to node.id, "textLength" to text.length, "minLength" to node.chatMinLength))
            return false
        }
        if (node.chatMaxLength != null && text.length > node.chatMaxLength) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2603", "chatAllowed maxLength failed", mapOf("nodeId" to node.id, "textLength" to text.length, "maxLength" to node.chatMaxLength))
            return false
        }
        if (node.chatWhitelist.isNotEmpty() && node.chatWhitelist.none { it.equals(text, true) }) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2604", "chatAllowed whitelist failed", mapOf("nodeId" to node.id, "text" to text, "whitelist" to node.chatWhitelist))
            return false
        }
        if (node.chatBlacklist.isNotEmpty() && node.chatBlacklist.any { it.equals(text, true) }) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2605", "chatAllowed blacklist failed", mapOf("nodeId" to node.id, "text" to text, "blacklist" to node.chatBlacklist))
            return false
        }
        if (!node.chatRegex.isNullOrBlank() && !text.matches(node.chatRegex.toRegex())) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2606", "chatAllowed regex failed", mapOf("nodeId" to node.id, "text" to text, "regex" to node.chatRegex))
            return false
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2607", "chatAllowed allowed", mapOf("nodeId" to node.id, "text" to text.take(50)))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2314", "handlePlayerItemEvent entry", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "itemType" to (item?.type?.name ?: "null"), "inventoryType" to (inventoryType ?: "null"), "slot" to (slot ?: "null"), "villagerId" to (villagerId?.toString() ?: "null")))
        val session = sessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2320", "handlePlayerItemEvent no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        val model = questService.questInfo(session.questId) ?: return
        val branch = model.branches[session.branchId] ?: return
        val node = branch.objects[session.nodeId] ?: return
        if (!isPlayerItemNode(node.type)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2326", "handlePlayerItemEvent not item node", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name))
            return
        }

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
        if (!typeMatches) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2575", "handlePlayerItemEvent type mismatch", mapOf("questId" to model.id, "nodeId" to node.id, "nodeType" to node.type.name, "kind" to kind.name))
            return
        }

        if (!node.tradeAllowSameVillagers && villagerId != null) {
            val seen = session.villagerCounted.getOrPut(node.id) { mutableSetOf() }
            if (seen.contains(villagerId)) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2579", "handlePlayerItemEvent same villager not allowed", mapOf("questId" to model.id, "nodeId" to node.id, "villagerId" to villagerId.toString()))
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
        val delta = when {
            matchedItem != null && kind in setOf(
                ItemEventType.PICKUP,
                ItemEventType.ACQUIRE,
                ItemEventType.DROP,
                ItemEventType.CONTAINER_TAKE,
                ItemEventType.CONTAINER_PUT,
                ItemEventType.TRADE,
                ItemEventType.CRAFT,
                ItemEventType.REPAIR,
                ItemEventType.ENCHANT,
                ItemEventType.BREW,
                ItemEventType.MELT,
                ItemEventType.THROW,
                ItemEventType.INTERACT
            ) -> matchedItem.amount.toDouble().coerceAtLeast(1.0)
            else -> 1.0
        }
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
                    val updated = cur + delta
                    progMap[goal.id] = updated
                    questService.saveNodeProgress(player, model.id, session.branchId, node.id, updated, goal.id)
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2658", "handlePlayerItemEvent progress", mapOf("questId" to model.id, "nodeId" to node.id, "goalId" to goal.id, "current" to cur, "delta" to delta, "updated" to updated, "goal" to goal.goal))
                    progressed = true
                    if ((kind == ItemEventType.BREW || kind == ItemEventType.MELT) && DebugLog.enabled) {
                        DebugLog.log("ITEM_PROG kind=$kind player=${player.name} goal=${goal.id} new=$updated need=${goal.goal}")
                    }
                    if (goal.take && matchedItem != null) {
                        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2664", "handlePlayerItemEvent taking item", mapOf("questId" to model.id, "nodeId" to node.id, "itemType" to matchedItem.type.name, "amount" to matchedItem.amount))
                        removeOne(player, matchedItem)
                    }
                }
            }
        }

        if (node.type == QuestObjectNodeType.PLAYER_ITEMS_REQUIRE) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2671", "handlePlayerItemEvent checking REQUIRE", mapOf("questId" to model.id, "nodeId" to node.id))
            if (checkRequireSatisfied(player, goals, node.itemsRequired)) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2672", "handlePlayerItemEvent REQUIRE satisfied", mapOf("questId" to model.id, "nodeId" to node.id, "hasGoto" to (node.goto != null)))
                node.goto?.let { handleGoto(player, model, session.branchId, it, 0) }
            }
            return
        }

        if (!progressed) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2678", "handlePlayerItemEvent no progress", mapOf("questId" to model.id, "nodeId" to node.id))
            return
        }
        val progMap = session.itemProgress[node.id] ?: return
        val allDone = goals.all { g -> (progMap[g.id] ?: 0.0) >= g.goal }
        if (allDone) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2681", "handlePlayerItemEvent all done", mapOf("questId" to model.id, "nodeId" to node.id, "goalsCount" to goals.size))
            questService.clearNodeProgress(player, model.id, session.branchId, node.id)
            node.goto?.let { handleGoto(player, model, session.branchId, it, 0, node.id) }
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2770", "matchesItem entry", mapOf("goalType" to goal.type, "stackType" to stack.type.name, "goalPotionType" to (goal.potionType ?: "null")))
        if (!goal.type.equals(stack.type.name, true)) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2771", "matchesItem type mismatch", mapOf("goalType" to goal.type, "stackType" to stack.type.name))
            return false
        }
        if (goal.potionType != null) {
            val meta = stack.itemMeta as? org.bukkit.inventory.meta.PotionMeta
            if (meta == null) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2773", "matchesItem not potion meta", mapOf("goalPotionType" to goal.potionType))
                return false
            }
            val base = meta.basePotionType
            val baseName = base?.name
            val baseKey = base?.key?.key
            if (!(goal.potionType.equals(baseName, true) || goal.potionType.equals(baseKey, true))) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2777", "matchesItem potion type mismatch", mapOf("goalPotionType" to goal.potionType, "baseName" to (baseName ?: "null"), "baseKey" to (baseKey ?: "null")))
                return false
            }
        }
        // shallow checks only; ignore name/lore/customModelData for now
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2782", "matchesItem match", mapOf("goalType" to goal.type, "stackType" to stack.type.name))
        return true
    }

    private fun removeOne(player: org.bukkit.entity.Player, sample: ItemStack) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2785", "removeOne entry", mapOf("playerUuid" to player.uniqueId.toString(), "itemType" to sample.type.name, "amount" to sample.amount))
        val inv = player.inventory
        for (i in 0 until inv.size) {
            val it = inv.getItem(i) ?: continue
            if (it.type == sample.type) {
                val oldAmount = it.amount
                if (it.amount > 1) {
                    it.amount = it.amount - 1
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2790", "removeOne decreased", mapOf("itemType" to sample.type.name, "slot" to i, "oldAmount" to oldAmount, "newAmount" to it.amount))
                } else {
                    inv.clear(i)
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2793", "removeOne cleared", mapOf("itemType" to sample.type.name, "slot" to i))
                }
                return
            }
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2797", "removeOne not found", mapOf("itemType" to sample.type.name))
    }

    private fun checkRequireSatisfied(player: org.bukkit.entity.Player, goals: List<ItemGoal>, itemsReq: List<ItemStackConfig>): Boolean {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2800", "checkRequireSatisfied entry", mapOf("playerUuid" to player.uniqueId.toString(), "goalsCount" to goals.size, "itemsReqCount" to itemsReq.size))
        val inv = player.inventory
        val stacks = inv.contents.filterNotNull()
        if (itemsReq.isNotEmpty()) {
            val ok = itemsReq.all { cfg ->
                stacks.any { it.type.name.equals(cfg.type, true) && it.amount >= 1 }
            }
            DebugLog.log("REQUIRE_CHECK itemsReq=${itemsReq.map { it.type }} ok=$ok")
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2807", "checkRequireSatisfied itemsReq result", mapOf("itemsReq" to itemsReq.map { it.type }, "ok" to ok))
            return ok
        }
        val result = goals.all { g ->
            val needed = g.goal.toInt()
            val matchedCount = if (g.items.isEmpty()) stacks.sumOf { it.amount } else {
                stacks.filter { st -> g.items.any { it.type.equals(st.type.name, true) } }.sumOf { it.amount }
            }
            DebugLog.log("REQUIRE_CHECK goal=${g.id} needed=$needed matched=$matchedCount items=${g.items.map { it.type }}")
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2814", "checkRequireSatisfied goal check", mapOf("goalId" to g.id, "needed" to needed, "matchedCount" to matchedCount, "items" to g.items.map { it.type }, "satisfied" to (matchedCount >= needed)))
            matchedCount >= needed
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2816", "checkRequireSatisfied result", mapOf("result" to result))
        return result
    }

    private fun sendSyntheticMessage(player: org.bukkit.entity.Player, component: Component, trackSynthetic: Boolean = false) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2919", "sendSyntheticMessage entry", mapOf("playerUuid" to player.uniqueId.toString(), "trackSynthetic" to trackSynthetic, "componentText" to PlainTextComponentSerializer.plainText().serialize(component).take(100)))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2930", "replayHistory entry", mapOf("playerUuid" to player.uniqueId.toString(), "historySize" to history.size, "trackSynthetic" to trackSynthetic))
        history.forEach { comp -> sendSyntheticMessage(player, comp, trackSynthetic) }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2933", "replayHistory completed", mapOf("playerUuid" to player.uniqueId.toString(), "historySize" to history.size))
    }

    private fun pickEntries(entries: List<QuestItemEntry>, count: Int): List<QuestItemEntry> {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2934", "pickEntries entry", mapOf("entriesCount" to entries.size, "count" to count))
        if (entries.isEmpty()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2935", "pickEntries empty entries", mapOf())
            return emptyList()
        }
        if (count <= 0) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2936", "pickEntries count <= 0", mapOf("count" to count))
            return emptyList()
        }
        val result = if (count >= entries.size) entries else entries.shuffled().take(count)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2938", "pickEntries result", mapOf("entriesCount" to entries.size, "count" to count, "resultCount" to result.size))
        return result
    }

    private fun buildItem(entry: QuestItemEntry): ItemStack? {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2844", "buildItem entry", mapOf("entryType" to entry.type, "entryAmount" to entry.amount))
        val mat = runCatching { org.bukkit.Material.valueOf(entry.type.uppercase()) }.getOrNull()
        if (mat == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2845", "buildItem invalid material", mapOf("entryType" to entry.type))
            return null
        }
        val stack = ItemStack(mat, entry.amount)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2846", "buildItem success", mapOf("entryType" to entry.type, "material" to mat.name, "amount" to entry.amount))
        return stack
    }

    private fun giveOrDropItems(player: org.bukkit.entity.Player, node: QuestObjectNode, drop: Boolean) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2808", "giveOrDropItems entry", mapOf("playerUuid" to player.uniqueId.toString(), "drop" to drop, "itemsCount" to node.items.size, "count" to node.count))
        val selection = pickEntries(node.items, node.count)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2809", "giveOrDropItems selection", mapOf("selectionCount" to selection.size))
        selection.forEach { entry ->
            val stack = buildItem(entry)
            if (stack == null) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2811", "giveOrDropItems buildItem failed", mapOf("entryType" to entry.type, "entryAmount" to entry.amount))
                return@forEach
            }
            if (drop) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2813", "giveOrDropItems dropping", mapOf("itemType" to stack.type.name, "amount" to stack.amount))
                player.world.dropItemNaturally(player.location, stack)
            } else {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2815", "giveOrDropItems giving", mapOf("itemType" to stack.type.name, "amount" to stack.amount))
                player.inventory.addItem(stack)
            }
        }
    }

    private fun evalFormula(formula: String?, current: Double = 0.0): Double {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2989", "evalFormula entry", mapOf("formula" to (formula ?: "null"), "current" to current))
        if (formula.isNullOrBlank()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2990", "evalFormula no formula", mapOf("current" to current))
            return current
        }
        val prepared = formula.replace("{value}", current.toString())
        var total = 0.0
        val parts = prepared.split("+")
        for (part in parts) {
            val num = part.trim().toDoubleOrNull() ?: continue
            total += num
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2998", "evalFormula result", mapOf("formula" to formula, "current" to current, "prepared" to prepared, "result" to total))
        return total
    }

    private fun resolvePoints(player: OfflinePlayer, category: String?): Double {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3001", "resolvePoints entry", mapOf("playerUuid" to player.uniqueId.toString(), "category" to (category ?: "null")))
        val catKey = category ?: "global"
        val questId = sessions[player.uniqueId]?.questId ?: ""
        val data = questService.progress(player)[questId]?.variables ?: emptyMap()
        val points = data["points:$catKey"]?.toDoubleOrNull() ?: 0.0
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3004", "resolvePoints result", mapOf("playerUuid" to player.uniqueId.toString(), "category" to catKey, "questId" to questId, "points" to points))
        return points
    }

    private fun updatePoints(player: OfflinePlayer, category: String?, value: Double) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3007", "updatePoints entry", mapOf("playerUuid" to player.uniqueId.toString(), "category" to (category ?: "null"), "value" to value))
        val catKey = category ?: "global"
        val questId = sessions[player.uniqueId]?.questId
        if (questId == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3009", "updatePoints no questId", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3009", "updatePoints updating", mapOf("playerUuid" to player.uniqueId.toString(), "category" to catKey, "questId" to questId, "value" to value))
        questService.updateVariable(player, questId, "points:$catKey", value.toLong().toString())
    }

    private fun takeItems(player: org.bukkit.entity.Player, node: QuestObjectNode): Int {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2843", "takeItems entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "itemsCount" to node.items.size, "count" to node.count))
        val selection = pickEntries(node.items, node.count)
        var taken = 0
        selection.forEach { entry ->
            val mat = runCatching { org.bukkit.Material.valueOf(entry.type.uppercase()) }.getOrNull()
            if (mat == null) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2847", "takeItems invalid material", mapOf("entryType" to entry.type))
                return@forEach
            }
            val required = entry.amount
            val inv = player.inventory
            val has = inv.contents.filterNotNull().filter { it.type == mat }.sumOf { it.amount }
            if (has < required) {
                DebugLog.log("ItemsTake quest=${node.id} missing material=$mat has=$has need=$required")
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2851", "takeItems insufficient items", mapOf("nodeId" to node.id, "material" to mat.name, "has" to has, "required" to required))
                return@forEach
            }
            val removed = inv.removeItemAnySlot(ItemStack(mat, required))
            val leftover = removed.values.sumOf { it.amount }
            val actuallyTaken = required - leftover
            taken += actuallyTaken
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2855", "takeItems taken", mapOf("nodeId" to node.id, "material" to mat.name, "required" to required, "leftover" to leftover, "actuallyTaken" to actuallyTaken))
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2859", "takeItems result", mapOf("nodeId" to node.id, "totalTaken" to taken))
        return taken
    }

    private fun modifyItems(player: org.bukkit.entity.Player, node: QuestObjectNode): Int {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2862", "modifyItems entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "itemsCount" to node.items.size, "count" to node.count, "hasModifyOptions" to (node.modifyOptions != null)))
        val opts = node.modifyOptions
        if (opts == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2863", "modifyItems no options", mapOf("nodeId" to node.id))
            return 0
        }
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3002", "modifyItems result", mapOf("nodeId" to node.id, "modifiedCount" to modified))
        return modified
    }

    private fun placeBlocks(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3005", "placeBlocks entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "blockType" to (node.blockType ?: "null")))
        val mat = node.blockType?.let { runCatching { org.bukkit.Material.valueOf(it.uppercase()) }.getOrNull() }
        if (mat == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3006", "placeBlocks invalid blockType", mapOf("nodeId" to node.id, "blockType" to (node.blockType ?: "null")))
            return
        }
        val target = resolvePosition(player, node.position)
        val block = target.block
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3008", "placeBlocks placing", mapOf("nodeId" to node.id, "material" to mat.name, "targetWorld" to (target.world?.name ?: "null"), "targetX" to target.blockX, "targetY" to target.blockY, "targetZ" to target.blockZ))
        block.type = mat
        // block_states ignored for now
    }

    private fun createExplosions(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3028", "createExplosions entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "explosionPower" to (node.explosionPower ?: 2.0), "countOverride" to (node.countOverride ?: 1), "allowDamage" to node.allowDamage))
        val center = resolvePosition(player, node.position)
        val power = (node.explosionPower ?: 2.0).toFloat()
        val cnt = node.countOverride ?: 1
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3032", "createExplosions creating", mapOf("nodeId" to node.id, "count" to cnt, "power" to power, "centerWorld" to (center.world?.name ?: "null"), "centerX" to center.x, "centerY" to center.y, "centerZ" to center.z))
        repeat(cnt) {
            center.world.createExplosion(center, power, node.allowDamage, node.allowDamage, player)
        }
    }

    private fun launchFireworks(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3037", "launchFireworks entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "countOverride" to (node.countOverride ?: 1), "effectsCount" to node.effectList.size))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3056", "launchFireworks launched", mapOf("nodeId" to node.id, "count" to cnt, "centerWorld" to (center.world?.name ?: "null")))
    }

    private fun lightningStrikes(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3059", "lightningStrikes entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "countOverride" to (node.countOverride ?: 1), "allowDamage" to node.allowDamage))
        val center = resolvePosition(player, node.position)
        val cnt = node.countOverride ?: 1
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3062", "lightningStrikes striking", mapOf("nodeId" to node.id, "count" to cnt, "allowDamage" to node.allowDamage, "centerWorld" to (center.world?.name ?: "null")))
        repeat(cnt) {
            if (node.allowDamage) center.world.strikeLightning(center)
            else center.world.strikeLightningEffect(center)
        }
    }

    private fun awardAchievement(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3068", "awardAchievement entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "achievementType" to (node.achievementType ?: "null")))
        val advId = node.achievementType
        if (advId == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3069", "awardAchievement no achievementType", mapOf("nodeId" to node.id))
            return
        }
        val key = NamespacedKey.fromString(advId)
        if (key == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3070", "awardAchievement invalid key", mapOf("nodeId" to node.id, "advId" to advId))
            return
        }
        val adv = plugin.server.advancementIterator().asSequence().firstOrNull { it.key() == key }
        if (adv == null) {
            DebugLog.log("Advancement not found id=$advId")
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3072", "awardAchievement not found", mapOf("nodeId" to node.id, "advId" to advId))
            return
        }
        val progress = player.getAdvancementProgress(adv)
        val remainingCount = progress.remainingCriteria.size
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3077", "awardAchievement awarding", mapOf("nodeId" to node.id, "advId" to advId, "remainingCriteriaCount" to remainingCount))
        progress.remainingCriteria.forEach { crit -> progress.awardCriteria(crit) }
    }

    private fun toggleCameraMode(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3080", "toggleCameraMode entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "cameraToggle" to (node.cameraToggle ?: true)))
        val toggle = node.cameraToggle ?: true
        val key = NamespacedKey(plugin, "prev_gamemode")
        if (toggle) {
            if (!player.persistentDataContainer.has(key, PersistentDataType.STRING)) {
                player.persistentDataContainer.set(key, PersistentDataType.STRING, player.gameMode.name)
            }
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3087", "toggleCameraMode enabling", mapOf("nodeId" to node.id, "previousGameMode" to player.gameMode.name))
            player.gameMode = GameMode.SPECTATOR
        } else {
            val prev = player.persistentDataContainer.get(key, PersistentDataType.STRING)
            val gm = prev?.let { runCatching { GameMode.valueOf(it) }.getOrNull() } ?: GameMode.SURVIVAL
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3090", "toggleCameraMode disabling", mapOf("nodeId" to node.id, "previousGameMode" to (prev ?: "null"), "restoredGameMode" to gm.name))
            player.gameMode = gm
            player.persistentDataContainer.remove(key)
        }
    }

    private fun openDivergeGui(player: org.bukkit.entity.Player, model: QuestModel, branchId: String, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3096", "openDivergeGui entry", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to model.id, "branchId" to branchId, "nodeId" to node.id, "choicesCount" to node.divergeChoices.size))
        val choices = node.divergeChoices
        if (choices.isEmpty()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3098", "openDivergeGui no choices", mapOf("questId" to model.id, "nodeId" to node.id))
            node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            return
        }
        val maxSlot = choices.maxOf { it.slot }.coerceAtLeast(8)
        val size = (((maxSlot / 9) + 1) * 9).coerceAtMost(54)
        val inv = plugin.server.createInventory(null, size, MessageFormatter.format("Wybierz opcję"))
        val counts = questService.progress(player)?.get(model.id)?.divergeCounts ?: emptyMap()
        var availableCount = 0
        choices.forEach { ch ->
            val key = "${node.id}:${ch.id}".lowercase()
            val used = counts[key] ?: 0
            val available = used < ch.maxCompletions && questService.checkConditions(player, ch.conditions, model.id)
            if (available) availableCount++
            val itemCfg = when {
                !available && ch.unavailableItem != null -> ch.unavailableItem
                used > 0 && ch.redoItem != null -> ch.redoItem
                else -> ch.item
            } ?: ItemStackConfig("BARRIER", "&cNiedostępne", emptyList(), null)
            buildItem(itemCfg)?.let { inv.setItem(ch.slot, it) }
        }
        guiSessions[player.uniqueId] = DivergeGuiSession(model.id, branchId, node.id, choices, node.divergeReopenDelayTicks)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3116", "openDivergeGui opened", mapOf("questId" to model.id, "nodeId" to node.id, "size" to size, "availableCount" to availableCount, "totalCount" to choices.size))
        player.openInventory(inv)
    }

    internal fun handleDivergeGuiClick(player: org.bukkit.entity.Player, inv: org.bukkit.inventory.Inventory, rawSlot: Int): Boolean {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3019", "handleDivergeGuiClick entry", mapOf("playerUuid" to player.uniqueId.toString(), "rawSlot" to rawSlot, "invSize" to inv.size))
        val sess = guiSessions[player.uniqueId]
        if (sess == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3020", "handleDivergeGuiClick no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return false
        }
        if (rawSlot < 0 || rawSlot >= inv.size) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3021", "handleDivergeGuiClick invalid slot", mapOf("rawSlot" to rawSlot, "invSize" to inv.size))
            return false
        }
        val choice = sess.choices.firstOrNull { it.slot == rawSlot }
        if (choice == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3022", "handleDivergeGuiClick no choice for slot", mapOf("rawSlot" to rawSlot))
            return false
        }
        val model = questService.questInfo(sess.questId) ?: return false
        val key = "${sess.nodeId}:${choice.id}".lowercase()
        val used = questService.progress(player)[sess.questId]?.divergeCounts?.get(key) ?: 0
        val available = used < choice.maxCompletions && questService.checkConditions(player, choice.conditions, sess.questId)
        if (!available) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3027", "handleDivergeGuiClick choice not available", mapOf("questId" to sess.questId, "nodeId" to sess.nodeId, "choiceId" to choice.id, "used" to used, "maxCompletions" to choice.maxCompletions))
            return true
        }
        sess.selected = true
        questService.mutateProgress(player, sess.questId) { prog ->
            prog.divergeCounts[key] = used + 1
        }
        player.closeInventory()
        val goto = choice.goto ?: model.branches[sess.branchId]?.objects?.get(choice.objRef ?: "")?.goto
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3033", "handleDivergeGuiClick choice selected", mapOf("questId" to sess.questId, "nodeId" to sess.nodeId, "choiceId" to choice.id, "goto" to (goto ?: "null")))
        if (goto != null) {
            handleGoto(player, model, sess.branchId, goto, 0, sess.nodeId)
        }
        guiSessions.remove(player.uniqueId)
        return true
    }

    internal fun handleDivergeGuiClose(player: org.bukkit.entity.Player, inv: org.bukkit.inventory.Inventory) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3041", "handleDivergeGuiClose entry", mapOf("playerUuid" to player.uniqueId.toString()))
        val sess = guiSessions[player.uniqueId]
        if (sess == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3042", "handleDivergeGuiClose no session", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        if (sess.selected) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3043", "handleDivergeGuiClose already selected", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to sess.questId))
            guiSessions.remove(player.uniqueId)
            return
        }
        val delay = sess.reopenDelay
        if (delay == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3047", "handleDivergeGuiClose no reopen delay", mapOf("playerUuid" to player.uniqueId.toString()))
            return
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3048", "handleDivergeGuiClose scheduling reopen", mapOf("playerUuid" to player.uniqueId.toString(), "delay" to delay))
        object : BukkitRunnable() {
            override fun run() {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3051", "handleDivergeGuiClose reopening", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to sess.questId))
                val model = questService.questInfo(sess.questId) ?: return
                openDivergeGui(player, model, sess.branchId, model.branches[sess.branchId]?.objects?.get(sess.nodeId) ?: return)
            }
        }.runTaskLater(plugin, delay)
    }

    private fun startDivergeObjects(player: OfflinePlayer, model: QuestModel, branchId: String, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2649", "startDivergeObjects entry", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to node.id, "divergeChoicesCount" to node.divergeChoices.size))
        val p = player.player
        if (p == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2650", "startDivergeObjects player offline", mapOf("questId" to model.id, "nodeId" to node.id))
            return
        }
        val choices = node.divergeChoices.filter { ch ->
            questService.checkConditions(p, ch.conditions, model.id) &&
                (questService.progress(player)[model.id]?.divergeCounts?.get("${node.id}:${ch.id}".lowercase()) ?: 0) < ch.maxCompletions
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2654", "startDivergeObjects filtered choices", mapOf("questId" to model.id, "nodeId" to node.id, "filteredCount" to choices.size, "totalCount" to node.divergeChoices.size))
        val first = choices.firstOrNull()
        if (first == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2655", "startDivergeObjects no valid choices", mapOf("questId" to model.id, "nodeId" to node.id))
            node.goto?.let { handleGoto(player, model, branchId, it, 0, node.id) }
            return
        }
        val session = sessions[p.uniqueId] ?: BranchSession(model.id, branchId, node.id).also { sessions[p.uniqueId] = it }
        session.divergeObjectMap.clear()
        choices.forEach { ch ->
            ch.objRef?.let { objId -> session.divergeObjectMap[objId] = ch }
        }
        val targetObj = first.objRef
        if (targetObj == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2664", "startDivergeObjects no objRef", mapOf("questId" to model.id, "nodeId" to node.id))
            return
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2665", "startDivergeObjects running node", mapOf("questId" to model.id, "nodeId" to node.id, "targetObj" to targetObj, "mappedChoicesCount" to session.divergeObjectMap.size))
        runNode(player, model, branchId, targetObj, 0)
    }

    private fun buildItem(cfg: ItemStackConfig): ItemStack? {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3314", "buildItem entry", mapOf("itemType" to cfg.type, "hasName" to (cfg.name != null), "loreCount" to cfg.lore.size, "customModelData" to (cfg.customModelData ?: "null")))
        val mat = runCatching { org.bukkit.Material.valueOf(cfg.type.uppercase()) }.getOrNull()
        if (mat == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3315", "buildItem invalid material", mapOf("itemType" to cfg.type))
            return null
        }
        val stack = ItemStack(mat)
        val meta = stack.itemMeta
        if (meta == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3317", "buildItem no itemMeta", mapOf("itemType" to cfg.type))
            return null
        }
        cfg.name?.let { meta.setDisplayName(MessageFormatter.format(it)) }
        if (cfg.lore.isNotEmpty()) meta.lore = cfg.lore.map { MessageFormatter.format(it) }
        cfg.customModelData?.let { meta.setCustomModelData(it) }
        stack.itemMeta = meta
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3322", "buildItem success", mapOf("itemType" to cfg.type, "material" to mat.name))
        return stack
    }

    private fun giveEffects(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3325", "giveEffects entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "effectsCount" to node.effectList.size, "countOverride" to (node.countOverride ?: "null")))
        val effects = if (node.effectList.isNotEmpty()) node.effectList else listOf()
        val selection = if (node.countOverride != null && node.countOverride!! < effects.size) effects.shuffled().take(node.countOverride!!) else effects
        var givenCount = 0
        selection.forEach { eff ->
            val parts = eff.split("\\s+".toRegex())
            val type = parts.getOrNull(0)?.let { PotionEffectType.getByName(it.uppercase()) }
            if (type == null) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3330", "giveEffects invalid effect type", mapOf("nodeId" to node.id, "effectStr" to eff))
                return@forEach
            }
            val amp = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val ticks = parts.getOrNull(2)?.toIntOrNull() ?: 200
            player.addPotionEffect(PotionEffect(type, ticks, amp))
            givenCount++
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3335", "giveEffects result", mapOf("nodeId" to node.id, "givenCount" to givenCount, "selectionCount" to selection.size))
    }

    private fun removeEffects(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3337", "removeEffects entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "effectsCount" to node.effectList.size, "countOverride" to (node.countOverride ?: "null")))
        val effects = if (node.effectList.isNotEmpty()) node.effectList else listOf()
        val selection = if (node.countOverride != null && node.countOverride!! < effects.size) effects.shuffled().take(node.countOverride!!) else effects
        var removedCount = 0
        selection.forEach { eff ->
            val type = PotionEffectType.getByName(eff.uppercase())
            if (type == null) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3341", "removeEffects invalid effect type", mapOf("nodeId" to node.id, "effectStr" to eff))
                return@forEach
            }
            player.removePotionEffect(type)
            removedCount++
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3344", "removeEffects result", mapOf("nodeId" to node.id, "removedCount" to removedCount, "selectionCount" to selection.size))
    }

    private fun resolvePosition(player: org.bukkit.entity.Player, target: PositionTarget?): org.bukkit.Location {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3374", "resolvePosition entry", mapOf("playerUuid" to player.uniqueId.toString(), "hasTarget" to (target != null), "targetWorld" to (target?.world ?: "null"), "targetX" to (target?.x ?: "null"), "targetY" to (target?.y ?: "null"), "targetZ" to (target?.z ?: "null")))
        if (target == null) {
            val loc = player.location
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3375", "resolvePosition no target, using player location", mapOf("world" to (loc.world?.name ?: "null"), "x" to loc.x, "y" to loc.y, "z" to loc.z))
            return loc
        }
        val world = target.world?.let { plugin.server.getWorld(it) } ?: player.world
        val x = target.x ?: player.location.x
        val y = target.y ?: player.location.y
        val z = target.z ?: player.location.z
        val result = org.bukkit.Location(world, x, y, z)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3380", "resolvePosition result", mapOf("world" to (world.name ?: "null"), "x" to x, "y" to y, "z" to z))
        return result
    }

    private fun normalizeTreeType(raw: String?): String? {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3524", "normalizeTreeType entry", mapOf("raw" to (raw ?: "null")))
        val up = raw?.uppercase()
        if (up == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3525", "normalizeTreeType null raw", mapOf())
            return null
        }
        val result = when (up) {
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3538", "normalizeTreeType result", mapOf("raw" to raw, "normalized" to result))
        return result
    }

    private fun matchEntity(entity: org.bukkit.entity.Entity, goal: EntityGoal): Boolean {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3541", "matchEntity entry", mapOf("entityType" to entity.type.name, "goalId" to goal.id, "goalTypes" to goal.types, "goalNames" to goal.names, "goalColors" to goal.colors, "goalHorseColors" to goal.horseColors, "goalHorseStyles" to goal.horseStyles))
        val typeOk = goal.types.isEmpty() || goal.types.any { t -> entity.type.name.equals(t, true) }
        val nameOk = goal.names.isEmpty() || run {
            val name = entity.customName()?.let { PlainTextComponentSerializer.plainText().serialize(it) }?.lowercase()
            name != null && goal.names.any { it.equals(name, true) }
        }
        val allowedColors = goal.colors.mapNotNull { runCatching { org.bukkit.DyeColor.valueOf(it.uppercase()) }.getOrNull() }
        val entityColor = when (entity) {
            is Sheep -> entity.color
            else -> null
        }
        val colorOk = allowedColors.isEmpty() || (entityColor != null && entityColor in allowedColors)
        val horseOk = if (goal.horseColors.isEmpty() && goal.horseStyles.isEmpty()) {
            true
        } else {
            if (entity !is Horse) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3556", "matchEntity not horse", mapOf("entityType" to entity.type.name, "goalId" to goal.id))
                return false
            }
            val hColorOk = goal.horseColors.isEmpty() || goal.horseColors.any { runCatching { Horse.Color.valueOf(it.uppercase()) }.getOrNull() == entity.color }
            val hStyleOk = goal.horseStyles.isEmpty() || goal.horseStyles.any { runCatching { Horse.Style.valueOf(it.uppercase()) }.getOrNull() == entity.style }
            hColorOk && hStyleOk
        }
        val result = typeOk && nameOk && colorOk && horseOk
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3561", "matchEntity result", mapOf("entityType" to entity.type.name, "goalId" to goal.id, "typeOk" to typeOk, "nameOk" to nameOk, "colorOk" to colorOk, "horseOk" to horseOk, "result" to result))
        return result
    }

    private fun processEntities(player: OfflinePlayer, questId: String, node: QuestObjectNode, action: (org.bukkit.entity.LivingEntity, Double) -> Unit): Int {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3181", "processEntities entry", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "nodeId" to node.id, "hasPosition" to (node.position != null), "radius" to (node.position?.radius ?: 8.0)))
        val p = player.player
        if (p == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3182", "processEntities player offline", mapOf("questId" to questId, "nodeId" to node.id))
            return 0
        }
        val center = resolvePosition(p, node.position)
        val radius = node.position?.radius ?: 8.0
        var processed = 0
        val goals = if (node.goals.isNotEmpty()) node.goals else listOf(EntityGoal(goal = node.count.toDouble()))
        goals.forEach { goal ->
            val targetCount = goal.goal.toInt().coerceAtLeast(1)
            val nearby = center.world.getNearbyEntities(center, radius, radius, radius)
                .filterIsInstance<org.bukkit.entity.LivingEntity>()
                .filter { matchEntity(it, goal) }
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3191", "processEntities found entities", mapOf("questId" to questId, "nodeId" to node.id, "goalId" to goal.id, "nearbyCount" to nearby.size, "targetCount" to targetCount))
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
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3202", "processEntities result", mapOf("questId" to questId, "nodeId" to node.id, "processedCount" to processed))
        return processed
    }

    private fun spawnEntities(player: OfflinePlayer, questId: String, node: QuestObjectNode): Int {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3205", "spawnEntities entry", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "nodeId" to node.id, "hasPosition" to (node.position != null), "goalsCount" to node.goals.size))
        val p = player.player
        if (p == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3206", "spawnEntities player offline", mapOf("questId" to questId, "nodeId" to node.id))
            return 0
        }
        val center = resolvePosition(p, node.position)
        val goals = if (node.goals.isNotEmpty()) node.goals else listOf(EntityGoal(types = listOf("ZOMBIE"), goal = node.count.toDouble()))
        var spawned = 0
        goals.forEach { goal ->
            val count = goal.goal.toInt().coerceAtLeast(1)
            val types = if (goal.types.isNotEmpty()) goal.types else listOf("ZOMBIE")
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3212", "spawnEntities spawning goal", mapOf("questId" to questId, "nodeId" to node.id, "goalId" to goal.id, "count" to count, "types" to types))
            repeat(count) {
                val typeName = types.getOrNull(it % types.size) ?: types.first()
                val etype = runCatching { org.bukkit.entity.EntityType.valueOf(typeName.uppercase()) }.getOrNull()
                if (etype == null) {
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3215", "spawnEntities invalid entity type", mapOf("questId" to questId, "nodeId" to node.id, "typeName" to typeName))
                    return@repeat
                }
                val ent = center.world.spawnEntity(center, etype)
                if (ent is org.bukkit.entity.LivingEntity) {
                    if (goal.names.isNotEmpty()) ent.customName(Component.text(goal.names.first()))
                    if (node.linkToQuest) tagEntity(ent, questId)
                }
                spawned++
            }
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3224", "spawnEntities result", mapOf("questId" to questId, "nodeId" to node.id, "spawnedCount" to spawned))
        return spawned
    }

    private fun teleportEntities(player: OfflinePlayer, questId: String, node: QuestObjectNode): Int {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3227", "teleportEntities entry", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "nodeId" to node.id, "hasPosition" to (node.position != null), "hasTeleportPosition" to (node.teleportPosition != null)))
        val p = player.player
        if (p == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3228", "teleportEntities player offline", mapOf("questId" to questId, "nodeId" to node.id))
            return 0
        }
        val center = resolvePosition(p, node.position)
        val target = resolvePosition(p, node.teleportPosition ?: node.position)
        val radius = node.position?.radius ?: 8.0
        val goals = if (node.goals.isNotEmpty()) node.goals else listOf(EntityGoal(goal = node.count.toDouble()))
        var processed = 0
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3234", "teleportEntities positions", mapOf("questId" to questId, "nodeId" to node.id, "centerWorld" to (center.world?.name ?: "null"), "targetWorld" to (target.world?.name ?: "null"), "radius" to radius))
        goals.forEach { goal ->
            val limit = goal.goal.toInt().coerceAtLeast(1)
            val nearby = center.world.getNearbyEntities(center, radius, radius, radius)
                .filterIsInstance<org.bukkit.entity.LivingEntity>()
                .filter { matchEntity(it, goal) }
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3240", "teleportEntities found entities", mapOf("questId" to questId, "nodeId" to node.id, "goalId" to goal.id, "nearbyCount" to nearby.size, "limit" to limit))
            for (ent in nearby) {
                if (processed >= limit) break
                ent.teleport(target)
                processed++
            }
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3245", "teleportEntities result", mapOf("questId" to questId, "nodeId" to node.id, "processedCount" to processed))
        return processed
    }

    private fun killLinked(player: OfflinePlayer, questId: String, node: QuestObjectNode): Int {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3516", "killLinked entry", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "nodeId" to node.id, "hasPosition" to (node.position != null), "radius" to (node.position?.radius ?: 8.0)))
        val p = player.player
        if (p == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3517", "killLinked player offline", mapOf("questId" to questId, "nodeId" to node.id))
            return 0
        }
        val center = resolvePosition(p, node.position)
        val radius = node.position?.radius ?: 8.0
        val key = questLinkKey()
        val nearby = center.world.getNearbyEntities(center, radius, radius, radius)
            .filterIsInstance<org.bukkit.entity.LivingEntity>()
            .filter { ent ->
                ent.persistentDataContainer.has(key, PersistentDataType.STRING) &&
                    ent.persistentDataContainer.get(key, PersistentDataType.STRING) == questId
            }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3527", "killLinked found entities", mapOf("questId" to questId, "nodeId" to node.id, "foundCount" to nearby.size, "centerWorld" to (center.world?.name ?: "null")))
        nearby.forEach { it.remove() }
        return nearby.size
    }

    private fun tagEntity(entity: org.bukkit.entity.LivingEntity, questId: String) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3712", "tagEntity entry", mapOf("entityType" to entity.type.name, "entityUuid" to entity.uniqueId.toString(), "questId" to questId))
        entity.persistentDataContainer.set(questLinkKey(), PersistentDataType.STRING, questId)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3713", "tagEntity tagged", mapOf("entityType" to entity.type.name, "entityUuid" to entity.uniqueId.toString(), "questId" to questId))
    }

    private fun questLinkKey(): NamespacedKey =
        NamespacedKey(plugin, "quest_link")

    private fun BranchSession.findGroupByChild(childId: String): GroupState? =
        groupChildMap[childId]

    private fun findGroupForChild(player: OfflinePlayer, questId: String, childId: String): Pair<String, GroupProgress>? {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3722", "findGroupForChild entry", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "childId" to childId))
        val gp = questService.progress(player)[questId]?.groupState
        if (gp == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3723", "findGroupForChild no groupState", mapOf("questId" to questId, "childId" to childId))
            return null
        }
        gp.forEach { (parent, state) ->
            if (state.remaining.contains(childId) || state.completed.contains(childId)) {
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3725", "findGroupForChild found", mapOf("questId" to questId, "childId" to childId, "parentId" to parent, "inRemaining" to state.remaining.contains(childId), "inCompleted" to state.completed.contains(childId)))
                return parent to state
            }
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3727", "findGroupForChild not found", mapOf("questId" to questId, "childId" to childId, "groupsCount" to gp.size))
        return null
    }

    private fun sendStartNotify(player: org.bukkit.entity.Player, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3613", "sendStartNotify entry", mapOf("playerUuid" to player.uniqueId.toString(), "nodeId" to node.id, "hasStartNotify" to (node.startNotify != null), "hasTitle" to (node.title != null)))
        node.startNotify?.let { notify ->
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3614", "sendStartNotify sending messages", mapOf("nodeId" to node.id, "messagesCount" to notify.message.size, "sound" to (notify.sound ?: "null")))
            notify.message.forEach { msg -> MessageFormatter.send(player, msg) }
            notify.sound?.let { snd ->
                runCatching { Sound.valueOf(snd.uppercase()) }.onSuccess { 
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3617", "sendStartNotify playing sound", mapOf("nodeId" to node.id, "sound" to it.name))
                    player.playSound(player.location, it, 1f, 1f) 
                }
            }
        }
        node.title?.let { t ->
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3620", "sendStartNotify sending title", mapOf("nodeId" to node.id, "title" to (t.title ?: ""), "subtitle" to (t.subtitle ?: ""), "fadeIn" to t.fadeIn, "stay" to t.stay, "fadeOut" to t.fadeOut))
            val title = MessageFormatter.format(t.title ?: "")
            val subtitle = MessageFormatter.format(t.subtitle ?: "")
            player.sendTitle(title, subtitle, t.fadeIn, t.stay, t.fadeOut)
        }
    }

    private fun renderRaw(text: String, model: QuestModel, player: OfflinePlayer): String =
        questService.renderPlaceholders(text, model.id, player)

    private fun playSound(player: org.bukkit.entity.Player, payload: String) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3630", "playSound entry", mapOf("playerUuid" to player.uniqueId.toString(), "payload" to payload))
        val parts = payload.split("\\s+".toRegex())
        val sound = runCatching { Sound.valueOf(parts.getOrNull(0)?.uppercase() ?: "") }.getOrNull()
        if (sound == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3632", "playSound invalid sound", mapOf("payload" to payload, "soundStr" to parts.getOrNull(0)))
            return
        }
        val volume = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
        val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3635", "playSound playing", mapOf("sound" to sound.name, "volume" to volume, "pitch" to pitch))
        player.playSound(player.location, sound, volume, pitch)
    }

    private fun spawnParticles(player: org.bukkit.entity.Player, payload: String, allPlayers: Boolean = false) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3574", "spawnParticles entry", mapOf("playerUuid" to player.uniqueId.toString(), "payload" to payload, "allPlayers" to allPlayers))
        val parts = payload.split("\\s+".toRegex())
        val particle = runCatching { Particle.valueOf(parts.getOrNull(0)?.uppercase() ?: "") }.getOrNull()
        if (particle == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3576", "spawnParticles invalid particle", mapOf("payload" to payload, "particleStr" to parts.getOrNull(0)))
            return
        }
        val amount = parts.getOrNull(1)?.toIntOrNull() ?: 10
        val targets = if (allPlayers) plugin.server.onlinePlayers else listOf(player)
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3578", "spawnParticles spawning", mapOf("particle" to particle.name, "amount" to amount, "targetsCount" to targets.size, "allPlayers" to allPlayers))
        targets.forEach { viewer ->
            viewer.world.spawnParticle(particle, player.location, amount, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun preloadNodeProgress(player: OfflinePlayer, model: QuestModel, branchId: String, node: QuestObjectNode) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2884", "preloadNodeProgress entry", mapOf("questId" to model.id, "branchId" to branchId, "nodeId" to node.id, "nodeType" to node.type.name, "playerUuid" to player.uniqueId.toString()))
        val session = sessions[player.uniqueId]
        if (session == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2885", "preloadNodeProgress no session", mapOf("questId" to model.id, "nodeId" to node.id))
            return
        }
        val saved = questService.loadNodeProgress(player, model.id, branchId, node.id)
        when {
            isMovementNode(node.type) -> {
                session.movementProgress.putIfAbsent(node.id, saved)
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2888", "preloadNodeProgress movement", mapOf("questId" to model.id, "nodeId" to node.id, "saved" to saved))
            }
            isPhysicalNode(node.type) -> {
                session.physicalProgress.putIfAbsent(node.id, saved)
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2889", "preloadNodeProgress physical", mapOf("questId" to model.id, "nodeId" to node.id, "saved" to saved))
            }
            isMiscNode(node.type) -> {
                session.miscProgress.putIfAbsent(node.id, saved)
                DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2890", "preloadNodeProgress misc", mapOf("questId" to model.id, "nodeId" to node.id, "saved" to saved))
            }
            isPlayerBlockNode(node.type) -> {
                val goals = questService.loadNodeGoalProgress(player, model.id, branchId, node.id)
                if (goals.isNotEmpty()) {
                    val map = session.blockProgress.getOrPut(node.id) { mutableMapOf() }
                    goals.forEach { (g, v) -> map[g] = v }
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2892", "preloadNodeProgress block goals", mapOf("questId" to model.id, "nodeId" to node.id, "goalsCount" to goals.size))
                }
            }
            isPlayerItemNode(node.type) -> {
                val goals = questService.loadNodeGoalProgress(player, model.id, branchId, node.id)
                if (goals.isNotEmpty()) {
                    val map = session.itemProgress.getOrPut(node.id) { mutableMapOf() }
                    goals.forEach { (g, v) -> map[g] = v }
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2898", "preloadNodeProgress item goals", mapOf("questId" to model.id, "nodeId" to node.id, "goalsCount" to goals.size))
                }
            }
            isPlayerEntityNode(node.type) -> {
                val goals = questService.loadNodeGoalProgress(player, model.id, branchId, node.id)
                if (goals.isNotEmpty()) {
                    val map = session.entityProgress.getOrPut(node.id) { mutableMapOf() }
                    goals.forEach { (g, v) -> map[g] = v }
                    DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:2905", "preloadNodeProgress entity goals", mapOf("questId" to model.id, "nodeId" to node.id, "goalsCount" to goals.size))
                }
            }
        }
    }

    private fun giveEffect(player: org.bukkit.entity.Player, payload: String) {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3648", "giveEffect entry", mapOf("playerUuid" to player.uniqueId.toString(), "payload" to payload))
        val parts = payload.split("\\s+".toRegex())
        val type = runCatching { PotionEffectType.getByName(parts.getOrNull(0)?.uppercase() ?: "") }.getOrNull()
        if (type == null) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3650", "giveEffect invalid effect type", mapOf("payload" to payload, "typeStr" to parts.getOrNull(0)))
            return
        }
        val amp = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val ticks = parts.getOrNull(2)?.toIntOrNull() ?: 20
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3653", "giveEffect giving", mapOf("effectType" to type.name, "amplifier" to amp, "ticks" to ticks))
        player.addPotionEffect(PotionEffect(type, ticks, amp))
    }

    private fun isClickAllowed(clickTypes: List<String>, attempted: String): Boolean {
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3887", "isClickAllowed entry", mapOf("clickTypes" to clickTypes, "attempted" to attempted))
        if (clickTypes.isEmpty()) {
            DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3888", "isClickAllowed empty clickTypes, allowing", mapOf())
            return true
        }
        val result = clickTypes.any { ct ->
            val normalized = ct.uppercase()
            normalized == "ANY" || normalized == attempted.uppercase() || normalized.contains("RIGHT")
        }
        DebugLog.logToFile("debug-session", "run1", "RUNTIME", "BranchRuntimeManager.kt:3892", "isClickAllowed result", mapOf("clickTypes" to clickTypes, "attempted" to attempted, "result" to result))
        return result
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
        val positionActionbarHint: MutableMap<String, Long> = mutableMapOf(),
        val transientTasks: MutableSet<org.bukkit.scheduler.BukkitTask> = mutableSetOf()
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

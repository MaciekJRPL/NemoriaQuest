package net.nemoria.quest.runtime

import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.core.MessageFormatter
import net.nemoria.quest.runtime.ChatHideService
import net.nemoria.quest.runtime.ChatHistoryManager
import net.nemoria.quest.data.user.GroupProgress
import net.nemoria.quest.quest.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
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
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.UUID
import kotlin.sequences.asSequence

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
            session.timeLimitTask = object : BukkitRunnable() {
                override fun run() {
                    stop(player)
                    tl.failGoto?.let { goto ->
                        handleGoto(player, model, branchId, goto, 0)
                    } ?: questService.finishOutcome(player, model.id, "FAIL")
                }
            }.runTaskLater(plugin, tl.durationSeconds * 20)
        }
    }

    fun stop(player: OfflinePlayer) {
        sessions.remove(player.uniqueId)?.timeLimitTask?.cancel()
        divergeSessions.remove(player.uniqueId)
        val bukkit = player.player
        if (bukkit != null) {
            ChatHideService.show(bukkit.uniqueId)
            ChatHideService.flushBuffered(bukkit)
        } else {
            ChatHideService.show(player.uniqueId)
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
                val originalHistory = ChatHistoryManager.history(p.uniqueId)
                val greyHistory = originalHistory.map { ChatHistoryManager.greyOut(it) }
                if (node.hideChat || node.dialog) {
                    ChatHideService.hide(player.uniqueId)
                }
                divergeSessions[player.uniqueId] = DivergeChatSession(
                    node.choices,
                    intro = (node.startNotify?.message ?: emptyList()) + node.message,
                    dialogMode = node.dialog,
                    currentIndex = 1,
                    lastRenderIdx = 1,
                    lastRenderAt = 0L,
                    originalHistory = originalHistory,
                    greyHistory = greyHistory,
                    baselineSize = originalHistory.size
                )
                sendDivergeChoices(p, node.choices, highlightIdx = 1, storeState = true)
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
            val currentHistory = ChatHistoryManager.history(bukkit.uniqueId)
            val gson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
            val newMessages: List<Component> =
                if (currentHistory.size > diverge.baselineSize) {
                    currentHistory.drop(diverge.baselineSize).filter { msg ->
                        val json = gson.serialize(msg)
                        !diverge.syntheticMessages.contains(json)
                    }
                } else emptyList()
            val mergedHistory = diverge.originalHistory + newMessages
            clearChatWindow(bukkit, 100)
            replayHistory(bukkit, mergedHistory)
        }
        ChatHideService.show(player.uniqueId)
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
            replayHistory(player, session.greyHistory)
            repeat(2) {
                ChatHideService.allowNext(player.uniqueId)
                ChatHistoryManager.skipNextMessages(player.uniqueId)
                player.sendMessage(Component.empty())
            }
        }
        val legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
        val introLines = session?.intro?.map { legacy.deserialize(MessageFormatter.format(it)) } ?: emptyList()
        val optionLines = choices.mapIndexed { idx, ch ->
            val prefixColor = if (idx + 1 == highlightIdx) "<red>> " else "<green>> "
            val formatted = MessageFormatter.format("$prefixColor${idx + 1}. ${ch.text}")
            val comp = legacy.deserialize(formatted)
            comp.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/nq diverge ${idx + 1}"))
        }
        val hintRaw = net.nemoria.quest.core.Services.i18n.msg("chat_hint")
        val hintComp = legacy.deserialize(MessageFormatter.format(hintRaw))

        val allComps = introLines + optionLines + hintComp
        val block = allComps.reduce { acc, c -> acc.append(net.kyori.adventure.text.Component.newline()).append(c) }

        val sendMenu: () -> Unit = {
            val chatType = com.github.retrooper.packetevents.protocol.chat.ChatTypes.CHAT
            val json = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(block)
            ChatHideService.allowJsonOnce(player.uniqueId, json)
            divergeSessions[player.uniqueId]?.syntheticMessages?.add(json)
            ChatHistoryManager.skipNextMessages(player.uniqueId)
            val packet = com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage(
                chatType,
                block
            )
            com.github.retrooper.packetevents.PacketEvents.getAPI().playerManager.sendPacket(player, packet)
            session?.lastDialog = allComps.map { legacy.serialize(it) }
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
            ChatHideService.allowNext(player.uniqueId)
            ChatHistoryManager.skipNextMessages(player.uniqueId)
            player.sendMessage(Component.empty())
        }
    }

    private fun replayHistory(player: org.bukkit.entity.Player, history: List<Component>) {
        history.forEach { comp ->
            ChatHideService.allowNext(player.uniqueId)
            ChatHistoryManager.skipNextMessages(player.uniqueId)
            player.sendMessage(comp)
        }
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
        val divergeObjectMap: MutableMap<String, DivergeChoiceGui> = mutableMapOf()
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






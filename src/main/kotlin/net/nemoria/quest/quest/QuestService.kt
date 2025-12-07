package net.nemoria.quest.quest

import net.nemoria.quest.data.repo.QuestModelRepository
import net.nemoria.quest.data.repo.UserDataRepository
import net.nemoria.quest.data.user.ObjectiveState
import net.nemoria.quest.data.user.QuestProgress
import net.nemoria.quest.core.MessageFormatter
import net.nemoria.quest.core.Services
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.quest.*
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import net.nemoria.quest.runtime.BranchRuntimeManager

class QuestService(
    private val plugin: JavaPlugin,
    private val userRepo: UserDataRepository,
    private val questRepo: QuestModelRepository
) {
    private val branchRuntime = BranchRuntimeManager(plugin, this)

    enum class StartResult { SUCCESS, NOT_FOUND, ALREADY_ACTIVE, COMPLETION_LIMIT, REQUIREMENT_FAIL, PERMISSION_FAIL, OFFLINE }

    fun startQuest(player: OfflinePlayer, questId: String): StartResult {
        val model = questRepo.findById(questId) ?: return StartResult.NOT_FOUND
        net.nemoria.quest.core.DebugLog.log("startQuest questId=$questId player=${player.name}")
        val bukkitPlayer = player.player ?: return StartResult.OFFLINE
        val data = userRepo.load(player.uniqueId)
        if (data.activeQuests.contains(questId)) return StartResult.ALREADY_ACTIVE
        if (model.completion.maxCompletions == 1 && data.completedQuests.contains(questId)) return StartResult.COMPLETION_LIMIT
        if (!requirementsMet(model, data)) return StartResult.REQUIREMENT_FAIL
        if (!conditionsMet(model, bukkitPlayer)) return StartResult.PERMISSION_FAIL
        data.activeQuests.add(model.id)
        val progress = QuestProgress()
        progress.variables.putAll(model.variables)
        model.objectives.forEach { obj ->
            progress.objectives[obj.id] = ObjectiveState(completed = false, startedAt = System.currentTimeMillis())
            if (obj.type == QuestObjectiveType.TIMER && obj.durationSeconds != null) {
                scheduleTimer(player, questId, obj.id, obj.durationSeconds)
            }
        }
        // set initial branch state
        if (model.branches.isNotEmpty()) {
            val branchId = model.mainBranch ?: model.branches.keys.firstOrNull()
            progress.currentBranchId = branchId
            progress.currentNodeId = branchId?.let { b -> model.branches[b]?.startsAt ?: model.branches[b]?.objects?.keys?.firstOrNull() }
        }
        data.progress[questId] = progress
        userRepo.save(data)
        if (model.branches.isNotEmpty()) {
            branchRuntime.start(player, model)
        }
        return StartResult.SUCCESS
    }

    fun stopQuest(player: OfflinePlayer, questId: String, complete: Boolean) {
        val data = userRepo.load(player.uniqueId)
        data.activeQuests.remove(questId)
        data.progress.remove(questId)
        if (complete) {
            data.completedQuests.add(questId)
            questRepo.findById(questId)?.let { applyRewards(player, it) }
        }
        userRepo.save(data)
        branchRuntime.stop(player)
    }

    fun activeQuests(player: OfflinePlayer): Set<String> =
        userRepo.load(player.uniqueId).activeQuests.toSet()

    fun hasDiverge(player: OfflinePlayer): Boolean = branchRuntime.hasDiverge(player)

    fun scrollDiverge(player: org.bukkit.entity.Player, delta: Int) =
        branchRuntime.scrollDiverge(player, delta)

    fun acceptCurrentDiverge(player: org.bukkit.entity.Player) =
        branchRuntime.acceptCurrentDiverge(player)

    fun progress(player: OfflinePlayer): Map<String, QuestProgress> =
        userRepo.load(player.uniqueId).progress.toMap()

    fun listQuests(): Collection<QuestModel> = questRepo.findAll()

    fun questInfo(id: String): QuestModel? = questRepo.findById(id)

    fun gotoNode(player: org.bukkit.entity.Player, questId: String, branchId: String, nodeId: String): Boolean {
        val data = userRepo.load(player.uniqueId)
        if (!data.activeQuests.contains(questId)) return false
        val model = questRepo.findById(questId) ?: return false
        if (!model.branches.containsKey(branchId)) return false
        if (model.branches[branchId]?.objects?.containsKey(nodeId) != true) return false
        updateBranchState(player, questId, branchId, nodeId)
        return branchRuntime.forceGoto(player, questId, branchId, nodeId)
    }

    fun completeQuest(player: OfflinePlayer, questId: String) {
        finishOutcome(player, questId, "SUCCESS")
    }

    private fun progressKey(branchId: String, nodeId: String, goalId: String? = null): String =
        if (goalId.isNullOrBlank()) "$branchId:$nodeId" else "$branchId:$nodeId:$goalId"

    internal fun saveNodeProgress(player: OfflinePlayer, questId: String, branchId: String, nodeId: String, value: Double, goalId: String? = null) {
        val data = userRepo.load(player.uniqueId)
        val qp = data.progress[questId] ?: return
        qp.nodeProgress[progressKey(branchId, nodeId, goalId)] = value
        userRepo.save(data)
    }

    internal fun loadNodeProgress(player: OfflinePlayer, questId: String, branchId: String, nodeId: String, goalId: String? = null): Double {
        val data = userRepo.load(player.uniqueId)
        val qp = data.progress[questId] ?: return 0.0
        return qp.nodeProgress[progressKey(branchId, nodeId, goalId)] ?: 0.0
    }

    internal fun loadNodeGoalProgress(player: OfflinePlayer, questId: String, branchId: String, nodeId: String): Map<String, Double> {
        val data = userRepo.load(player.uniqueId)
        val qp = data.progress[questId] ?: return emptyMap()
        val prefix = progressKey(branchId, nodeId)
        return qp.nodeProgress.filterKeys { it.startsWith("$prefix:") }.mapKeys { it.key.removePrefix("$prefix:") }
    }

    internal fun clearNodeProgress(player: OfflinePlayer, questId: String, branchId: String, nodeId: String) {
        val data = userRepo.load(player.uniqueId)
        val qp = data.progress[questId] ?: return
        val prefix = progressKey(branchId, nodeId)
        qp.nodeProgress.keys.removeIf { it == prefix || it.startsWith("$prefix:") }
        userRepo.save(data)
    }

    internal fun clearAllNodeProgress(player: OfflinePlayer, questId: String) {
        val data = userRepo.load(player.uniqueId)
        val qp = data.progress[questId] ?: return
        qp.nodeProgress.clear()
        userRepo.save(data)
    }

    fun completeObjective(player: OfflinePlayer, questId: String, objectiveId: String) {
        val data = userRepo.load(player.uniqueId)
        val progress = data.progress[questId] ?: return
        val state = progress.objectives[objectiveId] ?: return
        if (state.completed) return
        state.completed = true
        state.completedAt = System.currentTimeMillis()
        data.progress[questId] = progress
        userRepo.save(data)
        val allDone = progress.objectives.values.all { it.completed }
        if (allDone) {
            finishOutcome(player, questId, "SUCCESS")
        }
    }

    fun incrementObjective(player: OfflinePlayer, questId: String, objectiveId: String, required: Int) {
        val data = userRepo.load(player.uniqueId)
        val progress = data.progress[questId] ?: return
        val state = progress.objectives[objectiveId] ?: return
        if (state.completed) return
        state.progress += 1
        if (state.progress >= required) {
            completeObjective(player, questId, objectiveId)
        } else {
            data.progress[questId] = progress
            userRepo.save(data)
        }
    }

    private fun scheduleTimer(player: OfflinePlayer, questId: String, objectiveId: String, durationSeconds: Long) {
        object : BukkitRunnable() {
            override fun run() {
                completeObjective(player, questId, objectiveId)
            }
        }.runTaskLater(plugin, durationSeconds * 20)
    }

    private fun requirementsMet(model: QuestModel, data: net.nemoria.quest.data.user.UserData): Boolean {
        if (model.requirements.isEmpty()) return true
        return model.requirements.all { data.completedQuests.contains(it) }
    }

    private fun conditionsMet(model: QuestModel, player: Player): Boolean {
        val sc = model.startConditions ?: return true
        if (sc.conditions.isEmpty()) return true
        return checkConditions(player, sc.conditions, model.id)
    }

    private fun applyRewards(player: OfflinePlayer, model: QuestModel) {
        val rewards = model.rewards
        val name = player.name ?: return
        rewards.commands.forEach { cmd ->
            plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(cmd, player))
        }
        // TODO: points/variables systems to implement; placeholders for now
    }

    internal fun finishOutcome(player: OfflinePlayer, questId: String, outcome: String) {
        val model = questRepo.findById(questId)
        if (model != null) {
            val end = model.endObjects[outcome] ?: emptyList()
            end.forEach { eo ->
                when (eo.type) {
                    QuestEndObjectType.SERVER_ACTIONS -> {
                        eo.actions.forEach { act ->
                            val parts = act.trim().split("\\s+".toRegex(), limit = 2)
                            val key = parts.getOrNull(0)?.uppercase() ?: return@forEach
                            val payload = parts.getOrNull(1) ?: ""
                            when (key) {
                                "SEND_MESSAGE" -> player.player?.sendMessage(render(payload, model, player))
                                "PERFORM_COMMAND" -> plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(payload, player))
                                "SEND_SOUND" -> runCatching {
                                    player.player?.playSound(player.player!!.location, org.bukkit.Sound.valueOf(payload.uppercase()), 1f, 1f)
                                }
                                "SEND_TITLE" -> {
                                    val pts = payload.split("\\s+".toRegex(), limit = 5)
                                    val fi = pts.getOrNull(0)?.toIntOrNull() ?: 10
                                    val st = pts.getOrNull(1)?.toIntOrNull() ?: 60
                                    val fo = pts.getOrNull(2)?.toIntOrNull() ?: 10
                                    val title = render(pts.getOrNull(3) ?: "", model, player)
                                    val subtitle = render(pts.getOrNull(4) ?: "", model, player)
                                    player.player?.sendTitle(title, subtitle, fi, st, fo)
                                }
                            }
                        }
                        eo.title?.let { t ->
                            player.player?.sendTitle(
                                render(t.title ?: "", model, player),
                                render(t.subtitle ?: "", model, player),
                                t.fadeIn, t.stay, t.fadeOut
                            )
                        }
                        eo.sound?.let { s ->
                            runCatching { org.bukkit.Sound.valueOf(s.uppercase()) }.onSuccess { snd ->
                                player.player?.playSound(player.player!!.location, snd, 1f, 1f)
                            }
                        }
                    }
                    QuestEndObjectType.SERVER_COMMANDS_PERFORM -> {
                        eo.commands.forEach { cmd ->
                            plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(cmd, player))
                        }
                    }
                    QuestEndObjectType.SERVER_LOGIC_MONEY -> {
                        val amount = evalValueFormula(eo.valueFormula) ?: 0
                        if (eo.commands.isNotEmpty()) {
                            eo.commands.forEach { cmd ->
                                plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(cmd, player, amount))
                            }
                        } else if (eo.currency != null) {
                            val cmd = "eco give ${player.name ?: ""} $amount"
                            plugin.server.dispatchCommand(plugin.server.consoleSender, cmd)
                        }
                    }
                }
            }
        }
        stopQuest(player, questId, complete = outcome.equals("SUCCESS", ignoreCase = true))
    }

    private fun formatCommand(cmd: String, player: OfflinePlayer, amount: Long? = null): String {
        var out = cmd.replace("{player}", player.name ?: "")
        if (amount != null) {
            out = out.replace("{amount}", amount.toString())
        }
        return out
    }

    private fun evalValueFormula(formula: String?, current: Long = 0): Long? {
        if (formula.isNullOrBlank()) return null
        val prepared = formula.replace("{value}", current.toString())
        var total = 0L
        val parts = prepared.split("+")
        for (part in parts) {
            val num = part.trim().toLongOrNull() ?: return null
            total += num
        }
        return total
    }

    fun branchRuntimeHandleNpc(player: Player, npcId: Int) {
        branchRuntime.handleNpcInteract(player, npcId)
    }

    fun branchRuntimeHandleChoice(player: Player, idx: Int): Boolean {
        val before = branchRuntime.hasDiverge(player)
        branchRuntime.handleDivergeChoice(player, idx)
        return before && !branchRuntime.hasDiverge(player)
    }

    fun handlePromptClick(player: Player, token: String) {
        branchRuntime.handlePromptClick(player, token)
    }

    fun handleSneak(player: Player) {
        branchRuntime.handleSneakResume(player)
    }

    internal fun handlePlayerBlockEvent(
        player: Player,
        kind: net.nemoria.quest.runtime.BranchRuntimeManager.BlockEventType,
        block: org.bukkit.block.Block,
        action: String? = null,
        item: org.bukkit.inventory.ItemStack? = null,
        placedByPlayer: Boolean = false,
        spawnerType: String? = null,
        treeType: String? = null
    ) {
        branchRuntime.handlePlayerBlockEvent(
            player,
            kind,
            block,
            action,
            item,
            placedByPlayer,
            spawnerType,
            treeType
        )
    }

    internal fun handlePlayerEntityEvent(
        player: Player,
        kind: net.nemoria.quest.runtime.BranchRuntimeManager.EntityEventType,
        entity: org.bukkit.entity.Entity?,
        damager: org.bukkit.entity.Entity? = null,
        entityTypeHint: String? = null
    ) {
        branchRuntime.handlePlayerEntityEvent(player, kind, entity, damager, entityTypeHint)
    }

    internal fun handlePlayerItemEvent(
        player: Player,
        kind: net.nemoria.quest.runtime.BranchRuntimeManager.ItemEventType,
        item: org.bukkit.inventory.ItemStack?,
        inventoryType: String? = null,
        slot: Int? = null,
        villagerId: java.util.UUID? = null
    ) {
        branchRuntime.handlePlayerItemEvent(player, kind, item, inventoryType, slot, villagerId)
    }

    internal fun handlePlayerMovementEvent(
        player: Player,
        kind: net.nemoria.quest.runtime.BranchRuntimeManager.MovementEventType,
        delta: Double,
        vehicleType: String? = null
    ) {
        branchRuntime.handleMovementEvent(player, kind, delta, vehicleType)
    }

    internal fun handlePlayerPhysicalEvent(
        player: Player,
        kind: net.nemoria.quest.runtime.BranchRuntimeManager.PhysicalEventType,
        amount: Double,
        detail: String? = null
    ) {
        branchRuntime.handlePhysicalEvent(player, kind, amount, detail)
    }

    internal fun handlePlayerMiscEvent(
        player: Player,
        kind: net.nemoria.quest.runtime.BranchRuntimeManager.MiscEventType,
        detail: String? = null
    ): Boolean {
        return branchRuntime.handleMiscEvent(player, kind, detail)
    }

    fun resumeBranch(player: Player, model: QuestModel) {
        branchRuntime.start(player, model)
    }

    internal fun runtime(): net.nemoria.quest.runtime.BranchRuntimeManager = branchRuntime

    internal fun mutateProgress(player: OfflinePlayer, questId: String, mutate: (QuestProgress) -> Unit) {
        val data = userRepo.load(player.uniqueId)
        val progress = data.progress[questId] ?: return
        mutate(progress)
        data.progress[questId] = progress
        userRepo.save(data)
    }

    internal fun updateBranchState(player: OfflinePlayer, questId: String, branchId: String, nodeId: String) {
        val data = userRepo.load(player.uniqueId)
        val progress = data.progress[questId] ?: return
        progress.currentBranchId = branchId
        progress.currentNodeId = nodeId
        data.progress[questId] = progress
        userRepo.save(data)
    }

    private fun render(text: String, model: QuestModel, player: OfflinePlayer): String {
        if (text.isBlank()) return text
        val replaced = renderPlaceholders(text, model.id, player)
        return MessageFormatter.format(replaced)
    }

    internal fun renderPlaceholders(raw: String, questId: String?, player: OfflinePlayer): String {
        var out = raw
        val questVars = questId?.let { qid ->
            progress(player)[qid]?.variables
                ?: questRepo.findById(qid)?.variables
        } ?: emptyMap()
        out = out.replace("\\{mvariable:([A-Za-z0-9_]+)}".toRegex()) { m ->
            questVars[m.groupValues[1]] ?: "0"
        }
        out = out.replace("\\{variable:([A-Za-z0-9_]+)}".toRegex()) { m ->
            userVariable(player, m.groupValues[1])
        }
        out = out.replace("\\{gvariable:([A-Za-z0-9_]+)}".toRegex()) { m ->
            Services.variables.global(m.groupValues[1]) ?: "0"
        }
        out = out.replace("\\{server_variable:([A-Za-z0-9_]+)}".toRegex()) { m ->
            Services.variables.server(m.groupValues[1]) ?: "0"
        }
        return out
    }

    private fun fmtNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

    private fun prioritizedGoals(node: QuestObjectNode): List<Double> = when {
        node.blockGoals.isNotEmpty() -> node.blockGoals.map { it.goal }
        node.itemGoals.isNotEmpty() -> node.itemGoals.map { it.goal }
        node.goals.isNotEmpty() -> node.goals.map { it.goal }
        else -> emptyList()
    }

    private fun goalValue(node: QuestObjectNode): Double {
        val goals = prioritizedGoals(node)
        return when {
            goals.isNotEmpty() -> goals.sum()
            node.distanceGoal != null -> node.distanceGoal
            node.count != null -> node.count.toDouble()
            else -> 1.0
        }
    }

    fun currentObjectiveDetail(player: org.bukkit.entity.Player): String? {
        val data = userRepo.load(player.uniqueId)
        val questId = data.activeQuests.firstOrNull() ?: return null
        return currentObjectiveDetail(player, questId)
    }

    fun currentObjectiveDetail(player: org.bukkit.entity.Player, questId: String): String? {
        val data = userRepo.load(player.uniqueId)
        val model = questRepo.findById(questId) ?: return null
        val prog = data.progress[questId]
        if (model.branches.isEmpty()) return null
        val branchId = prog?.currentBranchId ?: model.mainBranch ?: model.branches.keys.firstOrNull() ?: return null
        val nodeId = prog?.currentNodeId ?: model.branches[branchId]?.startsAt ?: model.branches[branchId]?.objects?.keys?.firstOrNull()
        val node = model.branches[branchId]?.objects?.get(nodeId) ?: return null
        val desc = node.description ?: return null
        val base = renderPlaceholders(desc, questId, player)
        val progressVal = run {
            val goals = prioritizedGoals(node)
            if (goals.isNotEmpty()) {
                val goalsMap = loadNodeGoalProgress(player, questId, branchId, node.id)
                if (goalsMap.isNotEmpty()) goalsMap.values.sum() else loadNodeProgress(player, questId, branchId, node.id)
            } else {
                loadNodeProgress(player, questId, branchId, node.id)
            }
        }
        val goalVal = goalValue(node)
        return base
            .replace("{progress}", fmtNumber(progressVal))
            .replace("{goal}", fmtNumber(goalVal))
    }

    internal fun updateVariable(player: OfflinePlayer, questId: String, variable: String, value: String) {
        val data = userRepo.load(player.uniqueId)
        val progress = data.progress[questId] ?: return
        progress.variables[variable] = value
        data.progress[questId] = progress
        userRepo.save(data)
    }

    internal fun userVariable(player: OfflinePlayer, name: String): String {
        val key = name.lowercase()
        val data = userRepo.load(player.uniqueId)
        val current = data.userVariables[key]
        if (current != null) return current
        val def = Services.variables.defaultUser(key)
        if (def != null) {
            data.userVariables[key] = def
            userRepo.save(data)
            return def
        }
        return "0"
    }

    internal fun updateUserVariable(player: OfflinePlayer, name: String, value: String) {
        val key = name.lowercase()
        val data = userRepo.load(player.uniqueId)
        data.userVariables[key] = value
        userRepo.save(data)
    }


    internal fun checkConditions(player: Player, conditions: List<ConditionEntry>, questId: String? = null): Boolean {
        val qid = questId ?: branchRuntime.getQuestId(player) ?: ""
        conditions.forEach { cond ->
            when (cond.type) {
                ConditionType.PERMISSION -> {
                    if (!(cond.permission?.let { player.hasPermission(it) } ?: true)) return false
                }
                ConditionType.ITEMS -> {
                    val mat = cond.itemType?.let { runCatching { org.bukkit.Material.valueOf(it.uppercase()) }.getOrNull() }
                    if (mat != null) {
                        val has = player.inventory.contents.filterNotNull().sumOf { if (it.type == mat) it.amount else 0 }
                        if (has < cond.itemAmount) return false
                    }
                }
                ConditionType.VARIABLE -> {
                    val varName = cond.variable ?: return@forEach
                    val target = cond.variableValue ?: return@forEach
                    val comp = cond.variableCompare ?: "="
                    val questVars = progress(player)[qid]?.variables ?: emptyMap()
                    val current = questVars[varName]?.toLongOrNull()
                        ?: userVariable(player, varName).toLongOrNull()
                        ?: 0L
                    val ok = when (comp) {
                        "=","==" -> current == target
                        ">" -> current > target
                        "<" -> current < target
                        ">=" -> current >= target
                        "<=" -> current <= target
                        "!=" -> current != target
                        else -> false
                    }
                    if (!ok) return false
                }
            }
        }
        return true
    }
}





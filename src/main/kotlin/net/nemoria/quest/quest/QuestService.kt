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
import org.bukkit.scheduler.BukkitTask
import org.bukkit.ChatColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.nemoria.quest.runtime.BranchRuntimeManager
import net.nemoria.quest.runtime.ParticleScriptEngine
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import net.nemoria.quest.data.user.GroupProgress
import net.nemoria.quest.data.user.QuestPoolData
import net.nemoria.quest.data.user.QuestPoolsState
import net.nemoria.quest.content.ActivatorContentLoader
import net.nemoria.quest.content.PoolContentLoader
import net.nemoria.quest.config.GuiRankingType
import java.io.File
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.lang.reflect.Method
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max

class QuestService(
    private val plugin: JavaPlugin,
    private val userRepo: UserDataRepository,
    private val questRepo: QuestModelRepository
) {
    private val branchRuntime = BranchRuntimeManager(plugin, this)
    private data class CachedUser(val data: net.nemoria.quest.data.user.UserData, var dirty: Boolean, var lastAccess: Long, var version: Long = 0)
    private val userCache: MutableMap<UUID, CachedUser> = ConcurrentHashMap()
    private val questTimeLimitTasks: MutableMap<UUID, MutableMap<String, BukkitTask>> = ConcurrentHashMap()
    private val questTimeLimitReminders: MutableMap<UUID, MutableMap<String, BukkitTask>> = ConcurrentHashMap()
    private val objectiveTimers: MutableMap<UUID, MutableMap<String, MutableMap<String, BukkitTask>>> = ConcurrentHashMap()
    private var flushTask: BukkitTask? = null
    private data class SaveRequest(val playerId: UUID, val version: Long, val snapshot: net.nemoria.quest.data.user.UserData)
    private val pendingSaves: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val saveQueue: ConcurrentLinkedQueue<SaveRequest> = ConcurrentLinkedQueue()
    private val completedSaves: ConcurrentLinkedQueue<Pair<UUID, Long>> = ConcurrentLinkedQueue()
    private var saveTask: BukkitTask? = null
    private val cacheTtlMs = 5 * 60 * 1000L
    private val flushIntervalTicks = 20L
    private val questVarsCache: MutableMap<String, Map<String, String>> = ConcurrentHashMap()
    private var citizensNpcActivatorIndex: Map<Int, List<String>> = emptyMap()
    private val citizensActivatorParticlesRange = 24.0
    private data class CitizensNpcActivatorConfig(
        val questIds: List<String>,
        val dialog: List<String>,
        val autoStartDistance: Double?,
        val resetDelaySeconds: Long?,
        val resetDistance: Double?,
        val resetNotify: NotifySettings?,
        val particlesAvailableScript: String?,
        val particlesProgressScript: String?,
        val particlesVerticalOffset: Double,
        val sneakClickCancel: Boolean,
        val requiredGuiQuests: Int?
    )

    private data class ActivatorDialogState(var idx: Int, var lastAtMs: Long, var questId: String)

    private var citizensNpcActivatorConfig: Map<Int, CitizensNpcActivatorConfig> = emptyMap()
    private var citizensNpcAutoStartIds: Set<Int> = emptySet()
    private var citizensNpcParticlesIds: Set<Int> = emptySet()
    private var citizensNpcProximityIds: Set<Int> = emptySet()
    private var citizensNpcMoveQueryRadius: Double = 0.0
    private var citizensNpcAutoStartQueryRadius: Double = 0.0
    private var citizensNpcProximityQueryRadius: Double = 0.0
    private var citizensNpcIndexedIds: List<Int> = emptyList()
    private val activatorDialogStates: MutableMap<UUID, MutableMap<Int, ActivatorDialogState>> = ConcurrentHashMap()
    private val activatorDialogResetTasks: MutableMap<UUID, MutableMap<Int, BukkitTask>> = ConcurrentHashMap()
    private val lastActivatorMoveCheckAtMs: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val lastActivatorMoveBlockKey: MutableMap<UUID, Long> = ConcurrentHashMap()

    private data class ActivatorParticleRun(
        val status: String,
        val scriptId: String,
        val handle: ParticleScriptEngine.Handle
    )

    private val activatorParticleRuns: MutableMap<UUID, MutableMap<Int, ActivatorParticleRun>> = ConcurrentHashMap()
    private var poolModels: Map<String, QuestPoolModel> = emptyMap()
    private var poolGroups: Map<String, QuestPoolGroup> = emptyMap()
    private var poolQuestIds: Set<String> = emptySet()
    private var poolIdsByQuestId: Map<String, Set<String>> = emptyMap()
    private var poolRefundTypesByQuestId: Map<String, Set<String>> = emptyMap()
    private var poolProcessTask: BukkitTask? = null
    private var actionbarTask: BukkitTask? = null
    private var titleTask: BukkitTask? = null
    private val actionbarState: MutableMap<UUID, ProgressLoopState> = ConcurrentHashMap()
    private val titleState: MutableMap<UUID, ProgressLoopState> = ConcurrentHashMap()
    data class GuiRankingEntry(val name: String, val value: Int)
    private data class GuiRankingSnapshot(val entries: List<GuiRankingEntry>, val atMs: Long)
    private val guiRankingCache: MutableMap<String, GuiRankingSnapshot> = ConcurrentHashMap()
    private val guiRankingCacheTtlMs = 2000L
    private val legacy = LegacyComponentSerializer.builder()
        .character(ChatColor.COLOR_CHAR)
        .hexColors()
        .build()
    private data class ProgressLoopState(
        val questId: String?,
        val signature: String,
        val version: Long,
        val lastAt: Long
    )

    private fun clearActivatorParticleRuns(playerId: UUID) {
        val perNpc = activatorParticleRuns.remove(playerId) ?: return
        perNpc.values.forEach { run ->
            branchRuntime.stopParticleScript(run.handle)
        }
    }

    internal fun handlePlayerWorldChange(player: Player) {
        clearActivatorParticleRuns(player.uniqueId)
    }

    private fun clearActivatorDialogState(playerId: UUID) {
        activatorDialogStates.remove(playerId)
        activatorDialogResetTasks.remove(playerId)?.values?.forEach { it.cancel() }
        lastActivatorMoveCheckAtMs.remove(playerId)
        lastActivatorMoveBlockKey.remove(playerId)
    }

    private fun clearActivatorParticleRunsForQuest(playerId: UUID, questId: String) {
        val perNpc = activatorParticleRuns[playerId] ?: return
        val it = perNpc.entries.iterator()
        while (it.hasNext()) {
            val (npcId, run) = it.next()
            val cfg = citizensNpcActivatorConfig[npcId] ?: continue
            if (!cfg.questIds.contains(questId)) continue
            branchRuntime.stopParticleScript(run.handle)
            it.remove()
        }
        if (perNpc.isEmpty()) activatorParticleRuns.remove(playerId)
    }

    private var citizensRegistry: Any? = null
    private var citizensGetByIdMethod: Method? = null
    private var citizensNpcGetEntityMethod: Method? = null

    private val citizensNpcEntityUuidById: MutableMap<Int, UUID> = ConcurrentHashMap()
    private val citizensNpcChunkById: MutableMap<Int, Pair<String, Long>> = ConcurrentHashMap()
    private val citizensNpcIdsByWorldChunk: MutableMap<String, MutableMap<Long, MutableSet<Int>>> = ConcurrentHashMap()
    private var citizensNpcIndexTask: BukkitTask? = null
    private var citizensNpcIndexCursor: Int = 0

    init {
        startFlushTask()
        startSaveTask()
        startProgressNotifyLoops()
    }

    enum class StartResult { SUCCESS, NOT_FOUND, ALREADY_ACTIVE, COMPLETION_LIMIT, REQUIREMENT_FAIL, CONDITION_FAIL, PERMISSION_FAIL, OFFLINE, WORLD_RESTRICTED, COOLDOWN, INVALID_BRANCH }

    private fun cached(uuid: UUID): CachedUser {
        val now = System.currentTimeMillis()
        return userCache.compute(uuid) { _, existing ->
            if (existing != null) {
                existing.lastAccess = now
                existing
            } else {
                DebugLog.logToFile("debug-session", "run1", "CACHE", "QuestService.kt:50", "cached miss - loading from DB", mapOf("uuid" to uuid.toString(), "cacheSize" to userCache.size))
                val loaded = userRepo.load(uuid)
                DebugLog.logToFile("debug-session", "run1", "CACHE", "QuestService.kt:50", "cached loaded", mapOf("uuid" to uuid.toString(), "activeQuests" to loaded.activeQuests.size, "completedQuests" to loaded.completedQuests.size, "progressCount" to loaded.progress.size))
                CachedUser(loaded, false, now)
            }
        }!!
    }

    private fun data(player: OfflinePlayer): net.nemoria.quest.data.user.UserData = cached(player.uniqueId).data
    private fun data(player: Player): net.nemoria.quest.data.user.UserData = cached(player.uniqueId).data

    internal fun preload(player: OfflinePlayer) {
        cached(player.uniqueId)
    }

    internal fun preload(playerId: UUID) {
        cached(playerId)
    }

    internal fun preloadFromData(playerId: UUID, data: net.nemoria.quest.data.user.UserData) {
        val now = System.currentTimeMillis()
        userCache.compute(playerId) { _, existing ->
            if (existing != null) {
                existing.lastAccess = now
                existing
            } else {
                CachedUser(data, false, now)
            }
        }
    }

    internal fun activeQuestIds(playerId: UUID): Set<String> =
        cached(playerId).data.activeQuests.toSet()

    internal fun progressVersion(playerId: UUID): Long =
        cached(playerId).version

    private fun markDirty(player: OfflinePlayer) {
        val cu = cached(player.uniqueId)
        val oldVersion = cu.version
        cu.dirty = true
        cu.version += 1
        guiRankingCache.clear()
        DebugLog.logToFile("debug-session", "run1", "CACHE", "QuestService.kt:61", "markDirty", mapOf("playerUuid" to player.uniqueId.toString(), "playerName" to (player.name ?: "null"), "oldVersion" to oldVersion, "newVersion" to cu.version))
    }

    private fun startFlushTask() {
        flushTask?.cancel()
        flushTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { flushDirty() }, flushIntervalTicks, flushIntervalTicks)
    }

    private fun startSaveTask() {
        saveTask?.cancel()
        saveTask = plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable { drainSaveQueue() }, 1L, 1L)
    }

    private fun startProgressNotifyLoops() {
        startActionbarLoop()
        startTitleLoop()
    }

    private fun startActionbarLoop() {
        actionbarTask?.cancel()
        if (!plugin.config.getBoolean("progress_notify_loop_actionbar", true)) {
            actionbarTask = null
            return
        }
        actionbarTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { updateActionbarLoop() }, 20L, 20L)
    }

    private fun startTitleLoop() {
        titleTask?.cancel()
        if (!plugin.config.getBoolean("progress_notify_loop_title", true)) {
            titleTask = null
            return
        }
        titleTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { updateTitleLoop() }, 20L, 20L)
    }

    private fun updateActionbarLoop() {
        val loopMillis = plugin.config.getLong("progress_notify_actionbar_loop_millis", 2500L).coerceAtLeast(250L)
        plugin.server.onlinePlayers.forEach { player ->
            sendActionbarLoop(player, loopMillis)
        }
    }

    private fun updateTitleLoop() {
        val loopMillis = plugin.config.getLong("progress_notify_title_loop_millis", 2500L).coerceAtLeast(250L)
        plugin.server.onlinePlayers.forEach { player ->
            sendTitleLoop(player, loopMillis)
        }
    }

    private fun sendActionbarLoop(player: Player, loopMillis: Long) {
        if (!isActionbarEnabled(player)) {
            clearActionbar(player)
            return
        }
        val now = System.currentTimeMillis()
        val version = progressVersion(player.uniqueId)
        val state = actionbarState[player.uniqueId]
        if (state != null && state.version == version && now - state.lastAt < loopMillis) return
        val selection = selectProgressQuest(player, requireActionbar = true) ?: run {
            clearActionbar(player)
            return
        }
        val (questId, model) = selection
        val template = model.progressNotify?.actionbar
        if (template.isNullOrBlank()) {
            clearActionbar(player)
            return
        }
        val text = buildProgressLine(template, model, questId, player) ?: run {
            clearActionbar(player)
            return
        }
        val signature = text
        if (state != null && state.signature == signature && state.version == version && now - state.lastAt < loopMillis) {
            return
        }
        player.sendActionBar(legacy.deserialize(text))
        actionbarState[player.uniqueId] = ProgressLoopState(questId, signature, version, now)
    }

    private fun sendTitleLoop(player: Player, loopMillis: Long) {
        if (!isTitleEnabled(player)) {
            clearTitle(player)
            return
        }
        val now = System.currentTimeMillis()
        val version = progressVersion(player.uniqueId)
        val state = titleState[player.uniqueId]
        if (state != null && state.version == version && now - state.lastAt < loopMillis) return
        val selection = selectProgressQuest(player, requireActionbar = false) ?: run {
            clearTitle(player)
            return
        }
        val (questId, model) = selection
        val titleCfg = model.progressNotify?.title ?: run {
            clearTitle(player)
            return
        }
        val titleText = buildProgressLine(titleCfg.title ?: "", model, questId, player) ?: ""
        val subtitleText = buildProgressLine(titleCfg.subtitle ?: "", model, questId, player) ?: ""
        val signature = "${titleCfg.fadeIn}|${titleCfg.stay}|${titleCfg.fadeOut}|$titleText|$subtitleText"
        if (state != null && state.signature == signature && state.version == version && now - state.lastAt < loopMillis) {
            return
        }
        val fi = titleCfg.fadeIn
        val st = titleCfg.stay
        val fo = titleCfg.fadeOut
        player.sendTitle(titleText, subtitleText, fi, st, fo)
        titleState[player.uniqueId] = ProgressLoopState(questId, signature, version, now)
    }

    private fun selectProgressQuest(player: Player, requireActionbar: Boolean): Pair<String, QuestModel>? {
        val active = activeQuests(player)
        for (questId in active) {
            val model = questInfo(questId) ?: continue
            val notify = model.progressNotify ?: continue
            if (requireActionbar && notify.actionbar.isNullOrBlank()) continue
            if (!requireActionbar && notify.title == null) continue
            return questId to model
        }
        return null
    }

    private fun buildProgressLine(template: String, model: QuestModel, questId: String, player: Player): String? {
        if (template.isBlank()) return null
        val questNameRaw = model.displayName ?: model.name
        val questName = renderPlaceholders(questNameRaw, questId, player)
        val objective = currentObjectiveDetail(player, questId) ?: Services.i18n.msg("scoreboard.no_objective")
        var out = template
        val placeholders = mapOf(
            "quest" to questName,
            "quest_name" to questName,
            "objective" to objective,
            "objective_detail" to objective
        )
        placeholders.forEach { (k, v) ->
            out = out.replace("{$k}", v)
        }
        return MessageFormatter.formatLegacyOnly(out)
    }

    private fun clearActionbar(player: Player) {
        if (actionbarState.remove(player.uniqueId) != null) {
            player.sendActionBar(legacy.deserialize(""))
        }
    }

    private fun clearTitle(player: Player) {
        if (titleState.remove(player.uniqueId) != null) {
            player.sendTitle("", "", 0, 0, 0)
        }
    }

    fun isActionbarEnabled(player: OfflinePlayer): Boolean = data(player).actionbarEnabled

    fun isTitleEnabled(player: OfflinePlayer): Boolean = data(player).titleEnabled

    fun toggleActionbar(player: Player): Boolean {
        val d = data(player)
        d.actionbarEnabled = !d.actionbarEnabled
        markDirty(player)
        if (!d.actionbarEnabled) {
            clearActionbar(player)
        }
        return d.actionbarEnabled
    }

    fun toggleTitle(player: Player): Boolean {
        val d = data(player)
        d.titleEnabled = !d.titleEnabled
        markDirty(player)
        if (!d.titleEnabled) {
            clearTitle(player)
        }
        return d.titleEnabled
    }

    fun guiRanking(type: GuiRankingType, questId: String?, limit: Int): List<GuiRankingEntry> {
        val safeLimit = limit.coerceAtLeast(1)
        val key = "${type.name}|${questId ?: ""}|$safeLimit"
        val now = System.currentTimeMillis()
        guiRankingCache[key]?.let {
            if (now - it.atMs <= guiRankingCacheTtlMs) return it.entries
        }
        val entries = plugin.server.onlinePlayers.map { player ->
            val data = data(player)
            val value = when (type) {
                GuiRankingType.COMPLETED_QUESTS -> data.completedQuests.size
                GuiRankingType.COMPLETED_OBJECTIVES -> data.progress.values.sumOf { progress ->
                    progress.objectives.values.count { it.completed }
                }
                GuiRankingType.QUEST_COMPLETED -> if (!questId.isNullOrBlank() && data.completedQuests.contains(questId)) 1 else 0
            }
            GuiRankingEntry(player.name, value)
        }.filter { it.value > 0 }
            .sortedWith(compareByDescending<GuiRankingEntry> { it.value }.thenBy { it.name })
            .take(safeLimit)
        guiRankingCache[key] = GuiRankingSnapshot(entries, now)
        return entries
    }

    private fun drainSaveQueue() {
        while (true) {
            val req = saveQueue.poll() ?: break
            userRepo.save(req.snapshot)
            completedSaves.add(req.playerId to req.version)
        }
    }

    fun shutdown() {
        saveTask?.cancel()
        saveTask = null
        poolProcessTask?.cancel()
        poolProcessTask = null
        flushDirty(forceAll = true)
        flushTask?.cancel()
        flushTask = null
        actionbarTask?.cancel()
        actionbarTask = null
        titleTask?.cancel()
        titleTask = null
        actionbarState.clear()
        titleState.clear()
        guiRankingCache.clear()
        questTimeLimitTasks.values.forEach { inner -> inner.values.forEach { it.cancel() } }
        questTimeLimitTasks.clear()
        questTimeLimitReminders.values.forEach { inner -> inner.values.forEach { it.cancel() } }
        questTimeLimitReminders.clear()
        objectiveTimers.values.forEach { questMap -> questMap.values.forEach { objMap -> objMap.values.forEach { it.cancel() } } }
        objectiveTimers.clear()
        branchRuntime.shutdown()
        userCache.clear()
        questVarsCache.clear()
        citizensNpcActivatorIndex = emptyMap()
        citizensNpcActivatorConfig = emptyMap()
        citizensNpcAutoStartIds = emptySet()
        citizensNpcParticlesIds = emptySet()
        citizensNpcProximityIds = emptySet()
        citizensNpcMoveQueryRadius = 0.0
        citizensNpcAutoStartQueryRadius = 0.0
        citizensNpcProximityQueryRadius = 0.0
        citizensNpcIndexedIds = emptyList()
        activatorDialogStates.clear()
        activatorDialogResetTasks.values.forEach { it.values.forEach { task -> task.cancel() } }
        activatorDialogResetTasks.clear()
        lastActivatorMoveCheckAtMs.clear()
        lastActivatorMoveBlockKey.clear()
        activatorParticleRuns.clear()
        poolModels = emptyMap()
        poolGroups = emptyMap()
        poolQuestIds = emptySet()
        poolIdsByQuestId = emptyMap()
        poolRefundTypesByQuestId = emptyMap()
        citizensRegistry = null
        citizensGetByIdMethod = null
        citizensNpcGetEntityMethod = null
        citizensNpcEntityUuidById.clear()
        citizensNpcChunkById.clear()
        citizensNpcIdsByWorldChunk.clear()
        citizensNpcIndexTask?.cancel()
        citizensNpcIndexTask = null
    }

    fun reloadCitizensNpcActivators() {
        val activatorsDir = File(plugin.dataFolder, "content/activators")
        val activatorSpecs = ActivatorContentLoader.loadCitizensNpcActivatorSpecs(activatorsDir)
        if (activatorSpecs.isEmpty()) {
            citizensNpcActivatorIndex = emptyMap()
            citizensNpcActivatorConfig = emptyMap()
            citizensNpcAutoStartIds = emptySet()
            citizensNpcParticlesIds = emptySet()
            citizensNpcProximityIds = emptySet()
            citizensNpcMoveQueryRadius = 0.0
            citizensNpcAutoStartQueryRadius = 0.0
            citizensNpcProximityQueryRadius = 0.0
            citizensNpcIndexedIds = emptyList()
            citizensNpcEntityUuidById.clear()
            citizensNpcChunkById.clear()
            citizensNpcIdsByWorldChunk.clear()
            citizensNpcIndexTask?.cancel()
            citizensNpcIndexTask = null
            return
        }

        val index = mutableMapOf<Int, MutableList<String>>()
        val specByNpc = mutableMapOf<Int, ActivatorContentLoader.CitizensNpcActivatorSpec>()
        val models = questRepo.findAll()
        val priorities = models.associate { it.id to (it.displayPriority ?: Int.MAX_VALUE) }
        val modelsById = models.associateBy { it.id }
        models.forEach { model ->
            model.activators.forEach { activatorId ->
                val spec = activatorSpecs[activatorId] ?: return@forEach
                spec.npcs.forEach { npcId ->
                    index.computeIfAbsent(npcId) { mutableListOf() }.add(model.id)
                    specByNpc.putIfAbsent(npcId, spec)
                }
            }
        }
        val npcToQuestIds = index.mapValues { (_, v) ->
            v.distinct().sortedWith(compareBy<String> { priorities[it] ?: Int.MAX_VALUE }.thenBy { it })
        }
        citizensNpcActivatorIndex = npcToQuestIds

        citizensNpcActivatorConfig = npcToQuestIds.mapValues { (npcId, questIds) ->
            val primary = questIds.firstOrNull()?.let { modelsById[it] }
            val spec = specByNpc[npcId]
            CitizensNpcActivatorConfig(
                questIds = questIds,
                dialog = primary?.activatorsDialog ?: emptyList(),
                autoStartDistance = primary?.activatorsDialogAutoStartDistance,
                resetDelaySeconds = primary?.activatorsDialogResetDelaySeconds,
                resetDistance = primary?.activatorsDialogResetDistance,
                resetNotify = primary?.activatorsDialogResetNotify,
                particlesAvailableScript = spec?.particles?.get("AVAILABLE"),
                particlesProgressScript = spec?.particles?.get("PROGRESS"),
                particlesVerticalOffset = spec?.particlesVerticalOffset ?: 0.0,
                sneakClickCancel = spec?.sneakClickCancel ?: false,
                requiredGuiQuests = spec?.requiredGuiQuests
            )
        }

        citizensNpcAutoStartIds = citizensNpcActivatorConfig
            .filter { (_, cfg) -> cfg.autoStartDistance != null && cfg.dialog.isNotEmpty() }
            .keys
        citizensNpcParticlesIds = citizensNpcActivatorConfig
            .filter { (_, cfg) -> !cfg.particlesAvailableScript.isNullOrBlank() || !cfg.particlesProgressScript.isNullOrBlank() }
            .keys
        citizensNpcProximityIds = (citizensNpcAutoStartIds + citizensNpcParticlesIds)
        val maxAutoStart = citizensNpcActivatorConfig.values.maxOfOrNull { it.autoStartDistance ?: 0.0 } ?: 0.0
        val maxResetDist = citizensNpcActivatorConfig.values.maxOfOrNull { it.resetDistance ?: 0.0 } ?: 0.0
        val particlesRange = if (citizensNpcParticlesIds.isNotEmpty()) citizensActivatorParticlesRange else 0.0
        citizensNpcMoveQueryRadius = maxOf(maxAutoStart, maxResetDist, particlesRange)
        citizensNpcAutoStartQueryRadius = maxAutoStart
        citizensNpcProximityQueryRadius = maxOf(maxAutoStart, citizensActivatorParticlesRange)
        citizensNpcIndexedIds = citizensNpcActivatorConfig
            .filter { (_, cfg) ->
                cfg.autoStartDistance != null ||
                    cfg.resetDistance != null ||
                    !cfg.particlesAvailableScript.isNullOrBlank() ||
                    !cfg.particlesProgressScript.isNullOrBlank()
            }
            .keys
            .sorted()

        startCitizensNpcIndexTask()
    }

    fun reloadPoolsFromDisk() {
        val contentDir = File(plugin.dataFolder, "content")
        val poolDirs = listOf(
            File(contentDir, "pools"),
            File(contentDir, "quest_pools"),
            File(plugin.dataFolder, "quest_pools")
        )
        val groupsDir = File(contentDir, "groups")
        val result = PoolContentLoader.loadAll(poolDirs, groupsDir)
        poolModels = result.pools
        poolGroups = result.groups
        rebuildPoolIndexes()
        startPoolProcessTask()
    }

    private fun rebuildPoolIndexes() {
        val questIds = mutableSetOf<String>()
        val poolsByQuest = mutableMapOf<String, MutableSet<String>>()
        val refundByQuest = mutableMapOf<String, MutableSet<String>>()
        poolModels.forEach { (poolId, pool) ->
            pool.quests.values.forEach { entry ->
                val qid = entry.questId.lowercase()
                questIds.add(qid)
                poolsByQuest.computeIfAbsent(qid) { mutableSetOf() }.add(poolId)
                entry.refundTokenOnEndTypes.forEach { t ->
                    refundByQuest.computeIfAbsent(qid) { mutableSetOf() }.add(t)
                }
            }
            pool.questGroups.values.forEach { entry ->
                val group = poolGroups[entry.groupId.lowercase()] ?: return@forEach
                group.quests.forEach { questId ->
                    val qid = questId.lowercase()
                    questIds.add(qid)
                    poolsByQuest.computeIfAbsent(qid) { mutableSetOf() }.add(poolId)
                    entry.refundTokenOnEndTypes.forEach { t ->
                        refundByQuest.computeIfAbsent(qid) { mutableSetOf() }.add(t)
                    }
                }
            }
        }
        poolQuestIds = questIds
        poolIdsByQuestId = poolsByQuest.mapValues { it.value.toSet() }
        poolRefundTypesByQuestId = refundByQuest.mapValues { it.value.toSet() }
    }

    private fun startPoolProcessTask() {
        poolProcessTask?.cancel()
        if (poolModels.isEmpty()) {
            poolProcessTask = null
            return
        }
        val ticks = plugin.config.getLong("pools_process_task_ticks", 1200L).coerceAtLeast(20L)
        poolProcessTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            plugin.server.onlinePlayers.forEach { processPoolsForPlayer(it) }
        }, ticks, ticks)
    }

    private fun processPoolsForPlayer(player: Player) {
        if (poolModels.isEmpty()) return
        val data = data(player)
        poolModels.values.forEach { pool ->
            processPool(pool, player, data)
        }
    }

    private data class ActivePoolFrame(val frameId: String, val startMs: Long, val endMs: Long)

    private fun processPool(pool: QuestPoolModel, player: Player, data: net.nemoria.quest.data.user.UserData) {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val poolId = pool.id.lowercase()
        val poolData = data.questPools.pools.computeIfAbsent(poolId) { QuestPoolData() }
        val (activeFrame, frameChanged) = activePoolFrame(pool, now, poolData)
        if (frameChanged) {
            markDirty(player)
        }
        if (activeFrame == null) return
        if (poolData.lastProcessedFrame[activeFrame.frameId] == activeFrame.startMs) return

        if (poolHasTokensRemaining(pool, data)) {
            poolData.streak = 0
        }

        preProcessPoolQuests(pool, player, data)
        preProcessPoolGroups(pool, player, data)
        var amountRemaining = computePoolAmountRemaining(pool, data)
        amountRemaining = distributeQuestTokens(pool, player, data, amountRemaining)
        distributeGroupTokens(pool, player, data, amountRemaining)

        poolData.lastProcessedFrame[activeFrame.frameId] = activeFrame.startMs
        data.questPools.pools[poolId] = poolData
        markDirty(player)
    }

    private fun preProcessPoolQuests(pool: QuestPoolModel, player: Player, data: net.nemoria.quest.data.user.UserData) {
        pool.quests.values.forEach { entry ->
            if (!checkPoolConditions(player, entry.processConditions, entry.questId)) return@forEach
            if (entry.preResetTokens) setPoolTokens(data, entry.questId, 0)
            if (entry.preStop) stopQuestIfActive(player, entry.questId)
            if (entry.preResetHistory) resetQuestHistory(player, entry.questId)
        }
    }

    private fun preProcessPoolGroups(pool: QuestPoolModel, player: Player, data: net.nemoria.quest.data.user.UserData) {
        pool.questGroups.values.forEach { entry ->
            if (!checkPoolConditions(player, entry.processConditions, null)) return@forEach
            val group = poolGroups[entry.groupId.lowercase()] ?: return@forEach
            group.quests.forEach { questId ->
                if (entry.preResetTokens) setPoolTokens(data, questId, 0)
                if (entry.preStop) stopQuestIfActive(player, questId)
                if (entry.preResetHistory) resetQuestHistory(player, questId)
            }
        }
    }

    private fun computePoolAmountRemaining(pool: QuestPoolModel, data: net.nemoria.quest.data.user.UserData): Int {
        var amountRemaining = pool.amount
        if (pool.amountTolerance == QuestPoolAmountTolerance.COUNT_STARTED) {
            val started = countStartedInPool(pool, data)
            amountRemaining = max(0, amountRemaining - started)
        }
        return amountRemaining
    }

    private fun distributeQuestTokens(
        pool: QuestPoolModel,
        player: Player,
        data: net.nemoria.quest.data.user.UserData,
        amountRemaining: Int
    ): Int {
        var remainingAmount = amountRemaining
        val questEntries = pool.quests.values
            .filter { checkPoolConditions(player, it.processConditions, it.questId) }
            .toMutableList()
        while (remainingAmount > 0 && questEntries.isNotEmpty()) {
            val idx = if (pool.order == QuestPoolOrder.IN_ORDER) 0 else ThreadLocalRandom.current().nextInt(questEntries.size)
            val entry = questEntries.removeAt(idx)
            if (entry.selectedResetTokens) setPoolTokens(data, entry.questId, 0)
            if (entry.selectedStop) stopQuestIfActive(player, entry.questId)
            if (entry.selectedResetHistory) resetQuestHistory(player, entry.questId)
            val tokens = randomTokens(entry.minTokens, entry.maxTokens)
            if (tokens > 0) {
                alterPoolTokens(data, entry.questId, tokens)
                remainingAmount--
            }
        }
        return remainingAmount
    }

    private fun distributeGroupTokens(
        pool: QuestPoolModel,
        player: Player,
        data: net.nemoria.quest.data.user.UserData,
        amountRemaining: Int
    ) {
        var remainingAmount = amountRemaining
        val groupEntries = pool.questGroups.values
            .filter { checkPoolConditions(player, it.processConditions, null) }
            .toMutableList()
        while (remainingAmount > 0 && groupEntries.isNotEmpty()) {
            val idx = if (pool.order == QuestPoolOrder.IN_ORDER) 0 else ThreadLocalRandom.current().nextInt(groupEntries.size)
            val entry = groupEntries.removeAt(idx)
            val group = poolGroups[entry.groupId.lowercase()] ?: continue
            val questIds = group.quests.toMutableList()
            if (pool.order == QuestPoolOrder.RANDOM) {
                questIds.shuffle()
            }
            if (entry.selectedResetTokens || entry.selectedStop || entry.selectedResetHistory) {
                group.quests.forEach { questId ->
                    if (entry.selectedResetTokens) setPoolTokens(data, questId, 0)
                    if (entry.selectedStop) stopQuestIfActive(player, questId)
                    if (entry.selectedResetHistory) resetQuestHistory(player, questId)
                }
            }
            var gaveAny = false
            var remaining = max(0, entry.amount)
            while (remaining > 0 && questIds.isNotEmpty()) {
                val questId = if (pool.order == QuestPoolOrder.IN_ORDER) questIds.removeAt(0) else questIds.removeAt(ThreadLocalRandom.current().nextInt(questIds.size))
                val tokens = randomTokens(entry.minTokens, entry.maxTokens)
                if (tokens > 0) {
                    alterPoolTokens(data, questId, tokens)
                    gaveAny = true
                }
                remaining--
            }
            if (gaveAny) {
                remainingAmount--
            }
        }

    }

    private fun activePoolFrame(pool: QuestPoolModel, now: ZonedDateTime, poolData: QuestPoolData): Pair<ActivePoolFrame?, Boolean> {
        val frames = if (pool.timeFrames.isEmpty()) {
            listOf(QuestPoolTimeFrame(id = "frame_0", type = QuestPoolTimeFrameType.NONE))
        } else {
            pool.timeFrames
        }
        var changed = false
        frames.forEach { frame ->
            val (active, frameChanged) = resolveActiveFrame(frame, now, poolData)
            if (frameChanged) changed = true
            if (active != null) return Pair(active, changed)
        }
        return Pair(null, changed)
    }

    private fun resolveActiveFrame(frame: QuestPoolTimeFrame, now: ZonedDateTime, poolData: QuestPoolData): Pair<ActivePoolFrame?, Boolean> {
        return when (frame.type) {
            QuestPoolTimeFrameType.NONE -> Pair(ActivePoolFrame(frame.id, 0L, Long.MAX_VALUE), false)
            QuestPoolTimeFrameType.REPEAT_PERIOD -> resolveRepeatPeriodFrame(frame, now)
            QuestPoolTimeFrameType.DAILY -> resolveDailyFrame(frame, now)
            QuestPoolTimeFrameType.WEEKLY -> resolveWeeklyFrame(frame, now)
            QuestPoolTimeFrameType.MONTHLY -> resolveMonthlyFrame(frame, now)
            QuestPoolTimeFrameType.YEARLY -> resolveYearlyFrame(frame, now)
            QuestPoolTimeFrameType.LIMITED -> resolveLimitedFrame(frame, now, poolData)
        }
    }

    private fun resolveRepeatPeriodFrame(frame: QuestPoolTimeFrame, now: ZonedDateTime): Pair<ActivePoolFrame?, Boolean> {
        val duration = frame.durationSeconds ?: return Pair(null, false)
        if (duration <= 0) return Pair(null, false)
        val periodMs = duration * 1000
        val startMs = now.toInstant().toEpochMilli() / periodMs * periodMs
        return Pair(ActivePoolFrame(frame.id, startMs, startMs + periodMs), false)
    }

    private fun resolveDailyFrame(frame: QuestPoolTimeFrame, now: ZonedDateTime): Pair<ActivePoolFrame?, Boolean> {
        val start = frame.start ?: QuestPoolTimePoint(hour = 0, minute = 0)
        val end = frame.end ?: QuestPoolTimePoint(hour = 23, minute = 59)
        val startTime = LocalTime.of(start.hour ?: 0, start.minute ?: 0)
        val endTime = LocalTime.of(end.hour ?: 23, end.minute ?: 59)
        var startAt = now.with(startTime)
        var endAt = now.with(endTime)
        if (!endAt.isAfter(startAt)) endAt = endAt.plusDays(1)
        if (now.isBefore(startAt)) {
            startAt = startAt.minusDays(1)
            endAt = endAt.minusDays(1)
        }
        if (now.isBefore(startAt) || !now.isBefore(endAt)) return Pair(null, false)
        return Pair(ActivePoolFrame(frame.id, startAt.toInstant().toEpochMilli(), endAt.toInstant().toEpochMilli()), false)
    }

    private fun resolveWeeklyFrame(frame: QuestPoolTimeFrame, now: ZonedDateTime): Pair<ActivePoolFrame?, Boolean> {
        val start = frame.start ?: QuestPoolTimePoint(dayOfWeek = DayOfWeek.MONDAY, hour = 0, minute = 0)
        val end = frame.end ?: QuestPoolTimePoint(dayOfWeek = DayOfWeek.SUNDAY, hour = 23, minute = 59)
        val startDay = start.dayOfWeek ?: DayOfWeek.MONDAY
        val endDay = end.dayOfWeek ?: DayOfWeek.SUNDAY
        val startTime = LocalTime.of(start.hour ?: 0, start.minute ?: 0)
        val endTime = LocalTime.of(end.hour ?: 23, end.minute ?: 59)
        var startAt = now.with(TemporalAdjusters.previousOrSame(startDay)).with(startTime)
        var endAt = startAt.with(TemporalAdjusters.nextOrSame(endDay)).with(endTime)
        if (!endAt.isAfter(startAt)) endAt = endAt.plusWeeks(1)
        if (now.isBefore(startAt)) {
            startAt = startAt.minusWeeks(1)
            endAt = endAt.minusWeeks(1)
        }
        if (now.isBefore(startAt) || !now.isBefore(endAt)) return Pair(null, false)
        return Pair(ActivePoolFrame(frame.id, startAt.toInstant().toEpochMilli(), endAt.toInstant().toEpochMilli()), false)
    }

    private fun resolveMonthlyFrame(frame: QuestPoolTimeFrame, now: ZonedDateTime): Pair<ActivePoolFrame?, Boolean> {
        val start = frame.start ?: QuestPoolTimePoint(dayOfMonth = 1, hour = 0, minute = 0)
        val end = frame.end ?: QuestPoolTimePoint(dayOfMonth = 31, hour = 23, minute = 59)
        var startAt = now.withDayOfMonth(minDayOfMonth(now, start.dayOfMonth ?: 1)).with(LocalTime.of(start.hour ?: 0, start.minute ?: 0))
        var endAt = now.withDayOfMonth(minDayOfMonth(now, end.dayOfMonth ?: 31)).with(LocalTime.of(end.hour ?: 23, end.minute ?: 59))
        if (!endAt.isAfter(startAt)) endAt = endAt.plusMonths(1)
        if (now.isBefore(startAt)) {
            startAt = startAt.minusMonths(1)
            endAt = endAt.minusMonths(1)
        }
        if (now.isBefore(startAt) || !now.isBefore(endAt)) return Pair(null, false)
        return Pair(ActivePoolFrame(frame.id, startAt.toInstant().toEpochMilli(), endAt.toInstant().toEpochMilli()), false)
    }

    private fun resolveYearlyFrame(frame: QuestPoolTimeFrame, now: ZonedDateTime): Pair<ActivePoolFrame?, Boolean> {
        val start = frame.start ?: QuestPoolTimePoint(month = Month.JANUARY, dayOfMonth = 1, hour = 0, minute = 0)
        val end = frame.end ?: QuestPoolTimePoint(month = Month.DECEMBER, dayOfMonth = 31, hour = 23, minute = 59)
        var startAt = now.withMonth((start.month ?: Month.JANUARY).value)
            .withDayOfMonth(minDayOfMonth(now.withMonth((start.month ?: Month.JANUARY).value), start.dayOfMonth ?: 1))
            .with(LocalTime.of(start.hour ?: 0, start.minute ?: 0))
        var endAt = now.withMonth((end.month ?: Month.DECEMBER).value)
            .withDayOfMonth(minDayOfMonth(now.withMonth((end.month ?: Month.DECEMBER).value), end.dayOfMonth ?: 31))
            .with(LocalTime.of(end.hour ?: 23, end.minute ?: 59))
        if (!endAt.isAfter(startAt)) endAt = endAt.plusYears(1)
        if (now.isBefore(startAt)) {
            startAt = startAt.minusYears(1)
            endAt = endAt.minusYears(1)
        }
        if (now.isBefore(startAt) || !now.isBefore(endAt)) return Pair(null, false)
        return Pair(ActivePoolFrame(frame.id, startAt.toInstant().toEpochMilli(), endAt.toInstant().toEpochMilli()), false)
    }

    private fun resolveLimitedFrame(frame: QuestPoolTimeFrame, now: ZonedDateTime, poolData: QuestPoolData): Pair<ActivePoolFrame?, Boolean> {
        val nowMs = now.toInstant().toEpochMilli()
        val stored = poolData.limitedFrames[frame.id]
        if (stored != null) {
            val active = nowMs >= stored.startMs && nowMs < stored.endMs
            return Pair(if (active) ActivePoolFrame(frame.id, stored.startMs, stored.endMs) else null, false)
        }
        val start = frame.start ?: return Pair(null, false)
        val end = frame.end ?: return Pair(null, false)
        val startAt = now.withMonth((start.month ?: now.month).value)
            .withDayOfMonth(minDayOfMonth(now.withMonth((start.month ?: now.month).value), start.dayOfMonth ?: 1))
            .with(LocalTime.of(start.hour ?: 0, start.minute ?: 0))
        var endAt = now.withMonth((end.month ?: now.month).value)
            .withDayOfMonth(minDayOfMonth(now.withMonth((end.month ?: now.month).value), end.dayOfMonth ?: 1))
            .with(LocalTime.of(end.hour ?: 0, end.minute ?: 0))
        if (!endAt.isAfter(startAt)) endAt = endAt.plusYears(1)
        val window = net.nemoria.quest.data.user.QuestPoolTimeWindow(
            startMs = startAt.toInstant().toEpochMilli(),
            endMs = endAt.toInstant().toEpochMilli()
        )
        poolData.limitedFrames[frame.id] = window
        val active = nowMs >= window.startMs && nowMs < window.endMs
        return Pair(if (active) ActivePoolFrame(frame.id, window.startMs, window.endMs) else null, true)
    }

    private fun minDayOfMonth(base: ZonedDateTime, day: Int): Int {
        val maxDay = base.toLocalDate().lengthOfMonth()
        return if (day <= 0) 1 else minOf(day, maxDay)
    }

    private fun poolHasTokensRemaining(pool: QuestPoolModel, data: net.nemoria.quest.data.user.UserData): Boolean {
        pool.quests.values.forEach { entry ->
            if (getPoolTokens(data, entry.questId) > 0) return true
        }
        pool.questGroups.values.forEach { entry ->
            val group = poolGroups[entry.groupId.lowercase()] ?: return@forEach
            group.quests.forEach { questId ->
                if (getPoolTokens(data, questId) > 0) return true
            }
        }
        return false
    }

    private fun countStartedInPool(pool: QuestPoolModel, data: net.nemoria.quest.data.user.UserData): Int {
        var count = 0
        pool.quests.values.forEach { entry ->
            if (data.activeQuests.contains(entry.questId)) count++
        }
        pool.questGroups.values.forEach { entry ->
            val group = poolGroups[entry.groupId.lowercase()] ?: return@forEach
            if (group.quests.any { data.activeQuests.contains(it) }) count++
        }
        return count
    }

    private fun checkPoolConditions(player: Player, conditions: List<ConditionEntry>, questId: String?): Boolean {
        if (conditions.isEmpty()) return true
        return checkConditions(player, conditions, questId)
    }

    private fun stopQuestIfActive(player: Player, questId: String) {
        if (data(player).activeQuests.contains(questId)) {
            stopQuest(player, questId, complete = false)
        }
    }

    private fun resetQuestHistory(player: OfflinePlayer, questId: String) {
        val data = data(player)
        val progress = data.progress[questId] ?: return
        progress.randomHistory.clear()
        progress.divergeCounts.clear()
        progress.nodeProgress.clear()
        progress.groupState.clear()
        data.progress[questId] = progress
        markDirty(player)
    }

    private fun setPoolTokens(data: net.nemoria.quest.data.user.UserData, questId: String, value: Int) {
        val key = questId.lowercase()
        if (value <= 0) {
            data.questPools.tokens.remove(key)
        } else {
            data.questPools.tokens[key] = value
        }
    }

    private fun alterPoolTokens(data: net.nemoria.quest.data.user.UserData, questId: String, delta: Int) {
        val key = questId.lowercase()
        val current = data.questPools.tokens[key] ?: 0
        val next = current + delta
        if (next <= 0) {
            data.questPools.tokens.remove(key)
        } else {
            data.questPools.tokens[key] = next
        }
    }

    private fun getPoolTokens(data: net.nemoria.quest.data.user.UserData, questId: String): Int {
        return data.questPools.tokens[questId.lowercase()] ?: 0
    }

    private fun randomTokens(min: Int, max: Int): Int {
        val safeMin = if (min <= 0) 1 else min
        val safeMax = if (max < safeMin) safeMin else max
        return ThreadLocalRandom.current().nextInt(safeMin, safeMax + 1)
    }

    private fun isPooledQuest(questId: String): Boolean =
        poolQuestIds.contains(questId.lowercase())

    private fun hasPoolToken(player: OfflinePlayer, questId: String): Boolean {
        val data = data(player)
        return getPoolTokens(data, questId) > 0
    }

    private fun consumePoolToken(player: OfflinePlayer, questId: String) {
        val data = data(player)
        alterPoolTokens(data, questId, -1)
        markDirty(player)
    }

    private fun handlePoolQuestEnd(player: OfflinePlayer, questId: String, outcome: String) {
        if (!isPooledQuest(questId)) return
        val outcomeKey = outcome.uppercase()
        val data = data(player)
        if (poolRefundTypesByQuestId[questId.lowercase()]?.contains(outcomeKey) == true) {
            alterPoolTokens(data, questId, 1)
        }
        if (!outcomeKey.equals("SUCCESS", true)) {
            markDirty(player)
            return
        }
        val model = questRepo.findById(questId) ?: QuestModel(questId)
        val pools = poolIdsByQuestId[questId.lowercase()].orEmpty()
        pools.forEach { poolId ->
            val pool = poolModels[poolId] ?: return@forEach
            if (poolHasTokensRemaining(pool, data)) return@forEach
            val poolData = data.questPools.pools.computeIfAbsent(poolId) { QuestPoolData() }
            poolData.streak += 1
            val streak = poolData.streak
            pool.rewards.forEach { reward ->
                if (streak < reward.minStreak) return@forEach
                if (streak > reward.maxStreak) return@forEach
                reward.node?.let {
                    executePoolRewardNode(player, model, it)
                    return@forEach
                }
                reward.reward?.let { executePoolReward(player, it) }
            }
        }
        markDirty(player)
    }

    private fun executePoolRewardNode(player: OfflinePlayer, model: QuestModel, node: QuestObjectNode) {
        branchRuntime.executeServerReward(player, model, node)
    }

    private fun executePoolReward(player: OfflinePlayer, reward: QuestEndObject) {
        when (reward.type) {
            QuestEndObjectType.SERVER_ACTIONS -> {
                val livePlayer = player.player
                reward.actions.forEach { act ->
                    val parts = act.trim().split("\\s+".toRegex(), limit = 2)
                    val key = parts.getOrNull(0)?.uppercase() ?: return@forEach
                    val payload = parts.getOrNull(1) ?: ""
                    when (key) {
                        "SEND_MESSAGE" -> livePlayer?.sendMessage(renderPoolMessage(payload, player))
                        "PERFORM_COMMAND" -> plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(payload, player))
                        "SEND_SOUND" -> runCatching {
                            livePlayer?.playSound(livePlayer.location, org.bukkit.Sound.valueOf(payload.uppercase()), 1f, 1f)
                        }
                        "SEND_TITLE" -> {
                            val pts = payload.split("\\s+".toRegex(), limit = 5)
                            val fi = pts.getOrNull(0)?.toIntOrNull() ?: 10
                            val st = pts.getOrNull(1)?.toIntOrNull() ?: 60
                            val fo = pts.getOrNull(2)?.toIntOrNull() ?: 10
                            val title = renderPoolMessage(pts.getOrNull(3) ?: "", player)
                            val subtitle = renderPoolMessage(pts.getOrNull(4) ?: "", player)
                            livePlayer?.sendTitle(title, subtitle, fi, st, fo)
                        }
                    }
                }
                reward.title?.let { t ->
                    livePlayer?.sendTitle(
                        renderPoolMessage(t.title ?: "", player),
                        renderPoolMessage(t.subtitle ?: "", player),
                        t.fadeIn, t.stay, t.fadeOut
                    )
                }
                reward.sound?.let { s ->
                    runCatching { org.bukkit.Sound.valueOf(s.uppercase()) }.onSuccess { snd ->
                        livePlayer?.playSound(livePlayer.location, snd, 1f, 1f)
                    }
                }
            }
            QuestEndObjectType.SERVER_COMMANDS_PERFORM -> {
                reward.commands.forEach { cmd ->
                    plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(cmd, player))
                }
            }
            QuestEndObjectType.SERVER_LOGIC_MONEY -> {
                val amount = evalValueFormula(reward.valueFormula) ?: 0
                if (reward.commands.isNotEmpty()) {
                    reward.commands.forEach { cmd ->
                        plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(cmd, player, amount))
                    }
                } else if (reward.currency != null) {
                    val cmd = "eco give ${player.name ?: ""} $amount"
                    plugin.server.dispatchCommand(plugin.server.consoleSender, cmd)
                }
            }
        }
    }

    private fun renderPoolMessage(raw: String, player: OfflinePlayer): String {
        val replaced = renderPlaceholders(raw, null, player)
        return MessageFormatter.format(replaced)
    }

    fun handleCitizensNpcActivator(player: Player, npcId: Int) {
        val cfg = citizensNpcActivatorConfig[npcId] ?: run {
            val questIds = citizensNpcActivatorIndex[npcId].orEmpty()
            if (questIds.isEmpty()) return
            CitizensNpcActivatorConfig(
                questIds = questIds,
                dialog = emptyList(),
                autoStartDistance = null,
                resetDelaySeconds = null,
                resetDistance = null,
                resetNotify = null,
                particlesAvailableScript = null,
                particlesProgressScript = null,
                particlesVerticalOffset = 0.0,
                sneakClickCancel = false,
                requiredGuiQuests = null
            )
        }

        if (cfg.sneakClickCancel && player.isSneaking) {
            return
        }

        val data = data(player)
        if (cfg.questIds.any { data.activeQuests.contains(it) }) {
            activatorDialogStates[player.uniqueId]?.remove(npcId)
            cancelActivatorDialogReset(player.uniqueId, npcId)
            openActivatorGuiIfAllowed(player, cfg)
            return
        }

        val dialogModel = run {
            for (questId in cfg.questIds) {
                val model = questRepo.findById(questId) ?: continue
                if (checkStartQuest(player, questId, model, data, viaCommand = false) == StartResult.SUCCESS) return@run model
            }
            null
        }
        if (dialogModel == null) {
            activatorDialogStates[player.uniqueId]?.remove(npcId)
            cancelActivatorDialogReset(player.uniqueId, npcId)
            openActivatorGuiIfAllowed(player, cfg)
            return
        }

        val dialog = dialogModel.activatorsDialog
        if (dialog.isEmpty()) {
            openActivatorGuiIfAllowed(player, cfg)
            return
        }

        val now = System.currentTimeMillis()
        val byNpc = activatorDialogStates.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val state = byNpc[npcId]
        if (state != null && state.questId != dialogModel.id) {
            byNpc.remove(npcId)
            cancelActivatorDialogReset(player.uniqueId, npcId)
        }
        val stateAfterQuestPick = byNpc[npcId]

        if (stateAfterQuestPick != null && shouldResetActivatorDialog(dialogModel.activatorsDialogResetDelaySeconds, stateAfterQuestPick, now)) {
            byNpc.remove(npcId)
            dialogModel.activatorsDialogResetNotify?.let { sendNotifySimple(player, it) }
        }

        val current = byNpc.computeIfAbsent(npcId) { ActivatorDialogState(idx = 0, lastAtMs = now, questId = dialogModel.id) }
        if (current.idx >= dialog.size) {
            byNpc.remove(npcId)
            cancelActivatorDialogReset(player.uniqueId, npcId)
            openActivatorGuiIfAllowed(player, cfg)
            return
        }

        val line = dialog.getOrNull(current.idx) ?: run {
            current.idx = dialog.size
            current.lastAtMs = now
            return
        }
        current.idx += 1
        current.lastAtMs = now
        scheduleActivatorDialogReset(
            player.uniqueId,
            npcId,
            delaySeconds = dialogModel.activatorsDialogResetDelaySeconds,
            resetNotify = dialogModel.activatorsDialogResetNotify,
            lastAtMs = current.lastAtMs
        )
        if (line.isBlank()) return
        MessageFormatter.send(player, line)
    }

    private fun sendNotifySimple(player: Player, notify: NotifySettings) {
        notify.message.forEach { MessageFormatter.send(player, it) }
        notify.sound?.let { s ->
            runCatching { org.bukkit.Sound.valueOf(s.uppercase()) }.onSuccess { snd ->
                player.playSound(player.location, snd, 1f, 1f)
            }
        }
    }

    private fun shouldResetActivatorDialog(resetDelaySeconds: Long?, state: ActivatorDialogState, nowMs: Long): Boolean {
        val delay = resetDelaySeconds
        return delay != null && nowMs - state.lastAtMs >= delay * 1000L
    }

    fun handleCitizensNpcActivatorMove(player: Player, from: Location, to: Location) {
        val now = beginCitizensNpcMoveCheck(player, to) ?: return
        resetActivatorDialogsOnMove(player)

        val hasAutoStart = citizensNpcAutoStartIds.isNotEmpty() && citizensNpcAutoStartQueryRadius > 0.0
        val hasParticles = citizensNpcParticlesIds.isNotEmpty()
        if (!hasAutoStart && !hasParticles) return
        if (citizensNpcProximityQueryRadius <= 0.0) return

        processCitizensNpcProximity(player, to, now, hasAutoStart, hasParticles)
    }

    private fun beginCitizensNpcMoveCheck(player: Player, to: Location): Long? {
        if (citizensNpcMoveQueryRadius <= 0.0) return null
        if (plugin.server.pluginManager.getPlugin("Citizens") == null) return null
        if (citizensNpcProximityIds.isEmpty() && activatorDialogStates[player.uniqueId].isNullOrEmpty()) return null

        val blockKey = (((to.blockX.toLong() and 0x3FFFFFF) shl 38) or ((to.blockZ.toLong() and 0x3FFFFFF) shl 12) or (to.blockY.toLong() and 0xFFF))
        if (lastActivatorMoveBlockKey[player.uniqueId] == blockKey) return null
        lastActivatorMoveBlockKey[player.uniqueId] = blockKey

        val now = System.currentTimeMillis()
        val last = lastActivatorMoveCheckAtMs[player.uniqueId] ?: 0L
        if (now - last < 500L) return null
        lastActivatorMoveCheckAtMs[player.uniqueId] = now
        return now
    }

    private fun resetActivatorDialogsOnMove(player: Player) {
        val states = activatorDialogStates[player.uniqueId]
        if (states != null && states.isNotEmpty()) {
            val toRemove = mutableListOf<Int>()
            states.forEach { (npcId, _) ->
                val cfg = citizensNpcActivatorConfig[npcId] ?: return@forEach
                val dist = cfg.resetDistance ?: return@forEach
                val npcEntity = resolveCitizensNpcEntity(npcId)
                if (npcEntity == null || npcEntity.world != player.world) {
                    toRemove.add(npcId)
                    cfg.resetNotify?.let { sendNotifySimple(player, it) }
                    cancelActivatorDialogReset(player.uniqueId, npcId)
                    return@forEach
                }
                if (player.location.distanceSquared(npcEntity.location) > dist * dist) {
                    toRemove.add(npcId)
                    cfg.resetNotify?.let { sendNotifySimple(player, it) }
                    cancelActivatorDialogReset(player.uniqueId, npcId)
                }
            }
            toRemove.forEach { states.remove(it) }
        }
    }

    private fun processCitizensNpcProximity(
        player: Player,
        to: Location,
        now: Long,
        hasAutoStart: Boolean,
        hasParticles: Boolean
    ) {
        val perNpc = activatorDialogStates.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val worldIndex = citizensNpcIdsByWorldChunk[player.world.name] ?: return
        val chunkRadius = kotlin.math.ceil(citizensNpcProximityQueryRadius / 16.0).toInt().coerceAtLeast(1)
        val centerChunkX = to.blockX shr 4
        val centerChunkZ = to.blockZ shr 4
        val playerLoc = player.location
        val particlesRangeSq = citizensActivatorParticlesRange * citizensActivatorParticlesRange
        val dataForParticles = if (hasParticles) data(player) else null
        val runsByNpc = activatorParticleRuns.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        for (dx in -chunkRadius..chunkRadius) {
            for (dz in -chunkRadius..chunkRadius) {
                val key = makeChunkKey(centerChunkX + dx, centerChunkZ + dz)
                val ids = worldIndex[key] ?: continue
                ids.forEach { npcId ->
                    val cfg = citizensNpcActivatorConfig[npcId] ?: return@forEach
                    val npcEntity = resolveCitizensNpcEntity(npcId) ?: return@forEach
                    val distSq = playerLoc.distanceSquared(npcEntity.location)
                    if (handleCitizensNpcProximityEntry(
                            player,
                            npcId,
                            cfg,
                            npcEntity,
                            distSq,
                            now,
                            hasAutoStart,
                            hasParticles,
                            perNpc,
                            particlesRangeSq,
                            dataForParticles,
                            runsByNpc
                        )
                    ) {
                        return@forEach
                    }
                }
            }
        }
    }

    private fun handleCitizensNpcProximityEntry(
        player: Player,
        npcId: Int,
        cfg: CitizensNpcActivatorConfig,
        npcEntity: Entity,
        distSq: Double,
        now: Long,
        hasAutoStart: Boolean,
        hasParticles: Boolean,
        perNpc: MutableMap<Int, ActivatorDialogState>,
        particlesRangeSq: Double,
        dataForParticles: net.nemoria.quest.data.user.UserData?,
        runsByNpc: MutableMap<Int, ActivatorParticleRun>
    ): Boolean {
        if (npcEntity.world != player.world) return true
        if (!citizensNpcProximityIds.contains(npcId)) return true

        if (hasAutoStart && citizensNpcAutoStartIds.contains(npcId) && !perNpc.containsKey(npcId)) {
            if (handleActivatorAutoStart(player, npcId, cfg, distSq, now, perNpc)) return true
        }

        if (hasParticles && citizensNpcParticlesIds.contains(npcId) && distSq <= particlesRangeSq) {
            if (handleActivatorParticles(player, npcId, cfg, npcEntity, particlesRangeSq, dataForParticles, runsByNpc)) {
                return true
            }
        }

        return false
    }

    private fun handleActivatorAutoStart(
        player: Player,
        npcId: Int,
        cfg: CitizensNpcActivatorConfig,
        distSq: Double,
        now: Long,
        perNpc: MutableMap<Int, ActivatorDialogState>
    ): Boolean {
        val dist = cfg.autoStartDistance ?: return true
        if (distSq > dist * dist) return false
        val dataForDialog = data(player)
        if (cfg.questIds.any { dataForDialog.activeQuests.contains(it) }) return true
        val modelForDialog = run {
            for (questId in cfg.questIds) {
                val model = questRepo.findById(questId) ?: continue
                if (checkStartQuest(player, questId, model, dataForDialog, viaCommand = false) == StartResult.SUCCESS) return@run model
            }
            null
        } ?: return true
        val dialog = modelForDialog.activatorsDialog
        if (dialog.isEmpty()) return true

        val existing = perNpc[npcId]
        val st = when {
            existing == null || existing.questId != modelForDialog.id -> {
                cancelActivatorDialogReset(player.uniqueId, npcId)
                ActivatorDialogState(idx = 0, lastAtMs = now, questId = modelForDialog.id).also { perNpc[npcId] = it }
            }
            else -> existing
        }
        if (st.idx >= dialog.size) return true
        val line = dialog.getOrNull(st.idx) ?: return true
        st.idx += 1
        st.lastAtMs = now
        scheduleActivatorDialogReset(
            player.uniqueId,
            npcId,
            delaySeconds = modelForDialog.activatorsDialogResetDelaySeconds,
            resetNotify = modelForDialog.activatorsDialogResetNotify,
            lastAtMs = st.lastAtMs
        )
        if (line.isNotBlank()) MessageFormatter.send(player, line)
        return false
    }

    private fun handleActivatorParticles(
        player: Player,
        npcId: Int,
        cfg: CitizensNpcActivatorConfig,
        npcEntity: Entity,
        particlesRangeSq: Double,
        dataForParticles: net.nemoria.quest.data.user.UserData?,
        runsByNpc: MutableMap<Int, ActivatorParticleRun>
    ): Boolean {
        val desiredStatus = resolveActivatorParticlesStatus(player, cfg, dataForParticles)
        if (desiredStatus.isBlank()) return false
        val scriptId = resolveActivatorParticlesScriptId(cfg, desiredStatus) ?: return true
        if (shouldSkipExistingActivatorParticleRun(npcId, desiredStatus, scriptId, runsByNpc)) return true
        val handle = startActivatorParticleRun(player, npcEntity, scriptId, particlesRangeSq, cfg.particlesVerticalOffset) ?: return true
        runsByNpc[npcId] = ActivatorParticleRun(desiredStatus, scriptId, handle)
        return false
    }

    private fun resolveActivatorParticlesStatus(
        player: Player,
        cfg: CitizensNpcActivatorConfig,
        dataForParticles: net.nemoria.quest.data.user.UserData?
    ): String {
        val data = dataForParticles ?: return ""
        if (cfg.questIds.any { data.activeQuests.contains(it) }) return "PROGRESS"
        if (cfg.particlesAvailableScript.isNullOrBlank()) return ""
        for (questId in cfg.questIds) {
            val model = questRepo.findById(questId) ?: continue
            if (checkStartQuest(player, questId, model, data, viaCommand = false) == StartResult.SUCCESS) {
                return "AVAILABLE"
            }
        }
        return ""
    }

    private fun resolveActivatorParticlesScriptId(
        cfg: CitizensNpcActivatorConfig,
        desiredStatus: String
    ): String? {
        val scriptId = when (desiredStatus) {
            "PROGRESS" -> cfg.particlesProgressScript
            "AVAILABLE" -> cfg.particlesAvailableScript
            else -> null
        }
        return if (scriptId.isNullOrBlank()) null else scriptId
    }

    private fun shouldSkipExistingActivatorParticleRun(
        npcId: Int,
        desiredStatus: String,
        scriptId: String,
        runsByNpc: MutableMap<Int, ActivatorParticleRun>
    ): Boolean {
        val existing = runsByNpc[npcId] ?: return false
        if (existing.status == desiredStatus && existing.scriptId == scriptId) {
            if (branchRuntime.isParticleScriptActive(existing.handle)) return true
            runsByNpc.remove(npcId)
            return false
        }
        branchRuntime.stopParticleScript(existing.handle)
        runsByNpc.remove(npcId)
        return false
    }

    private fun startActivatorParticleRun(
        player: Player,
        npcEntity: Entity,
        scriptId: String,
        particlesRangeSq: Double,
        offsetY: Double
    ): ParticleScriptEngine.Handle? {
        val npcUuid = npcEntity.uniqueId
        val npcWorld = npcEntity.world
        val viewerId = player.uniqueId
        val provider = provider@{
            val p = plugin.server.getPlayer(viewerId) ?: return@provider null
            val e = npcWorld.getEntity(npcUuid) ?: return@provider null
            if (p.world != npcWorld) return@provider null
            if (p.location.distanceSquared(e.location) > particlesRangeSq) return@provider null
            e.location.clone().add(0.0, offsetY, 0.0)
        }
        return branchRuntime.startParticleScriptLoopAtLocation(player, scriptId, provider)
    }

    private fun openActivatorGuiIfAllowed(player: Player, cfg: CitizensNpcActivatorConfig) {
        val required = cfg.requiredGuiQuests ?: 0
        if (required > 0) {
            val data = data(player)
            var count = 0
            for (questId in cfg.questIds) {
                if (data.activeQuests.contains(questId)) {
                    count++
                    if (count >= required) break
                    continue
                }
                val model = questRepo.findById(questId) ?: continue
                if (checkStartQuest(player, questId, model, data, viaCommand = false) == StartResult.SUCCESS) {
                    count++
                    if (count >= required) break
                }
            }
            if (count < required) return
        }
        Services.guiManager.openListFiltered(player, Services.guiDefault, cfg.questIds.toSet())
    }

    private fun checkStartQuest(player: Player, questId: String, model: QuestModel, data: net.nemoria.quest.data.user.UserData, viaCommand: Boolean): StartResult {
        if (data.activeQuests.contains(questId)) return StartResult.ALREADY_ACTIVE
        if (model.completion.maxCompletions == 1 && data.completedQuests.contains(questId)) return StartResult.COMPLETION_LIMIT
        if (!model.permissionStartRestriction.isNullOrBlank() && !player.hasPermission(model.permissionStartRestriction)) return StartResult.PERMISSION_FAIL
        if (viaCommand && !model.permissionStartCommandRestriction.isNullOrBlank() && !player.hasPermission(model.permissionStartCommandRestriction)) return StartResult.PERMISSION_FAIL
        if (!worldAllowed(model, player.world.name)) return StartResult.WORLD_RESTRICTED
        if (isOnCooldown(player, model)) return StartResult.COOLDOWN
        if (!requirementsMet(model, data)) return StartResult.REQUIREMENT_FAIL
        if (!conditionsMet(model, player)) return StartResult.CONDITION_FAIL
        if (isPooledQuest(questId) && !hasPoolToken(player, questId)) return StartResult.REQUIREMENT_FAIL
        if (!hasStartNode(model)) return StartResult.INVALID_BRANCH
        return StartResult.SUCCESS
    }

    private fun scheduleActivatorDialogReset(playerId: UUID, npcId: Int, delaySeconds: Long?, resetNotify: NotifySettings?, lastAtMs: Long) {
        val delay = delaySeconds ?: return
        val perPlayer = activatorDialogResetTasks.computeIfAbsent(playerId) { ConcurrentHashMap() }
        perPlayer[npcId]?.cancel()
        perPlayer[npcId] = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val state = activatorDialogStates[playerId]?.get(npcId) ?: return@Runnable
            if (state.lastAtMs != lastAtMs) return@Runnable
            activatorDialogStates[playerId]?.remove(npcId)
            cancelActivatorDialogReset(playerId, npcId)
            val player = plugin.server.getPlayer(playerId) ?: return@Runnable
            resetNotify?.let { sendNotifySimple(player, it) }
        }, delay * 20L)
    }

    private fun cancelActivatorDialogReset(playerId: UUID, npcId: Int) {
        val perPlayer = activatorDialogResetTasks[playerId] ?: return
        perPlayer.remove(npcId)?.cancel()
        if (perPlayer.isEmpty()) activatorDialogResetTasks.remove(playerId)
    }

    private fun startCitizensNpcIndexTask() {
        citizensNpcIndexTask?.cancel()
        citizensNpcIndexTask = null
        if (citizensNpcIndexedIds.isEmpty()) return

        citizensNpcIndexCursor = 0
        citizensNpcIndexTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val size = citizensNpcIndexedIds.size
            if (size == 0) return@Runnable
            repeat(10) {
                val idx = citizensNpcIndexCursor
                citizensNpcIndexCursor = if (idx + 1 >= size) 0 else idx + 1
                val npcId = citizensNpcIndexedIds[idx]
                val entity = resolveCitizensNpcEntity(npcId)
                if (entity == null) {
                    removeCitizensNpcFromIndex(npcId)
                } else {
                    updateCitizensNpcIndex(npcId, entity)
                }
            }
        }, 1L, 1L)
    }

    private fun resolveCitizensNpcEntity(npcId: Int): Entity? {
        val cachedUuid = citizensNpcEntityUuidById[npcId]
        if (cachedUuid != null) {
            val e = plugin.server.getEntity(cachedUuid) as? Entity
            if (e != null) return e
        }

        val registry = citizensRegistry ?: runCatching {
            val api = Class.forName("net.citizensnpcs.api.CitizensAPI")
            api.getMethod("getNPCRegistry").invoke(null)
        }.getOrNull()?.also { citizensRegistry = it } ?: return null

        val getById = citizensGetByIdMethod ?: runCatching {
            registry.javaClass.getMethod("getById", Int::class.javaPrimitiveType)
        }.getOrNull()?.also { citizensGetByIdMethod = it } ?: return null

        val npc = runCatching { getById.invoke(registry, npcId) }.getOrNull() ?: return null
        val getEntity = citizensNpcGetEntityMethod ?: runCatching { npc.javaClass.getMethod("getEntity") }.getOrNull()?.also { citizensNpcGetEntityMethod = it } ?: return null
        val entity = runCatching { getEntity.invoke(npc) as? Entity }.getOrNull() ?: return null
        citizensNpcEntityUuidById[npcId] = entity.uniqueId
        return entity
    }

    private fun updateCitizensNpcIndex(npcId: Int, entity: Entity) {
        val worldName = entity.world.name
        val chunkKey = makeChunkKey(entity.location.blockX shr 4, entity.location.blockZ shr 4)
        val newKey = worldName to chunkKey
        val oldKey = citizensNpcChunkById.put(npcId, newKey)
        if (oldKey != null && oldKey != newKey) {
            val oldWorldIndex = citizensNpcIdsByWorldChunk[oldKey.first]
            oldWorldIndex?.get(oldKey.second)?.remove(npcId)
        }
        val worldIndex = citizensNpcIdsByWorldChunk.computeIfAbsent(worldName) { ConcurrentHashMap() }
        val set = worldIndex.computeIfAbsent(chunkKey) { ConcurrentHashMap.newKeySet() }
        set.add(npcId)
    }

    private fun removeCitizensNpcFromIndex(npcId: Int) {
        val oldKey = citizensNpcChunkById.remove(npcId)
        if (oldKey != null) {
            val worldIndex = citizensNpcIdsByWorldChunk[oldKey.first]
            worldIndex?.get(oldKey.second)?.remove(npcId)
        }
        citizensNpcEntityUuidById.remove(npcId)
    }

    private fun makeChunkKey(chunkX: Int, chunkZ: Int): Long {
        return (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xffffffffL)
    }

    private fun applyCompletedSaves(now: Long) {
        while (true) {
            val entry = completedSaves.poll() ?: break
            val (uuid, version) = entry
            pendingSaves.remove(uuid)
            val cached = userCache[uuid] ?: continue
            if (cached.version == version) {
                cached.dirty = false
                cached.lastAccess = now
            }
        }
    }

    private fun flushDirty(forceAll: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceAll) {
            applyCompletedSaves(now)
        }
        var savedCount = 0
        userCache.forEach { (uuid, cached) ->
            if (forceAll) {
                val snapshot = copiedUser(cached.data)
                userRepo.save(snapshot)
                cached.dirty = false
                cached.lastAccess = now
                savedCount++
                return@forEach
            }
            if (!cached.dirty) return@forEach
            if (pendingSaves.containsKey(uuid)) return@forEach
            val versionSnapshot = cached.version
            val snapshot = copiedUser(cached.data)
            pendingSaves[uuid] = versionSnapshot
            saveQueue.add(SaveRequest(uuid, versionSnapshot, snapshot))
            savedCount++
        }
        if (forceAll) {
            pendingSaves.clear()
            saveQueue.clear()
            completedSaves.clear()
        }
        if (forceAll || savedCount > 0) {
            DebugLog.logToFile("debug-session", "run1", "A", "QuestService.kt:87", "flushDirty completed", mapOf("forceAll" to forceAll, "cacheSize" to userCache.size, "savedCount" to savedCount))
        }
        userCache.keys.forEach { uuid ->
            userCache.computeIfPresent(uuid) { _, current ->
                val expired = now - current.lastAccess > cacheTtlMs
                if (!current.dirty && expired) null else current
            }
        }
    }

    fun startQuest(player: OfflinePlayer, questId: String, viaCommand: Boolean = false): StartResult {
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:123", "startQuest entry", mapOf("questId" to questId, "playerUuid" to player.uniqueId.toString(), "playerName" to (player.name ?: "null"), "viaCommand" to viaCommand))
        val model = questRepo.findById(questId)
        if (model == null) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:124", "startQuest NOT_FOUND", mapOf("questId" to questId))
            return StartResult.NOT_FOUND
        }
        net.nemoria.quest.core.DebugLog.log("startQuest questId=$questId player=${player.name}")
        val bukkitPlayer = player.player
        if (bukkitPlayer == null) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:126", "startQuest OFFLINE", mapOf("questId" to questId, "playerUuid" to player.uniqueId.toString()))
            return StartResult.OFFLINE
        }
        val data = data(player)
        if (data.activeQuests.contains(questId)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:128", "startQuest ALREADY_ACTIVE", mapOf("questId" to questId, "playerUuid" to player.uniqueId.toString()))
            return StartResult.ALREADY_ACTIVE
        }
        if (model.completion.maxCompletions == 1 && data.completedQuests.contains(questId)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:129", "startQuest COMPLETION_LIMIT", mapOf("questId" to questId, "playerUuid" to player.uniqueId.toString()))
            return StartResult.COMPLETION_LIMIT
        }
        if (!model.permissionStartRestriction.isNullOrBlank() && !bukkitPlayer.hasPermission(model.permissionStartRestriction)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:130", "startQuest PERMISSION_FAIL", mapOf("questId" to questId, "permission" to model.permissionStartRestriction))
            return StartResult.PERMISSION_FAIL
        }
        if (viaCommand && !model.permissionStartCommandRestriction.isNullOrBlank() && !bukkitPlayer.hasPermission(model.permissionStartCommandRestriction)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:131", "startQuest PERMISSION_FAIL (command)", mapOf("questId" to questId, "permission" to model.permissionStartCommandRestriction))
            return StartResult.PERMISSION_FAIL
        }
        if (!worldAllowed(model, bukkitPlayer.world.name)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:132", "startQuest WORLD_RESTRICTED", mapOf("questId" to questId, "world" to bukkitPlayer.world.name))
            return StartResult.WORLD_RESTRICTED
        }
        if (isOnCooldown(player, model)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:133", "startQuest COOLDOWN", mapOf("questId" to questId, "playerUuid" to player.uniqueId.toString()))
            return StartResult.COOLDOWN
        }
        if (!requirementsMet(model, data)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:134", "startQuest REQUIREMENT_FAIL", mapOf("questId" to questId, "requirements" to model.requirements.size))
            return StartResult.REQUIREMENT_FAIL
        }
        if (!conditionsMet(model, bukkitPlayer)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:135", "startQuest CONDITION_FAIL", mapOf("questId" to questId))
            return StartResult.CONDITION_FAIL
        }
        if (isPooledQuest(questId) && !hasPoolToken(player, questId)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:136", "startQuest POOL_TOKEN_FAIL", mapOf("questId" to questId, "playerUuid" to player.uniqueId.toString()))
            return StartResult.REQUIREMENT_FAIL
        }
        if (!hasStartNode(model)) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:136", "startQuest INVALID_BRANCH", mapOf("questId" to questId, "branchesCount" to model.branches.size))
            return StartResult.INVALID_BRANCH
        }
        data.activeQuests.add(model.id)
        val progress = QuestProgress()
        progress.variables.putAll(model.variables)
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:137", "startQuest creating progress", mapOf("questId" to questId, "objectivesCount" to model.objectives.size, "variablesCount" to model.variables.size))
        model.objectives.forEach { obj ->
            progress.objectives[obj.id] = ObjectiveState(completed = false, startedAt = System.currentTimeMillis())
            if (obj.type == QuestObjectiveType.TIMER && obj.durationSeconds != null) {
                DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:142", "startQuest scheduling timer", mapOf("questId" to questId, "objectiveId" to obj.id, "durationSeconds" to obj.durationSeconds))
                scheduleTimer(player, questId, obj.id, obj.durationSeconds)
            }
        }
        if (model.timeLimit != null) {
            progress.timeLimitStartedAt = System.currentTimeMillis()
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:146", "startQuest timeLimit set", mapOf("questId" to questId, "timeLimitSeconds" to model.timeLimit.durationSeconds))
        }
        // set initial branch state
        if (model.branches.isNotEmpty()) {
            val branchId = model.mainBranch ?: model.branches.keys.firstOrNull()
            progress.currentBranchId = branchId
            progress.currentNodeId = branchId?.let { b -> model.branches[b]?.startsAt ?: model.branches[b]?.objects?.keys?.firstOrNull() }
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:152", "startQuest branch state", mapOf("questId" to questId, "branchId" to (branchId ?: "null"), "nodeId" to (progress.currentNodeId ?: "null")))
        }
        data.progress[questId] = progress
        if (isPooledQuest(questId)) {
            consumePoolToken(player, questId)
        }
        markDirty(player)
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:157", "startQuest starting branchRuntime", mapOf("questId" to questId))
        branchRuntime.start(player, model)
        if (model.timeLimit != null) {
            scheduleQuestTimeLimit(player, model)
        }
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:161", "startQuest SUCCESS", mapOf("questId" to questId, "playerUuid" to player.uniqueId.toString()))
        return StartResult.SUCCESS
    }

    private fun hasStartNode(model: QuestModel): Boolean {
        if (model.branches.isEmpty()) return true
        val branchId = model.mainBranch ?: model.branches.keys.firstOrNull() ?: return false
        val branch = model.branches[branchId] ?: return false
        if (branch.objects.isEmpty()) return false
        val startNode = branch.startsAt ?: branch.objects.keys.firstOrNull() ?: return false
        return branch.objects.containsKey(startNode)
    }

    fun stopQuest(player: OfflinePlayer, questId: String, complete: Boolean) {
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:173", "stopQuest entry", mapOf("questId" to questId, "playerUuid" to player.uniqueId.toString(), "complete" to complete))
        val data = data(player)
        data.activeQuests.remove(questId)
        data.progress.remove(questId)
        if (complete) {
            data.completedQuests.add(questId)
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:178", "stopQuest applying rewards", mapOf("questId" to questId))
            questRepo.findById(questId)?.let { applyRewards(player, it) }
        }
        markDirty(player)
        cancelQuestTimeLimit(player, questId)
        cancelObjectiveTimers(player, questId)
        clearActivatorParticleRunsForQuest(player.uniqueId, questId)
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:184", "stopQuest stopping branchRuntime", mapOf("questId" to questId))
        branchRuntime.stop(player)
    }

    fun activeQuests(player: OfflinePlayer): Set<String> {
        val active = data(player).activeQuests
        val sorted = active.sortedWith(
            compareBy<String> { questInfo(it)?.displayPriority ?: Int.MAX_VALUE }
                .thenBy { it }
        )
        return java.util.LinkedHashSet(sorted)
    }

    fun completedQuests(player: OfflinePlayer): Set<String> {
        return data(player).completedQuests.toSet()
    }

    fun hasDiverge(player: OfflinePlayer): Boolean = branchRuntime.hasDiverge(player)

    fun progress(player: OfflinePlayer): Map<String, QuestProgress> =
        data(player).progress.toMap()

    fun listQuests(): Collection<QuestModel> = questRepo.findAll()

    fun questInfo(id: String): QuestModel? = questRepo.findById(id)

    fun gotoNode(player: org.bukkit.entity.Player, questId: String, branchId: String, nodeId: String): Boolean {
        val data = data(player)
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
        val data = data(player)
        val qp = data.progress[questId] ?: return
        qp.nodeProgress[progressKey(branchId, nodeId, goalId)] = value
        markDirty(player)
    }

    internal fun loadNodeProgress(player: OfflinePlayer, questId: String, branchId: String, nodeId: String, goalId: String? = null): Double {
        val data = data(player)
        val qp = data.progress[questId] ?: return 0.0
        return qp.nodeProgress[progressKey(branchId, nodeId, goalId)] ?: 0.0
    }

    internal fun loadNodeGoalProgress(player: OfflinePlayer, questId: String, branchId: String, nodeId: String): Map<String, Double> {
        val data = data(player)
        val qp = data.progress[questId] ?: return emptyMap()
        val prefix = progressKey(branchId, nodeId)
        return qp.nodeProgress.filterKeys { it.startsWith("$prefix:") }.mapKeys { it.key.removePrefix("$prefix:") }
    }

    internal fun clearNodeProgress(player: OfflinePlayer, questId: String, branchId: String, nodeId: String) {
        val data = data(player)
        val qp = data.progress[questId] ?: return
        val prefix = progressKey(branchId, nodeId)
        qp.nodeProgress.keys.removeIf { it == prefix || it.startsWith("$prefix:") }
        markDirty(player)
    }

    internal fun clearAllNodeProgress(player: OfflinePlayer, questId: String) {
        val data = data(player)
        val qp = data.progress[questId] ?: return
        qp.nodeProgress.clear()
        markDirty(player)
    }

    fun completeObjective(player: OfflinePlayer, questId: String, objectiveId: String) {
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:263", "completeObjective entry", mapOf("questId" to questId, "objectiveId" to objectiveId, "playerUuid" to player.uniqueId.toString()))
        val data = data(player)
        val progress = data.progress[questId]
        if (progress == null) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:265", "completeObjective no progress", mapOf("questId" to questId, "objectiveId" to objectiveId))
            return
        }
        val state = progress.objectives[objectiveId]
        if (state == null) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:266", "completeObjective no state", mapOf("questId" to questId, "objectiveId" to objectiveId))
            return
        }
        if (state.completed) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:267", "completeObjective already completed", mapOf("questId" to questId, "objectiveId" to objectiveId))
            return
        }
        state.completed = true
        state.completedAt = System.currentTimeMillis()
        data.progress[questId] = progress
        markDirty(player)
        cancelObjectiveTimer(player, questId, objectiveId)
        val allDone = progress.objectives.values.all { it.completed }
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:273", "completeObjective completed", mapOf("questId" to questId, "objectiveId" to objectiveId, "allDone" to allDone, "totalObjectives" to progress.objectives.size))
        if (allDone) {
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:275", "completeObjective finishing quest", mapOf("questId" to questId))
            finishOutcome(player, questId, "SUCCESS")
        }
    }

    fun incrementObjective(player: OfflinePlayer, questId: String, objectiveId: String, required: Int) {
        val data = data(player)
        val progress = data.progress[questId] ?: return
        val state = progress.objectives[objectiveId] ?: return
        if (state.completed) return
        state.progress += 1
        if (state.progress >= required) {
            completeObjective(player, questId, objectiveId)
        } else {
            data.progress[questId] = progress
            markDirty(player)
        }
    }

    private fun scheduleTimer(player: OfflinePlayer, questId: String, objectiveId: String, durationSeconds: Long) {
        DebugLog.logToFile("debug-session", "run1", "B", "QuestService.kt:278", "scheduleTimer entry", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "objectiveId" to objectiveId, "durationSeconds" to durationSeconds))
        val task = object : BukkitRunnable() {
            override fun run() {
                DebugLog.logToFile("debug-session", "run1", "B", "QuestService.kt:281", "scheduleTimer task run", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "objectiveId" to objectiveId))
                completeObjective(player, questId, objectiveId)
            }
        }.runTaskLater(plugin, durationSeconds * 20)
        val existingTask = objectiveTimers[player.uniqueId]?.get(questId)?.get(objectiveId)
        DebugLog.logToFile("debug-session", "run1", "B", "QuestService.kt:285", "scheduleTimer before computeIfAbsent", mapOf("playerUuid" to player.uniqueId.toString(), "hasExistingTask" to (existingTask != null)))
        objectiveTimers.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
            .computeIfAbsent(questId) { ConcurrentHashMap() }[objectiveId]?.cancel()
        val mapAfter = objectiveTimers[player.uniqueId]?.get(questId)
        DebugLog.logToFile("debug-session", "run1", "B", "QuestService.kt:286", "scheduleTimer after computeIfAbsent", mapOf("playerUuid" to player.uniqueId.toString(), "mapExists" to (mapAfter != null)))
        objectiveTimers[player.uniqueId]?.get(questId)?.put(objectiveId, task)
    }

    private fun scheduleQuestTimeLimit(player: OfflinePlayer, model: QuestModel) {
        val tl = model.timeLimit ?: return
        val startedAt = ensureTimeLimitStart(player, model.id)
        val remainingMs = tl.durationSeconds * 1000 - (System.currentTimeMillis() - startedAt)
        if (remainingMs <= 0) {
            tl.failGoto?.let { /* brak gałęzi, goto ignorujemy */ }
            finishOutcome(player, model.id, "FAIL")
            return
        }
        val ticks = ((remainingMs + 49) / 50).coerceAtLeast(1)
        val task = object : BukkitRunnable() {
            override fun run() {
                tl.failGoto?.let { /* brak gałęzi, goto ignorujemy */ }
                finishOutcome(player, model.id, "FAIL")
            }
        }.runTaskLater(plugin, ticks)
        questTimeLimitTasks.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[model.id]?.cancel()
        questTimeLimitTasks[player.uniqueId]?.put(model.id, task)
        tl.reminder?.let { rem ->
            val intervalSec = tl.reminderIntervalSeconds ?: 60L
            val period = (intervalSec * 20).coerceAtLeast(20)
            val reminderTask = object : BukkitRunnable() {
                override fun run() {
                    val remain = tl.durationSeconds * 1000 - (System.currentTimeMillis() - ensureTimeLimitStart(player, model.id))
                    if (remain <= 0) { cancel(); return }
                    val secondsLeft = (remain + 999) / 1000
                    sendNotify(player, model, rem, mapOf("time" to formatDuration(secondsLeft)))
                }
            }.runTaskTimer(plugin, period, period)
            questTimeLimitReminders.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[model.id]?.cancel()
            questTimeLimitReminders[player.uniqueId]?.put(model.id, reminderTask)
        }
    }

    private fun cancelQuestTimeLimit(player: OfflinePlayer, questId: String) {
        questTimeLimitTasks[player.uniqueId]?.remove(questId)?.cancel()
        if (questTimeLimitTasks[player.uniqueId]?.isEmpty() == true) {
            questTimeLimitTasks.remove(player.uniqueId)
        }
        questTimeLimitReminders[player.uniqueId]?.remove(questId)?.cancel()
        if (questTimeLimitReminders[player.uniqueId]?.isEmpty() == true) {
            questTimeLimitReminders.remove(player.uniqueId)
        }
    }

    private fun cancelObjectiveTimer(player: OfflinePlayer, questId: String, objectiveId: String) {
        objectiveTimers[player.uniqueId]?.get(questId)?.remove(objectiveId)?.cancel()
        objectiveTimers[player.uniqueId]?.get(questId)?.let { if (it.isEmpty()) objectiveTimers[player.uniqueId]?.remove(questId) }
        if (objectiveTimers[player.uniqueId]?.isEmpty() == true) {
            objectiveTimers.remove(player.uniqueId)
        }
    }

    private fun cancelObjectiveTimers(player: OfflinePlayer, questId: String) {
        objectiveTimers[player.uniqueId]?.remove(questId)?.values?.forEach { it.cancel() }
        if (objectiveTimers[player.uniqueId]?.isEmpty() == true) {
            objectiveTimers.remove(player.uniqueId)
        }
    }

    internal fun ensureTimeLimitStart(player: OfflinePlayer, questId: String): Long {
        val data = data(player)
        val progress = data.progress[questId] ?: return System.currentTimeMillis()
        val existing = progress.timeLimitStartedAt
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        progress.timeLimitStartedAt = now
        data.progress[questId] = progress
        markDirty(player)
        return now
    }

    fun resumeQuestTimeLimit(player: OfflinePlayer, model: QuestModel) {
        if (model.timeLimit == null || model.branches.isNotEmpty()) return
        scheduleQuestTimeLimit(player, model)
    }

    private fun copiedUser(src: net.nemoria.quest.data.user.UserData): net.nemoria.quest.data.user.UserData {
        val progressCopy = src.progress.mapValues { (_, qp) -> copyQuestProgress(qp) }.toMutableMap()
        return net.nemoria.quest.data.user.UserData(
            uuid = src.uuid,
            activeQuests = src.activeQuests.toMutableSet(),
            completedQuests = src.completedQuests.toMutableSet(),
            progress = progressCopy,
            userVariables = src.userVariables.toMutableMap(),
            cooldowns = src.cooldowns.toMutableMap(),
            questPools = copyQuestPools(src.questPools),
            actionbarEnabled = src.actionbarEnabled,
            titleEnabled = src.titleEnabled
        )
    }

    private fun copyQuestPools(src: QuestPoolsState): QuestPoolsState {
        val poolsCopy = src.pools.mapValues { (_, pd) ->
            QuestPoolData(
                streak = pd.streak,
                lastProcessedFrame = pd.lastProcessedFrame.toMutableMap(),
                limitedFrames = pd.limitedFrames.toMutableMap()
            )
        }.toMutableMap()
        return QuestPoolsState(
            pools = poolsCopy,
            tokens = src.tokens.toMutableMap()
        )
    }

    private fun copyQuestProgress(src: QuestProgress): QuestProgress {
        val objectivesCopy = src.objectives.mapValues { (_, st) -> ObjectiveState(st.completed, st.startedAt, st.completedAt, st.progress) }.toMutableMap()
        return QuestProgress(
            objectives = objectivesCopy,
            variables = src.variables.toMutableMap(),
            currentBranchId = src.currentBranchId,
            currentNodeId = src.currentNodeId,
            timeLimitStartedAt = src.timeLimitStartedAt,
            randomHistory = src.randomHistory.toMutableSet(),
            groupState = src.groupState.mapValues { (_, gs) -> GroupProgress(gs.completed.toMutableSet(), gs.remaining.toMutableList(), gs.required, gs.ordered) }.toMutableMap(),
            divergeCounts = src.divergeCounts.toMutableMap(),
            nodeProgress = src.nodeProgress.toMutableMap()
        )
    }

    fun resumeTimers(player: Player, model: QuestModel) {
        val data = data(player)
        val progress = data.progress[model.id] ?: return
        val now = System.currentTimeMillis()
        var changed = false
        model.objectives.forEach { obj ->
            if (obj.type == QuestObjectiveType.TIMER && obj.durationSeconds != null) {
                val state = progress.objectives[obj.id] ?: return@forEach
                if (state.completed) return@forEach
                val started = state.startedAt ?: run {
                    state.startedAt = now
                    changed = true
                    now
                }
                val remainingMs = obj.durationSeconds * 1000 - (now - started)
                if (remainingMs <= 0) {
                    completeObjective(player, model.id, obj.id)
                } else {
                    val remainingSec = (remainingMs + 999) / 1000
                    scheduleTimer(player, model.id, obj.id, remainingSec)
                }
            }
        }
        if (changed) {
            data.progress[model.id] = progress
            markDirty(player)
        }
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

    fun cooldownRemainingSeconds(player: OfflinePlayer, questId: String): Long {
        val model = questInfo(questId) ?: return 0
        val cd = model.cooldown ?: return 0
        val data = data(player)
        val info = data.cooldowns[questId] ?: return 0
        val last = info.lastAt ?: return 0
        if (!info.lastEndType.isNullOrBlank() && cd.endTypes.isNotEmpty() && cd.endTypes.none { it.equals(info.lastEndType, true) }) return 0
        val elapsed = System.currentTimeMillis() - last
        val remaining = cd.durationSeconds * 1000 - elapsed
        return if (remaining > 0) (remaining + 999) / 1000 else 0
    }

    private fun isOnCooldown(player: OfflinePlayer, model: QuestModel): Boolean =
        cooldownRemainingSeconds(player, model.id) > 0

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }

    fun isCommandAllowed(player: Player, commandRaw: String): Boolean {
        val cmd = commandRaw.removePrefix("/").trim().lowercase().substringBefore(" ")
        val quests = activeQuests(player)
        quests.forEach { qid ->
            val model = questInfo(qid) ?: return@forEach
            val cr = model.commandRestriction ?: return@forEach
            if (cr.whitelist.isNotEmpty() && cr.whitelist.none { it.equals(cmd, true) }) return false
            if (cr.blacklist.any { it.equals(cmd, true) }) return false
        }
        return true
    }

    private fun worldAllowed(model: QuestModel, worldName: String): Boolean {
        val wr = model.worldRestriction ?: return true
        val name = worldName.lowercase()
        if (wr.blacklist.any { it.equals(name, true) }) return false
        if (wr.whitelist.isNotEmpty() && wr.whitelist.none { it.equals(name, true) }) return false
        return true
    }

    private fun applyRewards(player: OfflinePlayer, model: QuestModel) {
        val rewards = model.rewards
        if (player.name == null) return
        rewards.commands.forEach { cmd ->
            plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(cmd, player))
        }
    }

    internal fun finishOutcome(player: OfflinePlayer, questId: String, outcome: String) {
        DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:648", "finishOutcome entry", mapOf("questId" to questId, "outcome" to outcome, "playerUuid" to player.uniqueId.toString()))
        val model = questRepo.findById(questId)
        if (model != null) {
            val end = model.endObjects[outcome] ?: emptyList()
            DebugLog.logToFile("debug-session", "run1", "QUEST", "QuestService.kt:651", "finishOutcome processing", mapOf("questId" to questId, "outcome" to outcome, "endObjectsCount" to end.size))
            end.forEach { eo ->
                when (eo.type) {
                    QuestEndObjectType.SERVER_ACTIONS -> {
                        val livePlayer = player.player
                        eo.actions.forEach { act ->
                            val parts = act.trim().split("\\s+".toRegex(), limit = 2)
                            val key = parts.getOrNull(0)?.uppercase() ?: return@forEach
                            val payload = parts.getOrNull(1) ?: ""
                            when (key) {
                                "SEND_MESSAGE" -> livePlayer?.sendMessage(render(payload, model, player))
                                "PERFORM_COMMAND" -> plugin.server.dispatchCommand(plugin.server.consoleSender, formatCommand(payload, player))
                                "SEND_SOUND" -> runCatching {
                                    livePlayer?.playSound(livePlayer.location, org.bukkit.Sound.valueOf(payload.uppercase()), 1f, 1f)
                                }
                                "SEND_TITLE" -> {
                                    val pts = payload.split("\\s+".toRegex(), limit = 5)
                                    val fi = pts.getOrNull(0)?.toIntOrNull() ?: 10
                                    val st = pts.getOrNull(1)?.toIntOrNull() ?: 60
                                    val fo = pts.getOrNull(2)?.toIntOrNull() ?: 10
                                    val title = render(pts.getOrNull(3) ?: "", model, player)
                                    val subtitle = render(pts.getOrNull(4) ?: "", model, player)
                                    livePlayer?.sendTitle(title, subtitle, fi, st, fo)
                                }
                            }
                        }
                        eo.title?.let { t ->
                            livePlayer?.sendTitle(
                                render(t.title ?: "", model, player),
                                render(t.subtitle ?: "", model, player),
                                t.fadeIn, t.stay, t.fadeOut
                            )
                        }
                        eo.sound?.let { s ->
                            runCatching { org.bukkit.Sound.valueOf(s.uppercase()) }.onSuccess { snd ->
                                livePlayer?.playSound(livePlayer.location, snd, 1f, 1f)
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
            // completion notify
            val notify = model.completion.notify[outcome.uppercase()]
            notify?.let { sendNotify(player, model, it) }
            // cooldown stamp
            val data = data(player)
            data.cooldowns[questId] = net.nemoria.quest.data.user.QuestCooldown(lastEndType = outcome.uppercase(), lastAt = System.currentTimeMillis())
            markDirty(player)
        }
        handlePoolQuestEnd(player, questId, outcome)
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

    private fun sendNotify(player: OfflinePlayer, model: QuestModel, notify: NotifySettings, extraPlaceholders: Map<String, String> = emptyMap()) {
        val p = player.player ?: return
        notify.message.forEach { msg ->
            val rendered = renderPlaceholders(msg, model.id, player)
            val withExtra = extraPlaceholders.entries.fold(rendered) { acc, e -> acc.replace("{${e.key}}", e.value) }
            MessageFormatter.send(p, withExtra)
        }
        notify.sound?.let { s ->
            runCatching { org.bukkit.Sound.valueOf(s.uppercase()) }.onSuccess { snd ->
                p.playSound(p.location, snd, 1f, 1f)
            }
        }
    }

    fun branchRuntimeHandleNpc(player: Player, npcId: Int, npcName: String?, clickType: String): Boolean {
        return branchRuntime.handleNpcInteract(player, npcId, npcName, clickType)
    }

    fun shouldBlockCitizensNpcActivator(player: Player, npcId: Int, npcName: String?): Boolean {
        return branchRuntime.shouldBlockCitizensNpcActivator(player, npcId, npcName)
    }

    fun branchRuntimeHandleNpcKill(player: Player, npcId: Int, npcName: String?): Boolean {
        return branchRuntime.handleNpcKill(player, npcId, npcName)
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
        DebugLog.logToFile("debug-session", "run1", "EVENT", "QuestService.kt:640", "handlePlayerBlockEvent", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "blockType" to block.type.name, "world" to block.world.name, "x" to block.x, "y" to block.y, "z" to block.z, "action" to (action ?: "null"), "placedByPlayer" to placedByPlayer))
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
        DebugLog.logToFile("debug-session", "run1", "EVENT", "QuestService.kt:662", "handlePlayerEntityEvent", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "entityType" to (entity?.type?.name ?: "null"), "damagerType" to (damager?.type?.name ?: "null"), "entityTypeHint" to (entityTypeHint ?: "null")))
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
        DebugLog.logToFile("debug-session", "run1", "EVENT", "QuestService.kt:672", "handlePlayerItemEvent", mapOf("playerUuid" to player.uniqueId.toString(), "kind" to kind.name, "itemType" to (item?.type?.name ?: "null"), "itemAmount" to (item?.amount ?: 0), "inventoryType" to (inventoryType ?: "null"), "slot" to (slot?.toString() ?: "null")))
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
        if (kind == net.nemoria.quest.runtime.BranchRuntimeManager.MiscEventType.DISCONNECT) {
            clearActivatorParticleRuns(player.uniqueId)
            clearActivatorDialogState(player.uniqueId)
            net.nemoria.quest.runtime.ChatHistoryManager.clear(player.uniqueId)
            net.nemoria.quest.runtime.ChatMessageDeduplicator.clear(player.uniqueId)
            actionbarState.remove(player.uniqueId)
            titleState.remove(player.uniqueId)
        }
        if (kind == net.nemoria.quest.runtime.BranchRuntimeManager.MiscEventType.CONNECT) {
            processPoolsForPlayer(player)
        }
        return branchRuntime.handleMiscEvent(player, kind, detail)
    }

    fun resumeBranch(player: Player, model: QuestModel) {
        branchRuntime.start(player, model)
    }

    internal fun runtime(): net.nemoria.quest.runtime.BranchRuntimeManager = branchRuntime
    fun preloadParticleScripts() = branchRuntime.preloadParticleScripts()

    internal fun mutateProgress(player: OfflinePlayer, questId: String, mutate: (QuestProgress) -> Unit) {
        val data = data(player)
        val progress = data.progress[questId] ?: return
        mutate(progress)
        data.progress[questId] = progress
        markDirty(player)
    }

    internal fun updateBranchState(player: OfflinePlayer, questId: String, branchId: String, nodeId: String) {
        val data = data(player)
        val progress = data.progress[questId] ?: return
        progress.currentBranchId = branchId
        progress.currentNodeId = nodeId
        data.progress[questId] = progress
        markDirty(player)
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
                ?: questVarsCache.computeIfAbsent(qid) { questInfo(it)?.variables ?: emptyMap() }
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
            else -> node.count.toDouble()
        }
    }

    fun currentObjectiveDetail(player: org.bukkit.entity.Player): String? {
        val data = data(player)
        val questId = data.activeQuests.firstOrNull() ?: return null
        return currentObjectiveDetail(player, questId)
    }

    fun currentObjectiveDetail(player: org.bukkit.entity.Player, questId: String): String? {
        val data = data(player)
        val model = questRepo.findById(questId) ?: return null
        val prog = data.progress[questId]
        if (model.branches.isEmpty()) return null
        val branchId = prog?.currentBranchId ?: model.mainBranch ?: model.branches.keys.firstOrNull() ?: return null
        val nodeId = prog?.currentNodeId ?: model.branches[branchId]?.startsAt ?: model.branches[branchId]?.objects?.keys?.firstOrNull()
        val node = model.branches[branchId]?.objects?.get(nodeId) ?: return null
        val desc = node.description ?: return null
        val base = renderPlaceholders(desc, questId, player)
        val goalById = when {
            node.blockGoals.isNotEmpty() -> node.blockGoals.associate { (it.id.ifBlank { "default" }) to it.goal }
            node.itemGoals.isNotEmpty() -> node.itemGoals.associate { (it.id.ifBlank { "default" }) to it.goal }
            node.goals.isNotEmpty() -> node.goals.associate { (it.id.ifBlank { "default" }) to it.goal }
            else -> emptyMap()
        }
        val progressById = if (goalById.isNotEmpty()) loadNodeGoalProgress(player, questId, branchId, node.id) else emptyMap()
        val progressVal = run {
            val goals = prioritizedGoals(node)
            if (goals.isNotEmpty()) {
                if (progressById.isNotEmpty()) progressById.values.sum() else loadNodeProgress(player, questId, branchId, node.id)
            } else {
                loadNodeProgress(player, questId, branchId, node.id)
            }
        }
        val goalVal = goalValue(node)
        return base
            .replace("\\{objective_progression:([A-Za-z0-9_]+)}".toRegex()) { m ->
                fmtNumber(progressById[m.groupValues[1]] ?: 0.0)
            }
            .replace("\\{objective_goal:([A-Za-z0-9_]+)}".toRegex()) { m ->
                fmtNumber(goalById[m.groupValues[1]] ?: 0.0)
            }
            .replace("{progress}", fmtNumber(progressVal))
            .replace("{goal}", fmtNumber(goalVal))
    }

    internal fun updateVariable(player: OfflinePlayer, questId: String, variable: String, value: String) {
        val data = data(player)
        val progress = data.progress[questId] ?: return
        progress.variables[variable] = value
        data.progress[questId] = progress
        markDirty(player)
    }

    internal fun userVariable(player: OfflinePlayer, name: String): String {
        val key = name.lowercase()
        val data = data(player)
        val current = data.userVariables[key]
        if (current != null) return current
        val def = Services.variables.defaultUser(key)
        if (def != null) {
            data.userVariables[key] = def
            markDirty(player)
            return def
        }
        return "0"
    }

    internal fun updateUserVariable(player: OfflinePlayer, name: String, value: String) {
        val key = name.lowercase()
        val data = data(player)
        data.userVariables[key] = value
        markDirty(player)
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

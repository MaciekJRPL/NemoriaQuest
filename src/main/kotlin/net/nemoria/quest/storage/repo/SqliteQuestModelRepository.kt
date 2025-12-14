package net.nemoria.quest.storage.repo

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.data.repo.QuestModelRepository
import net.nemoria.quest.quest.*
import java.util.concurrent.ConcurrentHashMap

class SqliteQuestModelRepository(private val dataSource: HikariDataSource) : QuestModelRepository {
    private val gson = Gson()
    private val objectivesType = object : TypeToken<List<QuestObjective>>() {}.type
    private val descriptionLinesType = object : TypeToken<List<String>>() {}.type
    private val statusItemsType = object : TypeToken<Map<QuestStatusItemState, StatusItemTemplate>>() {}.type
    private val statusItemType = object : TypeToken<StatusItemTemplate>() {}.type
    private val requirementsType = object : TypeToken<List<String>>() {}.type
    private val rewardsType = object : TypeToken<QuestRewards>() {}.type
    private val branchesType = object : TypeToken<Map<String, Branch>>() {}.type
    private val variablesType = object : TypeToken<Map<String, String>>() {}.type
    private val endObjectsType = object : TypeToken<Map<String, List<QuestEndObject>>>() {}.type
    private val cache: MutableMap<String, QuestModel> = ConcurrentHashMap()

    init {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:23", "init entry", mapOf())
        loadAllToCache()
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:25", "init cache loaded", mapOf("cacheSize" to cache.size))
    }

    override fun findById(id: String): QuestModel? {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:27", "findById entry", mapOf("id" to id, "inCache" to cache.containsKey(id)))
        cache[id]?.let {
            DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:28", "findById found in cache", mapOf("id" to id))
            return it
        }
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT name, description, display_name, description_lines, progress_notify, status_items, requirements, objectives, rewards, time_limit, variables, branches, main_branch, end_objects, saving, concurrency, players, start_conditions, completion, activators, description_placeholder, information_message, display_priority, default_status_item, permission_start_restriction, permission_start_command_restriction, world_restriction, command_restriction, cooldown FROM quest_model WHERE id = ?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val model = QuestModel(
                            id = id,
                            name = rs.getString("name"),
                            description = rs.getString("description"),
                            displayName = rs.getString("display_name"),
                            descriptionLines = parseDescriptionLines(rs.getString("description_lines")),
                            progressNotify = parseProgressNotify(rs.getString("progress_notify")),
                            statusItems = parseStatusItems(rs.getString("status_items")),
                            requirements = parseRequirements(rs.getString("requirements")),
                            objectives = parseObjectives(rs.getString("objectives")),
                            rewards = parseRewards(rs.getString("rewards")),
                            saving = parseSaving(rs.getString("saving")),
                            concurrency = parseConcurrency(rs.getString("concurrency")),
                            players = parsePlayers(rs.getString("players")),
                            startConditions = parseStartConditions(rs.getString("start_conditions")),
                            completion = parseCompletion(rs.getString("completion")),
                            activators = parseActivators(rs.getString("activators")),
                            timeLimit = parseTimeLimit(rs.getString("time_limit")),
                            variables = parseVariables(rs.getString("variables")),
                            branches = parseBranches(rs.getString("branches")),
                            mainBranch = rs.getString("main_branch"),
                            endObjects = parseEndObjects(rs.getString("end_objects")),
                            descriptionPlaceholder = rs.getString("description_placeholder"),
                            informationMessage = rs.getString("information_message"),
                            displayPriority = rs.getInt("display_priority").let { if (rs.wasNull()) null else it },
                            defaultStatusItem = parseStatusItem(rs.getString("default_status_item")),
                            permissionStartRestriction = rs.getString("permission_start_restriction"),
                            permissionStartCommandRestriction = rs.getString("permission_start_command_restriction"),
                            worldRestriction = parseWorldRestriction(rs.getString("world_restriction")),
                            commandRestriction = parseCommandRestriction(rs.getString("command_restriction")),
                            cooldown = parseCooldown(rs.getString("cooldown"))
                        )
                        cache[id] = model
                        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:84", "findById found in database", mapOf("id" to id, "name" to model.name))
                        return model
                    }
                }
            }
        }
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:89", "findById not found", mapOf("id" to id))
        return null
    }

    override fun findAll(): Collection<QuestModel> {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:92", "findAll entry", mapOf("cacheSize" to cache.size))
        if (cache.isNotEmpty()) {
            DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:93", "findAll returning cache", mapOf("cacheSize" to cache.size))
            return cache.values
        }
        val list = mutableListOf<QuestModel>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, name, description, display_name, description_lines, progress_notify, status_items, requirements, objectives, rewards, time_limit, variables, branches, main_branch, end_objects, saving, concurrency, players, start_conditions, completion, activators, description_placeholder, information_message, display_priority, default_status_item, permission_start_restriction, permission_start_command_restriction, world_restriction, command_restriction, cooldown FROM quest_model"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val model = QuestModel(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            description = rs.getString("description"),
                            displayName = rs.getString("display_name"),
                            descriptionLines = parseDescriptionLines(rs.getString("description_lines")),
                            progressNotify = parseProgressNotify(rs.getString("progress_notify")),
                            statusItems = parseStatusItems(rs.getString("status_items")),
                            requirements = parseRequirements(rs.getString("requirements")),
                            objectives = parseObjectives(rs.getString("objectives")),
                            rewards = parseRewards(rs.getString("rewards")),
                            saving = parseSaving(rs.getString("saving")),
                            concurrency = parseConcurrency(rs.getString("concurrency")),
                            players = parsePlayers(rs.getString("players")),
                            startConditions = parseStartConditions(rs.getString("start_conditions")),
                            completion = parseCompletion(rs.getString("completion")),
                            activators = parseActivators(rs.getString("activators")),
                            timeLimit = parseTimeLimit(rs.getString("time_limit")),
                            variables = parseVariables(rs.getString("variables")),
                            branches = parseBranches(rs.getString("branches")),
                            mainBranch = rs.getString("main_branch"),
                            endObjects = parseEndObjects(rs.getString("end_objects")),
                            descriptionPlaceholder = rs.getString("description_placeholder"),
                            informationMessage = rs.getString("information_message"),
                            displayPriority = rs.getInt("display_priority").let { if (rs.wasNull()) null else it },
                            defaultStatusItem = parseStatusItem(rs.getString("default_status_item")),
                            permissionStartRestriction = rs.getString("permission_start_restriction"),
                            permissionStartCommandRestriction = rs.getString("permission_start_command_restriction"),
                            worldRestriction = parseWorldRestriction(rs.getString("world_restriction")),
                            commandRestriction = parseCommandRestriction(rs.getString("command_restriction")),
                            cooldown = parseCooldown(rs.getString("cooldown"))
                        )
                        cache[model.id] = model
                        list.add(model)
                    }
                }
            }
        }
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:139", "findAll completed", mapOf("count" to list.size, "cacheSize" to cache.size))
        return list
    }

    override fun save(model: QuestModel) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:142", "save entry", mapOf("questId" to model.id, "name" to model.name))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO quest_model(id, name, description, display_name, description_lines, progress_notify, status_items, requirements, objectives, rewards, time_limit, variables, branches, main_branch, end_objects, saving, concurrency, players, start_conditions, completion, activators, description_placeholder, information_message, display_priority, default_status_item, permission_start_restriction, permission_start_command_restriction, world_restriction, command_restriction, cooldown)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name = excluded.name, description = excluded.description, display_name = excluded.display_name, description_lines = excluded.description_lines, progress_notify = excluded.progress_notify, status_items = excluded.status_items, requirements = excluded.requirements, objectives = excluded.objectives, rewards = excluded.rewards, time_limit = excluded.time_limit, variables = excluded.variables, branches = excluded.branches, main_branch = excluded.main_branch, end_objects = excluded.end_objects, saving = excluded.saving, concurrency = excluded.concurrency, players = excluded.players, start_conditions = excluded.start_conditions, completion = excluded.completion, activators = excluded.activators, description_placeholder = excluded.description_placeholder, information_message = excluded.information_message, display_priority = excluded.display_priority, default_status_item = excluded.default_status_item, permission_start_restriction = excluded.permission_start_restriction, permission_start_command_restriction = excluded.permission_start_command_restriction, world_restriction = excluded.world_restriction, command_restriction = excluded.command_restriction, cooldown = excluded.cooldown
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, model.id)
                ps.setString(2, model.name)
                ps.setString(3, model.description)
                ps.setString(4, model.displayName)
                ps.setString(5, gson.toJson(model.descriptionLines))
                ps.setString(6, gson.toJson(model.progressNotify))
                ps.setString(7, gson.toJson(model.statusItems))
                ps.setString(8, gson.toJson(model.requirements))
                ps.setString(9, gson.toJson(model.objectives))
                ps.setString(10, gson.toJson(model.rewards))
                ps.setString(11, gson.toJson(model.timeLimit))
                ps.setString(12, gson.toJson(model.variables))
                ps.setString(13, gson.toJson(model.branches))
                ps.setString(14, model.mainBranch)
                ps.setString(15, gson.toJson(model.endObjects))
                ps.setString(16, gson.toJson(model.saving))
                ps.setString(17, gson.toJson(model.concurrency))
                ps.setString(18, gson.toJson(model.players))
                ps.setString(19, gson.toJson(model.startConditions))
                ps.setString(20, gson.toJson(model.completion))
                ps.setString(21, gson.toJson(model.activators))
                ps.setString(22, model.descriptionPlaceholder)
                ps.setString(23, model.informationMessage)
                if (model.displayPriority != null) ps.setInt(24, model.displayPriority) else ps.setNull(24, java.sql.Types.INTEGER)
                ps.setString(25, gson.toJson(model.defaultStatusItem))
                ps.setString(26, model.permissionStartRestriction)
                ps.setString(27, model.permissionStartCommandRestriction)
                ps.setString(28, gson.toJson(model.worldRestriction))
                ps.setString(29, gson.toJson(model.commandRestriction))
                ps.setString(30, gson.toJson(model.cooldown))
                val rows = ps.executeUpdate()
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:181", "save completed", mapOf("questId" to model.id, "rowsAffected" to rows))
            }
        }
        cache[model.id] = model
    }

    private fun loadAllToCache() {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:187", "loadAllToCache entry", mapOf("cacheSize" to cache.size))
        cache.clear()
        findAll()
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:190", "loadAllToCache completed", mapOf("cacheSize" to cache.size))
    }

    private fun parseObjectives(raw: String?): List<QuestObjective> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<QuestObjective>>(raw, objectivesType) }.getOrDefault(emptyList())
    }

    private fun parseDescriptionLines(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, descriptionLinesType) }.getOrDefault(emptyList())
    }

    private fun parseStatusItems(raw: String?): Map<QuestStatusItemState, StatusItemTemplate> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { gson.fromJson<Map<QuestStatusItemState, StatusItemTemplate>>(raw, statusItemsType) }.getOrDefault(emptyMap())
    }

    private fun parseStatusItem(raw: String?): StatusItemTemplate? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson<StatusItemTemplate>(raw, statusItemType) }.getOrNull()
    }

    private fun parseWorldRestriction(raw: String?): WorldRestriction? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, WorldRestriction::class.java) }.getOrNull()
    }

    private fun parseCommandRestriction(raw: String?): CommandRestriction? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, CommandRestriction::class.java) }.getOrNull()
    }

    private fun parseCooldown(raw: String?): CooldownSettings? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, CooldownSettings::class.java) }.getOrNull()
    }

    private fun parseProgressNotify(raw: String?): ProgressNotify? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, ProgressNotify::class.java) }.getOrNull()
    }

    private fun parseRequirements(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, requirementsType) }.getOrDefault(emptyList())
    }

    private fun parseRewards(raw: String?): QuestRewards =
        runCatching { gson.fromJson<QuestRewards>(raw, rewardsType) }.getOrDefault(QuestRewards())

    private fun parseTimeLimit(raw: String?): TimeLimit? =
        runCatching { gson.fromJson(raw, TimeLimit::class.java) }.getOrNull()

    private fun parseVariables(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching { gson.fromJson<Map<String, String>>(raw, variablesType)!!.toMutableMap() }.getOrDefault(mutableMapOf())
    }

    private fun parseBranches(raw: String?): Map<String, Branch> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { gson.fromJson<Map<String, Branch>>(raw, branchesType) }.getOrDefault(emptyMap())
    }

    private fun parseEndObjects(raw: String?): Map<String, List<QuestEndObject>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { gson.fromJson<Map<String, List<QuestEndObject>>>(raw, endObjectsType) }.getOrDefault(emptyMap())
    }

    private fun parseSaving(raw: String?): SavingMode =
        runCatching { gson.fromJson(raw, SavingMode::class.java) }.getOrDefault(SavingMode.ENABLED)

    private fun parseConcurrency(raw: String?): Concurrency =
        runCatching { gson.fromJson(raw, Concurrency::class.java) }.getOrDefault(Concurrency())

    private fun parsePlayers(raw: String?): PlayerSettings =
        runCatching { gson.fromJson(raw, PlayerSettings::class.java) }.getOrDefault(PlayerSettings())

    private fun parseStartConditions(raw: String?): StartConditions? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, StartConditions::class.java) }.getOrNull()
    }

    private fun parseCompletion(raw: String?): CompletionSettings =
        runCatching { gson.fromJson(raw, CompletionSettings::class.java) }.getOrDefault(CompletionSettings())

    private fun parseActivators(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, requirementsType) }.getOrDefault(emptyList())
    }
}

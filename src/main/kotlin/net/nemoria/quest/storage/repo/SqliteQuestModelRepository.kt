package net.nemoria.quest.storage.repo

import com.google.gson.Gson
import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.data.repo.QuestModelRepository
import net.nemoria.quest.quest.*
import java.util.concurrent.ConcurrentHashMap

class SqliteQuestModelRepository(private val dataSource: HikariDataSource) : QuestModelRepository {
    private val gson = Gson()
    private val parser = QuestModelJsonParser(gson)
    private val cache: MutableMap<String, QuestModel> = ConcurrentHashMap()

    init {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:23", "init entry", mapOf())
        loadAllToCache()
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:25", "init cache loaded", mapOf("cacheSize" to cache.size))
    }

    override fun findById(id: String): QuestModel? {
        return QuestModelRepositoryQueries.fetchById(
            dataSource,
            cache,
            parser,
            id,
            onDbHit = { model ->
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:84", "findById found in database", mapOf("id" to id, "name" to model.name))
            },
            onMiss = {
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:89", "findById not found", mapOf("id" to id))
            }
        )
    }

    override fun findAll(): Collection<QuestModel> {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:92", "findAll entry", mapOf("cacheSize" to cache.size))
        return QuestModelRepositoryQueries.fetchAll(
            dataSource,
            cache,
            parser,
            onCacheHit = { cacheSize ->
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:93", "findAll returning cache", mapOf("cacheSize" to cacheSize))
            },
            onDbLoaded = { count, cacheSize ->
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:139", "findAll completed", mapOf("count" to count, "cacheSize" to cacheSize))
            }
        )
    }

    override fun save(model: QuestModel) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteQuestModelRepository.kt:142", "save entry", mapOf("questId" to model.id, "name" to model.name))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO quest_model(id, name, description, display_name, description_lines, progress_notify, status_items, requirements, objectives, rewards, time_limit, variables, branches, main_branch, end_objects, saving, concurrency, players, start_conditions, completion, activators, activators_dialog, activators_dialog_auto_start_distance, activators_dialog_reset_delay, activators_dialog_reset_distance, activators_dialog_reset_notify, description_placeholder, information_message, display_priority, default_status_item, permission_start_restriction, permission_start_command_restriction, world_restriction, command_restriction, cooldown)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name = excluded.name, description = excluded.description, display_name = excluded.display_name, description_lines = excluded.description_lines, progress_notify = excluded.progress_notify, status_items = excluded.status_items, requirements = excluded.requirements, objectives = excluded.objectives, rewards = excluded.rewards, time_limit = excluded.time_limit, variables = excluded.variables, branches = excluded.branches, main_branch = excluded.main_branch, end_objects = excluded.end_objects, saving = excluded.saving, concurrency = excluded.concurrency, players = excluded.players, start_conditions = excluded.start_conditions, completion = excluded.completion, activators = excluded.activators, activators_dialog = excluded.activators_dialog, activators_dialog_auto_start_distance = excluded.activators_dialog_auto_start_distance, activators_dialog_reset_delay = excluded.activators_dialog_reset_delay, activators_dialog_reset_distance = excluded.activators_dialog_reset_distance, activators_dialog_reset_notify = excluded.activators_dialog_reset_notify, description_placeholder = excluded.description_placeholder, information_message = excluded.information_message, display_priority = excluded.display_priority, default_status_item = excluded.default_status_item, permission_start_restriction = excluded.permission_start_restriction, permission_start_command_restriction = excluded.permission_start_command_restriction, world_restriction = excluded.world_restriction, command_restriction = excluded.command_restriction, cooldown = excluded.cooldown
                """.trimIndent()
            ).use { ps ->
                QuestModelRepositoryQueries.bindModel(ps, gson, model, java.sql.Types.REAL)
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

}

package net.nemoria.quest.storage.repo

import com.google.gson.Gson
import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.QuestModelRepository
import net.nemoria.quest.quest.*
import java.util.concurrent.ConcurrentHashMap

class MysqlQuestModelRepository(private val dataSource: HikariDataSource) : QuestModelRepository {
    private val gson = Gson()
    private val parser = QuestModelJsonParser(gson)
    private val cache: MutableMap<String, QuestModel> = ConcurrentHashMap()

    init {
        loadAllToCache()
    }

    override fun findById(id: String): QuestModel? {
        return QuestModelRepositoryQueries.fetchById(
            dataSource,
            cache,
            parser,
            id
        )
    }

    override fun findAll(): Collection<QuestModel> {
        return QuestModelRepositoryQueries.fetchAll(
            dataSource,
            cache,
            parser
        )
    }

    override fun save(model: QuestModel) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO quest_model(id, name, description, display_name, description_lines, progress_notify, status_items, requirements, objectives, rewards, time_limit, variables, branches, main_branch, end_objects, saving, concurrency, players, start_conditions, completion, activators, activators_dialog, activators_dialog_auto_start_distance, activators_dialog_reset_delay, activators_dialog_reset_distance, activators_dialog_reset_notify, description_placeholder, information_message, display_priority, default_status_item, permission_start_restriction, permission_start_command_restriction, world_restriction, command_restriction, cooldown)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE name = new.name, description = new.description, display_name = new.display_name, description_lines = new.description_lines, progress_notify = new.progress_notify, status_items = new.status_items, requirements = new.requirements, objectives = new.objectives, rewards = new.rewards, time_limit = new.time_limit, variables = new.variables, branches = new.branches, main_branch = new.main_branch, end_objects = new.end_objects, saving = new.saving, concurrency = new.concurrency, players = new.players, start_conditions = new.start_conditions, completion = new.completion, activators = new.activators, activators_dialog = new.activators_dialog, activators_dialog_auto_start_distance = new.activators_dialog_auto_start_distance, activators_dialog_reset_delay = new.activators_dialog_reset_delay, activators_dialog_reset_distance = new.activators_dialog_reset_distance, activators_dialog_reset_notify = new.activators_dialog_reset_notify, description_placeholder = new.description_placeholder, information_message = new.information_message, display_priority = new.display_priority, default_status_item = new.default_status_item, permission_start_restriction = new.permission_start_restriction, permission_start_command_restriction = new.permission_start_command_restriction, world_restriction = new.world_restriction, command_restriction = new.command_restriction, cooldown = new.cooldown
                """.trimIndent()
            ).use { ps ->
                QuestModelRepositoryQueries.bindModel(ps, gson, model, java.sql.Types.DOUBLE)
                ps.executeUpdate()
            }
        }
        cache[model.id] = model
    }

    private fun loadAllToCache() {
        cache.clear()
        findAll()
    }
}

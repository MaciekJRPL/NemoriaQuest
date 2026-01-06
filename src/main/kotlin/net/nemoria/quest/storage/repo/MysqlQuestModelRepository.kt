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
                bindModel(ps, model)
                ps.executeUpdate()
            }
        }
        cache[model.id] = model
    }

    private fun loadAllToCache() {
        cache.clear()
        findAll()
    }


    private fun bindModel(ps: java.sql.PreparedStatement, model: QuestModel) {
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
        ps.setString(22, gson.toJson(model.activatorsDialog))
        if (model.activatorsDialogAutoStartDistance != null) ps.setDouble(23, model.activatorsDialogAutoStartDistance) else ps.setNull(23, java.sql.Types.DOUBLE)
        if (model.activatorsDialogResetDelaySeconds != null) ps.setLong(24, model.activatorsDialogResetDelaySeconds) else ps.setNull(24, java.sql.Types.BIGINT)
        if (model.activatorsDialogResetDistance != null) ps.setDouble(25, model.activatorsDialogResetDistance) else ps.setNull(25, java.sql.Types.DOUBLE)
        ps.setString(26, gson.toJson(model.activatorsDialogResetNotify))
        ps.setString(27, model.descriptionPlaceholder)
        ps.setString(28, model.informationMessage)
        if (model.displayPriority != null) ps.setInt(29, model.displayPriority) else ps.setNull(29, java.sql.Types.INTEGER)
        ps.setString(30, gson.toJson(model.defaultStatusItem))
        ps.setString(31, model.permissionStartRestriction)
        ps.setString(32, model.permissionStartCommandRestriction)
        ps.setString(33, gson.toJson(model.worldRestriction))
        ps.setString(34, gson.toJson(model.commandRestriction))
        ps.setString(35, gson.toJson(model.cooldown))
    }
}

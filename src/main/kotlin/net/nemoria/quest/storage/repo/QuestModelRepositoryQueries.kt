package net.nemoria.quest.storage.repo

import com.google.gson.Gson
import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.quest.QuestModel
import java.sql.PreparedStatement

internal object QuestModelRepositoryQueries {
    private const val SELECT_BY_ID =
        "SELECT name, description, display_name, description_lines, progress_notify, status_items, requirements, objectives, rewards, time_limit, variables, branches, main_branch, end_objects, saving, concurrency, players, start_conditions, completion, activators, activators_dialog, activators_dialog_auto_start_distance, activators_dialog_reset_delay, activators_dialog_reset_distance, activators_dialog_reset_notify, description_placeholder, information_message, display_priority, default_status_item, permission_start_restriction, permission_start_command_restriction, world_restriction, command_restriction, cooldown FROM quest_model WHERE id = ?"
    private const val SELECT_ALL =
        "SELECT id, name, description, display_name, description_lines, progress_notify, status_items, requirements, objectives, rewards, time_limit, variables, branches, main_branch, end_objects, saving, concurrency, players, start_conditions, completion, activators, activators_dialog, activators_dialog_auto_start_distance, activators_dialog_reset_delay, activators_dialog_reset_distance, activators_dialog_reset_notify, description_placeholder, information_message, display_priority, default_status_item, permission_start_restriction, permission_start_command_restriction, world_restriction, command_restriction, cooldown FROM quest_model"

    fun fetchById(
        dataSource: HikariDataSource,
        cache: MutableMap<String, QuestModel>,
        parser: QuestModelJsonParser,
        id: String,
        onDbHit: ((QuestModel) -> Unit)? = null,
        onMiss: (() -> Unit)? = null
    ): QuestModel? {
        cache[id]?.let { return it }
        dataSource.connection.use { conn ->
            conn.prepareStatement(SELECT_BY_ID).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val model = QuestModelRowMapper.fromResultSet(rs, parser, id)
                        cache[id] = model
                        onDbHit?.invoke(model)
                        return model
                    }
                }
            }
        }
        onMiss?.invoke()
        return null
    }

    fun fetchAll(
        dataSource: HikariDataSource,
        cache: MutableMap<String, QuestModel>,
        parser: QuestModelJsonParser,
        onCacheHit: ((Int) -> Unit)? = null,
        onDbLoaded: ((Int, Int) -> Unit)? = null
    ): Collection<QuestModel> {
        if (cache.isNotEmpty()) {
            onCacheHit?.invoke(cache.size)
            return cache.values
        }
        val list = mutableListOf<QuestModel>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(SELECT_ALL).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val model = QuestModelRowMapper.fromResultSet(rs, parser)
                        cache[model.id] = model
                        list.add(model)
                    }
                }
            }
        }
        onDbLoaded?.invoke(list.size, cache.size)
        return list
    }

    fun bindModel(ps: PreparedStatement, gson: Gson, model: QuestModel, realType: Int) {
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
        if (model.activatorsDialogAutoStartDistance != null) ps.setDouble(23, model.activatorsDialogAutoStartDistance) else ps.setNull(23, realType)
        if (model.activatorsDialogResetDelaySeconds != null) ps.setLong(24, model.activatorsDialogResetDelaySeconds) else ps.setNull(24, java.sql.Types.BIGINT)
        if (model.activatorsDialogResetDistance != null) ps.setDouble(25, model.activatorsDialogResetDistance) else ps.setNull(25, realType)
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

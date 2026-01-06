package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.quest.QuestModel

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
}

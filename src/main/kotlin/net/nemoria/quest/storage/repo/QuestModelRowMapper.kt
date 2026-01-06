package net.nemoria.quest.storage.repo

import net.nemoria.quest.quest.QuestModel
import java.sql.ResultSet

object QuestModelRowMapper {
    fun fromResultSet(
        rs: ResultSet,
        parser: QuestModelJsonParser,
        idOverride: String? = null
    ): QuestModel {
        val activatorsDialogAutoStartDistance =
            rs.getDouble("activators_dialog_auto_start_distance").let { if (rs.wasNull()) null else it }
        val activatorsDialogResetDelaySeconds =
            rs.getLong("activators_dialog_reset_delay").let { if (rs.wasNull()) null else it }
        val activatorsDialogResetDistance =
            rs.getDouble("activators_dialog_reset_distance").let { if (rs.wasNull()) null else it }

        return QuestModel(
            id = idOverride ?: rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            displayName = rs.getString("display_name"),
            descriptionLines = parser.parseDescriptionLines(rs.getString("description_lines")),
            progressNotify = parser.parseProgressNotify(rs.getString("progress_notify")),
            statusItems = parser.parseStatusItems(rs.getString("status_items")),
            requirements = parser.parseRequirements(rs.getString("requirements")),
            objectives = parser.parseObjectives(rs.getString("objectives")),
            rewards = parser.parseRewards(rs.getString("rewards")),
            saving = parser.parseSaving(rs.getString("saving")),
            concurrency = parser.parseConcurrency(rs.getString("concurrency")),
            players = parser.parsePlayers(rs.getString("players")),
            startConditions = parser.parseStartConditions(rs.getString("start_conditions")),
            completion = parser.parseCompletion(rs.getString("completion")),
            activators = parser.parseActivators(rs.getString("activators")),
            activatorsDialog = parser.parseStringList(rs.getString("activators_dialog")),
            activatorsDialogAutoStartDistance = activatorsDialogAutoStartDistance,
            activatorsDialogResetDelaySeconds = activatorsDialogResetDelaySeconds,
            activatorsDialogResetDistance = activatorsDialogResetDistance,
            activatorsDialogResetNotify = parser.parseNotifySettings(rs.getString("activators_dialog_reset_notify")),
            timeLimit = parser.parseTimeLimit(rs.getString("time_limit")),
            variables = parser.parseVariables(rs.getString("variables")),
            branches = parser.parseBranches(rs.getString("branches")),
            mainBranch = rs.getString("main_branch"),
            endObjects = parser.parseEndObjects(rs.getString("end_objects")),
            descriptionPlaceholder = rs.getString("description_placeholder"),
            informationMessage = rs.getString("information_message"),
            displayPriority = rs.getInt("display_priority").let { if (rs.wasNull()) null else it },
            defaultStatusItem = parser.parseStatusItem(rs.getString("default_status_item")),
            permissionStartRestriction = rs.getString("permission_start_restriction"),
            permissionStartCommandRestriction = rs.getString("permission_start_command_restriction"),
            worldRestriction = parser.parseWorldRestriction(rs.getString("world_restriction")),
            commandRestriction = parser.parseCommandRestriction(rs.getString("command_restriction")),
            cooldown = parser.parseCooldown(rs.getString("cooldown"))
        )
    }
}

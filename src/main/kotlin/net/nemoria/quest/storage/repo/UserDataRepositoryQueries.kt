package net.nemoria.quest.storage.repo

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.nemoria.quest.data.user.ObjectiveState
import net.nemoria.quest.data.user.QuestProgress
import net.nemoria.quest.data.user.UserData
import java.sql.PreparedStatement

internal object UserDataRepositoryQueries {
    fun bindUser(ps: PreparedStatement, gson: Gson, data: UserData) {
        ps.setString(1, data.uuid.toString())
        ps.setString(2, data.activeQuests.joinToString(";"))
        ps.setString(3, data.completedQuests.joinToString(";"))
        ps.setString(4, gson.toJson(data.progress))
        ps.setString(5, gson.toJson(data.userVariables))
        ps.setString(6, gson.toJson(data.cooldowns))
        ps.setString(7, gson.toJson(data.questPools))
        ps.setInt(8, if (data.actionbarEnabled) 1 else 0)
        ps.setInt(9, if (data.titleEnabled) 1 else 0)
    }

    fun parseProgress(
        raw: String?,
        gson: Gson,
        onError: ((Throwable) -> Unit)? = null
    ): MutableMap<String, QuestProgress> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        val type = object : TypeToken<Map<String, QuestProgress>>() {}.type
        return runCatching { gson.fromJson<Map<String, QuestProgress>>(raw, type).toMutableMap() }.getOrElse { ex ->
            onError?.invoke(ex)
            val map = mutableMapOf<String, QuestProgress>()
            raw.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val qp = QuestProgress()
                    qp.objectives[parts[0]] = ObjectiveState(completed = parts[1].toIntOrNull() == 1)
                    map[parts[0]] = qp
                }
            }
            map
        }
    }
}

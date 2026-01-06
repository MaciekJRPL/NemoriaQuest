package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.UserDataRepository
import net.nemoria.quest.data.user.*
import java.util.UUID
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MysqlUserDataRepository(private val dataSource: HikariDataSource) : UserDataRepository {
    private val gson = Gson()

    override fun load(uuid: UUID): UserData {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT active, completed, progress, user_vars, cooldowns, pools, actionbar_enabled, title_enabled FROM user_data WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val progressRaw = rs.getString("progress")
                        val userVarsRaw = rs.getString("user_vars")
                        val actionbarEnabled = rs.getInt("actionbar_enabled") != 0
                        val titleEnabled = rs.getInt("title_enabled") != 0
                        return UserData(
                            uuid = uuid,
                            activeQuests = rs.getString("active").toSetMutable(),
                            completedQuests = rs.getString("completed").toSetMutable(),
                            progress = parseProgress(progressRaw),
                            userVariables = parseMap(userVarsRaw),
                            cooldowns = parseCooldowns(rs.getString("cooldowns")),
                            questPools = parsePools(rs.getString("pools")),
                            actionbarEnabled = actionbarEnabled,
                            titleEnabled = titleEnabled
                        )
                    }
                }
            }
            val empty = UserData(uuid)
            save(empty)
            return empty
        }
    }

    override fun save(data: UserData) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO user_data(uuid, active, completed, progress, user_vars, cooldowns, pools, actionbar_enabled, title_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE active = new.active, completed = new.completed, progress = new.progress, user_vars = new.user_vars, cooldowns = new.cooldowns, pools = new.pools, actionbar_enabled = new.actionbar_enabled, title_enabled = new.title_enabled
                """.trimIndent()
            ).use { ps ->
                UserDataRepositoryQueries.bindUser(ps, gson, data)
                ps.executeUpdate()
            }
        }
    }

    private fun String.toSetMutable(): MutableSet<String> =
        if (isBlank()) mutableSetOf() else split(";").filter { it.isNotBlank() }.toMutableSet()

    private fun parseProgress(raw: String?): MutableMap<String, QuestProgress> {
        return UserDataRepositoryQueries.parseProgress(raw, gson)
    }

    private fun parseMap(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return runCatching { gson.fromJson<Map<String, String>>(raw, type).toMutableMap() }.getOrElse { mutableMapOf() }
    }

    private fun parseCooldowns(raw: String?): MutableMap<String, QuestCooldown> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        val type = object : TypeToken<Map<String, QuestCooldown>>() {}.type
        return runCatching { gson.fromJson<Map<String, QuestCooldown>>(raw, type).toMutableMap() }.getOrElse { mutableMapOf() }
    }

    private fun parsePools(raw: String?): QuestPoolsState {
        if (raw.isNullOrBlank()) return QuestPoolsState()
        return runCatching { gson.fromJson(raw, QuestPoolsState::class.java) }.getOrElse { QuestPoolsState() }
    }
}

package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.data.repo.PlayerBlockRepository
import java.sql.Timestamp
import java.util.UUID

class SqlitePlayerBlockRepository(private val ds: HikariDataSource) : PlayerBlockRepository {
    override fun upsert(world: String, x: Int, y: Int, z: Int, owner: UUID?, timestamp: Long) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:9", "upsert entry", mapOf("world" to world, "x" to x, "y" to y, "z" to z, "owner" to (owner?.toString() ?: "null")))
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO player_blocks(world, x, y, z, owner, ts)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(world, x, y, z) DO UPDATE SET owner=excluded.owner, ts=excluded.ts
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, world)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.setString(5, owner?.toString())
                ps.setTimestamp(6, Timestamp(timestamp))
                val rows = ps.executeUpdate()
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:24", "upsert completed", mapOf("world" to world, "x" to x, "y" to y, "z" to z, "rowsAffected" to rows))
            }
        }
    }

    override fun remove(world: String, x: Int, y: Int, z: Int) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:29", "remove entry", mapOf("world" to world, "x" to x, "y" to y, "z" to z))
        ds.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM player_blocks WHERE world=? AND x=? AND y=? AND z=?"
            ).use { ps ->
                ps.setString(1, world)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                val rows = ps.executeUpdate()
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:38", "remove completed", mapOf("world" to world, "x" to x, "y" to y, "z" to z, "rowsAffected" to rows))
            }
        }
    }

    override fun find(world: String, x: Int, y: Int, z: Int): PlayerBlockRepository.BlockEntry? {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:43", "find entry", mapOf("world" to world, "x" to x, "y" to y, "z" to z))
        ds.connection.use { conn ->
            conn.prepareStatement(PlayerBlockRepositoryQueries.FIND_SQL).use { ps ->
                PlayerBlockRepositoryQueries.bindKey(ps, world, x, y, z)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val ownerStr = rs.getString("owner")
                    val ts = rs.getTimestamp("ts")?.time
                    val result = PlayerBlockRepositoryQueries.parseBlockEntry(ownerStr, ts) ?: return null
                    DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:57", "find found", mapOf("world" to world, "x" to x, "y" to y, "z" to z, "owner" to (result.owner?.toString() ?: "null")))
                    return result
                }
            }
        }
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:61", "find not found", mapOf("world" to world, "x" to x, "y" to y, "z" to z))
        return null
    }

    override fun pruneOlderThan(cutoffMillis: Long): Int {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:63", "pruneOlderThan entry", mapOf("cutoffMillis" to cutoffMillis))
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM player_blocks WHERE ts < ?").use { ps ->
                ps.setTimestamp(1, Timestamp(cutoffMillis))
                val rows = ps.executeUpdate()
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqlitePlayerBlockRepository.kt:68", "pruneOlderThan completed", mapOf("cutoffMillis" to cutoffMillis, "rowsDeleted" to rows))
                return rows
            }
        }
    }
}

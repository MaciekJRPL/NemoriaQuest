package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.PlayerBlockRepository
import java.sql.Timestamp
import java.util.UUID

class SqlitePlayerBlockRepository(private val ds: HikariDataSource) : PlayerBlockRepository {
    override fun upsert(world: String, x: Int, y: Int, z: Int, owner: UUID?, timestamp: Long) {
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
                ps.executeUpdate()
            }
        }
    }

    override fun remove(world: String, x: Int, y: Int, z: Int) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM player_blocks WHERE world=? AND x=? AND y=? AND z=?"
            ).use { ps ->
                ps.setString(1, world)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.executeUpdate()
            }
        }
    }

    override fun find(world: String, x: Int, y: Int, z: Int): PlayerBlockRepository.BlockEntry? {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT owner, ts FROM player_blocks WHERE world=? AND x=? AND y=? AND z=?"
            ).use { ps ->
                ps.setString(1, world)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val ownerStr = rs.getString("owner")
                    val owner = ownerStr?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val ts = rs.getTimestamp("ts")?.time ?: return null
                    return PlayerBlockRepository.BlockEntry(owner, ts)
                }
            }
        }
    }

    override fun pruneOlderThan(cutoffMillis: Long): Int {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM player_blocks WHERE ts < ?").use { ps ->
                ps.setTimestamp(1, Timestamp(cutoffMillis))
                return ps.executeUpdate()
            }
        }
    }
}

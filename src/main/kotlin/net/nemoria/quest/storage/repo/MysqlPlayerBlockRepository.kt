package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.PlayerBlockRepository
import java.util.UUID

class MysqlPlayerBlockRepository(private val ds: HikariDataSource) : PlayerBlockRepository {
    override fun upsert(world: String, x: Int, y: Int, z: Int, owner: UUID?, timestamp: Long) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO player_blocks(world, x, y, z, owner, ts)
                VALUES (?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE owner = new.owner, ts = new.ts
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, world)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.setString(5, owner?.toString())
                ps.setLong(6, timestamp)
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
                    val ts = rs.getLong("ts")
                    return PlayerBlockRepository.BlockEntry(owner, ts)
                }
            }
        }
    }

    override fun pruneOlderThan(cutoffMillis: Long): Int {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM player_blocks WHERE ts < ?").use { ps ->
                ps.setLong(1, cutoffMillis)
                return ps.executeUpdate()
            }
        }
    }
}

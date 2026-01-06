package net.nemoria.quest.storage.repo

import net.nemoria.quest.data.repo.PlayerBlockRepository
import java.util.UUID

object PlayerBlockRepositoryQueries {
    const val FIND_SQL = "SELECT owner, ts FROM player_blocks WHERE world=? AND x=? AND y=? AND z=?"

    fun bindKey(ps: java.sql.PreparedStatement, world: String, x: Int, y: Int, z: Int) {
        ps.setString(1, world)
        ps.setInt(2, x)
        ps.setInt(3, y)
        ps.setInt(4, z)
    }

    fun parseBlockEntry(ownerStr: String?, timestamp: Long?): PlayerBlockRepository.BlockEntry? {
        if (timestamp == null) return null
        val owner = ownerStr?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return PlayerBlockRepository.BlockEntry(owner, timestamp)
    }
}

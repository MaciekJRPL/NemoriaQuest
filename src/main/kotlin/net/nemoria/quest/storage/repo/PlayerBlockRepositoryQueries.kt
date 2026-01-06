package net.nemoria.quest.storage.repo

import net.nemoria.quest.data.repo.PlayerBlockRepository
import java.util.UUID

object PlayerBlockRepositoryQueries {
    fun parseBlockEntry(ownerStr: String?, timestamp: Long?): PlayerBlockRepository.BlockEntry? {
        if (timestamp == null) return null
        val owner = ownerStr?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return PlayerBlockRepository.BlockEntry(owner, timestamp)
    }
}

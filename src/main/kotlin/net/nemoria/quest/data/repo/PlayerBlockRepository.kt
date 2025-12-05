package net.nemoria.quest.data.repo

import java.util.UUID

interface PlayerBlockRepository {
    data class BlockEntry(val owner: UUID?, val timestamp: Long)

    fun upsert(world: String, x: Int, y: Int, z: Int, owner: UUID?, timestamp: Long)
    fun remove(world: String, x: Int, y: Int, z: Int)
    fun find(world: String, x: Int, y: Int, z: Int): BlockEntry?
    fun pruneOlderThan(cutoffMillis: Long): Int
}

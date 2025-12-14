package net.nemoria.quest.runtime

import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.core.Services
import net.nemoria.quest.data.repo.PlayerBlockRepository
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PlayerBlockTracker {
    private val placed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val placedOwner: MutableMap<String, UUID> = ConcurrentHashMap()
    private val placedAt: MutableMap<String, Long> = ConcurrentHashMap()
    private var repo: PlayerBlockRepository? = null
    private const val TTL_MS: Long = 1L * 60 * 60 * 1000
    private var pruneTask: BukkitRunnable? = null

    fun init(repository: PlayerBlockRepository) {
        DebugLog.logToFile("debug-session", "run1", "BLOCK_TRACKER", "PlayerBlockTracker.kt:21", "init entry", mapOf())
        repo = repository
        pruneExpired()
        schedulePrune()
        DebugLog.logToFile("debug-session", "run1", "BLOCK_TRACKER", "PlayerBlockTracker.kt:25", "init completed", mapOf())
    }

    fun markPlaced(block: Block, owner: UUID? = null) {
        DebugLog.logToFile("debug-session", "run1", "BLOCK_TRACKER", "PlayerBlockTracker.kt:27", "markPlaced entry", mapOf("world" to block.world.name, "x" to block.x, "y" to block.y, "z" to block.z, "owner" to (owner?.toString() ?: "null")))
        pruneExpired()
        val k = key(block.location)
        placed.add(k)
        if (owner != null) {
            placedOwner[k] = owner
        }
        placedAt[k] = System.currentTimeMillis()
        repo?.let { r ->
            runAsync {
                r.upsert(block.world.name, block.x, block.y, block.z, owner, placedAt[k] ?: System.currentTimeMillis())
            }
        }
        DebugLog.logToFile("debug-session", "run1", "BLOCK_TRACKER", "PlayerBlockTracker.kt:39", "markPlaced completed", mapOf("key" to k))
    }

    fun isPlayerPlaced(block: Block): Boolean {
        val k = key(block.location)
        ensureLoaded(block)
        if (isExpired(k)) {
            removeKey(k)
            return false
        }
        return placed.contains(k)
    }

    fun owner(block: Block): UUID? {
        val k = key(block.location)
        ensureLoaded(block)
        if (isExpired(k)) {
            removeKey(k)
            return null
        }
        return placedOwner[k]
    }

    fun remove(block: Block) {
        val k = key(block.location)
        removeKey(k)
        repo?.let { r ->
            runAsync { r.remove(block.world.name, block.x, block.y, block.z) }
        }
    }

    private fun key(loc: Location): String =
        "${loc.world?.name}:${loc.blockX}:${loc.blockY}:${loc.blockZ}"

    private fun isExpired(key: String): Boolean {
        val ts = placedAt[key] ?: return false
        return System.currentTimeMillis() - ts > TTL_MS
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val expired = placedAt.filterValues { now - it > TTL_MS }.keys
        expired.forEach { removeKey(it) }
        repo?.let { r ->
            runAsync { r.pruneOlderThan(now - TTL_MS) }
        }
    }

    private fun removeKey(k: String) {
        placed.remove(k)
        placedOwner.remove(k)
        placedAt.remove(k)
    }

    private fun schedulePrune() {
        pruneTask?.cancel()
        if (!Services.hasPlugin()) return
        val plugin = Services.plugin
        pruneTask = object : BukkitRunnable() {
            override fun run() {
                pruneExpired()
            }
        }.also { it.runTaskTimerAsynchronously(plugin, 20L * 60, 20L * 60) } // co 60s
    }

    private fun runAsync(block: () -> Unit) {
        if (!Services.hasPlugin()) return
        val plugin = Services.plugin
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { block() })
    }

    fun shutdown() {
        pruneTask?.cancel()
        pruneTask = null
        placed.clear()
        placedOwner.clear()
        placedAt.clear()
    }

    private fun ensureLoaded(block: Block) {
        val k = key(block.location)
        if (placed.contains(k) || placedAt.containsKey(k)) return
        val r = repo ?: return
        val entry = r.find(block.world.name, block.x, block.y, block.z) ?: return
        placed.add(k)
        entry.owner?.let { placedOwner[k] = it }
        placedAt[k] = entry.timestamp
    }

    fun importLegacy(file: File) {
        if (!file.exists()) return
        val cfg = YamlConfiguration.loadConfiguration(file)
        cfg.getConfigurationSection("blocks")?.getKeys(false)?.forEach { key ->
            val ownerStr = cfg.getString("blocks.$key.owner") ?: cfg.getString("blocks.$key")
            val uuid = ownerStr?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val ts = cfg.getLong("blocks.$key.ts", System.currentTimeMillis())
            val parts = key.split(":")
            if (parts.size != 4) return@forEach
            val world = parts[0]
            val x = parts[1].toIntOrNull() ?: return@forEach
            val y = parts[2].toIntOrNull() ?: return@forEach
            val z = parts[3].toIntOrNull() ?: return@forEach
            repo?.upsert(world, x, y, z, uuid, ts)
        }
        runCatching { file.delete() }
    }
}

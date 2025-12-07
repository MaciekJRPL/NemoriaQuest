package net.nemoria.quest.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.nemoria.quest.core.Colors
import net.nemoria.quest.core.MessageFormatter
import net.nemoria.quest.core.Services
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScoreboardManager {
    private var task: org.bukkit.scheduler.BukkitTask? = null
    private val legacy = LegacyComponentSerializer.builder()
        .character(ChatColor.COLOR_CHAR)
        .hexColors()
        .build()
    private val entryPool = ChatColor.values().map { it.toString() }
    private val cache: MutableMap<UUID, Snapshot> = ConcurrentHashMap()
    private val inFlight: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val cacheTtlMs = 1000L
    private val rendering: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val rendered: MutableMap<UUID, RenderState> = ConcurrentHashMap()

    private data class Snapshot(
        val questId: String?,
        val questNamePlain: String?,
        val detail: String?,
        val showQuest: Boolean,
        val version: String,
        val timestamp: Long
    )

    private data class RenderState(val signature: String)

    fun start() {
        stop()
        val cfg = Services.scoreboardConfig
        if (!cfg.enabled) return
        task = object : BukkitRunnable() {
            override fun run() {
                Bukkit.getOnlinePlayers().forEach { update(it) }
            }
        }.runTaskTimer(Services.plugin, 20L, 20L)
    }

    fun stop() {
        task?.cancel()
        task = null
        cache.clear()
        inFlight.clear()
        rendering.clear()
        rendered.clear()
        Bukkit.getOnlinePlayers().forEach { clear(it) }
    }

    fun update(player: Player) {
        val now = System.currentTimeMillis()
        val snap = cache[player.uniqueId]
        if (snap == null) {
            fetchAsync(player, rerender = true)
            return
        }
        if (now - snap.timestamp > cacheTtlMs) {
            fetchAsync(player, rerender = true)
            return
        }
        val questId = snap.questId
        val questNamePlain = snap.questNamePlain
        val detailRaw = snap.detail
        val showQuest = snap.showQuest
        val manager = Bukkit.getScoreboardManager() ?: return
        if (player.scoreboard == manager.mainScoreboard) {
            player.scoreboard = manager.newScoreboard
        }
        val sb = player.scoreboard
        val objective = sb.getObjective("nemoriaquest") ?: sb.registerNewObjective(
            "nemoriaquest",
            "dummy",
            legacy.deserialize(MessageFormatter.formatLegacyOnly("${Colors.PRIMARY}NemoriaQuest"))
        )
        objective.displaySlot = org.bukkit.scoreboard.DisplaySlot.SIDEBAR

        val lines = mutableListOf<String>()
        val cfg = Services.scoreboardConfig
        val version = Services.plugin.description.version
        val titleTemplate = if (cfg.title.contains("{version}")) cfg.title else (cfg.title + " <secondary>v{version}")
        var title: String
        if (showQuest && questId != null && detailRaw != null && questNamePlain != null) {
            title = formatAndLimit(titleTemplate, cfg.maxTitleLength, mapOf("version" to version))
            objective.displayName(legacy.deserialize(title))
            val placeholders = mapOf(
                "quest_name" to questNamePlain,
                "objective_detail" to detailRaw,
                "version" to version
            )
            cfg.activeLines.forEach { line ->
                lines.add(formatAndLimit(line, cfg.maxLineLength, placeholders))
            }
        } else {
            title = formatAndLimit(titleTemplate, cfg.maxTitleLength, mapOf("version" to version))
            objective.displayName(legacy.deserialize(title))
            val placeholders = mapOf(
                "scoreboard_empty" to Services.i18n.msg("scoreboard.empty"),
                "version" to version
            )
            cfg.emptyLines.forEach { line ->
                lines.add(formatAndLimit(line, cfg.maxLineLength, placeholders))
            }
        }
        val signature = buildString {
            append(title)
            append("|")
            lines.joinTo(this, separator = "\u0001")
        }
        rendered[player.uniqueId]?.let { if (it.signature == signature) return }
        // render lines
        var score = lines.size
        sb.entries.toList().forEach { sb.resetScores(it) }
        sb.teams.toList().filter { it.name.startsWith("nq_line_") }.forEach { it.unregister() }
        lines.forEachIndexed { idx, line ->
            val team = sb.registerNewTeam("nq_line_$idx")
            val entry = entryKey(idx)
            val (prefixText, suffixText) = splitLine(line)
            team.prefix(legacy.deserialize(prefixText))
            team.suffix(legacy.deserialize(suffixText))
            team.addEntry(entry)
            objective.getScore(entry).score = score--
        }
        player.scoreboard = sb
        rendered[player.uniqueId] = RenderState(signature)
    }

    private fun formatAndLimit(raw: String, limit: Int, placeholders: Map<String, String>): String {
        var text = raw
        placeholders.forEach { (k, v) ->
            text = text.replace("{$k}", v)
        }
        val formatted = MessageFormatter.formatLegacyOnly(text)
        return trimWithColors(formatted, limit)
    }

    private fun splitLine(line: String): Pair<String, String> {
        val section = '\u00A7'
        var idx = 0
        var visible = 0
        var lastColor = ""
        while (idx < line.length && visible < 16) {
            val c = line[idx]
            if (c == section && idx + 1 < line.length) {
                val next = line[idx + 1]
                if (next.lowercaseChar() == 'x' && idx + 13 < line.length) {
                    lastColor = line.substring(idx, idx + 14)
                    idx += 14
                    continue
                }
                lastColor = line.substring(idx, idx + 2)
                idx += 2
                continue
            }
            idx++
            visible++
        }
        val prefix = line.substring(0, idx)
        if (idx >= line.length) return prefix to ""
        val suffixRaw = lastColor + line.substring(idx)
        val suffix = trimWithColors(suffixRaw, 16)
        return prefix to suffix
    }

    private fun trimWithColors(input: String, limit: Int): String {
        val sb = StringBuilder()
        var visible = 0
        var i = 0
        val section = '\u00A7'
        while (i < input.length && visible < limit) {
            val c = input[i]
            if (c == section && i + 1 < input.length) {
                val next = input[i + 1]
                if (next.lowercaseChar() == 'x' && i + 13 < input.length) {
                    sb.append(input, i, i + 14)
                    i += 14
                    continue
                }
                sb.append(c).append(next)
                i += 2
                continue
            }
            sb.append(c)
            visible++
            i++
        }
        return sb.toString()
    }

    private fun entryKey(index: Int): String {
        return entryPool.getOrNull(index) ?: "${ChatColor.COLOR_CHAR}${index.toString(16)}${ChatColor.RESET}"
    }

    private fun clear(player: Player) {
        val sb = Bukkit.getScoreboardManager().newScoreboard
        player.scoreboard = sb
    }

    private fun fetchAsync(player: Player, rerender: Boolean) {
        if (!inFlight.add(player.uniqueId)) return
        Bukkit.getScheduler().runTaskAsynchronously(Services.plugin, Runnable {
            try {
                val active = Services.questService.activeQuests(player)
                val questId = active.firstOrNull()
                if (questId == null) {
                    val ts = System.currentTimeMillis()
                    cache[player.uniqueId] = Snapshot(null, null, null, false, Services.plugin.description.version, ts)
                    return@Runnable
                }
                val quest = Services.questService.questInfo(questId)
                if (quest == null || quest.progressNotify?.scoreboard != true) {
                    val ts = System.currentTimeMillis()
                    cache[player.uniqueId] = Snapshot(null, null, null, false, Services.plugin.description.version, ts)
                    return@Runnable
                }
                val detail = Services.questService.currentObjectiveDetail(player, questId)
                    ?: Services.i18n.msg("scoreboard.no_objective")
                val questNamePlain = ChatColor.stripColor(MessageFormatter.formatLegacyOnly(quest.displayName ?: quest.name))
                    ?: (quest.displayName ?: quest.name)
                val ts = System.currentTimeMillis()
                cache[player.uniqueId] = Snapshot(questId, questNamePlain, detail, true, Services.plugin.description.version, ts)
            } finally {
                inFlight.remove(player.uniqueId)
                if (rerender) {
                    Bukkit.getScheduler().runTask(Services.plugin, Runnable {
                        // guard to avoid re-entrancy loops
                        if (!rendering.add(player.uniqueId)) return@Runnable
                        try {
                            if (player.isOnline) {
                                update(player)
                            }
                        } finally {
                            rendering.remove(player.uniqueId)
                        }
                    })
                }
            }
        })
    }
}

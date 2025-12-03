package net.nemoria.quest.gui

import net.nemoria.quest.core.Services
import net.nemoria.quest.quest.QuestModel
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

class ScoreboardManager {
    private var task: org.bukkit.scheduler.BukkitTask? = null

    fun start() {
        stop()
        val cfg = (Services.plugin as? net.nemoria.quest.NemoriaQuestPlugin)?.coreConfig
        if (cfg != null && !cfg.scoreboardEnabled) return
        task = object : BukkitRunnable() {
            override fun run() {
                Bukkit.getOnlinePlayers().forEach { update(it) }
            }
        }.runTaskTimer(Services.plugin, 20L, 20L)
    }

    fun stop() {
        task?.cancel()
        task = null
        Bukkit.getOnlinePlayers().forEach { clear(it) }
    }

    fun update(player: Player) {
        val active = Services.storage.userRepo.load(player.uniqueId).activeQuests
        val quest = active.firstOrNull()?.let { Services.questService.questInfo(it) }
        val progress = active.firstOrNull()?.let { Services.questService.progress(player)[it] }
        val showQuest = quest != null &&
            quest.progressNotify?.scoreboard == true
        val sb = player.scoreboard
        val objective = sb.getObjective("nemoriaquest") ?: sb.registerNewObjective("nemoriaquest", "dummy", ChatColor.GOLD.toString() + "NemoriaQuest")
        objective.displaySlot = org.bukkit.scoreboard.DisplaySlot.SIDEBAR

        val lines = mutableListOf<String>()
        if (showQuest && quest != null) {
            val title = net.nemoria.quest.core.MessageFormatter.format(quest.displayName ?: quest.name)
            objective.displayName = title.take(32)
            val detailRaw = Services.questService.currentObjectiveDetail(player, quest.id) ?: "Brak celu"
            val detail = net.nemoria.quest.core.MessageFormatter.format(detailRaw)
            lines.add(ChatColor.YELLOW.toString() + detail.take(40))
        } else {
            objective.displayName = ChatColor.GOLD.toString() + "NemoriaQuest"
            val msg = Services.i18n.msg("scoreboard.empty")
            lines.add(net.nemoria.quest.core.MessageFormatter.format(msg))
        }
        // render lines
        var score = lines.size
        sb.entries.toList().forEach { sb.resetScores(it) }
        lines.forEach {
            objective.getScore(it).score = score--
        }
        player.scoreboard = sb
    }

    private fun clear(player: Player) {
        val sb = Bukkit.getScoreboardManager().newScoreboard
        player.scoreboard = sb
    }
}

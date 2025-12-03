package net.nemoria.quest.command

import net.nemoria.quest.NemoriaQuestPlugin
import net.nemoria.quest.core.Services
import net.nemoria.quest.core.MessageFormatter
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MainCommand(private val plugin: NemoriaQuestPlugin) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            return handleHelp(sender)
        }
        return when (args[0].lowercase()) {
            "reload" -> handleReload(sender)
            "start" -> handleStart(sender, args)
            "stop" -> handleStop(sender, args)
            "active" -> handleActive(sender)
            "list" -> handleList(sender)
            "info" -> handleInfo(sender, args)
            "complete" -> handleComplete(sender, args)
            "progress" -> handleProgress(sender, args)
            "debug" -> handleDebug(sender, args)
            "gui" -> handleGui(sender, args)
            "prompt" -> handlePrompt(sender, args)
            else -> {
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.unknown")))
                true
            }
        }
    }

    private fun handleReload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.reload")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        plugin.reloadAll()
        sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.reload")))
        return true
    }

    private fun handleStart(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.start")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.start.usage")))
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.player_only")))
            return true
        }
        val result = net.nemoria.quest.core.Services.questService.startQuest(player, args[1])
        net.nemoria.quest.core.DebugLog.log("Command start result=$result quest=${args[1]} player=${player.name}")
        when (result) {
            net.nemoria.quest.quest.QuestService.StartResult.SUCCESS ->
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.start.started", mapOf("quest" to args[1]))))
            net.nemoria.quest.quest.QuestService.StartResult.NOT_FOUND ->
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.start.not_found", mapOf("quest" to args[1]))))
            net.nemoria.quest.quest.QuestService.StartResult.ALREADY_ACTIVE ->
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.start.already_active", mapOf("quest" to args[1]))))
            net.nemoria.quest.quest.QuestService.StartResult.COMPLETION_LIMIT ->
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.start.limit_reached", mapOf("quest" to args[1]))))
            net.nemoria.quest.quest.QuestService.StartResult.REQUIREMENT_FAIL ->
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.start.requirements", mapOf("quest" to args[1]))))
            net.nemoria.quest.quest.QuestService.StartResult.PERMISSION_FAIL ->
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.start.permission", mapOf("quest" to args[1]))))
            net.nemoria.quest.quest.QuestService.StartResult.OFFLINE ->
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.player_only")))
        }
        return true
    }

    private fun handleStop(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.stop")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.stop.usage")))
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.player_only")))
            return true
        }
        net.nemoria.quest.core.Services.questService.stopQuest(player, args[1], complete = false)
        sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.stop.stopped", mapOf("quest" to args[1]))))
        return true
    }

    private fun handleActive(sender: CommandSender): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.active")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.player_only")))
            return true
        }
        val active = net.nemoria.quest.core.Services.questService.activeQuests(player)
        if (active.isEmpty()) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.active.none")))
        } else {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.active.list", mapOf("list" to active.joinToString(", ")))))
        }
        return true
    }

    private fun handleList(sender: CommandSender): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.list")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        val quests = Services.questService.listQuests()
        if (quests.isEmpty()) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.list.none")))
        } else {
            quests.forEach {
                val display = it.displayName ?: it.name
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.list.entry", mapOf("id" to it.id, "name" to display))))
            }
        }
        return true
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.info")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.info.usage")))
            return true
        }
        val quest = Services.questService.questInfo(args[1])
        if (quest == null) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.info.not_found", mapOf("quest" to args[1]))))
            return true
        }
        val desc = quest.description ?: Services.i18n.msg("command.info.no_description")
        sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.info.entry", mapOf("id" to quest.id, "name" to quest.name, "desc" to desc))))
        sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.info.max_completions", mapOf("count" to quest.completion.maxCompletions.toString()))))
        if (quest.requirements.isNotEmpty()) {
            val reqs = quest.requirements.joinToString(", ")
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.info.requirements", mapOf("reqs" to reqs))))
        } else {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.info.requirements_none")))
        }
        if (quest.objectives.isNotEmpty()) {
            quest.objectives.forEachIndexed { idx, obj ->
                val line = Services.i18n.msg(
                    "command.info.objective",
                    mapOf(
                        "index" to (idx + 1).toString(),
                        "id" to obj.id,
                        "desc" to (obj.description ?: Services.i18n.msg("command.info.no_description"))
                    )
                )
                sender.sendMessage(colorize(line))
            }
        }
        return true
    }

    private fun handleComplete(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.complete")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.complete.usage")))
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.player_only")))
            return true
        }
        Services.questService.completeQuest(player, args[1])
        sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.complete.done", mapOf("quest" to args[1]))))
        return true
    }

    private fun handleProgress(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.progress")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.progress.usage")))
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.player_only")))
            return true
        }
        Services.questService.completeObjective(player, args[1], args[2])
        sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.progress.done", mapOf("quest" to args[1], "objective" to args[2]))))
        return true
    }

    private fun handleDebug(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.debug")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.debug.usage")))
            return true
        }
        when (args[1].lowercase()) {
            "on", "true", "1" -> {
                net.nemoria.quest.core.DebugLog.enabled = true
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.debug.enabled")))
            }
            "off", "false", "0" -> {
                net.nemoria.quest.core.DebugLog.enabled = false
                sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.debug.disabled")))
            }
            else -> sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.debug.usage")))
        }
        return true
    }

    private fun handleGui(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.gui")) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.no_permission")))
            return true
        }
        val player = sender as? org.bukkit.entity.Player
        if (player == null) {
            sender.sendMessage(colorize(Services.i18n.msg("prefix") + Services.i18n.msg("command.player_only")))
            return true
        }
        val useActive = args.getOrNull(1)?.equals("active", true) == true
        val cfg = if (useActive) Services.guiActive else Services.guiDefault
        Services.guiManager.openList(player, cfg, filterActive = useActive)
        return true
    }

    private fun handlePrompt(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? org.bukkit.entity.Player ?: return true
        val token = args.getOrNull(1) ?: return true
        Services.questService.handlePromptClick(player, token)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.isEmpty()) return mutableListOf()
        return when (args.size) {
            1 -> {
                val options = listOf("reload", "start", "stop", "active", "list", "info", "complete", "progress", "debug", "gui")
                options.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
            }
            2 -> when (args[0].lowercase()) {
                "start", "stop", "info", "complete", "progress" -> questIds(args[1])
                "gui" -> mutableListOf("active").filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
                else -> mutableListOf()
            }
            else -> mutableListOf()
        }
    }

    private fun questIds(prefix: String): MutableList<String> =
        Services.questService.listQuests()
            .map { it.id }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toMutableList()

    private fun handleHelp(sender: CommandSender): Boolean {
        val version = plugin.description.version
        val configVersion = plugin.coreConfig.configVersion
        val header = Services.i18n.msg("command.help.title", mapOf("version" to version, "config" to configVersion))
        val lines = Services.i18n.msgList("command.help.lines")
        sender.sendMessage(MessageFormatter.format(header))
        lines.forEach { sender.sendMessage(MessageFormatter.format(it)) }
        return true
    }

    private fun colorize(input: String): String = MessageFormatter.format(input)
}

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
            "diverge" -> handleDiverge(sender, args)
            "prompt" -> handlePrompt(sender, args)
            "goto" -> handleGoto(sender, args)
            else -> {
                sendMsg(sender, "command.unknown")
                true
            }
        }
    }

    private fun handleReload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.reload")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        plugin.reloadAll()
        sendMsg(sender, "command.reload")
        return true
    }

    private fun handleStart(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.start")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        if (args.size < 2) {
            sendMsg(sender, "command.start.usage")
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sendMsg(sender, "command.player_only")
            return true
        }
        val result = net.nemoria.quest.core.Services.questService.startQuest(player, args[1], viaCommand = true)
        net.nemoria.quest.core.DebugLog.log("Command start result=$result quest=${args[1]} player=${player.name}")
        when (result) {
            net.nemoria.quest.quest.QuestService.StartResult.SUCCESS ->
                sendMsg(sender, "command.start.started", mapOf("quest" to args[1]))
            net.nemoria.quest.quest.QuestService.StartResult.NOT_FOUND ->
                sendMsg(sender, "command.start.not_found", mapOf("quest" to args[1]))
            net.nemoria.quest.quest.QuestService.StartResult.ALREADY_ACTIVE ->
                sendMsg(sender, "command.start.already_active", mapOf("quest" to args[1]))
            net.nemoria.quest.quest.QuestService.StartResult.COMPLETION_LIMIT ->
                sendMsg(sender, "command.start.limit_reached", mapOf("quest" to args[1]))
            net.nemoria.quest.quest.QuestService.StartResult.REQUIREMENT_FAIL ->
                sendMsg(sender, "command.start.requirements", mapOf("quest" to args[1]))
            net.nemoria.quest.quest.QuestService.StartResult.CONDITION_FAIL ->
                sendMsg(sender, "command.start.conditions", mapOf("quest" to args[1]))
            net.nemoria.quest.quest.QuestService.StartResult.WORLD_RESTRICTED ->
                sendMsg(sender, "command.start.world_restriction", mapOf("quest" to args[1]))
            net.nemoria.quest.quest.QuestService.StartResult.COOLDOWN -> {
                val remaining = net.nemoria.quest.core.Services.questService.cooldownRemainingSeconds(player, args[1])
                val timeFmt = net.nemoria.quest.core.Services.questService.formatDuration(remaining)
                sendMsg(sender, "command.start.cooldown", mapOf("quest" to args[1], "time" to timeFmt))
            }
            net.nemoria.quest.quest.QuestService.StartResult.PERMISSION_FAIL ->
                sendMsg(sender, "command.start.permission", mapOf("quest" to args[1]))
            net.nemoria.quest.quest.QuestService.StartResult.OFFLINE ->
                sendMsg(sender, "command.player_only")
            net.nemoria.quest.quest.QuestService.StartResult.INVALID_BRANCH ->
                sendMsg(sender, "command.start.invalid_branch", mapOf("quest" to args[1]))
        }
        return true
    }

    private fun handleStop(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.stop")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        if (args.size < 2) {
            sendMsg(sender, "command.stop.usage")
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sendMsg(sender, "command.player_only")
            return true
        }
        net.nemoria.quest.core.Services.questService.stopQuest(player, args[1], complete = false)
        sendMsg(sender, "command.stop.stopped", mapOf("quest" to args[1]))
        return true
    }

    private fun handleActive(sender: CommandSender): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.active")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sendMsg(sender, "command.player_only")
            return true
        }
        val active = net.nemoria.quest.core.Services.questService.activeQuests(player)
        if (active.isEmpty()) {
            sendMsg(sender, "command.active.none")
        } else {
            sendMsg(sender, "command.active.list", mapOf("list" to active.joinToString(", ")))
        }
        return true
    }

    private fun handleList(sender: CommandSender): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.list")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        val quests = Services.questService.listQuests()
        if (quests.isEmpty()) {
            sendMsg(sender, "command.list.none")
        } else {
            quests.forEach {
                val display = it.displayName ?: it.name
                sendMsg(sender, "command.list.entry", mapOf("id" to it.id, "name" to display))
            }
        }
        return true
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.info")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        if (args.size < 2) {
            sendMsg(sender, "command.info.usage")
            return true
        }
        val quest = Services.questService.questInfo(args[1])
        if (quest == null) {
            sendMsg(sender, "command.info.not_found", mapOf("quest" to args[1]))
            return true
        }
        val desc = quest.description ?: Services.i18n.msg("command.info.no_description")
        sendMsg(sender, "command.info.entry", mapOf("id" to quest.id, "name" to quest.name, "desc" to desc))
        sendMsg(sender, "command.info.max_completions", mapOf("count" to quest.completion.maxCompletions.toString()))
        if (quest.requirements.isNotEmpty()) {
            val reqs = quest.requirements.joinToString(", ")
            sendMsg(sender, "command.info.requirements", mapOf("reqs" to reqs))
        } else {
            sendMsg(sender, "command.info.requirements_none")
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
                sender.sendMessage(MessageFormatter.format(line))
            }
        }
        return true
    }

    private fun handleComplete(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.complete")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        if (args.size < 2) {
            sendMsg(sender, "command.complete.usage")
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sendMsg(sender, "command.player_only")
            return true
        }
        Services.questService.completeQuest(player, args[1])
        sendMsg(sender, "command.complete.done", mapOf("quest" to args[1]))
        return true
    }

    private fun handleProgress(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.progress")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        if (args.size < 3) {
            sendMsg(sender, "command.progress.usage")
            return true
        }
        val player = sender.server.getPlayer(sender.name)
        if (player == null) {
            sendMsg(sender, "command.player_only")
            return true
        }
        Services.questService.completeObjective(player, args[1], args[2])
        sendMsg(sender, "command.progress.done", mapOf("quest" to args[1], "objective" to args[2]))
        return true
    }

    private fun handleDebug(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.debug")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        if (args.size < 2) {
            sendMsg(sender, "command.debug.usage")
            return true
        }
        when (args[1].lowercase()) {
            "on", "true", "1" -> {
                net.nemoria.quest.core.DebugLog.enabled = true
                sendMsg(sender, "command.debug.enabled")
            }
            "off", "false", "0" -> {
                net.nemoria.quest.core.DebugLog.enabled = false
                sendMsg(sender, "command.debug.disabled")
            }
            else -> sendMsg(sender, "command.debug.usage")
        }
        return true
    }

    private fun handleGui(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.gui")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        val player = sender as? org.bukkit.entity.Player
        if (player == null) {
            sendMsg(sender, "command.player_only")
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

    private fun handleDiverge(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? org.bukkit.entity.Player ?: return true
        val idx = args.getOrNull(1)?.toIntOrNull() ?: return true
        val handled = Services.questService.branchRuntimeHandleChoice(player, idx)
        return handled
    }

    private fun handleGoto(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nemoriaquest.command.goto")) {
            sendMsg(sender, "command.no_permission")
            return true
        }
        val player = sender as? org.bukkit.entity.Player
        if (player == null) {
            sendMsg(sender, "command.player_only")
            return true
        }
        if (args.size < 4) {
            sendMsg(sender, "command.goto.usage")
            return true
        }
        val questId = args[1]
        val branchId = args[2]
        val nodeId = args[3]
        val ok = Services.questService.gotoNode(player, questId, branchId, nodeId)
        if (!ok) {
            sendMsg(sender, "command.goto.fail", mapOf("quest" to questId, "branch" to branchId, "node" to nodeId))
        } else {
            sendMsg(sender, "command.goto.done", mapOf("quest" to questId, "branch" to branchId, "node" to nodeId))
        }
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
                val options = listOf("reload", "start", "stop", "active", "list", "info", "complete", "progress", "debug", "gui", "goto")
                options.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
            }
            2 -> when (args[0].lowercase()) {
                "start", "stop", "info", "complete", "progress" -> questIds(args[1])
                "gui" -> mutableListOf("active").filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
                "goto" -> questIds(args[1])
                else -> mutableListOf()
            }
            3 -> when (args[0].lowercase()) {
                "goto" -> branchIds(args[1], args[2])
                else -> mutableListOf()
            }
            4 -> when (args[0].lowercase()) {
                "goto" -> nodeIds(args[1], args[2], args[3])
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

    private fun branchIds(questId: String, prefix: String): MutableList<String> {
        val quest = Services.questService.questInfo(questId) ?: return mutableListOf()
        return quest.branches.keys.filter { it.startsWith(prefix, ignoreCase = true) }.toMutableList()
    }

    private fun nodeIds(questId: String, branchId: String, prefix: String): MutableList<String> {
        val quest = Services.questService.questInfo(questId) ?: return mutableListOf()
        val branch = quest.branches[branchId] ?: return mutableListOf()
        return branch.objects.keys.filter { it.startsWith(prefix, ignoreCase = true) }.toMutableList()
    }

    private fun handleHelp(sender: CommandSender): Boolean {
        val version = plugin.description.version
        val configVersion = plugin.coreConfig.configVersion
        val headerRaw = Services.i18n.msg("help.title", mapOf("version" to version, "config" to configVersion))
        headerRaw.split("\n").forEach { line ->
            sender.sendMessage(MessageFormatter.format(line))
        }
        val lines = Services.i18n.msgList("help.lines")
        lines.forEach { sender.sendMessage(MessageFormatter.format(it)) }
        return true
    }

    private fun sendMsg(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sender.sendMessage(MessageFormatter.format(Services.i18n.msg(key, placeholders)))
    }
}

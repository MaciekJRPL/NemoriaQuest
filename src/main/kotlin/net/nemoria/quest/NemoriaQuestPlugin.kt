package net.nemoria.quest

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import net.nemoria.quest.hook.ChatHidePacketListener
import org.bukkit.plugin.java.JavaPlugin
import net.nemoria.quest.config.ConfigLoader
import net.nemoria.quest.config.CoreConfig
import net.nemoria.quest.core.Registries
import net.nemoria.quest.config.StorageConfigLoader
import net.nemoria.quest.core.Services
import net.nemoria.quest.storage.DataSourceProvider
import net.nemoria.quest.storage.StorageManager
import net.nemoria.quest.command.MainCommand
import net.nemoria.quest.quest.QuestService
import net.nemoria.quest.core.I18n
import net.nemoria.quest.content.ContentBootstrap
import net.nemoria.quest.util.ResourceExporter
import net.nemoria.quest.hook.ChatHideBukkitListener
import net.nemoria.quest.listener.QuestListeners
import net.nemoria.quest.listener.BranchInteractListener
import net.nemoria.quest.listener.PlayerBlockListener
import net.nemoria.quest.listener.PlayerEntityListener
import net.nemoria.quest.listener.PlayerItemListener
import net.nemoria.quest.listener.PlayerMoveListener
import net.nemoria.quest.listener.PlayerPhysicalListener
import net.nemoria.quest.listener.PlayerMiscListener
import net.nemoria.quest.config.GuiConfigLoader
import net.nemoria.quest.hook.ChatHistoryPacketListener
import net.nemoria.quest.runtime.PlayerBlockTracker
import net.nemoria.quest.core.MessageFormatter
import net.nemoria.quest.core.DebugLog
import java.io.File
import org.bukkit.ChatColor

class NemoriaQuestPlugin : JavaPlugin() {
    lateinit var coreConfig: CoreConfig
        private set
    private var packetEvents: PacketEventsAPI<*>? = null
    private var itemListener: PlayerItemListener? = null

    override fun onLoad() {
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:43", "onLoad entry", mapOf(), this)
        if (server.pluginManager.getPlugin("PacketEvents") != null) {
            net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:45", "onLoad PacketEvents found", mapOf(), this)
            packetEvents = SpigotPacketEventsBuilder.build(this)
            packetEvents?.load()
        } else {
            net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:47", "onLoad PacketEvents not found", mapOf(), this)
        }
    }

    override fun onEnable() {
        Services.plugin = this
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:50", "onEnable entry", mapOf())
        coreConfig = ConfigLoader(this).load()
        net.nemoria.quest.core.DebugLog.enabled = coreConfig.debugEnabled
        net.nemoria.quest.core.DebugLog.debugToLogEnabled = coreConfig.debugToLog
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:53", "onEnable config loaded", mapOf("debugEnabled" to coreConfig.debugEnabled, "debugToLog" to coreConfig.debugToLog, "locale" to coreConfig.locale))
        val storageConfig = StorageConfigLoader(this).load()
        logger.info("NemoriaQuest enabling (targets ${coreConfig.multiVersion.joinToString()})")

        Services.i18n = I18n(coreConfig.locale, "en_US")

        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:60", "onEnable initializing storage", mapOf("backend" to storageConfig.backend.name))
        initStorage(storageConfig)
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:61", "onEnable storage initialized", mapOf())
        exportTexts()

        Registries.bootstrap()

        Services.variables = net.nemoria.quest.core.VariableService(this, Services.storage.serverVarRepo)
        Services.variables.load()
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:66", "onEnable variables loaded", mapOf())
        loadScoreboardConfig()
        Services.scoreboardManager.start()
        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            net.nemoria.quest.hook.PlaceholderHook().register()
            logger.info("PlaceholderAPI detected - NemoriaQuest placeholders aktywne")
        }
        if (packetEvents == null && server.pluginManager.getPlugin("PacketEvents") != null) {
            packetEvents = SpigotPacketEventsBuilder.build(this)
            packetEvents?.load()
        }
        packetEvents?.init()
        packetEvents?.eventManager?.registerListener(ChatHidePacketListener())
        packetEvents?.eventManager?.registerListener(ChatHistoryPacketListener())
        packetEvents?.let { PacketEvents.setAPI(it) }

        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:82", "onEnable bootstrapping content", mapOf())
        ContentBootstrap(this, Services.storage.questModelRepo).bootstrap()
        val questCount = Services.storage.questModelRepo.findAll().size
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:83", "onEnable content bootstrapped", mapOf("questCount" to questCount))
        Services.questService.reloadCitizensNpcActivators()
        Services.questService.preloadParticleScripts()
        loadGuiConfigs()
        logQuestLoadSummary("log.content.action_loaded")
        server.pluginManager.registerEvents(net.nemoria.quest.gui.GuiListener(), this)
        server.pluginManager.registerEvents(net.nemoria.quest.listener.DivergeGuiListener(), this)
        server.pluginManager.registerEvents(ChatHideBukkitListener(), this)
        server.pluginManager.registerEvents(PlayerBlockListener(), this)
        server.pluginManager.registerEvents(PlayerEntityListener(), this)
        itemListener = PlayerItemListener()
        server.pluginManager.registerEvents(itemListener!!, this)
        server.pluginManager.registerEvents(PlayerMoveListener(), this)
        server.pluginManager.registerEvents(PlayerPhysicalListener(), this)
        server.pluginManager.registerEvents(PlayerMiscListener(), this)

        PlayerBlockTracker.init(Services.storage.playerBlockRepo)
        PlayerBlockTracker.importLegacy(java.io.File(dataFolder, "player_blocks.yml"))

        getCommand("nemoriaquest")?.setExecutor(MainCommand(this))
        server.pluginManager.registerEvents(QuestListeners(), this)
        server.pluginManager.registerEvents(BranchInteractListener(), this)
    }

    override fun onDisable() {
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:127", "onDisable entry", mapOf())
        server.scheduler.cancelTasks(this)
        if (::coreConfig.isInitialized) {
            net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:130", "onDisable shutting down services", mapOf())
            runCatching {
                if (Services.hasQuestService()) {
                    Services.questService.shutdown()
                }
            }
            runCatching { PlayerBlockTracker.shutdown() }
            runCatching { itemListener?.shutdown() }
            runCatching { Services.storage.close() }
        }
        Services.scoreboardManager.stop()
        net.nemoria.quest.runtime.ChatHideService.clear()
        org.bukkit.event.HandlerList.unregisterAll(this)
        packetEvents?.terminate()
        logger.info("NemoriaQuest disabled")
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:143", "onDisable completed", mapOf())
    }

    fun reloadAll(): Boolean {
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:146", "reloadAll entry", mapOf())
        saveDefaultConfig()
        reloadConfig()
        coreConfig = ConfigLoader(this).load()
        net.nemoria.quest.core.DebugLog.enabled = coreConfig.debugEnabled
        net.nemoria.quest.core.DebugLog.debugToLogEnabled = coreConfig.debugToLog
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:151", "reloadAll config loaded", mapOf("debugEnabled" to coreConfig.debugEnabled, "debugToLog" to coreConfig.debugToLog))
        Services.i18n = I18n(coreConfig.locale, "en_US")
        val storageConfig = StorageConfigLoader(this).load()
        runCatching {
            if (Services.hasQuestService()) {
                Services.questService.shutdown()
            }
        }
        runCatching { itemListener?.shutdown() }
        server.scheduler.cancelTasks(this)
        PlayerBlockTracker.shutdown()
        runCatching { Services.storage.close() }
        initStorage(storageConfig)
        PlayerBlockTracker.init(Services.storage.playerBlockRepo)
        PlayerBlockTracker.importLegacy(java.io.File(dataFolder, "player_blocks.yml"))
        exportTexts()
        Services.variables = net.nemoria.quest.core.VariableService(this, Services.storage.serverVarRepo)
        Services.variables.load()
        loadScoreboardConfig()
        Services.scoreboardManager.start()
        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            net.nemoria.quest.hook.PlaceholderHook().register()
        }
        ContentBootstrap(this, Services.storage.questModelRepo).bootstrap()
        Services.questService.reloadCitizensNpcActivators()
        Services.questService.preloadParticleScripts()
        org.bukkit.event.HandlerList.unregisterAll(this)
        resumeActiveBranches()
        logQuestLoadSummary("log.content.action_reloaded")
        loadGuiConfigs()
        server.pluginManager.registerEvents(net.nemoria.quest.gui.GuiListener(), this)
        server.pluginManager.registerEvents(net.nemoria.quest.listener.DivergeGuiListener(), this)
        server.pluginManager.registerEvents(ChatHideBukkitListener(), this)
        server.pluginManager.registerEvents(PlayerBlockListener(), this)
        server.pluginManager.registerEvents(PlayerEntityListener(), this)
        itemListener = PlayerItemListener()
        server.pluginManager.registerEvents(itemListener!!, this)
        server.pluginManager.registerEvents(PlayerMoveListener(), this)
        server.pluginManager.registerEvents(PlayerPhysicalListener(), this)
        server.pluginManager.registerEvents(PlayerMiscListener(), this)
        server.pluginManager.registerEvents(QuestListeners(), this)
        server.pluginManager.registerEvents(BranchInteractListener(), this)
        net.nemoria.quest.runtime.ChatHideService.clear()
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:191", "reloadAll completed", mapOf())
        return true
    }

    private fun initStorage(storageConfig: net.nemoria.quest.config.StorageConfig) {
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:194", "initStorage entry", mapOf("backend" to storageConfig.backend.name))
        val dataSource = DataSourceProvider.create(storageConfig)
        Services.storage = StorageManager(storageConfig.backend, dataSource)
        Services.questService = QuestService(this, Services.storage.userRepo, Services.storage.questModelRepo)
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "PLUGIN", "NemoriaQuestPlugin.kt:198", "initStorage completed", mapOf("backend" to storageConfig.backend.name))
    }

    private fun exportTexts() {
        ResourceExporter.exportIfMissing(
            this,
            listOf(
                "texts/en_US/messages.yml",
                "texts/en_US/gui.yml",
                "texts/pl_PL/messages.yml",
                "texts/pl_PL/gui.yml",
                "gui/default.yml",
                "gui/active.yml",
                "content/particle_scripts/demo.yml",
                "default_variables.yml",
                "server_variables.yml",
                "global_variables.yml",
                "content/templates/quest_template.yml",
                "scoreboard.yml"
            )
        )
    }

    private fun resumeActiveBranches() {
        DebugLog.logToFile("debug-session", "run1", "C", "NemoriaQuestPlugin.kt:195", "resumeActiveBranches entry", mapOf("onlinePlayersCount" to server.onlinePlayers.size))
        val scheduler = server.scheduler
        server.onlinePlayers.forEach { player ->
            DebugLog.logToFile("debug-session", "run1", "C", "NemoriaQuestPlugin.kt:197", "resumeActiveBranches forEach", mapOf("playerName" to player.name, "playerUuid" to player.uniqueId.toString(), "isOnline" to player.isOnline))
            scheduler.runTaskAsynchronously(this, Runnable {
                val playerStillOnline = player.isOnline
                val playerObj = player.player
                DebugLog.logToFile("debug-session", "run1", "C", "NemoriaQuestPlugin.kt:198", "resumeActiveBranches async start", mapOf("playerName" to player.name, "playerUuid" to player.uniqueId.toString(), "isOnline" to playerStillOnline, "playerObjNull" to (playerObj == null)))
                Services.questService.preload(player)
                val active = Services.questService.activeQuests(player)
                DebugLog.logToFile("debug-session", "run1", "C", "NemoriaQuestPlugin.kt:200", "resumeActiveBranches after activeQuests", mapOf("playerUuid" to player.uniqueId.toString(), "activeCount" to active.size))
                if (active.isEmpty()) return@Runnable
                val models = active.mapNotNull { Services.questService.questInfo(it) }
                if (models.isEmpty()) return@Runnable
                scheduler.runTask(this, Runnable {
                    val playerObjSync = player.player
                    DebugLog.logToFile("debug-session", "run1", "C", "NemoriaQuestPlugin.kt:204", "resumeActiveBranches sync task", mapOf("playerUuid" to player.uniqueId.toString(), "playerObjNull" to (playerObjSync == null), "modelsCount" to models.size))
                    models.forEach { model ->
                        Services.questService.resumeTimers(player, model)
                        if (model.branches.isNotEmpty()) {
                            Services.questService.resumeBranch(player, model)
                        } else {
                            Services.questService.resumeQuestTimeLimit(player, model)
                        }
                    }
                })
            })
        }
    }

    private fun loadGuiConfigs() {
        val loader = GuiConfigLoader(this)
        Services.guiDefault = loader.load("default")
        Services.guiActive = loader.load("active")
    }

    private fun loadScoreboardConfig() {
        Services.scoreboardConfig = net.nemoria.quest.config.ScoreboardConfigLoader(this).load()
    }

    private fun logQuestLoadSummary(actionKey: String) {
        val quests = Services.storage.questModelRepo.findAll()
        val questCount = quests.size
        val objectiveCount = quests.sumOf { it.objectives.size }
        val branchCount = quests.sumOf { it.branches.size }
        val filesCount = File(dataFolder, "content/quests")
            .listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
            ?.size ?: 0
        val action = Services.i18n.msg(actionKey)
        val raw = Services.i18n.msg(
            "log.content.summary",
            mapOf(
                "action" to action,
                "quests" to questCount.toString(),
                "objectives" to objectiveCount.toString(),
                "branches" to branchCount.toString(),
                "files" to filesCount.toString()
            )
        )
        val colored = MessageFormatter.format(raw, allowCenter = false)
        server.consoleSender.sendMessage(colored)
    }
}

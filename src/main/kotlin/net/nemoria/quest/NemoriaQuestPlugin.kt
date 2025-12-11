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
import java.io.File
import org.bukkit.ChatColor

class NemoriaQuestPlugin : JavaPlugin() {
    lateinit var coreConfig: CoreConfig
        private set
    private var packetEvents: PacketEventsAPI<*>? = null
    private var itemListener: PlayerItemListener? = null

    override fun onLoad() {
        if (server.pluginManager.getPlugin("PacketEvents") != null) {
            packetEvents = SpigotPacketEventsBuilder.build(this)
            packetEvents?.load()
        }
    }

    override fun onEnable() {
        Services.plugin = this
        coreConfig = ConfigLoader(this).load()
        net.nemoria.quest.core.DebugLog.enabled = coreConfig.debugEnabled
        val storageConfig = StorageConfigLoader(this).load()
        logger.info("NemoriaQuest enabling (targets ${coreConfig.multiVersion.joinToString()})")

        Services.i18n = I18n(coreConfig.locale, "en_US")

        initStorage(storageConfig)
        exportTexts()

        Registries.bootstrap()

        Services.variables = net.nemoria.quest.core.VariableService(this, Services.storage.serverVarRepo)
        Services.variables.load()
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

        ContentBootstrap(this, Services.storage.questModelRepo).bootstrap()
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
        server.scheduler.cancelTasks(this)
        if (::coreConfig.isInitialized) {
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
    }

    fun reloadAll(): Boolean {
        saveDefaultConfig()
        reloadConfig()
        coreConfig = ConfigLoader(this).load()
        net.nemoria.quest.core.DebugLog.enabled = coreConfig.debugEnabled
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
        net.nemoria.quest.runtime.ChatHideService.clear()
        return true
    }

    private fun initStorage(storageConfig: net.nemoria.quest.config.StorageConfig) {
        val dataSource = DataSourceProvider.create(storageConfig)
        Services.storage = StorageManager(dataSource)
        Services.questService = QuestService(this, Services.storage.userRepo, Services.storage.questModelRepo)
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
        val scheduler = server.scheduler
        server.onlinePlayers.forEach { player ->
            scheduler.runTaskAsynchronously(this, Runnable {
                Services.questService.preload(player)
                val active = Services.questService.activeQuests(player)
                if (active.isEmpty()) return@Runnable
                val models = active.mapNotNull { Services.questService.questInfo(it) }
                if (models.isEmpty()) return@Runnable
                scheduler.runTask(this, Runnable {
                    models.forEach { model ->
                        val qid = model.id
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

package net.nemoria.quest.core

import net.nemoria.quest.storage.StorageManager
import org.bukkit.plugin.java.JavaPlugin
import net.nemoria.quest.quest.QuestService
import net.nemoria.quest.gui.GuiManager
import net.nemoria.quest.config.GuiConfig
import net.nemoria.quest.core.VariableService
import net.nemoria.quest.gui.ScoreboardManager

object Services {
    lateinit var plugin: JavaPlugin
    lateinit var storage: StorageManager
    lateinit var questService: QuestService
    lateinit var i18n: I18n
    lateinit var variables: VariableService
    val scoreboardManager: ScoreboardManager by lazy { ScoreboardManager() }
    val guiManager: GuiManager by lazy { GuiManager() }
    lateinit var guiDefault: GuiConfig
    lateinit var guiActive: GuiConfig
    lateinit var scoreboardConfig: net.nemoria.quest.config.ScoreboardConfig

    fun hasQuestService(): Boolean = this::questService.isInitialized
    fun hasPlugin(): Boolean = this::plugin.isInitialized
}

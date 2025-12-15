package net.nemoria.quest.gui

import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.core.MessageFormatter
import net.nemoria.quest.core.Services
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

class GuiListener : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:11", "onClick entry", mapOf<String, Any?>("playerUuid" to ((event.whoClicked as? Player)?.uniqueId?.toString() ?: "null"), "clickType" to event.click.name, "slot" to event.slot))
        val player = event.whoClicked as? Player ?: return
        val topHolder = event.view.topInventory.holder
        val manager = Services.guiManager
        when (topHolder) {
            is GuiManager.ListHolder -> {
                DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:16", "onClick ListHolder", mapOf<String, Any?>("playerUuid" to player.uniqueId.toString(), "clickType" to event.click.name, "filterActive" to topHolder.filterActive, "page" to topHolder.page))
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                val item = event.currentItem ?: return
                val questId = manager.questFromItem(item) ?: return
                if (event.click.isRightClick) {
                    val active = Services.questService.activeQuests(player).contains(questId)
                    DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:22", "onClick right click", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId, "active" to active))
                    if (active) {
                        Services.questService.stopQuest(player, questId, complete = false)
                        MessageFormatter.send(player, Services.i18n.msg("command.stop.stopped", mapOf("quest" to questId)))
                    } else {
                        val result = Services.questService.startQuest(player, questId, viaCommand = false)
                        when (result) {
                            net.nemoria.quest.quest.QuestService.StartResult.SUCCESS ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.started", mapOf("quest" to questId)))
                            net.nemoria.quest.quest.QuestService.StartResult.NOT_FOUND ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.not_found", mapOf("quest" to questId)))
                            net.nemoria.quest.quest.QuestService.StartResult.ALREADY_ACTIVE ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.already_active", mapOf("quest" to questId)))
                            net.nemoria.quest.quest.QuestService.StartResult.COMPLETION_LIMIT ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.limit_reached", mapOf("quest" to questId)))
                            net.nemoria.quest.quest.QuestService.StartResult.REQUIREMENT_FAIL ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.requirements", mapOf("quest" to questId)))
                            net.nemoria.quest.quest.QuestService.StartResult.CONDITION_FAIL ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.conditions", mapOf("quest" to questId)))
                            net.nemoria.quest.quest.QuestService.StartResult.WORLD_RESTRICTED ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.world_restriction", mapOf("quest" to questId)))
                            net.nemoria.quest.quest.QuestService.StartResult.COOLDOWN -> {
                                val remaining = Services.questService.cooldownRemainingSeconds(player, questId)
                                val timeFmt = Services.questService.formatDuration(remaining)
                                MessageFormatter.send(player, Services.i18n.msg("command.start.cooldown", mapOf("quest" to questId, "time" to timeFmt)))
                            }
                            net.nemoria.quest.quest.QuestService.StartResult.PERMISSION_FAIL ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.permission", mapOf("quest" to questId)))
                            net.nemoria.quest.quest.QuestService.StartResult.OFFLINE ->
                                MessageFormatter.send(player, Services.i18n.msg("command.player_only"))
                            net.nemoria.quest.quest.QuestService.StartResult.INVALID_BRANCH ->
                                MessageFormatter.send(player, Services.i18n.msg("command.start.invalid_branch", mapOf("quest" to questId)))
                        }
                    }
                    manager.openList(player, topHolder.config, topHolder.filterActive, topHolder.page)
                } else if (event.click.isLeftClick) {
                    DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:26", "onClick left click", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to questId))
                    Services.storage.questModelRepo.findById(questId)?.let { manager.openDetail(player, it) }
                }
            }
            is GuiManager.DetailHolder -> {
                DebugLog.logToFile("debug-session", "run1", "GUI", "GuiListener.kt:30", "onClick DetailHolder", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to topHolder.questId))
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                // LPM anywhere wraca do listy
                manager.openList(player, Services.guiDefault, filterActive = false)
            }
            else -> return
        }
    }
}

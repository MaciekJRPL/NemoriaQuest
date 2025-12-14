package net.nemoria.quest.gui

import net.nemoria.quest.config.GuiConfig
import net.nemoria.quest.config.GuiType
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.core.MessageFormatter
import net.nemoria.quest.core.Services
import net.nemoria.quest.quest.QuestModel
import net.nemoria.quest.quest.QuestStatusItemState
import net.nemoria.quest.data.user.QuestProgress
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class GuiManager {
    private val keyQuestId: NamespacedKey by lazy { NamespacedKey(Services.plugin, "quest_id") }

    fun openList(player: Player, config: GuiConfig, filterActive: Boolean = false, page: Int = 0) {
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:22", "openList entry", mapOf("playerUuid" to player.uniqueId.toString(), "filterActive" to filterActive, "page" to page, "guiType" to config.type.name))
        val holder = ListHolder(filterActive, page, config)
        val inv = Bukkit.createInventory(holder, config.type.size, MessageFormatter.format(config.name))
        holder.inv = inv
        val all = Services.questService.listQuests()
        val userData = Services.storage.userRepo.load(player.uniqueId)
        val activeSet = userData.activeQuests
        val completedSet = userData.completedQuests
        var shown = all.filter { !filterActive || activeSet.contains(it.id) }
        val allowedStatuses = config.showStatus.map { it.uppercase() }.toSet()
        if (allowedStatuses.isNotEmpty()) {
            shown = shown.filter { allowedStatuses.contains(status(player, it, activeSet, completedSet).name) }
        }
        if (!config.orderQuests) {
            shown = shown.shuffled()
        }
        if (config.sortQuestsByStatus) {
            shown = shown.sortedBy { statusWeight(status(player, it, activeSet, completedSet)) }
        }
        val startIndex = page * PAGE_SIZE
        val pageItems = shown.drop(startIndex).take(PAGE_SIZE)
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:41", "openList filtered", mapOf("playerUuid" to player.uniqueId.toString(), "allCount" to all.size, "shownCount" to shown.size, "pageItemsCount" to pageItems.size))

        fillBorder(inv)
        pageItems.forEachIndexed { idx, model ->
            val slot = CONTENT_SLOTS.getOrNull(idx) ?: return@forEachIndexed
            val st = status(player, model, activeSet, completedSet)
            val progress = userData.progress[model.id]
            inv.setItem(slot, questItem(player, model, st, progress))
        }
        inv.setItem(inv.size - 5, toggleItem(filterActive))
        player.openInventory(inv)
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:52", "openList opened", mapOf("playerUuid" to player.uniqueId.toString(), "pageItemsCount" to pageItems.size))
    }

    fun openDetail(player: Player, model: QuestModel) {
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:55", "openDetail entry", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to model.id))
        val holder = DetailHolder(model.id)
        val inv = Bukkit.createInventory(holder, 27, MessageFormatter.format("<gold>${model.displayName ?: model.name}"))
        holder.inv = inv
        fillBorder(inv)
        val info = ItemStack(Material.WRITABLE_BOOK)
        info.itemMeta = info.itemMeta.apply {
            setDisplayName(MessageFormatter.format("<yellow>${model.displayName ?: model.name}"))
            lore = buildList {
                add(MessageFormatter.format("<gray>ID: ${model.id}"))
                model.description?.let { add(MessageFormatter.format("<white>$it")) }
                if (model.descriptionLines.isNotEmpty()) {
                    addAll(model.descriptionLines.map { MessageFormatter.format("<gray>$it") })
                }
                if (model.objectives.isNotEmpty()) {
                    add(MessageFormatter.format("<gold>Cele:"))
                    model.objectives.forEach { obj ->
                        add(MessageFormatter.format("<gray>- ${obj.description ?: obj.id}"))
                    }
                }
            }
        }
        inv.setItem(13, info)
        player.openInventory(inv)
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:78", "openDetail opened", mapOf("playerUuid" to player.uniqueId.toString(), "questId" to model.id))
    }

    internal fun questFromItem(item: ItemStack?): String? {
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:81", "questFromItem entry", mapOf("hasItem" to (item != null), "itemType" to (item?.type?.name ?: "null")))
        if (item == null) {
            DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:82", "questFromItem null item", mapOf())
            return null
        }
        val meta = item.itemMeta
        if (meta == null) {
            DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:84", "questFromItem no meta", mapOf())
            return null
        }
        val container = meta.persistentDataContainer
        val questId = container.get(keyQuestId, org.bukkit.persistence.PersistentDataType.STRING)
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:87", "questFromItem result", mapOf("questId" to (questId ?: "null")))
        return questId
    }

    private fun questItem(player: Player, model: QuestModel, status: QuestStatusItemState, progress: QuestProgress?): ItemStack {
        val statusTemplate = model.statusItems[status] ?: model.defaultStatusItem
        val placeholders = buildPlaceholders(player, model, status, progress)
        val type = statusTemplate?.type?.let { runCatching { Material.valueOf(it.uppercase()) }.getOrNull() } ?: Material.PAPER
        val item = ItemStack(type)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(render(player, model, statusTemplate?.name ?: "<white>${model.displayName ?: model.name}", placeholders, progress))
        meta.lore = buildLore(player, model, statusTemplate, placeholders, status, progress)
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        meta.persistentDataContainer.set(keyQuestId, org.bukkit.persistence.PersistentDataType.STRING, model.id)
        statusTemplate?.customModelData?.let { meta.setCustomModelData(it) }
        item.itemMeta = meta
        return item
    }

    private fun toggleItem(filterActive: Boolean): ItemStack {
        val item = ItemStack(Material.COMPASS)
        item.itemMeta = item.itemMeta.apply {
            setDisplayName(MessageFormatter.format(if (filterActive) "<green>Widok: aktywne" else "<yellow>Widok: wszystkie"))
            lore = listOf(MessageFormatter.format("<gray>Kliknij aby przełączyć"))
        }
        return item
    }

    private fun fillBorder(inv: Inventory) {
        val pane = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        pane.itemMeta = pane.itemMeta.apply { setDisplayName(" ") }
        for (i in inv.contents.indices) {
            if (i < 9 || i >= inv.size - 9 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane)
            }
        }
    }

    class ListHolder(val filterActive: Boolean, val page: Int, val config: GuiConfig) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }
    class DetailHolder(val questId: String) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private val CONTENT_SLOTS = listOf(
            10,11,12,13,14,15,16,
            19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,
            37,38,39,40,41,42,43
        )
        private const val PAGE_SIZE = 28

        private fun status(_player: Player, model: QuestModel, active: Set<String>, completed: Set<String>): QuestStatusItemState {
            return when {
                active.contains(model.id) -> QuestStatusItemState.PROGRESS
                completed.contains(model.id) -> QuestStatusItemState.COMPLETED
                else -> QuestStatusItemState.AVAILABLE
            }
        }

        private fun statusWeight(st: QuestStatusItemState): Int = when (st) {
            QuestStatusItemState.AVAILABLE -> 0
            QuestStatusItemState.PROGRESS -> 1
            QuestStatusItemState.UNAVAILABLE -> 2
            QuestStatusItemState.COMPLETED -> 3
        }

        private fun buildPlaceholders(player: Player, model: QuestModel, status: QuestStatusItemState, progress: net.nemoria.quest.data.user.QuestProgress?): Map<String, String> {
            val objectiveDetail = currentObjectiveDetail(player, model, progress)
            val controls = when (status) {
                QuestStatusItemState.AVAILABLE -> Services.i18n.msg("gui.controls.start", mapOf("quest" to model.name))
                QuestStatusItemState.PROGRESS -> Services.i18n.msg("gui.controls.stop", mapOf("quest" to model.name))
                QuestStatusItemState.COMPLETED -> Services.i18n.msg("gui.controls.completed", mapOf("quest" to model.name))
                else -> ""
            }
            val completions = when {
                status == QuestStatusItemState.COMPLETED -> Services.i18n.msg("gui.status.completed")
                progress != null && progress.objectives.values.any { it.completed } -> Services.i18n.msg("gui.status.in_progress")
                else -> ""
            }
            val errors = if (status == QuestStatusItemState.UNAVAILABLE) Services.i18n.msg("gui.status.unavailable") else ""
            val lastCompletionTs = progress?.objectives?.values?.maxOfOrNull { it.completedAt ?: 0L } ?: 0L
            val lastCompletion = if (lastCompletionTs > 0L) {
                Services.i18n.msg("gui.status.last_completion", mapOf("ts" to lastCompletionTs.toString()))
            } else ""
            return mapOf(
                "name" to (model.displayName ?: model.name),
                "quest" to (model.displayName ?: model.name),
                "id" to model.id,
                "description" to (model.description ?: ""),
                "objective_detail" to (objectiveDetail ?: ""),
                "detailed_progression" to (objectiveDetail ?: ""),
                "controls" to controls,
                "completions" to completions,
                "last_completion" to lastCompletion,
                "cooldown" to "",
                "detailed_errors" to errors
            )
        }

        private fun render(player: Player, model: QuestModel, text: String, placeholders: Map<String, String>, _progress: QuestProgress?): String {
            var out = text
            placeholders.forEach { (k, v) -> out = out.replace("{$k}", v) }
            out = Services.questService.renderPlaceholders(out, model.id, player)
            return MessageFormatter.format(out)
        }

        private fun buildLore(
            player: Player,
            model: QuestModel,
            tpl: net.nemoria.quest.quest.StatusItemTemplate?,
            placeholders: Map<String, String>,
            status: QuestStatusItemState,
            progress: QuestProgress?
        ): List<String> {
            val loreLines = mutableListOf<String>()
            model.description?.let { loreLines.add(MessageFormatter.format("<white>$it")) }
            placeholders["objective_detail"]?.takeIf { it.isNotBlank() }?.let {
                loreLines.add(MessageFormatter.format("<light_purple>$it"))
            }
            if (tpl?.lore?.isNotEmpty() == true) {
                loreLines.addAll(tpl.lore.map { render(player, model, it, placeholders, progress) })
            }
            loreLines.add(MessageFormatter.format(if (status == QuestStatusItemState.PROGRESS) "<green>Aktywny (PPM: stop)" else "<yellow>PPM: start, LPM: szczegóły"))
            return loreLines
        }

        private fun currentObjectiveDetail(player: Player, model: QuestModel, progress: QuestProgress?): String? {
            if (model.branches.isEmpty()) return null
            val branchId = progress?.currentBranchId ?: model.mainBranch ?: model.branches.keys.firstOrNull() ?: return null
            val nodeId = progress?.currentNodeId ?: model.branches[branchId]?.startsAt ?: model.branches[branchId]?.objects?.keys?.firstOrNull()
            val node = model.branches[branchId]?.objects?.get(nodeId) ?: return null
            val desc = node.description ?: return null
            return Services.questService.renderPlaceholders(desc, model.id, player)
        }
    }
}

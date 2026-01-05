package net.nemoria.quest.gui

import net.nemoria.quest.config.GuiConfig
import net.nemoria.quest.config.GuiItemConfig
import net.nemoria.quest.config.GuiItemStackConfig
import net.nemoria.quest.config.GuiItemType
import net.nemoria.quest.config.GuiListSource
import net.nemoria.quest.config.GuiPageConfig
import net.nemoria.quest.config.GuiRankingSpec
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
import kotlin.math.max

class GuiManager {
    private val keyQuestId: NamespacedKey by lazy { NamespacedKey(Services.plugin, "quest_id") }
    private val keyGuiItemId: NamespacedKey by lazy { NamespacedKey(Services.plugin, "gui_item_id") }

    fun openList(player: Player, config: GuiConfig, filterActive: Boolean = false, page: Int = 0) {
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:22", "openList entry", mapOf("playerUuid" to player.uniqueId.toString(), "filterActive" to filterActive, "page" to page, "guiType" to config.type.name))
        if (config.pages.isNotEmpty()) {
            val pageId = if (filterActive && config.pages.containsKey("active")) "active" else config.defaultPage ?: "main"
            openPage(player, config, pageId, page, allowedQuestIds = null)
            return
        }
        val holder = ListHolder(filterActive, page, config)
        val inv = Bukkit.createInventory(holder, config.type.size, MessageFormatter.format(config.name))
        holder.inv = inv
        val all = Services.questService.listQuests()
        val activeSet = Services.questService.activeQuests(player)
        val completedSet = Services.questService.completedQuests(player)
        val progressMap = Services.questService.progress(player)
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
            val progress = progressMap[model.id]
            inv.setItem(slot, questItem(player, model, st, progress))
        }
        inv.setItem(inv.size - 5, toggleItem(filterActive))
        player.openInventory(inv)
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:52", "openList opened", mapOf("playerUuid" to player.uniqueId.toString(), "pageItemsCount" to pageItems.size))
    }

    fun openListFiltered(player: Player, config: GuiConfig, allowedQuestIds: Set<String>, filterActive: Boolean = false, page: Int = 0) {
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:54", "openListFiltered entry", mapOf("playerUuid" to player.uniqueId.toString(), "allowedCount" to allowedQuestIds.size, "filterActive" to filterActive, "page" to page, "guiType" to config.type.name))
        if (config.pages.isNotEmpty()) {
            val pageId = if (filterActive && config.pages.containsKey("active")) "active" else config.defaultPage ?: "main"
            openPage(player, config, pageId, page, allowedQuestIds)
            return
        }
        val holder = ListHolder(filterActive, page, config)
        val inv = Bukkit.createInventory(holder, config.type.size, MessageFormatter.format(config.name))
        holder.inv = inv
        val all = Services.questService.listQuests().filter { allowedQuestIds.contains(it.id) }
        val activeSet = Services.questService.activeQuests(player)
        val completedSet = Services.questService.completedQuests(player)
        val progressMap = Services.questService.progress(player)
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

        fillBorder(inv)
        pageItems.forEachIndexed { idx, model ->
            val slot = CONTENT_SLOTS.getOrNull(idx) ?: return@forEachIndexed
            val st = status(player, model, activeSet, completedSet)
            val progress = progressMap[model.id]
            inv.setItem(slot, questItem(player, model, st, progress))
        }
        inv.setItem(inv.size - 5, toggleItem(filterActive))
        player.openInventory(inv)
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:86", "openListFiltered opened", mapOf("playerUuid" to player.uniqueId.toString(), "pageItemsCount" to pageItems.size))
    }

    fun openPage(player: Player, config: GuiConfig, pageId: String, page: Int = 0, allowedQuestIds: Set<String>? = null) {
        val pageCfg = config.pages[pageId] ?: run {
            openList(player, config, filterActive = false, page = page)
            return
        }
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:90", "openPage entry", mapOf("playerUuid" to player.uniqueId.toString(), "pageId" to pageId, "page" to page, "guiType" to (pageCfg.type ?: config.type).name))
        val holder = PageHolder(pageId, page, config, allowedQuestIds)
        val invType = pageCfg.type ?: config.type
        val title = pageCfg.name ?: config.name
        val inv = Bukkit.createInventory(holder, invType.size, MessageFormatter.format(title))
        holder.inv = inv
        if (pageCfg.fillBorder) {
            fillBorder(inv)
        }

        val all = Services.questService.listQuests()
        val activeSet = Services.questService.activeQuests(player)
        val completedSet = Services.questService.completedQuests(player)
        val progressMap = Services.questService.progress(player)
        val contentSlots = (pageCfg.contentSlots.takeIf { it.isNotEmpty() } ?: CONTENT_SLOTS)
            .filter { it in 0 until inv.size }

        val listItem = applyPageStaticItems(player, pageCfg, inv)
        applyPageListItems(
            player,
            config,
            inv,
            listItem,
            all,
            activeSet,
            completedSet,
            progressMap,
            contentSlots,
            allowedQuestIds,
            page
        )

        player.openInventory(inv)
        DebugLog.logToFile("debug-session", "run1", "GUI", "GuiManager.kt:149", "openPage opened", mapOf("playerUuid" to player.uniqueId.toString(), "pageId" to pageId, "page" to page))
    }

    private fun applyPageStaticItems(player: Player, pageCfg: GuiPageConfig, inv: Inventory): GuiItemConfig? {
        var listItem: GuiItemConfig? = null
        pageCfg.items.forEach { itemCfg ->
            when (itemCfg.type) {
                GuiItemType.LIST -> if (listItem == null) listItem = itemCfg
                GuiItemType.BUTTON, GuiItemType.STATIC -> {
                    val slot = itemCfg.slot ?: return@forEach
                    if (slot !in 0 until inv.size) return@forEach
                    val item = buildStaticItem(player, itemCfg, null) ?: return@forEach
                    inv.setItem(slot, item)
                }
                GuiItemType.RANKING -> {
                    val slot = itemCfg.slot ?: return@forEach
                    if (slot !in 0 until inv.size) return@forEach
                    val item = buildRankingItem(player, itemCfg) ?: return@forEach
                    inv.setItem(slot, item)
                }
            }
        }
        return listItem
    }

    private fun applyPageListItems(
        player: Player,
        config: GuiConfig,
        inv: Inventory,
        listItem: GuiItemConfig?,
        all: Collection<QuestModel>,
        activeSet: Set<String>,
        completedSet: Set<String>,
        progressMap: Map<String, QuestProgress>,
        contentSlots: List<Int>,
        allowedQuestIds: Set<String>?,
        page: Int
    ) {
        listItem?.let { itemCfg ->
            val slots = (itemCfg.slots.takeIf { it.isNotEmpty() } ?: contentSlots)
                .filter { it in 0 until inv.size }
            if (slots.isEmpty()) return@let
            var shown = when (itemCfg.source) {
                GuiListSource.ALL -> all
                GuiListSource.ACTIVE -> all.filter { activeSet.contains(it.id) }
                GuiListSource.GROUP -> {
                    val groupIds = config.groups[itemCfg.group] ?: emptyList()
                    all.filter { groupIds.contains(it.id) }
                }
                GuiListSource.CUSTOM -> all.filter { itemCfg.questIds.contains(it.id) }
            }
            if (allowedQuestIds != null) {
                shown = shown.filter { allowedQuestIds.contains(it.id) }
            }
            val allowedStatuses = itemCfg.showStatus.ifEmpty { config.showStatus }.map { it.uppercase() }.toSet()
            if (allowedStatuses.isNotEmpty()) {
                shown = shown.filter { allowedStatuses.contains(status(player, it, activeSet, completedSet).name) }
            }
            val orderQuests = itemCfg.orderQuests ?: config.orderQuests
            val sortByStatus = itemCfg.sortQuestsByStatus ?: config.sortQuestsByStatus
            if (!orderQuests) {
                shown = shown.shuffled()
            }
            if (sortByStatus) {
                shown = shown.sortedBy { statusWeight(status(player, it, activeSet, completedSet)) }
            }
            val startIndex = page * slots.size
            val pageItems = shown.drop(startIndex).take(slots.size)
            pageItems.forEachIndexed { idx, model ->
                val slot = slots.getOrNull(idx) ?: return@forEachIndexed
                val st = status(player, model, activeSet, completedSet)
                val progress = progressMap[model.id]
                inv.setItem(slot, questItem(player, model, st, progress))
            }
        }
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

    internal fun guiItemIdFromItem(item: ItemStack?): String? {
        if (item == null) return null
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(keyGuiItemId, org.bukkit.persistence.PersistentDataType.STRING)
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


    internal fun handlePageItemClick(player: Player, holder: PageHolder, itemId: String) {
        val pageCfg = holder.config.pages[holder.pageId] ?: return
        val itemCfg = pageCfg.items.firstOrNull { it.id == itemId } ?: return
        if (itemCfg.commands.isNotEmpty()) {
            val commands = itemCfg.commands.map { it.replace("{player}", player.name) }
            if (itemCfg.commandsAsPlayer) {
                commands.forEach { player.performCommand(it) }
            } else {
                val console = Bukkit.getServer().consoleSender
                commands.forEach { Bukkit.dispatchCommand(console, it) }
            }
        }
        val nextPage = when {
            itemCfg.nextPage -> holder.page + 1
            itemCfg.prevPage -> max(0, holder.page - 1)
            else -> holder.page
        }
        val targetPage = itemCfg.openPage ?: holder.pageId
        if (itemCfg.nextPage || itemCfg.prevPage || itemCfg.openPage != null || itemCfg.commands.isNotEmpty()) {
            openPage(player, holder.config, targetPage, nextPage, holder.allowedQuestIds)
        }
    }

    private fun buildStaticItem(player: Player, itemCfg: GuiItemConfig, questId: String?): ItemStack? {
        val cfg = itemCfg.item ?: return null
        val material = runCatching { Material.valueOf(cfg.type.uppercase()) }.getOrNull() ?: Material.STONE
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        val name = cfg.name?.let { renderSimple(player, questId, it) } ?: ""
        if (name.isNotBlank()) meta.setDisplayName(name)
        if (cfg.lore.isNotEmpty()) {
            meta.lore = cfg.lore.mapNotNull { renderSimple(player, questId, it) }.filter { it.isNotBlank() }
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        cfg.customModelData?.let { meta.setCustomModelData(it) }
        meta.persistentDataContainer.set(keyGuiItemId, org.bukkit.persistence.PersistentDataType.STRING, itemCfg.id)
        item.itemMeta = meta
        return item
    }

    private fun buildRankingItem(player: Player, itemCfg: GuiItemConfig): ItemStack? {
        val ranking = itemCfg.ranking ?: return null
        val baseCfg = itemCfg.item ?: GuiItemStackConfig(type = "PAPER")
        val material = runCatching { Material.valueOf(baseCfg.type.uppercase()) }.getOrNull() ?: Material.PAPER
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        val name = baseCfg.name?.let { renderSimple(player, null, it) } ?: Services.i18n.msg("gui.ranking.title")
        if (name.isNotBlank()) meta.setDisplayName(MessageFormatter.format(name))
        val lines = buildRankingLines(player, ranking)
        val baseLore = baseCfg.lore.mapNotNull { renderSimple(player, null, it) }
        val finalLore = if (baseLore.any { it.contains("{ranking}") }) {
            baseLore.flatMap { line ->
                if (line.contains("{ranking}")) lines else listOf(line)
            }
        } else {
            baseLore + lines
        }
        if (finalLore.isNotEmpty()) {
            meta.lore = finalLore
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        baseCfg.customModelData?.let { meta.setCustomModelData(it) }
        meta.persistentDataContainer.set(keyGuiItemId, org.bukkit.persistence.PersistentDataType.STRING, itemCfg.id)
        item.itemMeta = meta
        return item
    }

    private fun buildRankingLines(player: Player, ranking: GuiRankingSpec): List<String> {
        val titleLine = ranking.title ?: Services.i18n.msg("gui.ranking.title")
        val lineFormat = ranking.lineFormat ?: Services.i18n.msg("gui.ranking.line")
        val emptyLine = ranking.emptyLine ?: Services.i18n.msg("gui.ranking.empty")
        val entries = Services.questService.guiRanking(ranking.type, ranking.questId, ranking.limit)
        val lines = mutableListOf<String>()
        if (titleLine.isNotBlank()) {
            lines.add(MessageFormatter.format(titleLine))
        }
        if (entries.isEmpty()) {
            lines.add(MessageFormatter.format(emptyLine))
            return lines
        }
        entries.forEachIndexed { idx, entry ->
            var line = lineFormat
                .replace("{pos}", (idx + 1).toString())
                .replace("{player}", entry.name)
                .replace("{value}", entry.value.toString())
            line = MessageFormatter.format(line)
            lines.add(line)
        }
        return lines
    }

    private fun renderSimple(player: Player, questId: String?, text: String): String {
        var out = text.replace("{player}", player.name)
        if (!questId.isNullOrBlank()) {
            out = Services.questService.renderPlaceholders(out, questId, player)
        }
        return MessageFormatter.format(out)
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

    class PageHolder(val pageId: String, val page: Int, val config: GuiConfig, val allowedQuestIds: Set<String>? = null) : InventoryHolder {
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
            if (tpl?.lore?.isNotEmpty() == true) {
                return tpl.lore
                    .map { render(player, model, it, placeholders, progress) }
                    .filter { it.isNotBlank() }
            }
            val loreLines = mutableListOf<String>()
            model.description?.let { loreLines.add(MessageFormatter.format("<white>$it")) }
            placeholders["objective_detail"]?.takeIf { it.isNotBlank() }?.let {
                loreLines.add(MessageFormatter.format("<light_purple>$it"))
            }
            loreLines.add(MessageFormatter.format(if (status == QuestStatusItemState.PROGRESS) "<green>Aktywny (PPM: stop)" else "<yellow>PPM: start, LPM: szczegóły"))
            return loreLines
        }

        private fun currentObjectiveDetail(player: Player, model: QuestModel, progress: QuestProgress?): String? {
            if (model.branches.isEmpty()) return null
            return Services.questService.currentObjectiveDetail(player, model.id)
        }
    }
}

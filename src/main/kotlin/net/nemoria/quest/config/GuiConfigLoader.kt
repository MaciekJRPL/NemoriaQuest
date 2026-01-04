package net.nemoria.quest.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class GuiConfigLoader(private val plugin: JavaPlugin) {
    fun load(name: String): GuiConfig {
        val file = File(plugin.dataFolder, "gui/$name.yml")
        if (!file.exists()) {
            plugin.saveResource("gui/$name.yml", false)
        }
        val cfg = YamlConfiguration.loadConfiguration(file)
        val guiName = cfg.getString("name", "<gold>NemoriaQuest") ?: "<gold>NemoriaQuest"
        val type = GuiType.fromString(cfg.getString("type"))
        val showStatus = cfg.getStringList("show_status")
        val order = cfg.getBoolean("order_quests", true)
        val sortStatus = cfg.getBoolean("sort_quests_by_status", true)
        val defaultPage = cfg.getString("default_page")
        val groups = cfg.getConfigurationSection("groups")?.let { sec ->
            sec.getKeys(false).associateWith { key -> sec.getStringList(key) }
        } ?: emptyMap()
        val pages = cfg.getConfigurationSection("pages")?.let { pagesSec ->
            pagesSec.getKeys(false).associateWith { pageId ->
                val pageSec = pagesSec.getConfigurationSection(pageId) ?: return@associateWith GuiPageConfig()
                val pageName = pageSec.getString("name")
                val pageTypeRaw = pageSec.getString("type")
                val pageType = if (pageTypeRaw.isNullOrBlank()) null else GuiType.fromString(pageTypeRaw)
                val fillBorder = pageSec.getBoolean("fill_border", true)
                val contentSlots = pageSec.getIntegerList("content_slots")
                val items = pageSec.getConfigurationSection("items")?.let { itemsSec ->
                    itemsSec.getKeys(false).map { itemId ->
                        val itemSec = itemsSec.getConfigurationSection(itemId) ?: return@map GuiItemConfig(itemId)
                        val itemType = GuiItemType.fromString(itemSec.getString("type"))
                        val slot = if (itemSec.contains("slot")) itemSec.getInt("slot") else null
                        val slots = itemSec.getIntegerList("slots")
                        val item = itemSec.getConfigurationSection("item")?.let { meta ->
                            GuiItemStackConfig(
                                type = meta.getString("type", "STONE") ?: "STONE",
                                name = meta.getString("name"),
                                lore = meta.getStringList("lore"),
                                customModelData = if (meta.contains("custom_model_data")) meta.getInt("custom_model_data") else null
                            )
                        }
                        val source = GuiListSource.fromString(itemSec.getString("source"))
                        val group = itemSec.getString("group")
                        val questIds = itemSec.getStringList("quest_ids")
                        val showStatusItem = itemSec.getStringList("show_status")
                        val orderItem = if (itemSec.contains("order_quests")) itemSec.getBoolean("order_quests") else null
                        val sortItem = if (itemSec.contains("sort_quests_by_status")) itemSec.getBoolean("sort_quests_by_status") else null
                        val openPage = itemSec.getString("open_page")
                        val commands = itemSec.getStringList("commands")
                        val commandsAsPlayer = itemSec.getBoolean("commands_as_player", true)
                        val nextPage = itemSec.getBoolean("next_page", false)
                        val prevPage = itemSec.getBoolean("prev_page", false)
                        val ranking = itemSec.getConfigurationSection("ranking")?.let { rank ->
                            GuiRankingSpec(
                                type = GuiRankingType.fromString(rank.getString("type")),
                                questId = rank.getString("quest_id"),
                                limit = rank.getInt("limit", 10),
                                title = rank.getString("title"),
                                lineFormat = rank.getString("line_format"),
                                emptyLine = rank.getString("empty_line")
                            )
                        }
                        GuiItemConfig(
                            id = itemId,
                            type = itemType,
                            slot = slot,
                            slots = slots,
                            item = item,
                            source = source,
                            group = group,
                            questIds = questIds,
                            showStatus = showStatusItem,
                            orderQuests = orderItem,
                            sortQuestsByStatus = sortItem,
                            openPage = openPage,
                            commands = commands,
                            commandsAsPlayer = commandsAsPlayer,
                            nextPage = nextPage,
                            prevPage = prevPage,
                            ranking = ranking
                        )
                    }
                } ?: emptyList()
                GuiPageConfig(
                    name = pageName,
                    type = pageType,
                    items = items,
                    fillBorder = fillBorder,
                    contentSlots = contentSlots
                )
            }
        } ?: emptyMap()
        return GuiConfig(guiName, type, showStatus, order, sortStatus, defaultPage, pages, groups)
    }
}

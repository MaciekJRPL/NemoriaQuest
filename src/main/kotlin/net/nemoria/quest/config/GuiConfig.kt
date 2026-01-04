package net.nemoria.quest.config

data class GuiConfig(
    val name: String = "<gold>NemoriaQuest",
    val type: GuiType = GuiType.CHEST_6_ROW,
    val showStatus: List<String> = emptyList(),
    val orderQuests: Boolean = true,
    val sortQuestsByStatus: Boolean = true,
    val defaultPage: String? = null,
    val pages: Map<String, GuiPageConfig> = emptyMap(),
    val groups: Map<String, List<String>> = emptyMap()
)

enum class GuiType(val size: Int) {
    CHEST_3_ROW(27),
    CHEST_6_ROW(54);

    companion object {
        fun fromString(raw: String?): GuiType =
            raw?.let { runCatching { valueOf(it.uppercase()) }.getOrDefault(CHEST_6_ROW) } ?: CHEST_6_ROW
    }
}

data class GuiPageConfig(
    val name: String? = null,
    val type: GuiType? = null,
    val items: List<GuiItemConfig> = emptyList(),
    val fillBorder: Boolean = true,
    val contentSlots: List<Int> = emptyList()
)

data class GuiItemConfig(
    val id: String,
    val type: GuiItemType = GuiItemType.STATIC,
    val slot: Int? = null,
    val slots: List<Int> = emptyList(),
    val item: GuiItemStackConfig? = null,
    val source: GuiListSource = GuiListSource.ALL,
    val group: String? = null,
    val questIds: List<String> = emptyList(),
    val showStatus: List<String> = emptyList(),
    val orderQuests: Boolean? = null,
    val sortQuestsByStatus: Boolean? = null,
    val openPage: String? = null,
    val commands: List<String> = emptyList(),
    val commandsAsPlayer: Boolean = true,
    val nextPage: Boolean = false,
    val prevPage: Boolean = false,
    val ranking: GuiRankingSpec? = null
)

data class GuiItemStackConfig(
    val type: String = "STONE",
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val customModelData: Int? = null
)

data class GuiRankingSpec(
    val type: GuiRankingType = GuiRankingType.COMPLETED_QUESTS,
    val questId: String? = null,
    val limit: Int = 10,
    val title: String? = null,
    val lineFormat: String? = null,
    val emptyLine: String? = null
)

enum class GuiItemType {
    LIST,
    BUTTON,
    RANKING,
    STATIC;

    companion object {
        fun fromString(raw: String?): GuiItemType =
            raw?.let { runCatching { valueOf(it.uppercase()) }.getOrDefault(STATIC) } ?: STATIC
    }
}

enum class GuiListSource {
    ALL,
    ACTIVE,
    GROUP,
    CUSTOM;

    companion object {
        fun fromString(raw: String?): GuiListSource =
            raw?.let { runCatching { valueOf(it.uppercase()) }.getOrDefault(ALL) } ?: ALL
    }
}

enum class GuiRankingType {
    COMPLETED_QUESTS,
    COMPLETED_OBJECTIVES,
    QUEST_COMPLETED;

    companion object {
        fun fromString(raw: String?): GuiRankingType =
            raw?.let { runCatching { valueOf(it.uppercase()) }.getOrDefault(COMPLETED_QUESTS) } ?: COMPLETED_QUESTS
    }
}

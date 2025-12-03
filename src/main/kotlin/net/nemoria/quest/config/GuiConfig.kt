package net.nemoria.quest.config

data class GuiConfig(
    val name: String = "<gold>NemoriaQuest",
    val type: GuiType = GuiType.CHEST_6_ROW,
    val showStatus: List<String> = emptyList(),
    val orderQuests: Boolean = true,
    val sortQuestsByStatus: Boolean = true
)

enum class GuiType(val size: Int) {
    CHEST_3_ROW(27),
    CHEST_6_ROW(54);

    companion object {
        fun fromString(raw: String?): GuiType =
            raw?.let { runCatching { valueOf(it.uppercase()) }.getOrDefault(CHEST_6_ROW) } ?: CHEST_6_ROW
    }
}

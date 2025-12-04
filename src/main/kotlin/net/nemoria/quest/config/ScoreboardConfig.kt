package net.nemoria.quest.config

data class ScoreboardConfig(
    val enabled: Boolean = true,
    val title: String = "<primary>NemoriaQuest",
    val emptyLines: List<String> = listOf("<secondary>Nothing to display"),
    val activeLines: List<String> = listOf("<primary>{quest_name}", "<secondary>{objective_detail}"),
    val maxTitleLength: Int = 32,
    val maxLineLength: Int = 40
)

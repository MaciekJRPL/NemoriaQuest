package net.nemoria.quest.data.user

data class QuestPoolsState(
    val pools: MutableMap<String, QuestPoolData> = mutableMapOf(),
    val tokens: MutableMap<String, Int> = mutableMapOf()
)

data class QuestPoolData(
    var streak: Int = 0,
    val lastProcessedFrame: MutableMap<String, Long> = mutableMapOf(),
    val limitedFrames: MutableMap<String, QuestPoolTimeWindow> = mutableMapOf()
)

data class QuestPoolTimeWindow(
    val startMs: Long,
    val endMs: Long
)

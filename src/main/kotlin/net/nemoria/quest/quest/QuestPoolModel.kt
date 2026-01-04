package net.nemoria.quest.quest

import java.time.DayOfWeek
import java.time.Month

data class QuestPoolModel(
    val id: String,
    val displayName: String? = null,
    val timeFrames: List<QuestPoolTimeFrame> = emptyList(),
    val quests: Map<String, QuestPoolQuestEntry> = emptyMap(),
    val questGroups: Map<String, QuestPoolGroupEntry> = emptyMap(),
    val order: QuestPoolOrder = QuestPoolOrder.IN_ORDER,
    val amount: Int = 0,
    val amountTolerance: QuestPoolAmountTolerance = QuestPoolAmountTolerance.DONT_COUNT_STARTED,
    val rewards: List<QuestPoolReward> = emptyList()
)

enum class QuestPoolOrder { IN_ORDER, RANDOM }

enum class QuestPoolAmountTolerance { COUNT_STARTED, DONT_COUNT_STARTED }

data class QuestPoolQuestEntry(
    val questId: String,
    val processConditions: List<ConditionEntry> = emptyList(),
    val preResetTokens: Boolean = false,
    val preStop: Boolean = false,
    val preResetHistory: Boolean = false,
    val selectedResetTokens: Boolean = false,
    val selectedStop: Boolean = false,
    val selectedResetHistory: Boolean = false,
    val minTokens: Int = 1,
    val maxTokens: Int = 1,
    val refundTokenOnEndTypes: List<String> = emptyList()
)

data class QuestPoolGroupEntry(
    val groupId: String,
    val processConditions: List<ConditionEntry> = emptyList(),
    val preResetTokens: Boolean = false,
    val preStop: Boolean = false,
    val preResetHistory: Boolean = false,
    val selectedResetTokens: Boolean = false,
    val selectedStop: Boolean = false,
    val selectedResetHistory: Boolean = false,
    val amount: Int = 1,
    val minTokens: Int = 1,
    val maxTokens: Int = 1,
    val refundTokenOnEndTypes: List<String> = emptyList()
)

data class QuestPoolReward(
    val reward: QuestEndObject? = null,
    val node: QuestObjectNode? = null,
    val minStreak: Int = 1,
    val maxStreak: Int = Int.MAX_VALUE
)

data class QuestPoolTimeFrame(
    val id: String,
    val type: QuestPoolTimeFrameType,
    val start: QuestPoolTimePoint? = null,
    val end: QuestPoolTimePoint? = null,
    val durationSeconds: Long? = null
)

enum class QuestPoolTimeFrameType {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    REPEAT_PERIOD,
    LIMITED
}

data class QuestPoolTimePoint(
    val month: Month? = null,
    val dayOfMonth: Int? = null,
    val dayOfWeek: DayOfWeek? = null,
    val hour: Int? = null,
    val minute: Int? = null
)

data class QuestPoolGroup(
    val id: String,
    val quests: List<String>
)

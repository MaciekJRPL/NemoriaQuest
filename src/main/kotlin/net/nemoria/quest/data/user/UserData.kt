package net.nemoria.quest.data.user

import java.util.UUID

data class UserData(
    val uuid: UUID,
    val activeQuests: MutableSet<String> = mutableSetOf(),
    val completedQuests: MutableSet<String> = mutableSetOf(),
    val progress: MutableMap<String, QuestProgress> = mutableMapOf(),
    val userVariables: MutableMap<String, String> = mutableMapOf(),
    val cooldowns: MutableMap<String, QuestCooldown> = mutableMapOf(),
    val questPools: QuestPoolsState = QuestPoolsState(),
    var actionbarEnabled: Boolean = true,
    var titleEnabled: Boolean = true
)

data class QuestProgress(
    val objectives: MutableMap<String, ObjectiveState> = mutableMapOf(),
    val variables: MutableMap<String, String> = mutableMapOf(),
    var currentBranchId: String? = null,
    var currentNodeId: String? = null,
    var timeLimitStartedAt: Long? = null,
    val randomHistory: MutableSet<String> = mutableSetOf(),
    val groupState: MutableMap<String, GroupProgress> = mutableMapOf(),
    val divergeCounts: MutableMap<String, Int> = mutableMapOf(),
    val nodeProgress: MutableMap<String, Double> = mutableMapOf() // key: branchId:nodeId -> progress value
)

data class QuestCooldown(
    val lastEndType: String? = null,
    val lastAt: Long? = null
)

data class ObjectiveState(
    var completed: Boolean = false,
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var progress: Int = 0
)

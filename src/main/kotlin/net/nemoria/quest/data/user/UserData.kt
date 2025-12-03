package net.nemoria.quest.data.user

import java.util.UUID

data class UserData(
    val uuid: UUID,
    val activeQuests: MutableSet<String> = mutableSetOf(),
    val completedQuests: MutableSet<String> = mutableSetOf(),
    val progress: MutableMap<String, QuestProgress> = mutableMapOf(),
    val userVariables: MutableMap<String, String> = mutableMapOf()
)

data class QuestProgress(
    val objectives: MutableMap<String, ObjectiveState> = mutableMapOf(),
    val variables: MutableMap<String, String> = mutableMapOf(),
    var currentBranchId: String? = null,
    var currentNodeId: String? = null,
    val randomHistory: MutableSet<String> = mutableSetOf(),
    val groupState: MutableMap<String, GroupProgress> = mutableMapOf(),
    val divergeCounts: MutableMap<String, Int> = mutableMapOf()
)

data class ObjectiveState(
    var completed: Boolean = false,
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var progress: Int = 0
)

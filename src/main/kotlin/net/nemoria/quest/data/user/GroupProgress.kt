package net.nemoria.quest.data.user

data class GroupProgress(
    val completed: MutableSet<String> = mutableSetOf(),
    val remaining: MutableList<String> = mutableListOf(),
    val required: Int = 0,
    val ordered: Boolean = false
)

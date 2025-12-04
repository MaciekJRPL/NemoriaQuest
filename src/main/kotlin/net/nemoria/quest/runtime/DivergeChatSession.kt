package net.nemoria.quest.runtime

import net.nemoria.quest.quest.DivergeChoice
import net.kyori.adventure.text.Component

data class DivergeChatSession(
    val choices: List<DivergeChoice>,
    val intro: List<String> = emptyList(),
    val dialogMode: Boolean = false,
    var currentIndex: Int = 1,
    var lastRenderIdx: Int = 1,
    var lastRenderAt: Long = 0L,
    var lastDialog: List<String> = emptyList(),
    val originalHistory: List<Component> = emptyList(),
    val greyHistory: List<Component> = emptyList(),
    val baselineSize: Int = 0,
    val syntheticMessages: MutableSet<String> = mutableSetOf()
)

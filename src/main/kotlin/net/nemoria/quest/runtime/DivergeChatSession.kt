package net.nemoria.quest.runtime

import net.nemoria.quest.quest.DivergeChoice

data class DivergeChatSession(
    val choices: List<DivergeChoice>,
    val intro: List<String> = emptyList(),
    var lastRenderIdx: Int = 1,
    var lastRenderAt: Long = 0L
)

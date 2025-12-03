package net.nemoria.quest.hook

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.nemoria.quest.core.Services
import org.bukkit.entity.Player

class PlaceholderHook : PlaceholderExpansion() {
    override fun getIdentifier(): String = "nemoriaquest"
    override fun getAuthor(): String = "Nemoria"
    override fun getVersion(): String = "1.0"

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        val p = player ?: return ""
        return when (params.lowercase()) {
            "objective_detail" -> Services.questService.currentObjectiveDetail(p) ?: ""
            else -> null
        }
    }
}

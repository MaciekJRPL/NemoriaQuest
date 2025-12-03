package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.core.DebugLog
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerInteractEntityEvent

class BranchInteractListener : Listener {
    @EventHandler
    fun onNpcInteract(event: PlayerInteractEntityEvent) {
        val npcId = resolveNpcId(event.rightClicked) ?: return
        DebugLog.log("NPC interact player=${event.player.name} npcId=$npcId entity=${event.rightClicked.type}")
        Services.questService.branchRuntimeHandleNpc(event.player, npcId)
    }

    @EventHandler
    fun onChat(event: AsyncPlayerChatEvent) {
        val msg = event.message.trim()
        val idx = msg.toIntOrNull()
        if (idx != null) {
            val handled = Services.questService.branchRuntimeHandleChoice(event.player, idx)
            if (handled) {
                event.isCancelled = true
            }
        }
    }

    private fun resolveNpcId(entity: Entity): Int? {
        val id = runCatching {
            val clazz = Class.forName("net.citizensnpcs.api.CitizensAPI")
            val regMethod = clazz.getMethod("getNPCRegistry")
            val registry = regMethod.invoke(null)
            val getNpc = registry.javaClass.getMethod("getNPC", Entity::class.java)
            val npc = getNpc.invoke(registry, entity) ?: return null
            val idMethod = npc.javaClass.getMethod("getId")
            idMethod.invoke(npc) as? Int
        }.getOrNull()
        if (id == null) {
            DebugLog.log("NPC interact entity=${entity.type} not recognized as Citizens NPC")
        }
        return id
    }
}

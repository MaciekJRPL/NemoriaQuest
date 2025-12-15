package net.nemoria.quest.listener

import net.nemoria.quest.core.Services
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.runtime.ChatHideService
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot

class BranchInteractListener : Listener {
    @EventHandler
    fun onNpcInteract(event: PlayerInteractEntityEvent) {
        if (!Services.hasQuestService()) return
        if (event.hand != EquipmentSlot.HAND) return
        DebugLog.logToFile("debug-session", "run1", "CITIZENS", "BranchInteractListener.kt:16", "onNpcInteract entry", mapOf("playerUuid" to event.player.uniqueId.toString(), "entityType" to event.rightClicked.type.name))
        val info = resolveNpcInfo(event.rightClicked) ?: run {
            DebugLog.logToFile("debug-session", "run1", "CITIZENS", "BranchInteractListener.kt:18", "onNpcInteract not Citizens NPC", mapOf("playerUuid" to event.player.uniqueId.toString(), "entityType" to event.rightClicked.type.name))
            return
        }
        val npcId = info.first
        val npcName = info.second
        DebugLog.log("NPC interact player=${event.player.name} npcId=$npcId npcName=${npcName ?: "null"} entity=${event.rightClicked.type}")
        DebugLog.logToFile("debug-session", "run1", "CITIZENS", "BranchInteractListener.kt:22", "onNpcInteract resolved", mapOf("playerUuid" to event.player.uniqueId.toString(), "npcId" to npcId, "npcName" to (npcName ?: "null")))
        val handled = Services.questService.branchRuntimeHandleNpc(event.player, npcId, npcName, "RIGHT_CLICK")
        DebugLog.logToFile("debug-session", "run1", "CITIZENS", "BranchInteractListener.kt:23", "onNpcInteract handled", mapOf("playerUuid" to event.player.uniqueId.toString(), "npcId" to npcId, "handled" to handled))
        if (!handled) {
            if (Services.questService.shouldBlockCitizensNpcActivator(event.player, npcId, npcName)) {
                DebugLog.logToFile("debug-session", "run1", "CITIZENS", "BranchInteractListener.kt:24", "onNpcInteract activator blocked by active npc node", mapOf("playerUuid" to event.player.uniqueId.toString(), "npcId" to npcId))
                return
            }
            DebugLog.logToFile("debug-session", "run1", "CITIZENS", "BranchInteractListener.kt:24", "onNpcInteract trying activator", mapOf("playerUuid" to event.player.uniqueId.toString(), "npcId" to npcId))
            Services.questService.handleCitizensNpcActivator(event.player, npcId)
        }
    }

    @EventHandler
    fun onChat(event: AsyncPlayerChatEvent) {
        // Wyłącz wybór przez numer (wymaganie: tylko klik i scroll)
        if (!Services.hasQuestService()) return
        if (ChatHideService.isDialogActive(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onHotbarScroll(event: PlayerItemHeldEvent) {
        if (!Services.hasQuestService()) return
    }

    @EventHandler
    fun onLeftClick(event: PlayerInteractEvent) {
        if (!Services.hasQuestService()) return
    }

    private fun resolveNpcInfo(entity: Entity): Pair<Int, String?>? {
        val pair = runCatching {
            val clazz = Class.forName("net.citizensnpcs.api.CitizensAPI")
            val regMethod = clazz.getMethod("getNPCRegistry")
            val registry = regMethod.invoke(null)
            val getNpc = registry.javaClass.getMethod("getNPC", Entity::class.java)
            val npc = getNpc.invoke(registry, entity) ?: return null
            val idMethod = npc.javaClass.getMethod("getId")
            val id = idMethod.invoke(npc) as? Int ?: return null
            val name = runCatching { npc.javaClass.getMethod("getName").invoke(npc) as? String }.getOrNull()
            id to name
        }.onFailure { ex ->
            DebugLog.logToFile("debug-session", "run1", "CITIZENS", "BranchInteractListener.kt:59", "resolveNpcInfo error", mapOf("entityType" to entity.type.name, "errorType" to ex.javaClass.simpleName, "errorMessage" to (ex.message?.take(100) ?: "null")))
        }.getOrNull()
        if (pair == null) {
            DebugLog.log("NPC interact entity=${entity.type} not recognized as Citizens NPC")
        } else {
            DebugLog.logToFile("debug-session", "run1", "CITIZENS", "BranchInteractListener.kt:71", "resolveNpcInfo success", mapOf("entityType" to entity.type.name, "npcId" to pair.first, "npcName" to (pair.second ?: "null")))
        }
        return pair
    }
}

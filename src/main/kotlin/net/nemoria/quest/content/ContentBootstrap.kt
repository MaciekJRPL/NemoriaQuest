package net.nemoria.quest.content

import net.nemoria.quest.data.repo.QuestModelRepository
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class ContentBootstrap(
    private val plugin: JavaPlugin,
    private val questRepo: QuestModelRepository
) {
    fun bootstrap() {
        val contentDir = File(plugin.dataFolder, "content")
        ensureDirs(contentDir)
        writeSampleQuestIfEmpty(File(contentDir, "quests"))
        loadQuests(File(contentDir, "quests"))
    }

    private fun ensureDirs(contentDir: File) {
        listOf(
            File(contentDir, "quests"),
            File(contentDir, "pools"),
            File(contentDir, "groups"),
            File(contentDir, "variables"),
            File(contentDir, "points"),
            File(contentDir, "activators"),
            File(contentDir, "branches")
        ).forEach { it.mkdirs() }
    }

    private fun writeSampleQuestIfEmpty(questsDir: File) {
        val files = questsDir.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
        if (files != null && files.isNotEmpty()) return
        val sample = File(questsDir, "example_fetch.yml")
        if (!sample.exists()) {
            sample.writeText(
                """
                id: example_fetch
                name: Example Fetch
                description: Collect 10 apples and return to the NPC.
                display_name: "&aBanker 1"
                description_lines:
                  - "&aBanker."
                saving: ENABLED
                concurrency:
                  max_instances: -1
                  queue_on_limit: false
                players:
                  min: 1
                  max: 1
                  allow_leader_stop: true
                start_conditions:
                  match_amount: 0
                  no_match_amount: 1
                  conditions:
                    - type: PERMISSION
                      permission: zalozone.konto.bankier
                      amount: 1
                completion:
                  max_completions: 1
                activators: []
                progress_notify:
                  actionbar: "&7{objective}"
                  actionbar_duration: 10 SECOND
                  scoreboard: true
                status_items:
                  AVAILABLE:
                    type: COOKED_CHICKEN
                    name: "&a{name} &8- &aDOSTEPNA"
                    lore:
                      - "&7Opis przed rozpoczeciem."
                  PROGRESS:
                    type: COOKED_CHICKEN
                    name: "&a{name} &8- &6AKTYWNA"
                    lore:
                      - "{detailed_progression}"
                  UNAVAILABLE:
                    type: PAPER
                    name: "&a{name} &8- &cNIEDOSTEPNA"
                    lore:
                      - "&7{description}"
                      - "&cUkoncz zadanie &aRIWAN&c, aby aktywowac zadanie."
                    custom_model_data: 13
                  COMPLETED:
                    type: PAPER
                    name: "&a{name} &8- &aUKONCZONA"
                    lore:
                      - "&Opis po ukonczeniu."
                    custom_model_data: 14
                requirements: []
                objectives:
                  - id: wait_intro
                    type: TIMER
                    duration: 5
                    description: Wait for 5 seconds
                  - id: manual_turnin
                    type: MANUAL
                    description: Turn in apples
                """.trimIndent()
            )
        }
    }

    private fun loadQuests(questsDir: File) {
        val quests = QuestContentLoader.loadAll(questsDir)
        questRepo.saveAll(quests)
    }
}

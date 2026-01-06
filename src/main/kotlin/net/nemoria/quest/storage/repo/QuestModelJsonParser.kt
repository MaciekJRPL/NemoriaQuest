package net.nemoria.quest.storage.repo

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.nemoria.quest.quest.*

class QuestModelJsonParser(private val gson: Gson) {
    private val objectivesType = object : TypeToken<List<QuestObjective>>() {}.type
    private val descriptionLinesType = object : TypeToken<List<String>>() {}.type
    private val statusItemsType = object : TypeToken<Map<QuestStatusItemState, StatusItemTemplate>>() {}.type
    private val statusItemType = object : TypeToken<StatusItemTemplate>() {}.type
    private val requirementsType = object : TypeToken<List<String>>() {}.type
    private val rewardsType = object : TypeToken<QuestRewards>() {}.type
    private val branchesType = object : TypeToken<Map<String, Branch>>() {}.type
    private val variablesType = object : TypeToken<Map<String, String>>() {}.type
    private val endObjectsType = object : TypeToken<Map<String, List<QuestEndObject>>>() {}.type

    fun parseObjectives(raw: String?): List<QuestObjective> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<QuestObjective>>(raw, objectivesType) }.getOrDefault(emptyList())
    }

    fun parseDescriptionLines(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, descriptionLinesType) }.getOrDefault(emptyList())
    }

    fun parseStatusItems(raw: String?): Map<QuestStatusItemState, StatusItemTemplate> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<QuestStatusItemState, StatusItemTemplate>>(raw, statusItemsType)
        }.getOrDefault(emptyMap())
    }

    fun parseStatusItem(raw: String?): StatusItemTemplate? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson<StatusItemTemplate>(raw, statusItemType) }.getOrNull()
    }

    fun parseWorldRestriction(raw: String?): WorldRestriction? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, WorldRestriction::class.java) }.getOrNull()
    }

    fun parseCommandRestriction(raw: String?): CommandRestriction? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, CommandRestriction::class.java) }.getOrNull()
    }

    fun parseCooldown(raw: String?): CooldownSettings? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, CooldownSettings::class.java) }.getOrNull()
    }

    fun parseProgressNotify(raw: String?): ProgressNotify? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, ProgressNotify::class.java) }.getOrNull()
    }

    fun parseRequirements(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, requirementsType) }.getOrDefault(emptyList())
    }

    fun parseRewards(raw: String?): QuestRewards =
        runCatching { gson.fromJson<QuestRewards>(raw, rewardsType) }.getOrDefault(QuestRewards())

    fun parseTimeLimit(raw: String?): TimeLimit? =
        runCatching { gson.fromJson(raw, TimeLimit::class.java) }.getOrNull()

    fun parseVariables(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            gson.fromJson<Map<String, String>>(raw, variablesType)!!.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    fun parseBranches(raw: String?): Map<String, Branch> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { gson.fromJson<Map<String, Branch>>(raw, branchesType) }.getOrDefault(emptyMap())
    }

    fun parseEndObjects(raw: String?): Map<String, List<QuestEndObject>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { gson.fromJson<Map<String, List<QuestEndObject>>>(raw, endObjectsType) }
            .getOrDefault(emptyMap())
    }

    fun parseSaving(raw: String?): SavingMode =
        runCatching { gson.fromJson(raw, SavingMode::class.java) }.getOrDefault(SavingMode.ENABLED)

    fun parseConcurrency(raw: String?): Concurrency =
        runCatching { gson.fromJson(raw, Concurrency::class.java) }.getOrDefault(Concurrency())

    fun parsePlayers(raw: String?): PlayerSettings =
        runCatching { gson.fromJson(raw, PlayerSettings::class.java) }.getOrDefault(PlayerSettings())

    fun parseStartConditions(raw: String?): StartConditions? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, StartConditions::class.java) }.getOrNull()
    }

    fun parseCompletion(raw: String?): CompletionSettings =
        runCatching { gson.fromJson(raw, CompletionSettings::class.java) }.getOrDefault(CompletionSettings())

    fun parseActivators(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, requirementsType) }.getOrDefault(emptyList())
    }

    fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, requirementsType) }.getOrDefault(emptyList())
    }

    fun parseNotifySettings(raw: String?): NotifySettings? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, NotifySettings::class.java) }.getOrNull()
    }
}

package net.nemoria.quest.content

import net.nemoria.quest.quest.*
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.DayOfWeek
import java.time.Month

object PoolContentLoader {
    data class LoadResult(
        val pools: Map<String, QuestPoolModel>,
        val groups: Map<String, QuestPoolGroup>
    )

    fun loadAll(poolDirs: List<File>, groupsDir: File): LoadResult {
        val groups = loadGroups(groupsDir)
        val pools = linkedMapOf<String, QuestPoolModel>()
        poolDirs.forEach { dir ->
            if (!dir.exists() || !dir.isDirectory) return@forEach
            dir.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
                ?.sortedBy { it.nameWithoutExtension }
                ?.forEach { file ->
                    loadPool(file)?.let { pool ->
                        pools[pool.id.lowercase()] = pool
                    }
                }
        }
        return LoadResult(pools, groups)
    }

    private fun loadGroups(dir: File): Map<String, QuestPoolGroup> {
        if (!dir.exists() || !dir.isDirectory) return emptyMap()
        val result = linkedMapOf<String, QuestPoolGroup>()
        dir.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                val cfg = YamlConfiguration.loadConfiguration(file)
                val id = cfg.getString("id") ?: file.nameWithoutExtension
                val questsRaw = cfg.get("quests") ?: cfg.get("quest_ids")
                val quests = readStringListFlexible(questsRaw)
                if (quests.isNotEmpty()) {
                    result[id.lowercase()] = QuestPoolGroup(id = id, quests = quests)
                }
            }
        return result
    }

    private fun loadPool(file: File): QuestPoolModel? {
        val cfg = YamlConfiguration.loadConfiguration(file)
        val id = cfg.getString("id") ?: file.nameWithoutExtension
        val displayName = cfg.getString("display_name")
        val timeFrames = parseTimeFrames(cfg.getList("time_frames"), id)
        val order = runCatching { QuestPoolOrder.valueOf(cfg.getString("order", "IN_ORDER")!!.uppercase()) }
            .getOrDefault(QuestPoolOrder.IN_ORDER)
        val amount = cfg.getInt("amount", 0)
        val amountTolerance = runCatching {
            QuestPoolAmountTolerance.valueOf(cfg.getString("amount_tolerance", "DONT_COUNT_STARTED")!!.uppercase())
        }.getOrDefault(QuestPoolAmountTolerance.DONT_COUNT_STARTED)
        val quests = parsePoolQuests(cfg.getConfigurationSection("quests"))
        val groups = parsePoolGroups(cfg.getConfigurationSection("quest_groups"))
        val rewards = parseRewards(cfg.getList("rewards"), cfg.getConfigurationSection("rewards"))
        return QuestPoolModel(
            id = id,
            displayName = displayName,
            timeFrames = timeFrames,
            quests = quests,
            questGroups = groups,
            order = order,
            amount = amount,
            amountTolerance = amountTolerance,
            rewards = rewards
        )
    }

    private fun parsePoolQuests(sec: ConfigurationSection?): Map<String, QuestPoolQuestEntry> {
        if (sec == null) return emptyMap()
        val out = linkedMapOf<String, QuestPoolQuestEntry>()
        sec.getKeys(false).forEach { questId ->
            val qsec = sec.getConfigurationSection(questId) ?: return@forEach
            val conds = parseProcessConditions(qsec)
            val entry = QuestPoolQuestEntry(
                questId = questId,
                processConditions = conds,
                preResetTokens = qsec.getBoolean("pre_reset_token", false),
                preStop = qsec.getBoolean("pre_stop", false),
                preResetHistory = qsec.getBoolean("pre_reset_history", false),
                selectedResetTokens = qsec.getBoolean("selected_reset_tokens", false),
                selectedStop = qsec.getBoolean("selected_stop", false),
                selectedResetHistory = qsec.getBoolean("selected_reset_history", false),
                minTokens = qsec.getInt("min_tokens", 1),
                maxTokens = qsec.getInt("max_tokens", 1),
                refundTokenOnEndTypes = qsec.getStringList("refund_token_on_end_types").map { it.uppercase() }
            )
            out[questId.lowercase()] = entry
        }
        return out
    }

    private fun parsePoolGroups(sec: ConfigurationSection?): Map<String, QuestPoolGroupEntry> {
        if (sec == null) return emptyMap()
        val out = linkedMapOf<String, QuestPoolGroupEntry>()
        sec.getKeys(false).forEach { groupId ->
            val gsec = sec.getConfigurationSection(groupId) ?: return@forEach
            val conds = parseProcessConditions(gsec)
            val entry = QuestPoolGroupEntry(
                groupId = groupId,
                processConditions = conds,
                preResetTokens = gsec.getBoolean("pre_reset_token", false),
                preStop = gsec.getBoolean("pre_stop", false),
                preResetHistory = gsec.getBoolean("pre_reset_history", false),
                selectedResetTokens = gsec.getBoolean("selected_reset_tokens", false),
                selectedStop = gsec.getBoolean("selected_stop", false),
                selectedResetHistory = gsec.getBoolean("selected_reset_history", false),
                amount = gsec.getInt("amount", 1),
                minTokens = gsec.getInt("min_tokens", 1),
                maxTokens = gsec.getInt("max_tokens", 1),
                refundTokenOnEndTypes = gsec.getStringList("refund_token_on_end_types").map { it.uppercase() }
            )
            out[groupId.lowercase()] = entry
        }
        return out
    }

    private fun parseRewards(raw: Any?, sec: ConfigurationSection?): List<QuestPoolReward> {
        val rewards = mutableListOf<QuestPoolReward>()
        val list = when (raw) {
            is List<*> -> raw
            else -> sec?.getKeys(false)?.mapNotNull { sec.getConfigurationSection(it) } ?: emptyList()
        }
        list.forEachIndexed { idx, entry ->
            val rewardSec = when (entry) {
                is ConfigurationSection -> entry
                is Map<*, *> -> mapToSection(entry)
                else -> null
            } ?: return@forEachIndexed
            val objectSec = rewardSec.getConfigurationSection("object") ?: rewardSec
            val node = parseRewardNode(objectSec, "reward_$idx")
            val obj = if (node == null) parseRewardObject(objectSec) else null
            if (node == null && obj == null) return@forEachIndexed
            val minStreak = rewardSec.getInt("min_streak", 1)
            val maxStreak = rewardSec.getInt("max_streak", Int.MAX_VALUE)
            rewards.add(QuestPoolReward(reward = obj, node = node, minStreak = minStreak, maxStreak = maxStreak))
        }
        return rewards
    }

    private fun parseRewardNode(sec: ConfigurationSection, id: String): QuestObjectNode? {
        val typeRaw = sec.getString("type") ?: return null
        val nodeType = runCatching { QuestObjectNodeType.valueOf(typeRaw.uppercase()) }.getOrNull() ?: return null
        if (!nodeType.name.startsWith("SERVER_")) return null
        return QuestContentLoader.parseObjectNode(id, sec)
    }

    private fun parseRewardObject(sec: ConfigurationSection?): QuestEndObject? {
        if (sec == null) return null
        val typeRaw = sec.getString("type") ?: return null
        val type = runCatching { QuestEndObjectType.valueOf(typeRaw.uppercase()) }.getOrNull() ?: return null
        val actions = sec.getStringList("actions")
        val commands = sec.getStringList("commands")
        val titleSec = sec.getConfigurationSection("title")
        val title = titleSec?.let {
            TitleSettings(
                fadeIn = it.getInt("fade_in", 10),
                stay = it.getInt("stay", 60),
                fadeOut = it.getInt("fade_out", 10),
                title = it.getString("title"),
                subtitle = it.getString("subtitle")
            )
        }
        return QuestEndObject(
            type = type,
            actions = actions,
            commands = commands,
            currency = sec.getString("currency"),
            valueFormula = sec.getString("value_formula"),
            sound = sec.getString("sound"),
            title = title
        )
    }

    private fun parseTimeFrames(raw: List<*>?, poolId: String): List<QuestPoolTimeFrame> {
        if (raw.isNullOrEmpty()) {
            return listOf(QuestPoolTimeFrame(id = "frame_0", type = QuestPoolTimeFrameType.NONE))
        }
        val frames = mutableListOf<QuestPoolTimeFrame>()
        raw.forEachIndexed { idx, entry ->
            val frame = when (entry) {
                is ConfigurationSection -> parseTimeFrameSection(entry, idx)
                is Map<*, *> -> parseTimeFrameMap(entry, idx)
                else -> null
            }
            if (frame != null) frames.add(frame)
        }
        return if (frames.isEmpty()) listOf(QuestPoolTimeFrame(id = "frame_0", type = QuestPoolTimeFrameType.NONE)) else frames
    }

    private fun parseTimeFrameSection(sec: ConfigurationSection, idx: Int): QuestPoolTimeFrame? {
        val typeRaw = sec.getString("type") ?: return null
        val type = runCatching { QuestPoolTimeFrameType.valueOf(typeRaw.uppercase()) }.getOrNull() ?: return null
        val start = sec.getConfigurationSection("start")?.let { parseTimePointSection(it) }
        val end = sec.getConfigurationSection("end")?.let { parseTimePointSection(it) }
        val duration = sec.getString("duration")?.let { parseDurationSeconds(it) }
        return QuestPoolTimeFrame(id = "frame_$idx", type = type, start = start, end = end, durationSeconds = duration)
    }

    private fun parseTimeFrameMap(map: Map<*, *>, idx: Int): QuestPoolTimeFrame? {
        val typeRaw = map["type"]?.toString() ?: return null
        val type = runCatching { QuestPoolTimeFrameType.valueOf(typeRaw.uppercase()) }.getOrNull() ?: return null
        val start = (map["start"] as? Map<*, *>)?.let { parseTimePointMap(it) }
        val end = (map["end"] as? Map<*, *>)?.let { parseTimePointMap(it) }
        val duration = map["duration"]?.toString()?.let { parseDurationSeconds(it) }
        return QuestPoolTimeFrame(id = "frame_$idx", type = type, start = start, end = end, durationSeconds = duration)
    }

    private fun parseTimePointSection(sec: ConfigurationSection): QuestPoolTimePoint {
        return QuestPoolTimePoint(
            month = sec.getString("month")?.let { runCatching { Month.valueOf(it.uppercase()) }.getOrNull() },
            dayOfMonth = sec.getInt("day_of_month").takeIf { it > 0 },
            dayOfWeek = sec.getString("day_of_week")?.let { runCatching { DayOfWeek.valueOf(it.uppercase()) }.getOrNull() },
            hour = sec.getInt("hour").takeIf { it >= 0 },
            minute = sec.getInt("minute").takeIf { it >= 0 }
        )
    }

    private fun parseTimePointMap(map: Map<*, *>): QuestPoolTimePoint {
        return QuestPoolTimePoint(
            month = map["month"]?.toString()?.let { runCatching { Month.valueOf(it.uppercase()) }.getOrNull() },
            dayOfMonth = map["day_of_month"]?.toString()?.toIntOrNull(),
            dayOfWeek = map["day_of_week"]?.toString()?.let { runCatching { DayOfWeek.valueOf(it.uppercase()) }.getOrNull() },
            hour = map["hour"]?.toString()?.toIntOrNull(),
            minute = map["minute"]?.toString()?.toIntOrNull()
        )
    }

    private fun parseProcessConditions(sec: ConfigurationSection): List<ConditionEntry> {
        val raw = sec.get("process_conditions")
        if (raw is List<*>) {
            return parseConditionContainer(raw, null)
        }
        val sub = sec.getConfigurationSection("process_conditions") ?: return emptyList()
        if (sub.getString("type") != null) {
            return listOfNotNull(parseCondition(sub))
        }
        return parseConditionContainer(sub.get("conditions"), sub.getConfigurationSection("conditions"))
    }

    private fun parseConditionContainer(raw: Any?, section: ConfigurationSection?): List<ConditionEntry> {
        val fromList = (raw as? List<*>)?.mapNotNull { parseCondition(it) } ?: emptyList()
        val fromSection = section?.getKeys(false)?.mapNotNull { key ->
            val csec = section.getConfigurationSection(key) ?: return@mapNotNull null
            parseCondition(csec)
        } ?: emptyList()
        return fromList + fromSection
    }

    private fun parseCondition(any: Any?): ConditionEntry? {
        val map = when (any) {
            is ConfigurationSection -> any.getValues(false)
            is Map<*, *> -> any
            else -> return null
        }
        val typeRaw = map["type"]?.toString()?.uppercase() ?: return null
        val type = runCatching { ConditionType.valueOf(typeRaw) }.getOrDefault(ConditionType.PERMISSION)
        val perm = map["permission"]?.toString()
        val amount = map["amount"]?.toString()?.toIntOrNull() ?: 1
        val itemType = map["item"]?.toString() ?: map["item_type"]?.toString()
        val itemAmount = map["item_amount"]?.toString()?.toIntOrNull() ?: amount
        val variable = map["variable"]?.toString()
        val compare = map["compare"]?.toString()
        val variableValue = map["value"]?.toString()?.toLongOrNull()
        return ConditionEntry(type, perm, amount, itemType, itemAmount, variable, compare, variableValue)
    }

    private fun parseDurationSeconds(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split("\\s+".toRegex())
        val num = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val unit = parts.getOrNull(1)?.lowercase() ?: "second"
        return when {
            unit.startsWith("sec") -> num
            unit.startsWith("min") -> num * 60
            unit.startsWith("hour") -> num * 3600
            else -> num
        }
    }

    private fun readStringListFlexible(raw: Any?): List<String> {
        return when (raw) {
            is String -> listOf(raw)
            is List<*> -> raw.mapNotNull { it?.toString() }
            else -> emptyList()
        }
    }

    private fun mapToSection(map: Map<*, *>): ConfigurationSection? {
        val cfg = YamlConfiguration()
        map.forEach { (k, v) ->
            k?.toString()?.let { key -> cfg.set(key, v) }
        }
        return cfg
    }
}

package net.nemoria.quest.content

import net.nemoria.quest.quest.*
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

object QuestContentLoader {
    fun loadAll(dir: File): List<QuestModel> {
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "CONTENT", "QuestContentLoader.kt:8", "loadAll entry", mapOf("dir" to dir.absolutePath, "exists" to dir.exists(), "isDirectory" to dir.isDirectory))
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "CONTENT", "QuestContentLoader.kt:10", "loadAll files found", mapOf("filesCount" to (files?.size ?: 0)))
        return files?.mapNotNull { loadQuest(it) } ?: emptyList()
    }

    private fun loadQuest(file: File): QuestModel? {
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "CONTENT", "QuestContentLoader.kt:15", "loadQuest entry", mapOf("file" to file.name))
        val cfg = try {
            YamlConfiguration.loadConfiguration(file)
        } catch (e: Exception) {
            net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "CONTENT", "QuestContentLoader.kt:16", "loadQuest YAML error", mapOf("file" to file.name, "error" to (e.message ?: "unknown")))
            return null
        }
        val id = cfg.getString("id")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val name = cfg.getString("name") ?: id
        val descriptionRaw = cfg.get("description")
        var description = cfg.getString("description")
        val descriptionPlaceholder = cfg.getString("description_placeholder")
        val informationMessage = cfg.getString("information_message")
        val displayName = cfg.getString("display_name")
        val descriptionLinesRaw = cfg.get("description_lines")
        var descriptionLines = cfg.getStringList("description_lines")
        if (descriptionLines.isEmpty()) {
            descriptionLines = readStringListFlexibleKeepEmpty(descriptionLinesRaw)
        }
        when (descriptionRaw) {
            is List<*> -> {
                val lines = descriptionRaw.map { it?.toString() ?: "" }
                if (description.isNullOrBlank()) description = lines.firstOrNull { it.isNotBlank() }
                if (descriptionLines.isEmpty()) descriptionLines = lines
            }
            is String -> {
                if (descriptionLines.isEmpty()) {
                    val lines = descriptionRaw.lines()
                    if (lines.size > 1) descriptionLines = lines
                }
                if (description.isNullOrBlank()) description = descriptionRaw.lines().firstOrNull { it.isNotBlank() }
            }
        }
        val displayPriority = if (cfg.contains("display_priority")) cfg.getInt("display_priority") else null
        val permissionStartRestriction = cfg.getString("permission_start_restriction")
        val permissionStartCommandRestriction = cfg.getString("permission_start_command_restriction")
        val worldRestriction = cfg.getConfigurationSection("world_restriction")?.let { sec ->
            val whitelist = sec.getString("whitelist")?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val blacklist = sec.getString("blacklist")?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            WorldRestriction(whitelist, blacklist)
        }
        val commandRestriction = cfg.getConfigurationSection("command_restriction")?.let { sec ->
            val whitelist = sec.getString("whitelist")?.lines()?.map { it.trimStart('/') }?.filter { it.isNotBlank() } ?: emptyList()
            val blacklist = sec.getString("blacklist")?.lines()?.map { it.trimStart('/') }?.filter { it.isNotBlank() } ?: emptyList()
            CommandRestriction(whitelist, blacklist)
        }
        val saving = cfg.getString("saving")?.let { runCatching { SavingMode.valueOf(it.uppercase()) }.getOrDefault(SavingMode.ENABLED) } ?: SavingMode.ENABLED
        val timeLimit = cfg.getConfigurationSection("time_limit")?.let { sec ->
            val dur = parseDurationSeconds(sec.getString("duration")) ?: return@let null
            val failGoto = sec.getString("fail_goto")
            val reminderSec = sec.getConfigurationSection("reminder")
            val reminder = parseNotify(reminderSec)
            val reminderInterval = reminderSec?.getString("interval")?.let { parseDurationSeconds(it) }
            TimeLimit(durationSeconds = dur, failGoto = failGoto, reminder = reminder, reminderIntervalSeconds = reminderInterval)
        }
        val cooldown = cfg.getConfigurationSection("cooldown")?.let { csec ->
            val dur = parseDurationSeconds(csec.getString("duration")) ?: return@let null
            val endTypes = csec.getStringList("end_types").takeIf { it.isNotEmpty() } ?: listOf("SUCCESS")
            CooldownSettings(durationSeconds = dur, endTypes = endTypes.map { it.uppercase() })
        }
        val modelVarsSec = cfg.getConfigurationSection("model_variables")
        val variables = modelVarsSec?.getKeys(false)?.associateWith { key ->
            modelVarsSec.getString(key, "") ?: ""
        }?.toMutableMap() ?: mutableMapOf()
        val concurrency = cfg.getConfigurationSection("concurrency")?.let {
            Concurrency(
                maxInstances = it.getInt("max_instances", -1),
                queueOnLimit = it.getBoolean("queue_on_limit", false)
            )
        } ?: Concurrency()
        val players = cfg.getConfigurationSection("players")?.let {
            PlayerSettings(
                min = it.getInt("min", 1),
                max = it.getInt("max", 1),
                allowLeaderStop = it.getBoolean("allow_leader_stop", true)
            )
        } ?: PlayerSettings()
        val startSec = cfg.getConfigurationSection("start_conditions")
            ?: cfg.getConfigurationSection("conditions_start_restriction")
        val startConditions = startSec?.let { sec ->
            val match = sec.getInt("match_amount", 0)
            val noMatch = sec.getInt("no_match_amount", 0)
            val conds = parseConditionContainer(sec.get("conditions"), sec.getConfigurationSection("conditions"))
            StartConditions(match, noMatch, conds)
        }
        val completion = cfg.getConfigurationSection("completion")?.let { compSec ->
            val maxComp = compSec.getInt("max_completions", 1)
            val notify = compSec.getConfigurationSection("notify")?.getKeys(false)
                ?.associateNotNull { key ->
                    parseNotify(compSec.getConfigurationSection("notify.$key"))?.let { key to it }
                } ?: emptyMap()
            CompletionSettings(maxCompletions = maxComp, notify = notify)
        } ?: CompletionSettings()
        val activators = cfg.getStringList("activators")
        val activatorsDialog = when (val raw = cfg.get("activators_dialog")) {
            null -> emptyList()
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.lines().map { it.trim() }
            else -> emptyList()
        }
        val activatorsDialogAutoStartDistance =
            if (cfg.contains("activators_dialog_auto_start_distance")) cfg.getDouble("activators_dialog_auto_start_distance") else null
        val activatorsDialogResetSec = cfg.getConfigurationSection("activators_dialog_reset")
        val activatorsDialogResetDelaySeconds =
            activatorsDialogResetSec?.getString("reset_delay")?.let { parseDurationSeconds(it) }
        val activatorsDialogResetDistance =
            if (activatorsDialogResetSec != null && activatorsDialogResetSec.contains("reset_distance")) activatorsDialogResetSec.getDouble("reset_distance") else null
        val activatorsDialogResetNotify = parseNotify(activatorsDialogResetSec?.getConfigurationSection("reset_notify"))
        val progressNotify = cfg.getConfigurationSection("progress_notify")?.let {
            val actionbar = it.getString("actionbar")
            val durationRaw = it.getString("actionbar_duration")
            val durationSeconds = parseDurationSeconds(durationRaw)
            val scoreboard = it.getBoolean("scoreboard", false)
            val title = it.getConfigurationSection("title")?.let { t ->
                TitleSettings(
                    fadeIn = t.getInt("fade_in", 10),
                    stay = t.getInt("stay", 60),
                    fadeOut = t.getInt("fade_out", 10),
                    title = t.getString("title"),
                    subtitle = t.getString("subtitle")
                )
            }
            ProgressNotify(actionbar, durationSeconds, scoreboard, title)
        }
        val statusItems = cfg.getConfigurationSection("status_items")?.getKeys(false)?.mapNotNull { key ->
            val state = runCatching { QuestStatusItemState.valueOf(key.uppercase()) }.getOrNull() ?: return@mapNotNull null
            val section = cfg.getConfigurationSection("status_items.$key") ?: return@mapNotNull null
            val type = section.getString("type") ?: return@mapNotNull null
            val nameItem = section.getString("name")
            val lore = readStringListFlexibleKeepEmpty(section.get("lore"))
            val cmd = if (section.contains("custom_model_data")) section.getInt("custom_model_data") else null
            state to StatusItemTemplate(type = type, name = nameItem, lore = lore, customModelData = cmd)
        }?.toMap() ?: emptyMap()
        val defaultStatusItem = cfg.getConfigurationSection("all_status_item")?.let { section ->
            val type = section.getString("type") ?: return@let null
            val nameItem = section.getString("name")
            val lore = readStringListFlexibleKeepEmpty(section.get("lore"))
            val cmd = if (section.contains("custom_model_data")) section.getInt("custom_model_data") else null
            StatusItemTemplate(type = type, name = nameItem, lore = lore, customModelData = cmd)
        }
        val requirements = cfg.getStringList("requirements")
        val objectives = cfg.getList("objectives")?.mapIndexedNotNull { index, any ->
            when (any) {
                is String -> QuestObjective(id = "obj_${index + 1}", description = any)
                is Map<*, *> -> {
                    val objId = any["id"]?.toString() ?: "obj_${index + 1}"
                    val desc = any["description"]?.toString()
                    val typeRaw = any["type"]?.toString()?.uppercase()
                    val type = runCatching { QuestObjectiveType.valueOf(typeRaw ?: "MANUAL") }.getOrDefault(QuestObjectiveType.MANUAL)
                    val duration = any["duration"]?.toString()?.toLongOrNull()
                    val count = any["count"]?.toString()?.toIntOrNull() ?: 1
                    val entityType = any["entity"]?.toString()
                    val material = any["material"]?.toString()
                    val world = any["world"]?.toString()
                    val x = any["x"]?.toString()?.toDoubleOrNull()
                    val y = any["y"]?.toString()?.toDoubleOrNull()
                    val z = any["z"]?.toString()?.toDoubleOrNull()
                    val radius = any["radius"]?.toString()?.toDoubleOrNull()
                    QuestObjective(objId, desc, type, duration, count, entityType, material, world, x, y, z, radius)
                }
                else -> null
            }
        } ?: emptyList()
        val rewards = cfg.getConfigurationSection("rewards")?.let { sec ->
            val commands = sec.getStringList("commands")
            val pointsSec = sec.getConfigurationSection("points")
            val points = pointsSec?.getKeys(false)?.associateWith { key ->
                pointsSec.getInt(key, 0)
            } ?: emptyMap()
            val rewardVarsSec = sec.getConfigurationSection("variables")
            val rewardVariables = rewardVarsSec?.getKeys(false)?.associateWith { varKey ->
                rewardVarsSec.getString(varKey, "") ?: ""
            } ?: emptyMap()
            QuestRewards(commands, points, rewardVariables)
        } ?: QuestRewards()
        val branches = cfg.getConfigurationSection("branches")?.getKeys(false)?.associateNotNull { key ->
            val branchSec = cfg.getConfigurationSection("branches.$key") ?: return@associateNotNull null
            val startsAt = branchSec.getString("starts_at")
            val objectsSec = branchSec.getConfigurationSection("objects")
            val objs = objectsSec?.getKeys(false)
                ?.associateNotNull { objId ->
                    parseObjectNode(objId, objectsSec.getConfigurationSection(objId))?.let { objId to it }
                } ?: emptyMap()
            key to Branch(startsAt, objs)
        } ?: emptyMap()
        val mainBranch = cfg.getString("main_branch")
        val endObjects = cfg.getConfigurationSection("end_objects")?.getKeys(false)?.associateWith { key ->
            val raw = cfg.get("end_objects.$key")
            when (raw) {
                is List<*> -> raw.mapNotNull { elem -> parseEndObjectAny(elem) }
                else -> {
                    val listSec = cfg.getConfigurationSection("end_objects.$key") ?: return@associateWith emptyList<QuestEndObject>()
                    listSec.getKeys(false).mapNotNull { idx ->
                        parseEndObject(listSec.getConfigurationSection(idx))
                    }
                }
            }
        } ?: emptyMap()
        val questModel = QuestModel(
            id = id,
            name = name,
            description = description,
            descriptionPlaceholder = descriptionPlaceholder,
            informationMessage = informationMessage,
            displayName = displayName,
            descriptionLines = descriptionLines,
            timeLimit = timeLimit,
            variables = variables,
            saving = saving,
            concurrency = concurrency,
            players = players,
            startConditions = startConditions,
            completion = completion,
            activators = activators,
            activatorsDialog = activatorsDialog,
            activatorsDialogAutoStartDistance = activatorsDialogAutoStartDistance,
            activatorsDialogResetDelaySeconds = activatorsDialogResetDelaySeconds,
            activatorsDialogResetDistance = activatorsDialogResetDistance,
            activatorsDialogResetNotify = activatorsDialogResetNotify,
            progressNotify = progressNotify,
            statusItems = statusItems,
            defaultStatusItem = defaultStatusItem,
            requirements = requirements,
            objectives = objectives,
            rewards = rewards,
            branches = branches,
            mainBranch = mainBranch,
            endObjects = endObjects,
            displayPriority = displayPriority,
            permissionStartRestriction = permissionStartRestriction,
            permissionStartCommandRestriction = permissionStartCommandRestriction,
            worldRestriction = worldRestriction,
            commandRestriction = commandRestriction,
            cooldown = cooldown
        )
        net.nemoria.quest.core.DebugLog.logToFile("debug-session", "run1", "CONTENT", "QuestContentLoader.kt:196", "loadQuest success", mapOf("questId" to id, "objectivesCount" to objectives.size, "branchesCount" to branches.size))
        return questModel
    }

    private fun parseDurationSeconds(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split("\\s+".toRegex())
        val num = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val unit = parts.getOrNull(1)?.lowercase() ?: "second"
        val seconds = when {
            unit.startsWith("sec") -> num
            unit.startsWith("min") -> num * 60
            unit.startsWith("hour") -> num * 3600
            else -> num
        }
        return seconds
    }

    private fun parseNotify(sec: org.bukkit.configuration.ConfigurationSection?): NotifySettings? {
        if (sec == null) return null
        val rawMsg = sec.get("message")
        val messages = when (rawMsg) {
            is String -> listOf(rawMsg)
            is List<*> -> sec.getStringList("message")
            else -> emptyList()
        }
        return NotifySettings(
            message = messages,
            sound = sec.getString("sound")
        )
    }

    internal fun parseObjectNode(id: String, sec: org.bukkit.configuration.ConfigurationSection?): QuestObjectNode? {
        if (sec == null) return null
        val typeRaw = sec.getString("type")?.uppercase() ?: "SERVER_ACTIONS"
        val type = runCatching { QuestObjectNodeType.valueOf(typeRaw) }.getOrDefault(QuestObjectNodeType.SERVER_ACTIONS)
        val isPlayerBlockType = when (type) {
            QuestObjectNodeType.PLAYER_BLOCKS_BREAK,
            QuestObjectNodeType.PLAYER_BLOCKS_PLACE,
            QuestObjectNodeType.PLAYER_BLOCKS_INTERACT,
            QuestObjectNodeType.PLAYER_BLOCKS_IGNITE,
            QuestObjectNodeType.PLAYER_BLOCKS_STRIP,
            QuestObjectNodeType.PLAYER_BLOCK_FARM,
            QuestObjectNodeType.PLAYER_BLOCK_FROST_WALK,
            QuestObjectNodeType.PLAYER_MAKE_PATHS,
            QuestObjectNodeType.PLAYER_SPAWNER_PLACE,
            QuestObjectNodeType.PLAYER_TREE_GROW -> true
            else -> false
        }
        val desc = sec.getString("objective_detail") ?: sec.getString("description")
        val actions = sec.getStringList("actions")
        val msgRaw = sec.get("messages") ?: sec.get("message")
        val message = readStringListFlexible(msgRaw)
        val goto = sec.getString("goto")
        val gotos = readStringListFlexible(sec.get("gotos"))
        val randomGotos = readStringListFlexible(sec.get("random_gotos"))
        val logic = sec.getString("logic")
        val items = sec.getConfigurationSection("items")?.let { itemsSec ->
            itemsSec.getKeys(false).mapNotNull { key ->
                val itemSec = itemsSec.getConfigurationSection(key)?.getConfigurationSection("item") ?: return@mapNotNull null
                val typeStr = itemSec.getString("type") ?: return@mapNotNull null
                val amt = itemSec.getInt("amount", 1)
                QuestItemEntry(typeStr, amt)
            }
        } ?: sec.getList("items")?.mapNotNull { any ->
            when (any) {
                is String -> QuestItemEntry(any, 1)
                is Map<*, *> -> {
                    val typeStr = any["type"]?.toString() ?: return@mapNotNull null
                    val amt = any["amount"]?.toString()?.toIntOrNull() ?: 1
                    QuestItemEntry(typeStr, amt)
                }
                else -> null
            }
        } ?: emptyList()
        val count = when {
            sec.isInt("count") -> sec.getInt("count")
            sec.isInt("goal") -> sec.getInt("goal")
            else -> 1
        }
        val variable = sec.getString("variable")
        val questId = sec.getString("quest") ?: sec.getString("quest_id")
        val valueFormula = sec.getString("value_formula")
        val sound = sec.getString("sound")
        val title = sec.getConfigurationSection("title")?.let { t ->
            TitleSettings(
                fadeIn = t.getInt("fade_in", 10),
                stay = t.getInt("stay", 60),
                fadeOut = t.getInt("fade_out", 10),
                title = t.getString("title"),
                subtitle = t.getString("subtitle")
            )
        }
        val startNotify = sec.getConfigurationSection("start_notify")?.let { n ->
            val rawMsg = n.get("message")
            val messages = when (rawMsg) {
                is String -> listOf(rawMsg)
                is List<*> -> n.getStringList("message")
                else -> emptyList()
            }
            NotifySettings(
                message = messages,
                sound = n.getString("sound")
            )
        }
        val hideChat = sec.getBoolean("hide_chat", false)
        val dialogMode = false
        val npcId = sec.getInt("npc", -1).let { if (it >= 0) it else null }
        val npcNames = readStringListFlexible(sec.get("npc_names"))
        val clickTypes = sec.getStringList("click_types")
        val resetSec = sec.getConfigurationSection("reset")
        val resetDelayTicks = resetSec?.getString("reset_delay")?.let { parseDurationSeconds(it) }?.times(20)
        val resetDistance = resetSec?.getDouble("reset_distance", Double.NaN).let { if (it == null || it.isNaN()) null else it }
        val resetNotify = parseNotify(resetSec?.getConfigurationSection("reset_notify"))
        val resetGoto = resetSec?.getString("reset_goto")
        val waitForCompletion = sec.getBoolean("wait_for_completion", false)
        val waitForPlayerRadius = sec.getDouble("wait_for_player_radius", Double.NaN).let { if (it.isNaN()) null else it }
        val waitForPlayerNotify = parseNotify(sec.getConfigurationSection("wait_for_player_notify"))
        val waitForPlayerNotifyDelayTicks = sec.getString("wait_for_player_notify_delay")?.let { parseDurationSeconds(it) }?.times(20)
        val choices = run {
            val choicesSec = sec.getConfigurationSection("choices")
            if (choicesSec != null) {
                choicesSec.getKeys(false).mapNotNull { key ->
                    val choiceSec = choicesSec.getConfigurationSection(key) ?: return@mapNotNull null
                    val text = choiceSec.getString("text") ?: return@mapNotNull null
                    val redo = choiceSec.getString("redo_text")
                    val gotoChoice = choiceSec.getString("goto")
                    DivergeChoice(text = text, redoText = redo, goto = gotoChoice)
                }
            } else {
                val raw = sec.getList("choices") ?: return@run emptyList()
                raw.mapNotNull { any ->
                    when (any) {
                        is Map<*, *> -> {
                            val text = any["text"]?.toString() ?: return@mapNotNull null
                            val redo = any["redo_text"]?.toString()
                            val gotoChoice = any["goto"]?.toString()
                            DivergeChoice(text = text, redoText = redo, goto = gotoChoice)
                        }
                        else -> null
                    }
                }
            }
        }
        val cases = sec.getConfigurationSection("cases")?.getKeys(false)?.mapNotNull { key ->
            val caseSec = sec.getConfigurationSection("cases.$key") ?: return@mapNotNull null
            val matchAmt = caseSec.getInt("match_amount", 1)
            val noMatchAmt = caseSec.getInt("no_match_amount", 0)
            val conds = parseConditionContainer(caseSec.get("conditions"), caseSec.getConfigurationSection("conditions"))
            val gotoCase = caseSec.getString("goto")
            SwitchCase(matchAmount = matchAmt, noMatchAmount = noMatchAmt, conditions = conds, goto = gotoCase)
        } ?: emptyList()
        val isPlayerEntityType = type.isPlayerEntityNode()
        val isPlayerItemType = type.isPlayerItemNode() || type == QuestObjectNodeType.PLAYER_CITIZENS_NPC_DELIVER_ITEMS

        val goalsRaw = sec.get("goals")
        val goalEntries = readGoalEntries(goalsRaw)

        val blockGoals = if (isPlayerBlockType) {
            goalEntries.mapNotNull { (goalId, any) -> parseBlockGoal(goalId, any) }
        } else emptyList()

        val itemGoals = if (isPlayerItemType) {
            goalEntries.mapNotNull { (goalId, any) -> parseItemGoal(goalId, any) }
        } else emptyList()

        val goals = if (!isPlayerEntityType) emptyList() else goalEntries.mapNotNull { (goalId, any) -> parseEntityGoal(goalId, any) }
        val position = parsePosition(sec.getConfigurationSection("position"))
        val teleportPosition = parsePosition(sec.getConfigurationSection("teleport_position"))
        val damage = sec.getDouble("damage", Double.NaN).let { if (it.isNaN()) null else it }
        val linkToQuest = sec.getBoolean("link_to_quest", false)
        val modifyOptions = parseModifyOptions(sec)
        val blockType = sec.getString("block_type")
        val blockStates = sec.getStringList("block_states")
        val explosionPower = sec.getDouble("power", Double.NaN).let { if (it.isNaN()) null else it }
        val effects = sec.getStringList("effects")
        val countOverride = sec.getInt("count", Int.MIN_VALUE).let { if (sec.isInt("count")) it else null }
        val allowDamage = sec.getBoolean("damage", true)
        val currency = sec.getString("currency")
        val pointsCategory = sec.getString("category")
        val achievementType = sec.getString("achievement_type")
        val cameraToggle = if (sec.isBoolean("toggle")) sec.getBoolean("toggle") else null
        val commandsAsPlayer = sec.getBoolean("as_player", false)
        val groupObjects = sec.getStringList("objects")
        val groupOrdered = sec.getBoolean("ordered_objects", false)
        val groupRequired = sec.getInt("required_objects", Int.MIN_VALUE).let { if (sec.isInt("required_objects")) it else null }
        val divergeChoices = sec.getConfigurationSection("choices")?.getKeys(false)?.mapNotNull { key ->
            val csec = sec.getConfigurationSection("choices.$key") ?: return@mapNotNull null
            val slot = csec.getInt("slot", 0)
            val maxComp = csec.getInt("max_completions", 1)
            val conds = parseConditionContainer(csec.get("conditions"), csec.getConfigurationSection("conditions"))
            val gotoChoice = csec.getString("goto")
            val objRef = csec.getString("object")
            DivergeChoiceGui(
                id = key,
                slot = slot,
                item = parseItemStackConfig(csec.getConfigurationSection("item")),
                redoItem = parseItemStackConfig(csec.getConfigurationSection("redo_item")),
                unavailableItem = parseItemStackConfig(csec.getConfigurationSection("unavailable_item")),
                maxCompletions = maxComp,
                conditions = conds,
                goto = gotoChoice,
                objRef = objRef
            )
        } ?: emptyList()
        val divergeDelay = parseDurationSeconds(sec.getString("reopen_delay"))?.let { it * 20 }
        val avoidRepeat = sec.getStringList("avoid_repeat_end_types")
        val blockAllowPlayerBlocks = sec.getBoolean("allow_player_blocks", true)
        val blockAllowSameBlocks = sec.getBoolean("allow_same_blocks", true)
        val blockAllowPlayerBlocksMsg = sec.getString("allow_player_blocks_error_message")
        val blockAllowSameBlocksMsg = sec.getString("allow_same_blocks_error_message")
        val blockClickType = sec.getString("block_click_type") ?: sec.getString("click_type")
        val blockSpawnTypes = sec.getStringList("spawn_types")
        val blockTreeType = sec.getString("tree_type")
        val itemInventoryTypes = sec.getStringList("inventory_types")
        val itemInventorySlots = sec.getIntegerList("inventory_slots")
        val itemClickType = sec.getString("item_click_type") ?: sec.getString("click_type")
        val tradeAllowSameVillagers = sec.getBoolean("allow_same_villagers", true)
        val tradeAllowSameVillagersMsg = sec.getString("allow_same_villagers_error_message")
        val itemsRequired = parseItemsList(sec.get("items"))
        val entityAllowSame = sec.getBoolean("allow_same_entities", true)
        val entityAllowSameMsg = sec.getString("allow_same_entities_error_message")
        val entityMaxDistance = sec.getDouble("max_distance", Double.NaN).let { if (it.isNaN()) null else it }
        val distanceGoalNumber = if (sec.isDouble("goal")) sec.getDouble("goal") else null
        val distanceGoalDuration = parseDurationSeconds(sec.getString("goal"))?.toDouble()
        val distanceGoal = distanceGoalNumber ?: distanceGoalDuration
        val positionDisplayDistance = sec.getBoolean("display_distance", false)
        val vehicleType = sec.getString("vehicle_type")
        val bucketType = sec.getString("bucket_type")
        val regainCausesRaw = sec.getStringList("regain_causes").ifEmpty { sec.getStringList("causes") }
        val damageCausesRaw = sec.getStringList("damage_causes").ifEmpty { sec.getStringList("causes") }
        val regainCauses = if (regainCausesRaw.any { it.equals("ANY", true) || it.equals("ALL", true) }) emptyList() else regainCausesRaw
        val damageCauses = if (damageCausesRaw.any { it.equals("ANY", true) || it.equals("ALL", true) }) emptyList() else damageCausesRaw
        val projectileTypes = sec.getStringList("projectile_types")
        val chatWhitelist = sec.getStringList("whitelist")
        val chatBlacklist = sec.getStringList("blacklist")
        val chatRegex = sec.getString("regex")
        val chatMinLength = sec.getInt("min_length", Int.MIN_VALUE).let { if (sec.isInt("min_length")) it else null }
        val chatMaxLength = sec.getInt("max_length", Int.MIN_VALUE).let { if (sec.isInt("max_length")) it else null }
        val chatErrorMessage = sec.getString("error_message")
        val chatStoreVariable = sec.getString("store_in_variable")
        val waitGoalSeconds = parseDurationSeconds(sec.getString("goal"))?.let { it }
        return QuestObjectNode(
            id = id,
            type = type,
            description = desc,
            actions = actions,
            message = message,
            goto = goto,
            gotos = gotos,
            logic = logic,
            randomGotos = if (randomGotos.isNotEmpty()) randomGotos else gotos,
            items = items,
            count = count,
            variable = variable,
            questId = questId,
            valueFormula = valueFormula,
            sound = sound,
            title = title,
            startNotify = startNotify,
            hideChat = hideChat,
            dialog = dialogMode,
            npcId = npcId,
            npcNames = npcNames,
            clickTypes = clickTypes,
            resetDelayTicks = resetDelayTicks,
            resetDistance = resetDistance,
            resetNotify = resetNotify,
            resetGoto = resetGoto,
            waitForCompletion = waitForCompletion,
            waitForPlayerRadius = waitForPlayerRadius,
            waitForPlayerNotify = waitForPlayerNotify,
            waitForPlayerNotifyDelayTicks = waitForPlayerNotifyDelayTicks,
            choices = choices,
            cases = cases,
            goals = goals,
            entityAllowSame = entityAllowSame,
            entityAllowSameMessage = entityAllowSameMsg,
            entityMaxDistance = entityMaxDistance,
            blockGoals = blockGoals,
            blockAllowPlayerBlocks = blockAllowPlayerBlocks,
            blockAllowSameBlocks = blockAllowSameBlocks,
            blockAllowPlayerBlocksMessage = blockAllowPlayerBlocksMsg,
            blockAllowSameBlocksMessage = blockAllowSameBlocksMsg,
            blockClickType = blockClickType,
            blockSpawnTypes = blockSpawnTypes,
            blockTreeType = blockTreeType,
            itemGoals = itemGoals,
            tradeAllowSameVillagers = tradeAllowSameVillagers,
            tradeAllowSameVillagersMessage = tradeAllowSameVillagersMsg,
            itemInventoryTypes = itemInventoryTypes,
            itemInventorySlots = itemInventorySlots,
            itemClickType = itemClickType,
            itemsRequired = itemsRequired,
            distanceGoal = distanceGoal,
            positionDisplayDistance = positionDisplayDistance,
            vehicleType = vehicleType,
            bucketType = bucketType,
            regainCauses = regainCauses,
            damageCauses = damageCauses,
            projectileTypes = projectileTypes,
            chatWhitelist = chatWhitelist,
            chatBlacklist = chatBlacklist,
            chatRegex = chatRegex,
            chatMinLength = chatMinLength,
            chatMaxLength = chatMaxLength,
            chatErrorMessage = chatErrorMessage,
            chatStoreVariable = chatStoreVariable,
            waitGoalSeconds = waitGoalSeconds,
            damage = damage,
            linkToQuest = linkToQuest,
            position = position,
            teleportPosition = teleportPosition,
            modifyOptions = modifyOptions,
            blockType = blockType,
            blockStates = blockStates,
            explosionPower = explosionPower,
            effectList = effects,
            countOverride = countOverride,
            allowDamage = allowDamage,
            currency = currency,
            pointsCategory = pointsCategory,
            achievementType = achievementType,
            cameraToggle = cameraToggle,
            commandsAsPlayer = commandsAsPlayer,
            groupObjects = groupObjects,
            groupOrdered = groupOrdered,
            groupRequired = groupRequired,
            divergeChoices = divergeChoices,
            divergeReopenDelayTicks = divergeDelay,
            avoidRepeatEndTypes = avoidRepeat
        )
    }

    private fun readStringListFlexible(raw: Any?): List<String> {
        return when (raw) {
            null -> emptyList()
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.lines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) null else trimmed
            }
            else -> emptyList()
        }
    }

    private fun readStringListFlexibleKeepEmpty(raw: Any?): List<String> {
        return when (raw) {
            null -> emptyList()
            is List<*> -> raw.map { it?.toString() ?: "" }
            is String -> raw.lines()
            else -> emptyList()
        }
    }

    private fun readGoalEntries(raw: Any?): List<Pair<String, Any?>> {
        return when (raw) {
            null -> emptyList()
            is org.bukkit.configuration.ConfigurationSection -> raw.getKeys(false).mapNotNull { key ->
                val child = raw.getConfigurationSection(key) ?: raw.get(key)
                child?.let { key to it }
            }
            is List<*> -> raw.mapIndexedNotNull { idx, elem ->
                val id = goalIdForIndex(idx)
                elem?.let { id to it }
            }
            else -> emptyList()
        }
    }

    private fun goalIdForIndex(idx: Int): String {
        var i = idx
        val sb = StringBuilder()
        do {
            val rem = i % 26
            sb.append(('a'.code + rem).toChar())
            i = (i / 26) - 1
        } while (i >= 0)
        return sb.reverse().toString()
    }

    private fun parseBlockGoal(id: String, any: Any?): BlockGoal? {
        return when (any) {
            is org.bukkit.configuration.ConfigurationSection -> {
                BlockGoal(
                    id = id,
                    types = any.getStringList("types"),
                    states = any.getStringList("states"),
                    statesRequiredCount = any.getInt("states_required_count", Int.MAX_VALUE),
                    goal = any.getDouble("goal", 1.0)
                )
            }
            is Map<*, *> -> {
                BlockGoal(
                    id = id,
                    types = readStringListAny(any["types"]),
                    states = readStringListAny(any["states"]),
                    statesRequiredCount = any["states_required_count"]?.toString()?.toIntOrNull() ?: Int.MAX_VALUE,
                    goal = any["goal"]?.toString()?.toDoubleOrNull() ?: 1.0
                )
            }
            else -> null
        }
    }

    private fun parseItemGoal(id: String, any: Any?): ItemGoal? {
        return when (any) {
            is org.bukkit.configuration.ConfigurationSection -> {
                val singleItem = any.getConfigurationSection("item")?.let { parseItemStackConfig(it) }
                val itemsList = parseItemsList(any.get("items"))
                val combined = when {
                    singleItem != null -> listOf(singleItem)
                    itemsList.isNotEmpty() -> itemsList
                    else -> emptyList()
                }
                ItemGoal(
                    id = id,
                    items = combined,
                    check = any.getString("check"),
                    goal = any.getDouble("goal", 1.0),
                    take = any.getBoolean("take", false)
                )
            }
            is Map<*, *> -> {
                val singleItem = parseItemStackConfigAny(any["item"])
                val itemsList = parseItemsList(any["items"])
                val combined = when {
                    singleItem != null -> listOf(singleItem)
                    itemsList.isNotEmpty() -> itemsList
                    else -> emptyList()
                }
                ItemGoal(
                    id = id,
                    items = combined,
                    check = any["check"]?.toString(),
                    goal = any["goal"]?.toString()?.toDoubleOrNull() ?: 1.0,
                    take = readBooleanAny(any["take"]) ?: false
                )
            }
            else -> null
        }
    }

    private fun parseEntityGoal(id: String, any: Any?): EntityGoal? {
        return when (any) {
            is org.bukkit.configuration.ConfigurationSection -> {
                EntityGoal(
                    types = any.getStringList("types"),
                    names = any.getStringList("names"),
                    colors = any.getStringList("colors"),
                    horseColors = any.getStringList("horse_colors"),
                    horseStyles = any.getStringList("horse_styles"),
                    goal = any.getDouble("goal", 1.0),
                    id = id
                )
            }
            is Map<*, *> -> {
                EntityGoal(
                    types = readStringListAny(any["types"]),
                    names = readStringListAny(any["names"]),
                    colors = readStringListAny(any["colors"]),
                    horseColors = readStringListAny(any["horse_colors"]),
                    horseStyles = readStringListAny(any["horse_styles"]),
                    goal = any["goal"]?.toString()?.toDoubleOrNull() ?: 1.0,
                    id = id
                )
            }
            else -> null
        }
    }

    private fun readStringListAny(raw: Any?): List<String> {
        return when (raw) {
            null -> emptyList()
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun readBooleanAny(raw: Any?): Boolean? {
        return when (raw) {
            null -> null
            is Boolean -> raw
            is String -> raw.trim().toBooleanStrictOrNull()
            is Number -> raw.toInt() != 0
            else -> null
        }
    }

    private fun parseItemStackConfigAny(raw: Any?): ItemStackConfig? {
        return when (raw) {
            null -> null
            is org.bukkit.configuration.ConfigurationSection -> parseItemStackConfig(raw)
            is Map<*, *> -> {
                val type = raw["type"]?.toString() ?: return null
                val name = raw["name"]?.toString()
                val lore = (raw["lore"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                val cmd = raw["custom_model_data"]?.toString()?.toIntOrNull()
                val potionType = raw["potion_type"]?.toString()
                ItemStackConfig(type = type, name = name, lore = lore, customModelData = cmd, potionType = potionType)
            }
            else -> null
        }
    }

    private fun parseConditionContainer(raw: Any?, section: org.bukkit.configuration.ConfigurationSection?): List<ConditionEntry> {
        val fromList = (raw as? List<*>)?.mapNotNull { parseCondition(it) } ?: emptyList()
        if (fromList.isNotEmpty()) return fromList
        val sec = section ?: return emptyList()
        return sec.getKeys(false).mapNotNull { key ->
            val obj = sec.get(key) ?: sec.getConfigurationSection(key)
            parseCondition(obj)
        }
    }

    private fun parsePosition(sec: org.bukkit.configuration.ConfigurationSection?): PositionTarget? {
        if (sec == null) return null
        fun readDouble(key: String): Double? {
            val raw = sec.get(key) ?: return null
            return when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
        }
        return PositionTarget(
            world = sec.getString("world"),
            x = readDouble("x"),
            y = readDouble("y"),
            z = readDouble("z"),
            radius = readDouble("radius") ?: 8.0
        )
    }

    private fun parseModifyOptions(sec: org.bukkit.configuration.ConfigurationSection): ItemModifyOptions? {
        val optsSec = sec
        if (!optsSec.contains("durability_set") &&
            !optsSec.contains("custom_model_data_set") &&
            !optsSec.contains("name_set") &&
            !optsSec.contains("name_remove") &&
            !optsSec.contains("lore_set") &&
            !optsSec.contains("lore_add") &&
            !optsSec.contains("lore_remove") &&
            !optsSec.contains("enchantments_add") &&
            !optsSec.contains("enchantments_remove") &&
            !optsSec.contains("quest_unlink")
        ) return null
        val enchAddSec = optsSec.getConfigurationSection("enchantments_add")
        val enchAdd = enchAddSec?.getKeys(false)?.associateWith { key ->
            enchAddSec.getInt(key, 1)
        } ?: emptyMap()
        val enchRemove = optsSec.getStringList("enchantments_remove")
        return ItemModifyOptions(
            questUnlink = optsSec.getBoolean("quest_unlink", false),
            durabilitySet = optsSec.getInt("durability_set", Int.MIN_VALUE).let { if (optsSec.isInt("durability_set")) it else null },
            customModelDataSet = optsSec.getInt("custom_model_data_set", Int.MIN_VALUE).let { if (optsSec.isInt("custom_model_data_set")) it else null },
            nameSet = optsSec.getString("name_set"),
            nameRemove = optsSec.getString("name_remove"),
            loreSet = optsSec.getStringList("lore_set"),
            loreAdd = optsSec.getStringList("lore_add"),
            loreRemove = optsSec.getStringList("lore_remove"),
            enchantmentsAdd = enchAdd,
            enchantmentsRemove = enchRemove
        )
    }

    private fun parseCondition(any: Any?): ConditionEntry? {
        val map: Map<*, *> = when (any) {
            is Map<*, *> -> any
            is org.bukkit.configuration.ConfigurationSection -> any.getValues(false)
            else -> return null
        }
        val type = map["type"]?.toString()?.uppercase() ?: return null
        val ctype = runCatching { ConditionType.valueOf(type) }.getOrDefault(ConditionType.PERMISSION)
        val perm = map["permission"]?.toString()
        val amount = map["amount"]?.toString()?.toIntOrNull() ?: 1
        val itemType = map["item"]?.toString() ?: map["material"]?.toString()
        val itemAmount = map["item_amount"]?.toString()?.toIntOrNull() ?: 1
        val variable = map["variable"]?.toString()
        val compare = map["compare"]?.toString()
        val variableValue = map["value"]?.toString()?.toLongOrNull()
        return ConditionEntry(ctype, perm, amount, itemType, itemAmount, variable, compare, variableValue)
    }

    private fun parseEndObject(sec: org.bukkit.configuration.ConfigurationSection?): QuestEndObject? {
        if (sec == null) return null
        val typeRaw = sec.getString("type")?.uppercase() ?: return null
        val type = runCatching { QuestEndObjectType.valueOf(typeRaw) }.getOrElse { return null }
        val actions = sec.getStringList("actions")
        val commands = sec.getStringList("commands")
        val currency = sec.getString("currency")
        val valueFormula = sec.getString("value_formula")
        val sound = sec.getString("sound")
        val title = sec.getConfigurationSection("title")?.let { t ->
            TitleSettings(
                fadeIn = t.getInt("fade_in", 10),
                stay = t.getInt("stay", 60),
                fadeOut = t.getInt("fade_out", 10),
                title = t.getString("title"),
                subtitle = t.getString("subtitle")
            )
        }
        return QuestEndObject(
            type = type,
            actions = actions,
            commands = commands,
            currency = currency,
            valueFormula = valueFormula,
            sound = sound,
            title = title
        )
    }

    private fun parseEndObjectAny(any: Any?): QuestEndObject? {
        return when (any) {
            is org.bukkit.configuration.ConfigurationSection -> parseEndObject(any)
            is Map<*, *> -> {
                val typeRaw = any["type"]?.toString()?.uppercase() ?: return null
                val type = runCatching { QuestEndObjectType.valueOf(typeRaw) }.getOrNull() ?: return null
                val actions = (any["actions"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                val commands = (any["commands"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                val currency = any["currency"]?.toString()
                val valueFormula = any["value_formula"]?.toString()
                val sound = any["sound"]?.toString()
                val title = (any["title"] as? Map<*, *>)?.let { t ->
                    TitleSettings(
                        fadeIn = t["fade_in"]?.toString()?.toIntOrNull() ?: 10,
                        stay = t["stay"]?.toString()?.toIntOrNull() ?: 60,
                        fadeOut = t["fade_out"]?.toString()?.toIntOrNull() ?: 10,
                        title = t["title"]?.toString(),
                        subtitle = t["subtitle"]?.toString()
                    )
                }
                QuestEndObject(
                    type = type,
                    actions = actions,
                    commands = commands,
                    currency = currency,
                    valueFormula = valueFormula,
                    sound = sound,
                    title = title
                )
            }
            else -> null
        }
    }

    private fun parseItemStackConfig(sec: org.bukkit.configuration.ConfigurationSection?): ItemStackConfig? {
        if (sec == null) return null
        val type = sec.getString("type") ?: return null
        return ItemStackConfig(
            type = type,
            name = sec.getString("name"),
            lore = sec.getStringList("lore"),
            customModelData = if (sec.isInt("custom_model_data")) sec.getInt("custom_model_data") else null,
            potionType = sec.getString("potion_type")
        )
    }

    private fun parseItemsList(raw: Any?): List<ItemStackConfig> {
        return when (raw) {
            null -> emptyList()
            is org.bukkit.configuration.ConfigurationSection -> raw.getKeys(false).mapNotNull { key ->
                parseItemStackConfig(raw.getConfigurationSection(key))
            }
            is List<*> -> raw.mapNotNull { elem ->
                when (elem) {
                    is String -> ItemStackConfig(type = elem)
                    is Map<*, *> -> {
                        val type = elem["type"]?.toString() ?: return@mapNotNull null
                        val name = elem["name"]?.toString()
                        val lore = (elem["lore"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                        val cmd = elem["custom_model_data"]?.toString()?.toIntOrNull()
                        ItemStackConfig(type = type, name = name, lore = lore, customModelData = cmd)
                    }
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    private inline fun <K, V> Iterable<K>.associateNotNull(transform: (K) -> Pair<K, V>?): Map<K, V> {
        val dest = LinkedHashMap<K, V>()
        for (item in this) {
            val pair = transform(item)
            if (pair != null) {
                dest[pair.first] = pair.second
            }
        }
        return dest
    }
}

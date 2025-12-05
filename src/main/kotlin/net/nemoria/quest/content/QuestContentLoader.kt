package net.nemoria.quest.content

import net.nemoria.quest.quest.*
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

object QuestContentLoader {
    fun loadAll(dir: File): List<QuestModel> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
            ?.mapNotNull { loadQuest(it) }
            ?: emptyList()
    }

    private fun loadQuest(file: File): QuestModel? {
        val cfg = YamlConfiguration.loadConfiguration(file)
        val id = cfg.getString("id")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val name = cfg.getString("name") ?: id
        val description = cfg.getString("description")
        val displayName = cfg.getString("display_name")
        val descriptionLines = cfg.getStringList("description_lines")
        val saving = cfg.getString("saving")?.let { runCatching { SavingMode.valueOf(it.uppercase()) }.getOrDefault(SavingMode.ENABLED) } ?: SavingMode.ENABLED
        val timeLimit = cfg.getConfigurationSection("time_limit")?.let { sec ->
            val dur = parseDurationSeconds(sec.getString("duration")) ?: return@let null
            val failGoto = sec.getString("fail_goto")
            TimeLimit(durationSeconds = dur, failGoto = failGoto)
        }
        val variables = cfg.getConfigurationSection("model_variables")?.getKeys(false)?.associateWith { key ->
            cfg.getConfigurationSection("model_variables")!!.getString(key, "") ?: ""
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
        val completion = cfg.getConfigurationSection("completion")?.let {
            CompletionSettings(maxCompletions = it.getInt("max_completions", 1))
        } ?: CompletionSettings()
        val activators = cfg.getStringList("activators")
        val progressNotify = cfg.getConfigurationSection("progress_notify")?.let {
            val actionbar = it.getString("actionbar")
            val durationRaw = it.getString("actionbar_duration")
            val durationSeconds = parseDurationSeconds(durationRaw)
            val scoreboard = it.getBoolean("scoreboard", false)
            ProgressNotify(actionbar, durationSeconds, scoreboard)
        }
        val statusItems = cfg.getConfigurationSection("status_items")?.getKeys(false)?.mapNotNull { key ->
            val state = runCatching { QuestStatusItemState.valueOf(key.uppercase()) }.getOrNull() ?: return@mapNotNull null
            val section = cfg.getConfigurationSection("status_items.$key") ?: return@mapNotNull null
            val type = section.getString("type") ?: return@mapNotNull null
            val nameItem = section.getString("name")
            val lore = section.getStringList("lore")
            val cmd = if (section.contains("custom_model_data")) section.getInt("custom_model_data") else null
            state to StatusItemTemplate(type = type, name = nameItem, lore = lore, customModelData = cmd)
        }?.toMap() ?: emptyMap()
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
            val points = sec.getConfigurationSection("points")?.getKeys(false)?.associateWith { key ->
                sec.getConfigurationSection("points")!!.getInt(key, 0)
            } ?: emptyMap()
            val variables = sec.getConfigurationSection("variables")?.getKeys(false)?.associateWith { key ->
                sec.getConfigurationSection("variables")!!.getString(key, "") ?: ""
            } ?: emptyMap()
            QuestRewards(commands, points, variables)
        } ?: QuestRewards()
        val branches = cfg.getConfigurationSection("branches")?.getKeys(false)?.associateWith { key ->
            val branchSec = cfg.getConfigurationSection("branches.$key") ?: return@associateWith null
            val startsAt = branchSec.getString("starts_at")
            val objectsSec = branchSec.getConfigurationSection("objects")
            val objs = objectsSec?.getKeys(false)?.associateWith { objId ->
                parseObjectNode(objId, objectsSec.getConfigurationSection(objId))
            }?.filterValues { it != null }?.mapValues { it.value!! } ?: emptyMap()
            Branch(startsAt, objs)
        }?.filterValues { it != null }?.mapValues { it.value!! } ?: emptyMap()
        val mainBranch = cfg.getString("main_branch")
        val endObjects = cfg.getConfigurationSection("end_objects")?.getKeys(false)?.associateWith { key ->
            val listSec = cfg.getConfigurationSection("end_objects.$key") ?: return@associateWith emptyList<QuestEndObject>()
            listSec.getKeys(false).mapNotNull { idx ->
                parseEndObject(listSec.getConfigurationSection(idx))
            }
        } ?: emptyMap()
        return QuestModel(
            id = id,
            name = name,
            description = description,
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
            progressNotify = progressNotify,
            statusItems = statusItems,
            requirements = requirements,
            objectives = objectives,
            rewards = rewards,
            branches = branches,
            mainBranch = mainBranch,
            endObjects = endObjects
        )
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

    private fun parseObjectNode(id: String, sec: org.bukkit.configuration.ConfigurationSection?): QuestObjectNode? {
        if (sec == null) return null
        val type = runCatching { QuestObjectNodeType.valueOf(sec.getString("type", "SERVER_ACTIONS")!!.uppercase()) }.getOrDefault(QuestObjectNodeType.SERVER_ACTIONS)
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
        val count = sec.getInt("count", 1)
        val variable = sec.getString("variable")
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
        val dialogMode = sec.getBoolean("dialog", false)
        val npcId = sec.getInt("npc", -1).let { if (it >= 0) it else null }
        val clickTypes = sec.getStringList("click_types")
        val choices = sec.getConfigurationSection("choices")?.getKeys(false)?.mapNotNull { key ->
            val choiceSec = sec.getConfigurationSection("choices.$key") ?: return@mapNotNull null
            val text = choiceSec.getString("text") ?: return@mapNotNull null
            val redo = choiceSec.getString("redo_text")
            val gotoChoice = choiceSec.getString("goto")
            DivergeChoice(text = text, redoText = redo, goto = gotoChoice)
        } ?: emptyList()
        val cases = sec.getConfigurationSection("cases")?.getKeys(false)?.mapNotNull { key ->
            val caseSec = sec.getConfigurationSection("cases.$key") ?: return@mapNotNull null
            val matchAmt = caseSec.getInt("match_amount", 1)
            val noMatchAmt = caseSec.getInt("no_match_amount", 0)
            val conds = parseConditionContainer(caseSec.get("conditions"), caseSec.getConfigurationSection("conditions"))
            val gotoCase = caseSec.getString("goto")
            SwitchCase(matchAmount = matchAmt, noMatchAmount = noMatchAmt, conditions = conds, goto = gotoCase)
        } ?: emptyList()
        val isPlayerEntityType = when (type) {
            QuestObjectNodeType.PLAYER_ENTITIES_BREED,
            QuestObjectNodeType.PLAYER_ENTITIES_INTERACT,
            QuestObjectNodeType.PLAYER_ENTITIES_CATCH,
            QuestObjectNodeType.PLAYER_ENTITIES_DAMAGE,
            QuestObjectNodeType.PLAYER_ENTITIES_DEATH_NEARBY,
            QuestObjectNodeType.PLAYER_ENTITIES_DISMOUNT,
            QuestObjectNodeType.PLAYER_ENTITIES_GET_DAMAGED,
            QuestObjectNodeType.PLAYER_ENTITIES_KILL,
            QuestObjectNodeType.PLAYER_ENTITIES_MOUNT,
            QuestObjectNodeType.PLAYER_ENTITIES_SHEAR,
            QuestObjectNodeType.PLAYER_ENTITIES_SPAWN,
            QuestObjectNodeType.PLAYER_ENTITIES_TAME -> true
            else -> false
        }
        val isPlayerItemType = when (type) {
            QuestObjectNodeType.PLAYER_ITEMS_ACQUIRE,
            QuestObjectNodeType.PLAYER_ITEMS_BREW,
            QuestObjectNodeType.PLAYER_ITEMS_CONSUME,
            QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_PUT,
            QuestObjectNodeType.PLAYER_ITEMS_CONTAINER_TAKE,
            QuestObjectNodeType.PLAYER_ITEMS_CRAFT,
            QuestObjectNodeType.PLAYER_ITEMS_DROP,
            QuestObjectNodeType.PLAYER_ITEMS_ENCHANT,
            QuestObjectNodeType.PLAYER_ITEMS_FISH,
            QuestObjectNodeType.PLAYER_ITEMS_INTERACT,
            QuestObjectNodeType.PLAYER_ITEMS_MELT,
            QuestObjectNodeType.PLAYER_ITEMS_PICKUP,
            QuestObjectNodeType.PLAYER_ITEMS_REPAIR,
            QuestObjectNodeType.PLAYER_ITEMS_REQUIRE,
            QuestObjectNodeType.PLAYER_ITEMS_THROW,
            QuestObjectNodeType.PLAYER_ITEMS_TRADE -> true
            else -> false
        }

        val blockGoals = if (isPlayerBlockType) {
            sec.getConfigurationSection("goals")?.getKeys(false)?.mapNotNull { key ->
                val g = sec.getConfigurationSection("goals.$key") ?: return@mapNotNull null
                BlockGoal(
                    id = key,
                    types = g.getStringList("types"),
                    states = g.getStringList("states"),
                    statesRequiredCount = g.getInt("states_required_count", Int.MAX_VALUE),
                    goal = g.getDouble("goal", 1.0)
                )
            } ?: emptyList()
        } else emptyList()
        val itemGoals = if (isPlayerItemType) {
            sec.getConfigurationSection("goals")?.getKeys(false)?.mapNotNull { key ->
                val g = sec.getConfigurationSection("goals.$key") ?: return@mapNotNull null
                val singleItem = g.getConfigurationSection("item")?.let { parseItemStackConfig(it) }
                val itemsList = parseItemsList(g.get("items"))
                val combined = when {
                    singleItem != null -> listOf(singleItem)
                    itemsList.isNotEmpty() -> itemsList
                    else -> emptyList()
                }
                ItemGoal(
                    id = key,
                    items = combined,
                    check = g.getString("check"),
                    goal = g.getDouble("goal", 1.0),
                    take = g.getBoolean("take", false)
                )
            } ?: emptyList()
        } else emptyList()

        val goals = if (!isPlayerEntityType) emptyList() else sec.getConfigurationSection("goals")?.getKeys(false)?.mapNotNull { key ->
            val g = sec.getConfigurationSection("goals.$key") ?: return@mapNotNull null
            EntityGoal(
                types = g.getStringList("types"),
                names = g.getStringList("names"),
                colors = g.getStringList("colors"),
                horseColors = g.getStringList("horse_colors"),
                horseStyles = g.getStringList("horse_styles"),
                goal = g.getDouble("goal", 1.0),
                id = key
            )
        } ?: emptyList()
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
        val distanceGoal = sec.getDouble("goal", Double.NaN).let { if (it.isNaN()) null else it }
        val positionDisplayDistance = sec.getBoolean("display_distance", false)
        val vehicleType = sec.getString("vehicle_type")
        val bucketType = sec.getString("bucket_type")
        val regainCauses = sec.getStringList("regain_causes").ifEmpty { sec.getStringList("causes") }
        val damageCauses = sec.getStringList("damage_causes").ifEmpty { sec.getStringList("causes") }
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
            goto = goto,
            gotos = gotos,
            logic = logic,
            randomGotos = if (randomGotos.isNotEmpty()) randomGotos else gotos,
            items = items,
            count = count,
            variable = variable,
            valueFormula = valueFormula,
            sound = sound,
            title = title,
            startNotify = startNotify,
            hideChat = hideChat,
            dialog = dialogMode,
            npcId = npcId,
            clickTypes = clickTypes,
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
        val enchAdd = optsSec.getConfigurationSection("enchantments_add")?.getKeys(false)?.associateWith { key ->
            optsSec.getConfigurationSection("enchantments_add")!!.getInt(key, 1)
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
        val type = runCatching { QuestEndObjectType.valueOf(sec.getString("type")!!.uppercase()) }.getOrElse { return null }
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

    private fun parseItemStackConfig(sec: org.bukkit.configuration.ConfigurationSection?): ItemStackConfig? {
        if (sec == null) return null
        val type = sec.getString("type") ?: return null
        return ItemStackConfig(
            type = type,
            name = sec.getString("name"),
            lore = sec.getStringList("lore"),
            customModelData = if (sec.isInt("custom_model_data")) sec.getInt("custom_model_data") else null
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

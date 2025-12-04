package net.nemoria.quest.quest

data class QuestModel(
    val id: String,
    val name: String = id,
    val description: String? = null,
    val displayName: String? = null,
    val descriptionLines: List<String> = emptyList(),
    val progressNotify: ProgressNotify? = null,
    val statusItems: Map<QuestStatusItemState, StatusItemTemplate> = emptyMap(),
    val requirements: List<String> = emptyList(),
    val objectives: List<QuestObjective> = emptyList(),
    val rewards: QuestRewards = QuestRewards(),
    val saving: SavingMode = SavingMode.ENABLED,
    val concurrency: Concurrency = Concurrency(),
    val players: PlayerSettings = PlayerSettings(),
    val startConditions: StartConditions? = null,
    val completion: CompletionSettings = CompletionSettings(),
    val activators: List<String> = emptyList(),
    val timeLimit: TimeLimit? = null,
    val variables: MutableMap<String, String> = mutableMapOf(),
    val branches: Map<String, Branch> = emptyMap(),
    val mainBranch: String? = null,
    val endObjects: Map<String, List<QuestEndObject>> = emptyMap()
)

data class QuestObjective(
    val id: String,
    val description: String? = null,
    val type: QuestObjectiveType = QuestObjectiveType.MANUAL,
    val durationSeconds: Long? = null,
    val count: Int = 1,
    val entityType: String? = null,
    val material: String? = null,
    val world: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val radius: Double? = null
)

enum class QuestObjectiveType {
    MANUAL,
    TIMER,
    KILL_MOB,
    COLLECT_ITEM,
    MOVE_TO
}

data class ProgressNotify(
    val actionbar: String? = null,
    val actionbarDurationSeconds: Long? = null,
    val scoreboard: Boolean = false
)

enum class QuestStatusItemState {
    AVAILABLE,
    PROGRESS,
    UNAVAILABLE,
    COMPLETED
}

data class StatusItemTemplate(
    val type: String,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val customModelData: Int? = null
)

data class QuestRewards(
    val commands: List<String> = emptyList(),
    val points: Map<String, Int> = emptyMap(),
    val variables: Map<String, String> = emptyMap()
)

data class TimeLimit(
    val durationSeconds: Long,
    val failGoto: String? = null
)

data class Branch(
    val startsAt: String? = null,
    val objects: Map<String, QuestObjectNode> = emptyMap()
)

data class QuestObjectNode(
    val id: String = "",
    val type: QuestObjectNodeType = QuestObjectNodeType.SERVER_ACTIONS,
    val description: String? = null,
    val actions: List<String> = emptyList(),
    val message: List<String> = emptyList(),
    val dialog: Boolean = false,
    val goto: String? = null,
    val gotos: List<String> = emptyList(),
    val logic: String? = null,
    val randomGotos: List<String> = emptyList(),
    val items: List<QuestItemEntry> = emptyList(),
    val count: Int = 1,
    val variable: String? = null,
    val valueFormula: String? = null,
    val sound: String? = null,
    val title: TitleSettings? = null,
    val startNotify: NotifySettings? = null,
    val hideChat: Boolean = false,
    val npcId: Int? = null,
    val clickTypes: List<String> = emptyList(),
    val choices: List<DivergeChoice> = emptyList(),
    val cases: List<SwitchCase> = emptyList(),
    val goals: List<EntityGoal> = emptyList(),
    val damage: Double? = null,
    val linkToQuest: Boolean = false,
    val position: PositionTarget? = null,
    val teleportPosition: PositionTarget? = null,
    val modifyOptions: ItemModifyOptions? = null,
    val blockType: String? = null,
    val blockStates: List<String> = emptyList(),
    val explosionPower: Double? = null,
    val effectList: List<String> = emptyList(),
    val countOverride: Int? = null,
    val allowDamage: Boolean = true,
    val currency: String? = null,
    val pointsCategory: String? = null,
    val achievementType: String? = null,
    val cameraToggle: Boolean? = null,
    val commandsAsPlayer: Boolean = false,
    val groupObjects: List<String> = emptyList(),
    val groupOrdered: Boolean = false,
    val groupRequired: Int? = null,
    val divergeChoices: List<DivergeChoiceGui> = emptyList(),
    val divergeReopenDelayTicks: Long? = null,
    val avoidRepeatEndTypes: List<String> = emptyList()
)

data class QuestItemEntry(
    val type: String,
    val amount: Int = 1
)

enum class QuestObjectNodeType {
    NONE,
    GROUP,
    SERVER_ACTIONS,
    RANDOM,
    LOGIC_SWITCH,
    CONDITIONS_SWITCH,
    SERVER_ITEMS_CLEAR,
    SERVER_ITEMS_DROP,
    SERVER_ITEMS_GIVE,
    SERVER_ITEMS_MODIFY,
    SERVER_ITEMS_TAKE,
    SERVER_COMMANDS_PERFORM,
    SERVER_LOGIC_VARIABLE,
    SERVER_LOGIC_MONEY,
    NPC_INTERACT,
    DIVERGE_CHAT,
    SERVER_ENTITIES_DAMAGE,
    SERVER_ENTITIES_KILL,
    SERVER_ENTITIES_KILL_LINKED,
    SERVER_ENTITIES_SPAWN,
    SERVER_ENTITIES_TELEPORT,
    SERVER_BLOCKS_PLACE,
    SERVER_EXPLOSIONS_CREATE,
    SERVER_FIREWORKS_LAUNCH,
    SERVER_LIGHTNING_STRIKE,
    SERVER_PLAYER_DAMAGE,
    SERVER_PLAYER_EFFECTS_GIVE,
    SERVER_PLAYER_EFFECTS_REMOVE,
    SERVER_PLAYER_TELEPORT,
    SERVER_LOGIC_POINTS,
    SERVER_LOGIC_MODEL_VARIABLE,
    SERVER_LOGIC_SERVER_VARIABLE,
    SERVER_LOGIC_XP,
    SERVER_ACHIEVEMENT_AWARD,
    SERVER_CAMERA_MODE_TOGGLE,
    DIVERGE_GUI,
    DIVERGE_OBJECTS
}

data class NotifySettings(
    val message: List<String> = emptyList(),
    val sound: String? = null
)

data class TitleSettings(
    val fadeIn: Int = 10,
    val stay: Int = 60,
    val fadeOut: Int = 10,
    val title: String? = null,
    val subtitle: String? = null
)

data class QuestEndObject(
    val type: QuestEndObjectType,
    val actions: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val currency: String? = null,
    val valueFormula: String? = null,
    val sound: String? = null,
    val title: TitleSettings? = null
)

enum class QuestEndObjectType {
    SERVER_ACTIONS,
    SERVER_COMMANDS_PERFORM,
    SERVER_LOGIC_MONEY
}

data class DivergeChoice(
    val text: String,
    val redoText: String? = null,
    val goto: String? = null
)

data class DivergeChoiceGui(
    val id: String,
    val slot: Int = 0,
    val item: ItemStackConfig? = null,
    val redoItem: ItemStackConfig? = null,
    val unavailableItem: ItemStackConfig? = null,
    val maxCompletions: Int = 1,
    val conditions: List<ConditionEntry> = emptyList(),
    val goto: String? = null,
    val objRef: String? = null
)

data class ItemStackConfig(
    val type: String,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val customModelData: Int? = null
)

data class ItemModifyOptions(
    val questUnlink: Boolean = false,
    val durabilitySet: Int? = null,
    val customModelDataSet: Int? = null,
    val nameSet: String? = null,
    val nameRemove: String? = null,
    val loreSet: List<String> = emptyList(),
    val loreAdd: List<String> = emptyList(),
    val loreRemove: List<String> = emptyList(),
    val enchantmentsAdd: Map<String, Int> = emptyMap(),
    val enchantmentsRemove: List<String> = emptyList()
)

data class SwitchCase(
    val matchAmount: Int = 1,
    val noMatchAmount: Int = 0,
    val conditions: List<ConditionEntry> = emptyList(),
    val goto: String? = null
)

data class PositionTarget(
    val world: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val radius: Double = 8.0
)

data class EntityGoal(
    val types: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    val colors: List<String> = emptyList(),
    val horseColors: List<String> = emptyList(),
    val horseStyles: List<String> = emptyList(),
    val goal: Double = 1.0
)

enum class SavingMode { ENABLED, DISABLED }

data class Concurrency(
    val maxInstances: Int = -1,
    val queueOnLimit: Boolean = false
)

data class PlayerSettings(
    val min: Int = 1,
    val max: Int = 1,
    val allowLeaderStop: Boolean = true
)

data class StartConditions(
    val matchAmount: Int = 0,
    val noMatchAmount: Int = 0,
    val conditions: List<ConditionEntry> = emptyList()
)

data class ConditionEntry(
    val type: ConditionType = ConditionType.PERMISSION,
    val permission: String? = null,
    val amount: Int = 1,
    val itemType: String? = null,
    val itemAmount: Int = 1,
    val variable: String? = null,
    val variableCompare: String? = null,
    val variableValue: Long? = null
)

enum class ConditionType { PERMISSION, ITEMS, VARIABLE }

data class CompletionSettings(
    val maxCompletions: Int = 1
)

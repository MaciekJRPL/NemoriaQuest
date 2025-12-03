package net.nemoria.quest.core

import net.nemoria.quest.activator.ActivatorType
import net.nemoria.quest.condition.ConditionType
import net.nemoria.quest.qobject.QuestObjectType
import net.nemoria.quest.qobject.type.ObjectTypeNone
import net.nemoria.quest.condition.type.ConditionTypeAlways
import net.nemoria.quest.activator.type.ActivatorTypeAuto
import net.nemoria.quest.qobject.type.ObjectTypeTimer
import net.nemoria.quest.condition.type.ConditionTypePermission

object Registries {
    val objectTypes = SimpleRegistry<QuestObjectType>()
    val conditionTypes = SimpleRegistry<ConditionType>()
    val activatorTypes = SimpleRegistry<ActivatorType>()

    fun bootstrap() {
        objectTypes.register(ObjectTypeNone)
        objectTypes.register(ObjectTypeTimer)
        conditionTypes.register(ConditionTypeAlways)
        conditionTypes.register(ConditionTypePermission)
        activatorTypes.register(ActivatorTypeAuto)
    }
}

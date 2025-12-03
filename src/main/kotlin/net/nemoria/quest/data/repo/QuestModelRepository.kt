package net.nemoria.quest.data.repo

import net.nemoria.quest.quest.QuestModel

interface QuestModelRepository {
    fun findById(id: String): QuestModel?
    fun findAll(): Collection<QuestModel>
    fun save(model: QuestModel)
    fun saveAll(models: Collection<QuestModel>) = models.forEach { save(it) }
}

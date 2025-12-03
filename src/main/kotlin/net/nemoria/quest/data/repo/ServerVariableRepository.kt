package net.nemoria.quest.data.repo

interface ServerVariableRepository {
    fun loadAll(): Map<String, String>
    fun save(key: String, value: String)
}

package net.nemoria.quest.core

import java.util.concurrent.ConcurrentHashMap

class SimpleRegistry<T : Registrable> {
    private val entries = ConcurrentHashMap<String, T>()

    fun register(entry: T) {
        entries[entry.id.lowercase()] = entry
    }

    fun get(id: String): T? = entries[id.lowercase()]

    fun all(): Collection<T> = entries.values
}

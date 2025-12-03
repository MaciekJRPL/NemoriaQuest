package net.nemoria.quest.core

/**
 * Zdefiniowane kolory w formacie MiniMessage do spójnego użycia w wiadomościach.
 */
object Colors {
    const val PREFIX_TEXT = "<gold>[NemoriaQuest]</gold>"
    const val PRIMARY = "<gold>"
    const val SECONDARY = "<gray>"
    const val DARK = "<dark_gray>"
    const val SUCCESS = "<green>"
    const val ERROR = "<red>"
    const val INFO = "<aqua>"
    const val ADMIN = "<light_purple>"

    fun placeholders(): Map<String, String> = mapOf(
        "prefix" to PREFIX_TEXT,
        "primary" to PRIMARY,
        "secondary" to SECONDARY,
        "dark" to DARK,
        "success" to SUCCESS,
        "error" to ERROR,
        "info" to INFO,
        "admin" to ADMIN
    )
}

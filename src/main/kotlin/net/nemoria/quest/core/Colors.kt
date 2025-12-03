package net.nemoria.quest.core

/**
 * Zdefiniowane kolory w formacie MiniMessage do spójnego użycia w wiadomościach.
 */
object Colors {
    const val PREFIX_TEXT = "<#C7F000> NemoriaQuest <dark>❙ "
    const val PRIMARY = "<#C7F000>"
    const val SECONDARY = "<#a7af7f>"
    const val DARK = "<#4F6000>"
    const val SUCCESS = "<#a0df5f>"
    const val ERROR = "<#e74c3c>"
    const val INFO = "<gray>"
    const val ADMIN = "<#00C7F0>"

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

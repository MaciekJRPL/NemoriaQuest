package net.nemoria.quest.core

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor

object MessageFormatter {
    private val mm = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.builder()
        .character('&')
        .hexCharacter('#')
        .build()

    private fun applyColors(text: String): String {
        var out = text
        Colors.placeholders().forEach { (k, v) ->
            out = out.replace("<$k>", v)
        }
        return out
    }

    fun format(text: String, allowCenter: Boolean = true): String {
        var raw = applyColors(text)
        val centerTag = allowCenter && raw.trimStart().lowercase().startsWith("<center>")
        if (centerTag) {
            raw = raw.replaceFirst("(?i)<center>".toRegex(), "").trimStart()
        }
        val rendered = if (raw.contains("<") && raw.contains(">")) {
            // MiniMessage -> legacy section (hex-friendly) without extra translations
            val comp = mm.deserialize(raw)
            LegacyComponentSerializer.legacySection().serialize(comp)
        } else {
            ChatColor.translateAlternateColorCodes('&', raw)
        }
        return if (centerTag) center(rendered) else rendered
    }

    private fun center(text: String): String {
        val centerPx = 154
        return text.split("\n").joinToString("\n") { line ->
            val stripped = line.trim()
            var messagePx = 0
            var previousCode = false
            var bold = false
            for (c in stripped.toCharArray()) {
                if (c == '§') {
                    previousCode = true
                    continue
                } else if (previousCode) {
                    previousCode = false
                    bold = c == 'l' || c == 'L'
                    continue
                }
                val length = FontInfo.length(c, bold)
                messagePx += length + 1 // one pixel spacer
            }
            val toCompensate = centerPx - messagePx / 2
            val spaceLength = FontInfo.length(' ', false) + 1
            val pad = (toCompensate / spaceLength).coerceAtLeast(0)
            " ".repeat(pad) + stripped
        }
    }

    private object FontInfo {
        private val widths = mapOf(
            'A' to 5, 'a' to 5, 'B' to 5, 'b' to 5, 'C' to 5, 'c' to 5, 'D' to 5, 'd' to 5,
            'E' to 5, 'e' to 5, 'F' to 5, 'f' to 4, 'G' to 5, 'g' to 5, 'H' to 5, 'h' to 5,
            'I' to 3, 'i' to 1, 'J' to 5, 'j' to 5, 'K' to 5, 'k' to 4, 'L' to 5, 'l' to 1,
            'M' to 5, 'm' to 5, 'N' to 5, 'n' to 5, 'O' to 5, 'o' to 5, 'P' to 5, 'p' to 5,
            'Q' to 5, 'q' to 5, 'R' to 5, 'r' to 5, 'S' to 5, 's' to 5, 'T' to 5, 't' to 4,
            'U' to 5, 'u' to 5, 'V' to 5, 'v' to 5, 'W' to 5, 'w' to 5, 'X' to 5, 'x' to 5,
            'Y' to 5, 'y' to 5, 'Z' to 5, 'z' to 5,
            '1' to 5, '2' to 5, '3' to 5, '4' to 5, '5' to 5, '6' to 5, '7' to 5, '8' to 5, '9' to 5, '0' to 5,
            '!' to 1, '@' to 6, '#' to 5, '$' to 5, '%' to 5, '^' to 5, '&' to 5, '*' to 5, '(' to 4, ')' to 4,
            '-' to 5, '_' to 5, '+' to 5, '=' to 5, '{' to 4, '}' to 4, '[' to 3, ']' to 3, ':' to 1, ';' to 1,
            '"' to 3, '\'' to 1, '<' to 4, '>' to 4, '?' to 5, '/' to 5, '\\' to 5, '|' to 1, '~' to 5, '`' to 2, '.' to 1, ',' to 1, ' ' to 3
        )

        fun length(c: Char, bold: Boolean): Int {
            val base = widths[c] ?: 4
            return if (bold && c != ' ') base + 1 else base
        }
    }

    /**
     * Wyślij wiadomość z obsługą efektu <text=XX> (litery na sekundę).
     * Efekt nie stosuje centrowania.
     */
    fun send(player: org.bukkit.entity.Player, raw: String) {
        val cleaned = raw.replaceFirst("(?i)<text=\\d+>".toRegex(), "").trimStart()
        player.sendMessage(format(cleaned))
    }

    fun sendTyping(player: org.bukkit.entity.Player, raw: String, speed: Int, allowCenter: Boolean = false) {
        val text = raw
        val formatted = format(text, allowCenter = allowCenter)
        val delay = (20.0 / speed).coerceAtLeast(1.0).toLong()
        object : org.bukkit.scheduler.BukkitRunnable() {
            var idx = 0
            override fun run() {
                if (!player.isOnline) { cancel(); return }
                if (idx >= formatted.length) { cancel(); return }
                var visibleAdvanced = false
                var nextIdx = idx
                while (nextIdx < formatted.length && !visibleAdvanced) {
                    val c = formatted[nextIdx]
                    nextIdx++
                    if (c == '§' && nextIdx < formatted.length) {
                        nextIdx++
                    } else {
                        visibleAdvanced = true
                    }
                }
                idx = nextIdx
                val part = formatted.substring(0, idx)
                player.sendMessage(part)
            }
        }.runTaskTimer(net.nemoria.quest.core.Services.plugin, 0L, delay)
    }

}

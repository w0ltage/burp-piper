package burp.ui

import java.awt.Color
import java.awt.Font
import java.awt.Insets
import java.lang.reflect.InvocationTargetException
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class AnsiTextPane : JTextPane() {
    private val parser = AnsiParser()
    private val lock = Any()

    private val defaultForeground = Color(0xE0, 0xE0, 0xE0)
    private val defaultBackground = Color(0x32, 0x33, 0x34)

    init {
        isEditable = false
        background = defaultBackground
        foreground = defaultForeground
        caretColor = defaultForeground
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        margin = Insets(6, 8, 6, 8)
    }

    fun clearContent() {
        synchronized(lock) {
            parser.reset()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            removeAllText()
        } else {
            try {
                SwingUtilities.invokeAndWait { removeAllText() }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: InvocationTargetException) {
                // ignore failures during reset
            }
        }
    }

    fun setAnsiText(text: String) {
        clearContent()
        appendAnsi(text)
    }

    fun appendAnsi(text: String) {
        if (text.isEmpty()) return
        val sanitized = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u0007", "")
        val chunks = synchronized(lock) {
            parser.feed(sanitized)
        }
        if (chunks.isEmpty()) return
        runOnEdt {
            val doc = styledDocument
            for (chunk in chunks) {
                if (chunk.text.isEmpty()) continue
                val attributes = chunk.toAttributes(defaultForeground, defaultBackground)
                try {
                    doc.insertString(doc.length, chunk.text, attributes)
                } catch (_: BadLocationException) {
                    // ignore
                }
            }
            caretPosition = doc.length
        }
    }

    private fun removeAllText() {
        try {
            document.remove(0, document.length)
        } catch (_: BadLocationException) {
            // ignore
        }
        caretPosition = 0
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }

    private inner class AnsiParser {
        private val buffer = StringBuilder()
        private var state = StyleState()

        fun reset() {
            buffer.setLength(0)
            state = StyleState()
        }

        fun feed(input: String): List<AnsiChunk> {
            buffer.append(input)
            val chunks = mutableListOf<AnsiChunk>()
            var index = 0
            while (index < buffer.length) {
                val escIndex = buffer.indexOf("\u001B[", index)
                if (escIndex == -1) {
                    if (index < buffer.length) {
                        chunks += AnsiChunk(buffer.substring(index), state.snapshot())
                    }
                    buffer.setLength(0)
                    return chunks
                }
                if (escIndex > index) {
                    chunks += AnsiChunk(buffer.substring(index, escIndex), state.snapshot())
                }
                val terminatorIndex = findSgrTerminator(escIndex + 2)
                if (terminatorIndex == -1) {
                    if (escIndex > 0) {
                        buffer.delete(0, escIndex)
                    }
                    return chunks
                }
                val command = buffer.substring(escIndex + 2, terminatorIndex)
                if (buffer[terminatorIndex] == 'm') {
                    applySgr(command)
                }
                index = terminatorIndex + 1
                if (index >= buffer.length) {
                    buffer.setLength(0)
                    return chunks
                }
            }
            buffer.setLength(0)
            return chunks
        }

        private fun findSgrTerminator(start: Int): Int {
            var i = start
            while (i < buffer.length) {
                val ch = buffer[i]
                if (ch in 'A'..'Z' || ch in 'a'..'z') {
                    return if (ch == 'm') i else i
                }
                i++
            }
            return -1
        }

        private fun applySgr(sequence: String) {
            if (sequence.isEmpty()) {
                state = StyleState()
                return
            }
            val tokens = sequence.split(';').mapNotNull { it.toIntOrNull() }
            if (tokens.isEmpty()) {
                state = StyleState()
                return
            }
            var i = 0
            while (i < tokens.size) {
                when (val code = tokens[i]) {
                    0 -> state = StyleState()
                    1 -> state = state.copy(bold = true)
                    3 -> state = state.copy(italic = true)
                    4 -> state = state.copy(underline = true)
                    7 -> state = state.copy(inverse = true)
                    22 -> state = state.copy(bold = false)
                    23 -> state = state.copy(italic = false)
                    24 -> state = state.copy(underline = false)
                    27 -> state = state.copy(inverse = false)
                    in 30..37 -> state = state.copy(foreground = ansiColor(code - 30, bright = false))
                    in 90..97 -> state = state.copy(foreground = ansiColor(code - 90, bright = true))
                    39 -> state = state.copy(foreground = null)
                    in 40..47 -> state = state.copy(background = ansiColor(code - 40, bright = false))
                    in 100..107 -> state = state.copy(background = ansiColor(code - 100, bright = true))
                    49 -> state = state.copy(background = null)
                    38 -> {
                        val (advance, color) = parseExtendedColor(tokens, i)
                        if (color != null) {
                            state = state.copy(foreground = color)
                        }
                        i += advance
                    }
                    48 -> {
                        val (advance, color) = parseExtendedColor(tokens, i)
                        if (color != null) {
                            state = state.copy(background = color)
                        }
                        i += advance
                    }
                }
                i++
            }
        }

        private fun parseExtendedColor(values: List<Int>, index: Int): Pair<Int, Color?> {
            if (index + 1 >= values.size) {
                return 0 to null
            }
            return when (values[index + 1]) {
                5 -> {
                    if (index + 2 >= values.size) {
                        0 to null
                    } else {
                        val colorIndex = values[index + 2]
                        2 to xtermColor(colorIndex)
                    }
                }
                2 -> {
                    if (index + 4 >= values.size) {
                        0 to null
                    } else {
                        val r = values[index + 2].coerceIn(0, 255)
                        val g = values[index + 3].coerceIn(0, 255)
                        val b = values[index + 4].coerceIn(0, 255)
                        4 to Color(r, g, b)
                    }
                }
                else -> 0 to null
            }
        }

        private fun ansiColor(code: Int, bright: Boolean): Color {
            val palette = if (bright) BRIGHT_COLORS else NORMAL_COLORS
            return palette.getOrElse(code) { defaultForeground }
        }

        private fun xtermColor(index: Int): Color? {
            if (index < 0) return null
            return when {
                index < 16 -> XTERM_PALETTE.getOrNull(index)
                index in 16..231 -> {
                    val value = index - 16
                    val r = value / 36
                    val g = (value % 36) / 6
                    val b = value % 6
                    Color(scaleColor(r), scaleColor(g), scaleColor(b))
                }
                index in 232..255 -> {
                    val level = 8 + (index - 232) * 10
                    Color(level, level, level)
                }
                else -> null
            }
        }

        private fun scaleColor(component: Int): Int {
            return if (component == 0) 0 else 55 + component * 40
        }
    }

    private data class StyleState(
        val foreground: Color? = null,
        val background: Color? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
    ) {
        fun snapshot(): StyleState = copy()
    }

    private data class AnsiChunk(val text: String, val style: StyleState) {
        fun toAttributes(defaultForeground: Color, defaultBackground: Color): SimpleAttributeSet {
            val attrs = SimpleAttributeSet()
            val fg = effectiveForeground(defaultForeground, defaultBackground)
            val bg = effectiveBackground(defaultForeground, defaultBackground)
            StyleConstants.setForeground(attrs, fg)
            StyleConstants.setBackground(attrs, bg)
            StyleConstants.setBold(attrs, style.bold)
            StyleConstants.setItalic(attrs, style.italic)
            StyleConstants.setUnderline(attrs, style.underline)
            return attrs
        }

        private fun effectiveForeground(defaultForeground: Color, defaultBackground: Color): Color {
            val base = style.foreground ?: defaultForeground
            val background = style.background ?: defaultBackground
            return if (style.inverse) background else base
        }

        private fun effectiveBackground(defaultForeground: Color, defaultBackground: Color): Color {
            val base = style.background ?: defaultBackground
            val foreground = style.foreground ?: defaultForeground
            return if (style.inverse) foreground else base
        }
    }

    companion object {
        private val NORMAL_COLORS = listOf(
            Color(0x00, 0x00, 0x00),
            Color(0x80, 0x00, 0x00),
            Color(0x00, 0x80, 0x00),
            Color(0x80, 0x80, 0x00),
            Color(0x00, 0x00, 0x80),
            Color(0x80, 0x00, 0x80),
            Color(0x00, 0x80, 0x80),
            Color(0xC0, 0xC0, 0xC0),
        )

        private val BRIGHT_COLORS = listOf(
            Color(0x80, 0x80, 0x80),
            Color(0xFF, 0x00, 0x00),
            Color(0x00, 0xFF, 0x00),
            Color(0xFF, 0xFF, 0x00),
            Color(0x00, 0x00, 0xFF),
            Color(0xFF, 0x00, 0xFF),
            Color(0x00, 0xFF, 0xFF),
            Color(0xFF, 0xFF, 0xFF),
        )

        private val XTERM_PALETTE = NORMAL_COLORS + BRIGHT_COLORS
    }
}

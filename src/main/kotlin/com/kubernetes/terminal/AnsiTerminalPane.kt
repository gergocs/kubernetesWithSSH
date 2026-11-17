package com.kubernetes.terminal

import java.awt.Color
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.regex.Pattern
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class AnsiTerminalPane(
    var onInput: ((String) -> Unit)? = null,
    var onCtrlC: (() -> Unit)? = null,
    var onCtrlD: (() -> Unit)? = null,
    var onCtrlZ: (() -> Unit)? = null,
    var onCtrlL: (() -> Unit)? = null
) : JTextPane() {

    private val doc = DefaultStyledDocument()
    private val defaultForeground = Color(220, 220, 220)
    private val defaultBackground = Color(30, 30, 30)

    private var currentAttr = SimpleAttributeSet()

    companion object {
        private const val MAX_DOC_LENGTH = 300_000
        private const val TRIM_SIZE = 50_000

        private val COLOR_MAP = mapOf(
            30 to Color(60, 60, 60),       // Black
            31 to Color(235, 87, 87),      // Red
            32 to Color(80, 205, 137),     // Green
            33 to Color(242, 201, 76),     // Yellow
            34 to Color(86, 156, 214),     // Blue
            35 to Color(187, 128, 250),    // Magenta
            36 to Color(78, 201, 176),     // Cyan
            37 to Color(220, 220, 220),    // White
            90 to Color(128, 128, 128),    // Bright Black (Gray)
            91 to Color(255, 107, 107),    // Bright Red
            92 to Color(105, 220, 155),    // Bright Green
            93 to Color(255, 215, 0),      // Bright Yellow
            94 to Color(110, 180, 255),    // Bright Blue
            95 to Color(218, 150, 255),    // Bright Magenta
            96 to Color(110, 230, 230),    // Bright Cyan
            97 to Color(255, 255, 255)     // Bright White
        )

        private val ANSI_PATTERN = Pattern.compile("\u001B\\[([0-9;]*)m")
        private val CONTROL_SEQ_PATTERN = Pattern.compile("\u001B\\[[?]?[0-9;]*[a-zA-Z]")
    }

    init {
        document = doc
        isEditable = false
        background = defaultBackground
        foreground = defaultForeground
        font = Font("JetBrains Mono", Font.PLAIN, 13).let {
            if (it.family == "JetBrains Mono") it else Font(Font.MONOSPACED, Font.PLAIN, 13)
        }
        caretColor = Color(100, 255, 100)
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.ALWAYS_UPDATE

        resetAttributes()
        setupListeners()
    }

    private fun resetAttributes() {
        currentAttr = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, defaultForeground)
            StyleConstants.setBackground(this, defaultBackground)
            StyleConstants.setBold(this, false)
            StyleConstants.setUnderline(this, false)
        }
    }

    private fun setupListeners() {
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                handleKeyPressed(e)
            }

            override fun keyTyped(e: KeyEvent) {
                handleKeyTyped(e)
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                requestFocusInWindow()
            }
        })
    }

    private fun handleKeyPressed(e: KeyEvent) {
        val isCtrl = (e.modifiersEx and KeyEvent.CTRL_DOWN_MASK) != 0 || (e.modifiersEx and KeyEvent.META_DOWN_MASK) != 0
        val isShift = (e.modifiersEx and KeyEvent.SHIFT_DOWN_MASK) != 0

        if (isCtrl) {
            when (e.keyCode) {
                KeyEvent.VK_C -> {
                    val sel = selectedText
                    if (!sel.isNullOrEmpty()) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(sel), null)
                    } else {
                        onCtrlC?.invoke() ?: onInput?.invoke("\u0003")
                    }
                    e.consume()
                    return
                }
                KeyEvent.VK_V -> {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                        val text = clipboard.getData(DataFlavor.stringFlavor) as? String
                        if (!text.isNullOrEmpty()) {
                            onInput?.invoke(text)
                        }
                    }
                    e.consume()
                    return
                }
                KeyEvent.VK_D -> {
                    onCtrlD?.invoke() ?: onInput?.invoke("\u0004")
                    e.consume()
                    return
                }
                KeyEvent.VK_Z -> {
                    onCtrlZ?.invoke() ?: onInput?.invoke("\u001a")
                    e.consume()
                    return
                }
                KeyEvent.VK_L -> {
                    onCtrlL?.invoke() ?: onInput?.invoke("\u000c")
                    e.consume()
                    return
                }
                KeyEvent.VK_A -> {
                    onInput?.invoke("\u0001")
                    e.consume()
                    return
                }
                KeyEvent.VK_E -> {
                    onInput?.invoke("\u0005")
                    e.consume()
                    return
                }
                KeyEvent.VK_U -> {
                    onInput?.invoke("\u0015")
                    e.consume()
                    return
                }
                KeyEvent.VK_K -> {
                    onInput?.invoke("\u000b")
                    e.consume()
                    return
                }
                KeyEvent.VK_W -> {
                    onInput?.invoke("\u0017")
                    e.consume()
                    return
                }
            }
        }

        when (e.keyCode) {
            KeyEvent.VK_ENTER -> {
                onInput?.invoke("\r")
                e.consume()
            }
            KeyEvent.VK_BACK_SPACE -> {
                onInput?.invoke("\u007f")
                e.consume()
            }
            KeyEvent.VK_TAB -> {
                onInput?.invoke("\t")
                e.consume()
            }
            KeyEvent.VK_ESCAPE -> {
                onInput?.invoke("\u001b")
                e.consume()
            }
            KeyEvent.VK_UP -> {
                onInput?.invoke("\u001b[A")
                e.consume()
            }
            KeyEvent.VK_DOWN -> {
                onInput?.invoke("\u001b[B")
                e.consume()
            }
            KeyEvent.VK_RIGHT -> {
                onInput?.invoke("\u001b[C")
                e.consume()
            }
            KeyEvent.VK_LEFT -> {
                onInput?.invoke("\u001b[D")
                e.consume()
            }
            KeyEvent.VK_HOME -> {
                onInput?.invoke("\u001b[H")
                e.consume()
            }
            KeyEvent.VK_END -> {
                onInput?.invoke("\u001b[F")
                e.consume()
            }
            KeyEvent.VK_PAGE_UP -> {
                onInput?.invoke("\u001b[5~")
                e.consume()
            }
            KeyEvent.VK_PAGE_DOWN -> {
                onInput?.invoke("\u001b[6~")
                e.consume()
            }
            KeyEvent.VK_DELETE -> {
                onInput?.invoke("\u001b[3~")
                e.consume()
            }
        }
    }

    private fun handleKeyTyped(e: KeyEvent) {
        val ch = e.keyChar
        val isCtrl = (e.modifiersEx and KeyEvent.CTRL_DOWN_MASK) != 0 || (e.modifiersEx and KeyEvent.META_DOWN_MASK) != 0
        if (!isCtrl && ch.code in 32..126) {
            onInput?.invoke(ch.toString())
            e.consume()
        }
    }

    fun appendOutput(text: String) {
        if (text.isEmpty()) return

        val run = Runnable {
            try {
                // Check if screen clear sequence was received (\u001b[2J or \u000c)
                if (text.contains("\u001b[2J") || text.contains("\u000c")) {
                    clearTerminal()
                }

                // Clean and parse ANSI sequences
                val matcher = ANSI_PATTERN.matcher(text)
                var lastIndex = 0

                while (matcher.find()) {
                    val start = matcher.start()
                    if (start > lastIndex) {
                        val chunk = cleanControlCodes(text.substring(lastIndex, start))
                        if (chunk.isNotEmpty()) {
                            insertChunk(chunk)
                        }
                    }

                    val codeStr = matcher.group(1)
                    applyAnsiCodes(codeStr)
                    lastIndex = matcher.end()
                }

                if (lastIndex < text.length) {
                    val chunk = cleanControlCodes(text.substring(lastIndex))
                    if (chunk.isNotEmpty()) {
                        insertChunk(chunk)
                    }
                }

                // Enforce buffer limit
                if (doc.length > MAX_DOC_LENGTH) {
                    doc.remove(0, TRIM_SIZE)
                }

                caretPosition = doc.length
            } catch (_: Exception) {}
        }

        if (SwingUtilities.isEventDispatchThread()) {
            run.run()
        } else {
            SwingUtilities.invokeLater(run)
        }
    }

    private fun insertChunk(chunk: String) {
        // Handle backspace characters (\b or \u0008)
        if (chunk.contains('\b') || chunk.contains('\u0008')) {
            val sb = StringBuilder()
            for (ch in chunk) {
                if (ch == '\b' || ch == '\u0008') {
                    if (sb.isNotEmpty()) {
                        sb.deleteCharAt(sb.length - 1)
                    } else if (doc.length > 0) {
                        doc.remove(doc.length - 1, 1)
                    }
                } else if (ch != '\r' || chunk.contains("\r\n")) {
                    sb.append(ch)
                }
            }
            if (sb.isNotEmpty()) {
                val cleanText = sb.toString().replace("\r\n", "\n")
                doc.insertString(doc.length, cleanText, currentAttr)
            }
        } else {
            val cleanText = chunk.replace("\r\n", "\n").replace("\r", "")
            doc.insertString(doc.length, cleanText, currentAttr)
        }
    }

    private fun cleanControlCodes(raw: String): String {
        return CONTROL_SEQ_PATTERN.matcher(raw).replaceAll("")
    }

    private fun applyAnsiCodes(codeStr: String) {
        if (codeStr.isBlank() || codeStr == "0") {
            resetAttributes()
            return
        }

        val codes = codeStr.split(";").mapNotNull { it.toIntOrNull() }
        for (code in codes) {
            when {
                code == 0 -> resetAttributes()
                code == 1 -> StyleConstants.setBold(currentAttr, true)
                code == 4 -> StyleConstants.setUnderline(currentAttr, true)
                code == 22 -> StyleConstants.setBold(currentAttr, false)
                code == 24 -> StyleConstants.setUnderline(currentAttr, false)
                COLOR_MAP.containsKey(code) -> {
                    COLOR_MAP[code]?.let { color ->
                        StyleConstants.setForeground(currentAttr, color)
                    }
                }
                code == 39 -> StyleConstants.setForeground(currentAttr, defaultForeground)
                code == 49 -> StyleConstants.setBackground(currentAttr, defaultBackground)
            }
        }
    }

    fun clearTerminal() {
        val run = Runnable {
            try {
                doc.remove(0, doc.length)
                resetAttributes()
                caretPosition = 0
            } catch (_: Exception) {}
        }
        if (SwingUtilities.isEventDispatchThread()) {
            run.run()
        } else {
            SwingUtilities.invokeLater(run)
        }
    }
}

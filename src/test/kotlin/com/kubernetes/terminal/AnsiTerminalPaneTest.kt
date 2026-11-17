package com.kubernetes.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.lang.reflect.Method
import javax.swing.SwingUtilities

class AnsiTerminalPaneTest {

    @Test
    fun testAnsiTerminalPaneCreationAndInputHandler() {
        var receivedInput = ""
        var ctrlCCalled = false

        val pane = AnsiTerminalPane(
            onInput = { receivedInput = it },
            onCtrlC = { ctrlCCalled = true }
        )

        pane.onInput?.invoke("ls -la\r")
        assertEquals("ls -la\r", receivedInput)

        pane.onCtrlC?.invoke()
        assertTrue(ctrlCCalled)
    }

    @Test
    fun testAppendOutputAndClear() {
        val pane = AnsiTerminalPane()

        SwingUtilities.invokeAndWait {
            pane.appendOutput("Hello World\n")
        }

        val text = pane.document.getText(0, pane.document.length)
        assertEquals("Hello World\n", text)

        SwingUtilities.invokeAndWait {
            pane.clearTerminal()
        }

        val clearedText = pane.document.getText(0, pane.document.length)
        assertEquals("", clearedText)
    }

    @Test
    fun testAnsiColorCodeStrippingAndFormatting() {
        val pane = AnsiTerminalPane()

        SwingUtilities.invokeAndWait {
            pane.appendOutput("\u001B[32mroot@my-pod\u001B[0m:\u001B[34m/app\u001B[0m# \n")
        }

        // Text should be cleanly inserted without raw ANSI sequences
        val text = pane.document.getText(0, pane.document.length)
        assertEquals("root@my-pod:/app# \n", text)
    }
}

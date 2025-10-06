/*
 * This file is part of Piper for Burp Suite (https://github.com/silentsignal/burp-piper)
 * Copyright (c) 2018 Andras Veres-Szentkiralyi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray as MontoyaByteArray
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.intruder.GeneratedPayload
import burp.api.montoya.intruder.IntruderInsertionPoint
import burp.api.montoya.ui.Selection
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor
import burp.api.montoya.utilities.Utilities
import com.redpois0n.terminal.JTerminal
import java.awt.Component
import java.io.File
import javax.swing.*
import kotlin.concurrent.thread

/**
 * Base abstract class for Montoya API compatible editors. Provides common functionality for both
 * text and terminal editors.
 */
abstract class MontoyaEditor(
        protected val messageViewer: Piper.MessageViewer,
        protected val utilities: Utilities,
        protected val montoyaApi: MontoyaApi
) {
    protected var currentMessage: MontoyaByteArray? = null
    protected var isModified = false

    abstract fun getUiComponent(): Component
    abstract fun setRequestResponse(content: MontoyaByteArray?)
    abstract fun isEnabled(content: MontoyaByteArray?): Boolean
    abstract fun getSelectedData(): MontoyaByteArray?
    abstract fun caption(): String

    /** Set request response content (concrete implementation for subclasses) */
    open fun setRequestResponseContent(content: MontoyaByteArray?) {
        setRequestResponse(content)
    }

    protected fun processMessage(content: MontoyaByteArray?): ByteArray? {
        if (content == null) return null

        val inputBytes = content.bytes
        val messageInfo =
                MessageInfo(
                        content = inputBytes,
                        text = utilities.bytesToString(content).toString(),
                        headers = extractHeaders(inputBytes),
                        url = null
                )

        if (messageViewer.common.hasFilter() &&
                        !messageViewer.common.filter.matches(messageInfo, utilities, montoyaApi)
        ) {
            return null
        }

        return try {
            val executionResult = messageViewer.common.cmd.execute(inputBytes)
            getStdoutWithErrorHandling(executionResult, messageViewer.common)
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError(
                            "Error processing message in ${messageViewer.common.name}: ${e.message}"
                    )
            null
        }
    }

    private fun extractHeaders(bytes: ByteArray): List<String>? {
        return try {
            val content = String(bytes)
            val headerEndIndex = content.indexOf("\r\n\r\n")
            if (headerEndIndex == -1) return null

            content.substring(0, headerEndIndex).split("\r\n")
        } catch (e: Exception) {
            null
        }
    }

    private fun getStdoutWithErrorHandling(
            executionResult: Pair<Process, List<File>>,
            tool: Piper.MinimalTool
    ): ByteArray {
        return executionResult.processOutput { process ->
            val stderr = process.errorStream.readBytes()
            if (stderr.isNotEmpty()) {
                montoyaApi.logging().logToError("${tool.name} stderr: ${String(stderr)}")
            }
            process.inputStream.readBytes()
        }
    }
}

/** Text-based editor for Montoya API. Implements both request and response editor interfaces. */
class MontoyaTextEditor(
        messageViewer: Piper.MessageViewer,
        utilities: Utilities,
        montoyaApi: MontoyaApi
) :
        MontoyaEditor(messageViewer, utilities, montoyaApi),
        ExtensionProvidedHttpRequestEditor,
        ExtensionProvidedHttpResponseEditor {

    private val textArea = JTextArea()
    private val scrollPane = JScrollPane(textArea)

    init {
        textArea.isEditable = false
        textArea.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        scrollPane.preferredSize = java.awt.Dimension(600, 400)
    }

    override fun getUiComponent(): Component = scrollPane

    override fun uiComponent(): Component = scrollPane

    override fun isModified(): Boolean = false

    override fun setRequestResponse(requestResponse: HttpRequestResponse?) {
        // Implementation for HttpRequestResponse interface
        if (requestResponse != null) {
            val request = requestResponse.request()
            setRequestResponseContent(request.toByteArray())
        } else {
            setRequestResponseContent(null as MontoyaByteArray?)
        }
    }

    override fun setRequestResponseContent(content: MontoyaByteArray?) {
        currentMessage = content
        isModified = false

        if (content == null) {
            textArea.text = ""
            return
        }

        thread {
            try {
                val processedContent = processMessage(content)
                SwingUtilities.invokeLater {
                    if (processedContent != null) {
                        textArea.text = String(processedContent)
                    } else {
                        textArea.text = utilities.bytesToString(content).toString()
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { textArea.text = "Error: ${e.message}" }
            }
        }
    }

    override fun isEnabled(content: MontoyaByteArray?): Boolean {
        if (content == null) return false

        if (!messageViewer.common.hasFilter()) return true

        val messageInfo =
                MessageInfo(
                        content = content.bytes,
                        text = utilities.bytesToString(content).toString(),
                        headers = null,
                        url = null
                )

        return messageViewer.common.filter.matches(messageInfo, utilities, montoyaApi)
    }

    override fun getSelectedData(): MontoyaByteArray? {
        val selectedText = textArea.selectedText
        return if (selectedText != null) {
            utilities.byteArrayToByteArray(selectedText.toByteArray())
        } else null
    }

    override fun caption(): String = messageViewer.common.name

    override fun isEnabledFor(requestResponse: HttpRequestResponse?): Boolean {
        return requestResponse != null
    }

/**
 * Terminal-based editor for Montoya API with color support. Implements both request and response
 * editor interfaces.
 */
class MontoyaTerminalEditor(
        messageViewer: Piper.MessageViewer,
        utilities: Utilities,
        montoyaApi: MontoyaApi
) :
        MontoyaEditor(messageViewer, utilities, montoyaApi),
        ExtensionProvidedHttpRequestEditor,
        ExtensionProvidedHttpResponseEditor {

    private val terminal = JTerminal()
    private val scrollPane = JScrollPane(terminal)
    private val outputBuilder = StringBuilder()

    override fun uiComponent(): Component = scrollPane

    override fun isModified(): Boolean = false

    override fun setRequestResponse(requestResponse: HttpRequestResponse?) {
        // Implementation for HttpRequestResponse interface
        if (requestResponse != null) {
            val request = requestResponse.request()
            setRequestResponseContent(request.toByteArray())
        } else {
            setRequestResponseContent(null as MontoyaByteArray?)
        }
    }

    init {
        terminal.isEditable = false
        scrollPane.preferredSize = java.awt.Dimension(600, 400)
    }

    override fun getUiComponent(): Component = scrollPane

    override fun setRequestResponse(content: MontoyaByteArray?) {
        currentMessage = content
        isModified = false

        if (content == null) {
            terminal.text = ""
            return
        }

        thread {
            try {
                val processedContent = processMessage(content)
                SwingUtilities.invokeLater {
                    terminal.text = ""
                    if (processedContent != null) {
                        terminal.append(String(processedContent))
                    } else {
                        terminal.append(utilities.bytesToString(content).toString())
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    terminal.text = ""
                    terminal.append("Error: ${e.message}")
                }
            }
        }
    }

    override fun isEnabled(content: MontoyaByteArray?): Boolean {
        if (content == null) return false

        if (!messageViewer.common.hasFilter()) return true

        val messageInfo =
                MessageInfo(
                        content = content.bytes,
                        text = utilities.bytesToString(content).toString(),
                        headers = null,
                        url = null
                )

        return messageViewer.common.filter.matches(messageInfo, utilities, montoyaApi)
    }

    override fun getSelectedData(): MontoyaByteArray? {
        val selectedText = terminal.selectedText
        return if (selectedText != null) {
            utilities.byteArrayToByteArray(selectedText.toByteArray())
        } else null
    }

    override fun caption(): String = messageViewer.common.name

    override fun isEnabledFor(requestResponse: HttpRequestResponse?): Boolean {
        return requestResponse != null
    }

    override fun selectedData(): Selection? {
        return null // No selection support for terminal editor
    }
}

/** Payload generator for Montoya API Intruder integration. */
class MontoyaPayloadGenerator(
        private val tool: Piper.MinimalTool,
        private val utilities: Utilities,
        private val montoyaApi: MontoyaApi
) : burp.api.montoya.intruder.PayloadGenerator {

    override fun generatePayloadFor(insertionPoint: IntruderInsertionPoint): GeneratedPayload? {
        return try {
            val baseValue = insertionPoint.baseValue()
            val input = baseValue.bytes
            val executionResult = tool.cmd.execute(input)
            val output = getStdoutWithErrorHandling(executionResult, tool)
            GeneratedPayload.payload(utilities.byteArrayToByteArray(output))
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Error generating payload with ${tool.name}: ${e.message}")
            null
        }
    }

    fun generateNextPayload(baseValue: MontoyaByteArray): MontoyaByteArray? {
        return try {
            val input = baseValue.bytes
            val executionResult = tool.cmd.execute(input)
            val output = getStdoutWithErrorHandling(executionResult, tool)
            utilities.byteArrayToByteArray(output)
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError("Error generating payload with ${tool.name}: ${e.message}")
            null
        }
    }

    private fun getStdoutWithErrorHandling(
            executionResult: Pair<Process, List<File>>,
            tool: Piper.MinimalTool
    ): ByteArray {
        return executionResult.processOutput { process ->
            val stderr = process.errorStream.readBytes()
            if (stderr.isNotEmpty()) {
                montoyaApi.logging().logToError("${tool.name} stderr: ${String(stderr)}")
            }
            process.inputStream.readBytes()
        }
    }
}

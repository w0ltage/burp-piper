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
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
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

    abstract fun getUiComponent(): Component
    abstract fun setRequestResponse(content: MontoyaByteArray?)
    abstract fun isEnabled(content: MontoyaByteArray?): Boolean

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
                        text = utilities.bytesToString(content.bytes),
                        headers = extractHeaders(inputBytes),
                        url = null
                )

        return if (messageViewer.common.filter.matches(messageInfo, utilities, montoyaApi)) {
            try {
                val executionResult = messageViewer.common.cmd.execute(inputBytes)
                executionResult.processOutput { process ->
                    val stderr = process.errorStream.readBytes()
                    if (stderr.isNotEmpty()) {
                        montoyaApi
                                .logging()
                                .logToError(
                                        "${messageViewer.common.name} stderr: ${String(stderr)}"
                                )
                    }
                    process.inputStream.readBytes()
                }
            } catch (e: Exception) {
                montoyaApi
                        .logging()
                        .logToError(
                                "Error processing message with ${messageViewer.common.name}: ${e.message}"
                        )
                inputBytes
            }
        } else {
            inputBytes
        }
    }

    private fun extractHeaders(content: ByteArray): List<String>? {
        return try {
            val contentString = String(content)
            val headerEndIndex = contentString.indexOf("\r\n\r\n")
            if (headerEndIndex == -1) {
                listOf(contentString.trim())
            } else {
                contentString.substring(0, headerEndIndex).split("\r\n")
            }
        } catch (e: Exception) {
            null
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

    override fun getRequest(): burp.api.montoya.http.message.requests.HttpRequest? {
        return null // Text editor doesn't modify requests
    }

    override fun getResponse(): burp.api.montoya.http.message.responses.HttpResponse? {
        return null // Text editor doesn't modify responses
    }

    override fun setRequestResponse(requestResponse: HttpRequestResponse?) {
        // Implementation for HttpRequestResponse interface
        if (requestResponse != null) {
            val request = requestResponse.request()
            setRequestResponseContent(utilities.byteArrayToByteArray(request.toByteArray().bytes))
        } else {
            setRequestResponseContent(null as MontoyaByteArray?)
        }
    }

    override fun setRequestResponse(content: MontoyaByteArray?) {
        currentMessage = content

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
                        textArea.text = utilities.bytesToString(content.bytes)
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
                        text = utilities.bytesToString(content.bytes),
                        headers = null,
                        url = null
                )

        return messageViewer.common.filter.matches(messageInfo, utilities, montoyaApi)
    }

    fun getSelectedData(): MontoyaByteArray? {
        val selectedText = textArea.selectedText
        return if (selectedText != null) {
            utilities.byteArrayToByteArray(selectedText.toByteArray())
        } else null
    }

    override fun caption(): String = messageViewer.common.name

    override fun isEnabledFor(requestResponse: HttpRequestResponse?): Boolean {
        return requestResponse != null
    }

    override fun selectedData(): Selection? {
        return null // No selection support for text editor
    }
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

    init {
        terminal.isEditable = false
        scrollPane.preferredSize = java.awt.Dimension(600, 400)
    }

    override fun getUiComponent(): Component = scrollPane

    override fun uiComponent(): Component = scrollPane

    override fun isModified(): Boolean = false

    override fun getRequest(): burp.api.montoya.http.message.requests.HttpRequest? {
        return null // Terminal editor doesn't modify requests
    }

    override fun getResponse(): burp.api.montoya.http.message.responses.HttpResponse? {
        return null // Terminal editor doesn't modify responses
    }

    override fun setRequestResponse(requestResponse: HttpRequestResponse?) {
        // Implementation for HttpRequestResponse interface
        if (requestResponse != null) {
            val request = requestResponse.request()
            setRequestResponseContent(utilities.byteArrayToByteArray(request.toByteArray().bytes))
        } else {
            setRequestResponseContent(null as MontoyaByteArray?)
        }
    }

    override fun setRequestResponse(content: MontoyaByteArray?) {
        currentMessage = content

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
                        terminal.append(utilities.bytesToString(content.bytes))
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
                        text = utilities.bytesToString(content.bytes),
                        headers = null,
                        url = null
                )

        return messageViewer.common.filter.matches(messageInfo, utilities, montoyaApi)
    }

    fun getSelectedData(): MontoyaByteArray? {
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

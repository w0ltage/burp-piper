package burp

import burp.ui.AnsiTextPane
import java.awt.Component
import java.io.IOException
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.text.Charsets

abstract class Editor(protected val tool: Piper.MessageViewer,
                      protected val helpers: IExtensionHelpers,
                      protected val callbacks: IBurpExtenderCallbacks,
                      private val context: PiperContext,
                      private val isEnabledSupplier: () -> Boolean) : IMessageEditorTab {
    private var msg: ByteArray? = null

    override fun getMessage(): ByteArray? = msg
    override fun isModified(): Boolean = false
    override fun getTabCaption(): String = tool.common.name

    override fun isEnabled(content: ByteArray?, isRequest: Boolean): Boolean {
        if (!isEnabledSupplier()) return false
        if (content == null || !tool.common.isInToolScope(isRequest)) return false

        val rr = RequestResponse.fromBoolean(isRequest)
        val payload = getPayload(content, rr)

        if (payload.isEmpty()) return false

        if (!tool.common.hasFilter()) {
            val cmd = tool.common.cmd
            return !cmd.hasFilter || cmd.matches(payload, context) // TODO cache output
        }

        val mi = MessageInfo(payload, helpers.bytesToString(payload), rr.getHeaders(content, helpers), url = null)
        return tool.common.filter.matches(mi, context)
    }

    override fun setMessage(content: ByteArray?, isRequest: Boolean) {
        msg = content
        if (content == null) return
        thread(start = true) {
            val input = getPayload(content, RequestResponse.fromBoolean(isRequest))
            try {
                tool.common.cmd.execute(input).processOutput(this::outputProcessor)
            } catch (e: IOException) {
                handleExecutionFailure(e)
            }
        }
    }

    private fun getPayload(content: ByteArray, rr: RequestResponse) =
            if (tool.common.cmd.passHeaders) content
            else content.copyOfRange(rr.getBodyOffset(content, helpers), content.size)

    abstract fun outputProcessor(process: Process)

    abstract override fun getSelectedData(): ByteArray
    abstract override fun getUiComponent(): Component

    protected fun logToError(message: String) {
        callbacks.stderr.write((message + "\n").toByteArray(Charsets.UTF_8))
    }

    protected open fun handleExecutionFailure(e: IOException) {
        val message = "Failed to execute ${tool.common.cmd.commandLine}: ${e.message}"
        logToError(message)
    }
}

class TerminalEditor(
    tool: Piper.MessageViewer,
    helpers: IExtensionHelpers,
    callbacks: IBurpExtenderCallbacks,
    context: PiperContext,
    isEnabledSupplier: () -> Boolean,
) : Editor(tool, helpers, callbacks, context, isEnabledSupplier) {
    private val textPane = AnsiTextPane()
    private val scrollPane = JScrollPane(textPane).apply {
        val backgroundColor = textPane.background
        background = backgroundColor
        viewport.background = backgroundColor
    }

    override fun getSelectedData(): ByteArray = helpers.stringToBytes(textPane.selectedText ?: "")
    override fun getUiComponent(): Component = scrollPane

    override fun outputProcessor(process: Process) {
        textPane.clearContent()
        val streams = listOf(process.inputStream, process.errorStream)
        streams.forEach { stream ->
            thread(start = true) {
                stream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read == -1) break
                        textPane.appendAnsi(String(buffer, 0, read))
                    }
                }
            }
        }
    }

    override fun handleExecutionFailure(e: IOException) {
        val message = "Failed to execute ${tool.common.cmd.commandLine}: ${e.message}"
        SwingUtilities.invokeLater { textPane.setAnsiText("$message\n") }
        logToError(message)
    }
}

class TextEditor(tool: Piper.MessageViewer, helpers: IExtensionHelpers,
                 callbacks: IBurpExtenderCallbacks, context: PiperContext,
                 isEnabledSupplier: () -> Boolean) :
        Editor(tool, helpers, callbacks, context, isEnabledSupplier) {
    private val editor = callbacks.createTextEditor()

    init {
        editor.setEditable(false)
    }

    override fun getSelectedData(): ByteArray = editor.selectedText
    override fun getUiComponent(): Component = editor.component

    override fun outputProcessor(process: Process) {
        process.inputStream.use {
            val bytes = it.readBytes()
            SwingUtilities.invokeLater { editor.text = bytes }
        }
    }

    override fun handleExecutionFailure(e: IOException) {
        val message = "Failed to execute ${tool.common.cmd.commandLine}: ${e.message}"
        SwingUtilities.invokeLater { editor.text = helpers.stringToBytes(message) }
        logToError(message)
    }
}

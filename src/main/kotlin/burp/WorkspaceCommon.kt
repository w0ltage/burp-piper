package burp

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.concurrent.thread

private const val DEFAULT_SAMPLE_INPUT = "GET /example HTTP/1.1\nHost: example.com\n\n"

class WorkspaceDocumentListener(private val handler: () -> Unit) : DocumentListener {
    override fun insertUpdate(e: DocumentEvent?) = handler()
    override fun removeUpdate(e: DocumentEvent?) = handler()
    override fun changedUpdate(e: DocumentEvent?) = handler()
}

class WorkspaceHistoryPanel : JPanel(BorderLayout()) {
    private val entries = mutableListOf<ValidationResultEntry>()
    private val list = JTextArea()

    init {
        border = EmptyBorder(8, 8, 8, 8)
        list.isEditable = false
        list.lineWrap = true
        list.wrapStyleWord = true
        add(JScrollPane(list), BorderLayout.CENTER)
        refresh()
    }

    fun addEntry(entry: ValidationResultEntry) {
        entries.add(0, entry)
        refresh()
    }

    fun clear() {
        entries.clear()
        refresh()
    }

    private fun refresh() {
        list.text = if (entries.isEmpty()) {
            "Run a test to populate history."
        } else {
            entries.joinToString(separator = "\n\n") {
                buildString {
                    appendLine("${it.timestamp} — Exit ${it.exitCode} (${it.inputType})")
                    appendLine("Stdout bytes: ${it.stdout.size}")
                    appendLine("Stderr bytes: ${it.stderr.size}")
                    if (!it.errorMessage.isNullOrBlank()) {
                        appendLine("Error: ${it.errorMessage}")
                    }
                }
            }
        }
        list.caretPosition = 0
    }
}

class WorkspaceValidationPanel(
    private val parent: Component?,
    private val toolSupplier: () -> Piper.MinimalTool?,
    private val onResult: (ValidationResultEntry) -> Unit,
) : JPanel(BorderLayout()) {

    private val sampleInput = JTextArea(DEFAULT_SAMPLE_INPUT)
    private val parameterPanel = JPanel()
    private val runButton = JButton("Run test")
    private val stdoutArea = JTextArea()
    private val stderrArea = JTextArea()
    private val exitCodeLabel = JLabel("Exit code: ?")
    private val statusLabel = JLabel("Ready")
    private var parameterFields: List<Pair<Piper.CommandInvocation.Parameter, JTextField>> = emptyList()

    init {
        border = EmptyBorder(8, 8, 8, 8)
        sampleInput.lineWrap = true
        sampleInput.border = BorderFactory.createTitledBorder("Sample input")
        stdoutArea.border = BorderFactory.createTitledBorder("Stdout")
        stderrArea.border = BorderFactory.createTitledBorder("Stderr")
        stdoutArea.lineWrap = true
        stderrArea.lineWrap = true
        stdoutArea.isEditable = false
        stderrArea.isEditable = false

        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(JScrollPane(sampleInput), BorderLayout.CENTER)
        parameterPanel.layout = BoxLayout(parameterPanel, BoxLayout.Y_AXIS)
        parameterPanel.border = BorderFactory.createTitledBorder("Parameter preview")
        inputPanel.add(parameterPanel, BorderLayout.SOUTH)
        add(inputPanel, BorderLayout.WEST)

        val outputSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        outputSplit.topComponent = JScrollPane(stdoutArea)
        outputSplit.bottomComponent = JScrollPane(stderrArea)
        outputSplit.resizeWeight = 0.5
        add(outputSplit, BorderLayout.CENTER)

        val footer = JPanel()
        footer.layout = BoxLayout(footer, BoxLayout.X_AXIS)
        runButton.addActionListener { runTest() }
        footer.add(runButton)
        footer.add(Box.createRigidArea(Dimension(8, 0)))
        val copyStdout = JButton("Copy stdout")
        copyStdout.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(stdoutArea.text), null)
        }
        val copyStderr = JButton("Copy stderr")
        copyStderr.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(stderrArea.text), null)
        }
        footer.add(copyStdout)
        footer.add(Box.createRigidArea(Dimension(4, 0)))
        footer.add(copyStderr)
        footer.add(Box.createRigidArea(Dimension(8, 0)))
        footer.add(exitCodeLabel)
        footer.add(Box.createRigidArea(Dimension(8, 0)))
        footer.add(statusLabel)
        add(footer, BorderLayout.SOUTH)
    }

    fun reset() {
        stdoutArea.text = ""
        stderrArea.text = ""
        exitCodeLabel.text = "Exit code: ?"
        statusLabel.text = "Ready"
    }

    fun updateParameterInputs(parameters: List<Piper.CommandInvocation.Parameter>) {
        parameterPanel.removeAll()
        parameterFields = parameters.map { parameter ->
            val field = JTextField(parameter.defaultValue ?: "")
            val row = JPanel()
            row.layout = BoxLayout(row, BoxLayout.X_AXIS)
            row.border = EmptyBorder(2, 8, 2, 8)
            row.add(JLabel(parameter.displayName + ":"))
            row.add(Box.createRigidArea(Dimension(8, 0)))
            row.add(field)
            parameterPanel.add(row)
            parameter to field
        }
        parameterPanel.revalidate()
        parameterPanel.repaint()
    }

    private fun runTest() {
        val tool = toolSupplier() ?: return
        runButton.isEnabled = false
        statusLabel.text = "Running…"
        stdoutArea.text = ""
        stderrArea.text = ""
        exitCodeLabel.text = "Exit code: ?"

        val inputBytes = sampleInput.text.toByteArray(StandardCharsets.UTF_8)
        val parameterMap = parameterFields.associate { (param, field) -> param.name to field.text }
        thread {
            val start = Instant.now()
            try {
                val (process, tempFiles) = tool.cmd.execute(parameterMap, inputBytes to null)
                val stdout = process.inputStream.readBytes()
                val stderr = process.errorStream.readBytes()
                val exit = process.waitFor()
                tempFiles.forEach(File::delete)
                val entry = ValidationResultEntry(
                    timestamp = DateTimeFormatter.ISO_INSTANT.format(start),
                    exitCode = exit,
                    inputType = "stdin",
                    stdout = stdout,
                    stderr = stderr,
                    errorMessage = null,
                )
                SwingUtilities.invokeLater {
                    stdoutArea.text = stdout.toString(StandardCharsets.UTF_8)
                    stderrArea.text = stderr.toString(StandardCharsets.UTF_8)
                    exitCodeLabel.text = "Exit code: $exit"
                    statusLabel.text = if (exit == 0) "Success" else "Command failed"
                    onResult(entry)
                    runButton.isEnabled = true
                }
            } catch (ex: Exception) {
                val entry = ValidationResultEntry(
                    timestamp = DateTimeFormatter.ISO_INSTANT.format(start),
                    exitCode = -1,
                    inputType = "stdin",
                    stdout = ByteArray(0),
                    stderr = ByteArray(0),
                    errorMessage = ex.message,
                )
                SwingUtilities.invokeLater {
                    stdoutArea.text = ""
                    stderrArea.text = ex.message ?: "Command failed"
                    exitCodeLabel.text = "Exit code: error"
                    statusLabel.text = "Error"
                    onResult(entry)
                    runButton.isEnabled = true
                }
            }
        }
    }
}

data class ValidationResultEntry(
    val timestamp: String,
    val exitCode: Int,
    val inputType: String,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val errorMessage: String?,
) {
    companion object {
        fun simpleSuccess(stdout: ByteArray, stderr: ByteArray, exit: Int): ValidationResultEntry = ValidationResultEntry(
            timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            exitCode = exit,
            inputType = "stdin",
            stdout = stdout,
            stderr = stderr,
            errorMessage = null,
        )
    }
}


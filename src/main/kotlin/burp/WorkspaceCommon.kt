package burp

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToggleButton
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

data class WorkspaceHeaderValues<T>(
    val name: String,
    val enabled: Boolean,
    val tags: List<String>,
    val template: T?,
)

open class WorkspaceHeaderPanel<T>(
    private val templateLabel: String,
    private val onChange: () -> Unit,
) : JPanel(GridBagLayout()) {

    val nameField = JTextField()
    val enabledToggle = JToggleButton("Enabled")
    val tagsField = JTextField()
    val templateCombo = JComboBox<T>()

    private var templateRowNextGridx = 2
    private var suppressTemplateEvent = false

    init {
        border = EmptyBorder(12, 12, 12, 12)

        addLabel("Name", 0, 0)
        addComponent(nameField, gridx = 1, gridy = 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL)
        addComponent(enabledToggle, gridx = 2, gridy = 0)

        addLabel("Tags", 0, 1)
        addComponent(tagsField, gridx = 1, gridy = 1, gridwidth = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL)

        addLabel(templateLabel, 0, 2)
        addComponent(templateCombo, gridx = 1, gridy = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL)

        nameField.document.addDocumentListener(WorkspaceDocumentListener { onChange() })
        tagsField.document.addDocumentListener(WorkspaceDocumentListener { onChange() })
        enabledToggle.addActionListener { onChange() }
        templateCombo.addActionListener {
            if (!suppressTemplateEvent) {
                onChange()
            }
        }
    }

    protected fun addTemplateField(label: String, component: JComponent) {
        addLabel(label, templateRowNextGridx, TEMPLATE_ROW)
        addComponent(component, gridx = templateRowNextGridx + 1, gridy = TEMPLATE_ROW)
        templateRowNextGridx += 2
    }

    fun withTemplateChangeSuppressed(block: () -> Unit) {
        suppressTemplateEvent = true
        try {
            block()
        } finally {
            suppressTemplateEvent = false
        }
    }

    fun setFieldsEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        enabledToggle.isEnabled = enabled
        tagsField.isEnabled = enabled
        templateCombo.isEnabled = enabled
    }

    fun readValues(): WorkspaceHeaderValues<T> = WorkspaceHeaderValues(
        name = nameField.text,
        enabled = enabledToggle.isSelected,
        tags = tagsField.text.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) },
        template = templateCombo.selectedItem as? T,
    )

    fun setValues(values: WorkspaceHeaderValues<T>) {
        nameField.text = values.name
        enabledToggle.isSelected = values.enabled
        tagsField.text = values.tags.joinToString(", ")
        withTemplateChangeSuppressed {
            when {
                values.template != null -> templateCombo.selectedItem = values.template
                templateCombo.itemCount > 0 -> templateCombo.selectedIndex = 0
                else -> templateCombo.selectedIndex = -1
            }
        }
    }

    private fun addLabel(text: String, gridx: Int, gridy: Int) {
        val constraints = createConstraints(gridx, gridy)
        add(JLabel(text), constraints)
    }

    private fun addComponent(
        component: JComponent,
        gridx: Int,
        gridy: Int,
        gridwidth: Int = 1,
        weightx: Double = 0.0,
        fill: Int = GridBagConstraints.NONE,
    ) {
        val constraints = createConstraints(gridx, gridy)
        constraints.gridwidth = gridwidth
        constraints.weightx = weightx
        constraints.fill = fill
        add(component, constraints)
    }

    private fun createConstraints(gridx: Int, gridy: Int): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = gridx
        this.gridy = gridy
        this.insets = Insets(4, 4, 4, 4)
        this.anchor = GridBagConstraints.WEST
    }

    companion object {
        private const val TEMPLATE_ROW = 2
    }
}

class WorkspaceOverviewPanel<T>(
    title: String,
    private val emptyText: String,
    private val summaryBuilder: (T) -> String,
) : JPanel(BorderLayout()) {

    private val summaryArea = JTextArea()

    init {
        border = EmptyBorder(12, 12, 12, 12)
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false

        val titleLabel = JLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(titleLabel.font.style or java.awt.Font.BOLD)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        container.add(titleLabel)
        container.add(Box.createVerticalStrut(8))

        summaryArea.isEditable = false
        summaryArea.isOpaque = false
        summaryArea.lineWrap = true
        summaryArea.wrapStyleWord = true
        summaryArea.border = null
        summaryArea.alignmentX = Component.LEFT_ALIGNMENT
        container.add(summaryArea)

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(container, BorderLayout.NORTH)
        add(wrapper, BorderLayout.CENTER)
        display(null)
    }

    fun display(state: T?) {
        summaryArea.text = state?.let(summaryBuilder) ?: emptyText
        summaryArea.caretPosition = 0
    }
}

class MessageMatchInlinePanel(parent: Component?) : JPanel(BorderLayout()) {
    private val window: Window = when (parent) {
        is Window -> parent
        is Component -> SwingUtilities.getWindowAncestor(parent) as? Window ?: JOptionPane.getRootFrame()
        else -> JOptionPane.getRootFrame()
    }
    private val widget = CollapsedMessageMatchWidget(window, mm = null, showHeaderMatch = true, caption = "Filter:")

    init {
        border = EmptyBorder(8, 8, 8, 8)
        val content = JPanel(GridBagLayout())
        val cs = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }
        widget.buildGUI(content, cs)
        add(content, BorderLayout.NORTH)
    }

    fun addChangeListener(listener: () -> Unit) {
        widget.addChangeListener(object : ChangeListener<Piper.MessageMatch> {
            override fun valueChanged(value: Piper.MessageMatch?) {
                listener()
            }
        })
    }

    fun setValue(newValue: Piper.MessageMatch?) {
        widget.value = newValue
    }

    fun toMessageMatch(): Piper.MessageMatch? = widget.value
}

class WorkspaceFilterPanel(
    parent: Component?,
    private val onChange: () -> Unit,
) : JPanel(BorderLayout()) {

    private val filterPanel = MessageMatchInlinePanel(parent)
    private val summaryLabel = JLabel("Filter description → (none)")
    private val sampleLabel = JLabel("Matched sample: –")

    init {
        border = EmptyBorder(12, 12, 12, 12)
        add(filterPanel, BorderLayout.CENTER)
        val footer = JPanel()
        footer.layout = BoxLayout(footer, BoxLayout.Y_AXIS)
        footer.border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
        summaryLabel.alignmentX = Component.LEFT_ALIGNMENT
        sampleLabel.alignmentX = Component.LEFT_ALIGNMENT
        footer.add(summaryLabel)
        footer.add(Box.createVerticalStrut(4))
        footer.add(sampleLabel)
        add(footer, BorderLayout.SOUTH)
        filterPanel.addChangeListener {
            updateSummary()
            onChange()
        }
    }

    fun display(filter: Piper.MessageMatch?) {
        filterPanel.setValue(filter)
        updateSummary()
    }

    fun value(): Piper.MessageMatch? = filterPanel.toMessageMatch()

    private fun updateSummary() {
        val value = filterPanel.toMessageMatch()
        summaryLabel.text = "Filter description → " +
            (value?.toHumanReadable(negation = false, hideParentheses = true) ?: "(none)")
        sampleLabel.text = "Matched sample: preview unavailable"
    }
}

data class WorkspaceCommandState(
    val command: Piper.CommandInvocation,
    val usesAnsi: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

class WorkspaceCommandPanel(
    parent: Component?,
    private val onChange: () -> Unit,
    private val purpose: CommandInvocationPurpose = CommandInvocationPurpose.SELF_FILTER,
    private val showAnsiCheckbox: Boolean = true,
    private val showDependenciesField: Boolean = true,
    private val showTagsField: Boolean = true,
    private val dependenciesLabelText: String = "Binaries required in PATH (comma separated)",
    private val tagsLabelText: String = "Command tags (comma separated)",
    private val placeholderValues: List<String> = DEFAULT_COMMAND_TOKEN_PLACEHOLDERS,
) : JPanel(BorderLayout()) {

    private val window: Window = when (parent) {
        is Window -> parent
        is Component -> SwingUtilities.getWindowAncestor(parent) as? Window ?: JOptionPane.getRootFrame()
        else -> JOptionPane.getRootFrame()
    }
    private val commandEditor = CollapsedCommandInvocationWidget(
        window,
        Piper.CommandInvocation.getDefaultInstance(),
        purpose,
        placeholderValues = placeholderValues,
    )
    private val ansiCheck = if (showAnsiCheckbox) JCheckBox("Uses ANSI (color) escape sequences") else null
    private val dependenciesField = if (showDependenciesField) JTextField() else null
    private val tagsField = if (showTagsField) JTextField() else null
    private val commandChangeListeners = mutableListOf<(Piper.CommandInvocation?) -> Unit>()

    init {
        border = EmptyBorder(12, 12, 12, 12)
        val content = JPanel()
        content.layout = GridBagLayout()
        val cs = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 0, 4, 0)
        }
        commandEditor.buildGUI(content, cs)

        ansiCheck?.let {
            cs.gridy++
            content.add(it, cs)
            it.addChangeListener { onChange() }
        }

        dependenciesField?.let { field ->
            cs.gridy++
            content.add(JLabel(dependenciesLabelText), cs)
            cs.gridy++
            content.add(field, cs)
            field.document.addDocumentListener(WorkspaceDocumentListener { onChange() })
        }

        tagsField?.let { field ->
            cs.gridy++
            content.add(JLabel(tagsLabelText), cs)
            cs.gridy++
            content.add(field, cs)
            field.document.addDocumentListener(WorkspaceDocumentListener { onChange() })
        }

        commandEditor.addChangeListener(object : ChangeListener<Piper.CommandInvocation> {
            override fun valueChanged(value: Piper.CommandInvocation?) {
                onChange()
                notifyCommandChange(value)
            }
        })

        add(JScrollPane(content), BorderLayout.CENTER)
    }

    private fun notifyCommandChange(value: Piper.CommandInvocation?) {
        commandChangeListeners.forEach { it(value) }
    }

    fun display(state: WorkspaceCommandState?) {
        commandEditor.value = state?.command ?: Piper.CommandInvocation.getDefaultInstance()
        ansiCheck?.isSelected = state?.usesAnsi ?: false
        dependenciesField?.text = state?.dependencies?.joinToString(", ") ?: ""
        tagsField?.text = state?.tags?.joinToString(", ") ?: ""
        notifyCommandChange(commandEditor.value)
    }

    fun snapshot(): WorkspaceCommandState {
        val command = commandEditor.value ?: Piper.CommandInvocation.getDefaultInstance()
        val dependencies = dependenciesField?.text
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?: emptyList()
        val tags = tagsField?.text
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?: emptyList()
        return WorkspaceCommandState(
            command = command,
            usesAnsi = ansiCheck?.isSelected ?: false,
            dependencies = dependencies,
            tags = tags,
        )
    }

    fun requireCommand(): Piper.CommandInvocation = commandEditor.requireValue()

    fun addCommandChangeListener(listener: (Piper.CommandInvocation?) -> Unit) {
        commandChangeListeners += listener
        listener(commandEditor.value)
    }
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

class WorkspaceValidationHistoryPanel(
    parent: Component?,
    toolSupplier: () -> Piper.MinimalTool?,
) : JPanel(BorderLayout()) {
    private val historyPanel = WorkspaceHistoryPanel()
    private val validationPanel = WorkspaceValidationPanel(parent, toolSupplier) { entry ->
        historyPanel.addEntry(entry)
    }

    init {
        val tabs = JTabbedPane()
        tabs.addTab("Run test", validationPanel)
        tabs.addTab("History", historyPanel)
        add(tabs, BorderLayout.CENTER)
    }

    fun reset() {
        validationPanel.reset()
        historyPanel.clear()
    }

    fun updateParameters(parameters: List<Piper.CommandInvocation.Parameter>) {
        validationPanel.updateParameterInputs(parameters)
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


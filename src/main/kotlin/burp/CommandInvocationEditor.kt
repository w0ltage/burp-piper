package burp

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

const val INPUT_FILENAME_TOKEN = "<INPUT>"

private val MONO_FONT = Font("monospaced", Font.PLAIN, 12)

data class CommandInvocationEditorConfig(
    val showParametersTab: Boolean = true,
    val showInputTab: Boolean = true,
    val showFiltersTab: Boolean = true,
    val showDependenciesField: Boolean = true,
)

class CommandInvocationEditor(
    parent: Component?,
    private val purpose: CommandInvocationPurpose,
    private val showPassHeaders: Boolean = true,
    private val config: CommandInvocationEditorConfig = CommandInvocationEditorConfig(),
) : JPanel(BorderLayout()) {

    private val commandPanel = CommandEditorPanel { notifyChanged() }
    private val parametersPanel = if (config.showParametersTab) ParameterEditorPanel { notifyChanged() } else null
    private val ioPanel = if (config.showInputTab) InputMethodPanel(showPassHeaders) { notifyChanged() } else null
    private val stdoutPanel = if (config.showFiltersTab && purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
        MessageMatchInlinePanel(parent)
    } else null
    private val stderrPanel = if (config.showFiltersTab && purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
        MessageMatchInlinePanel(parent)
    } else null
    private val hintLabel = JLabel("Define success conditions using filters or exit codes")
    private val dependenciesField = if (config.showInputTab && config.showDependenciesField) JTextField() else null
    private val changeListeners = mutableListOf<() -> Unit>()

    init {
        border = EmptyBorder(8, 8, 8, 8)
        if (shouldUseTabbedLayout()) {
            val tabs = JTabbedPane()
            tabs.addTab("Command", commandPanel)
            parametersPanel?.let { tabs.addTab("Parameters", it) }
            ioPanel?.let { panel ->
                val inputContainer = JPanel()
                inputContainer.layout = BoxLayout(inputContainer, BoxLayout.Y_AXIS)
                inputContainer.add(panel)
                if (dependenciesField != null) {
                    inputContainer.add(Box.createVerticalStrut(12))
                    val label = sectionLabel("Required binaries (comma separated)")
                    inputContainer.add(label)
                    dependenciesField.alignmentX = Component.LEFT_ALIGNMENT
                    dependenciesField.columns = 24
                    dependenciesField.document.addDocumentListener(documentChangeListener())
                    inputContainer.add(dependenciesField)
                }
                tabs.addTab("Input / Method", JScrollPane(inputContainer))
            }
            if (stdoutPanel != null && stderrPanel != null) {
                val filters = JPanel()
                filters.layout = BoxLayout(filters, BoxLayout.Y_AXIS)
                filters.border = EmptyBorder(8, 8, 8, 8)
                filters.add(sectionLabel("Match on stdout"))
                filters.add(stdoutPanel)
                filters.add(Box.createVerticalStrut(12))
                filters.add(sectionLabel("Match on stderr"))
                filters.add(stderrPanel)
                filters.add(Box.createVerticalStrut(12))
                hintLabel.alignmentX = Component.LEFT_ALIGNMENT
                filters.add(hintLabel)
                stdoutPanel.addChangeListener { notifyChanged() }
                stderrPanel.addChangeListener { notifyChanged() }
                tabs.addTab("Filters", JScrollPane(filters))
            }
            add(tabs, BorderLayout.CENTER)
        } else {
            val container = JPanel(BorderLayout())
            container.add(commandPanel, BorderLayout.CENTER)
            add(container, BorderLayout.CENTER)
        }
    }

    private fun sectionLabel(text: String): JComponent = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun shouldUseTabbedLayout(): Boolean =
        config.showParametersTab || config.showInputTab || (config.showFiltersTab && purpose != CommandInvocationPurpose.EXECUTE_ONLY)

    private fun documentChangeListener(): DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = notifyChanged()
        override fun removeUpdate(e: DocumentEvent?) = notifyChanged()
        override fun changedUpdate(e: DocumentEvent?) = notifyChanged()
    }

    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    fun display(command: Piper.CommandInvocation) {
        commandPanel.setTokens(command.buildTokenList())
        parametersPanel?.setParameters(command.parameterList.map { it.toParameterState() })
        ioPanel?.setInputMethod(command.inputMethod, command.exitCodeList, command.passHeaders)
        dependenciesField?.text = command.requiredInPathList.joinToString(", ")
        if (stdoutPanel != null && stderrPanel != null) {
            stdoutPanel.setValue(command.stdout)
            stderrPanel.setValue(command.stderr)
        }
    }

    fun snapshot(): Piper.CommandInvocation {
        val tokens = commandPanel.tokens()
        require(tokens.isNotEmpty()) { "The command must contain at least one token" }
        require(tokens.first().isNotBlank()) { "The first token (command) cannot be blank" }
        val parameters = parametersPanel?.parameters ?: emptyList()
        val duplicateNames = parameters.groupingBy { it.name }.eachCount().filterValues { it > 1 }
        if (duplicateNames.isNotEmpty()) {
            throw IllegalStateException("Parameter names must be unique: ${duplicateNames.keys.joinToString(", ")}")
        }
        val ioSnapshot = ioPanel?.snapshot()
        val builder = Piper.CommandInvocation.newBuilder()
        if (ioSnapshot != null) {
            builder.setInputMethod(ioSnapshot.method)
                .setPassHeaders(ioSnapshot.passHeaders)
                .addAllPrefix(tokens.prefixTokens(ioSnapshot.method))
                .addAllPostfix(tokens.postfixTokens(ioSnapshot.method))
                .addAllExitCode(ioSnapshot.exitCodes)
        } else {
            val defaultMethod = Piper.CommandInvocation.InputMethod.STDIN
            builder.setInputMethod(defaultMethod)
                .setPassHeaders(false)
                .addAllPrefix(tokens.prefixTokens(defaultMethod))
                .addAllPostfix(tokens.postfixTokens(defaultMethod))
        }
            .addAllParameter(parameters.map { it.toProtoParameter() })
        if (dependenciesField != null) {
            val dependencies = dependenciesField.text.split(',').mapNotNull {
                it.trim().takeIf(String::isNotEmpty)
            }
            builder.addAllRequiredInPath(dependencies)
        }
        if (stdoutPanel != null && stderrPanel != null) {
            stdoutPanel.toMessageMatch()?.let { builder.stdout = it }
            stderrPanel.toMessageMatch()?.let { builder.stderr = it }
            if (builder.exitCodeCount == 0 && !builder.hasStdout() && !builder.hasStderr()) {
                throw IllegalStateException("Define at least one success condition: stdout, stderr, or exit codes")
            }
        }
        return builder.build()
    }

    fun setHintText(text: String) {
        hintLabel.text = text
    }

    private fun notifyChanged() {
        changeListeners.forEach { it.invoke() }
    }
}

class CommandEditorPanel(
    private val onChange: () -> Unit,
) : JPanel(BorderLayout()) {

    private val tokensModel = DefaultListModel<String>()
    private val tokensList = JList(tokensModel)
    private val tokenField = JTextField()

    init {
        border = EmptyBorder(8, 8, 8, 8)
        val title = JLabel("Command tokens")
        title.font = title.font.deriveFont(Font.BOLD)
        add(title, BorderLayout.NORTH)
        tokensList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tokensList.font = MONO_FONT
        tokensList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = tokensList.locationToIndex(e.point)
                    if (index >= 0) {
                        val current = tokensModel[index]
                        val edited = JOptionPane.showInputDialog(this@CommandEditorPanel, "Edit token", current) ?: return
                        tokensModel[index] = edited
                        onChange()
                    }
                }
            }
        })
        add(JScrollPane(tokensList), BorderLayout.CENTER)

        val footer = JPanel()
        footer.layout = BoxLayout(footer, BoxLayout.Y_AXIS)
        val addRow = JPanel()
        addRow.layout = BoxLayout(addRow, BoxLayout.X_AXIS)
        tokenField.columns = 20
        tokenField.font = MONO_FONT
        addRow.add(tokenField)
        val addButton = JButton("Add")
        addButton.addActionListener {
            val text = tokenField.text
            if (text.isNotEmpty()) {
                tokensModel.addElement(text)
                tokenField.text = ""
                onChange()
            }
        }
        addRow.add(Box.createRigidArea(Dimension(4, 0)))
        addRow.add(addButton)
        val placeholderButton = JButton("Add placeholder")
        placeholderButton.addActionListener { showPlaceholderMenu(placeholderButton) }
        addRow.add(Box.createRigidArea(Dimension(4, 0)))
        addRow.add(placeholderButton)
        addRow.add(Box.createHorizontalGlue())
        footer.add(addRow)

        val actions = JPanel()
        actions.layout = BoxLayout(actions, BoxLayout.X_AXIS)
        val remove = JButton("Remove")
        remove.addActionListener {
            val idx = tokensList.selectedIndex
            if (idx >= 0) {
                tokensModel.remove(idx)
                onChange()
            }
        }
        val wrap = JButton("Wrap in quotes")
        wrap.addActionListener {
            val idx = tokensList.selectedIndex
            if (idx >= 0) {
                tokensModel[idx] = "\"${tokensModel[idx]}\""
                onChange()
            }
        }
        val moveUp = JButton("Move up")
        moveUp.addActionListener {
            val idx = tokensList.selectedIndex
            if (idx > 0) {
                val value = tokensModel.remove(idx)
                tokensModel.add(idx - 1, value)
                tokensList.selectedIndex = idx - 1
                onChange()
            }
        }
        val moveDown = JButton("Move down")
        moveDown.addActionListener {
            val idx = tokensList.selectedIndex
            if (idx >= 0 && idx < tokensModel.size() - 1) {
                val value = tokensModel.remove(idx)
                tokensModel.add(idx + 1, value)
                tokensList.selectedIndex = idx + 1
                onChange()
            }
        }
        actions.add(remove)
        actions.add(Box.createRigidArea(Dimension(4, 0)))
        actions.add(wrap)
        actions.add(Box.createRigidArea(Dimension(4, 0)))
        actions.add(moveUp)
        actions.add(Box.createRigidArea(Dimension(4, 0)))
        actions.add(moveDown)
        actions.add(Box.createHorizontalGlue())
        footer.add(Box.createRigidArea(Dimension(0, 6)))
        footer.add(actions)

        footer.add(Box.createRigidArea(Dimension(0, 6)))
        val help = JLabel("Use placeholders like \"\${'$'}{dialog0}\". $INPUT_FILENAME_TOKEN inserts the temp file when using filename mode.")
        footer.add(help)

        add(footer, BorderLayout.SOUTH)
    }

    fun setTokens(tokens: List<String>) {
        tokensModel.clear()
        tokens.forEach(tokensModel::addElement)
    }

    fun tokens(): List<String> = (0 until tokensModel.size()).map(tokensModel::getElementAt)

    private fun showPlaceholderMenu(anchor: Component) {
        val menu = JPopupMenu()
        val placeholders = listOf(
            "\${'$'}{BASE}",
            "\${'$'}{PAYLOAD_INDEX}",
            "\${'$'}{dialog0}",
            "\${'$'}{domain}",
            INPUT_FILENAME_TOKEN,
        )
        placeholders.forEach { placeholder ->
            menu.add(JMenuItem(placeholder).apply {
                addActionListener {
                    tokensModel.addElement(placeholder)
                    onChange()
                }
            })
        }
        menu.show(anchor, 0, anchor.height)
    }
}

class ParameterEditorPanel(
    private val onChange: () -> Unit,
) : JPanel(BorderLayout()) {

    private val model = ParameterTableModel()
    private val table = JTable(model)

    init {
        border = EmptyBorder(8, 8, 8, 8)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.rowHeight = 24
        table.columnModel.getColumn(4).cellEditor = javax.swing.DefaultCellEditor(JCheckBox())
        val typeColumn = table.columnModel.getColumn(2)
        val typeBox = JComboBox(ParameterInputType.values())
        typeColumn.cellEditor = javax.swing.DefaultCellEditor(typeBox)
        add(JScrollPane(table), BorderLayout.CENTER)

        val footer = JPanel()
        footer.layout = BoxLayout(footer, BoxLayout.X_AXIS)
        val addButton = JButton("Add parameter")
        addButton.addActionListener {
            val index = model.addRow()
            table.selectionModel.setSelectionInterval(index, index)
            onChange()
        }
        val removeButton = JButton("Remove")
        removeButton.addActionListener {
            val idx = table.selectedRow
            if (idx >= 0) {
                model.removeRow(idx)
                onChange()
            }
        }
        val moveUp = JButton("Move up")
        moveUp.addActionListener {
            val idx = table.selectedRow
            if (idx > 0) {
                model.swap(idx, idx - 1)
                table.selectionModel.setSelectionInterval(idx - 1, idx - 1)
                onChange()
            }
        }
        val moveDown = JButton("Move down")
        moveDown.addActionListener {
            val idx = table.selectedRow
            if (idx >= 0 && idx < model.rowCount - 1) {
                model.swap(idx, idx + 1)
                table.selectionModel.setSelectionInterval(idx + 1, idx + 1)
                onChange()
            }
        }
        footer.add(addButton)
        footer.add(Box.createRigidArea(Dimension(4, 0)))
        footer.add(removeButton)
        footer.add(Box.createRigidArea(Dimension(4, 0)))
        footer.add(moveUp)
        footer.add(Box.createRigidArea(Dimension(4, 0)))
        footer.add(moveDown)
        footer.add(Box.createHorizontalGlue())
        val hint = JLabel("Use \${'$'}{name} in the command to substitute the prompt value.")
        footer.add(hint)
        add(footer, BorderLayout.SOUTH)
    }

    fun setParameters(parameters: List<ParameterState>) {
        model.setParameters(parameters)
    }

    val parameters: List<ParameterState>
        get() = model.getParameters()
}

class ParameterTableModel : AbstractTableModel() {
    private val data = mutableListOf<ParameterState>()

    override fun getRowCount(): Int = data.size

    override fun getColumnCount(): Int = 6

    override fun getColumnName(column: Int): String = when (column) {
        0 -> "Placeholder"
        1 -> "Prompt label"
        2 -> "Type"
        3 -> "Default"
        4 -> "Required"
        else -> "Description"
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        2 -> ParameterInputType::class.java
        4 -> java.lang.Boolean::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
        0 -> data[rowIndex].name
        1 -> data[rowIndex].label
        2 -> data[rowIndex].type
        3 -> data[rowIndex].defaultValue
        4 -> data[rowIndex].required
        else -> data[rowIndex].description
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val row = data[rowIndex]
        when (columnIndex) {
            0 -> row.name = aValue?.toString().orEmpty()
            1 -> row.label = aValue?.toString().orEmpty()
            2 -> row.type = aValue as? ParameterInputType ?: ParameterInputType.TEXT
            3 -> row.defaultValue = aValue?.toString().orEmpty()
            4 -> row.required = (aValue as? Boolean) ?: false
            5 -> row.description = aValue?.toString().orEmpty()
        }
        fireTableRowsUpdated(rowIndex, rowIndex)
    }

    fun setParameters(parameters: List<ParameterState>) {
        data.clear()
        parameters.forEach { data += it.copy() }
        fireTableDataChanged()
    }

    fun getParameters(): List<ParameterState> = data.map { it.copy() }

    fun addRow(): Int {
        val nextIndex = data.size
        data += ParameterState(
            name = "dialog$nextIndex",
            label = "Prompt ${nextIndex + 1}",
            defaultValue = "",
            required = false,
            type = ParameterInputType.TEXT,
            description = "",
        )
        fireTableRowsInserted(nextIndex, nextIndex)
        return nextIndex
    }

    fun removeRow(index: Int) {
        if (index in data.indices) {
            data.removeAt(index)
            fireTableDataChanged()
        }
    }

    fun swap(i: Int, j: Int) {
        if (i in data.indices && j in data.indices) {
            val tmp = data[i]
            data[i] = data[j]
            data[j] = tmp
            fireTableRowsUpdated(minOf(i, j), maxOf(i, j))
        }
    }
}

data class ParameterState(
    var name: String,
    var label: String,
    var defaultValue: String,
    var required: Boolean,
    var type: ParameterInputType,
    var description: String,
)

data class CommandInputSnapshot(
    val method: Piper.CommandInvocation.InputMethod,
    val exitCodes: List<Int>,
    val passHeaders: Boolean,
)

class InputMethodPanel(
    private val showPassHeaders: Boolean,
    private val onChange: () -> Unit,
) : JPanel() {

    private val stdinButton = JToggleButton("Use stdin")
    private val filenameButton = JToggleButton("Use filename")
    private val exitCodesField = JTextField()
    private val passHeadersBox = JCheckBox("Pass HTTP headers to command")

    init {
        layout = GridBagLayout()
        border = EmptyBorder(8, 8, 8, 8)
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 4)
        }
        val group = ButtonGroup()
        group.add(stdinButton)
        group.add(filenameButton)
        stdinButton.addActionListener {
            if (!stdinButton.isSelected) {
                stdinButton.isSelected = true
            }
            onChange()
        }
        filenameButton.addActionListener {
            if (!filenameButton.isSelected) {
                filenameButton.isSelected = true
            }
            onChange()
        }
        stdinButton.isSelected = true
        add(stdinButton, constraints)
        constraints.gridx = 1
        add(filenameButton, constraints)
        constraints.gridx = 0
        constraints.gridy = 1
        constraints.gridwidth = 2
        val hint = JLabel("For filename mode include $INPUT_FILENAME_TOKEN in the command where the temp file should appear.")
        add(hint, constraints)

        constraints.gridy = 2
        constraints.gridwidth = 1
        add(JLabel("Success exit codes:"), constraints)
        constraints.gridx = 1
        exitCodesField.columns = 16
        exitCodesField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onChange()
            override fun removeUpdate(e: DocumentEvent?) = onChange()
            override fun changedUpdate(e: DocumentEvent?) = onChange()
        })
        add(exitCodesField, constraints)

        if (showPassHeaders) {
            constraints.gridx = 0
            constraints.gridy = 3
            constraints.gridwidth = 2
            passHeadersBox.addActionListener { onChange() }
            add(passHeadersBox, constraints)
        }
    }

    fun setInputMethod(
        method: Piper.CommandInvocation.InputMethod,
        exitCodes: List<Int>,
        passHeaders: Boolean,
    ) {
        stdinButton.isSelected = method == Piper.CommandInvocation.InputMethod.STDIN
        filenameButton.isSelected = method == Piper.CommandInvocation.InputMethod.FILENAME
        exitCodesField.text = exitCodes.joinToString(", ")
        passHeadersBox.isSelected = passHeaders && showPassHeaders
    }

    fun snapshot(): CommandInputSnapshot {
        val method = if (filenameButton.isSelected) Piper.CommandInvocation.InputMethod.FILENAME
        else Piper.CommandInvocation.InputMethod.STDIN
        val exitCodes = exitCodesField.text.split(',').mapNotNull {
            it.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        }
        return CommandInputSnapshot(method, exitCodes, passHeadersBox.isSelected && showPassHeaders)
    }
}

fun List<String>.prefixTokens(method: Piper.CommandInvocation.InputMethod): List<String> =
    if (method == Piper.CommandInvocation.InputMethod.FILENAME) {
        val placeholderIndex = indexOf(INPUT_FILENAME_TOKEN)
        if (placeholderIndex >= 0) subList(0, placeholderIndex) else this
    } else {
        this
    }

fun List<String>.postfixTokens(method: Piper.CommandInvocation.InputMethod): List<String> =
    if (method == Piper.CommandInvocation.InputMethod.FILENAME) {
        val placeholderIndex = indexOf(INPUT_FILENAME_TOKEN)
        if (placeholderIndex >= 0 && placeholderIndex + 1 <= size - 1) {
            subList(placeholderIndex + 1, size)
        } else emptyList()
    } else {
        emptyList()
    }

fun ParameterState.toProtoParameter(): Piper.CommandInvocation.Parameter {
    val builder = Piper.CommandInvocation.Parameter.newBuilder()
        .setName(name)
        .setLabel(label)
        .setDefaultValue(defaultValue)
    if (required) builder.required = true
    val metadata = GeneratorParameterMetadata(type, description)
    val encoded = encodeParameterMetadata(metadata)
    if (encoded.isNotEmpty()) {
        builder.description = encoded
    }
    return builder.build()
}

fun Piper.CommandInvocation.Parameter.toParameterState(): ParameterState {
    val metadata = decodeParameterMetadata(description)
    return ParameterState(
        name = name,
        label = label.orEmpty(),
        defaultValue = defaultValue.orEmpty(),
        required = required,
        type = metadata.type,
        description = metadata.description,
    )
}

fun Piper.CommandInvocation.buildTokenList(): MutableList<String> {
    val tokens = mutableListOf<String>()
    tokens += prefixList
    if (inputMethod == Piper.CommandInvocation.InputMethod.FILENAME) {
        tokens += INPUT_FILENAME_TOKEN
        tokens += postfixList
    }
    return tokens
}

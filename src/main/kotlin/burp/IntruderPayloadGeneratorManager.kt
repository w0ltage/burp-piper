package burp

import org.snakeyaml.engine.v1.api.Dump
import org.snakeyaml.engine.v1.api.DumpSettingsBuilder
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.AbstractAction
import javax.swing.AbstractListModel
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.UIManager
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import javax.swing.table.AbstractTableModel
private const val DEFAULT_SAMPLE_INPUT = "GET /example HTTP/1.1\nHost: example.com\n\n"

data class GeneratorEditorState(
    var modelIndex: Int? = null,
    var name: String = "",
    var enabled: Boolean = true,
    var tags: MutableList<String> = mutableListOf(),
    var templateId: String? = null,
    var commandTokens: MutableList<String> = mutableListOf("/usr/bin/env"),
    var inputMethod: Piper.CommandInvocation.InputMethod = Piper.CommandInvocation.InputMethod.STDIN,
    var parameters: MutableList<ParameterState> = mutableListOf(),
    var passHeaders: Boolean = false,
    var exitCodes: MutableList<Int> = mutableListOf(),
    var dependencies: MutableList<String> = mutableListOf(),
)

data class ParameterState(
    var name: String,
    var label: String,
    var defaultValue: String,
    var required: Boolean,
    var type: ParameterInputType,
    var description: String,
)

data class TestRunEntry(
    val timestamp: Instant,
    val exitCode: Int?,
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val errorMessage: String?,
)

private data class TemplateOption(val id: String?, val label: String) {
    override fun toString(): String = label
}

private fun renderGeneratorOverview(state: GeneratorEditorState): String = buildString {
    appendLine("Name: ${state.name.ifBlank { "(unnamed)" }}")
    appendLine("Status: ${if (state.enabled) "Enabled" else "Disabled"}")
    if (state.tags.isNotEmpty()) {
        appendLine("Tags: ${state.tags.joinToString(", ")}")
    }
    appendLine("Command: ${state.commandTokens.joinToString(" ")}")
    appendLine("Input method: ${state.inputMethod.name}")
    if (state.parameters.isNotEmpty()) {
        appendLine("Parameters: ${state.parameters.joinToString { "${it.name} (${it.type.name.lowercase()})" }}")
    }
    if (state.dependencies.isNotEmpty()) {
        appendLine("Required binaries: ${state.dependencies.joinToString(", ")}")
    }
    if (state.exitCodes.isNotEmpty()) {
        appendLine("Success exit codes: ${state.exitCodes.joinToString(", ")}")
    }
}

private data class GeneratorTemplate(
    val id: String,
    val name: String,
    val description: String,
    val apply: (GeneratorEditorState) -> Unit,
)

private object GeneratorTemplates {
    private val builtins = listOf(
        GeneratorTemplate(
            id = "template-ssrf-collaborator",
            name = "SSRF \u2192 Burp Collaborator",
            description = "Generate SSRF payloads that call back to a collaborator domain.",
        ) { state ->
            state.commandTokens = mutableListOf(
                "/usr/local/bin/ssrf-gen",
                "--target",
                "\${domain}",
                "--format",
                "raw",
            )
            state.inputMethod = Piper.CommandInvocation.InputMethod.STDIN
            state.parameters = mutableListOf(
                ParameterState(
                    name = "domain",
                    label = "Collaborator domain",
                    defaultValue = "abc.piper-collab.net",
                    required = true,
                    type = ParameterInputType.TEXT,
                    description = "Domain to embed in generated payloads.",
                ),
            )
            state.tags = mutableListOf("ssrf", "collaborator")
            state.dependencies = mutableListOf("/usr/local/bin/ssrf-gen")
        },
        GeneratorTemplate(
            id = "template-base64-urlsafe",
            name = "Base64 URL-safe",
            description = "Encode stdin using URL-safe base64.",
        ) { state ->
            state.commandTokens = mutableListOf(
                "/usr/bin/python3",
                "-c",
                "import sys,base64; print(base64.urlsafe_b64encode(sys.stdin.read().encode()).decode())",
            )
            state.inputMethod = Piper.CommandInvocation.InputMethod.STDIN
            state.parameters.clear()
            state.tags = mutableListOf("encoding", "base64")
            state.dependencies = mutableListOf("/usr/bin/python3")
        },
        GeneratorTemplate(
            id = "template-john-wordlist",
            name = "John wordlist",
            description = "Use john --stdout to stream wordlist entries.",
        ) { state ->
            state.commandTokens = mutableListOf(
                "/usr/local/bin/john",
                "--stdout",
                "--wordlist=\${wordlist}",
            )
            state.inputMethod = Piper.CommandInvocation.InputMethod.STDIN
            state.parameters = mutableListOf(
                ParameterState(
                    name = "wordlist",
                    label = "Path to wordlist",
                    defaultValue = "/usr/share/wordlists/rockyou.txt",
                    required = true,
                    type = ParameterInputType.FILE,
                    description = "Absolute path to the wordlist consumed by John.",
                ),
            )
            state.tags = mutableListOf("passwords", "john")
            state.dependencies = mutableListOf("/usr/local/bin/john")
        },
    )

    private val userDefined = CopyOnWriteArrayList<GeneratorTemplate>()

    fun all(): List<GeneratorTemplate> = builtins + userDefined

    fun saveCustomTemplate(name: String, description: String, state: GeneratorEditorState) {
        val template = GeneratorTemplate(
            id = "custom-${System.nanoTime()}",
            name = name,
            description = description,
        ) { target ->
            target.commandTokens = state.commandTokens.map { it }.toMutableList()
            target.inputMethod = state.inputMethod
            target.parameters = state.parameters.map { it.copy() }.toMutableList()
            target.tags = state.tags.map { it }.toMutableList()
            target.exitCodes = state.exitCodes.map { it }.toMutableList()
            target.dependencies = state.dependencies.map { it }.toMutableList()
            target.passHeaders = state.passHeaders
        }
        userDefined += template
    }
}

class IntruderPayloadGeneratorManagerPanel(
    private val model: DefaultListModel<Piper.MinimalTool>,
    parent: Component?,
) : JPanel(BorderLayout()) {

    private val filterModel = FilteredGeneratorListModel(model)
    private val generatorList = JList<Piper.MinimalTool>(filterModel)
    private val searchField = JTextField()
    private val editorPanel = GeneratorEditorPanel(parent) { state ->
        saveState(state)
    }

    init {
        generatorList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        generatorList.cellRenderer = GeneratorListRenderer()
        generatorList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadSelection()
            }
        }
        generatorList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    generatorList.selectedIndex = generatorList.locationToIndex(e.point)
                }
            }
        })
        generatorList.componentPopupMenu = createContextMenu()

        searchField.toolTipText = "Search by name or tag"
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        val leftPanel = JPanel(BorderLayout())
        leftPanel.border = EmptyBorder(8, 8, 8, 8)
        val top = JPanel(BorderLayout(4, 4))
        top.add(JLabel("Search"), BorderLayout.WEST)
        top.add(searchField, BorderLayout.CENTER)
        val newButton = JButton("+ New generator")
        newButton.addActionListener { createNewGenerator() }
        leftPanel.add(top, BorderLayout.NORTH)
        leftPanel.add(JScrollPane(generatorList), BorderLayout.CENTER)
        leftPanel.add(newButton, BorderLayout.SOUTH)

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, editorPanel)
        splitPane.dividerLocation = 280
        add(splitPane, BorderLayout.CENTER)

        if (model.size() > 0) {
            generatorList.selectedIndex = 0
        }
    }

    private fun applyFilter() {
        val query = searchField.text.orEmpty()
        filterModel.updateFilter(query)
        if (filterModel.getSize() == 0) {
            generatorList.clearSelection()
            editorPanel.displayState(null)
        } else if (generatorList.selectedIndex >= filterModel.getSize() || generatorList.selectedIndex < 0) {
            generatorList.selectedIndex = 0
        }
    }

    private fun loadSelection() {
        val filteredIndex = generatorList.selectedIndex
        if (filteredIndex < 0) {
            editorPanel.displayState(null)
            return
        }
        val backingIndex = filterModel.backingIndex(filteredIndex)
        val tool = model.getElementAt(backingIndex)
        editorPanel.displayState(tool.toEditorState(backingIndex))
    }

    private fun createNewGenerator() {
        generatorList.clearSelection()
        val state = GeneratorEditorState()
        editorPanel.displayState(state)
    }

    private fun saveState(state: GeneratorEditorState) {
        val minimalTool = state.toMinimalTool()
        val index = state.modelIndex
        if (index == null) {
            model.addElement(minimalTool)
            filterModel.invalidate()
            val newIndex = model.size() - 1
            val filteredIndex = filterModel.filteredIndexOf(newIndex)
            if (filteredIndex >= 0) {
                generatorList.selectedIndex = filteredIndex
            }
        } else {
            model.setElementAt(minimalTool, index)
            filterModel.invalidate()
            val filteredIndex = filterModel.filteredIndexOf(index)
            if (filteredIndex >= 0) {
                generatorList.selectedIndex = filteredIndex
            }
        }
        loadSelection()
    }

    private fun createContextMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(JMenuItem(object : AbstractAction("Clone") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = generatorList.selectedIndex
                if (idx < 0) return
                val backingIndex = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backingIndex)
                val clone = tool.toBuilder().setName(tool.name + " copy").build()
                model.addElement(clone)
                filterModel.invalidate()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Toggle enabled") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = generatorList.selectedIndex
                if (idx < 0) return
                val backingIndex = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backingIndex)
                model.setElementAt(tool.buildEnabled(!tool.enabled), backingIndex)
                filterModel.invalidate()
                loadSelection()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Delete") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = generatorList.selectedIndex
                if (idx < 0) return
                val backingIndex = filterModel.backingIndex(idx)
                if (JOptionPane.showConfirmDialog(
                        generatorList,
                        "Remove generator \"${model.getElementAt(backingIndex).name}\"?",
                        "Confirm removal",
                        JOptionPane.OK_CANCEL_OPTION,
                    ) == JOptionPane.OK_OPTION
                ) {
                    model.removeElementAt(backingIndex)
                    filterModel.invalidate()
                    editorPanel.displayState(null)
                }
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Export to YAML") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = generatorList.selectedIndex
                if (idx < 0) return
                val backingIndex = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backingIndex)
                exportTool(tool)
            }
        }))
        return menu
    }

    private fun exportTool(tool: Piper.MinimalTool) {
        val chooser = JFileChooser()
        chooser.selectedFile = File(tool.name.replace(Regex("\\W+"), "_") + ".yaml")
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val dump = Dump(DumpSettingsBuilder().build())
            val yaml = dump.dumpToString(tool.toMap())
            chooser.selectedFile.writeText(yaml)
        }
    }
}

private class GeneratorListRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val tool = value as? Piper.MinimalTool
        if (tool != null) {
            val status = if (tool.enabled) "Enabled" else "Disabled"
            val tags = tool.cmd.extractTags()
            val tagSuffix = if (tags.isEmpty()) "" else " – " + tags.joinToString(", ")
            text = tool.name + " [$status]" + tagSuffix
        }
        return component
    }
}

private class FilteredGeneratorListModel(
    private val backing: DefaultListModel<Piper.MinimalTool>,
) : AbstractListModel<Piper.MinimalTool>(), ListDataListener {

    private val indices = mutableListOf<Int>()
    private var query: String = ""

    init {
        backing.addListDataListener(this)
        rebuild()
    }

    override fun getSize(): Int = indices.size

    override fun getElementAt(index: Int): Piper.MinimalTool = backing.getElementAt(indices[index])

    fun updateFilter(query: String) {
        this.query = query.lowercase()
        rebuild()
    }

    fun invalidate() {
        rebuild()
    }

    fun backingIndex(filteredIndex: Int): Int = indices[filteredIndex]

    fun filteredIndexOf(backingIndex: Int): Int = indices.indexOf(backingIndex)

    private fun rebuild() {
        indices.clear()
        for (i in 0 until backing.size()) {
            val tool = backing.getElementAt(i)
            if (matches(tool)) {
                indices += i
            }
        }
        if (indices.isEmpty()) {
            fireContentsChanged(this, 0, 0)
        } else {
            fireContentsChanged(this, 0, indices.size - 1)
        }
    }

    private fun matches(tool: Piper.MinimalTool): Boolean {
        if (query.isBlank()) return true
        val tags = tool.cmd.extractTags()
        val haystack = buildString {
            append(tool.name.lowercase())
            append(' ')
            append(tags.joinToString(" ") { it.lowercase() })
        }
        return haystack.contains(query)
    }

    override fun intervalAdded(e: ListDataEvent?) = rebuild()
    override fun intervalRemoved(e: ListDataEvent?) = rebuild()
    override fun contentsChanged(e: ListDataEvent?) = rebuild()
}

private class GeneratorEditorPanel(
    private val parent: Component?,
    private val onSave: (GeneratorEditorState) -> Unit,
) : JPanel(BorderLayout()) {

    private var state: GeneratorEditorState? = null
    private var loading = false

    private val header = WorkspaceHeaderPanel<TemplateOption>("Template") { markDirty() }
    private val tabbedPane = JTabbedPane()
    private val overviewTab = WorkspaceOverviewPanel<GeneratorEditorState>(
        title = "Generator summary",
        emptyText = "Select or create a generator to get started.",
    ) { state ->
        renderGeneratorOverview(state)
    }
    private val commandTab = CommandTokensPanel(onChange = { markDirty() })
    private val inputTab = InputMethodPanel { markDirty() }
    private val historyTab = HistoryPanel()
    private lateinit var validationTab: ValidationTestPanel
    private lateinit var parametersTab: ParameterEditorPanel
    private val saveButton = JButton("Save")
    private val saveTemplateButton = JButton("Save as template")
    private val exportButton = JButton("Copy YAML")
    private val cancelButton = JButton("Cancel")

    init {
        layout = BorderLayout()
        border = EmptyBorder(8, 8, 8, 8)
        validationTab = ValidationTestPanel(parent, { collectStateForTest() }) { historyTab.addEntry(it) }
        parametersTab = ParameterEditorPanel {
            markDirty()
            validationTab.updateParameterInputs(parametersTab.parameters)
        }
        header.nameField.columns = 24
        header.tagsField.toolTipText = "Comma separated tags"
        header.tagsField.columns = 18
        header.templateCombo.addActionListener {
            if (!loading) {
                applyTemplate(header.templateCombo.selectedItem as? TemplateOption)
            }
        }
        add(header, BorderLayout.NORTH)

        tabbedPane.addTab("Overview", overviewTab)
        tabbedPane.addTab("Command", commandTab)
        tabbedPane.addTab("Parameters", parametersTab)
        tabbedPane.addTab("Input / Method", inputTab)
        tabbedPane.addTab("Validation & Test", validationTab)
        tabbedPane.addTab("History & Logs", historyTab)
        add(tabbedPane, BorderLayout.CENTER)

        val footer = JPanel()
        footer.layout = BoxLayout(footer, BoxLayout.X_AXIS)
        saveButton.isEnabled = false
        saveButton.addActionListener { save() }
        saveTemplateButton.addActionListener { saveTemplate() }
        exportButton.addActionListener { copyYamlToClipboard() }
        cancelButton.addActionListener { resetToCurrentState() }
        footer.add(saveButton)
        footer.add(Box.createRigidArea(Dimension(8, 0)))
        footer.add(saveTemplateButton)
        footer.add(Box.createRigidArea(Dimension(8, 0)))
        footer.add(exportButton)
        footer.add(Box.createHorizontalGlue())
        footer.add(cancelButton)
        add(footer, BorderLayout.SOUTH)
    }

    fun displayState(newState: GeneratorEditorState?) {
        state = newState
        loading = true
        try {
            val selectedTemplate = populateTemplates(newState?.templateId)
            header.setValues(
                WorkspaceHeaderValues(
                    name = newState?.name.orEmpty(),
                    enabled = newState?.enabled ?: false,
                    tags = newState?.tags ?: emptyList(),
                    template = selectedTemplate,
                ),
            )
            if (newState == null) {
                commandTab.setTokens(emptyList())
                parametersTab.setParameters(emptyList())
                inputTab.setInputMethod(Piper.CommandInvocation.InputMethod.STDIN, emptyList(), false)
                validationTab.reset()
                validationTab.updateParameterInputs(emptyList())
                overviewTab.display(null)
                historyTab.clear()
                saveButton.isEnabled = false
                return
            }
            commandTab.setTokens(newState.commandTokens)
            parametersTab.setParameters(newState.parameters)
            validationTab.updateParameterInputs(newState.parameters)
            inputTab.setInputMethod(newState.inputMethod, newState.exitCodes, newState.passHeaders)
            validationTab.reset()
            overviewTab.display(newState)
            historyTab.clear()
            saveButton.isEnabled = false
        } finally {
            loading = false
        }
    }

    private fun populateTemplates(selectedId: String?): TemplateOption? {
        val options = mutableListOf(TemplateOption(null, "Custom"))
        GeneratorTemplates.all().forEach { template ->
            options += TemplateOption(template.id, template.name)
        }
        val index = options.indexOfFirst { it.id == selectedId }
        val selectedIndex = when {
            index >= 0 -> index
            options.isNotEmpty() -> 0
            else -> -1
        }
        header.withTemplateChangeSuppressed {
            header.templateCombo.model = DefaultComboBoxModel(options.toTypedArray())
            header.templateCombo.selectedIndex = selectedIndex
        }
        return options.getOrNull(selectedIndex)
    }

    private fun applyTemplate(option: TemplateOption?) {
        val templateId = option?.id ?: return
        val template = GeneratorTemplates.all().firstOrNull { it.id == templateId } ?: return
        val current = state ?: GeneratorEditorState()
        template.apply(current)
        current.templateId = templateId
        displayState(current)
        markDirty()
    }

    private fun collectStateFromUI(): GeneratorEditorState? {
        val current = state ?: GeneratorEditorState()
        val headerValues = header.readValues()
        current.name = headerValues.name.trim()
        current.enabled = headerValues.enabled
        current.tags = headerValues.tags.toMutableList()
        current.templateId = headerValues.template?.id
        current.commandTokens = commandTab.tokens().toMutableList()
        val paramStates = parametersTab.parameters
        current.parameters = paramStates.map { it.copy() }.toMutableList()
        val inputConfig = inputTab.snapshot()
        current.inputMethod = inputConfig.method
        current.exitCodes = inputConfig.exitCodes.toMutableList()
        current.passHeaders = inputConfig.passHeaders
        overviewTab.display(current)
        state = current
        return current
    }

    private fun collectStateForTest(): Piper.MinimalTool? {
        val current = collectStateFromUI() ?: return null
        if (current.name.isBlank()) {
            current.name = "Untitled generator"
        }
        return current.toMinimalTool()
    }

    private fun save() {
        val current = collectStateFromUI() ?: return
        if (current.name.isBlank()) {
            JOptionPane.showMessageDialog(this, "Name cannot be empty", "Validation", JOptionPane.WARNING_MESSAGE)
            return
        }
        state = current
        onSave(current.deepCopy())
        saveButton.isEnabled = false
    }

    private fun resetToCurrentState() {
        val current = state ?: return
        displayState(current)
    }

    private fun saveTemplate() {
        val current = collectStateFromUI() ?: return
        val name = JOptionPane.showInputDialog(this, "Template name", current.name.takeIf { it.isNotBlank() } ?: "New template")
        if (name.isNullOrBlank()) return
        val description = JOptionPane.showInputDialog(this, "Template description", "") ?: ""
        GeneratorTemplates.saveCustomTemplate(name, description, current.deepCopy())
        populateTemplates(current.templateId)
        JOptionPane.showMessageDialog(this, "Template saved to this session.")
    }

    private fun copyYamlToClipboard() {
        val current = collectStateFromUI() ?: return
        val tool = current.toMinimalTool()
        val dump = Dump(DumpSettingsBuilder().build())
        val yaml = dump.dumpToString(tool.toMap())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(yaml), null)
        JOptionPane.showMessageDialog(this, "Generator YAML copied to clipboard")
    }

    private fun markDirty() {
        if (!loading) {
            saveButton.isEnabled = true
        }
    }
}

private class ParameterEditorPanel(
    private val onChange: () -> Unit,
) : JPanel(BorderLayout()) {

    private val model = ParameterTableModel()
    private val table = JTable(model)

    init {
        border = EmptyBorder(8, 8, 8, 8)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
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
        val hint = JLabel("Use \${name} in the command to substitute the prompt value.")
        footer.add(hint)
        add(footer, BorderLayout.SOUTH)
    }

    fun setParameters(parameters: List<ParameterState>) {
        model.setParameters(parameters)
    }

    val parameters: List<ParameterState>
        get() = model.getParameters()
}

private class ParameterTableModel : AbstractTableModel() {
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
            name = "dialog${nextIndex}",
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

private data class InputSnapshot(
    val method: Piper.CommandInvocation.InputMethod,
    val exitCodes: List<Int>,
    val passHeaders: Boolean,
)

private class InputMethodPanel(
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
        stdinButton.addActionListener {
            if (stdinButton.isSelected) {
                filenameButton.isSelected = false
            } else {
                stdinButton.isSelected = true
            }
            onChange()
        }
        filenameButton.addActionListener {
            if (filenameButton.isSelected) {
                stdinButton.isSelected = false
            } else {
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
        val hint = JLabel("For filename mode include ${INPUT_FILENAME_TOKEN} in the command where the temp file should appear.")
        add(hint, constraints)

        constraints.gridy = 2
        constraints.gridwidth = 1
        add(JLabel("Success exit codes:"), constraints)
        constraints.gridx = 1
        exitCodesField.columns = 16
        add(exitCodesField, constraints)
        exitCodesField.document.addDocumentListener(WorkspaceDocumentListener { onChange() })

        constraints.gridx = 0
        constraints.gridy = 3
        constraints.gridwidth = 2
        passHeadersBox.addActionListener { onChange() }
        add(passHeadersBox, constraints)
    }

    fun setInputMethod(
        method: Piper.CommandInvocation.InputMethod,
        exitCodes: List<Int>,
        passHeaders: Boolean,
    ) {
        stdinButton.isSelected = method == Piper.CommandInvocation.InputMethod.STDIN
        filenameButton.isSelected = method == Piper.CommandInvocation.InputMethod.FILENAME
        exitCodesField.text = exitCodes.joinToString(", ")
        passHeadersBox.isSelected = passHeaders
    }

    fun snapshot(): InputSnapshot {
        val method = if (filenameButton.isSelected) Piper.CommandInvocation.InputMethod.FILENAME else Piper.CommandInvocation.InputMethod.STDIN
        val exitCodes = exitCodesField.text.split(',').mapNotNull {
            it.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        }
        return InputSnapshot(method, exitCodes, passHeadersBox.isSelected)
    }
}

private class ValidationTestPanel(
    private val parent: Component?,
    private val toolSupplier: () -> Piper.MinimalTool?,
    private val onResult: (TestRunEntry) -> Unit,
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
        statusLabel.border = EmptyBorder(0, 8, 0, 0)
        footer.add(statusLabel)
        footer.add(Box.createHorizontalGlue())
        add(footer, BorderLayout.SOUTH)
    }

    fun updateParameterInputs(parameters: List<ParameterState>) {
        parameterPanel.removeAll()
        val protoParams = parameters.map { it.toProtoParameter() }
        parameterFields = protoParams.map { parameter ->
            val field = JTextField(parameter.defaultValue)
            field.columns = 20
            val label = JLabel(parameter.displayName + if (parameter.required) " *" else "")
            val row = JPanel()
            row.layout = BoxLayout(row, BoxLayout.X_AXIS)
            row.add(label)
            row.add(Box.createRigidArea(Dimension(8, 0)))
            row.add(field)
            row.add(Box.createHorizontalGlue())
            parameterPanel.add(row)
            parameterPanel.add(Box.createRigidArea(Dimension(0, 4)))
            parameter to field
        }
        parameterPanel.revalidate()
        parameterPanel.repaint()
    }

    fun reset() {
        stdoutArea.text = ""
        stderrArea.text = ""
        exitCodeLabel.text = "Exit code: ?"
    }

    private fun runTest() {
        val tool = toolSupplier() ?: return
        val command = tool.cmd
        val parameterValues = buildMap {
            for ((parameter, field) in parameterFields) {
                put(parameter.name, field.text)
            }
        }
        runButton.isEnabled = false
        statusLabel.text = "Running..."
        object : SwingWorker<TestRunEntry, Unit>() {
            override fun doInBackground(): TestRunEntry {
                val start = Instant.now()
                return try {
                    val (process, tempFiles) = command.execute(parameterValues, sampleInput.text.toByteArray(StandardCharsets.UTF_8) to null)
                    val stdout = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
                    val stderr = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
                    val exit = process.waitFor()
                    tempFiles.forEach(File::delete)
                    TestRunEntry(start, exit, true, stdout, stderr, null)
                } catch (ex: Exception) {
                    TestRunEntry(start, null, false, "", "", ex.message)
                }
            }

            override fun done() {
                try {
                    val result = get()
                    stdoutArea.text = result.stdout
                    stderrArea.text = result.stderr
                    exitCodeLabel.text = "Exit code: ${result.exitCode ?: "n/a"}"
                    val statusText = if (result.success) "Completed" else "Failed"
                    statusLabel.text = statusText
                    onResult(result)
                } catch (ex: Exception) {
                    stdoutArea.text = ""
                    stderrArea.text = ""
                    exitCodeLabel.text = "Exit code: error"
                    JOptionPane.showMessageDialog(parent, ex.message ?: "Test failed", "Validation", JOptionPane.ERROR_MESSAGE)
                } finally {
                    if (!isCancelled) {
                        statusLabel.text = "Ready"
                    }
                    runButton.isEnabled = true
                }
            }
        }.execute()
    }
}

private class HistoryPanel : JPanel(BorderLayout()) {
    private val model = DefaultListModel<TestRunEntry>()
    private val list = JList(model)

    init {
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val entry = value as TestRunEntry
                val ts = DateTimeFormatter.ISO_INSTANT.format(entry.timestamp)
                text = "$ts – ${if (entry.success) "Success" else "Failure"} (exit ${entry.exitCode ?: "n/a"})"
                toolTipText = buildString {
                    appendLine(text)
                    if (entry.errorMessage != null) {
                        appendLine("Error: ${entry.errorMessage}")
                    }
                }
                return component
            }
        }
        add(JScrollPane(list), BorderLayout.CENTER)
    }

    fun addEntry(entry: TestRunEntry) {
        model.addElement(entry)
    }

    fun clear() {
        model.clear()
    }
}

private fun ParameterState.toProtoParameter(): Piper.CommandInvocation.Parameter {
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

private fun Piper.CommandInvocation.Parameter.toParameterState(): ParameterState {
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

private fun Piper.MinimalTool.toEditorState(index: Int? = null): GeneratorEditorState {
    val state = GeneratorEditorState()
    state.modelIndex = index
    state.name = name
    state.enabled = enabled
    state.tags = cmd.extractTags().toMutableList()
    state.commandTokens = buildTokenList()
    state.inputMethod = cmd.inputMethod
    state.parameters = cmd.parameterList.map { it.toParameterState() }.toMutableList()
    state.passHeaders = cmd.passHeaders
    state.exitCodes = cmd.exitCodeList.toMutableList()
    state.dependencies = cmd.extractDependencies().toMutableList()
    return state
}

private fun GeneratorEditorState.toMinimalTool(): Piper.MinimalTool {
    val builder = Piper.MinimalTool.newBuilder()
        .setName(name)
        .setEnabled(enabled)
        .setScope(Piper.MinimalTool.Scope.REQUEST_RESPONSE)
    val commandBuilder = Piper.CommandInvocation.newBuilder()
        .setInputMethod(inputMethod)
        .setPassHeaders(passHeaders)
        .addAllPrefix(prefixTokens())
        .addAllPostfix(postfixTokens())
        .addAllExitCode(exitCodes)
        .addAllParameter(parameters.map { it.toProtoParameter() })
        .addAllRequiredInPath(mergeDependenciesAndTags(dependencies, tags))
    builder.cmd = commandBuilder.build()
    return builder.build()
}

private fun GeneratorEditorState.prefixTokens(): List<String> =
    if (inputMethod == Piper.CommandInvocation.InputMethod.FILENAME) {
        val placeholderIndex = commandTokens.indexOf(INPUT_FILENAME_TOKEN)
        if (placeholderIndex >= 0) commandTokens.subList(0, placeholderIndex) else commandTokens
    } else {
        commandTokens
    }

private fun GeneratorEditorState.postfixTokens(): List<String> =
    if (inputMethod == Piper.CommandInvocation.InputMethod.FILENAME) {
        val placeholderIndex = commandTokens.indexOf(INPUT_FILENAME_TOKEN)
        if (placeholderIndex >= 0 && placeholderIndex + 1 <= commandTokens.size - 1) {
            commandTokens.subList(placeholderIndex + 1, commandTokens.size)
        } else emptyList()
    } else {
        emptyList()
    }

private fun Piper.MinimalTool.buildTokenList(): MutableList<String> {
    val tokens = mutableListOf<String>()
    tokens += cmd.prefixList
    if (cmd.inputMethod == Piper.CommandInvocation.InputMethod.FILENAME) {
        tokens += INPUT_FILENAME_TOKEN
        tokens += cmd.postfixList
    }
    return tokens
}



private fun GeneratorEditorState.deepCopy(): GeneratorEditorState {
    val copy = GeneratorEditorState()
    copy.modelIndex = modelIndex
    copy.name = name
    copy.enabled = enabled
    copy.tags = tags.map { it }.toMutableList()
    copy.templateId = templateId
    copy.commandTokens = commandTokens.map { it }.toMutableList()
    copy.inputMethod = inputMethod
    copy.parameters = parameters.map { it.copy() }.toMutableList()
    copy.passHeaders = passHeaders
    copy.exitCodes = exitCodes.map { it }.toMutableList()
    copy.dependencies = dependencies.map { it }.toMutableList()
    return copy
}

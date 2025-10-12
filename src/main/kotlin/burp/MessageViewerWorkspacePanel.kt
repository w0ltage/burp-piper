package burp

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
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
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

private data class ViewerTemplateOption(val id: String?, val label: String, val description: String)

private data class ViewerEditorState(
    var modelIndex: Int? = null,
    var name: String = "",
    var enabled: Boolean = false,
    var scope: Piper.MinimalTool.Scope = Piper.MinimalTool.Scope.REQUEST_RESPONSE,
    var command: Piper.CommandInvocation = Piper.CommandInvocation.getDefaultInstance(),
    var filter: Piper.MessageMatch? = null,
    var usesColors: Boolean = false,
    var tags: MutableList<String> = mutableListOf(),
    var dependencies: MutableList<String> = mutableListOf(),
    var templateId: String? = null,
    var overwrite: Boolean = false,
    var applyWithListener: Boolean = false,
)

private fun Piper.MessageViewer.toEditorState(index: Int): ViewerEditorState {
    val minimal = this.common
    val command = minimal.cmd
    val tags = command.extractTags().toMutableList()
    val dependencies = command.extractDependencies().toMutableList()
    return ViewerEditorState(
        modelIndex = index,
        name = minimal.name,
        enabled = minimal.enabled,
        scope = minimal.scope,
        command = command,
        filter = minimal.filter,
        usesColors = this.usesColors,
        tags = tags,
        dependencies = dependencies,
    )
}

private fun Piper.Commentator.toEditorState(index: Int): ViewerEditorState {
    val minimal = this.common
    val command = minimal.cmd
    val tags = command.extractTags().toMutableList()
    val dependencies = command.extractDependencies().toMutableList()
    return ViewerEditorState(
        modelIndex = index,
        name = minimal.name,
        enabled = minimal.enabled,
        scope = minimal.scope,
        command = command,
        filter = minimal.filter,
        tags = tags,
        dependencies = dependencies,
        overwrite = this.overwrite,
        applyWithListener = this.applyWithListener,
    )
}

private fun ViewerEditorState.toMessageViewer(): Piper.MessageViewer {
    val minimal = Piper.MinimalTool.newBuilder().apply {
        name = this@toMessageViewer.name
        if (enabled) enabled = true
        scope = this@toMessageViewer.scope
        if (filter != null) filter = this@toMessageViewer.filter
        cmd = this@toMessageViewer.command.toBuilder()
            .clearRequiredInPath()
            .addAllRequiredInPath(mergeDependenciesAndTags(dependencies, tags))
            .build()
    }.build()
    return Piper.MessageViewer.newBuilder().apply {
        common = minimal
        if (usesColors) usesColors = true
    }.build()
}

private fun ViewerEditorState.toCommentator(): Piper.Commentator {
    val minimal = Piper.MinimalTool.newBuilder().apply {
        name = this@toCommentator.name
        if (enabled) enabled = true
        scope = this@toCommentator.scope
        if (filter != null) filter = this@toCommentator.filter
        cmd = this@toCommentator.command.toBuilder()
            .clearRequiredInPath()
            .addAllRequiredInPath(mergeDependenciesAndTags(dependencies, tags))
            .build()
    }.build()
    return Piper.Commentator.newBuilder().apply {
        common = minimal
        if (overwrite) overwrite = true
        if (applyWithListener) applyWithListener = true
    }.build()
}

private class ViewerListModel<T>(
    private val backing: DefaultListModel<T>,
    private val extractor: (T) -> Piper.MinimalTool,
) : javax.swing.AbstractListModel<T>(), ListDataListener {
    private val indices = mutableListOf<Int>()
    private var query: String = ""

    init {
        backing.addListDataListener(this)
        rebuild()
    }

    override fun getSize(): Int = indices.size

    override fun getElementAt(index: Int): T = backing.getElementAt(indices[index])

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
            val tool = extractor(backing.getElementAt(i))
            if (matches(tool)) {
                indices += i
            }
        }
        fireContentsChanged(this, 0, if (indices.isEmpty()) 0 else indices.size - 1)
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

private class ViewerListRenderer<T>(
    private val extractor: (T) -> Piper.MinimalTool,
) : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        @Suppress("UNCHECKED_CAST")
        val tool = value?.let { extractor(it as T) }
        if (tool != null) {
            val status = if (tool.enabled) "Enabled" else "Disabled"
            val tags = tool.cmd.extractTags()
            val suffix = if (tags.isEmpty()) "" else " – " + tags.joinToString(", ")
            text = tool.name + " [$status]" + suffix
        }
        return component
    }
}

private class OverviewPane : JPanel(BorderLayout()) {
    private val summary = JTextArea()

    init {
        border = EmptyBorder(12, 12, 12, 12)
        summary.isEditable = false
        summary.isOpaque = false
        summary.lineWrap = true
        summary.wrapStyleWord = true
        summary.border = null
        val title = JLabel("Workspace overview")
        title.font = title.font.deriveFont(title.font.style or java.awt.Font.BOLD)
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false
        container.add(title)
        container.add(Box.createVerticalStrut(8))
        container.add(summary)
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(container, BorderLayout.NORTH)
        add(wrapper, BorderLayout.CENTER)
    }

    fun update(state: ViewerEditorState?) {
        summary.text = if (state == null) {
            "Select or create a configuration to begin."
        } else {
            buildString {
                appendLine("Name: ${state.name.ifBlank { "(unnamed)" }}")
                appendLine("Status: ${if (state.enabled) "Enabled" else "Disabled"}")
                appendLine("Scope: ${state.scope.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)}")
                appendLine("Command: ${state.command.commandLine}")
                if (state.tags.isNotEmpty()) {
                    appendLine("Tags: ${state.tags.joinToString(", ")}")
                }
                if (state.dependencies.isNotEmpty()) {
                    appendLine("Required binaries: ${state.dependencies.joinToString(", ")}")
                }
                if (state.filter != null) {
                    appendLine("Filter: ${state.filter?.toHumanReadable(negation = false, hideParentheses = true)}")
                } else {
                    appendLine("Filter: (none)")
                }
            }
        }
    }
}

private class NameHeaderPanel(
    private val onChange: () -> Unit,
) : JPanel() {
    val nameField = JTextField()
    private val enabledToggle = JToggleButton("Enabled")
    val tagsField = JTextField()
    private val templateCombo = JComboBox<ViewerTemplateOption>()
    val scopeCombo = JComboBox(ScopeOption.values())

    init {
        layout = GridBagLayout()
        border = EmptyBorder(12, 12, 12, 12)
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 4)
        }
        add(JLabel("Name"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        add(nameField, constraints)

        constraints.gridx = 2
        constraints.weightx = 0.0
        add(enabledToggle, constraints)

        constraints.gridx = 0
        constraints.gridy = 1
        add(JLabel("Tags"), constraints)
        constraints.gridx = 1
        constraints.gridwidth = 2
        add(tagsField, constraints)

        constraints.gridy = 2
        constraints.gridx = 0
        constraints.gridwidth = 1
        add(JLabel("Template"), constraints)
        constraints.gridx = 1
        add(templateCombo, constraints)

        constraints.gridx = 2
        add(scopeCombo, constraints)

        nameField.document.addDocumentListener(WorkspaceDocumentListener { onChange() })
        tagsField.document.addDocumentListener(WorkspaceDocumentListener { onChange() })
        enabledToggle.addActionListener { onChange() }
        scopeCombo.addActionListener { onChange() }
        templateCombo.addActionListener { onChange() }
    }

    fun setTemplates(selected: String?) {
        val options = mutableListOf(
            ViewerTemplateOption(null, "Custom", ""),
            ViewerTemplateOption("json", "JSON formatter", "jq based pretty-printer"),
            ViewerTemplateOption("asn1", "ASN.1 decoder", "openssl asn1parse"),
            ViewerTemplateOption("gzip", "GZIP inflator", "gzip --decompress"),
        )
        templateCombo.model = DefaultComboBoxModel(options.toTypedArray())
        val index = options.indexOfFirst { it.id == selected }
        templateCombo.selectedIndex = if (index >= 0) index else 0
    }

    fun snapshot(): HeaderSnapshot = HeaderSnapshot(
        name = nameField.text,
        enabled = enabledToggle.isSelected,
        tags = tagsField.text.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) },
        templateId = (templateCombo.selectedItem as? ViewerTemplateOption)?.id,
        scope = (scopeCombo.selectedItem as ScopeOption).scope,
    )

    fun apply(state: ViewerEditorState?) {
        if (state == null) {
            nameField.text = ""
            tagsField.text = ""
            enabledToggle.isSelected = false
            scopeCombo.selectedItem = ScopeOption.from(state?.scope)
            setTemplates(null)
            return
        }
        nameField.text = state.name
        tagsField.text = state.tags.joinToString(", ")
        enabledToggle.isSelected = state.enabled
        scopeCombo.selectedItem = ScopeOption.from(state.scope)
        setTemplates(state.templateId)
    }

    fun setEnabledState(enabled: Boolean) {
        nameField.isEnabled = enabled
        tagsField.isEnabled = enabled
    }
}

private data class HeaderSnapshot(
    val name: String,
    val enabled: Boolean,
    val tags: List<String>,
    val templateId: String?,
    val scope: Piper.MinimalTool.Scope,
)

private enum class ScopeOption(val scope: Piper.MinimalTool.Scope, val label: String) {
    BOTH(Piper.MinimalTool.Scope.REQUEST_RESPONSE, "Requests & Responses"),
    REQUEST(Piper.MinimalTool.Scope.REQUEST_ONLY, "Requests"),
    RESPONSE(Piper.MinimalTool.Scope.RESPONSE_ONLY, "Responses"),
    ;

    override fun toString(): String = label

    companion object {
        fun from(scope: Piper.MinimalTool.Scope?): ScopeOption = when (scope) {
            Piper.MinimalTool.Scope.REQUEST_ONLY -> REQUEST
            Piper.MinimalTool.Scope.RESPONSE_ONLY -> RESPONSE
            else -> BOTH
        }
    }
}

private class FilterTab(
    parent: Component?,
    onChange: () -> Unit,
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

    fun setFilter(filter: Piper.MessageMatch?) {
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

private class MessageMatchInlinePanel(parent: Component?) : JPanel(BorderLayout()) {
    private var value: Piper.MessageMatch? = null
    private val editButton = JButton("Edit filter")
    private val removeButton = JButton("Remove filter")
    private val cardPanel = JPanel(CardLayout())
    private val summary = JTextArea()
    private val listeners = mutableListOf<() -> Unit>()

    init {
        border = EmptyBorder(8, 8, 8, 8)
        summary.isEditable = false
        summary.isOpaque = false
        summary.lineWrap = true
        summary.wrapStyleWord = true
        val summaryPanel = JPanel(BorderLayout())
        summaryPanel.add(summary, BorderLayout.CENTER)
        val buttonRow = JPanel()
        buttonRow.layout = BoxLayout(buttonRow, BoxLayout.X_AXIS)
        buttonRow.add(editButton)
        buttonRow.add(Box.createRigidArea(Dimension(4, 0)))
        buttonRow.add(removeButton)
        buttonRow.add(Box.createHorizontalGlue())
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.add(summaryPanel)
        container.add(Box.createVerticalStrut(8))
        container.add(buttonRow)
        cardPanel.add(container, "summary")
        add(cardPanel, BorderLayout.NORTH)

        editButton.addActionListener {
            val host = parent ?: this
            val edited = MessageMatchDialog(value ?: Piper.MessageMatch.getDefaultInstance(), true, host).showGUI()
            if (edited != null) {
                value = edited
                updateSummary()
                listeners.forEach { it.invoke() }
            }
        }
        removeButton.addActionListener {
            value = null
            updateSummary()
            listeners.forEach { it.invoke() }
        }
        updateSummary()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    fun setValue(newValue: Piper.MessageMatch?) {
        value = newValue
        updateSummary()
    }

    fun toMessageMatch(): Piper.MessageMatch? = value

    private fun updateSummary() {
        summary.text = value?.toHumanReadable(negation = false, hideParentheses = true)
            ?: "No filter defined. Click Edit filter to add rules."
        removeButton.isEnabled = value != null
    }
}

private class CommandTab(parent: Component?, onChange: () -> Unit) : JPanel(BorderLayout()) {
    private val window: Window = when (parent) {
        is Window -> parent
        is Component -> SwingUtilities.getWindowAncestor(parent) as? Window ?: JOptionPane.getRootFrame()
        else -> JOptionPane.getRootFrame()
    }
    private val commandEditor = CollapsedCommandInvocationWidget(window, Piper.CommandInvocation.getDefaultInstance(), CommandInvocationPurpose.SELF_FILTER)
    private val ansiCheck = JCheckBox("Uses ANSI (color) escape sequences")
    private val dependenciesField = JTextField()
    private val tagsField = JTextField()

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

        cs.gridy++
        content.add(ansiCheck, cs)
        cs.gridy++
        val dependenciesLabel = JLabel("Binaries required in PATH (comma separated)")
        content.add(dependenciesLabel, cs)
        cs.gridy++
        content.add(dependenciesField, cs)
        cs.gridy++
        val tagsLabel = JLabel("Command tags (comma separated)")
        content.add(tagsLabel, cs)
        cs.gridy++
        content.add(tagsField, cs)

        commandEditor.addChangeListener(object : ChangeListener<Piper.CommandInvocation> {
            override fun valueChanged(value: Piper.CommandInvocation?) {
                onChange()
            }
        })
        ansiCheck.addChangeListener { onChange() }
        dependenciesField.document.addDocumentListener(WorkspaceDocumentListener { onChange() })
        tagsField.document.addDocumentListener(WorkspaceDocumentListener { onChange() })

        add(JScrollPane(content), BorderLayout.CENTER)
    }

    fun setCommand(state: ViewerEditorState?) {
        commandEditor.value = state?.command ?: Piper.CommandInvocation.getDefaultInstance()
        ansiCheck.isSelected = state?.usesColors ?: false
        dependenciesField.text = state?.dependencies?.joinToString(", ") ?: ""
        tagsField.text = state?.tags?.joinToString(", ") ?: ""
    }

    fun snapshot(): CommandSnapshot {
        val command = commandEditor.value ?: Piper.CommandInvocation.getDefaultInstance()
        val dependencies = dependenciesField.text.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        val tags = tagsField.text.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        return CommandSnapshot(command, ansiCheck.isSelected, dependencies, tags)
    }
}

private data class CommandSnapshot(
    val command: Piper.CommandInvocation,
    val usesAnsi: Boolean,
    val dependencies: List<String>,
    val tags: List<String>,
)

private class ValidationHistoryTab(parent: Component?, supplier: () -> Piper.MinimalTool?) : JPanel(BorderLayout()) {
    private val historyPanel = WorkspaceHistoryPanel()
    private val validationPanel = WorkspaceValidationPanel(parent, supplier) { entry ->
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

private class CommentatorOptionsPanel(onChange: () -> Unit) : JPanel() {
    private val overwriteBox = JCheckBox("Overwrite existing comments")
    private val listenerBox = JCheckBox("Continuously apply to future messages")
    private val asyncBox = JCheckBox("Run asynchronously")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(12, 12, 12, 12)
        add(overwriteBox)
        add(listenerBox)
        add(asyncBox)
        overwriteBox.addChangeListener { onChange() }
        listenerBox.addChangeListener { onChange() }
        asyncBox.addChangeListener { onChange() }
        asyncBox.isEnabled = false
        asyncBox.toolTipText = "Asynchronous execution will be enabled in a future release."
    }

    fun apply(state: ViewerEditorState?) {
        overwriteBox.isSelected = state?.overwrite ?: false
        listenerBox.isSelected = state?.applyWithListener ?: false
    }

    fun snapshot(): Pair<Boolean, Boolean> = overwriteBox.isSelected to listenerBox.isSelected
}

class MessageViewerWorkspacePanel(
    private val model: DefaultListModel<Piper.MessageViewer>,
    parent: Component?,
    private val commentators: DefaultListModel<Piper.Commentator>,
    private val switchToCommentator: () -> Unit,
) : JPanel(BorderLayout()) {

    private val filterModel = ViewerListModel(model) { it.common }
    private val list = JList<Piper.MessageViewer>(filterModel)
    private val searchField = JTextField()
    private val header = NameHeaderPanel { markDirty() }
    private val overview = OverviewPane()
    private val filterTab = FilterTab(parent) { markDirty() }
    private val commandTab = CommandTab(parent) { markDirty() }
    private val validationTab = ValidationHistoryTab(parent) { collectMinimalToolForTest() }
    private val tabs = JTabbedPane()
    private val footer = JPanel()
    private val saveButton = JButton("Save")
    private val cancelButton = JButton("Cancel")
    private val convertButton = JButton("Convert to commentator")
    private var currentState: ViewerEditorState? = null
    private var loading = false

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ViewerListRenderer<Piper.MessageViewer> { it.common }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadSelection()
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val index = list.locationToIndex(e.point)
                    list.selectedIndex = index
                }
            }
        })
        list.componentPopupMenu = createContextMenu()

        searchField.toolTipText = "Search by name or tag"
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        val left = JPanel(BorderLayout())
        left.border = EmptyBorder(8, 8, 8, 8)
        val searchRow = JPanel(BorderLayout(4, 4))
        searchRow.add(JLabel("Search"), BorderLayout.WEST)
        searchRow.add(searchField, BorderLayout.CENTER)
        val addButton = JButton("+ New message viewer")
        addButton.addActionListener { createNew() }
        left.add(searchRow, BorderLayout.NORTH)
        left.add(JScrollPane(list), BorderLayout.CENTER)
        left.add(addButton, BorderLayout.SOUTH)

        tabs.addTab("Overview", overview)
        tabs.addTab("Filter", filterTab)
        tabs.addTab("Command", commandTab)
        tabs.addTab("Validation & Test", validationTab)

        footer.layout = BoxLayout(footer, BoxLayout.X_AXIS)
        saveButton.addActionListener { save() }
        cancelButton.addActionListener { resetToState() }
        convertButton.addActionListener { convertSelection() }
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
        footer.add(saveButton)
        footer.add(Box.createRigidArea(Dimension(4, 0)))
        footer.add(cancelButton)
        footer.add(Box.createHorizontalGlue())
        footer.add(convertButton)

        val right = JPanel(BorderLayout())
        right.add(header, BorderLayout.NORTH)
        right.add(tabs, BorderLayout.CENTER)
        right.add(footer, BorderLayout.SOUTH)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)
        split.dividerLocation = 280
        add(split, BorderLayout.CENTER)

        if (model.size() > 0) {
            list.selectedIndex = 0
        }
    }

    private fun applyFilter() {
        filterModel.updateFilter(searchField.text)
        if (filterModel.size == 0) {
            list.clearSelection()
            displayState(null)
        } else if (list.selectedIndex !in 0 until filterModel.size) {
            list.selectedIndex = 0
        }
    }

    private fun loadSelection() {
        val idx = list.selectedIndex
        if (idx < 0) {
            displayState(null)
            return
        }
        val backing = filterModel.backingIndex(idx)
        val viewer = model.getElementAt(backing)
        displayState(viewer.toEditorState(backing))
    }

    private fun displayState(state: ViewerEditorState?) {
        loading = true
        try {
            currentState = state?.copy()
            header.apply(state)
            header.setTemplates(state?.templateId)
            overview.update(state)
            filterTab.setFilter(state?.filter)
            commandTab.setCommand(state)
            validationTab.reset()
            val parameters = state?.command?.parameterList ?: emptyList()
            validationTab.updateParameters(parameters)
            saveButton.isEnabled = false
            cancelButton.isEnabled = false
            convertButton.isEnabled = state != null
        } finally {
            loading = false
        }
    }

    private fun createNew() {
        list.clearSelection()
        displayState(ViewerEditorState())
    }

    private fun collectStateFromUI(): ViewerEditorState? {
        val snapshot = header.snapshot()
        val commandSnapshot = commandTab.snapshot()
        val filter = filterTab.value()
        val current = currentState ?: ViewerEditorState()
        current.name = snapshot.name.trim()
        current.enabled = snapshot.enabled
        current.tags = commandSnapshot.tags.toMutableList()
        current.dependencies = commandSnapshot.dependencies.toMutableList()
        current.scope = snapshot.scope
        current.command = commandSnapshot.command
        current.filter = filter
        current.usesColors = commandSnapshot.usesAnsi
        current.templateId = snapshot.templateId
        overview.update(current)
        return current
    }

    private fun collectMinimalToolForTest(): Piper.MinimalTool? {
        val state = collectStateFromUI() ?: return null
        if (state.name.isBlank()) {
            state.name = "Untitled viewer"
        }
        return state.toMessageViewer().common
    }

    private fun save() {
        val state = collectStateFromUI() ?: return
        if (state.name.isBlank()) {
            JOptionPane.showMessageDialog(this, "Name cannot be empty", "Validation", JOptionPane.WARNING_MESSAGE)
            return
        }
        val index = state.modelIndex
        val item = state.toMessageViewer()
        if (index == null) {
            model.addElement(item)
            filterModel.invalidate()
            val newIndex = model.size() - 1
            val filtered = filterModel.filteredIndexOf(newIndex)
            if (filtered >= 0) {
                list.selectedIndex = filtered
            }
        } else {
            model.setElementAt(item, index)
            filterModel.invalidate()
            val filtered = filterModel.filteredIndexOf(index)
            if (filtered >= 0) {
                list.selectedIndex = filtered
            }
        }
        displayState(item.toEditorState(state.modelIndex ?: model.size() - 1))
    }

    private fun resetToState() {
        displayState(currentState)
    }

    private fun convertSelection() {
        val state = collectStateFromUI() ?: return
        val commentator = state.toCommentator()
        commentators.addElement(commentator)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            StringSelection("Viewer \"${state.name}\" converted to commentator."),
            null,
        )
        switchToCommentator()
    }

    private fun markDirty() {
        if (!loading) {
            saveButton.isEnabled = true
            cancelButton.isEnabled = true
        }
    }

    private fun createContextMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(JMenuItem(object : AbstractAction("Clone") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = list.selectedIndex
                if (idx < 0) return
                val backing = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backing)
                val clone = tool.toBuilder().setCommon(tool.common.toBuilder().setName(tool.common.name + " copy")).build()
                model.addElement(clone)
                filterModel.invalidate()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Toggle enabled") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = list.selectedIndex
                if (idx < 0) return
                val backing = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backing)
                model.setElementAt(tool.buildEnabled(!tool.common.enabled), backing)
                filterModel.invalidate()
                loadSelection()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Delete") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = list.selectedIndex
                if (idx < 0) return
                val backing = filterModel.backingIndex(idx)
                if (JOptionPane.showConfirmDialog(
                        list,
                        "Remove message viewer \"${model.getElementAt(backing).common.name}\"?",
                        "Confirm removal",
                        JOptionPane.OK_CANCEL_OPTION,
                    ) == JOptionPane.OK_OPTION
                ) {
                    model.removeElementAt(backing)
                    filterModel.invalidate()
                    displayState(null)
                }
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Export to YAML") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = list.selectedIndex
                if (idx < 0) return
                val backing = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backing)
                exportMinimalTool(tool.common)
            }
        }))
        return menu
    }

    private fun exportMinimalTool(tool: Piper.MinimalTool) {
        val dump = org.snakeyaml.engine.v1.api.Dump(org.snakeyaml.engine.v1.api.DumpSettingsBuilder().build())
        val yaml = dump.dumpToString(tool.toMap())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(yaml), null)
        JOptionPane.showMessageDialog(this, "Message viewer YAML copied to clipboard")
    }
}

class CommentatorWorkspacePanel(
    private val model: DefaultListModel<Piper.Commentator>,
    parent: Component?,
) : JPanel(BorderLayout()) {

    private val filterModel = ViewerListModel(model) { it.common }
    private val list = JList<Piper.Commentator>(filterModel)
    private val searchField = JTextField()
    private val header = NameHeaderPanel { markDirty() }
    private val overview = OverviewPane()
    private val filterTab = FilterTab(parent) { markDirty() }
    private val commandTab = CommandTab(parent) { markDirty() }
    private val validationTab = ValidationHistoryTab(parent) { collectMinimalToolForTest() }
    private val commentOptions = CommentatorOptionsPanel { markDirty() }
    private val tabs = JTabbedPane()
    private val footer = JPanel()
    private val saveButton = JButton("Save")
    private val cancelButton = JButton("Cancel")
    private var currentState: ViewerEditorState? = null
    private var loading = false

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ViewerListRenderer<Piper.Commentator> { it.common }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadSelection()
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val index = list.locationToIndex(e.point)
                    list.selectedIndex = index
                }
            }
        })
        list.componentPopupMenu = createContextMenu()

        searchField.toolTipText = "Search by name or tag"
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        val left = JPanel(BorderLayout())
        left.border = EmptyBorder(8, 8, 8, 8)
        val searchRow = JPanel(BorderLayout(4, 4))
        searchRow.add(JLabel("Search"), BorderLayout.WEST)
        searchRow.add(searchField, BorderLayout.CENTER)
        val addButton = JButton("+ New commentator")
        addButton.addActionListener { createNew() }
        left.add(searchRow, BorderLayout.NORTH)
        left.add(JScrollPane(list), BorderLayout.CENTER)
        left.add(addButton, BorderLayout.SOUTH)

        tabs.addTab("Overview", overview)
        tabs.addTab("Filter", filterTab)
        tabs.addTab("Command", commandTab)
        tabs.addTab("Validation & Test", validationTab)
        tabs.addTab("History", WorkspaceHistoryPanel())

        header.scopeCombo.isEnabled = false

        footer.layout = BoxLayout(footer, BoxLayout.X_AXIS)
        saveButton.addActionListener { save() }
        cancelButton.addActionListener { resetToState() }
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
        footer.add(saveButton)
        footer.add(Box.createRigidArea(Dimension(4, 0)))
        footer.add(cancelButton)
        footer.add(Box.createHorizontalGlue())
        footer.add(Box.createRigidArea(Dimension(8, 0)))

        val right = JPanel(BorderLayout())
        right.add(header, BorderLayout.NORTH)
        val body = JPanel(BorderLayout())
        body.add(tabs, BorderLayout.CENTER)
        body.add(commentOptions, BorderLayout.SOUTH)
        right.add(body, BorderLayout.CENTER)
        right.add(footer, BorderLayout.SOUTH)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)
        split.dividerLocation = 280
        add(split, BorderLayout.CENTER)

        if (model.size() > 0) {
            list.selectedIndex = 0
        }
    }

    private fun applyFilter() {
        filterModel.updateFilter(searchField.text)
        if (filterModel.size == 0) {
            list.clearSelection()
            displayState(null)
        } else if (list.selectedIndex !in 0 until filterModel.size) {
            list.selectedIndex = 0
        }
    }

    private fun loadSelection() {
        val idx = list.selectedIndex
        if (idx < 0) {
            displayState(null)
            return
        }
        val backing = filterModel.backingIndex(idx)
        val item = model.getElementAt(backing)
        displayState(item.toEditorState(backing))
    }

    private fun displayState(state: ViewerEditorState?) {
        loading = true
        try {
            currentState = state?.copy()
            header.apply(state)
            overview.update(state)
            filterTab.setFilter(state?.filter)
            commandTab.setCommand(state)
            commentOptions.apply(state)
            validationTab.reset()
            val parameters = state?.command?.parameterList ?: emptyList()
            validationTab.updateParameters(parameters)
            saveButton.isEnabled = false
            cancelButton.isEnabled = false
            // no conversion back from commentator workspace
        } finally {
            loading = false
        }
    }

    private fun createNew() {
        list.clearSelection()
        displayState(ViewerEditorState())
    }

    private fun collectStateFromUI(): ViewerEditorState? {
        val snapshot = header.snapshot()
        val commandSnapshot = commandTab.snapshot()
        val filter = filterTab.value()
        val state = currentState ?: ViewerEditorState()
        state.name = snapshot.name.trim()
        state.enabled = snapshot.enabled
        state.tags = commandSnapshot.tags.toMutableList()
        state.dependencies = commandSnapshot.dependencies.toMutableList()
        state.command = commandSnapshot.command
        state.filter = filter
        val (overwrite, applyListener) = commentOptions.snapshot()
        state.overwrite = overwrite
        state.applyWithListener = applyListener
        overview.update(state)
        return state
    }

    private fun collectMinimalToolForTest(): Piper.MinimalTool? {
        val state = collectStateFromUI() ?: return null
        if (state.name.isBlank()) {
            state.name = "Untitled commentator"
        }
        return state.toCommentator().common
    }

    private fun save() {
        val state = collectStateFromUI() ?: return
        if (state.name.isBlank()) {
            JOptionPane.showMessageDialog(this, "Name cannot be empty", "Validation", JOptionPane.WARNING_MESSAGE)
            return
        }
        val index = state.modelIndex
        val item = state.toCommentator()
        if (index == null) {
            model.addElement(item)
            filterModel.invalidate()
            val newIndex = model.size() - 1
            val filtered = filterModel.filteredIndexOf(newIndex)
            if (filtered >= 0) {
                list.selectedIndex = filtered
            }
        } else {
            model.setElementAt(item, index)
            filterModel.invalidate()
            val filtered = filterModel.filteredIndexOf(index)
            if (filtered >= 0) {
                list.selectedIndex = filtered
            }
        }
        displayState(item.toEditorState(state.modelIndex ?: model.size() - 1))
    }

    private fun resetToState() {
        displayState(currentState)
    }

    private fun markDirty() {
        if (!loading) {
            saveButton.isEnabled = true
            cancelButton.isEnabled = true
        }
    }

    private fun createContextMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(JMenuItem(object : AbstractAction("Clone") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = list.selectedIndex
                if (idx < 0) return
                val backing = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backing)
                val clone = tool.toBuilder().setCommon(tool.common.toBuilder().setName(tool.common.name + " copy")).build()
                model.addElement(clone)
                filterModel.invalidate()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Toggle enabled") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = list.selectedIndex
                if (idx < 0) return
                val backing = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backing)
                model.setElementAt(tool.buildEnabled(!tool.common.enabled), backing)
                filterModel.invalidate()
                loadSelection()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Delete") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = list.selectedIndex
                if (idx < 0) return
                val backing = filterModel.backingIndex(idx)
                if (JOptionPane.showConfirmDialog(
                        list,
                        "Remove commentator \"${model.getElementAt(backing).common.name}\"?",
                        "Confirm removal",
                        JOptionPane.OK_CANCEL_OPTION,
                    ) == JOptionPane.OK_OPTION
                ) {
                    model.removeElementAt(backing)
                    filterModel.invalidate()
                    displayState(null)
                }
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Export to YAML") {
            override fun actionPerformed(e: ActionEvent?) {
                val idx = list.selectedIndex
                if (idx < 0) return
                val backing = filterModel.backingIndex(idx)
                val tool = model.getElementAt(backing)
                exportMinimalTool(tool.common)
            }
        }))
        return menu
    }

    private fun exportMinimalTool(tool: Piper.MinimalTool) {
        val dump = org.snakeyaml.engine.v1.api.Dump(org.snakeyaml.engine.v1.api.DumpSettingsBuilder().build())
        val yaml = dump.dumpToString(tool.toMap())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(yaml), null)
        JOptionPane.showMessageDialog(this, "Commentator YAML copied to clipboard")
    }
}

fun createMessageViewerManager(
    model: DefaultListModel<Piper.MessageViewer>,
    parent: Component?,
    commentatorModel: DefaultListModel<Piper.Commentator>,
    switchToCommentator: () -> Unit,
): Component = MessageViewerWorkspacePanel(model, parent, commentatorModel, switchToCommentator)

fun createCommentatorManager(
    model: DefaultListModel<Piper.Commentator>,
    parent: Component?,
): Component = CommentatorWorkspacePanel(model, parent)


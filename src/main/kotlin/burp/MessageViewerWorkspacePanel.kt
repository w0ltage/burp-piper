package burp

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
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
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

private data class ViewerTemplateOption(val id: String?, val label: String, val description: String) {
    override fun toString(): String = label
}

private data class ViewerEditorState(
    var modelIndex: Int? = null,
    var name: String = "",
    var enabled: Boolean = false,
    var scope: Piper.MinimalTool.Scope = Piper.MinimalTool.Scope.REQUEST_RESPONSE,
    var command: Piper.CommandInvocation = Piper.CommandInvocation.getDefaultInstance(),
    var filter: Piper.MessageMatch? = null,
    var usesColors: Boolean = false,
    var dependencies: MutableList<String> = mutableListOf(),
    var templateId: String? = null,
    var overwrite: Boolean = false,
    var applyWithListener: Boolean = false,
)

private fun Piper.MessageViewer.toEditorState(index: Int): ViewerEditorState {
    val minimal = this.common
    val command = minimal.cmd
    val dependencies = command.extractDependencies().toMutableList()
    return ViewerEditorState(
        modelIndex = index,
        name = minimal.name,
        enabled = minimal.enabled,
        scope = minimal.scope,
        command = command,
        filter = minimal.filter,
        usesColors = this.usesColors,
        dependencies = dependencies,
    )
}

private fun Piper.Commentator.toEditorState(index: Int): ViewerEditorState {
    val minimal = this.common
    val command = minimal.cmd
    val dependencies = command.extractDependencies().toMutableList()
    return ViewerEditorState(
        modelIndex = index,
        name = minimal.name,
        enabled = minimal.enabled,
        scope = minimal.scope,
        command = command,
        filter = minimal.filter,
        dependencies = dependencies,
        overwrite = this.overwrite,
        applyWithListener = this.applyWithListener,
    )
}

private fun ViewerEditorState.toMessageViewer(): Piper.MessageViewer {
    val minimal = Piper.MinimalTool.newBuilder().apply {
        name = this@toMessageViewer.name
        if (this@toMessageViewer.enabled) enabled = true
        scope = this@toMessageViewer.scope
        this@toMessageViewer.filter?.let { filter = it }
        cmd = this@toMessageViewer.command.toBuilder()
            .clearRequiredInPath()
            .addAllRequiredInPath(dependencies)
            .build()
    }.build()
    return Piper.MessageViewer.newBuilder().apply {
        common = minimal
        usesColors = this@toMessageViewer.usesColors
    }.build()
}

private fun ViewerEditorState.toCommentator(): Piper.Commentator {
    val minimal = Piper.MinimalTool.newBuilder().apply {
        name = this@toCommentator.name
        if (this@toCommentator.enabled) enabled = true
        scope = this@toCommentator.scope
        this@toCommentator.filter?.let { filter = it }
        cmd = this@toCommentator.command.toBuilder()
            .clearRequiredInPath()
            .addAllRequiredInPath(dependencies)
            .build()
    }.build()
    return Piper.Commentator.newBuilder().apply {
        common = minimal
        if (overwrite) overwrite = true
        if (applyWithListener) applyWithListener = true
    }.build()
}

private fun ViewerEditorState.toCommandState(): WorkspaceCommandState = WorkspaceCommandState(
    command = command,
    usesAnsi = usesColors,
    dependencies = dependencies,
    passHeaders = command.passHeaders,
)

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
        val haystack = tool.name.lowercase()
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
            text = tool.name + " [$status]"
        }
        return component
    }
}

private fun renderViewerOverview(state: ViewerEditorState): String = buildString {
    appendLine("Name: ${state.name.ifBlank { "(unnamed)" }}")
    appendLine("Status: ${if (state.enabled) "Enabled" else "Disabled"}")
    appendLine("Scope: ${state.scope.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)}")
    appendLine("Command: ${state.command.commandLine}")
    if (state.dependencies.isNotEmpty()) {
        appendLine("Required binaries: ${state.dependencies.joinToString(", ")}")
    }
    if (state.filter != null) {
        appendLine("Filter: ${state.filter?.toHumanReadable(negation = false, hideParentheses = true)}")
    } else {
        appendLine("Filter: (none)")
    }
}

private fun createViewerOverviewPanel(): WorkspaceOverviewPanel<ViewerEditorState> =
    WorkspaceOverviewPanel(
        title = "Workspace overview",
        emptyText = "Select or create a configuration to begin.",
    ) { state ->
        renderViewerOverview(state)
    }

private class ViewerHeaderPanel(
    onChange: () -> Unit,
) : WorkspaceHeaderPanel<ViewerTemplateOption>(
    templateLabel = "Template",
    onChange = onChange,
) {

    private var templates: List<ViewerTemplateOption> = emptyList()

    init {
        updateTemplates(null)
    }

    fun apply(state: ViewerEditorState?) {
        val selectedTemplate = updateTemplates(state?.templateId)
        setValues(
            WorkspaceHeaderValues(
                name = state?.name.orEmpty(),
                enabled = state?.enabled ?: false,
                template = selectedTemplate,
                scope = state?.scope,
            ),
        )
    }

    fun snapshot(): HeaderSnapshot {
        val values = readValues()
        val selectedTemplate = values.template ?: templates.firstOrNull()
        return HeaderSnapshot(
            name = values.name,
            enabled = values.enabled,
            templateId = selectedTemplate?.id,
            scope = values.scope ?: Piper.MinimalTool.Scope.REQUEST_RESPONSE,
        )
    }

    private fun updateTemplates(selectedId: String?): ViewerTemplateOption? {
        templates = listOf(
            ViewerTemplateOption(null, "Custom", ""),
            ViewerTemplateOption("json", "JSON formatter", "jq based pretty-printer"),
            ViewerTemplateOption("asn1", "ASN.1 decoder", "openssl asn1parse"),
            ViewerTemplateOption("gzip", "GZIP inflator", "gzip --decompress"),
        )
        val selected = templates.firstOrNull { it.id == selectedId } ?: templates.firstOrNull()
        withTemplateChangeSuppressed {
            templateCombo.model = DefaultComboBoxModel(templates.toTypedArray())
            when {
                selected != null -> templateCombo.selectedItem = selected
                templateCombo.itemCount > 0 -> templateCombo.selectedIndex = 0
                else -> templateCombo.selectedIndex = -1
            }
        }
        return selected
    }
}

private data class HeaderSnapshot(
    val name: String,
    val enabled: Boolean,
    val templateId: String?,
    val scope: Piper.MinimalTool.Scope,
)

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
    private val saveButton = JButton("Save")
    private val cancelButton = JButton("Cancel")
    private val convertButton = JButton("Convert to commentator")
    private val header = ViewerHeaderPanel { markDirty() }
    private val overview = createViewerOverviewPanel()
    private val filterTab = WorkspaceFilterPanel(parent) { markDirty() }
    private val commandTab = WorkspaceCommandPanel(parent, onChange = { markDirty() })
    private val validationTab = WorkspaceValidationHistoryPanel(parent) { collectMinimalToolForTest() }
    private val tabs = JTabbedPane()
    private val footer = JPanel()
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

        searchField.toolTipText = "Search by name"
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        filterTab.addChangeListener { filter ->
            if (!loading) {
                currentState = currentState?.also { it.filter = filter }
                currentState?.let { overview.display(it) }
            }
        }

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
            overview.display(state)
            filterTab.display(state?.filter)
            commandTab.display(state?.toCommandState())
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
        current.enabled = header.enabledToggle.isSelected
        current.dependencies = commandSnapshot.dependencies.toMutableList()
        current.scope = snapshot.scope
        current.command = commandSnapshot.command
        current.filter = filter
        current.usesColors = commandTab.isAnsiSelected()
        current.templateId = snapshot.templateId
        overview.display(current)
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
    private val saveButton = JButton("Save")
    private val cancelButton = JButton("Cancel")
    private val header = ViewerHeaderPanel { markDirty() }
    private val overview = createViewerOverviewPanel()
    private val filterTab = WorkspaceFilterPanel(parent) { markDirty() }
    private val commandTab = WorkspaceCommandPanel(parent, onChange = { markDirty() })
    private val validationTab = WorkspaceValidationHistoryPanel(parent) { collectMinimalToolForTest() }
    private val commentOptions = CommentatorOptionsPanel { markDirty() }
    private val tabs = JTabbedPane()
    private val footer = JPanel()
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

        searchField.toolTipText = "Search by name"
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

        header.setScopeEnabled(false)

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
            overview.display(state)
            filterTab.display(state?.filter)
            commandTab.display(state?.toCommandState())
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
        state.dependencies = commandSnapshot.dependencies.toMutableList()
        state.command = commandSnapshot.command
        state.usesColors = commandTab.isAnsiSelected()
        state.filter = filter
        val (overwrite, applyListener) = commentOptions.snapshot()
        state.overwrite = overwrite
        state.applyWithListener = applyListener
        overview.display(state)
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


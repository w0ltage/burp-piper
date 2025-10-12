package burp

import org.snakeyaml.engine.v1.api.Dump
import org.snakeyaml.engine.v1.api.DumpSettingsBuilder
import org.snakeyaml.engine.v1.api.Load
import org.snakeyaml.engine.v1.api.LoadSettingsBuilder
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.AbstractButton
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.Box
import javax.swing.BoxLayout
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
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import javax.swing.event.ListSelectionListener
import javax.swing.text.JTextComponent
import kotlin.math.max

private data class MinimalToolEditorConfig(
    val showPassHeaders: Boolean = true,
    val showScope: Boolean = false,
    val showFilter: Boolean = true,
    val purpose: CommandInvocationPurpose = CommandInvocationPurpose.SELF_FILTER,
)

private interface ToolEditor<T> {
    val component: Component
    fun display(value: T?)
    fun buildUpdated(): T?
    fun setDirtyListener(listener: () -> Unit)
}

private class SimpleDocumentCallback(private val onChange: () -> Unit) : DocumentListener {
    override fun insertUpdate(e: DocumentEvent?) = onChange()
    override fun removeUpdate(e: DocumentEvent?) = onChange()
    override fun changedUpdate(e: DocumentEvent?) = onChange()
}

private class FilteredMinimalToolListModel<T>(
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

    fun backingIndex(filteredIndex: Int): Int = indices[filteredIndex]
    fun filteredIndexOf(backingIndex: Int): Int = indices.indexOf(backingIndex)

    fun updateFilter(query: String) {
        this.query = query.lowercase()
        rebuild()
    }

    fun invalidate() {
        rebuild()
    }

    private fun rebuild() {
        indices.clear()
        for (i in 0 until backing.size()) {
            val value = backing.getElementAt(i)
            if (matches(value)) {
                indices += i
            }
        }
        val last = if (indices.isEmpty()) 0 else indices.size - 1
        fireContentsChanged(this, 0, last)
    }

    private fun matches(value: T): Boolean {
        if (query.isBlank()) return true
        val tool = extractor(value)
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

private class MinimalToolListCellRenderer<T>(
    private val extractor: (T) -> Piper.MinimalTool,
) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val tool = value?.let { extractor(it as T) }
        if (tool != null) {
            val status = if (tool.enabled) "Enabled" else "Disabled"
            val tags = tool.cmd.extractTags()
            val tagSuffix = if (tags.isEmpty()) "" else " – " + tags.joinToString(", ")
            text = tool.name + " [$status]" + tagSuffix
        }
        return component
    }
}

private abstract class MinimalToolInlineEditor<T>(
    private val parent: Component?,
    private val config: MinimalToolEditorConfig,
) : JPanel(BorderLayout()), ToolEditor<T> {

    private val cards = JPanel(CardLayout())
    private val placeholder = JPanel(BorderLayout())
    private val formPanel = JPanel(GridBagLayout())
    private val scrollPane = JScrollPane(formPanel)
    private var widget: MinimalToolWidget? = null
    private var dirtyListener: (() -> Unit)? = null

    protected var currentValue: T? = null
        private set

    init {
        placeholder.add(JLabel("Select an item to edit", SwingConstants.CENTER), BorderLayout.CENTER)
        cards.add(placeholder, "empty")
        cards.add(scrollPane, "form")
        add(cards, BorderLayout.CENTER)
    }

    override val component: Component
        get() = this

    override fun display(value: T?) {
        currentValue = value
        if (value == null) {
            (cards.layout as CardLayout).show(cards, "empty")
            formPanel.removeAll()
            widget = null
            return
        }
        formPanel.removeAll()
        val cs = GridBagConstraints().apply {
            fill = GridBagConstraints.BOTH
            anchor = GridBagConstraints.FIRST_LINE_START
            weightx = 1.0
            weighty = 1.0
            gridx = 0
            gridy = 0
            insets = Insets(2, 0, 2, 0)
        }
        widget = MinimalToolWidget(
            extractCommon(value),
            formPanel,
            cs,
            locateWindow(),
            config.showPassHeaders,
            config.purpose,
            config.showScope,
            config.showFilter,
        )
        widget?.addCommandChangeListener { notifyDirty() }
        addCustomFields(value, formPanel, cs)
        val fillerConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = cs.gridy + 1
            gridwidth = 4
            weightx = 1.0
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        }
        formPanel.add(JPanel().apply { isOpaque = false }, fillerConstraints)
        attachDirtyListeners(formPanel)
        (cards.layout as CardLayout).show(cards, "form")
        revalidate()
        repaint()
    }

    override fun buildUpdated(): T? {
        val value = currentValue ?: return null
        val tool = widget?.toMinimalTool() ?: return null
        return buildUpdatedValue(value, tool)
    }

    override fun setDirtyListener(listener: () -> Unit) {
        dirtyListener = listener
    }

    protected fun minimalToolWidget(): MinimalToolWidget? = widget

    protected fun notifyDirty() {
        dirtyListener?.invoke()
    }

    private fun locateWindow(): Window {
        val parentWindow = when (parent) {
            is Window -> parent
            is Component -> SwingUtilities.getWindowAncestor(parent)
            else -> null
        }
        return parentWindow ?: SwingUtilities.getWindowAncestor(this) ?: JOptionPane.getRootFrame()
    }

    private fun attachDirtyListeners(root: Container) {
        val stack = ArrayDeque<Component>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val component = stack.removeFirst()
            if ((component as? JComponent)?.getClientProperty("skip-dirty") == true) {
                if (component is Container) {
                    component.components.forEach(stack::add)
                }
                continue
            }
            when (component) {
                is JTextComponent -> component.document.addDocumentListener(SimpleDocumentCallback { notifyDirty() })
                is AbstractButton -> component.addActionListener { notifyDirty() }
                is JComboBox<*> -> component.addActionListener { notifyDirty() }
                is JSpinner -> component.addChangeListener { notifyDirty() }
            }
            if (component is Container) {
                component.components.forEach(stack::add)
            }
        }
    }

    protected abstract fun extractCommon(value: T): Piper.MinimalTool
    protected abstract fun addCustomFields(value: T, panel: Container, cs: GridBagConstraints)
    protected abstract fun buildUpdatedValue(original: T, common: Piper.MinimalTool): T
}

private class MinimalToolManagerPanel<T>(
    private val model: DefaultListModel<T>,
    parent: Component?,
    private val extractor: (T) -> Piper.MinimalTool,
    editorFactory: (Component?) -> ToolEditor<T>,
    private val newButtonLabel: String,
    private val defaultFactory: () -> T,
    private val cloneFactory: (T) -> T,
    private val toggleFactory: (T) -> T,
    private val toMap: (T) -> Map<String, Any>,
    private val fromMap: (Map<String, Any>) -> T,
) : JPanel(BorderLayout()), ClipboardOwner {

    private val filterModel = FilteredMinimalToolListModel(model, extractor)
    private val list = JList(filterModel)
    private val searchField = JTextField()
    private val editor = editorFactory(parent)
    private val saveButton = JButton("Save")
    private val cancelButton = JButton("Cancel")
    private val newButton = JButton(newButtonLabel)

    private var currentIndex: Int? = null
    private var dirty = false

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = MinimalToolListCellRenderer(extractor)
        list.addListSelectionListener(ListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                loadSelection()
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val index = list.locationToIndex(e.point)
                    if (index >= 0) {
                        list.selectedIndex = index
                    }
                }
            }
        })
        list.componentPopupMenu = createContextMenu()

        editor.setDirtyListener { markDirty() }

        searchField.toolTipText = "Search by name or tag"
        searchField.document.addDocumentListener(SimpleDocumentCallback { applyFilter() })

        val leftPanel = JPanel(BorderLayout()).apply {
            border = EmptyBorder(8, 8, 8, 8)
        }
        val searchPanel = JPanel(BorderLayout(4, 4))
        searchPanel.add(JLabel("Search"), BorderLayout.WEST)
        searchPanel.add(searchField, BorderLayout.CENTER)
        leftPanel.add(searchPanel, BorderLayout.NORTH)
        leftPanel.add(JScrollPane(list), BorderLayout.CENTER)
        val newButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = EmptyBorder(12, 0, 0, 0)
            add(newButton.apply { addActionListener { createNew() } })
        }
        leftPanel.add(newButtonPanel, BorderLayout.SOUTH)

        val rightPanel = JPanel(BorderLayout()).apply {
            border = EmptyBorder(8, 12, 8, 8)
            add(editor.component, BorderLayout.CENTER)
            add(createEditorFooter(), BorderLayout.SOUTH)
        }

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel)
        split.dividerLocation = 280
        add(split, BorderLayout.CENTER)

        if (model.size() > 0) {
            list.selectedIndex = 0
        }
    }

    private fun createEditorFooter(): Component {
        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        footer.add(saveButton.apply {
            isEnabled = false
            addActionListener { save() }
        })
        footer.add(cancelButton.apply {
            isEnabled = false
            addActionListener { cancel() }
        })
        return footer
    }

    private fun createContextMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(JMenuItem(object : AbstractAction("Clone") {
            override fun actionPerformed(e: ActionEvent?) {
                cloneSelected()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction(TOGGLE_DEFAULT) {
            override fun actionPerformed(e: ActionEvent?) {
                toggleSelected()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Delete") {
            override fun actionPerformed(e: ActionEvent?) {
                deleteSelected()
            }
        }))
        menu.addSeparator()
        menu.add(JMenuItem(object : AbstractAction("Copy") {
            override fun actionPerformed(e: ActionEvent?) {
                copySelected()
            }
        }))
        menu.add(JMenuItem(object : AbstractAction("Paste") {
            override fun actionPerformed(e: ActionEvent?) {
                pasteFromClipboard()
            }
        }))
        return menu
    }

    private fun applyFilter() {
        val query = searchField.text.orEmpty()
        filterModel.updateFilter(query)
        if (filterModel.size == 0) {
            list.clearSelection()
            editor.display(null)
            currentIndex = null
        } else if (list.selectedIndex < 0) {
            list.selectedIndex = 0
        }
    }

    private fun loadSelection() {
        val filteredIndex = list.selectedIndex
        if (filteredIndex < 0) {
            currentIndex = null
            editor.display(null)
            dirty = false
            saveButton.isEnabled = false
            cancelButton.isEnabled = false
            return
        }
        val backingIndex = filterModel.backingIndex(filteredIndex)
        currentIndex = backingIndex
        val value = model.getElementAt(backingIndex)
        editor.display(value)
        dirty = false
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
    }

    private fun createNew() {
        list.clearSelection()
        currentIndex = null
        editor.display(defaultFactory())
        dirty = true
        saveButton.isEnabled = true
        cancelButton.isEnabled = true
    }

    private fun deleteSelected() {
        val filteredIndex = list.selectedIndex
        if (filteredIndex < 0) return
        val backingIndex = filterModel.backingIndex(filteredIndex)
        model.remove(backingIndex)
        filterModel.invalidate()
        if (model.size() > 0) {
            val newIndex = minOf(backingIndex, model.size() - 1)
            val filtered = filterModel.filteredIndexOf(newIndex)
            list.selectedIndex = if (filtered >= 0) filtered else 0
        } else {
            list.clearSelection()
        }
    }

    private fun cloneSelected() {
        val filteredIndex = list.selectedIndex
        if (filteredIndex < 0) return
        val backingIndex = filterModel.backingIndex(filteredIndex)
        val value = model.getElementAt(backingIndex)
        val clone = cloneFactory(value)
        model.addElement(clone)
        filterModel.invalidate()
        val newIndex = model.size() - 1
        val filtered = filterModel.filteredIndexOf(newIndex)
        if (filtered >= 0) {
            list.selectedIndex = filtered
        }
    }

    private fun toggleSelected() {
        val filteredIndex = list.selectedIndex
        if (filteredIndex < 0) return
        val backingIndex = filterModel.backingIndex(filteredIndex)
        val value = model.getElementAt(backingIndex)
        val toggled = toggleFactory(value)
        model.set(backingIndex, toggled)
        filterModel.invalidate()
        list.selectedIndex = filterModel.filteredIndexOf(backingIndex)
        editor.display(toggled)
        dirty = false
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
    }

    private fun copySelected() {
        val filteredIndex = list.selectedIndex
        if (filteredIndex < 0) return
        val backingIndex = filterModel.backingIndex(filteredIndex)
        val value = model.getElementAt(backingIndex)
        val dump = Dump(DumpSettingsBuilder().build())
        val yaml = dump.dumpToString(toMap(value))
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(yaml), this)
    }

    private fun pasteFromClipboard() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val data = clipboard.getData(DataFlavor.stringFlavor) as? String ?: return
        val loader = Load(LoadSettingsBuilder().build())
        try {
            val map = loader.loadFromString(data) as Map<String, Any>
            val value = fromMap(map)
            model.addElement(value)
            filterModel.invalidate()
            val newIndex = model.size() - 1
            val filtered = filterModel.filteredIndexOf(newIndex)
            if (filtered >= 0) {
                list.selectedIndex = filtered
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message)
        }
    }

    private fun save() {
        val updated = editor.buildUpdated() ?: return
        if (currentIndex == null) {
            model.addElement(updated)
            filterModel.invalidate()
            val newIndex = model.size() - 1
            val filtered = filterModel.filteredIndexOf(newIndex)
            if (filtered >= 0) {
                list.selectedIndex = filtered
            }
        } else {
            val idx = currentIndex!!
            model.set(idx, updated)
            filterModel.invalidate()
            val filtered = filterModel.filteredIndexOf(idx)
            if (filtered >= 0) {
                list.selectedIndex = filtered
            }
        }
        dirty = false
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
    }

    private fun cancel() {
        if (currentIndex == null) {
            editor.display(null)
        } else {
            val value = model.getElementAt(currentIndex!!)
            editor.display(value)
        }
        dirty = false
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
    }

    private fun markDirty() {
        dirty = true
        saveButton.isEnabled = true
        cancelButton.isEnabled = true
    }

    override fun lostOwnership(clipboard: java.awt.datatransfer.Clipboard?, contents: Transferable?) {}
}

private class MenuItemInlineEditor(parent: Component?) : MinimalToolInlineEditor<Piper.UserActionTool>(parent, MinimalToolEditorConfig()) {
    private lateinit var hasGUICheckBox: JCheckBox
    private lateinit var avoidPipeCheckBox: JCheckBox
    private lateinit var minInputsSpinner: JSpinner
    private lateinit var maxInputsSpinner: JSpinner

    override fun extractCommon(value: Piper.UserActionTool): Piper.MinimalTool = value.common

    override fun addCustomFields(value: Piper.UserActionTool, panel: Container, cs: GridBagConstraints) {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        hasGUICheckBox = JCheckBox("Has its own GUI (no need for a console window)", value.hasGUI).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        avoidPipeCheckBox = JCheckBox("Avoid piping into this tool (reduces clutter in menu if it doesn't make sense)", value.avoidPipe).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        container.add(hasGUICheckBox)
        container.add(Box.createVerticalStrut(8))
        container.add(avoidPipeCheckBox)
        container.add(Box.createVerticalStrut(12))

        minInputsSpinner = JSpinner(SpinnerNumberModel(max(value.minInputs, 1), 1, Integer.MAX_VALUE, 1))
        maxInputsSpinner = JSpinner(SpinnerNumberModel(value.maxInputs, 0, Integer.MAX_VALUE, 1))
        container.add(buildSpinnerRow("Minimum required number of selected items:", minInputsSpinner))
        container.add(Box.createVerticalStrut(8))
        container.add(buildSpinnerRow("Maximum allowed number of selected items (0 = no limit):", maxInputsSpinner))
        container.add(Box.createVerticalGlue())

        minimalToolWidget()?.addCustomTab("Behavior", JScrollPane(container))
    }

    override fun buildUpdatedValue(original: Piper.UserActionTool, common: Piper.MinimalTool): Piper.UserActionTool {
        val minInputsValue = (minInputsSpinner.value as Number).toInt()
        val maxInputsValue = (maxInputsSpinner.value as Number).toInt()
        if (maxInputsValue in 1 until minInputsValue) {
            throw RuntimeException("Maximum allowed number of selected items cannot be lower than minimum required number of selected items.")
        }
        return Piper.UserActionTool.newBuilder().apply {
            this.common = common
            if (hasGUICheckBox.isSelected) hasGUI = true
            if (avoidPipeCheckBox.isSelected) avoidPipe = true
            if (minInputsValue > 1) minInputs = minInputsValue
            if (maxInputsValue > 0) maxInputs = maxInputsValue
        }.build()
    }
}

private fun buildSpinnerRow(label: String, spinner: JSpinner): JComponent {
    val row = JPanel()
    row.layout = BoxLayout(row, BoxLayout.X_AXIS)
    val text = JLabel(label)
    text.alignmentX = Component.LEFT_ALIGNMENT
    row.add(text)
    row.add(Box.createHorizontalStrut(8))
    row.add(spinner)
    row.add(Box.createHorizontalGlue())
    row.alignmentX = Component.LEFT_ALIGNMENT
    return row
}

private class BasicMinimalToolEditor(
    parent: Component?,
    config: MinimalToolEditorConfig,
) : MinimalToolInlineEditor<Piper.MinimalTool>(parent, config) {
    override fun extractCommon(value: Piper.MinimalTool): Piper.MinimalTool = value
    override fun addCustomFields(value: Piper.MinimalTool, panel: Container, cs: GridBagConstraints) {}
    override fun buildUpdatedValue(original: Piper.MinimalTool, common: Piper.MinimalTool): Piper.MinimalTool = common
}

private class HttpListenerInlineEditor(parent: Component?) : MinimalToolInlineEditor<Piper.HttpListener>(parent, MinimalToolEditorConfig()) {
    private lateinit var scopeCombo: JComboBox<ConfigHttpListenerScope>
    private lateinit var toolsWidget: EnumSetWidget<BurpTool>
    private lateinit var ignoreOutputCheckBox: JCheckBox
    private lateinit var noteLabel: JLabel

    override fun extractCommon(value: Piper.HttpListener): Piper.MinimalTool = value.common

    override fun addCustomFields(value: Piper.HttpListener, panel: Container, cs: GridBagConstraints) {
        val container = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(8, 8, 8, 8)
        }
        scopeCombo = JComboBox(ConfigHttpListenerScope.values()).apply {
            selectedItem = ConfigHttpListenerScope.fromHttpListenerScope(value.scope)
        }
        container.add(JLabel("Listen to"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        container.add(scopeCombo, constraints)

        constraints.gridx = 0
        constraints.gridy++
        constraints.weightx = 0.0
        constraints.fill = GridBagConstraints.NONE
        toolsWidget = EnumSetWidget(value.toolSet, container, constraints, "sent/received by", BurpTool::class.java)

        constraints.gridx = 0
        constraints.gridy++
        constraints.gridwidth = 2
        constraints.anchor = GridBagConstraints.WEST
        constraints.fill = GridBagConstraints.NONE
        ignoreOutputCheckBox = JCheckBox("Ignore output (if you only need side effects)", value.ignoreOutput)
        container.add(ignoreOutputCheckBox, constraints)

        constraints.gridy++
        noteLabel = JLabel(HTTP_LISTENER_NOTE)
        container.add(noteLabel, constraints)
        minimalToolWidget()?.addCustomTab("HTTP listener", JScrollPane(container))
        minimalToolWidget()?.addFilterChangeListener(object : ChangeListener<Piper.MessageMatch> {
            override fun valueChanged(value: Piper.MessageMatch?) {
                noteLabel.isVisible = value == null
            }
        })
    }

    override fun buildUpdatedValue(original: Piper.HttpListener, common: Piper.MinimalTool): Piper.HttpListener =
        Piper.HttpListener.newBuilder().apply {
            this.common = common
            scope = (scopeCombo.selectedItem as ConfigHttpListenerScope).hls
            if (ignoreOutputCheckBox.isSelected) ignoreOutput = true
            val toolSet = toolsWidget.toSet()
            if (toolSet.size < BurpTool.values().size) {
                setToolSet(toolSet)
            }
        }.build()
}

private class HighlighterInlineEditor(parent: Component?) : MinimalToolInlineEditor<Piper.Highlighter>(parent, MinimalToolEditorConfig(showScope = true)) {
    private lateinit var overwriteCheckBox: JCheckBox
    private lateinit var listenerCheckBox: JCheckBox
    private lateinit var colorCombo: JComboBox<Highlight>

    override fun extractCommon(value: Piper.Highlighter): Piper.MinimalTool = value.common

    override fun addCustomFields(value: Piper.Highlighter, panel: Container, cs: GridBagConstraints) {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        overwriteCheckBox = JCheckBox("Overwrite highlight on items that already have one", value.overwrite).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        listenerCheckBox = JCheckBox("Continuously apply to future requests/responses", value.applyWithListener).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        container.add(overwriteCheckBox)
        container.add(Box.createVerticalStrut(8))
        container.add(listenerCheckBox)
        container.add(Box.createVerticalStrut(12))

        colorCombo = JComboBox(Highlight.values())
        val comboRow = JPanel()
        comboRow.layout = BoxLayout(comboRow, BoxLayout.X_AXIS)
        comboRow.add(JLabel("Set highlight to"))
        comboRow.add(Box.createHorizontalStrut(8))
        comboRow.add(colorCombo)
        comboRow.add(Box.createHorizontalGlue())
        comboRow.alignmentX = Component.LEFT_ALIGNMENT
        container.add(comboRow)
        colorCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val highlight = value as? Highlight
                if (highlight?.color != null) {
                    component.background = highlight.color
                    component.foreground = highlight.textColor
                }
                return component
            }
        }
        Highlight.fromString(value.color)?.let { colorCombo.selectedItem = it }
        container.add(Box.createVerticalGlue())
        minimalToolWidget()?.addCustomTab("Highlight", JScrollPane(container))
    }

    override fun buildUpdatedValue(original: Piper.Highlighter, common: Piper.MinimalTool): Piper.Highlighter =
        Piper.Highlighter.newBuilder().apply {
            this.common = common
            color = colorCombo.selectedItem.toString()
            if (overwriteCheckBox.isSelected) overwrite = true
            if (listenerCheckBox.isSelected) applyWithListener = true
        }.build()
}

fun createMenuItemManager(
    model: DefaultListModel<Piper.UserActionTool>,
    parent: Component?,
): Component {
    return MinimalToolManagerPanel(
        model,
        parent,
        extractor = { it.common },
        editorFactory = { MenuItemInlineEditor(it) },
        newButtonLabel = "+ New context menu item",
        defaultFactory = { Piper.UserActionTool.getDefaultInstance() },
        cloneFactory = { it.toBuilder().build() },
        toggleFactory = { it.buildEnabled(!it.common.enabled) },
        toMap = { it.toMap() },
        fromMap = { UserActionToolFromMap.invoke(it) },
    )
}

fun createMacroManager(
    model: DefaultListModel<Piper.MinimalTool>,
    parent: Component?,
): Component {
    return MinimalToolManagerPanel(
        model,
        parent,
        extractor = { it },
        editorFactory = { BasicMinimalToolEditor(it, MinimalToolEditorConfig()) },
        newButtonLabel = "+ New macro",
        defaultFactory = { Piper.MinimalTool.getDefaultInstance() },
        cloneFactory = { it.toBuilder().build() },
        toggleFactory = { it.buildEnabled(!it.enabled) },
        toMap = { it.toMap() },
        fromMap = { minimalToolFromMap(it) },
    )
}

fun createHttpListenerManager(
    model: DefaultListModel<Piper.HttpListener>,
    parent: Component?,
): Component {
    return MinimalToolManagerPanel(
        model,
        parent,
        extractor = { it.common },
        editorFactory = { HttpListenerInlineEditor(it) },
        newButtonLabel = "+ New HTTP listener",
        defaultFactory = { Piper.HttpListener.getDefaultInstance() },
        cloneFactory = { it.toBuilder().build() },
        toggleFactory = { it.buildEnabled(!it.common.enabled) },
        toMap = { it.toMap() },
        fromMap = { httpListenerFromMap(it) },
    )
}

fun createIntruderPayloadProcessorManager(
    model: DefaultListModel<Piper.MinimalTool>,
    parent: Component?,
): Component {
    return MinimalToolManagerPanel(
        model,
        parent,
        extractor = { it },
        editorFactory = { BasicMinimalToolEditor(it, MinimalToolEditorConfig(showPassHeaders = false)) },
        newButtonLabel = "+ New payload processor",
        defaultFactory = { Piper.MinimalTool.getDefaultInstance() },
        cloneFactory = { it.toBuilder().build() },
        toggleFactory = { it.buildEnabled(!it.enabled) },
        toMap = { it.toMap() },
        fromMap = { minimalToolFromMap(it) },
    )
}

fun createHighlighterManager(
    model: DefaultListModel<Piper.Highlighter>,
    parent: Component?,
): Component {
    return MinimalToolManagerPanel(
        model,
        parent,
        extractor = { it.common },
        editorFactory = { HighlighterInlineEditor(it) },
        newButtonLabel = "+ New highlighter",
        defaultFactory = { Piper.Highlighter.getDefaultInstance() },
        cloneFactory = { it.toBuilder().build() },
        toggleFactory = { it.buildEnabled(!it.common.enabled) },
        toMap = { it.toMap() },
        fromMap = { highlighterFromMap(it) },
    )
}

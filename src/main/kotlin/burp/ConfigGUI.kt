package burp

import com.google.protobuf.ByteString
import org.snakeyaml.engine.v1.api.Dump
import org.snakeyaml.engine.v1.api.DumpSettingsBuilder
import org.snakeyaml.engine.v1.api.Load
import org.snakeyaml.engine.v1.api.LoadSettingsBuilder
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import kotlin.math.max

import burp.WorkspaceHeaderPanel
import burp.WorkspaceHeaderValues

private fun minimalToolHumanReadableName(cfgItem: Piper.MinimalTool) = if (cfgItem.enabled) cfgItem.name else cfgItem.name + " [disabled]"

const val TOGGLE_DEFAULT = "Toggle enabled"

abstract class ListEditor<E>(protected val model: DefaultListModel<E>, protected val parent: Component?,
                             caption: String?) : JPanel(BorderLayout()), ListDataListener, ListCellRenderer<E>, ListSelectionListener {
    protected val pnToolbar = JPanel()
    protected val listWidget = JList(model)
    private val btnClone = JButton("Clone")
    private val cr = DefaultListCellRenderer()

    abstract fun editDialog(value: E): E?
    abstract fun addDialog(): E?
    abstract fun toHumanReadable(value: E): String

    private fun addButtons() {
        val btnAdd = JButton("Add")
        btnAdd.addActionListener {
            model.addElement(addDialog() ?: return@addActionListener)
        }
        btnClone.addActionListener {
            (listWidget.selectedValuesList.reversed().asSequence() zip listWidget.selectedIndices.reversed().asSequence()).forEach {(value, index) ->
                model.insertElementAt(value, index)
            }
        }

        listOf(btnAdd, createRemoveButton(listWidget, model), btnClone).map(pnToolbar::add)
    }

    override fun getListCellRendererComponent(list: JList<out E>?, value: E, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        val c = cr.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        cr.text = toHumanReadable(value)
        return c
    }

    override fun valueChanged(p0: ListSelectionEvent?) { updateBtnEnableDisableState() }
    override fun contentsChanged(p0: ListDataEvent?)   { updateBtnEnableDisableState() }
    override fun intervalAdded  (p0: ListDataEvent?)   { updateBtnEnableDisableState() }
    override fun intervalRemoved(p0: ListDataEvent?)   { updateBtnEnableDisableState() }

    private fun updateCloneBtnState() {
        btnClone.isEnabled = !listWidget.isSelectionEmpty
    }

    open fun updateBtnEnableDisableState() {
        updateCloneBtnState()
    }

    init {
        listWidget.addDoubleClickListener {
            model[it] = editDialog(model[it]) ?: return@addDoubleClickListener
        }
        listWidget.cellRenderer = this

        listWidget.addListSelectionListener(this)
        model.addListDataListener(this)

        addButtons()
        updateCloneBtnState()
        if (caption == null) {
            add(pnToolbar, BorderLayout.PAGE_START)
        } else {
            add(pnToolbar, BorderLayout.SOUTH)
            add(JLabel(caption), BorderLayout.PAGE_START)
        }
        add(JScrollPane(listWidget), BorderLayout.CENTER)
    }
}

open class MinimalToolListEditor<E>(model: DefaultListModel<E>, parent: Component?, private val dialog: (E, Component?) -> MinimalToolDialog<E>,
                               private val default: () -> E, private val fromMap: (Map<String, Any>) -> E,
                               private val toMap: (E) -> Map<String, Any>) : ListEditor<E>(model, parent, null), ClipboardOwner {

    private val btnEnableDisable = JButton()
    private val btnCopy = JButton("Copy")
    private val btnPaste = JButton("Paste")

    override fun addDialog(): E? {
        val enabledDefault = dialog(default(), parent).buildEnabled(true)
        return dialog(enabledDefault, parent).showGUI()
    }

    override fun editDialog(value: E): E? = dialog(value, parent).showGUI()
    override fun toHumanReadable(value: E): String = dialog(value, parent).toHumanReadable()

    override fun updateBtnEnableDisableState() {
        super.updateBtnEnableDisableState()
        updateEnableDisableBtnState()
    }

    private fun updateEnableDisableBtnState() {
        val si = listWidget.selectedIndices
        val selectionNotEmpty = si.isNotEmpty()
        btnCopy.isEnabled = selectionNotEmpty
        btnEnableDisable.isEnabled = selectionNotEmpty
        val maxIndex = si.maxOrNull()
        btnEnableDisable.text = if (maxIndex == null || maxIndex >= model.size()) TOGGLE_DEFAULT else
        {
            val states = listWidget.selectedValuesList.map { dialog(it, parent).isToolEnabled() }.toSet()
            if (states.size == 1) (if (states.first()) "Disable" else "Enable") else TOGGLE_DEFAULT
        }
    }

    init {
        btnCopy.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(
                    Dump(DumpSettingsBuilder().build()).dumpToString(toMap(listWidget.selectedValue ?: return@addActionListener))), this)
        }
        btnPaste.addActionListener {
            val s = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: return@addActionListener
            val ls = Load(LoadSettingsBuilder().build())
            try {
                model.addElement(fromMap(ls.loadFromString(s) as Map<String, Any>))
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(listWidget, e.message)
            }
        }
        btnEnableDisable.addActionListener {
            (listWidget.selectedValuesList.asSequence() zip listWidget.selectedIndices.asSequence()).forEach { (value, index) ->
                model[index] = dialog(value, parent).buildEnabled(!dialog(value, parent).isToolEnabled())
            }
        }
        listOf(btnEnableDisable, btnCopy, btnPaste).map(pnToolbar::add)
        updateEnableDisableBtnState()
    }

    override fun lostOwnership(p0: Clipboard?, p1: Transferable?) {} /* ClipboardOwner */
}

class MessageViewerListEditor(model: DefaultListModel<Piper.MessageViewer>, parent: Component?,
                              private val commentatorModel: DefaultListModel<Piper.Commentator>,
                              private val switchToCommentator: () -> Unit) :
        MinimalToolListEditor<Piper.MessageViewer>(model, parent, ::MessageViewerDialog,
                Piper.MessageViewer::getDefaultInstance, ::messageViewerFromMap, Piper.MessageViewer::toMap) {

    private val btnConvertToCommentator = JButton("Convert to commentator")

    override fun updateBtnEnableDisableState() {
        super.updateBtnEnableDisableState()
        updateEnableDisableBtnState()
    }

    private fun updateEnableDisableBtnState() {
        btnConvertToCommentator.isEnabled = !listWidget.isSelectionEmpty
    }

    init {
        btnConvertToCommentator.addActionListener {
            listWidget.selectedValuesList.forEach {
                commentatorModel.addElement(Piper.Commentator.newBuilder().setCommon(it.common).build())
            }
            switchToCommentator()
        }
        pnToolbar.add(btnConvertToCommentator)
        updateEnableDisableBtnState()
    }
}

fun <E> JList<E>.addDoubleClickListener(listener: (Int) -> Unit) {
    this.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount == 2) {
                listener(this@addDoubleClickListener.locationToIndex(e.point))
            }
        }
    })
}

class CancelClosingWindow : RuntimeException()

private data class MinimalToolOverview(
    val name: String,
    val enabled: Boolean,
    val scope: String?,
    val filterDescription: String?,
    val commandDescription: String?,
)

class MinimalToolWidget(
    tool: Piper.MinimalTool,
    parent: Component?,
    showPassHeaders: Boolean,
    private val purpose: CommandInvocationPurpose,
    private val showScope: Boolean,
    showFilter: Boolean,
) : JPanel(BorderLayout()) {

    private val header = WorkspaceHeaderPanel<Unit>(
        templateLabel = "Template",
        onChange = { onHeaderChanged() },
        includeTemplateField = false,
    )
    private val scopeCombo: JComboBox<ConfigMinimalToolScope>? = if (showScope) {
        JComboBox(ConfigMinimalToolScope.values()).apply {
            selectedItem = ConfigMinimalToolScope.fromScope(tool.scope)
        }
    } else {
        null
    }
    private val filterPanel = if (showFilter) {
        WorkspaceFilterPanel(parent) { onFilterChanged() }
    } else {
        null
    }
    private val commandPanel = WorkspaceCommandPanel(
        parent,
        onChange = { onCommandChanged() },
        purpose = purpose,
        showAnsiCheckbox = false,
        showDependenciesField = false,
        showPassHeadersToggle = showPassHeaders,
    )
    private val behaviorContent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(8, 8, 8, 8)
        isOpaque = false
    }
    private val behaviorScrollPane = JScrollPane(behaviorContent).apply {
        border = EmptyBorder(0, 0, 0, 0)
        isOpaque = false
        viewport.isOpaque = false
    }
    private val tabs = JTabbedPane()
    private val overviewPanel = WorkspaceOverviewPanel<MinimalToolOverview>(
        title = "Workspace overview",
        emptyText = "Configure the tool to see summary.",
    ) { overview ->
        buildString {
            appendLine("Name: ${overview.name.ifBlank { "(unnamed)" }}")
            appendLine("Status: ${if (overview.enabled) "Enabled" else "Disabled"}")
            appendLine("Scope: ${overview.scope ?: "(not applicable)"}")
            appendLine("Filter: ${overview.filterDescription ?: "(none)"}")
            appendLine("Command: ${overview.commandDescription ?: "(none)"}")
        }
    }
    private var behaviorTabAdded = false
    private val dirtyListeners = mutableListOf<() -> Unit>()

    init {
        scopeCombo?.let { combo ->
            header.addField("Scope", combo)
            combo.addActionListener {
                notifyDirty()
                updateOverview()
            }
        }

        header.setValues(
            WorkspaceHeaderValues(
                name = tool.name,
                enabled = tool.enabled,
                template = null,
            ),
        )
        layout = BorderLayout()
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        tabs.addTab("Overview", buildOverviewTab())
        filterPanel?.let { panel ->
            tabs.addTab("Filter", panel)
            panel.display(tool.filter)
        }
        tabs.addTab("Command", commandPanel)
        commandPanel.display(
            WorkspaceCommandState(
                command = tool.cmd,
                dependencies = tool.cmd.requiredInPathList,
                passHeaders = tool.cmd.passHeaders,
            ),
        )

        filterPanel?.addChangeListener { _ ->
            notifyDirty()
            updateOverview()
        }

        updateOverview()
    }

    private fun buildOverviewTab(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = EmptyBorder(8, 8, 8, 8)
            add(overviewPanel, BorderLayout.NORTH)
        }
    }

    fun toMinimalTool(): Piper.MinimalTool {
        val headerValues = header.readValues()
        val name = headerValues.name.trim()
        if (name.isEmpty()) throw RuntimeException("Name cannot be empty.")
        val commandState = try {
            commandPanel.snapshot()
        } catch (ex: Exception) {
            throw RuntimeException(ex.message ?: "Command configuration is invalid", ex)
        }
        try {
            if (headerValues.enabled) commandState.command.checkDependencies()
        } catch (c: DependencyException) {
            when (JOptionPane.showConfirmDialog(this, "${c.message}\n\nAre you sure you want this enabled?")) {
                JOptionPane.NO_OPTION -> header.enabledToggle.isSelected = false
                JOptionPane.CANCEL_OPTION -> throw CancelClosingWindow()
            }
        }

        return Piper.MinimalTool.newBuilder().apply {
            this.name = name
            if (headerValues.enabled) this.enabled = true
            filterPanel?.value()?.let { this.filter = it }
            if (scopeCombo != null) {
                scope = (scopeCombo.selectedItem as ConfigMinimalToolScope).scope
            }
            cmd = commandState.command
        }.build()
    }

    fun addFilterChangeListener(listener: ChangeListener<Piper.MessageMatch>) {
        filterPanel?.addChangeListener { value ->
            listener.valueChanged(value)
        }
    }

    fun addCommandChangeListener(listener: () -> Unit) {
        commandPanel.addChangeListener(listener)
    }

    fun passHeaders(): Boolean = commandPanel.passHeaders()

    fun setPassHeaders(value: Boolean) {
        commandPanel.setPassHeaders(value)
    }

    fun addBehaviorComponent(component: Component, spacing: Int = if (behaviorContent.componentCount == 0) 0 else 8) {
        val target = ensureBehaviorTab()
        if (spacing > 0) {
            target.add(Box.createVerticalStrut(spacing))
        }
        if (component is JComponent) {
            component.alignmentX = Component.LEFT_ALIGNMENT
        }
        target.add(component)
    }

    fun addCustomTab(title: String, component: Component) {
        tabs.addTab(title, component)
    }

    fun setDirtyListener(listener: () -> Unit) {
        dirtyListeners += listener
    }

    private fun onHeaderChanged() {
        notifyDirty()
        updateOverview()
    }

    private fun onFilterChanged() {
        notifyDirty()
        updateOverview()
    }

    private fun onCommandChanged() {
        notifyDirty()
        updateOverview()
    }

    private fun notifyDirty() {
        dirtyListeners.forEach { it.invoke() }
    }

    private fun updateOverview() {
        val headerValues = header.readValues()
        val scopeText = scopeCombo?.selectedItem?.toString()
        val filterSummary = filterPanel?.value()?.toHumanReadable(negation = false, hideParentheses = true)
        val commandResult = runCatching { commandPanel.snapshot() }
        val commandLine = when {
            commandResult.isSuccess -> commandResult.getOrNull()?.command?.commandLine
            commandResult.exceptionOrNull()?.message.orEmpty().contains("at least one token", ignoreCase = true) -> null
            else -> "(invalid configuration)"
        }
        overviewPanel.display(
            MinimalToolOverview(
                name = headerValues.name,
                enabled = headerValues.enabled,
                scope = scopeText,
                filterDescription = filterSummary,
                commandDescription = commandLine,
            ),
        )
    }

    private fun ensureBehaviorTab(): JPanel {
        if (!behaviorTabAdded) {
            tabs.addTab("Behavior", behaviorScrollPane)
            behaviorTabAdded = true
        }
        return behaviorContent
    }
}


abstract class CollapsedWidget<E>(private val w: Window, var value: E?, private val caption: String, val removable: Boolean) : ClipboardOwner {
    private val label = JLabel()
    private val pnToolbar = JPanel(FlowLayout(FlowLayout.LEFT))
    private val btnRemove = JButton("Remove")
    private val btnCopy = JButton("Copy")
    private val btnPaste = JButton("Paste")
    private val changeListeners = mutableListOf<ChangeListener<E>>()

    abstract fun editDialog(value: E, parent: Component): E?
    abstract fun toHumanReadable(): String
    abstract val asMap: Map<String, Any>?
    abstract val default: E

    abstract fun parseMap(map: Map<String, Any>): E

    private fun update() {
        label.text = toHumanReadable() + " "
        btnRemove.isEnabled = value != null
        btnCopy.isEnabled = value != null
        w.repack()
        changeListeners.forEach { it.valueChanged(value) }
    }

    fun addChangeListener(listener: ChangeListener<E>) {
        changeListeners.add(listener)
        listener.valueChanged(value)
    }

    fun buildGUI(panel: Container, cs: GridBagConstraints) {
        val btnEditFilter = JButton("Edit...")
        btnEditFilter.addActionListener {
            value = editDialog(value ?: default, panel) ?: return@addActionListener
            update()
        }

        update()
        cs.gridwidth = 1
        cs.gridy++

        cs.gridx = 0 ; panel.add(JLabel(caption), cs)
        cs.gridx = 1 ; panel.add(label, cs)
        cs.gridwidth = 2
        cs.gridx = 2 ; panel.add(pnToolbar, cs)

        listOf(btnEditFilter, btnCopy, btnPaste).map(pnToolbar::add)

        if (removable) {
            pnToolbar.add(btnRemove)
            btnRemove.addActionListener {
                value = null
                update()
            }
        }
    }

    init {
        if (value == default) value = null
        btnCopy.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(
                    Dump(DumpSettingsBuilder().build()).dumpToString(asMap ?: return@addActionListener)), this)
        }
        btnPaste.addActionListener {
            val s = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: return@addActionListener
            val ls = Load(LoadSettingsBuilder().build())
            try {
                value = parseMap(ls.loadFromString(s) as Map<String, Any>)
                update()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(w, e.message)
            }
        }
    }

    override fun lostOwnership(p0: Clipboard?, p1: Transferable?) {} /* ClipboardOwner */
}

interface ChangeListener<E> {
    fun valueChanged(value: E?)
}

class CollapsedMessageMatchWidget(w: Window, mm: Piper.MessageMatch?, val showHeaderMatch: Boolean, caption: String) :
        CollapsedWidget<Piper.MessageMatch>(w, mm, caption, removable = true) {

    override fun editDialog(value: Piper.MessageMatch, parent: Component): Piper.MessageMatch? =
            MessageMatchDialog(value, showHeaderMatch = showHeaderMatch, parent = parent).showGUI()

    override fun toHumanReadable(): String =
            value?.toHumanReadable(negation = false, hideParentheses = true) ?: "(no filter)"

    override val asMap: Map<String, Any>?
        get() = value?.toMap()

    override fun parseMap(map: Map<String, Any>): Piper.MessageMatch = messageMatchFromMap(map)

    override val default: Piper.MessageMatch
        get() = Piper.MessageMatch.getDefaultInstance()
}

class CollapsedCommandInvocationWidget(w: Window, cmd: Piper.CommandInvocation, private val purpose: CommandInvocationPurpose, private val showPassHeaders: Boolean = true) :
        CollapsedWidget<Piper.CommandInvocation>(w, cmd, "Command: ", removable = (purpose == CommandInvocationPurpose.MATCH_FILTER)) {

    override fun toHumanReadable(): String = (if (purpose == CommandInvocationPurpose.MATCH_FILTER) value?.toHumanReadable(negation = false) else value?.commandLine) ?: "(no command)"
    override fun editDialog(value: Piper.CommandInvocation, parent: Component): Piper.CommandInvocation? =
            CommandInvocationDialog(value, purpose = purpose, parent = parent, showPassHeaders = showPassHeaders).showGUI()

    override val asMap: Map<String, Any>?
        get() = value?.toMap()

    override fun parseMap(map: Map<String, Any>): Piper.CommandInvocation = commandInvocationFromMap(map)

    override val default: Piper.CommandInvocation
        get() = Piper.CommandInvocation.getDefaultInstance()
}

abstract class ConfigDialog<E>(private val parent: Component?, private val caption: String) : JDialog() {
    protected val panel = JPanel(GridBagLayout())
    protected val cs = GridBagConstraints().apply {
        fill = GridBagConstraints.HORIZONTAL
        gridx = 0
        gridy = 0
    }
    private var state: E? = null

    fun showGUI(): E? {
        addFullWidthComponent(createOkCancelButtonsPanel(), panel, cs)
        title = caption
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        add(panel)
        rootPane.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        pack()
        setLocationRelativeTo(parent)
        isModal = true
        isVisible = true
        return state
    }

    private fun createOkCancelButtonsPanel(): Component {
        val btnOK = JButton("OK")
        val btnCancel = JButton("Cancel")
        rootPane.defaultButton = btnOK

        btnOK.addActionListener {
            try {
                state = processGUI()
                isVisible = false
            } catch (e: CancelClosingWindow) {
                /* do nothing, just skip closing the window */
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this, e.message)
            }
        }

        btnCancel.addActionListener {
            isVisible = false
        }

        return JPanel().apply {
            add(btnOK)
            add(btnCancel)
        }
    }

    abstract fun processGUI(): E
}

abstract class MinimalToolDialog<E>(private val common: Piper.MinimalTool, parent: Component?, noun: String,
                                    showPassHeaders: Boolean = true, showScope: Boolean = false,
                                    showFilter: Boolean = true,
                                    purpose: CommandInvocationPurpose = CommandInvocationPurpose.SELF_FILTER) :
        ConfigDialog<E>(parent, if (common.name.isEmpty()) "Add $noun" else "Edit $noun \"${common.name}\"") {

    private val mtw: MinimalToolWidget

    init {
        val widget = MinimalToolWidget(
            common,
            this,
            showPassHeaders = showPassHeaders,
            purpose = purpose,
            showScope = showScope,
            showFilter = showFilter,
        )
        cs.gridwidth = 4
        cs.fill = GridBagConstraints.BOTH
        cs.weightx = 1.0
        cs.weighty = 1.0
        panel.add(widget, cs)
        cs.gridy += 1
        cs.weighty = 0.0
        cs.fill = GridBagConstraints.HORIZONTAL
        cs.gridwidth = 1
        mtw = widget
    }

    override fun processGUI(): E = processGUI(mtw.toMinimalTool())

    fun isToolEnabled() : Boolean = common.enabled
    fun toHumanReadable(): String = minimalToolHumanReadableName(common)

    abstract fun buildEnabled(value: Boolean) : E
    abstract fun processGUI(mt: Piper.MinimalTool): E

    protected fun addFilterChangeListener(listener: ChangeListener<Piper.MessageMatch>) {
        mtw.addFilterChangeListener(listener)
    }
}

class MessageViewerDialog(private val messageViewer: Piper.MessageViewer, parent: Component?) :
        MinimalToolDialog<Piper.MessageViewer>(messageViewer.common, parent, "message viewer", showScope = true) {

    private val cbUsesColors = createFullWidthCheckBox("Uses ANSI (color) escape sequences", messageViewer.usesColors, panel, cs)

    override fun processGUI(mt: Piper.MinimalTool): Piper.MessageViewer = Piper.MessageViewer.newBuilder().apply {
        common = mt
        usesColors = cbUsesColors.isSelected
    }.build()

    override fun buildEnabled(value: Boolean): Piper.MessageViewer = messageViewer.buildEnabled(value)
}

const val HTTP_LISTENER_NOTE = "<html>Note: Piper settings are global and thus <font color='red'>apply to all your Burp projects</font>.<br>HTTP listeners <font color='red'>without filters</font> might have <font color='red'>hard-to-debug side effects</font>, you've been warned.</html>"

class HttpListenerDialog(private val httpListener: Piper.HttpListener, parent: Component?) :
        MinimalToolDialog<Piper.HttpListener>(httpListener.common, parent, "HTTP listener") {

    private val lsScope = createLabeledWidget("Listen to ",
            JComboBox(ConfigHttpListenerScope.values()).apply { selectedItem = ConfigHttpListenerScope.fromHttpListenerScope(httpListener.scope) }, panel, cs)
    private val btw = EnumSetWidget(httpListener.toolSet, panel, cs, "sent/received by", BurpTool::class.java)
    private val cbIgnore = createFullWidthCheckBox("Ignore output (if you only need side effects)", httpListener.ignoreOutput, panel, cs)
    private val lbNote = addFullWidthComponent(JLabel(HTTP_LISTENER_NOTE), panel, cs)

    init {
        addFilterChangeListener(object : ChangeListener<Piper.MessageMatch> {
            override fun valueChanged(value: Piper.MessageMatch?) {
                lbNote.isVisible = value == null
                repack()
            }
        })
    }

    override fun processGUI(mt: Piper.MinimalTool): Piper.HttpListener {
        val bt = btw.toSet()
        return Piper.HttpListener.newBuilder().apply {
            common = mt
            scope = (lsScope.selectedItem as ConfigHttpListenerScope).hls
            if (cbIgnore.isSelected) ignoreOutput = true
            if (bt.size < BurpTool.values().size) setToolSet(bt)
        }.build()
    }

    override fun buildEnabled(value: Boolean): Piper.HttpListener = httpListener.buildEnabled(value)
}

class CommentatorDialog(private val commentator: Piper.Commentator, parent: Component?) :
        MinimalToolDialog<Piper.Commentator>(commentator.common, parent, "commentator", showScope = true) {

    private val cbOverwrite: JCheckBox = createFullWidthCheckBox("Overwrite comments on items that already have one", commentator.overwrite, panel, cs)
    private val cbListener: JCheckBox = createFullWidthCheckBox("Continuously apply to future requests/responses", commentator.applyWithListener, panel, cs)

    override fun processGUI(mt: Piper.MinimalTool): Piper.Commentator = Piper.Commentator.newBuilder().apply {
        common = mt
        if (cbOverwrite.isSelected) overwrite = true
        if (cbListener.isSelected) applyWithListener = true
    }.build()

    override fun buildEnabled(value: Boolean): Piper.Commentator = commentator.buildEnabled(value)
}

class HighlighterDialog(private val highlighter: Piper.Highlighter, parent: Component?) :
        MinimalToolDialog<Piper.Highlighter>(highlighter.common, parent, "highlighter", showScope = true) {

    private val cbOverwrite: JCheckBox = createFullWidthCheckBox("Overwrite highlight on items that already have one", highlighter.overwrite, panel, cs)
    private val cbListener: JCheckBox = createFullWidthCheckBox("Continuously apply to future requests/responses", highlighter.applyWithListener, panel, cs)
    private val cbColor = createLabeledWidget("Set highlight to ", JComboBox(Highlight.values()), panel, cs)

    init {
        cbColor.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val v = value as Highlight
                if (v.color != null) {
                    c.background = v.color
                    c.foreground = v.textColor
                }
                return c
            }
        }
        val h = Highlight.fromString(highlighter.color)
        if (h != null) cbColor.selectedItem = h
    }

    override fun processGUI(mt: Piper.MinimalTool): Piper.Highlighter = Piper.Highlighter.newBuilder().apply {
        common = mt
        color = cbColor.selectedItem.toString()
        if (cbOverwrite.isSelected) overwrite = true
        if (cbListener.isSelected) applyWithListener = true
    }.build()

    override fun buildEnabled(value: Boolean): Piper.Highlighter = highlighter.buildEnabled(value)
}

fun createFullWidthCheckBox(caption: String, initialValue: Boolean, panel: Container, cs: GridBagConstraints): JCheckBox {
    cs.gridwidth = 4
    cs.gridx = 0
    cs.gridy++
    return createCheckBox(caption, initialValue, panel, cs)
}

private fun createCheckBox(caption: String, initialValue: Boolean, panel: Container, cs: GridBagConstraints): JCheckBox {
    val cb = JCheckBox(caption)
    cb.isSelected = initialValue
    panel.add(cb, cs)
    return cb
}

class MenuItemDialog(private val menuItem: Piper.UserActionTool, parent: Component?) :
        MinimalToolDialog<Piper.UserActionTool>(menuItem.common, parent, "menu item",
                purpose = CommandInvocationPurpose.EXECUTE_ONLY, showScope = true) {

    private val cbHasGUI: JCheckBox = createFullWidthCheckBox("Has its own GUI (no need for a console window)", menuItem.hasGUI, panel, cs)
    private val cbAvoidPipe: JCheckBox = createFullWidthCheckBox("Avoid piping into this tool (reduces clutter in menu if it doesn't make sense)", menuItem.avoidPipe, panel, cs)
    private val smMinInputs: SpinnerNumberModel = createSpinner("Minimum required number of selected items: ",
            max(menuItem.minInputs, 1), 1, panel, cs)
    private val smMaxInputs: SpinnerNumberModel = createSpinner("Maximum allowed number of selected items: (0 = no limit) ",
            menuItem.maxInputs, 0, panel, cs)

    override fun processGUI(mt: Piper.MinimalTool): Piper.UserActionTool {
        val minInputsValue = smMinInputs.number.toInt()
        val maxInputsValue = smMaxInputs.number.toInt()

        if (maxInputsValue in 1 until minInputsValue) throw RuntimeException(
            "Maximum allowed number of selected items cannot be lower than minimum required number of selected items.")

        return Piper.UserActionTool.newBuilder().apply {
            common = mt
            if (cbHasGUI.isSelected) hasGUI = true
            if (cbAvoidPipe.isSelected) avoidPipe = true
            if (minInputsValue > 1) minInputs = minInputsValue
            if (maxInputsValue > 0) maxInputs = maxInputsValue
        }.build()
    }

    override fun buildEnabled(value: Boolean): Piper.UserActionTool = menuItem.buildEnabled(value)
}

private fun createSpinner(caption: String, initial: Int, minimum: Int, panel: Container, cs: GridBagConstraints): SpinnerNumberModel {
    val model = SpinnerNumberModel(initial, minimum, Integer.MAX_VALUE, 1)

    cs.gridy++
    cs.gridwidth = 2
    cs.gridx = 0 ; panel.add(JLabel(caption), cs)
    cs.gridx = 2 ; panel.add(JSpinner(model), cs)

    return model
}

class IntruderPayloadProcessorDialog(private val ipp: Piper.MinimalTool, parent: Component?) :
        MinimalToolDialog<Piper.MinimalTool>(ipp, parent, "Intruder payload processor", showPassHeaders = false) {

    override fun processGUI(mt: Piper.MinimalTool): Piper.MinimalTool = mt
    override fun buildEnabled(value: Boolean): Piper.MinimalTool = ipp.buildEnabled(value)
}

class MacroDialog(private val macro: Piper.MinimalTool, parent: Component?) :
        MinimalToolDialog<Piper.MinimalTool>(macro, parent, "macro") {

    override fun processGUI(mt: Piper.MinimalTool): Piper.MinimalTool = mt
    override fun buildEnabled(value: Boolean): Piper.MinimalTool = macro.buildEnabled(value)
}

fun createLabeledTextField(caption: String, initialValue: String, panel: Container, cs: GridBagConstraints): JTextField {
    return createLabeledWidget(caption, JTextField(initialValue), panel, cs)
}

fun <E> createLabeledComboBox(caption: String, initialValue: String, panel: Container, cs: GridBagConstraints, choices: Array<E>): JComboBox<E> {
    val cb = JComboBox(choices)
    cb.isEditable = true
    cb.selectedItem = initialValue
    return createLabeledWidget(caption, cb, panel, cs)
}

private fun buildInlineRow(
    caption: String,
    widget: Component,
    trailing: List<Component> = emptyList(),
): JPanel {
    val row = JPanel(GridBagLayout()).apply { isOpaque = false }
    val label = JLabel(caption)
    if (widget is JComponent) {
        label.labelFor = widget
        widget.maximumSize = Dimension(Int.MAX_VALUE, widget.preferredSize.height)
    }

    row.add(label, GridBagConstraints().apply {
        gridx = 0
        gridy = 0
        anchor = GridBagConstraints.WEST
        insets = Insets(0, 0, 0, 8)
    })

    row.add(widget, GridBagConstraints().apply {
        gridx = 1
        gridy = 0
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(0, 0, 0, if (trailing.isNotEmpty()) 12 else 0)
    })

    trailing.forEachIndexed { index, component ->
        row.add(component, GridBagConstraints().apply {
            gridx = 2 + index
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(0, if (index == 0) 0 else 8, 0, 0)
        })
    }

    return row
}

fun <T : Component> createLabeledWidget(caption: String, widget: T, panel: Container, cs: GridBagConstraints): T {
    cs.gridy++

    val previousFill = cs.fill
    val previousWeightX = cs.weightx
    val previousAnchor = cs.anchor
    val previousGridWidth = cs.gridwidth
    val previousGridX = cs.gridx

    cs.gridx = 0
    cs.gridwidth = 4
    cs.fill = GridBagConstraints.HORIZONTAL
    cs.weightx = if (previousWeightX > 0.0) previousWeightX else 1.0
    cs.anchor = GridBagConstraints.NORTHWEST

    panel.add(buildInlineRow(caption, widget), cs)

    cs.gridx = previousGridX
    cs.gridwidth = previousGridWidth
    cs.fill = previousFill
    cs.weightx = previousWeightX
    cs.anchor = previousAnchor
    return widget
}

private fun addInlineLabeledRow(
    caption: String,
    widget: JComponent,
    panel: Container,
    cs: GridBagConstraints,
    trailing: List<Component> = emptyList(),
) {
    cs.gridy++

    val previousFill = cs.fill
    val previousWeightX = cs.weightx
    val previousAnchor = cs.anchor
    val previousGridWidth = cs.gridwidth
    val previousGridX = cs.gridx

    cs.gridx = 0
    cs.gridwidth = 4
    cs.fill = GridBagConstraints.HORIZONTAL
    cs.weightx = if (previousWeightX > 0.0) previousWeightX else 1.0
    cs.anchor = GridBagConstraints.NORTHWEST

    panel.add(buildInlineRow(caption, widget, trailing), cs)

    cs.gridx = previousGridX
    cs.gridwidth = previousGridWidth
    cs.fill = previousFill
    cs.weightx = previousWeightX
    cs.anchor = previousAnchor
}

class HeaderMatchDialog(hm: Piper.HeaderMatch, parent: Component) : ConfigDialog<Piper.HeaderMatch>(parent, "Header filter editor") {
    private val commonHeaders = arrayOf("Content-Disposition", "Content-Type", "Cookie",
            "Host", "Origin", "Referer", "Server", "User-Agent", "X-Requested-With")
    private val cbHeader = createLabeledComboBox("Header name: (case insensitive) ", hm.header, panel, cs, commonHeaders)
    private val regExpWidget: RegExpWidget = RegExpWidget(hm.regex, panel, cs)

    override fun processGUI(): Piper.HeaderMatch {
        val text = cbHeader.selectedItem?.toString()
        if (text.isNullOrEmpty()) throw RuntimeException("The header name cannot be empty.")

        return Piper.HeaderMatch.newBuilder().apply {
            header = text
            regex = regExpWidget.toRegularExpression()
        }.build()
    }
}

const val CMDLINE_INPUT_FILENAME_PLACEHOLDER = "<INPUT>"
const val CMDLINE_EMPTY_STRING_PLACEHOLDER = "<EMPTY STRING>"

data class CommandLineParameter(val value: String?) { // null = input file name
    val isInputFileName: Boolean
        get() = value == null
    val isEmptyString: Boolean
        get() = value?.isEmpty() == true
    override fun toString(): String = when {
        isInputFileName -> CMDLINE_INPUT_FILENAME_PLACEHOLDER
        value.isNullOrEmpty() -> CMDLINE_EMPTY_STRING_PLACEHOLDER // empty strings would be rendered as a barely visible 1 to 2 px high item
        else -> value
    }
}

class CommandInvocationDialog(ci: Piper.CommandInvocation, private val purpose: CommandInvocationPurpose, parent: Component,
                              showPassHeaders: Boolean) : ConfigDialog<Piper.CommandInvocation>(parent, "Command invocation editor") {
    private val ccmwStdout = CollapsedMessageMatchWidget(this, mm = ci.stdout, showHeaderMatch = false, caption = "Match on stdout: ")
    private val ccmwStderr = CollapsedMessageMatchWidget(this, mm = ci.stderr, showHeaderMatch = false, caption = "Match on stderr: ")
    private val monospaced12 = Font("monospaced", Font.PLAIN, 12)
    private var tfExitCode: JTextField? = null
    private val passHeadersControls: PassHeadersControls? = if (showPassHeaders) {
        createPassHeadersControls(ci.passHeaders) { }
    } else null
    private val cbPassHeaders: JCheckBox?
        get() = passHeadersControls?.checkbox
    private val tfDependencies = JTextField()

    private val hasFileName = ci.inputMethod == Piper.CommandInvocation.InputMethod.FILENAME
    private val paramsModel = fillDefaultModel(sequence {
        yieldAll(ci.prefixList)
        if (hasFileName) yield(null)
        yieldAll(ci.postfixList)
    }.map(::CommandLineParameter))
    private val parameterEditor = CommandParameterListEditor(ci.parameterList, this)

    fun parseExitCodeList(): Iterable<Int> {
        val text = tfExitCode!!.text
        return if (text.isEmpty()) emptyList()
        else text.filterNot(Char::isWhitespace).split(',').map(String::toInt)
    }

    init {
        val lsParams = JList(paramsModel)
        lsParams.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        lsParams.font = monospaced12

        lsParams.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val v = value as CommandLineParameter
                if (v.isInputFileName) {
                    c.background = Color.RED
                    c.foreground = if (isSelected) Color.YELLOW else Color.WHITE
                } else if (v.isEmptyString) {
                    c.background = Color.YELLOW
                    c.foreground = if (isSelected) Color.RED else Color.BLUE
                }
                return c
            }
        }

        lsParams.addDoubleClickListener {
            if (paramsModel[it].isInputFileName) {
                JOptionPane.showMessageDialog(this, CMDLINE_INPUT_FILENAME_PLACEHOLDER +
                        " is a special placeholder for the names of the input file(s), and thus cannot be edited.")
                return@addDoubleClickListener
            }
            paramsModel[it] = CommandLineParameter(
                    JOptionPane.showInputDialog(this, "Edit command line parameter no. ${it + 1}:", paramsModel[it].value)
                            ?: return@addDoubleClickListener)
        }

        cs.gridwidth = 4

        panel.add(JLabel("Command line parameters: (one per line)"), cs)

        cs.gridy = 1
        cs.gridwidth = 3
        cs.gridheight = 3
        cs.fill = GridBagConstraints.BOTH

        panel.add(JScrollPane(lsParams), cs)

        val btnMoveUp = JButton("Move up")
        btnMoveUp.addActionListener {
            val si = lsParams.selectedIndices
            if (si.isEmpty() || si[0] == 0) return@addActionListener
            si.forEach {
                paramsModel.insertElementAt(paramsModel.remove(it - 1), it)
            }
        }

        val btnMoveDown = JButton("Move down")
        btnMoveDown.addActionListener {
            val si = lsParams.selectedIndices
            if (si.isEmpty() || si.last() == paramsModel.size - 1) return@addActionListener
            si.reversed().forEach {
                paramsModel.insertElementAt(paramsModel.remove(it + 1), it)
            }
            lsParams.selectedIndices = si.map { it + 1 }.toIntArray()
        }

        cs.gridx = 3
        cs.gridwidth = 1
        cs.gridheight = 1
        cs.fill = GridBagConstraints.HORIZONTAL

        panel.add(createRemoveButton(lsParams, paramsModel), cs)

        cs.gridy = 2; panel.add(btnMoveUp, cs)
        cs.gridy = 3; panel.add(btnMoveDown, cs)

        val tfParam = JTextField()
        val btnAdd = JButton("Add")

        btnAdd.addActionListener {
            paramsModel.addElement(CommandLineParameter(tfParam.text))
            tfParam.text = ""
        }

        cs.gridy = 4

        cs.gridx = 0; cs.gridwidth = 1; panel.add(JLabel("Add parameter: "), cs)
        cs.gridx = 1; cs.gridwidth = 2; panel.add(tfParam, cs)
        cs.gridx = 3; cs.gridwidth = 1; panel.add(btnAdd, cs)

        val cbSpace = createFullWidthCheckBox("Auto-add upon pressing space or closing quotes", true, panel, cs)

        tfParam.font = monospaced12
        tfParam.addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (cbSpace.isSelected) {
                    val t = tfParam.text
                    if (t.startsWith('"')) {
                        if (e.keyChar == '"') {
                            tfParam.text = t.substring(1)
                            btnAdd.doClick()
                            e.consume()
                        }
                    } else if (t.startsWith('\'')) {
                        if (e.keyChar == '\'') {
                            tfParam.text = t.substring(1)
                            btnAdd.doClick()
                            e.consume()
                        }
                    } else if (e.keyChar == ' ' && t.isNotEmpty()) {
                        btnAdd.doClick()
                        e.consume()
                    }
                }
            }
        })

        cs.gridy = 6

        InputMethodWidget.create(this, panel, cs, hasFileName, paramsModel)

        addFullWidthComponent(parameterEditor, panel, cs)

        passHeadersControls?.let { controls ->
            addFullWidthComponent(controls.checkbox, panel, cs)
            addFullWidthComponent(controls.note, panel, cs)
        }

        addFullWidthComponent(JLabel("Binaries required in PATH: (comma separated)"), panel, cs)
        addFullWidthComponent(tfDependencies, panel, cs)
        tfDependencies.text = ci.requiredInPathList.joinToString(separator = ", ")

        if (purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
            val exitValues = ci.exitCodeList.joinToString(", ")

            if (purpose == CommandInvocationPurpose.SELF_FILTER) {
                addFullWidthComponent(JLabel("If any filters are set below, they are treated the same way as a pre-exec filter."), panel, cs)
            }
            ccmwStdout.buildGUI(panel, cs)
            ccmwStderr.buildGUI(panel, cs)
            val tfExitCode = createLabeledTextField("Match on exit code: (comma separated) ", exitValues, panel, cs)

            tfExitCode.inputVerifier = object : InputVerifier() {
                override fun verify(input: JComponent?): Boolean =
                        try {
                            parseExitCodeList(); true
                        } catch (e: NumberFormatException) {
                            false
                        }
            }

            this.tfExitCode = tfExitCode
        }
    }

    override fun processGUI(): Piper.CommandInvocation = Piper.CommandInvocation.newBuilder().apply {
        if (purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
            if (ccmwStdout.value != null) stdout = ccmwStdout.value
            if (ccmwStderr.value != null) stderr = ccmwStderr.value
            try {
                addAllExitCode(parseExitCodeList())
            } catch (e: NumberFormatException) {
                throw RuntimeException("Exit codes should contain numbers separated by commas only. (Whitespace is ignored.)")
            }
            if (purpose == CommandInvocationPurpose.MATCH_FILTER && !hasFilter) {
                throw RuntimeException("No filters are defined for stdio or exit code.")
            }
        }
        val d = tfDependencies.text.replace("\\s".toRegex(), "")
        if (d.isNotEmpty()) addAllRequiredInPath(d.split(','))
        if (paramsModel.isEmpty) throw RuntimeException("The command must contain at least one argument.")
        if (paramsModel[0].isEmptyString) throw RuntimeException("The first argument (the command) is an empty string")
        val params = paramsModel.map(CommandLineParameter::value)
        addAllPrefix(params.takeWhile(Objects::nonNull))
        if (prefixCount < paramsModel.size) {
            inputMethod = Piper.CommandInvocation.InputMethod.FILENAME
            addAllPostfix(params.drop(prefixCount + 1))
        }
        val parameterItems = parameterEditor.items.toList()
        val duplicateNames = parameterItems.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            throw RuntimeException("Parameter names must be unique: ${duplicateNames.joinToString(", ")}")
        }
        addAllParameter(parameterItems)
        if (cbPassHeaders?.isSelected == true) passHeaders = true
    }.build()
}

private class CommandParameterListEditor(
    source: List<Piper.CommandInvocation.Parameter>,
    parent: Component?,
) : ListEditor<Piper.CommandInvocation.Parameter>(fillDefaultModel(source), parent, "Interactive parameters (prompted before execution):") {

    override fun addDialog(): Piper.CommandInvocation.Parameter? =
        CommandParameterDialog(Piper.CommandInvocation.Parameter.getDefaultInstance(), parent).showGUI()

    override fun editDialog(value: Piper.CommandInvocation.Parameter): Piper.CommandInvocation.Parameter? =
        CommandParameterDialog(value, parent).showGUI()

    override fun toHumanReadable(value: Piper.CommandInvocation.Parameter): String = buildString {
        append(value.displayName)
        if (!value.defaultValue.isNullOrEmpty()) {
            append(" (default: \"${value.defaultValue}\")")
        }
    }

    val items: Iterable<Piper.CommandInvocation.Parameter>
        get() = model.toIterable()
}

private class CommandParameterDialog(
    private val parameter: Piper.CommandInvocation.Parameter,
    parent: Component?,
) : ConfigDialog<Piper.CommandInvocation.Parameter>(parent, if (parameter.name.isEmpty()) "Add parameter" else "Edit parameter \"${parameter.displayName}\"") {

    private val nameField = createLabeledTextField("Placeholder name:", parameter.name, panel, cs)
    private val labelField = createLabeledTextField(
        "Prompt label (shown to the user):",
        if (parameter.label.isNullOrEmpty()) parameter.displayName else parameter.label,
        panel,
        cs,
    )
    private val descriptionArea = JTextArea(parameter.descriptionOrNull() ?: "").apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 3
    }
    private val defaultField = createLabeledTextField("Default value (optional):", parameter.defaultValue, panel, cs)
    private val requiredCheckBox = createFullWidthCheckBox("Require the user to supply a value", parameter.required, panel, cs)

    init {
        val descriptionScroll = JScrollPane(descriptionArea)
        descriptionScroll.preferredSize = Dimension(200, descriptionArea.preferredSize.height)
        createLabeledWidget("Description (optional):", descriptionScroll, panel, cs)
        addFullWidthComponent(JLabel("Use the placeholder as \"${name}\" in the command line."), panel, cs)
    }

    override fun processGUI(): Piper.CommandInvocation.Parameter {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            throw RuntimeException("The placeholder name cannot be empty.")
        }
        if (!name.matches(Regex("[A-Za-z0-9_]+"))) {
            throw RuntimeException("Placeholder names may contain letters, digits, and underscores only.")
        }
        val label = labelField.text.trim()
        val description = descriptionArea.text.trim()
        val defaultValue = defaultField.text

        return Piper.CommandInvocation.Parameter.newBuilder().apply {
            this.name = name
            if (label.isNotEmpty() && label != name) this.label = label
            if (description.isNotEmpty()) this.description = description
            if (defaultValue.isNotEmpty()) this.defaultValue = defaultValue
            if (requiredCheckBox.isSelected) this.required = true
        }.build()
    }
}

private class InputMethodWidget(private val w: Window, private val label: JLabel = JLabel(),
                                private val button: JButton = JButton(),
                                private var hasFileName: Boolean) {
    fun update() {
        label.text = "Input method: " + (if (hasFileName) "filename" else "standard input") + " "
        button.text = if (hasFileName) "Set to stdin (remove $CMDLINE_INPUT_FILENAME_PLACEHOLDER)"
        else "Set to filename (add $CMDLINE_INPUT_FILENAME_PLACEHOLDER)"
        w.repack()
    }

    companion object {
        fun create(w: Window, panel: Container, cs: GridBagConstraints, hasFileName: Boolean, paramsModel: DefaultListModel<CommandLineParameter>): InputMethodWidget {
            val imw = InputMethodWidget(w = w, hasFileName = hasFileName)
            imw.update()
            cs.gridwidth = 2
            cs.gridx = 0 ; panel.add(imw.label, cs)
            cs.gridx = 2 ; panel.add(imw.button, cs)

            paramsModel.addListDataListener(object : ListDataListener {
                override fun intervalRemoved(p0: ListDataEvent?) {
                    if (!imw.hasFileName || paramsModel.toIterable().any(CommandLineParameter::isInputFileName)) return
                    imw.hasFileName = false
                    imw.update()
                }

                override fun contentsChanged(p0: ListDataEvent?) { /* ignore */ }
                override fun intervalAdded(p0: ListDataEvent?) { /* ignore */ }
            })

            imw.button.addActionListener {
                if (imw.hasFileName) {
                    val iof = paramsModel.toIterable().indexOfFirst(CommandLineParameter::isInputFileName)
                    if (iof >= 0) paramsModel.remove(iof) // this triggers intervalRemoved above, no explicit update() necessary
                } else {
                    paramsModel.addElement(CommandLineParameter(null))
                    imw.hasFileName = true
                    imw.update()
                }
            }

            return imw
        }
    }
}

fun <E : Component> addFullWidthComponent(c: E, panel: Container, cs: GridBagConstraints): E {
    cs.gridx = 0
    cs.gridy++
    cs.gridwidth = 4

    panel.add(c, cs)
    return c
}

class HexASCIITextField(
    private val tf: JTextField = JTextField(),
    private val rbHex: JRadioButton = JRadioButton("Hex"),
    private val rbASCII: JRadioButton = JRadioButton("ASCII"),
    private val field: String,
    private var isASCII: Boolean,
) {

    constructor(field: String, source: ByteString, dialog: Component) : this(field=field, isASCII=source.isValidUtf8) {
        if (isASCII) {
            tf.text = source.toStringUtf8()
            rbASCII.isSelected = true
        } else {
            tf.text = source.toHexPairs()
            rbHex.isSelected = true
        }

        with(ButtonGroup()) { add(rbHex); add(rbASCII); }

        tf.columns = 28

        rbASCII.addActionListener {
            if (isASCII) return@addActionListener
            val bytes = try { parseHex() } catch(e: NumberFormatException) {
                JOptionPane.showMessageDialog(dialog, "Error in $field field: hexadecimal string ${e.message}")
                rbHex.isSelected = true
                return@addActionListener
            }
            tf.text = String(bytes, Charsets.UTF_8)
            isASCII = true
        }

        rbHex.addActionListener {
            if (!isASCII) return@addActionListener
            tf.text = tf.text.toByteArray(/* default is UTF-8 */).toHexPairs()
            isASCII = false
        }
    }

    private fun parseHex(): ByteArray = tf.text.filter(Char::isLetterOrDigit).run {
        if (length % 2 != 0) {
            throw NumberFormatException("needs to contain an even number of hex digits")
        }
        if (any { c -> c in 'g'..'z' || c in 'G'..'Z' }) {
            throw NumberFormatException("contains non-hexadecimal letters (maybe typo?)")
        }
        chunked(2, ::parseHexByte).toByteArray()
    }

    fun getByteString(): ByteString = if (isASCII) ByteString.copyFromUtf8(tf.text) else try {
        ByteString.copyFrom(parseHex())
    } catch (e: NumberFormatException) {
        throw RuntimeException("Error in $field field: hexadecimal string ${e.message}")
    }

    fun addWidgets(caption: String, cs: GridBagConstraints, panel: Container) {
        addInlineLabeledRow(caption, tf, panel, cs, trailing = listOf(rbASCII, rbHex))
    }

    fun setValue(source: ByteString) {
        if (source.isValidUtf8) {
            tf.text = source.toStringUtf8()
            rbASCII.isSelected = true
            rbHex.isSelected = false
            isASCII = true
        } else {
            tf.text = source.toHexPairs()
            rbHex.isSelected = true
            rbASCII.isSelected = false
            isASCII = false
        }
    }

    fun addChangeListener(listener: () -> Unit) {
        tf.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = listener()
            override fun removeUpdate(e: DocumentEvent?) = listener()
            override fun changedUpdate(e: DocumentEvent?) = listener()
        })
        rbHex.addActionListener { listener() }
        rbASCII.addActionListener { listener() }
    }
}

private fun parseHexByte(cs: CharSequence): Byte = (parseHexNibble(cs[0]) shl 4 or parseHexNibble(cs[1])).toByte()

private fun parseHexNibble(c: Char): Int = if (c in '0'..'9') (c - '0')
else ((c.toLowerCase() - 'a') + 0xA)

class RegExpWidget(regex: Piper.RegularExpression, panel: Container, cs: GridBagConstraints) {
    private val tfPattern = JTextField(regex.pattern).apply {
        columns = 28
        addInlineLabeledRow("Matches regular expression:", this, panel, cs)
    }
    private val esw = EnumSetWidget(regex.flagSet, panel, cs, "Regular expression flags: (see JDK documentation)", RegExpFlag::class.java)

    fun hasPattern(): Boolean = tfPattern.text.isNotEmpty()

    fun toRegularExpression(): Piper.RegularExpression {
        return Piper.RegularExpression.newBuilder().setPattern(tfPattern.text).setFlagSet(esw.toSet()).build().apply { compile() }
    }

    fun setValue(regex: Piper.RegularExpression) {
        tfPattern.text = regex.pattern
        esw.setSelection(regex.flagSet)
    }

    fun addChangeListener(listener: () -> Unit) {
        tfPattern.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = listener()
            override fun removeUpdate(e: DocumentEvent?) = listener()
            override fun changedUpdate(e: DocumentEvent?) = listener()
        })
        esw.addChangeListener(listener)
    }
}

class EnumSetWidget<E : Enum<E>>(set: Set<E>, panel: Container, cs: GridBagConstraints, caption: String, enumClass: Class<E>) {
    private val cbMap: Map<E, JCheckBox>

    fun toSet(): Set<E> = cbMap.filterValues(JCheckBox::isSelected).keys

    fun setSelection(selection: Set<E>) {
        cbMap.forEach { (value, checkbox) ->
            checkbox.isSelected = value in selection
        }
    }

    fun addChangeListener(listener: () -> Unit) {
        cbMap.values.forEach { checkbox ->
            checkbox.addActionListener { listener() }
        }
    }

    init {
        addFullWidthComponent(JLabel(caption), panel, cs)
        val originalFill = cs.fill
        val originalWeightX = cs.weightx

        cbMap = enumClass.enumConstants.asIterable().associateWithTo(EnumMap(enumClass)) { value ->
            cs.gridy++
            cs.gridx = 0
            cs.gridwidth = 4
            cs.fill = GridBagConstraints.HORIZONTAL
            cs.weightx = 1.0
            createCheckBox(value.toString(), value in set, panel, cs)
        }.toMap()

        cs.gridwidth = 1
        cs.fill = originalFill
        cs.weightx = originalWeightX
        cs.gridx = 0
    }
}

class CollapsedHeaderMatchWidget(w: Window, hm: Piper.HeaderMatch?) :
        CollapsedWidget<Piper.HeaderMatch>(w, hm, "Header: ", removable = true) {

    override fun editDialog(value: Piper.HeaderMatch, parent: Component): Piper.HeaderMatch? =
            HeaderMatchDialog(value, parent = parent).showGUI()

    override fun toHumanReadable(): String = value?.toHumanReadable(negation = false) ?: "(no header match)"

    override val asMap: Map<String, Any>?
        get() = value?.toMap()

    override fun parseMap(map: Map<String, Any>): Piper.HeaderMatch = HeaderMatchFromMap.invoke(map)

    override val default: Piper.HeaderMatch
        get() = Piper.HeaderMatch.getDefaultInstance()
}

class MessageMatchDialog(mm: Piper.MessageMatch, private val showHeaderMatch: Boolean, parent: Component) : ConfigDialog<Piper.MessageMatch>(parent, "Filter editor") {
    private val prefixField  = HexASCIITextField("prefix",  mm.prefix,  this)
    private val postfixField = HexASCIITextField("postfix", mm.postfix, this)
    private val cciw = CollapsedCommandInvocationWidget(this, mm.cmd, CommandInvocationPurpose.MATCH_FILTER)
    private val chmw = CollapsedHeaderMatchWidget(this, mm.header)
    private val cbNegation = JComboBox(MatchNegation.values())
    private val regExpWidget: RegExpWidget
    private val cbInScope: JCheckBox?
    private val andAlsoPanel = MatchListEditor("All of these apply: [AND]", mm.andAlsoList)
    private val  orElsePanel = MatchListEditor("Any of these apply: [OR]",  mm.orElseList)

    init {
        cs.gridwidth = 4

        panel.add(cbNegation, cs)
        cbNegation.selectedItem = if (mm.negation) MatchNegation.NEGATED else MatchNegation.NORMAL

        cs.gridwidth = 1

        prefixField .addWidgets("Starts with: ", cs, panel)
        postfixField.addWidgets(  "Ends with: ", cs, panel)
        regExpWidget = RegExpWidget(mm.regex, panel, cs)

        if (showHeaderMatch) chmw.buildGUI(panel, cs)

        cciw.buildGUI(panel, cs)

        cbInScope = if (showHeaderMatch) createFullWidthCheckBox("request is in Burp Suite scope", mm.inScope, panel, cs) else null

        val spList = JSplitPane()
        spList.leftComponent = andAlsoPanel
        spList.rightComponent = orElsePanel

        addFullWidthComponent(spList, panel, cs)

        cs.gridy++
    }

    override fun processGUI(): Piper.MessageMatch {
        val builder = Piper.MessageMatch.newBuilder()

        if ((cbNegation.selectedItem as MatchNegation).negation) builder.negation = true

        builder.postfix = postfixField.getByteString()
        builder.prefix  =  prefixField.getByteString()

        if (regExpWidget.hasPattern()) builder.regex = regExpWidget.toRegularExpression()

        if (chmw.value != null) builder.header = chmw.value

        if (cbInScope != null && cbInScope.isSelected) builder.inScope = true

        val cmd = cciw.value
        if (cmd != null) {
            try {
                cmd.checkDependencies()
            } catch (c: DependencyException) {
                if (JOptionPane.showConfirmDialog(panel, "${c.message}\n\nAre you sure you want to save this?",
                                "Confirmation", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) throw CancelClosingWindow()
            }
            builder.cmd = cmd
        }

        builder.addAllAndAlso(andAlsoPanel.items)
        builder.addAllOrElse ( orElsePanel.items)

        return builder.build()
    }

    inner class MatchListEditor(caption: String, source: List<Piper.MessageMatch>) : ClipboardOwner,
            ListEditor<Piper.MessageMatch>(fillDefaultModel(source), this, caption) {
        override fun addDialog(): Piper.MessageMatch? = MessageMatchDialog(Piper.MessageMatch.getDefaultInstance(),
                showHeaderMatch = showHeaderMatch, parent = this).showGUI()

        override fun editDialog(value: Piper.MessageMatch): Piper.MessageMatch? =
                MessageMatchDialog(value, showHeaderMatch = showHeaderMatch, parent = this).showGUI()

        override fun toHumanReadable(value: Piper.MessageMatch): String =
                value.toHumanReadable(negation = false, hideParentheses = true)

        val items: Iterable<Piper.MessageMatch>
            get() = model.toIterable()

        private val btnCopy = JButton("Copy")
        private val btnPaste = JButton("Paste")

        override fun updateBtnEnableDisableState() {
            super.updateBtnEnableDisableState()
            updateEnableDisableBtnState()
        }

        private fun updateEnableDisableBtnState() {
            btnCopy.isEnabled = listWidget.selectedIndices.isNotEmpty()
        }

        init {
            btnCopy.addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(
                        Dump(DumpSettingsBuilder().build()).dumpToString(listWidget.selectedValue.toMap())), this)
            }
            btnPaste.addActionListener {
                val s = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: return@addActionListener
                val ls = Load(LoadSettingsBuilder().build())
                try {
                    model.addElement(messageMatchFromMap(ls.loadFromString(s) as Map<String, Any>))
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(this, e.message)
                }
            }
            pnToolbar.add(btnCopy)
            pnToolbar.add(btnPaste)
            updateEnableDisableBtnState()
        }

        override fun lostOwnership(p0: Clipboard?, p1: Transferable?) {} /* ClipboardOwner */
    }
}

fun <E> createRemoveButton(listWidget: JList<E>, listModel: DefaultListModel<E>): JButton {
    val btn = JButton("Remove")
    btn.isEnabled = listWidget.selectedIndices.isNotEmpty()
    listWidget.addListSelectionListener {
        btn.isEnabled = listWidget.selectedIndices.isNotEmpty()
    }
    btn.addActionListener {
        listWidget.selectedIndices.reversed().map(listModel::remove)
    }
    return btn
}

fun <E> fillDefaultModel(source: Iterable<E>, model: DefaultListModel<E> = DefaultListModel()): DefaultListModel<E> =
        fillDefaultModel(source.asSequence(), model)
fun <E> fillDefaultModel(source: Sequence<E>, model: DefaultListModel<E> = DefaultListModel()): DefaultListModel<E> {
    model.clear()
    source.forEach(model::addElement)
    return model
}

fun showModalDialog(width: Int, height: Int, widget: Component, caption: String, dialog: JDialog, parent: Component?) {
    with(dialog) {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        add(widget)
        setSize(width, height)
        setLocationRelativeTo(parent)
        title = caption
        isModal = true
        isVisible = true
    }
}

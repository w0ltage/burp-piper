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
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import kotlin.math.max

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

class MinimalToolWidget(tool: Piper.MinimalTool, private val panel: Container, cs: GridBagConstraints, w: Window,
                        showPassHeaders: Boolean, purpose: CommandInvocationPurpose, showScope: Boolean, showFilter: Boolean) {
    private val tfName = createLabeledTextField("Name: ", tool.name, panel, cs)
    private val lsScope: JComboBox<ConfigMinimalToolScope>? = if (showScope) createLabeledWidget("Can handle... ",
            JComboBox(ConfigMinimalToolScope.values()).apply { selectedItem = ConfigMinimalToolScope.fromScope(tool.scope) }, panel, cs) else null
    private val cbEnabled: JCheckBox
    private val cciw: CollapsedCommandInvocationWidget = CollapsedCommandInvocationWidget(w, cmd = tool.cmd, purpose = purpose, showPassHeaders = showPassHeaders)
    private val ccmw: CollapsedMessageMatchWidget = CollapsedMessageMatchWidget(w, mm = tool.filter, showHeaderMatch = true, caption = "Filter: ")

    fun toMinimalTool(): Piper.MinimalTool {
        if (tfName.text.isEmpty()) throw RuntimeException("Name cannot be empty.")
        val command = cciw.requireValue()
        try {
            if (cbEnabled.isSelected) command.checkDependencies()
        } catch (c: DependencyException) {
            when (JOptionPane.showConfirmDialog(panel, "${c.message}\n\nAre you sure you want this enabled?")) {
                JOptionPane.NO_OPTION -> cbEnabled.isSelected = false
                JOptionPane.CANCEL_OPTION -> throw CancelClosingWindow()
            }
        }

        return Piper.MinimalTool.newBuilder().apply {
            name = tfName.text
            if (cbEnabled.isSelected) enabled = true
            if (ccmw.value != null) filter = ccmw.value
            if (lsScope != null) scope = (lsScope.selectedItem as ConfigMinimalToolScope).scope
            cmd = command
        }.build()
    }

    fun addFilterChangeListener(listener: ChangeListener<Piper.MessageMatch>) {
        ccmw.addChangeListener(listener)
    }

    init {
        if (showFilter) ccmw.buildGUI(panel, cs)
        cciw.buildGUI(panel, cs)
        cbEnabled = createFullWidthCheckBox("Enabled", tool.enabled, panel, cs)
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

class CollapsedMessageMatchWidget(
    private val window: Window,
    mm: Piper.MessageMatch?,
    private val showHeaderMatch: Boolean,
    private val caption: String,
) {
    private val changeListeners = mutableListOf<ChangeListener<Piper.MessageMatch>>()
    private val enableCheck = JCheckBox("Enable filter")
    private val btnCopy = JButton("Copy")
    private val btnPaste = JButton("Paste")
    private val btnClear = JButton("Clear")
    private val editorPanel = MessageMatchEditorPanel(
        window,
        window,
        showHeaderMatch,
        mm ?: Piper.MessageMatch.getDefaultInstance(),
    )
    private val editorContainer = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        add(editorPanel, BorderLayout.CENTER)
    }
    private var lastDefinedValue: Piper.MessageMatch = mm ?: Piper.MessageMatch.getDefaultInstance()

    var value: Piper.MessageMatch? = mm
        set(newValue) {
            field = newValue
            if (newValue != null) {
                lastDefinedValue = newValue
            }
            updateState()
            notifyListeners()
        }

    init {
        enableCheck.addActionListener {
            if (enableCheck.isSelected) {
                value = lastDefinedValue
            } else {
                readCurrentMatch()?.let { lastDefinedValue = it }
                value = null
            }
        }

        btnCopy.addActionListener {
            val current = value ?: return@addActionListener
            val yaml = Dump(DumpSettingsBuilder().build()).dumpToString(current.toMap())
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(yaml), null)
        }

        btnPaste.addActionListener {
            val clip = Toolkit.getDefaultToolkit().systemClipboard
            val text = clip.getData(DataFlavor.stringFlavor) as? String ?: return@addActionListener
            val loader = Load(LoadSettingsBuilder().build())
            try {
                val parsed = messageMatchFromMap(loader.loadFromString(text) as Map<String, Any>)
                value = parsed
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(window, e.message)
            }
        }

        btnClear.addActionListener {
            readCurrentMatch()?.let { lastDefinedValue = it }
            value = null
        }

        editorPanel.addChangeListener {
            if (enableCheck.isSelected) {
                val updated = readCurrentMatch() ?: return@addChangeListener
                lastDefinedValue = updated
                value = updated
            }
        }

        updateState()
    }

    private fun readCurrentMatch(): Piper.MessageMatch? = try {
        editorPanel.toMessageMatch()
    } catch (e: CancelClosingWindow) {
        null
    } catch (e: RuntimeException) {
        JOptionPane.showMessageDialog(window, e.message)
        null
    }

    private fun setPanelEnabled(component: Component, enabled: Boolean) {
        component.isEnabled = enabled
        if (component is Container) {
            component.components.forEach { setPanelEnabled(it, enabled) }
        }
    }

    private fun updateState() {
        val enabled = value != null
        enableCheck.isSelected = enabled
        btnCopy.isEnabled = enabled
        btnClear.isEnabled = enabled
        editorContainer.isVisible = enabled
        if (enabled) {
            editorPanel.setValue(value ?: lastDefinedValue)
        }
        setPanelEnabled(editorPanel, enabled)
    }

    private fun notifyListeners() {
        changeListeners.forEach { it.valueChanged(value) }
    }

    fun addChangeListener(listener: ChangeListener<Piper.MessageMatch>) {
        changeListeners += listener
        listener.valueChanged(value)
    }

    fun buildGUI(panel: Container, cs: GridBagConstraints) {
        addFullWidthComponent(JLabel(caption), panel, cs)
        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(enableCheck)
            add(btnCopy)
            add(btnPaste)
            add(btnClear)
        }
        addFullWidthComponent(controls, panel, cs)
        addFullWidthComponent(editorContainer, panel, cs)
    }
}

class CollapsedCommandInvocationWidget(
    private val window: Window,
    cmd: Piper.CommandInvocation,
    private val purpose: CommandInvocationPurpose,
    private val showPassHeaders: Boolean = true,
    private val placeholderValues: List<String> = DEFAULT_COMMAND_TOKEN_PLACEHOLDERS,
    private val inlineMode: Boolean = true,
) {

    private val isInline = inlineMode
    private val changeListeners = mutableListOf<ChangeListener<Piper.CommandInvocation>>()
    private val defaultCommand = Piper.CommandInvocation.getDefaultInstance()
    private val enableCheck = if (isInline && purpose == CommandInvocationPurpose.MATCH_FILTER) JCheckBox("Enable command") else null
    private val btnCopy = if (isInline) JButton("Copy") else null
    private val btnPaste = if (isInline) JButton("Paste") else null
    private val btnClear = if (isInline && purpose == CommandInvocationPurpose.MATCH_FILTER) JButton("Clear") else null
    private val editorPanel = if (isInline) {
        CommandInvocationEditorPanel(
            window,
            window,
            cmd,
            purpose,
            showPassHeaders,
            placeholderValues,
        )
    } else null
    private val editorContainer = if (isInline) JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        add(editorPanel, BorderLayout.CENTER)
    } else null
    private val collapsedDelegate = if (!isInline) object : CollapsedWidget<Piper.CommandInvocation>(
        window,
        cmd,
        "Command: ",
        removable = (purpose == CommandInvocationPurpose.MATCH_FILTER),
    ) {
        override fun editDialog(value: Piper.CommandInvocation, parent: Component): Piper.CommandInvocation? =
            CommandInvocationDialog(value, purpose = purpose, parent = parent, showPassHeaders = showPassHeaders).showGUI()

        override fun toHumanReadable(): String =
            (if (purpose == CommandInvocationPurpose.MATCH_FILTER) value?.toHumanReadable(negation = false) else value?.commandLine)
                ?: "(no command)"

        override val asMap: Map<String, Any>?
            get() = value?.toMap()

        override fun parseMap(map: Map<String, Any>): Piper.CommandInvocation = commandInvocationFromMap(map)

        override val default: Piper.CommandInvocation
            get() = defaultCommand
    } else null
    private var suppressEditorUpdates = false
    private var internalValue: Piper.CommandInvocation?
    private var lastDefinedValue: Piper.CommandInvocation

    init {
        if (isInline) {
            lastDefinedValue = if (cmd == defaultCommand) defaultCommand else cmd
            internalValue = if (purpose == CommandInvocationPurpose.MATCH_FILTER && cmd == defaultCommand) null else lastDefinedValue
            editorPanel!!.setValue(lastDefinedValue)

            editorPanel.addChangeListener {
                if (internalValue == null || suppressEditorUpdates) return@addChangeListener
                val updated = readCurrentCommand(showErrors = false) ?: return@addChangeListener
                internalValue = updated
                lastDefinedValue = updated
                notifyListeners()
            }

            enableCheck?.addActionListener {
                if (enableCheck.isSelected) {
                    value = lastDefinedValue
                } else {
                    readCurrentCommand(showErrors = false)?.let { lastDefinedValue = it }
                    internalValue = null
                    updateState()
                    notifyListeners()
                }
            }

            btnCopy?.addActionListener {
                val current = internalValue ?: return@addActionListener
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    StringSelection(Dump(DumpSettingsBuilder().build()).dumpToString(current.toMap())),
                    null,
                )
            }

            btnPaste?.addActionListener {
                val clip = Toolkit.getDefaultToolkit().systemClipboard
                val text = clip.getData(DataFlavor.stringFlavor) as? String ?: return@addActionListener
                val loader = Load(LoadSettingsBuilder().build())
                try {
                    val parsed = commandInvocationFromMap(loader.loadFromString(text) as Map<String, Any>)
                    value = parsed
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(window, e.message)
                }
            }

            btnClear?.addActionListener {
                readCurrentCommand(showErrors = false)?.let { lastDefinedValue = it }
                value = null
            }

            updateState()
        } else {
            internalValue = cmd
            lastDefinedValue = cmd
        }
    }

    var value: Piper.CommandInvocation?
        get() = if (isInline) internalValue else collapsedDelegate?.value
        set(newValue) {
            if (isInline) {
                internalValue = newValue
                if (newValue != null) {
                    lastDefinedValue = newValue
                    suppressEditorUpdates = true
                    editorPanel!!.setValue(newValue)
                    suppressEditorUpdates = false
                }
                updateState()
                notifyListeners()
            } else {
                collapsedDelegate?.value = newValue
            }
        }

    private fun readCurrentCommand(showErrors: Boolean): Piper.CommandInvocation? = try {
        editorPanel!!.buildCommand()
    } catch (e: RuntimeException) {
        if (showErrors) JOptionPane.showMessageDialog(window, e.message)
        null
    }

    private fun updateState() {
        if (!isInline) return
        val enabled = internalValue != null || purpose != CommandInvocationPurpose.MATCH_FILTER
        enableCheck?.isSelected = internalValue != null
        btnCopy?.isEnabled = internalValue != null
        btnClear?.isEnabled = internalValue != null
        editorContainer!!.isVisible = enabled
        setPanelEnabled(editorPanel!!, internalValue != null || purpose != CommandInvocationPurpose.MATCH_FILTER)
    }

    private fun setPanelEnabled(component: Component, enabled: Boolean) {
        component.isEnabled = enabled
        if (component is Container) {
            component.components.forEach { setPanelEnabled(it, enabled) }
        }
    }

    private fun notifyListeners() {
        changeListeners.forEach { it.valueChanged(internalValue) }
    }

    fun requireValue(): Piper.CommandInvocation {
        return if (isInline) {
            val command = readCurrentCommand(showErrors = true)
            if (command != null) {
                internalValue = command
                command
            } else {
                throw CancelClosingWindow()
            }
        } else {
            collapsedDelegate?.value ?: throw CancelClosingWindow()
        }
    }

    fun addChangeListener(listener: ChangeListener<Piper.CommandInvocation>) {
        if (isInline) {
            changeListeners += listener
            listener.valueChanged(internalValue)
        } else {
            collapsedDelegate?.addChangeListener(listener)
        }
    }

    fun buildGUI(panel: Container, cs: GridBagConstraints) {
        if (isInline) {
            addFullWidthComponent(JLabel("Command:"), panel, cs)
            val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                enableCheck?.let { add(it) }
                btnCopy?.let { add(it) }
                btnPaste?.let { add(it) }
                btnClear?.let { add(it) }
            }
            addFullWidthComponent(controls, panel, cs)
            addFullWidthComponent(editorContainer!!, panel, cs)
        } else {
            collapsedDelegate!!.buildGUI(panel, cs)
        }
    }
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
    private val mtw = MinimalToolWidget(common, panel, cs, this, showPassHeaders = showPassHeaders,
            purpose = purpose, showScope = showScope, showFilter = showFilter)

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
        if (cbUsesColors.isSelected) usesColors = true
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

fun <T : Component> createLabeledWidget(caption: String, widget: T, panel: Container, cs: GridBagConstraints): T {
    cs.gridy++
    cs.gridwidth = 1 ; cs.gridx = 0 ; panel.add(JLabel(caption), cs)
    cs.gridwidth = 3 ; cs.gridx = 1 ; panel.add(widget, cs)
    return widget
}

private fun simpleDocumentListener(onChange: () -> Unit): javax.swing.event.DocumentListener = object : javax.swing.event.DocumentListener {
    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
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

const val CMDLINE_INPUT_FILENAME_PLACEHOLDER = INPUT_FILENAME_TOKEN
const val PASS_HTTP_HEADERS_NOTE = "<html>Note: if the above checkbox is <font color='red'>unchecked</font>, messages without a body (such as<br>" +
        "GET/HEAD requests or 204 No Content responses) are <font color='red'>ignored by this tool</font>.</html>"

private class CommandInvocationEditorPanel(
    private val window: Window,
    private val parent: Component?,
    initial: Piper.CommandInvocation,
    private val purpose: CommandInvocationPurpose,
    private val showPassHeaders: Boolean,
    private val placeholderValues: List<String>,
) : JPanel(GridBagLayout()) {

    private val changeListeners = mutableListOf<() -> Unit>()
    private val tokensPanel = CommandTokensPanel(
        onChange = { emitChange() },
        placeholderValues = placeholderValues,
    )
    private val parameterEditor = CommandParameterListEditor(initial.parameterList, parent)
    private val cbPassHeaders = if (showPassHeaders) JCheckBox("Pass HTTP headers to command") else null
    private val dependenciesField = JTextField()
    private val stdoutPanel = if (purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
        CollapsedMessageMatchWidget(window, initial.stdout, showHeaderMatch = false, caption = "Match on stdout: ")
    } else null
    private val stderrPanel = if (purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
        CollapsedMessageMatchWidget(window, initial.stderr, showHeaderMatch = false, caption = "Match on stderr: ")
    } else null
    private val exitCodeField: JTextField?
    private var suppressEvents = false

    init {
        val cs = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            weightx = 1.0
            insets = Insets(4, 0, 4, 0)
        }

        addFullWidthComponent(tokensPanel, this, cs)
        tokensPanel.setTokens(toTokenList(initial))

        addFullWidthComponent(parameterEditor, this, cs)
        parameterEditor.addChangeListener { emitChange() }

        cbPassHeaders?.let { checkbox ->
            addFullWidthComponent(checkbox, this, cs)
            addFullWidthComponent(JLabel(PASS_HTTP_HEADERS_NOTE), this, cs)
            checkbox.addActionListener { emitChange() }
        }

        addFullWidthComponent(JLabel("Binaries required in PATH: (comma separated)"), this, cs)
        dependenciesField.text = initial.extractDependencies().joinToString(separator = ", ")
        addFullWidthComponent(dependenciesField, this, cs)
        dependenciesField.document.addDocumentListener(simpleDocumentListener { emitChange() })

        if (purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
            if (purpose == CommandInvocationPurpose.SELF_FILTER) {
                addFullWidthComponent(JLabel("If any filters are set below, they are treated the same way as a pre-exec filter."), this, cs)
            }
            stdoutPanel!!.buildGUI(this, cs)
            stdoutPanel.addChangeListener(object : ChangeListener<Piper.MessageMatch> {
                override fun valueChanged(value: Piper.MessageMatch?) {
                    emitChange()
                }
            })

            stderrPanel!!.buildGUI(this, cs)
            stderrPanel.addChangeListener(object : ChangeListener<Piper.MessageMatch> {
                override fun valueChanged(value: Piper.MessageMatch?) {
                    emitChange()
                }
            })

            val exitValues = initial.exitCodeList.joinToString(", ")
            val field = createLabeledTextField("Match on exit code: (comma separated) ", exitValues, this, cs)
            field.document.addDocumentListener(simpleDocumentListener { emitChange() })
            exitCodeField = field
        } else {
            exitCodeField = null
        }
    }

    fun setValue(command: Piper.CommandInvocation) {
        suppressEvents = true
        tokensPanel.setTokens(toTokenList(command))
        parameterEditor.setItems(command.parameterList)
        cbPassHeaders?.isSelected = command.passHeaders
        dependenciesField.text = command.extractDependencies().joinToString(separator = ", ")
        if (purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
            stdoutPanel!!.value = command.stdout
            stderrPanel!!.value = command.stderr
            exitCodeField?.text = command.exitCodeList.joinToString(", ")
        }
        suppressEvents = false
    }

    fun buildCommand(): Piper.CommandInvocation {
        return Piper.CommandInvocation.newBuilder().apply {
            if (purpose != CommandInvocationPurpose.EXECUTE_ONLY) {
                if (stdoutPanel?.value != null) stdout = stdoutPanel.value
                if (stderrPanel?.value != null) stderr = stderrPanel.value
                val exitCodesText = exitCodeField?.text.orEmpty()
                if (exitCodesText.isNotBlank()) {
                    try {
                        addAllExitCode(exitCodesText.filterNot(Char::isWhitespace).split(',').map(String::toInt))
                    } catch (e: NumberFormatException) {
                        throw RuntimeException("Exit codes should contain numbers separated by commas only. (Whitespace is ignored.)")
                    }
                }
                val hasExitCodes = exitCodeField?.text?.isNotBlank() == true
                val hasStdout = stdoutPanel?.value != null
                val hasStderr = stderrPanel?.value != null
                if (purpose == CommandInvocationPurpose.MATCH_FILTER && !(hasStdout || hasStderr || hasExitCodes)) {
                    throw RuntimeException("No filters are defined for stdio or exit code.")
                }
            }

            val dependencies = dependenciesField.text.replace("\\s".toRegex(), "")
            if (dependencies.isNotEmpty()) addAllRequiredInPath(dependencies.split(','))

            val tokens = tokensPanel.tokens()
            if (tokens.isEmpty()) {
                throw RuntimeException("The command must contain at least one argument.")
            }
            if (tokens.first().isBlank()) {
                throw RuntimeException("The first argument (the command) cannot be empty.")
            }
            val filenameIndices = tokens.withIndex().filter { it.value == INPUT_FILENAME_TOKEN }.map { it.index }
            if (filenameIndices.size > 1) {
                throw RuntimeException("The ${INPUT_FILENAME_TOKEN} placeholder may only appear once in the command.")
            }
            val placeholderIndex = filenameIndices.firstOrNull()
            val prefixTokens = if (placeholderIndex != null) tokens.subList(0, placeholderIndex) else tokens
            addAllPrefix(prefixTokens)
            if (placeholderIndex != null) {
                if (placeholderIndex == 0) {
                    throw RuntimeException("The ${INPUT_FILENAME_TOKEN} placeholder must appear after the executable name.")
                }
                inputMethod = Piper.CommandInvocation.InputMethod.FILENAME
                val postfixTokens = if (placeholderIndex + 1 < tokens.size) tokens.subList(placeholderIndex + 1, tokens.size) else emptyList()
                addAllPostfix(postfixTokens)
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

    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    private fun emitChange() {
        if (suppressEvents) return
        changeListeners.forEach { it.invoke() }
    }
}

class CommandInvocationDialog(
    ci: Piper.CommandInvocation,
    private val purpose: CommandInvocationPurpose,
    parent: Component,
    showPassHeaders: Boolean,
    placeholderValues: List<String> = DEFAULT_COMMAND_TOKEN_PLACEHOLDERS,
) : ConfigDialog<Piper.CommandInvocation>(parent, "Command invocation editor") {
    private val editor = CommandInvocationEditorPanel(this, parent, ci, purpose, showPassHeaders, placeholderValues)

    init {
        addFullWidthComponent(editor, panel, cs)
    }

    override fun processGUI(): Piper.CommandInvocation = editor.buildCommand()
}

private fun toTokenList(ci: Piper.CommandInvocation): List<String> {
    val tokens = mutableListOf<String>()
    tokens += ci.prefixList
    if (ci.inputMethod == Piper.CommandInvocation.InputMethod.FILENAME) {
        tokens += INPUT_FILENAME_TOKEN
        tokens += ci.postfixList
    }
    return tokens
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

    fun addChangeListener(listener: () -> Unit) {
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent?) = listener()
            override fun intervalRemoved(e: ListDataEvent?) = listener()
            override fun contentsChanged(e: ListDataEvent?) = listener()
        })
    }

    fun setItems(items: List<Piper.CommandInvocation.Parameter>) {
        fillDefaultModel(items, model)
    }
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

fun <E : Component> addFullWidthComponent(c: E, panel: Container, cs: GridBagConstraints): E {
    cs.gridx = 0
    cs.gridy++
    cs.gridwidth = 4

    panel.add(c, cs)
    return c
}

class HexASCIITextField(private val tf: JTextField = JTextField(),
                        private val rbHex: JRadioButton = JRadioButton("Hex"),
                        private val rbASCII: JRadioButton = JRadioButton("ASCII"),
                        private val field: String, private var isASCII: Boolean) {

    constructor(field: String, source: ByteString, dialog: Component) : this(field=field, isASCII=source.isValidUtf8) {
        if (isASCII) {
            tf.text = source.toStringUtf8()
            rbASCII.isSelected = true
        } else {
            tf.text = source.toHexPairs()
            rbHex.isSelected = true
        }

        with(ButtonGroup()) { add(rbHex); add(rbASCII); }

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

    fun getByteString(): ByteString = if (isASCII) ByteString.copyFromUtf8(tf.text) else try {
        ByteString.copyFrom(parseHex())
    } catch (e: NumberFormatException) {
        throw RuntimeException("Error in $field field: hexadecimal string ${e.message}")
    }

    fun addChangeListener(listener: () -> Unit) {
        tf.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = listener()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = listener()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = listener()
        })
        rbHex.addActionListener { listener() }
        rbASCII.addActionListener { listener() }
    }

    fun addWidgets(caption: String, cs: GridBagConstraints, panel: Container) {
        cs.gridy++
        cs.gridx = 0 ; panel.add(JLabel(caption), cs)
        cs.gridx = 1 ; panel.add(tf,      cs)
        cs.gridx = 2 ; panel.add(rbASCII, cs)
        cs.gridx = 3 ; panel.add(rbHex,   cs)
    }
}

private fun parseHexByte(cs: CharSequence): Byte = (parseHexNibble(cs[0]) shl 4 or parseHexNibble(cs[1])).toByte()

private fun parseHexNibble(c: Char): Int = if (c in '0'..'9') (c - '0')
else ((c.toLowerCase() - 'a') + 0xA)

class RegExpWidget(regex: Piper.RegularExpression, panel: Container, cs: GridBagConstraints) {
    private val tfPattern = createLabeledTextField("Matches regular expression: ", regex.pattern, panel, cs)
    private val esw = EnumSetWidget(regex.flagSet, panel, cs, "Regular expression flags: (see JDK documentation)", RegExpFlag::class.java)

    fun hasPattern(): Boolean = tfPattern.text.isNotEmpty()

    fun toRegularExpression(): Piper.RegularExpression {
        return Piper.RegularExpression.newBuilder().setPattern(tfPattern.text).setFlagSet(esw.toSet()).build().apply { compile() }
    }

    fun setValue(regex: Piper.RegularExpression) {
        tfPattern.text = regex.pattern
        esw.setSelected(regex.flagSet)
    }

    fun addChangeListener(listener: () -> Unit) {
        tfPattern.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = listener()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = listener()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = listener()
        })
        esw.addChangeListener(listener)
    }
}

class EnumSetWidget<E : Enum<E>>(set: Set<E>, panel: Container, cs: GridBagConstraints, caption: String, enumClass: Class<E>) {
    private val cbMap: Map<E, JCheckBox>

    fun toSet(): Set<E> = cbMap.filterValues(JCheckBox::isSelected).keys

    fun setSelected(values: Set<E>) {
        cbMap.forEach { (flag, checkbox) ->
            checkbox.isSelected = flag in values
        }
    }

    fun addChangeListener(listener: () -> Unit) {
        cbMap.values.forEach { checkbox ->
            checkbox.addActionListener { listener() }
        }
    }

    init {
        addFullWidthComponent(JLabel(caption), panel, cs)
        cs.gridy++
        cs.gridwidth = 1

        cbMap = enumClass.enumConstants.asIterable().associateWithTo(EnumMap(enumClass)) {
            val cb = createCheckBox(it.toString(), it in set, panel, cs)
            if (cs.gridx == 0) {
                cs.gridx = 1
            } else {
                cs.gridy++
                cs.gridx = 0
            }
            cb
        }.toMap()
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

private class MessageMatchListEditor(
    caption: String,
    source: List<Piper.MessageMatch>,
    private val host: Component?,
    private val showHeaderMatch: Boolean,
    private val onChange: () -> Unit,
) : ClipboardOwner, ListEditor<Piper.MessageMatch>(fillDefaultModel(source), host, caption) {
    private val btnCopy = JButton("Copy")
    private val btnPaste = JButton("Paste")

    init {
        btnCopy.addActionListener {
            val value = listWidget.selectedValue ?: return@addActionListener
            Toolkit.getDefaultToolkit().systemClipboard.setContents(
                StringSelection(Dump(DumpSettingsBuilder().build()).dumpToString(value.toMap())),
                this,
            )
        }
        btnPaste.addActionListener {
            val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
                ?: return@addActionListener
            val loader = Load(LoadSettingsBuilder().build())
            try {
                val pasted = messageMatchFromMap(loader.loadFromString(text) as Map<String, Any>)
                model.addElement(pasted)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(host ?: this, e.message)
            }
        }
        pnToolbar.add(btnCopy)
        pnToolbar.add(btnPaste)
        updateCopyButtonState()
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent?) = onChange()
            override fun intervalRemoved(e: ListDataEvent?) = onChange()
            override fun contentsChanged(e: ListDataEvent?) = onChange()
        })
    }

    override fun addDialog(): Piper.MessageMatch? = MessageMatchDialog(
        Piper.MessageMatch.getDefaultInstance(),
        showHeaderMatch = showHeaderMatch,
        parent = host ?: this,
    ).showGUI()

    override fun editDialog(value: Piper.MessageMatch): Piper.MessageMatch? = MessageMatchDialog(
        value,
        showHeaderMatch = showHeaderMatch,
        parent = host ?: this,
    ).showGUI()

    override fun toHumanReadable(value: Piper.MessageMatch): String =
        value.toHumanReadable(negation = false, hideParentheses = true)

    fun items(): List<Piper.MessageMatch> = model.toIterable().toList()

    fun setItems(items: List<Piper.MessageMatch>) {
        fillDefaultModel(items, model)
        onChange()
    }

    override fun updateBtnEnableDisableState() {
        super.updateBtnEnableDisableState()
        updateCopyButtonState()
    }

    override fun lostOwnership(p0: Clipboard?, p1: Transferable?) {} /* ClipboardOwner */

    private fun updateCopyButtonState() {
        btnCopy.isEnabled = listWidget.selectedIndices.isNotEmpty()
    }
}

private class MessageMatchEditorPanel(
    private val window: Window,
    private val parent: Component?,
    private val showHeaderMatch: Boolean,
    initial: Piper.MessageMatch,
) : JPanel(GridBagLayout()) {
    private val changeListeners = mutableListOf<() -> Unit>()
    private val cbNegation = JComboBox(MatchNegation.values())
    private val prefixField = HexASCIITextField("prefix", initial.prefix, window)
    private val postfixField = HexASCIITextField("postfix", initial.postfix, window)
    private val regExpWidget: RegExpWidget
    private val chmw = if (showHeaderMatch) CollapsedHeaderMatchWidget(window, initial.header) else null
    private val cciw = CollapsedCommandInvocationWidget(
        window,
        initial.cmd,
        CommandInvocationPurpose.MATCH_FILTER,
        inlineMode = false,
    )
    private val cbInScope: JCheckBox?
    private val andAlsoPanel = MessageMatchListEditor(
        "All of these apply: [AND]",
        initial.andAlsoList,
        parent,
        showHeaderMatch,
        ::emitChange,
    )
    private val orElsePanel = MessageMatchListEditor(
        "Any of these apply: [OR]",
        initial.orElseList,
        parent,
        showHeaderMatch,
        ::emitChange,
    )
    private var suppressChanges = false

    init {
        val cs = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = 4
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }

        add(cbNegation, cs)
        cbNegation.selectedItem = if (initial.negation) MatchNegation.NEGATED else MatchNegation.NORMAL
        cbNegation.addActionListener { emitChange() }

        cs.gridwidth = 1

        prefixField.addWidgets("Starts with: ", cs, this)
        postfixField.addWidgets("Ends with: ", cs, this)
        regExpWidget = RegExpWidget(initial.regex, this, cs)

        if (showHeaderMatch) {
            chmw!!.buildGUI(this, cs)
            chmw.addChangeListener(object : ChangeListener<Piper.HeaderMatch> {
                override fun valueChanged(value: Piper.HeaderMatch?) {
                    emitChange()
                }
            })
        }

        cciw.buildGUI(this, cs)
        cciw.addChangeListener(object : ChangeListener<Piper.CommandInvocation> {
            override fun valueChanged(value: Piper.CommandInvocation?) {
                emitChange()
            }
        })

        cbInScope = if (showHeaderMatch) createFullWidthCheckBox("request is in Burp Suite scope", initial.inScope, this, cs) else null
        cbInScope?.addActionListener { emitChange() }

        val spList = JSplitPane()
        spList.leftComponent = andAlsoPanel
        spList.rightComponent = orElsePanel

        addFullWidthComponent(spList, this, cs)

        prefixField.addChangeListener { emitChange() }
        postfixField.addChangeListener { emitChange() }
        regExpWidget.addChangeListener { emitChange() }
    }

    fun setValue(match: Piper.MessageMatch) {
        suppressChanges = true
        cbNegation.selectedItem = if (match.negation) MatchNegation.NEGATED else MatchNegation.NORMAL
        prefixField.setValue(match.prefix)
        postfixField.setValue(match.postfix)
        if (match.hasRegex()) {
            regExpWidget.setValue(match.regex)
        } else {
            regExpWidget.setValue(Piper.RegularExpression.getDefaultInstance())
        }
        if (showHeaderMatch) {
            chmw!!.value = match.header
        }
        cciw.value = match.cmd
        cbInScope?.isSelected = match.inScope
        andAlsoPanel.setItems(match.andAlsoList)
        orElsePanel.setItems(match.orElseList)
        suppressChanges = false
    }

    fun toMessageMatch(): Piper.MessageMatch {
        val builder = Piper.MessageMatch.newBuilder()
        if ((cbNegation.selectedItem as MatchNegation).negation) builder.negation = true

        builder.postfix = postfixField.getByteString()
        builder.prefix = prefixField.getByteString()

        if (regExpWidget.hasPattern()) builder.regex = regExpWidget.toRegularExpression()
        if (showHeaderMatch && chmw!!.value != null) builder.header = chmw.value
        if (cbInScope?.isSelected == true) builder.inScope = true

        val cmd = cciw.value
        if (cmd != null) {
            try {
                cmd.checkDependencies()
            } catch (c: DependencyException) {
                if (JOptionPane.showConfirmDialog(
                        parent,
                        "${c.message}\n\nAre you sure you want to save this?",
                        "Confirmation",
                        JOptionPane.YES_NO_OPTION,
                    ) != JOptionPane.YES_OPTION
                ) throw CancelClosingWindow()
            }
            builder.cmd = cmd
        }

        builder.addAllAndAlso(andAlsoPanel.items())
        builder.addAllOrElse(orElsePanel.items())
        return builder.build()
    }

    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    private fun emitChange() {
        if (!suppressChanges) {
            changeListeners.forEach { it.invoke() }
        }
    }
}

class MessageMatchDialog(mm: Piper.MessageMatch, private val showHeaderMatch: Boolean, parent: Component) : ConfigDialog<Piper.MessageMatch>(parent, "Filter editor") {
    private val editor = MessageMatchEditorPanel(this, parent, showHeaderMatch, mm)

    init {
        addFullWidthComponent(editor, panel, cs)
        cs.gridy++
    }

    override fun processGUI(): Piper.MessageMatch = editor.toMessageMatch()
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

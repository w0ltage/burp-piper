package burp

import com.google.protobuf.ByteString
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Container
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
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
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JLabel
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
import javax.swing.text.JTextComponent
import javax.swing.ListSelectionModel
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
    val template: T?,
    val scope: Piper.MinimalTool.Scope? = null,
)

open class WorkspaceHeaderPanel<T>(
    private val templateLabel: String,
    private val onChange: () -> Unit,
    private val includeTemplateField: Boolean = true,
) : JPanel(GridBagLayout()) {

    val nameField = JTextField()
    val enabledToggle = JToggleButton("Enabled")
    val templateCombo = JComboBox<T>()
    private val scopeLabel = JLabel("Scope")
    private val scopeCombo = JComboBox(WorkspaceScopeOption.values())

    private var templateRowIndex = -1
    private var templateRowNextGridx = 2
    private var suppressTemplateEvent = false
    private var suppressScopeEvent = false
    private var scopeVisible = true
    private var scopeEnabled = true

    init {
        border = EmptyBorder(12, 12, 12, 12)

        var row = 0
        addLabel("Name", 0, row)
        addComponent(nameField, gridx = 1, gridy = row, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL)
        addComponent(enabledToggle, gridx = 2, gridy = row)

        if (includeTemplateField) {
            row++
            templateRowIndex = row
            addLabel(templateLabel, 0, row)
            addComponent(templateCombo, gridx = 1, gridy = row, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL)
        }

        if (includeTemplateField && templateRowIndex >= 0) {
            addScopeAlongsideTemplate()
        } else {
            row++
            addScopeAsRow(row)
        }

        nameField.document.addDocumentListener(WorkspaceDocumentListener { onChange() })
        enabledToggle.addActionListener { onChange() }
        if (includeTemplateField) {
            templateCombo.addActionListener {
                if (!suppressTemplateEvent) {
                    onChange()
                }
            }
        }
        scopeCombo.addActionListener {
            if (!suppressScopeEvent) {
                onChange()
            }
        }

        withScopeChangeSuppressed {
            scopeCombo.selectedItem = WorkspaceScopeOption.from(null)
        }
    }

    protected fun addTemplateField(label: String, component: JComponent) {
        if (!includeTemplateField || templateRowIndex < 0) return
        addLabel(label, templateRowNextGridx, templateRowIndex)
        addComponent(component, gridx = templateRowNextGridx + 1, gridy = templateRowIndex)
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
        if (includeTemplateField) {
            templateCombo.isEnabled = enabled
        }
        scopeCombo.isEnabled = enabled && scopeEnabled && scopeVisible
    }

    fun readValues(): WorkspaceHeaderValues<T> = WorkspaceHeaderValues(
        name = nameField.text,
        enabled = enabledToggle.isSelected,
        template = if (includeTemplateField) templateCombo.selectedItem as? T else null,
        scope = if (scopeVisible) {
            (scopeCombo.selectedItem as? WorkspaceScopeOption)?.scope
        } else {
            null
        },
    )

    fun setValues(values: WorkspaceHeaderValues<T>) {
        nameField.text = values.name
        enabledToggle.isSelected = values.enabled
        if (includeTemplateField) {
            withTemplateChangeSuppressed {
                when {
                    values.template != null -> templateCombo.selectedItem = values.template
                    templateCombo.itemCount > 0 -> templateCombo.selectedIndex = 0
                    else -> templateCombo.selectedIndex = -1
                }
            }
        }
        withScopeChangeSuppressed {
            scopeCombo.selectedItem = WorkspaceScopeOption.from(values.scope)
        }
    }

    fun setScopeEnabled(enabled: Boolean) {
        scopeEnabled = enabled
        scopeCombo.isEnabled = enabled && scopeVisible
    }

    fun setScopeVisible(visible: Boolean) {
        scopeVisible = visible
        scopeLabel.isVisible = visible
        scopeCombo.isVisible = visible
        scopeCombo.isEnabled = scopeEnabled && visible
        revalidate()
        repaint()
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

    private inline fun withScopeChangeSuppressed(block: () -> Unit) {
        suppressScopeEvent = true
        try {
            block()
        } finally {
            suppressScopeEvent = false
        }
    }

    private fun addScopeAlongsideTemplate() {
        val labelConstraints = createConstraints(templateRowNextGridx, templateRowIndex)
        add(scopeLabel, labelConstraints)
        addComponent(
            scopeCombo,
            gridx = templateRowNextGridx + 1,
            gridy = templateRowIndex,
            weightx = 1.0,
            fill = GridBagConstraints.HORIZONTAL,
        )
        templateRowNextGridx += 2
    }

    private fun addScopeAsRow(row: Int) {
        val labelConstraints = createConstraints(0, row)
        add(scopeLabel, labelConstraints)
        addComponent(
            scopeCombo,
            gridx = 1,
            gridy = row,
            weightx = 1.0,
            fill = GridBagConstraints.HORIZONTAL,
        )
    }

}

enum class WorkspaceScopeOption(val scope: Piper.MinimalTool.Scope, private val label: String) {
    BOTH(Piper.MinimalTool.Scope.REQUEST_RESPONSE, "Requests & Responses"),
    REQUEST(Piper.MinimalTool.Scope.REQUEST_ONLY, "Requests"),
    RESPONSE(Piper.MinimalTool.Scope.RESPONSE_ONLY, "Responses"),
    ;

    override fun toString(): String = label

    companion object {
        fun from(scope: Piper.MinimalTool.Scope?): WorkspaceScopeOption = when (scope) {
            Piper.MinimalTool.Scope.REQUEST_ONLY -> REQUEST
            Piper.MinimalTool.Scope.RESPONSE_ONLY -> RESPONSE
            else -> BOTH
        }
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

class MessageMatchEditorPanel(
    parent: Component?,
    private val showHeaderMatch: Boolean = true,
) : JPanel(BorderLayout()) {

    private val enableToggle = JCheckBox("Enable filter")
    private val form = MessageMatchForm(parent, showHeaderMatch)
    private val listeners = mutableListOf<() -> Unit>()
    private var suppressNotifications = false

    init {
        border = EmptyBorder(8, 8, 8, 8)
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        enableToggle.addActionListener {
            updateFormEnabled()
            notifyChanged()
        }
        enableToggle.alignmentX = Component.LEFT_ALIGNMENT
        container.add(enableToggle)
        container.add(Box.createVerticalStrut(8))
        form.alignmentX = Component.LEFT_ALIGNMENT
        container.add(form)
        add(container, BorderLayout.NORTH)
        form.addChangeListener { notifyChanged() }
        updateFormEnabled()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    fun display(value: Piper.MessageMatch?) {
        suppressNotifications = true
        try {
            val hasValue = value != null && !value.equals(Piper.MessageMatch.getDefaultInstance())
            enableToggle.isSelected = hasValue
            form.display(value ?: Piper.MessageMatch.getDefaultInstance())
            updateFormEnabled()
        } finally {
            suppressNotifications = false
        }
    }

    fun value(): Piper.MessageMatch? {
        if (!enableToggle.isSelected) {
            return null
        }
        return form.snapshot()
    }

    private fun updateFormEnabled() {
        form.setFormEnabled(enableToggle.isSelected)
    }

    private fun notifyChanged() {
        if (suppressNotifications) return
        listeners.forEach { it.invoke() }
    }
}

private class MessageMatchForm(
    private val parent: Component?,
    private val showHeaderMatch: Boolean,
) : JPanel(GridBagLayout()) {

    private val changeListeners = mutableListOf<() -> Unit>()
    private var suppressNotifications = false
    private val negationBox = JComboBox(MatchNegation.values())
    private val prefixField = HexASCIITextField("prefix", ByteString.EMPTY, parent ?: this)
    private val postfixField = HexASCIITextField("postfix", ByteString.EMPTY, parent ?: this)
    private val regexWidget: RegExpWidget
    private val headerEditor = if (showHeaderMatch) HeaderMatchEditorPanel(parent) else null
    private val commandEditor = CommandInvocationEditor(
        parent,
        CommandInvocationPurpose.MATCH_FILTER,
        showPassHeaders = false,
    )
    private val inScopeCheck = if (showHeaderMatch) JCheckBox("Request is in Burp Suite scope") else null
    private val andPanel = MessageMatchListPanel(parent, "All of these apply: [AND]", showHeaderMatch)
    private val orPanel = MessageMatchListPanel(parent, "Any of these apply: [OR]", showHeaderMatch)

    init {
        val cs = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 4)
        }

        val previousFill = cs.fill
        val previousGridWidth = cs.gridwidth
        cs.gridwidth = 4
        cs.fill = GridBagConstraints.HORIZONTAL
        add(negationBox, cs)
        cs.gridwidth = previousGridWidth
        cs.fill = previousFill

        prefixField.addWidgets("Starts with:", cs, this)
        prefixField.addChangeListener { notifyChanged() }
        postfixField.addWidgets("Ends with:", cs, this)
        postfixField.addChangeListener { notifyChanged() }
        regexWidget = RegExpWidget(Piper.RegularExpression.getDefaultInstance(), this, cs)
        regexWidget.addChangeListener { notifyChanged() }

        headerEditor?.let {
            cs.gridy++
            add(it, cs)
            it.addChangeListener { notifyChanged() }
        }

        cs.gridy++
        add(commandEditor, cs)

        inScopeCheck?.let { check ->
            cs.gridy++
            add(check, cs)
        }

        cs.gridy++
        cs.fill = GridBagConstraints.BOTH
        cs.weighty = 1.0
        val lists = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        lists.leftComponent = andPanel
        lists.rightComponent = orPanel
        lists.resizeWeight = 0.5
        add(lists, cs)

        negationBox.addActionListener { notifyChanged() }
        commandEditor.addChangeListener { notifyChanged() }
        inScopeCheck?.addActionListener { notifyChanged() }
        andPanel.addChangeListener { notifyChanged() }
        orPanel.addChangeListener { notifyChanged() }
    }

    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    fun display(value: Piper.MessageMatch) {
        withSuppressedChanges {
            negationBox.selectedItem = if (value.negation) MatchNegation.NEGATED else MatchNegation.NORMAL
            prefixField.setValue(value.prefix)
            postfixField.setValue(value.postfix)
            if (value.hasRegex()) {
                regexWidget.setValue(value.regex)
            } else {
                regexWidget.setValue(Piper.RegularExpression.getDefaultInstance())
            }
            headerEditor?.display(value.header)
            if (value.hasCmd()) {
                commandEditor.display(value.cmd)
            } else {
                commandEditor.display(Piper.CommandInvocation.getDefaultInstance())
            }
            inScopeCheck?.isSelected = value.inScope
            andPanel.setItems(value.andAlsoList)
            orPanel.setItems(value.orElseList)
        }
    }

    fun snapshot(): Piper.MessageMatch {
        val builder = Piper.MessageMatch.newBuilder()
        if ((negationBox.selectedItem as? MatchNegation)?.negation == true) {
            builder.negation = true
        }
        builder.prefix = prefixField.getByteString()
        builder.postfix = postfixField.getByteString()
        if (regexWidget.hasPattern()) {
            builder.regex = regexWidget.toRegularExpression()
        }
        headerEditor?.toHeaderMatch()?.let { builder.header = it }
        val command = runCatching { commandEditor.snapshot() }.getOrElse { error ->
            val message = error.message.orEmpty()
            if (message.contains("at least one token", ignoreCase = true)) {
                Piper.CommandInvocation.getDefaultInstance()
            } else {
                throw RuntimeException(message.ifEmpty { "Invalid command configuration" }, error)
            }
        }
        if (command != Piper.CommandInvocation.getDefaultInstance()) {
            builder.cmd = command
        }
        if (inScopeCheck?.isSelected == true) {
            builder.inScope = true
        }
        builder.addAllAndAlso(andPanel.items())
        builder.addAllOrElse(orPanel.items())
        return builder.build()
    }

    fun setFormEnabled(enabled: Boolean) {
        fun Component.setEnabledRecursively(value: Boolean) {
            isEnabled = value
            if (this is Container) {
                components.forEach { it.setEnabledRecursively(value) }
            }
        }
        this.setEnabledRecursively(enabled)
    }

    private fun notifyChanged() {
        if (suppressNotifications) return
        changeListeners.forEach { it.invoke() }
    }

    private inline fun withSuppressedChanges(block: () -> Unit) {
        suppressNotifications = true
        try {
            block()
        } finally {
            suppressNotifications = false
        }
    }
}

private class HeaderMatchEditorPanel(parent: Component?) : JPanel(GridBagLayout()) {
    private val headerField: JComboBox<String>
    private val regexWidget: RegExpWidget
    private val listeners = mutableListOf<() -> Unit>()

    init {
        border = BorderFactory.createTitledBorder("Header match")
        val cs = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
        }
        headerField = createLabeledComboBox(
            "Header name (case insensitive)",
            "",
            this,
            cs,
            arrayOf(
                "Content-Disposition",
                "Content-Type",
                "Cookie",
                "Host",
                "Origin",
                "Referer",
                "Server",
                "User-Agent",
                "X-Requested-With",
            ),
        )
        regexWidget = RegExpWidget(Piper.RegularExpression.getDefaultInstance(), this, cs)
        regexWidget.addChangeListener { notifyListeners() }
        headerField.addActionListener { notifyListeners() }
        (headerField.editor.editorComponent as? JTextComponent)?.document?.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = notifyListeners()
            override fun removeUpdate(e: DocumentEvent?) = notifyListeners()
            override fun changedUpdate(e: DocumentEvent?) = notifyListeners()
        })
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    fun display(value: Piper.HeaderMatch) {
        if (value == Piper.HeaderMatch.getDefaultInstance()) {
            headerField.selectedItem = ""
            regexWidget.setValue(Piper.RegularExpression.getDefaultInstance())
        } else {
            headerField.selectedItem = value.header
            regexWidget.setValue(value.regex)
        }
    }

    fun toHeaderMatch(): Piper.HeaderMatch? {
        val text = headerField.selectedItem?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            return null
        }
        val regex = if (regexWidget.hasPattern()) regexWidget.toRegularExpression() else null
        return Piper.HeaderMatch.newBuilder().apply {
            header = text
            if (regex != null) {
                this.regex = regex
            }
        }.build()
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }
}

private class MessageMatchListPanel(
    private val parent: Component?,
    caption: String,
    private val showHeaderMatch: Boolean,
) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<Piper.MessageMatch>()
    private val list = JList(model)
    private val listeners = mutableListOf<() -> Unit>()

    init {
        border = BorderFactory.createTitledBorder(caption)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val scroll = JScrollPane(list)
        add(scroll, BorderLayout.CENTER)

        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.X_AXIS)
        val addButton = JButton("Add")
        val editButton = JButton("Edit")
        val removeButton = JButton("Remove")

        addButton.addActionListener {
            val created = MessageMatchDialog(
                Piper.MessageMatch.getDefaultInstance(),
                showHeaderMatch,
                parent ?: this,
            ).showGUI() ?: return@addActionListener
            model.addElement(created)
            notifyChanged()
        }

        editButton.addActionListener {
            val selectedIndex = list.selectedIndex
            if (selectedIndex >= 0) {
                val current = model.getElementAt(selectedIndex)
                val edited = MessageMatchDialog(current, showHeaderMatch, parent ?: this).showGUI()
                if (edited != null) {
                    model.set(selectedIndex, edited)
                    notifyChanged()
                }
            }
        }

        removeButton.addActionListener {
            val index = list.selectedIndex
            if (index >= 0) {
                model.remove(index)
                notifyChanged()
            }
        }

        list.addListSelectionListener {
            val hasSelection = list.selectedIndex >= 0
            editButton.isEnabled = hasSelection
            removeButton.isEnabled = hasSelection
        }

        toolbar.add(addButton)
        toolbar.add(Box.createRigidArea(Dimension(4, 0)))
        toolbar.add(editButton)
        toolbar.add(Box.createRigidArea(Dimension(4, 0)))
        toolbar.add(removeButton)
        toolbar.add(Box.createHorizontalGlue())
        add(toolbar, BorderLayout.SOUTH)
        editButton.isEnabled = false
        removeButton.isEnabled = false
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    fun setItems(items: List<Piper.MessageMatch>) {
        model.removeAllElements()
        items.forEach(model::addElement)
    }

    fun items(): List<Piper.MessageMatch> = (0 until model.size()).map(model::getElementAt)

    private fun notifyChanged() {
        listeners.forEach { it.invoke() }
    }
}

class WorkspaceFilterPanel(
    parent: Component?,
    private val onChange: () -> Unit,
) : JPanel(BorderLayout()) {

    private val filterPanel = MessageMatchEditorPanel(parent)
    private val summaryLabel = JLabel("Filter description → (none)")
    private val sampleLabel = JLabel("Matched sample: –")
    private val listeners = mutableListOf<(Piper.MessageMatch?) -> Unit>()

    init {
        border = EmptyBorder(12, 12, 12, 12)
        val scroll = JScrollPane(filterPanel).apply {
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = 16
        }
        add(scroll, BorderLayout.CENTER)
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
            notifyListeners()
        }
    }

    fun display(filter: Piper.MessageMatch?) {
        filterPanel.display(filter)
        updateSummary()
    }

    fun value(): Piper.MessageMatch? = filterPanel.value()

    fun addChangeListener(listener: (Piper.MessageMatch?) -> Unit) {
        listeners += listener
    }

    private fun updateSummary() {
        val value = filterPanel.value()
        summaryLabel.text = "Filter description → " +
            (value?.toHumanReadable(negation = false, hideParentheses = true) ?: "(none)")
        sampleLabel.text = "Matched sample: preview unavailable"
    }

    private fun notifyListeners() {
        val value = filterPanel.value()
        listeners.forEach { it(value) }
    }
}

data class WorkspaceCommandState(
    val command: Piper.CommandInvocation,
    val usesAnsi: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val passHeaders: Boolean = false,
)

class WorkspaceCommandPanel(
    parent: Component?,
    onChange: (() -> Unit)? = null,
    private val purpose: CommandInvocationPurpose = CommandInvocationPurpose.SELF_FILTER,
    private val showAnsiCheckbox: Boolean = true,
    private val showDependenciesField: Boolean = true,
    private val dependenciesLabelText: String = "Binaries required in PATH (comma separated)",
    private val showPassHeadersToggle: Boolean = false,
) : JPanel(BorderLayout()) {

    private val commandEditor = CommandInvocationEditor(
        parent,
        purpose,
        config = CommandInvocationEditorConfig(
            showParametersTab = false,
            showInputTab = false,
            showFiltersTab = false,
            showDependenciesField = false,
        ),
    )
    private val ansiCheck = if (showAnsiCheckbox) JCheckBox("Uses ANSI (color) escape sequences") else null
    private val dependenciesField = if (showDependenciesField) JTextField() else null
    private val listeners = mutableListOf<() -> Unit>().apply { onChange?.let(::add) }
    private var applyingState = false
    private val passHeadersControls = if (showPassHeadersToggle) {
        createPassHeadersControls(commandEditor.passHeaders()) { value ->
            commandEditor.setPassHeaders(value)
            if (!applyingState) {
                notifyChanged()
            }
        }
    } else {
        null
    }

    init {
        border = EmptyBorder(12, 12, 12, 12)
        val content = JPanel()
        content.layout = GridBagLayout()
        val cs = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            weightx = 1.0
            fill = GridBagConstraints.BOTH
            insets = Insets(4, 0, 4, 0)
        }
        content.add(commandEditor, cs)

        passHeadersControls?.let { controls ->
            cs.gridy++
            cs.fill = GridBagConstraints.HORIZONTAL
            content.add(controls.checkbox, cs)
            cs.gridy++
            content.add(controls.note, cs)
        }

        ansiCheck?.let {
            cs.gridy++
            cs.fill = GridBagConstraints.HORIZONTAL
            content.add(it, cs)
            it.addChangeListener {
                if (!applyingState) {
                    notifyChanged()
                }
            }
        }

        dependenciesField?.let { field ->
            cs.gridy++
            content.add(JLabel(dependenciesLabelText), cs)
            cs.gridy++
            content.add(field, cs)
            field.document.addDocumentListener(WorkspaceDocumentListener {
                if (!applyingState) {
                    notifyChanged()
                }
            })
        }

        commandEditor.addChangeListener {
            if (!applyingState) {
                notifyChanged()
            }
        }

        val scroll = JScrollPane(content)
        add(scroll, BorderLayout.CENTER)
        display(null)
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    fun display(state: WorkspaceCommandState?) {
        applyingState = true
        try {
            commandEditor.display(state?.command ?: Piper.CommandInvocation.getDefaultInstance())
            val dependencies = state?.dependencies?.joinToString(", ") ?: ""
            ansiCheck?.isSelected = state?.usesAnsi ?: false
            dependenciesField?.text = dependencies
            val passHeaders = state?.passHeaders ?: false
            passHeadersControls?.checkbox?.isSelected = passHeaders
            commandEditor.setPassHeaders(passHeaders)
        } finally {
            applyingState = false
        }
    }

    fun snapshot(): WorkspaceCommandState {
        val passHeaders = passHeadersControls?.checkbox?.isSelected ?: commandEditor.passHeaders()
        commandEditor.setPassHeaders(passHeaders)
        val command = runCatching { commandEditor.snapshot() }.getOrElse { error ->
            throw RuntimeException(error.message ?: "Invalid command configuration", error)
        }
        val dependencies = dependenciesField?.text
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?: emptyList()
        return WorkspaceCommandState(
            command = command,
            usesAnsi = ansiCheck?.isSelected ?: false,
            dependencies = dependencies,
            passHeaders = passHeaders,
        )
    }

    fun passHeaders(): Boolean = commandEditor.passHeaders()

    fun setPassHeaders(value: Boolean) {
        val wasApplying = applyingState
        applyingState = true
        try {
            passHeadersControls?.checkbox?.isSelected = value
            commandEditor.setPassHeaders(value)
        } finally {
            applyingState = wasApplying
        }
        if (!wasApplying) {
            notifyChanged()
        }
    }

    fun isAnsiSelected(): Boolean = ansiCheck?.isSelected ?: false

    private fun notifyChanged() {
        listeners.forEach { it.invoke() }
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


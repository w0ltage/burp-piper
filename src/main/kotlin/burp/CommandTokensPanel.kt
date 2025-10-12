package burp

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

private val DEFAULT_TOKEN_PLACEHOLDERS = listOf(
    "\${BASE}",
    "\${PAYLOAD_INDEX}",
    "\${dialog0}",
    "\${domain}",
    INPUT_FILENAME_TOKEN,
)

class CommandTokensPanel(
    private val onChange: () -> Unit = {},
    private val placeholderValues: List<String> = DEFAULT_TOKEN_PLACEHOLDERS,
) : JPanel(BorderLayout()) {

    private val tokensModel = DefaultListModel<String>()
    private val tokensList = JList(tokensModel)

    init {
        border = javax.swing.border.EmptyBorder(8, 8, 8, 8)
        val title = JLabel("Command tokens")
        title.font = title.font.deriveFont(Font.BOLD)
        add(title, BorderLayout.NORTH)

        tokensList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tokensList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = tokensList.locationToIndex(e.point)
                    if (index >= 0) {
                        val current = tokensModel[index]
                        val edited = JOptionPane.showInputDialog(this@CommandTokensPanel, "Edit token", current) ?: return
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
        val tokenField = javax.swing.JTextField()
        tokenField.columns = 20
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
        addRow.add(addSpacing())
        addRow.add(addButton)

        val placeholderButton = JButton("Add placeholder")
        placeholderButton.addActionListener { showPlaceholderMenu(placeholderButton) }
        addRow.add(addSpacing())
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
        actions.add(addSpacing())
        actions.add(wrap)
        actions.add(addSpacing())
        actions.add(moveUp)
        actions.add(addSpacing())
        actions.add(moveDown)
        actions.add(Box.createHorizontalGlue())
        footer.add(Box.createRigidArea(Dimension(0, 6)))
        footer.add(actions)

        footer.add(Box.createRigidArea(Dimension(0, 6)))
        val help = JLabel("Use placeholders like \"\${dialog0}\". ${INPUT_FILENAME_TOKEN} inserts the temp file when using filename mode.")
        footer.add(help)

        add(footer, BorderLayout.SOUTH)
    }

    private fun addSpacing(): Component = Box.createRigidArea(Dimension(4, 0))

    fun setTokens(tokens: List<String>) {
        tokensModel.clear()
        tokens.forEach(tokensModel::addElement)
    }

    fun tokens(): List<String> = (0 until tokensModel.size()).map(tokensModel::getElementAt)

    private fun showPlaceholderMenu(anchor: Component) {
        val menu = JPopupMenu()
        placeholderValues.forEach { placeholder ->
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

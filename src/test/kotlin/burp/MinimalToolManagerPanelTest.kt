package burp

import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.SwingUtilities

class MinimalToolManagerPanelTest {

    @Test
    fun enablingContextMenuItemSavesEnabledState() {
        val model = DefaultListModel<Piper.UserActionTool>()
        model.addElement(sampleUserActionTool("Context menu sample"))

        val panel = createMenuItemManager(model, /* parent = */ null)

        enableFirstToolViaMinimalPanel(panel)

        val updated = model.getElementAt(0)
        assertTrue(updated.common.enabled, "Context menu item should remain enabled after saving")
    }

    @Test
    fun disablingContextMenuItemSavesDisabledState() {
        val model = DefaultListModel<Piper.UserActionTool>()
        model.addElement(sampleUserActionTool("Context menu sample").buildEnabled(true))

        val panel = createMenuItemManager(model, /* parent = */ null)

        disableFirstToolViaMinimalPanel(panel)

        val updated = model.getElementAt(0)
        assertFalse(updated.common.enabled, "Context menu item should remain disabled after saving")
    }

    @Test
    fun togglingPassHeadersInContextMenuBehaviorTabPersists() {
        val model = DefaultListModel<Piper.UserActionTool>()
        model.addElement(sampleUserActionTool("Context menu sample"))

        val panel = createMenuItemManager(model, /* parent = */ null)

        togglePassHeaders(panel, enabled = true)
        val enabledTool = model.getElementAt(0)
        assertTrue(enabledTool.common.cmd.passHeaders, "Context menu item should enable pass headers after saving")

        togglePassHeaders(panel, enabled = false)
        val disabledTool = model.getElementAt(0)
        assertFalse(disabledTool.common.cmd.passHeaders, "Context menu item should disable pass headers after saving")
    }

    @Test
    fun enablingMacroSavesEnabledState() {
        val model = DefaultListModel<Piper.MinimalTool>()
        model.addElement(sampleMinimalTool("Macro sample"))

        val panel = createMacroManager(model, /* parent = */ null)

        enableFirstToolViaMinimalPanel(panel)

        val updated = model.getElementAt(0)
        assertTrue(updated.enabled, "Macro should remain enabled after saving")
    }

    @Test
    fun enablingHttpListenerSavesEnabledState() {
        val model = DefaultListModel<Piper.HttpListener>()
        model.addElement(sampleHttpListener("HTTP listener sample"))

        val panel = createHttpListenerManager(model, /* parent = */ null)

        enableFirstToolViaMinimalPanel(panel)

        val updated = model.getElementAt(0)
        assertTrue(updated.common.enabled, "HTTP listener should remain enabled after saving")
    }

    @Test
    fun enablingIntruderPayloadProcessorSavesEnabledState() {
        val model = DefaultListModel<Piper.MinimalTool>()
        model.addElement(sampleMinimalTool("Processor sample"))

        val panel = createIntruderPayloadProcessorManager(model, /* parent = */ null)

        enableFirstToolViaMinimalPanel(panel)

        val updated = model.getElementAt(0)
        assertTrue(updated.enabled, "Payload processor should remain enabled after saving")
    }

    @Test
    fun enablingHighlighterSavesEnabledState() {
        val model = DefaultListModel<Piper.Highlighter>()
        model.addElement(sampleHighlighter("Highlighter sample"))

        val panel = createHighlighterManager(model, /* parent = */ null)

        enableFirstToolViaMinimalPanel(panel)

        val updated = model.getElementAt(0)
        assertTrue(updated.common.enabled, "Highlighter should remain enabled after saving")
    }

    @Test
    fun enablingCommentatorSavesEnabledState() {
        val model = DefaultListModel<Piper.Commentator>()
        model.addElement(sampleCommentator("Commentator sample"))

        val panel = createCommentatorManager(model, /* parent = */ null)

        enableViewerPanel(panel)

        val updated = model.getElementAt(0)
        assertTrue(updated.common.enabled, "Commentator should remain enabled after saving")
    }

    private fun enableFirstToolViaMinimalPanel(panel: Component) {
        SwingUtilities.invokeAndWait {
            val panelClass = panel.javaClass
            val listField = panelClass.getDeclaredField("list").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val list = listField.get(panel) as JList<Any?>
            if (list.selectedIndex < 0 && list.model.size > 0) {
                list.selectedIndex = 0
            }

            val editorField = panelClass.getDeclaredField("editor").apply { isAccessible = true }
            val editor = editorField.get(panel)
            val widgetField = findField(editor, "widget")
            val widget = widgetField.get(editor) ?: error("Minimal tool widget should be available")
            val headerField = findField(widget, "header")
            val header = headerField.get(widget) as WorkspaceHeaderPanel<*>

            if (!header.enabledToggle.isSelected) {
                header.enabledToggle.doClick()
            }

            val saveButtonField = panelClass.getDeclaredField("saveButton").apply { isAccessible = true }
            val saveButton = saveButtonField.get(panel) as JButton
            check(saveButton.isEnabled) { "Save button should be enabled after toggling" }
            saveButton.doClick()
        }
    }

    private fun enableViewerPanel(panel: Component) {
        SwingUtilities.invokeAndWait {
            val panelClass = panel.javaClass
            val listField = panelClass.getDeclaredField("list").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val list = listField.get(panel) as JList<Any?>
            if (list.selectedIndex < 0 && list.model.size > 0) {
                list.selectedIndex = 0
            }

            val headerField = panelClass.getDeclaredField("header").apply { isAccessible = true }
            val header = headerField.get(panel) as WorkspaceHeaderPanel<*>
            if (!header.enabledToggle.isSelected) {
                header.enabledToggle.doClick()
            }

            val saveButtonField = panelClass.getDeclaredField("saveButton").apply { isAccessible = true }
            val saveButton = saveButtonField.get(panel) as JButton
            check(saveButton.isEnabled) { "Save button should be enabled after toggling" }
            saveButton.doClick()
        }
    }

    private fun disableFirstToolViaMinimalPanel(panel: Component) {
        SwingUtilities.invokeAndWait {
            val panelClass = panel.javaClass
            val listField = panelClass.getDeclaredField("list").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val list = listField.get(panel) as JList<Any?>
            if (list.selectedIndex < 0 && list.model.size > 0) {
                list.selectedIndex = 0
            }

            val editorField = panelClass.getDeclaredField("editor").apply { isAccessible = true }
            val editor = editorField.get(panel)
            val widgetField = findField(editor, "widget")
            val widget = widgetField.get(editor) ?: error("Minimal tool widget should be available")
            val headerField = findField(widget, "header")
            val header = headerField.get(widget) as WorkspaceHeaderPanel<*>

            if (header.enabledToggle.isSelected) {
                header.enabledToggle.doClick()
            }

            val saveButtonField = panelClass.getDeclaredField("saveButton").apply { isAccessible = true }
            val saveButton = saveButtonField.get(panel) as JButton
            check(saveButton.isEnabled) { "Save button should be enabled after toggling" }
            saveButton.doClick()
        }
    }

    private fun togglePassHeaders(panel: Component, enabled: Boolean) {
        SwingUtilities.invokeAndWait {
            val panelClass = panel.javaClass
            val listField = panelClass.getDeclaredField("list").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val list = listField.get(panel) as JList<Any?>
            if (list.selectedIndex < 0 && list.model.size > 0) {
                list.selectedIndex = 0
            }

            val editorField = panelClass.getDeclaredField("editor").apply { isAccessible = true }
            val editor = editorField.get(panel)
            val widgetField = findField(editor, "widget")
            val widget = widgetField.get(editor) ?: error("Minimal tool widget should be available")
            val passHeaders = requireNotNull(findCheckbox(widget as Component, "Pass HTTP headers to command")) {
                "Pass headers checkbox should be available"
            }
            if (passHeaders.isSelected != enabled) {
                passHeaders.doClick()
            }

            val saveButtonField = panelClass.getDeclaredField("saveButton").apply { isAccessible = true }
            val saveButton = saveButtonField.get(panel) as JButton
            if (saveButton.isEnabled) {
                saveButton.doClick()
            }
        }
    }

    private fun findCheckbox(root: Component, label: String): JCheckBox? {
        if (root is JCheckBox && root.text == label) {
            return root
        }
        if (root is java.awt.Container) {
            root.components.forEach { child ->
                val match = findCheckbox(child, label)
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }

    private fun sampleCommand(): Piper.CommandInvocation =
        Piper.CommandInvocation.newBuilder()
            .setInputMethod(Piper.CommandInvocation.InputMethod.STDIN)
            .addAllPrefix(listOf("/usr/bin/env", "cat"))
            .build()

    private fun sampleMinimalTool(name: String): Piper.MinimalTool =
        Piper.MinimalTool.newBuilder()
            .setName(name)
            .setScope(Piper.MinimalTool.Scope.REQUEST_RESPONSE)
            .setCmd(sampleCommand())
            .build()

    private fun sampleUserActionTool(name: String): Piper.UserActionTool =
        Piper.UserActionTool.newBuilder()
            .setCommon(sampleMinimalTool(name))
            .build()

    private fun sampleHttpListener(name: String): Piper.HttpListener =
        Piper.HttpListener.newBuilder()
            .setScope(Piper.HttpListenerScope.REQUEST)
            .setCommon(sampleMinimalTool(name))
            .build()

    private fun sampleHighlighter(name: String): Piper.Highlighter =
        Piper.Highlighter.newBuilder()
            .setCommon(sampleMinimalTool(name))
            .setColor(Highlight.ORANGE.toString())
            .build()

    private fun sampleCommentator(name: String): Piper.Commentator =
        Piper.Commentator.newBuilder()
            .setCommon(sampleMinimalTool(name))
            .build()

    private fun findField(instance: Any, name: String): java.lang.reflect.Field {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}

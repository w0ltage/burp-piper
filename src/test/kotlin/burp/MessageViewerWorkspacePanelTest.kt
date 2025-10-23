package burp

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import javax.swing.JButton
import javax.swing.JList
import javax.swing.SwingUtilities
import burp.WorkspaceHeaderPanel

class MessageViewerWorkspacePanelTest {

    @Test
    fun enablingViewerThroughWorkspaceSavesEnabledState() {
        val config = ConfigModel(loadDefaultConfig())
        val model = config.messageViewersModel

        // find Python JSON formatter index in model
        val index = (0 until model.size).first { model.getElementAt(it).common.name == "Python JSON formatter" }

        val panel = createMessageViewerManager(
            model,
            /* parent = */ null,
            config.commentatorsModel,
            switchToCommentator = {},
        ) as MessageViewerWorkspacePanel

        val originalViewer = model.getElementAt(index)

        var saveButton: JButton? = null
        SwingUtilities.invokeAndWait {
            val listField = MessageViewerWorkspacePanel::class.java.getDeclaredField("list").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val list = listField.get(panel) as JList<Piper.MessageViewer>

            list.selectedIndex = index

            val headerField = MessageViewerWorkspacePanel::class.java.getDeclaredField("header").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val header = headerField.get(panel) as WorkspaceHeaderPanel<Any?>
            assertTrue(header.enabledToggle.isEnabled, "Toggle should start enabled")
            val initialState = header.enabledToggle.isSelected
            header.enabledToggle.doClick()
            val toggledState = header.enabledToggle.isSelected
            assertTrue(!initialState && toggledState || initialState && toggledState, "Toggle should end up selected")


            val saveButtonField = MessageViewerWorkspacePanel::class.java.getDeclaredField("saveButton").apply { isAccessible = true }
            saveButton = saveButtonField.get(panel) as JButton
        }

        val button = requireNotNull(saveButton)
        assertTrue(button.isEnabled, "Save button should be enabled after toggling")

        var enabledBeforeSave = false
        var modelIndex: Int? = null
        val collectState = MessageViewerWorkspacePanel::class.java.getDeclaredMethod("collectStateFromUI").apply { isAccessible = true }
        SwingUtilities.invokeAndWait {
            val editorState = collectState.invoke(panel)
            val enabledField = editorState.javaClass.getDeclaredField("enabled").apply { isAccessible = true }
            enabledBeforeSave = enabledField.getBoolean(editorState)
            val indexField = editorState.javaClass.getDeclaredField("modelIndex").apply { isAccessible = true }
            modelIndex = indexField.get(editorState) as Int?
        }
        assertTrue(enabledBeforeSave, "Editor state should reflect enabled toggle before saving")
        assertTrue(modelIndex != null && modelIndex!! >= 0, "Editor state should retain model index")
        assertEquals(index, modelIndex!!, "Editor state index should match model index")

        SwingUtilities.invokeAndWait {
            button.doClick()
        }

        val updatedViewer = model.getElementAt(index)
        assertTrue(updatedViewer !== originalViewer, "Model entry should be replaced with new instance")
        assertTrue(updatedViewer.common.enabled, "Viewer should be enabled after saving from workspace")
    }
}

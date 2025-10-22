package burp

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListModel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class DefaultListModelTest {

    @Test
    fun printEventIndicesForSetElementAt() {
        val model = DefaultListModel<Piper.MessageViewer>()
        model.addElement(messageViewer(enabled = false))

        val start = AtomicInteger(-2)
        val end = AtomicInteger(-2)
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) {}
            override fun intervalRemoved(e: ListDataEvent) {}
            override fun contentsChanged(e: ListDataEvent) {
                start.set(e.index0)
                end.set(e.index1)
            }
        })

        model.setElementAt(messageViewer(enabled = true), 0)

        assertEquals(start.get(), 0)
        assertEquals(end.get(), 0)
    }

    @Test
    fun enableViewerUpdatesModel() {
        val config = loadDefaultConfig()
        val model = ConfigModel(config).messageViewersModel

        val targetIndex = (0 until model.size).first { model.getElementAt(it).common.name == "Python JSON formatter" }

        assertFalse(model.getElementAt(targetIndex).common.enabled)

        val enabledViewer = model.getElementAt(targetIndex).buildEnabled(true)
        model.setElementAt(enabledViewer, targetIndex)

        assertTrue(model.getElementAt(targetIndex).common.enabled)
    }

    private fun messageViewer(enabled: Boolean): Piper.MessageViewer {
        val minimal = Piper.MinimalTool.newBuilder()
            .setName("Test")
            .setEnabled(enabled)
            .build()
        return Piper.MessageViewer.newBuilder()
            .setCommon(minimal)
            .build()
    }
}

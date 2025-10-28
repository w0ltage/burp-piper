package burp

import burp.ui.AnsiTextPane
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MontoyaExtensionConsolePumpTest {

    @Test
    fun `pumpStream flushes output before process exits`() {
        val extension = MontoyaExtension()
        val pumpMethod = MontoyaExtension::class.java.getDeclaredMethod(
            "pumpStream",
            InputStream::class.java,
            AnsiTextPane::class.java,
        ).apply { isAccessible = true }

        val pipeInput = PipedInputStream()
        val pipeOutput = PipedOutputStream(pipeInput)
        val pane = AnsiTextPane()

        val latch = CountDownLatch(1)
        val edtFlag = AtomicBoolean(false)
        val textRef = AtomicReference<String>()

        pane.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                edtFlag.set(SwingUtilities.isEventDispatchThread())
                updateText(textRef, pane)
                latch.countDown()
            }

            override fun removeUpdate(e: DocumentEvent) {}

            override fun changedUpdate(e: DocumentEvent) {}
        })

        pumpMethod.invoke(extension, pipeInput, pane)

        pipeOutput.write("hello\n".toByteArray())
        pipeOutput.flush()

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(textRef.get(), "hello\n")
        assertTrue(edtFlag.get())

        pipeOutput.close()
        pipeInput.close()
    }

    private fun updateText(destination: AtomicReference<String>, pane: AnsiTextPane) {
        if (SwingUtilities.isEventDispatchThread()) {
            destination.set(pane.text)
            return
        }
        try {
            SwingUtilities.invokeAndWait { destination.set(pane.text) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: InvocationTargetException) {
            // ignore failures during test readback
        }
    }
}

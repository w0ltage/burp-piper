package burp

import java.awt.Component
import javax.swing.JCheckBox
import javax.swing.JLabel

const val PASS_HTTP_HEADERS_NOTE = "Note: if the above checkbox is unchecked, messages without a body (such as\n" +
    "GET/HEAD requests or 204 No Content responses) are ignored by this tool."

data class PassHeadersControls(
    val checkbox: JCheckBox,
    val note: JLabel,
)

fun createPassHeadersControls(
    initialValue: Boolean,
    onToggle: (Boolean) -> Unit,
): PassHeadersControls {
    val checkbox = JCheckBox("Pass HTTP headers to command").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        isSelected = initialValue
        addActionListener { onToggle(isSelected) }
    }
    val note = JLabel(PASS_HTTP_HEADERS_NOTE).apply {
        alignmentX = Component.LEFT_ALIGNMENT
    }
    return PassHeadersControls(checkbox, note)
}

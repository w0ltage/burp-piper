package burp

import java.awt.Component
import javax.swing.JCheckBox
import javax.swing.JLabel

const val PASS_HTTP_HEADERS_NOTE = "<html>Note: if the above checkbox is <font color='red'>unchecked</font>, messages without a" +
    " body (such as<br>" +
    "GET/HEAD requests or 204 No Content responses) are <font color='red'>ignored by this tool</font>.</html>"

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

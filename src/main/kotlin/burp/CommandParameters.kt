package burp

import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.Insets
import java.util.Locale
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

private val PLACEHOLDER_REGEX = Regex("\\$\\{([A-Za-z0-9_]+)}")
private const val ENVIRONMENT_PREFIX = "PIPER_PARAM_"

val Piper.CommandInvocation.Parameter.displayName: String
    get() = if (label.isNullOrBlank()) name else label

fun Piper.CommandInvocation.Parameter.descriptionOrNull(): String? =
    if (description.isNullOrBlank()) null else description

fun Piper.CommandInvocation.resolveParameterValues(provided: Map<String, String>): Map<String, String> {
    if (parameterCount == 0 && provided.isEmpty()) {
        return emptyMap()
    }
    val resolved = linkedMapOf<String, String>()
    val missing = mutableListOf<String>()
    for (parameter in parameterList) {
        val key = parameter.name
        val providedValue = provided[key]
        val finalValue = when {
            !providedValue.isNullOrEmpty() -> providedValue
            !parameter.defaultValue.isNullOrEmpty() -> parameter.defaultValue
            else -> ""
        }
        if (finalValue.isEmpty() && parameter.required) {
            missing += parameter.displayName
        }
        resolved[key] = finalValue
    }
    if (missing.isNotEmpty()) {
        throw IllegalArgumentException("Missing required parameters: ${missing.joinToString(", ")}")
    }
    for ((key, value) in provided) {
        if (!resolved.containsKey(key)) {
            resolved[key] = value
        }
    }
    return resolved
}

fun String.containsParameterPlaceholder(): Boolean = PLACEHOLDER_REGEX.containsMatchIn(this)

fun String.applyParameters(parameters: Map<String, String>): String {
    if (parameters.isEmpty()) {
        return this
    }
    return PLACEHOLDER_REGEX.replace(this) { matchResult ->
        val key = matchResult.groupValues[1]
        parameters[key]
            ?: throw IllegalArgumentException("No value provided for parameter \"$key\"")
    }
}

fun Piper.CommandInvocation.applyParametersTo(args: List<String>, parameters: Map<String, String>): List<String> =
    args.map { it.applyParameters(parameters) }

fun Piper.CommandInvocation.parameterEnvironment(parameters: Map<String, String>): Map<String, String> {
    if (parameters.isEmpty()) {
        return emptyMap()
    }
    return parameters.mapKeys { (key, _) ->
        ENVIRONMENT_PREFIX + key.uppercase(Locale.ROOT)
    }
}

fun promptForCommandParameters(
    parent: Component?,
    toolName: String,
    command: Piper.CommandInvocation,
): Map<String, String>? {
    if (command.parameterCount == 0) {
        return emptyMap()
    }
    val parameters = command.parameterList
    val values = linkedMapOf<String, String>()
    for (parameter in parameters) {
        values[parameter.name] = parameter.defaultValue.orEmpty()
    }
    while (true) {
        val (panel, fields) = buildParameterPromptPanel(parameters, values)
        val option = JOptionPane.showConfirmDialog(
            parent,
            panel,
            "Parameters for $toolName",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (option != JOptionPane.OK_OPTION) {
            return null
        }
        var error: String? = null
        for (parameter in parameters) {
            val fieldValue = fields[parameter.name]!!.text
            val finalValue = if (fieldValue.isEmpty()) parameter.defaultValue.orEmpty() else fieldValue
            if (finalValue.isEmpty() && parameter.required) {
                error = "\"${parameter.displayName}\" is required."
                break
            }
            values[parameter.name] = finalValue
        }
        if (error == null) {
            return parameters.associate { it.name to values[it.name].orEmpty() }
        }
        JOptionPane.showMessageDialog(parent, error, "Missing value", JOptionPane.ERROR_MESSAGE)
    }
}

private fun buildParameterPromptPanel(
    parameters: List<Piper.CommandInvocation.Parameter>,
    currentValues: Map<String, String>,
): Pair<JPanel, Map<String, JTextField>> {
    val panel = JPanel(java.awt.GridBagLayout())
    val constraints = GridBagConstraints().apply {
        gridx = 0
        gridy = 0
        insets = Insets(4, 4, 4, 4)
        fill = GridBagConstraints.HORIZONTAL
        weightx = 1.0
    }
    val fields = linkedMapOf<String, JTextField>()
    for (parameter in parameters) {
        val displayName = buildString {
            append(parameter.displayName)
            if (parameter.required && parameter.defaultValue.isNullOrEmpty()) {
                append(" *")
            }
        }
        val label = JLabel(displayName)
        panel.add(label, constraints)
        constraints.gridx = 1
        val field = JTextField(currentValues[parameter.name] ?: "")
        field.columns = 30
        panel.add(field, constraints)
        fields[parameter.name] = field
        constraints.gridx = 0
        constraints.gridy++
        val description = parameter.descriptionOrNull()
        if (description != null) {
            val descriptionLabel = JLabel("<html><i>${description.replace("\n", "<br>")}</i></html>")
            constraints.gridwidth = 2
            panel.add(descriptionLabel, constraints)
            constraints.gridwidth = 1
            constraints.gridy++
        }
    }
    constraints.gridwidth = 2
    constraints.gridx = 0
    val helper = JLabel("Use placeholders like \\\"${'$'}{name}\\\" in the command to reference the values.")
    panel.add(helper, constraints)
    return panel to fields
}

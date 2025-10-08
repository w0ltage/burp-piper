package burp

import burp.Piper.CommandInvocation

const val GENERATOR_TAG_PREFIX = "@tag:"
private const val TYPE_METADATA_PREFIX = "@type="

enum class ParameterInputType {
    TEXT,
    NUMBER,
    SELECT,
    FILE,
}

data class GeneratorParameterMetadata(
    val type: ParameterInputType,
    val description: String,
)

fun CommandInvocation.extractTags(): List<String> =
    requiredInPathList.filter { it.startsWith(GENERATOR_TAG_PREFIX) }
        .map { it.removePrefix(GENERATOR_TAG_PREFIX) }

fun CommandInvocation.extractDependencies(): List<String> =
    requiredInPathList.filterNot { it.startsWith(GENERATOR_TAG_PREFIX) }

fun mergeDependenciesAndTags(
    dependencies: List<String>,
    tags: List<String>,
): List<String> {
    if (tags.isEmpty()) {
        return dependencies
    }
    val tagEntries = tags.filter { it.isNotBlank() }.map { GENERATOR_TAG_PREFIX + it.trim() }
    return dependencies + tagEntries
}

fun decodeParameterMetadata(rawDescription: String?): GeneratorParameterMetadata {
    if (rawDescription.isNullOrEmpty()) {
        return GeneratorParameterMetadata(ParameterInputType.TEXT, "")
    }
    if (!rawDescription.startsWith(TYPE_METADATA_PREFIX)) {
        return GeneratorParameterMetadata(ParameterInputType.TEXT, rawDescription)
    }
    val body = rawDescription.removePrefix(TYPE_METADATA_PREFIX)
    val newlineIndex = body.indexOf('\n')
    val typeName: String
    val description: String
    if (newlineIndex >= 0) {
        typeName = body.substring(0, newlineIndex)
        description = body.substring(newlineIndex + 1)
    } else {
        typeName = body
        description = ""
    }
    val type = typeName.toParameterInputType()
    return GeneratorParameterMetadata(type, description)
}

fun encodeParameterMetadata(metadata: GeneratorParameterMetadata): String {
    val trimmedDescription = metadata.description.trimEnd()
    return when (metadata.type) {
        ParameterInputType.TEXT ->
            if (trimmedDescription.isEmpty()) "" else trimmedDescription
        else -> {
            val header = TYPE_METADATA_PREFIX + metadata.type.name.lowercase()
            if (trimmedDescription.isEmpty()) header else header + '\n' + trimmedDescription
        }
    }
}

private fun String.toParameterInputType(): ParameterInputType =
    runCatching { ParameterInputType.valueOf(uppercase()) }.getOrDefault(ParameterInputType.TEXT)

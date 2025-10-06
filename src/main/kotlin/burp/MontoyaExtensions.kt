/*
 * This file is part of Piper for Burp Suite (https://github.com/silentsignal/burp-piper)
 * Copyright (c) 2018 Andras Veres-Szentkiralyi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.utilities.Utilities
import com.google.protobuf.ByteString
import java.io.InputStream
import java.net.URL

/**
 * Montoya API-compatible extension functions for Piper functionality. These functions replace the
 * legacy Extensions.kt functions that used IBurpExtenderCallbacks and IExtensionHelpers.
 */

////////////////////////////////////// MATCHING - MONTOYA API //////////////////////////////////////

/**
 * Check if a MinimalTool can process the given message data using Montoya API.
 *
 * @param md List of MessageInfo to check
 * @param mims MessageInfoMatchStrategy to apply
 * @param utilities Montoya Utilities instance
 * @param montoyaApi MontoyaApi instance
 * @return true if the tool can process the message data
 */
fun Piper.MinimalTool.canProcess(
        md: List<MessageInfo>,
        mims: MessageInfoMatchStrategy,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean =
        !this.hasFilter() || mims.predicate(md) { this.filter.matches(it, utilities, montoyaApi) }

/**
 * Check if a MessageMatch matches the given MessageInfo using Montoya API.
 *
 * @param message MessageInfo to check against
 * @param utilities Montoya Utilities instance
 * @param montoyaApi MontoyaApi instance
 * @return true if the message matches the filter criteria
 */
fun Piper.MessageMatch.matches(
        message: MessageInfo,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean =
        ((this.prefix == null ||
                this.prefix.size() == 0 ||
                message.content.startsWith(this.prefix)) &&
                (this.postfix == null ||
                        this.postfix.size() == 0 ||
                        message.content.endsWith(this.postfix)) &&
                (!this.hasRegex() || this.regex.matches(message.text, utilities, montoyaApi)) &&
                (!this.hasHeader() || matchesHeader(message.headers, utilities, montoyaApi)) &&
                (!this.hasCmd() || this.cmd.matches(message.content, utilities, montoyaApi)) &&
                (!this.inScope || isInScope(message.url, montoyaApi)) &&
                (this.andAlsoCount == 0 ||
                        this.andAlsoList.all { it.matches(message, utilities, montoyaApi) }) &&
                (this.orElseCount == 0 ||
                        this.orElseList.any { it.matches(message, utilities, montoyaApi) })) xor
                this.negation

/**
 * Check if a MessageMatch matches the given InputStream using Montoya API.
 *
 * @param stream InputStream to check against
 * @param utilities Montoya Utilities instance
 * @param montoyaApi MontoyaApi instance
 * @return true if the stream content matches the filter criteria
 */
fun Piper.MessageMatch.matches(
        stream: InputStream,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean = this.matches(stream.readBytes(), utilities, montoyaApi)

/**
 * Check if a MessageMatch matches the given byte array using Montoya API.
 *
 * @param data ByteArray to check against
 * @param utilities Montoya Utilities instance
 * @param montoyaApi MontoyaApi instance
 * @return true if the data matches the filter criteria
 */
fun Piper.MessageMatch.matches(
        data: ByteArray,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean =
        this.matches(
                MessageInfo(
                        data,
                        utilities.bytesToString(utilities.byteArrayToByteArray(data)).toString(),
                        headers = null,
                        url = null
                ),
                utilities,
                montoyaApi
        )

/**
 * Check if a CommandInvocation matches the given byte array using Montoya API.
 *
 * @param subject ByteArray to process with the command
 * @param utilities Montoya Utilities instance
 * @param montoyaApi MontoyaApi instance
 * @return true if the command execution matches the criteria
 */
fun Piper.CommandInvocation.matches(
        subject: ByteArray,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean {
    val (process, tempFiles) = this.execute(subject)

    return try {
        val stderrMatches =
                if (this.hasStderr()) {
                    this.stderr.matches(process.errorStream, utilities, montoyaApi)
                } else true

        val stdoutMatches =
                if (this.hasStdout()) {
                    this.stdout.matches(process.inputStream, utilities, montoyaApi)
                } else true

        val exitCodeMatches =
                if (this.exitCodeCount > 0) {
                    val exitCode = process.waitFor()
                    this.exitCodeList.contains(exitCode)
                } else true

        stderrMatches && stdoutMatches && exitCodeMatches
    } catch (e: Exception) {
        montoyaApi.logging().logToError("Error executing command ${this.commandLine}: ${e.message}")
        false
    } finally {
        tempFiles.forEach { it.delete() }
    }
}

/**
 * Check if a RegularExpression matches the given text using Montoya API.
 *
 * @param text Text to match against
 * @param utilities Montoya Utilities instance
 * @param montoyaApi MontoyaApi instance
 * @return true if the regex matches the text
 */
fun Piper.RegularExpression.matches(
        text: String,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean {
    return try {
        val pattern = java.util.regex.Pattern.compile(this.pattern, this.flags)
        pattern.matcher(text).find()
    } catch (e: Exception) {
        montoyaApi.logging().logToError("Error in regex pattern '${this.pattern}': ${e.message}")
        false
    }
}

/**
 * Compile a RegularExpression into a Java Pattern using Montoya API.
 *
 * @return Compiled Pattern object
 */
fun Piper.RegularExpression.compile(): java.util.regex.Pattern =
        java.util.regex.Pattern.compile(this.pattern, this.flags)

/**
 * Check if headers match the header filter using Montoya API.
 *
 * @param headers List of header strings to check
 * @param utilities Montoya Utilities instance
 * @param montoyaApi MontoyaApi instance
 * @return true if headers match the criteria
 */
private fun Piper.MessageMatch.matchesHeader(
        headers: List<String>?,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean {
    if (!this.hasHeader() || headers == null) return true

    val headerMatch = this.header
    return headers.any { headerLine ->
        if (headerLine.lowercase().startsWith(headerMatch.header.lowercase() + ":")) {
            val headerValue = headerLine.substring(headerMatch.header.length + 1).trim()
            headerMatch.regex.matches(headerValue, utilities, montoyaApi)
        } else false
    }
}

/**
 * Extension functions for enhanced Montoya API functionality and legacy compatibility. These
 * functions provide utility methods for common operations and maintain compatibility with the
 * original Piper extension functionality.
 */

/** Get human-readable representation of any object */
fun Any?.toHumanReadable(): String {
    return when (this) {
        null -> "None"
        is Enum<*> -> (this as Enum<*>).toHumanReadable()
        is String -> this
        is Number -> this.toString()
        is Boolean -> if (this) "Yes" else "No"
        is Collection<*> -> this.joinToString(", ") { it.toHumanReadable() }
        else -> this.toString()
    }
}

/** Convert CommandInvocation to command line string representation */
val Piper.CommandInvocation.commandLine: String
    get() =
            sequence {
                        yieldAll(this@commandLine.prefixList.map(::shellQuote))
                        if (this@commandLine.inputMethod ==
                                        Piper.CommandInvocation.InputMethod.FILENAME
                        )
                                yield(CMDLINE_INPUT_FILENAME_PLACEHOLDER)
                        yieldAll(this@commandLine.postfixList.map(::shellQuote))
                    }
                    .joinToString(separator = " ")
                    .truncateTo(64)

/** Shell quote a string for command line usage */
fun shellQuote(s: String): String =
        if (!s.contains(Regex("[\"\\s\\\\]"))) s
        else '"' + s.replace(Regex("[\"\\\\]")) { "\\" + it.groups[0]!!.value } + '"'

/** Truncate string to specified character limit */
fun String.truncateTo(charLimit: Int): String =
        if (length < charLimit) this else this.substring(0, charLimit) + "..."

const val CMDLINE_INPUT_FILENAME_PLACEHOLDER = "<INPUT>"

/** Check if command dependencies are available */
fun checkDependencies(command: String): Boolean {
    return try {
        val process = ProcessBuilder(command, "--help").redirectErrorStream(true).start()
        val exitCode = process.waitFor()
        exitCode == 0 || exitCode == 1 // Some tools return 1 for --help
    } catch (e: Exception) {
        false
    }
}

/** Exception thrown when dependencies are not met */
class DependencyException(dependency: String) :
        RuntimeException("Dependent executable `$dependency` cannot be found in \$PATH")

/** Check dependencies for a CommandInvocation */
fun Piper.CommandInvocation.checkDependencies() {
    val s = sequence {
        if (prefixCount != 0) yield(getPrefix(0)!!)
        yieldAll(requiredInPathList)
    }
    throw DependencyException(s.firstOrNull { !findExecutable(it) } ?: return)
}

/** Find if an executable exists in PATH */
private fun findExecutable(name: String): Boolean {
    val endings =
            if ("Windows" in System.getProperty("os.name"))
                    listOf("", ".cmd", ".exe", ".com", ".bat")
            else listOf("")
    return sequence {
        yield(null) // current directory
        yieldAll(
                System.getenv()
                        .filterKeys { it.equals("PATH", ignoreCase = true) }
                        .values
                        .map { it.split(java.io.File.pathSeparator) }
                        .flatten()
        )
    }
            .any { parent ->
                endings.any { ending -> canExecute(java.io.File(parent, name + ending)) }
            }
}

/** Check if file can be executed */
private fun canExecute(f: java.io.File): Boolean = f.exists() && !f.isDirectory && f.canExecute()

/** Execute a MinimalTool and return the result */
fun Piper.MinimalTool.execute(utilities: Utilities): String {
    return try {
        val (process, tempFiles) = this.cmd.execute("")
        val result = getStdoutWithErrorHandling(process, this)
        tempFiles.forEach { it.delete() }
        result
    } catch (e: Exception) {
        throw RuntimeException("Failed to execute tool '${this.name}': ${e.message}", e)
    }
}

/** Convert String to HttpRequest using Montoya utilities */
fun String.toHttpRequest(): HttpRequest {
    return HttpRequest.httpRequestFromUrl(this)
}

/** Extension method to convert ByteArray to Montoya ByteArray */
fun ByteArray.toByteArray(): burp.api.montoya.core.ByteArray {
    return burp.api.montoya.core.ByteArray.byteArray(*this)
}

/** Extension method to convert Montoya ByteArray to regular ByteArray */
fun burp.api.montoya.core.ByteArray.toByteArray(): ByteArray {
    return this.bytes
}

/** Utility methods for Montoya API compatibility */
fun Utilities.byteArrayToByteArray(bytes: ByteArray): burp.api.montoya.core.ByteArray {
    return burp.api.montoya.core.ByteArray.byteArray(*bytes)
}

fun Utilities.bytesToString(bytes: burp.api.montoya.core.ByteArray): String {
    return String(bytes.bytes)
}

/** Repack a window (resize and center) */
fun java.awt.Window.repack() {
    val oldWidth = width
    pack()
    val loc = location
    setLocation(loc.x + ((oldWidth - width) / 2), loc.y)
}

/** Convert object to iterable */
fun <T> T.toIterable(): Iterable<T> {
    return when (this) {
        is Iterable<*> -> this as Iterable<T>
        is Array<*> -> this.asIterable() as Iterable<T>
        else -> listOf(this)
    }
}

/** Convert byte array to hex pairs */
fun ByteArray.toHexPairs(): String {
    return this.joinToString(separator = ":") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
}

/** Convert string to hex pairs */
fun String.toHexPairs(): String {
    return this.toByteArray().toHexPairs()
}

/** Convert ByteString to hex pairs */
fun com.google.protobuf.ByteString.toHexPairs(): String = this.toByteArray().toHexPairs()

/** Convert ByteString to human readable format */
fun com.google.protobuf.ByteString.toHumanReadable(): String =
        if (this.isValidUtf8) '"' + this.toStringUtf8() + '"' else "bytes " + this.toHexPairs()

/** Check if message match has filter */
fun Piper.MessageMatch.hasFilter(): Boolean {
    return this.hasPrefix() ||
            this.hasPostfix() ||
            this.hasRegex() ||
            this.hasHeader() ||
            this.hasCmd()
}

/** Check if CommandInvocation has filter */
val Piper.CommandInvocationOrBuilder.hasFilter: Boolean
    get() = hasStderr() || hasStdout() || exitCodeCount > 0

/** Get tool set for HTTP listener */
val Piper.HttpListener.toolSet: Set<BurpTool>
    get() =
            calcEnumSet(
                    BurpTool::class.java,
                    BurpTool::ordinal,
                    tool,
                    EnumSet.allOf(BurpTool::class.java)
            )

/** Set tool set for HTTP listener */
fun Piper.HttpListener.Builder.setToolSet(tools: Set<BurpTool>): Piper.HttpListener.Builder =
        this.setTool(tools.fold(0) { acc: Int, tool: BurpTool -> acc or (1 shl tool.ordinal) })

/** Get flag set for regex */
val Piper.RegularExpression.flagSet: Set<RegExpFlag>
    get() =
            calcEnumSet(
                    RegExpFlag::class.java,
                    RegExpFlag::value,
                    flags,
                    EnumSet.noneOf(RegExpFlag::class.java)
            )

/** Set flag set for regex */
fun Piper.RegularExpression.Builder.setFlagSet(
        flags: Set<RegExpFlag>
): Piper.RegularExpression.Builder =
        this.setFlags(flags.fold(0) { acc: Int, regExpFlag: RegExpFlag -> acc or regExpFlag.value })

/** Compile regex flags */
fun Set<RegExpFlag>.compile(): Int {
    return RegExpFlag.compile(this)
}

/** Calculate enum set from bit flags */
fun <E : Enum<E>> calcEnumSet(
        enumClass: Class<E>,
        getter: (E) -> Int,
        value: Int,
        zero: Set<E>
): Set<E> =
        if (value == 0) zero
        else EnumSet.copyOf(enumClass.enumConstants.filter { (getter(it) and value) != 0 })

/** Build enabled state for tools */
fun Piper.MinimalTool.buildEnabled(value: Boolean? = null): Piper.MinimalTool {
    val enabled =
            value
                    ?: try {
                        this.cmd.checkDependencies()
                        true
                    } catch (_: DependencyException) {
                        false
                    }
    return toBuilder().setEnabled(enabled).build()
}

fun Piper.UserActionTool.buildEnabled(value: Boolean? = null): Piper.UserActionTool =
        toBuilder().setCommon(common.buildEnabled(value)).build()

fun Piper.HttpListener.buildEnabled(value: Boolean? = null): Piper.HttpListener =
        toBuilder().setCommon(common.buildEnabled(value)).build()

/**
 * Map function for DefaultListModel
 */
fun <S, T> javax.swing.DefaultListModel<S>.map(transform: (S) -> T): Iterable<T> = toIterable().map(transform)
}

/**
 * Check if a URL is in Burp's scope using Montoya API.
 *
 * @param url URL to check
 * @param montoyaApi MontoyaApi instance
 * @return true if the URL is in scope
 */
private fun isInScope(url: URL?, montoyaApi: MontoyaApi): Boolean {
    return if (url != null) {
        montoyaApi.scope().isInScope(url.toString())
    } else false
}

////////////////////////////////////// TOOL TYPE UTILITIES - MONTOYA API
// //////////////////////////////////////

/**
 * Convert legacy tool flags to Montoya ToolType.
 *
 * @param toolFlag Legacy tool flag integer
 * @return Set of ToolType enum values
 */
fun toolFlagToToolTypes(toolFlag: Int): Set<ToolType> {
    val toolTypes = mutableSetOf<ToolType>()

    if ((toolFlag and 1) != 0) toolTypes.add(ToolType.PROXY)
    if ((toolFlag and 2) != 0) toolTypes.add(ToolType.SPIDER)
    if ((toolFlag and 4) != 0) toolTypes.add(ToolType.SCANNER)
    if ((toolFlag and 8) != 0) toolTypes.add(ToolType.INTRUDER)
    if ((toolFlag and 16) != 0) toolTypes.add(ToolType.REPEATER)
    if ((toolFlag and 32) != 0) toolTypes.add(ToolType.SEQUENCER)

    return toolTypes
}

/**
 * Check if a ToolType matches the given tool flag.
 *
 * @param toolFlag Legacy tool flag integer
 * @param toolType Montoya ToolType to check
 * @return true if the tool type is included in the flag
 */
fun isToolTypeInFlag(toolFlag: Int, toolType: ToolType): Boolean {
    return when (toolType) {
        ToolType.PROXY -> (toolFlag and 1) != 0
        ToolType.SPIDER -> (toolFlag and 2) != 0
        ToolType.SCANNER -> (toolFlag and 4) != 0
        ToolType.INTRUDER -> (toolFlag and 8) != 0
        ToolType.REPEATER -> (toolFlag and 16) != 0
        ToolType.SEQUENCER -> (toolFlag and 32) != 0
        else -> false
    }
}

////////////////////////////////////// REQUEST/RESPONSE UTILITIES - MONTOYA API
// //////////////////////////////////////

/**
 * RequestResponse enum updated for Montoya API compatibility. Provides message extraction and
 * manipulation methods using Montoya API.
 */
enum class MontoyaRequestResponse {
    REQUEST {
        override fun getMessage(rr: MontoyaHttpRequestResponseAdapter): ByteArray? = rr.getRequest()

        override fun setMessage(rr: MontoyaHttpRequestResponseAdapter, value: ByteArray) {
            rr.setRequest(value)
        }

        override fun getBodyOffset(data: ByteArray, utilities: Utilities): Int {
            return try {
                val content = String(data)
                val headerEndIndex = content.indexOf("\r\n\r\n")
                if (headerEndIndex == -1) data.size else headerEndIndex + 4
            } catch (e: Exception) {
                0
            }
        }

        override fun getHeaders(data: ByteArray, utilities: Utilities): List<String> {
            return try {
                val content = String(data)
                val headerEndIndex = content.indexOf("\r\n\r\n")
                if (headerEndIndex == -1) {
                    listOf(content.trim())
                } else {
                    content.substring(0, headerEndIndex).split("\r\n")
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    },
    RESPONSE {
        override fun getMessage(rr: MontoyaHttpRequestResponseAdapter): ByteArray? =
                rr.getResponse()

        override fun setMessage(rr: MontoyaHttpRequestResponseAdapter, value: ByteArray) {
            rr.setResponse(value)
        }

        override fun getBodyOffset(data: ByteArray, utilities: Utilities): Int {
            return try {
                val content = String(data)
                val headerEndIndex = content.indexOf("\r\n\r\n")
                if (headerEndIndex == -1) data.size else headerEndIndex + 4
            } catch (e: Exception) {
                0
            }
        }

        override fun getHeaders(data: ByteArray, utilities: Utilities): List<String> {
            return try {
                val content = String(data)
                val headerEndIndex = content.indexOf("\r\n\r\n")
                if (headerEndIndex == -1) {
                    listOf(content.trim())
                } else {
                    content.substring(0, headerEndIndex).split("\r\n")
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    };

    abstract fun getMessage(rr: MontoyaHttpRequestResponseAdapter): ByteArray?
    abstract fun setMessage(rr: MontoyaHttpRequestResponseAdapter, value: ByteArray)
    abstract fun getBodyOffset(data: ByteArray, utilities: Utilities): Int
    abstract fun getHeaders(data: ByteArray, utilities: Utilities): List<String>
}

/** Configuration scope mapping for HTTP listeners using Montoya API. */
enum class MontoyaConfigHttpListenerScope(val inputList: List<MontoyaRequestResponse>) {
    REQUEST(Collections.singletonList(MontoyaRequestResponse.REQUEST)),
    RESPONSE(Collections.singletonList(MontoyaRequestResponse.RESPONSE)),
    BOTH(listOf(MontoyaRequestResponse.REQUEST, MontoyaRequestResponse.RESPONSE));

    companion object {
        fun fromHttpListenerScope(scope: Piper.HttpListenerScope): MontoyaConfigHttpListenerScope =
                when (scope) {
                    Piper.HttpListenerScope.REQUEST -> REQUEST
                    Piper.HttpListenerScope.RESPONSE -> RESPONSE
                    Piper.HttpListenerScope.BOTH -> BOTH
                }
    }
}

////////////////////////////////////// CONFIGURATION UTILITIES - MONTOYA API
// //////////////////////////////////////

/**
 * Parse configuration data using Montoya API.
 *
 * @param configData Configuration data as byte array
 * @return Parsed Piper.Config instance
 */
fun parseConfig(configData: ByteArray): Piper.Config {
    return try {
        Piper.Config.parseFrom(configData)
    } catch (e: Exception) {
        // If protobuf parsing fails, try YAML parsing
        try {
            yamlToConfig(String(configData))
        } catch (yamlException: Exception) {
            throw RuntimeException("Failed to parse configuration as both protobuf and YAML", e)
        }
    }
}

/**
 * Get default configuration for Montoya API.
 *
 * @return Default Piper.Config instance
 */
fun getDefaultConfig(): Piper.Config {
    return try {
        // Load default configuration from resources
        val configResource = MontoyaBurpExtension::class.java.getResourceAsStream("/defaults.yaml")
        if (configResource != null) {
            yamlToConfig(configResource.readBytes().toString(Charsets.UTF_8))
        } else {
            Piper.Config.getDefaultInstance()
        }
    } catch (e: Exception) {
        Piper.Config.getDefaultInstance()
    }
}

/**
 * Utility function to build HTTP message from headers and body.
 *
 * @param headers List of header strings
 * @param body Message body as byte array
 * @return Complete HTTP message as byte array
 */
fun buildHttpMessage(headers: List<String>, body: ByteArray): ByteArray {
    val headerString = headers.joinToString("\r\n") + "\r\n\r\n"
    return headerString.toByteArray() + body
}

/**
 * Check if a MinimalTool is in the correct scope for the given message type.
 *
 * @param isRequest true if checking request scope, false for response
 * @return true if the tool is in the correct scope
 */
fun Piper.MinimalTool.isInToolScope(isRequest: Boolean): Boolean =
        when (scope) {
            Piper.MinimalTool.Scope.REQUEST_ONLY -> isRequest
            Piper.MinimalTool.Scope.RESPONSE_ONLY -> !isRequest
            else -> true
        }

/**
 * Build enabled/disabled version of a MinimalTool.
 *
 * @param value Optional boolean to set enabled state, null to toggle
 * @return New MinimalTool instance with updated enabled state
 */

/** Convert MessageMatch to human readable description with negation support */
fun Piper.MessageMatch.toHumanReadable(
        negation: Boolean,
        hideParentheses: Boolean = false
): String {
    val match = this
    val negated = negation xor match.negation
    val items =
            sequence {
                        if (match.prefix != null && !match.prefix.isEmpty) {
                            val prefix = if (negated) "doesn't start" else "starts"
                            yield("$prefix with ${match.prefix.toHumanReadable()}")
                        }
                        if (match.postfix != null && !match.postfix.isEmpty) {
                            val prefix = if (negated) "doesn't end" else "ends"
                            yield("$prefix with ${match.postfix.toHumanReadable()}")
                        }
                        if (match.hasRegex()) yield(match.regex.toHumanReadable(negated))

                        if (match.hasHeader()) yield(match.header.toHumanReadable(negated))

                        if (match.hasCmd()) yield(match.cmd.toHumanReadable(negated))

                        if (match.inScope)
                                yield("request is" + (if (negated) "n't" else "") + " in scope")

                        if (match.andAlsoCount > 0) {
                            yield(
                                    match.andAlsoList.joinToString(
                                            separator = (if (negated) " or " else " and "),
                                            transform = { it.toHumanReadable(negated) }
                                    )
                            )
                        }

                        if (match.orElseCount > 0) {
                            yield(
                                    match.orElseList.joinToString(
                                            separator = (if (negated) " and " else " or "),
                                            transform = { it.toHumanReadable(negated) }
                                    )
                            )
                        }
                    }
                    .toList()
    val result = items.joinToString(separator = (if (negated) " or " else " and ")).truncateTo(64)
    return if (items.size == 1 || hideParentheses) result else "($result)"
}

/** Convert HeaderMatch to human readable description */
fun Piper.HeaderMatch.toHumanReadable(negation: Boolean): String =
        "header \"$header\" " + regex.toHumanReadable(negation)

/** Convert CommandInvocation to human readable description */
fun Piper.CommandInvocation.toHumanReadable(negation: Boolean): String =
        sequence {
                    if (this@toHumanReadable.exitCodeCount > 0) {
                        val nt = if (negation) "n't" else ""
                        val ecl = this@toHumanReadable.exitCodeList
                        val values =
                                if (ecl.size == 1) ecl[0].toString()
                                else
                                        ecl.dropLast(1).joinToString(separator = ", ") +
                                                " or ${ecl.last()}"
                        yield("exit code is$nt $values")
                    }
                    if (this@toHumanReadable.hasStdout()) {
                        yield("stdout " + this@toHumanReadable.stdout.toHumanReadable(negation))
                    }
                    if (this@toHumanReadable.hasStderr()) {
                        yield("stderr " + this@toHumanReadable.stderr.toHumanReadable(negation))
                    }
                }
                .joinToString(
                        separator = (if (negation) " or " else " and "),
                        prefix = "when invoking `${this@toHumanReadable.commandLine}`, "
                )

/** Convert RegularExpression to human readable description */
fun Piper.RegularExpression.toHumanReadable(negation: Boolean): String =
        (if (negation) "doesn't match" else "matches") +
                " regex \"${this.pattern}\"" +
                (if (this.flags == 0) "" else " (${this.flagSet.joinToString(separator = ", ")})")

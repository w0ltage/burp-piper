package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ToolType
import burp.api.montoya.utilities.Utilities
import com.google.protobuf.ByteString
import java.io.File
import java.io.IOException
import java.lang.RuntimeException
import java.util.*
import java.util.EnumSet
import java.util.regex.Pattern

////////////////////////////////////// GUI //////////////////////////////////////

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

fun Piper.HeaderMatch.toHumanReadable(negation: Boolean): String =
        "header \"$header\" " + regex.toHumanReadable(negation)

fun Piper.RegularExpression.toHumanReadable(negation: Boolean): String {
    val prefix = if (negation) "doesn't match" else "matches"
    return "$prefix /${pattern}/${flagsValue.toHumanReadable()}"
}

fun Piper.CommandInvocation.toHumanReadable(negation: Boolean): String {
    val cmd = commandLine()
    val prefix = if (negation) "fails" else "passes"
    return "$prefix filter: ${cmd.joinToString(" ").truncateTo(32)}"
}

fun String.truncateTo(len: Int): String = if (length <= len) this else "${take(len - 3)}..."

fun ByteString.toHumanReadable(): String = "\"${toString(Charsets.UTF_8).truncateTo(16)}\""

/** Convert byte array to hex pairs */
fun ByteArray.toHexPairs(): String {
    return this.joinToString(separator = ":") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
}

/** Convert ByteString to hex pairs */
fun ByteString.toHexPairs(): String = this.toByteArray().toHexPairs()

/** Convert byte array to string using Montoya utilities */
fun Utilities.bytesToString(bytes: ByteArray): String {
    return String(bytes, Charsets.UTF_8)
}

/** Get command line representation of CommandInvocation */
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

const val CMDLINE_INPUT_FILENAME_PLACEHOLDER = "<INPUT>"

fun Int.toHumanReadable(): String =
        sequence {
            RegExpFlag.values().filter { it.ordinal != 0 && hasFlag(it.id) }.forEach {
                yield(it.name.toLowerCase())
            }
        }
                .joinToString("")
                .let { if (it.isEmpty()) "" else it }

////////////////////////////////////// DefaultListModel utilities
// //////////////////////////////////////

/** Map function for DefaultListModel */
fun <S, T> javax.swing.DefaultListModel<S>.map(transform: (S) -> T): Iterable<T> =
        toIterable().map(transform)

/** Convert DefaultListModel to Iterable */
fun <E> javax.swing.DefaultListModel<E>.toIterable(): Iterable<E> =
        (0 until size).map(this::elementAt)

////////////////////////////////////// Data structures //////////////////////////////////////

////////////////////////////////////// Business logic //////////////////////////////////////

fun Piper.MessageMatch.matches(
        msg: MessageInfo,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean {
    if (hasPrefix() && !msg.payloadString.startsWith(prefix.toString(Charsets.UTF_8)))
            return false.logicalXor(negation)
    if (hasPostfix() && !msg.payloadString.endsWith(postfix.toString(Charsets.UTF_8)))
            return false.logicalXor(negation)

    if (hasRegex()) {
        val pattern = Pattern.compile(regex.pattern, regex.flagsValue)
        if (!pattern.matcher(msg.payloadString).find()) return false.logicalXor(negation)
    }

    if (hasHeader()) {
        val pattern = Pattern.compile(header.regex.pattern, header.regex.flagsValue)
        val matches =
                msg.headers
                        .asSequence()
                        .filter { it.toLowerCase().startsWith(header.header.toLowerCase()) }
                        .map { it.substring(header.header.length).trimStart(':').trim() }
                        .any { pattern.matcher(it).find() }
        if (!matches) return false.logicalXor(negation)
    }

    if (hasCmd()) {
        if (!cmd.matches(msg.payload, utilities, montoyaApi)) return false.logicalXor(negation)
    }

    if (inScope) {
        // Montoya API scope check - simplified implementation
        if (msg.url == null) return false.logicalXor(negation)
    }

    val andAlsoResult = andAlsoList.all { it.matches(msg, utilities, montoyaApi) }
    if (!andAlsoResult) return false.logicalXor(negation)

    val orElseResult =
            orElseList.isEmpty() || orElseList.any { it.matches(msg, utilities, montoyaApi) }
    if (!orElseResult) return false.logicalXor(negation)

    return true.logicalXor(negation)
}

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

private fun Boolean.logicalXor(other: Boolean) = if (other) !this else this

fun Piper.CommandInvocation.matches(
        input: ByteArray,
        utilities: Utilities,
        montoyaApi: MontoyaApi
): Boolean {
    try {
        checkDependencies()
        val (process, tmpFiles) = execute(input)
        val stdout = getStdoutWithErrorHandling(process)
        tmpFiles.forEach { it.delete() }

        return when {
            hasStdoutFilter() ->
                    stdoutFilter.matches(
                            MessageInfo(
                                    stdout,
                                    utilities.byteUtils().convertToString(stdout),
                                    emptyList(),
                                    null
                            ),
                            utilities,
                            montoyaApi
                    )
            hasStderrFilter() -> {
                val stderr = process.errorStream.readBytes()
                stderrFilter.matches(
                        MessageInfo(
                                stderr,
                                utilities.byteUtils().convertToString(stderr),
                                emptyList(),
                                null
                        ),
                        utilities,
                        montoyaApi
                )
            }
            else -> successfulExitCodesList.contains(process.waitFor())
        }
    } catch (e: DependencyException) {
        return false
    } catch (e: Exception) {
        return false
    }
}

fun Piper.CommandInvocation.commandLine(): List<String> =
        (0 until prefixCount).map { getPrefix(it) } + requiredInPathList

fun Piper.CommandInvocation.execute(vararg inputs: ByteArray): Pair<Process, List<File>> {
    checkDependencies()
    val tmpFiles = mutableListOf<File>()
    val cmdLine = commandLine().toMutableList()

    inputs.forEachIndexed { idx, input ->
        when (inputMethod) {
            Piper.CommandInvocation.InputMethod.STDIN -> {
                // Will be handled after process creation
            }
            Piper.CommandInvocation.InputMethod.FILENAME -> {
                val tmpFile = File.createTempFile("piper", null)
                tmpFile.writeBytes(input)
                tmpFiles.add(tmpFile)
                cmdLine.add(tmpFile.absolutePath)
            }
        }
    }

    val processBuilder = ProcessBuilder(cmdLine)
    val process = processBuilder.start()

    if (inputMethod == Piper.CommandInvocation.InputMethod.STDIN && inputs.isNotEmpty()) {
        try {
            process.outputStream.use { os -> inputs.forEach { os.write(it) } }
        } catch (e: IOException) {
            // Process might not read from stdin - that's ok
        }
    }

    return Pair(process, tmpFiles)
}

private fun getStdoutWithErrorHandling(process: Process): ByteArray {
    return try {
        process.inputStream.readBytes()
    } catch (e: Exception) {
        ByteArray(0)
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
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                arrayOf("", ".exe", ".bat", ".cmd")
            } else {
                arrayOf("")
            }

    val pathEnv = System.getenv("PATH") ?: return false
    val paths = pathEnv.split(File.pathSeparator)

    return paths.any { path ->
        endings.any { ending ->
            val file = File(path, name + ending)
            file.exists() && file.canExecute()
        }
    }
}

/** Check if file can be executed */
private fun canExecute(f: File): Boolean = f.canExecute()

////////////////////////////////////// BurpSuite integration //////////////////////////////////////

fun Set<ToolType>.toFlags(): Int = fold(0) { acc, tool -> acc or tool.toolFlag() }

fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0

fun ToolType.toolFlag(): Int =
        when (this) {
            ToolType.SUITE -> 1 shl 0
            ToolType.TARGET -> 1 shl 1 // Updated from SPIDER
            ToolType.PROXY -> 1 shl 2
            ToolType.REPEATER -> 1 shl 3
            ToolType.INTRUDER -> 1 shl 4
            ToolType.SCANNER -> 1 shl 5
            ToolType.EXTENSIONS -> 1 shl 6
            ToolType.SEQUENCER -> 1 shl 7
            else -> 0
        }

fun <E : Enum<E>> calcEnumSet(
        enumClass: Class<E>,
        getter: (E) -> Int,
        value: Int,
        zero: Set<E>
): Set<E> {
    if (value == 0) return zero
    return EnumSet.allOf(enumClass).filter { value.hasFlag(getter(it)) }.toSet()
}

fun Int.toToolSet(): Set<ToolType> =
        calcEnumSet(ToolType::class.java, ToolType::toolFlag, this, emptySet())

fun Piper.MinimalTool.isInToolScope(isRequest: Boolean): Boolean {
    val tools = toolFlagsValue.toToolSet()
    if (tools.isEmpty()) return true

    return when (toolScope) {
        Piper.ConfigMinimalToolScope.ALL_TOOLS -> true
        Piper.ConfigMinimalToolScope.CUSTOM_TOOLS -> tools.isNotEmpty()
        else -> true
    }
}

fun Piper.HttpListener.isInToolScope(toolType: ToolType): Boolean {
    return when (toolScope) {
        Piper.ConfigHttpListenerScope.ALL_TOOLS -> true
        Piper.ConfigHttpListenerScope.CUSTOM_TOOLS -> toolFlagsValue.toToolSet().contains(toolType)
        else -> true
    }
}

////////////////////////////////////// Request/Response utilities
// //////////////////////////////////////

////////////////////////////////////// Montoya buildEnabled extensions
//////////////////////////////////////

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

fun Piper.MessageViewer.buildEnabled(value: Boolean? = null): Piper.MessageViewer =
        toBuilder().setCommon(common.buildEnabled(value)).build()

fun Piper.Commentator.buildEnabled(value: Boolean? = null): Piper.Commentator =
        toBuilder().setCommon(common.buildEnabled(value)).build()

fun Piper.Highlighter.buildEnabled(value: Boolean? = null): Piper.Highlighter =
        toBuilder().setCommon(common.buildEnabled(value)).build()

fun Piper.HttpListener.buildEnabled(value: Boolean? = null): Piper.HttpListener =
        toBuilder().setCommon(common.buildEnabled(value)).build()

fun Piper.UserActionTool.buildEnabled(value: Boolean? = null): Piper.UserActionTool =
        toBuilder().setCommon(common.buildEnabled(value)).build()

////////////////////////////////////// Window and GUI utilities
// //////////////////////////////////////

/** Repack a window (resize and center) */
fun java.awt.Window.repack() {
    val oldWidth = width
    pack()
    val loc = location
    setLocation(loc.x + ((oldWidth - width) / 2), loc.y)
}

////////////////////////////////////// Tool set utilities
// //////////////////////////////////////

/** Get tool set for HTTP listener */
val Piper.HttpListener.toolSet: Set<BurpTool>
    get() =
            calcEnumSet(
                    BurpTool::class.java,
                    BurpTool::value,
                    tool,
                    EnumSet.allOf(BurpTool::class.java)
            )

/** Set tool set for HTTP listener */
fun Piper.HttpListener.Builder.setToolSet(tools: Set<BurpTool>): Piper.HttpListener.Builder =
        this.setTool(tools.fold(0) { acc: Int, tool: BurpTool -> acc or tool.value })

/** Process output from a process and cleanup temp files */
fun <E> Pair<Process, List<File>>.processOutput(processor: (Process) -> E): E {
    val output = processor(this.first)
    this.first.waitFor()
    this.second.forEach { it.delete() }
    return output
}

////////////////////////////////////// Regular expression utilities
// //////////////////////////////////////

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

////////////////////////////////////// Configuration utilities
// //////////////////////////////////////

fun parseConfig(text: String): Piper.Config {
    return if (text.trim().startsWith("config")) {
        // YAML format
        configFromYaml(text)
    } else {
        // Assume protobuf format
        Piper.Config.parseFrom(text.toByteArray())
    }
}

fun loadConfig(): Piper.Config {
    val defaultConfigStream =
            {}::class.java.getResourceAsStream("/defaults.yaml")
                    ?: throw RuntimeException("Could not find /defaults.yaml in resources")
    return defaultConfigStream.use { stream -> configFromYaml(stream.bufferedReader().readText()) }
}

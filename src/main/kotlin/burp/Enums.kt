package burp

import burp.api.montoya.core.HighlightColor

/**
 * Enums for Piper Burp Suite Extension - Montoya API Compatible
 *
 * These enums define various configuration options and purposes used throughout the extension for
 * command invocations, tool scopes, and matching logic.
 */

/** Defines the purpose or context of a command invocation. */
enum class CommandInvocationPurpose {
    /** Command is executed only, without filtering */
    EXECUTE_ONLY,

    /** Command filters its own input */
    SELF_FILTER,

    /** Command is used to match/filter content */
    MATCH_FILTER
}

/** Defines the scope where minimal tools can be applied. */
enum class ConfigMinimalToolScope(val scope: Piper.MinimalTool.Scope) {
    /** Tool applies to both requests and responses */
    REQUEST_RESPONSE(Piper.MinimalTool.Scope.REQUEST_RESPONSE) {
        override fun toString(): String = "HTTP requests and responses"
    },

    /** Tool applies to HTTP requests only */
    REQUEST_ONLY(Piper.MinimalTool.Scope.REQUEST_ONLY) {
        override fun toString(): String = "HTTP requests only"
    },

    /** Tool applies to HTTP responses only */
    RESPONSE_ONLY(Piper.MinimalTool.Scope.RESPONSE_ONLY) {
        override fun toString(): String = "HTTP responses only"
    };

    companion object {
        fun fromScope(scope: Piper.MinimalTool.Scope): ConfigMinimalToolScope =
                values().first { it.scope == scope }
    }
}

/** Defines the scope for HTTP listener tools. */
enum class ConfigHttpListenerScope(val hls: Piper.HttpListenerScope) {
    /** Listen to HTTP requests */
    REQUEST(Piper.HttpListenerScope.REQUEST) {
        override fun toString(): String = "HTTP requests"
    },

    /** Listen to HTTP responses */
    RESPONSE(Piper.HttpListenerScope.RESPONSE) {
        override fun toString(): String = "HTTP responses"
    },

    /** Listen to HTTP responses with request prepended */
    RESPONSE_WITH_REQUEST(Piper.HttpListenerScope.RESPONSE_WITH_REQUEST) {
        override fun toString(): String = "HTTP responses with request prepended"
    };

    companion object {
        fun fromHttpListenerScope(hls: Piper.HttpListenerScope): ConfigHttpListenerScope =
                values().first { it.hls == hls }
    }
}

/** Represents different Burp Suite tools. */
enum class BurpTool {
    SUITE,
    TARGET,
    PROXY,
    SPIDER,
    SCANNER,
    INTRUDER,
    REPEATER,
    SEQUENCER,
    DECODER,
    COMPARER,
    EXTENDER,
    ORGANIZER
}

/** Highlight colors for marking messages. Maps to Montoya API HighlightColor values. */
enum class Highlight(
        val montoyaColor: HighlightColor?,
        val color: java.awt.Color?,
        val textColor: java.awt.Color = java.awt.Color.BLACK
) {
    CLEAR(null, null),
    RED(HighlightColor.RED, java.awt.Color(0xFF, 0x64, 0x64), java.awt.Color.WHITE),
    ORANGE(HighlightColor.ORANGE, java.awt.Color(0xFF, 0xC8, 0x64)),
    YELLOW(HighlightColor.YELLOW, java.awt.Color(0xFF, 0xFF, 0x64)),
    GREEN(HighlightColor.GREEN, java.awt.Color(0x64, 0xFF, 0x64)),
    CYAN(HighlightColor.CYAN, java.awt.Color(0x64, 0xFF, 0xFF)),
    BLUE(HighlightColor.BLUE, java.awt.Color(0x64, 0x64, 0xFF), java.awt.Color.WHITE),
    PINK(HighlightColor.PINK, java.awt.Color(0xFF, 0xC8, 0xC8)),
    MAGENTA(HighlightColor.MAGENTA, java.awt.Color(0xFF, 0x64, 0xFF)),
    GRAY(HighlightColor.GRAY, java.awt.Color(0xB4, 0xB4, 0xB4));

    override fun toString(): String = super.toString().lowercase()

    val burpValue: String?
        get() = if (color == null) null else toString()

    companion object {
        private val lookupTable = values().associateBy(Highlight::toString)

        fun fromString(value: String): Highlight? = lookupTable[value]

        /** Convert from Montoya API HighlightColor to our Highlight enum */
        fun fromMontoyaColor(color: HighlightColor?): Highlight {
            return values().find { it.montoyaColor == color } ?: CLEAR
        }
    }
}

/** Represents whether a message is a request or response for Montoya API compatibility. */
enum class RequestResponse(val isRequest: Boolean) {
    /** HTTP Request */
    REQUEST(isRequest = true) {
        override fun getMessage(rr: MontoyaHttpRequestResponseAdapter): ByteArray? =
                rr.request?.toByteArray()?.bytes

        override fun setMessage(rr: MontoyaHttpRequestResponseAdapter, value: ByteArray) {
            // Note: Montoya API HttpRequest/HttpResponse are immutable
            // This method is kept for compatibility but may not be fully functional
        }

        override fun getBodyOffset(
                data: ByteArray,
                utilities: burp.api.montoya.utilities.Utilities
        ): Int = utilities.byteUtils().indexOf(data, "\r\n\r\n".toByteArray()) + 4

        override fun getHeaders(
                data: ByteArray,
                utilities: burp.api.montoya.utilities.Utilities
        ): List<String> {
            val dataString = String(data)
            val headerEnd = dataString.indexOf("\r\n\r\n")
            return if (headerEnd != -1) {
                dataString.substring(0, headerEnd).split("\r\n")
            } else {
                dataString.split("\r\n")
            }
        }
    },

    /** HTTP Response */
    RESPONSE(isRequest = false) {
        override fun getMessage(rr: MontoyaHttpRequestResponseAdapter): ByteArray? =
                rr.response?.toByteArray()?.bytes

        override fun setMessage(rr: MontoyaHttpRequestResponseAdapter, value: ByteArray) {
            // Note: Montoya API HttpRequest/HttpResponse are immutable
            // This method is kept for compatibility but may not be fully functional
        }

        override fun getBodyOffset(
                data: ByteArray,
                utilities: burp.api.montoya.utilities.Utilities
        ): Int = utilities.byteUtils().indexOf(data, "\r\n\r\n".toByteArray()) + 4

        override fun getHeaders(
                data: ByteArray,
                utilities: burp.api.montoya.utilities.Utilities
        ): List<String> {
            val dataString = String(data)
            val headerEnd = dataString.indexOf("\r\n\r\n")
            return if (headerEnd != -1) {
                dataString.substring(0, headerEnd).split("\r\n")
            } else {
                dataString.split("\r\n")
            }
        }
    };

    abstract fun getMessage(rr: MontoyaHttpRequestResponseAdapter): ByteArray?
    abstract fun setMessage(rr: MontoyaHttpRequestResponseAdapter, value: ByteArray)
    abstract fun getBodyOffset(
            data: ByteArray,
            utilities: burp.api.montoya.utilities.Utilities
    ): Int
    abstract fun getHeaders(
            data: ByteArray,
            utilities: burp.api.montoya.utilities.Utilities
    ): List<String>

    companion object {
        /**
         * Create RequestResponse from boolean flag
         * @param isRequest true for REQUEST, false for RESPONSE
         * @return corresponding RequestResponse enum value
         */
        fun fromBoolean(isRequest: Boolean) = if (isRequest) REQUEST else RESPONSE
    }
}

/**
 * Information about an HTTP message for Montoya API compatibility.
 *
 * This data class provides message details needed for tool processing and filtering, adapted from
 * the legacy API to work with Montoya API types.
 */
data class MessageInfo(
        val content: ByteArray,
        val text: String,
        val headers: List<String>?,
        val url: java.net.URL?,
        val hrr: MontoyaHttpRequestResponseAdapter? = null
) {
    val asContentExtensionPair: Pair<ByteArray, String?>
        get() {
            return content to fileExtension
        }

    private val fileExtension: String?
        get() {
            if (url != null) {
                val match = Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE).find(url.path)
                if (match != null) {
                    return match.groups[0]!!.value
                }
            }
            return null
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MessageInfo

        if (!content.contentEquals(other.content)) return false
        if (text != other.text) return false
        if (headers != other.headers) return false
        if (url != other.url) return false
        if (hrr != other.hrr) return false

        return true
    }

    override fun hashCode(): Int {
        var result = content.contentHashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + (headers?.hashCode() ?: 0)
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + (hrr?.hashCode() ?: 0)
        return result
    }
}

/** Defines match negation options for filtering logic. */
enum class MatchNegation(val negation: Boolean, private val description: String) {
    /** Normal matching - condition must be true */
    NORMAL(false, "Match when all the rules below apply"),

    /** Negated matching - condition must be false */
    NEGATED(true, "Match when none of the rules below apply");

    override fun toString(): String = description
}

/** Regular expression flags for pattern matching. */
enum class RegExpFlag(val value: Int, val description: String) {
    /** Case-insensitive matching */
    CASE_INSENSITIVE(2, "Case insensitive"),

    /** Multiline mode - ^ and $ match line terminators */
    MULTILINE(8, "Multiline"),

    /** Dotall mode - . matches any character including line terminators */
    DOTALL(32, "Dot matches all"),

    /** Unicode case matching */
    UNICODE_CASE(64, "Unicode case"),

    /** Canonical equivalence */
    CANON_EQ(128, "Canonical equivalence"),

    /** Comments mode - whitespace and comments are ignored */
    COMMENTS(4, "Comments");

    companion object {
        /** Convert a set of RegExpFlag to int flags value */
        fun compile(flags: Set<RegExpFlag>): Int {
            return flags.fold(0) { acc, flag -> acc or flag.value }
        }

        /** Convert int flags value to set of RegExpFlag */
        fun fromInt(flagsValue: Int): Set<RegExpFlag> {
            return values().filter { (flagsValue and it.value) != 0 }.toSet()
        }
    }
}

/** Extension function to get human-readable string representation of enum */
fun <T : Enum<T>> T.toHumanReadable(): String {
    return when (this) {
        is CommandInvocationPurpose ->
                when (this) {
                    CommandInvocationPurpose.EXECUTE_ONLY -> "Execute Only"
                    CommandInvocationPurpose.SELF_FILTER -> "Self Filter"
                    CommandInvocationPurpose.MATCH_FILTER -> "Match Filter"
                }
        is ConfigMinimalToolScope -> this.toString()
        is ConfigHttpListenerScope -> this.toString()
        is BurpTool -> this.name.lowercase().replaceFirstChar { it.uppercase() }
        is Highlight -> this.name.lowercase().replaceFirstChar { it.uppercase() }
        is MatchNegation -> this.toString()
        is RegExpFlag -> this.description
        else -> this.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
}

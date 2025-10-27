package burp

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import java.io.ByteArrayOutputStream
import kotlin.text.Charsets

internal val HTTP_CHARSET = Charsets.ISO_8859_1
internal val CRLF_BYTES: kotlin.ByteArray = "\r\n".toByteArray(HTTP_CHARSET)

internal data class FormattedHttpMessage(
    val bytes: kotlin.ByteArray,
    val bodyOffset: Int,
)

internal fun formatRequestMessage(request: HttpRequest): FormattedHttpMessage {
    val pathWithQuery = normalizedOrNull(request.path())
    val pathWithoutQuery = normalizedOrNull(request.pathWithoutQuery())
    val query = normalizedOrNull(request.query())
    val target = pathWithQuery ?: when {
        pathWithoutQuery != null && query != null -> "$pathWithoutQuery?$query"
        pathWithoutQuery != null -> pathWithoutQuery
        query != null -> "?$query"
        else -> "/"
    }

    val startLine = joinTokens(request.method(), target, request.httpVersion())
    val headers = request.headers().map { it.toString() }
    return formatHttpMessage(startLine, headers, request.body().getBytes())
}

internal fun formatResponseMessage(response: HttpResponse): FormattedHttpMessage {
    val version = normalizedOrNull(response.httpVersion()) ?: "HTTP/1.1"
    val startLine = joinTokens(version, response.statusCode().toInt().toString(), response.reasonPhrase())
    val headers = response.headers().map { it.toString() }
    return formatHttpMessage(startLine, headers, response.body().getBytes())
}

internal fun formatHttpMessage(
    startLine: String,
    headers: List<String>,
    body: kotlin.ByteArray,
): FormattedHttpMessage {
    val output = ByteArrayOutputStream()
    output.write(startLine.toByteArray(HTTP_CHARSET))
    output.write(CRLF_BYTES)
    headers.forEach { header ->
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(CRLF_BYTES)
    }
    output.write(CRLF_BYTES)
    val headerLength = output.size()
    output.write(body)
    return FormattedHttpMessage(output.toByteArray(), headerLength)
}

internal fun joinTokens(vararg tokens: String?): String =
    tokens.mapNotNull(::normalizedOrNull).joinToString(separator = " ")

internal fun normalizedOrNull(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }


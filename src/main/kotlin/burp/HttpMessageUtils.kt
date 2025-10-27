package burp

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse

internal data class FormattedHttpMessage(
    val bytes: kotlin.ByteArray,
    val bodyOffset: Int,
)

internal fun formatRequestMessage(request: HttpRequest): FormattedHttpMessage {
    val raw = request.toByteArray()
    return FormattedHttpMessage(raw.getBytes(), request.bodyOffset())
}

internal fun formatResponseMessage(response: HttpResponse): FormattedHttpMessage {
    val raw = response.toByteArray()
    return FormattedHttpMessage(raw.getBytes(), response.bodyOffset())
}


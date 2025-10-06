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

import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import java.net.URL

/**
 * Adapter class to bridge Montoya API HTTP message types with legacy Piper interfaces. This allows
 * existing Piper logic to work with the new Montoya API without requiring complete rewrites of all
 * message processing logic.
 */
class MontoyaHttpRequestResponseAdapter {

    val request: HttpRequest?
    val response: HttpResponse?
    private val httpRequestResponse: HttpRequestResponse?

    constructor(requestToBeSent: HttpRequestToBeSent) {
        this.request = requestToBeSent
        this.response = null
        this.httpRequestResponse = null
    }

    constructor(responseReceived: HttpResponseReceived) {
        this.request = responseReceived.initiatingRequest()
        this.response = responseReceived
        this.httpRequestResponse = null
    }

    constructor(httpRequestResponse: HttpRequestResponse) {
        this.request = httpRequestResponse.request()
        this.response = httpRequestResponse.response()
        this.httpRequestResponse = httpRequestResponse
    }

    /**
     * Get the request bytes in legacy format. Compatible with legacy
     * IHttpRequestResponse.getRequest() method.
     */
    fun getRequest(): ByteArray? {
        return request?.toByteArray()?.bytes
    }

    /**
     * Get the response bytes in legacy format. Compatible with legacy
     * IHttpRequestResponse.getResponse() method.
     */
    fun getResponse(): ByteArray? {
        return response?.toByteArray()?.bytes
    }

    /**
     * Set the request bytes - used for message modification. Compatible with legacy
     * IHttpRequestResponse.setRequest() method.
     */
    fun setRequest(request: ByteArray) {
        // Note: Montoya API handles message modification differently
        // This method maintains compatibility but actual modification
        // needs to be handled through the appropriate Montoya API methods
        // in the calling code
    }

    /**
     * Set the response bytes - used for message modification. Compatible with legacy
     * IHttpRequestResponse.setResponse() method.
     */
    fun setResponse(response: ByteArray) {
        // Note: Montoya API handles message modification differently
        // This method maintains compatibility but actual modification
        // needs to be handled through the appropriate Montoya API methods
        // in the calling code
    }

    /**
     * Get the HTTP service information. Compatible with legacy
     * IHttpRequestResponse.getHttpService() method.
     */
    fun getHttpService(): MontoyaHttpServiceAdapter? {
        return request?.httpService()?.let { MontoyaHttpServiceAdapter(it) }
    }

    /**
     * Set the HTTP service information. Compatible with legacy
     * IHttpRequestResponse.setHttpService() method.
     */
    fun setHttpService(httpService: MontoyaHttpServiceAdapter) {
        // Note: HTTP service is typically immutable in Montoya API
        // This method maintains compatibility but may not be fully functional
    }

    /** Get highlight color. Compatible with legacy IHttpRequestResponse.getHighlight() method. */
    fun getHighlight(): String? {
        return httpRequestResponse?.annotations()?.highlightColor()?.toString()
    }

    /** Set highlight color. Compatible with legacy IHttpRequestResponse.setHighlight() method. */
    fun setHighlight(color: String?) {
        // Note: Annotations in Montoya API are handled differently
        // This method maintains compatibility but actual highlighting
        // needs to be handled through the appropriate Montoya API methods
        color?.let {
            httpRequestResponse
                    ?.annotations()
                    ?.setHighlightColor(
                            burp.api.montoya.core.HighlightColor.valueOf(it.uppercase())
                    )
        }
    }

    /** Get comment. Compatible with legacy IHttpRequestResponse.getComment() method. */
    fun getComment(): String? {
        return httpRequestResponse?.annotations()?.notes()
    }

    /** Set comment. Compatible with legacy IHttpRequestResponse.setComment() method. */
    fun setComment(comment: String?) {
        httpRequestResponse?.annotations()?.setNotes(comment ?: "")
    }

    /**
     * Get the URL from the request. Helper method to extract URL information from the HTTP request.
     */
    fun getUrl(): URL? {
        return try {
            request?.httpService()?.let { service ->
                val path = request.path()
                URL(
                        "${if (service.secure()) "https" else "http"}://${service.host()}:${service.port()}$path"
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Check if this adapter wraps a request. */
    fun hasRequest(): Boolean = request != null

    /** Check if this adapter wraps a response. */
    fun hasResponse(): Boolean = response != null

    /** Get the tool source that created this message (if available). */
    fun getToolSource(): burp.api.montoya.core.ToolSource? {
        return when {
            response is HttpResponseReceived -> response.toolSource()
            request is HttpRequestToBeSent -> request.toolSource()
            else -> null
        }
    }
}

/**
 * Adapter for HTTP service information. Bridges Montoya API HttpService with legacy IHttpService
 * interface.
 */
class MontoyaHttpServiceAdapter(private val httpService: burp.api.montoya.http.HttpService) {

    fun getHost(): String = httpService.host()

    fun getPort(): Int = httpService.port()

    fun getProtocol(): String = if (httpService.secure()) "https" else "http"

    fun isSecure(): Boolean = httpService.secure()

    override fun toString(): String = "${getProtocol()}://${getHost()}:${getPort()}"
}

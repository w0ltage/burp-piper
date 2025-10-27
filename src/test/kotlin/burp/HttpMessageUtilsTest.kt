package burp

import burp.api.montoya.core.ByteArray as MontoyaByteArray
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.lang.reflect.Proxy

class HttpMessageUtilsTest {

    @Test
    fun `formatHttpMessage joins headers before body`() {
        val body = "email=a@example.com".toByteArray(HTTP_CHARSET)
        val formatted = formatHttpMessage(
            startLine = "POST /submit HTTP/2",
            headers = listOf("Host: example.com", "Content-Type: application/x-www-form-urlencoded"),
            body = body,
        )

        val text = String(formatted.bytes, HTTP_CHARSET)
        assertTrue(text.startsWith("POST /submit HTTP/2\r\nHost: example.com\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n"))
        val headerPrefix = "POST /submit HTTP/2\r\nHost: example.com\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n".toByteArray(HTTP_CHARSET)
        assertEquals(headerPrefix.size, formatted.bodyOffset)
        assertTrue(text.endsWith(String(body, HTTP_CHARSET)))
    }

    @Test
    fun `formatRequestMessage includes query string and headers`() {
        val request = proxyHttpRequest(
            method = "POST",
            path = null,
            pathWithoutQuery = "/change-email",
            query = "id=42",
            version = "HTTP/2",
            headers = listOf(httpHeader("Host", "example.com"), httpHeader("Cookie", "session=abc")),
            body = "token=123".toByteArray(HTTP_CHARSET),
        )

        val formatted = formatRequestMessage(request)
        val text = String(formatted.bytes, HTTP_CHARSET)
        assertTrue(text.startsWith("POST /change-email?id=42 HTTP/2\r\nHost: example.com\r\nCookie: session=abc\r\n\r\n"))
        val expectedHeaders = "POST /change-email?id=42 HTTP/2\r\nHost: example.com\r\nCookie: session=abc\r\n\r\n".toByteArray(HTTP_CHARSET)
        assertEquals(expectedHeaders.size, formatted.bodyOffset)
    }

    @Test
    fun `joinTokens filters blanks`() {
        assertEquals("HTTP/2 200 OK", joinTokens(" HTTP/2 ", "", "200", null, " OK "))
    }

    private fun proxyHttpRequest(
        method: String?,
        path: String?,
        pathWithoutQuery: String?,
        query: String?,
        version: String?,
        headers: List<HttpHeader>,
        body: kotlin.ByteArray,
    ): HttpRequest {
        val bodyValue = montoyaByteArray(body)
        val classLoader = HttpRequest::class.java.classLoader
        return Proxy.newProxyInstance(classLoader, arrayOf(HttpRequest::class.java)) { proxy, methodObj, args ->
            when (methodObj.name) {
                "method" -> method
                "path" -> path
                "pathWithoutQuery" -> pathWithoutQuery
                "query" -> query
                "httpVersion" -> version
                "headers" -> headers
                "body" -> bodyValue
                "toString" -> "StubHttpRequest"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("Unexpected invocation: ${methodObj.name}")
            }
        } as HttpRequest
    }

    private fun httpHeader(name: String, value: String): HttpHeader {
        val classLoader = HttpHeader::class.java.classLoader
        return Proxy.newProxyInstance(classLoader, arrayOf(HttpHeader::class.java)) { proxy, method, args ->
            when (method.name) {
                "name" -> name
                "value" -> value
                "toString" -> "$name: $value"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("Unexpected invocation: ${method.name}")
            }
        } as HttpHeader
    }

    private fun montoyaByteArray(bytes: kotlin.ByteArray): MontoyaByteArray {
        val classLoader = MontoyaByteArray::class.java.classLoader
        return Proxy.newProxyInstance(classLoader, arrayOf(MontoyaByteArray::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBytes" -> bytes
                "length" -> bytes.size
                "copy" -> montoyaByteArray(bytes.copyOf())
                "iterator" -> bytes.toList().iterator()
                "toString" -> String(bytes, HTTP_CHARSET)
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("Unexpected invocation: ${method.name}")
            }
        } as MontoyaByteArray
    }
}


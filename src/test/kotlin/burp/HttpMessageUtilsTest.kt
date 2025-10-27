package burp

import burp.api.montoya.core.ByteArray as MontoyaByteArray
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import kotlin.text.Charsets
import java.lang.reflect.Proxy

class HttpMessageUtilsTest {

    @Test
    fun `formatRequestMessage returns Montoya bytes`() {
        val raw = "POST /submit HTTP/2\r\nHost: example.com\r\n\r\nbody".toByteArray(Charsets.ISO_8859_1)
        val request = proxyHttpRequest(raw, 32)

        val formatted = formatRequestMessage(request)

        assertEquals(raw.toList(), formatted.bytes.toList())
        assertEquals(32, formatted.bodyOffset)
    }

    @Test
    fun `formatResponseMessage returns Montoya bytes`() {
        val raw = "HTTP/2 200 OK\r\nContent-Length: 4\r\n\r\ntest".toByteArray(Charsets.ISO_8859_1)
        val response = proxyHttpResponse(raw, 31)

        val formatted = formatResponseMessage(response)

        assertEquals(raw.toList(), formatted.bytes.toList())
        assertEquals(31, formatted.bodyOffset)
    }

    private fun proxyHttpRequest(
        raw: kotlin.ByteArray,
        offset: Int,
    ): HttpRequest {
        val rawBytes = montoyaByteArray(raw)
        val classLoader = HttpRequest::class.java.classLoader
        return Proxy.newProxyInstance(classLoader, arrayOf(HttpRequest::class.java)) { proxy, methodObj, args ->
            when (methodObj.name) {
                "toByteArray" -> rawBytes
                "bodyOffset" -> offset
                "toString" -> "StubHttpRequest"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("Unexpected invocation: ${methodObj.name}")
            }
        } as HttpRequest
    }

    private fun proxyHttpResponse(
        raw: kotlin.ByteArray,
        offset: Int,
    ): HttpResponse {
        val rawBytes = montoyaByteArray(raw)
        val classLoader = HttpResponse::class.java.classLoader
        return Proxy.newProxyInstance(classLoader, arrayOf(HttpResponse::class.java)) { proxy, methodObj, args ->
            when (methodObj.name) {
                "toByteArray" -> rawBytes
                "bodyOffset" -> offset
                "toString" -> "StubHttpResponse"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("Unexpected invocation: ${methodObj.name}")
            }
        } as HttpResponse
    }

    private fun montoyaByteArray(bytes: kotlin.ByteArray): MontoyaByteArray {
        val classLoader = MontoyaByteArray::class.java.classLoader
        return Proxy.newProxyInstance(classLoader, arrayOf(MontoyaByteArray::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBytes" -> bytes
                "length" -> bytes.size
                "copy" -> montoyaByteArray(bytes.copyOf())
                "iterator" -> bytes.toList().iterator()
                "toString" -> String(bytes, Charsets.ISO_8859_1)
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("Unexpected invocation: ${method.name}")
            }
        } as MontoyaByteArray
    }
}


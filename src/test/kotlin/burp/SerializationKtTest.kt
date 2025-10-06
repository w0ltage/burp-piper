package burp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.text.Charsets.UTF_8

val testInput = "ABCDEFGHIJKLMNO".toByteArray(UTF_8)

class SerializationKtTest {

    @Test
    fun testPad4() {
        for (len in 0.rangeTo(testInput.size)) {
            val subset = testInput.copyOfRange(0, len)
            val padded = pad4(subset)
            assertEquals(0, padded.size % 4)
            assertArrayEquals(subset, unpad4(padded))
        }
    }

    @Test
    fun testCompress() {
        assertArrayEquals(testInput, decompress(compress(testInput)))
    }
}

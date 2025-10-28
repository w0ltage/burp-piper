package burp

import org.testng.Assert.assertEquals
import org.testng.annotations.Test

class CommandInvocationExtensionsTest {
    @Test
    fun `applyDefaultEnvironment preserves explicit values`() {
        val env = mutableMapOf("PYTHONUNBUFFERED" to "0", "EXISTING" to "1")
        invokeApplyDefaultEnvironment(env)

        assertEquals("0", env["PYTHONUNBUFFERED"])
        assertEquals("1", env["EXISTING"])
    }

    @Test
    fun `applyDefaultEnvironment sets defaults when missing`() {
        val env = mutableMapOf<String, String>()
        invokeApplyDefaultEnvironment(env)

        assertEquals("1", env["PYTHONUNBUFFERED"])
    }

    private fun invokeApplyDefaultEnvironment(env: MutableMap<String, String>) {
        val applyMethod = Class.forName("burp.ExtensionsKt")
            .getDeclaredMethod("applyDefaultEnvironment", java.util.Map::class.java)
        applyMethod.isAccessible = true
        applyMethod.invoke(null, env)
    }
}

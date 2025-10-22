package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray
import burp.api.montoya.core.Registration
import burp.api.montoya.extension.Extension
import burp.api.montoya.intruder.Intruder
import burp.api.montoya.internal.MontoyaObjectFactory
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.persistence.Persistence
import burp.api.montoya.scope.Scope
import burp.api.montoya.ui.UserInterface
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider
import burp.api.montoya.utilities.ByteUtils
import burp.api.montoya.utilities.Utilities
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.io.PrintStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class MontoyaExtensionTest {

    @Test
    fun testEnablingDefaultDisabledViewerRegistersAndPersists() {
        val savedConfigs = mutableListOf<kotlin.ByteArray>()
        val requestProviders = CopyOnWriteArrayList<HttpRequestEditorProvider>()
        val responseProviders = CopyOnWriteArrayList<HttpResponseEditorProvider>()

        var storedBytes: ByteArray? = null
        val extensionData = stub<PersistedObject> { method, args ->
            when (method.name) {
                "getByteArray" -> storedBytes
                "setByteArray" -> {
                    val array = args!![1] as ByteArray
                    storedBytes = array
                    savedConfigs += array.getBytes()
                    null
                }
                else -> unsupported(method)
            }
        }

        val persistence = stub<Persistence> { method, _ ->
            when (method.name) {
                "extensionData" -> extensionData
                else -> unsupported(method)
            }
        }

        val byteUtils = stub<ByteUtils> { method, args ->
            when (method.name) {
                "convertToString" -> String(args!![0] as kotlin.ByteArray, StandardCharsets.ISO_8859_1)
                else -> unsupported(method)
            }
        }

        val utilities = stub<Utilities> { method, _ ->
            when (method.name) {
                "byteUtils" -> byteUtils
                else -> unsupported(method)
            }
        }

        val logging = stub<Logging> { method, _ ->
            when (method.name) {
                "output" -> PrintStream.nullOutputStream()
                "error" -> PrintStream.nullOutputStream()
                "logToOutput", "logToError", "raiseDebugEvent", "raiseInfoEvent", "raiseErrorEvent", "raiseCriticalEvent" -> null
                else -> unsupported(method)
            }
        }

        val scope = stub<Scope> { method, _ ->
            when (method.name) {
                "isInScope" -> true
                else -> unsupported(method)
            }
        }

        fun registration(onDeregister: () -> Unit = {}): Registration = object : Registration {
            private var registered = true
            override fun isRegistered(): Boolean = registered
            override fun deregister() {
                if (registered) {
                    registered = false
                    onDeregister()
                }
            }
        }

        val userInterface = stub<UserInterface> { method, args ->
            when (method.name) {
                "registerSuiteTab" -> registration()
                "registerHttpRequestEditorProvider" -> {
                    val provider = args!![0] as HttpRequestEditorProvider
                    requestProviders += provider
                    registration { requestProviders.remove(provider) }
                }
                "registerHttpResponseEditorProvider" -> {
                    val provider = args!![0] as HttpResponseEditorProvider
                    responseProviders += provider
                    registration { responseProviders.remove(provider) }
                }
                else -> unsupported(method)
            }
        }

        val intruder = stub<Intruder> { method, _ ->
            when (method.name) {
                "registerPayloadProcessor", "registerPayloadGeneratorProvider" -> registration()
                else -> unsupported(method)
            }
        }

        val extensionBridge = stub<Extension> { method, _ ->
            when (method.name) {
                "setName" -> null
                else -> unsupported(method)
            }
        }

        val api = stub<MontoyaApi> { method, _ ->
            when (method.name) {
                "extension" -> extensionBridge
                "logging" -> logging
                "utilities" -> utilities
                "scope" -> scope
                "userInterface" -> userInterface
                "persistence" -> persistence
                "intruder" -> intruder
                else -> unsupported(method)
            }
        }

        val factoryHandle = installMontoyaFactory()
        val extension = MontoyaExtension()
        try {
            extension.initialize(api)

            // Default config should be persisted once during initialization
            assertTrue(savedConfigs.isNotEmpty(), "Expected initial configuration to be saved")
            val initialRequestCount = requestProviders.size
            val initialResponseCount = responseProviders.size

            val configField = MontoyaExtension::class.java.getDeclaredField("configModel").apply { isAccessible = true }
            val configModel = configField.get(extension) as ConfigModel
            val model = configModel.messageViewersModel
            val index = (0 until model.size()).first { model.getElementAt(it).common.name == "Python JSON formatter" }
            val viewer = model.getElementAt(index)
            assertFalse(viewer.common.enabled, "Viewer should start disabled by default")

            model.setElementAt(viewer.buildEnabled(true), index)

            assertEquals(requestProviders.size, initialRequestCount + 1, "Request provider should be registered when enabling viewer")
            assertEquals(responseProviders.size, initialResponseCount + 1, "Response provider should be registered when enabling viewer")

            val persistedConfig = Piper.Config.parseFrom(savedConfigs.last())
            val updatedViewer = persistedConfig.messageViewerList.first { it.common.name == "Python JSON formatter" }
            assertTrue(updatedViewer.common.enabled, "Updated configuration should persist enabled viewer state")
        } finally {
            factoryHandle.close()
        }
    }

    private fun unsupported(method: Method): Nothing = throw UnsupportedOperationException("Stub does not implement ${method.name}")

    private inline fun <reified T> stub(crossinline handler: (Method, Array<out Any?>?) -> Any?): T {
        val clazz = T::class.java
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "toString" -> "Stub:${clazz.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> throw UnsupportedOperationException(method.name)
                }
            } else {
                handler(method, args)
            }
        } as T
    }

    private fun installMontoyaFactory(): AutoCloseable {
        val locatorClass = Class.forName("burp.api.montoya.internal.ObjectFactoryLocator")
        val factoryField = locatorClass.getDeclaredField("FACTORY").apply { isAccessible = true }
        val previous = factoryField.get(null) as MontoyaObjectFactory?
        val factory = stub<MontoyaObjectFactory> { method, args ->
            when (method.name) {
                "byteArrayOfLength" -> createMontoyaByteArray(kotlin.ByteArray(args!![0] as Int))
                "byteArray" -> {
                    val value = args!![0]
                    when (value) {
                        is kotlin.ByteArray -> createMontoyaByteArray(value.copyOf())
                        is IntArray -> createMontoyaByteArray(value.toByteArray())
                        is String -> createMontoyaByteArray(value.toByteArray(StandardCharsets.ISO_8859_1))
                        else -> unsupported(method)
                    }
                }
                else -> unsupported(method)
            }
        }
        factoryField.set(null, factory)
        return AutoCloseable { factoryField.set(null, previous) }
    }

    private fun createMontoyaByteArray(initial: kotlin.ByteArray): ByteArray {
        var data = initial.copyOf()
        return stub<ByteArray> { method, args ->
            when (method.name) {
                "getBytes" -> data.copyOf()
                "length" -> data.size
                "getByte" -> data[(args!![0] as Int)]
                "iterator" -> data.toList().iterator()
                else -> unsupported(method)
            }
        }
    }

    private fun IntArray.toByteArray(): kotlin.ByteArray = kotlin.ByteArray(size) { this[it].toByte() }
}

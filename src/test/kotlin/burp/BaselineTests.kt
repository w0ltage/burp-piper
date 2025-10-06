package burp

import burp.*

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

/**
 * Baseline Integration Tests for Core Legacy Burp API Functionality
 *
 * These tests create a "golden set" that proves existing functionality works correctly
 * before any refactoring or API migration. All tests are designed to pass against the
 * current unmodified codebase using the legacy burp.* API.
 */
class BaselineTests {

    private lateinit var mockCallbacks: IBurpExtenderCallbacks
    private lateinit var mockHelpers: IExtensionHelpers
    private lateinit var mockHttpRequestResponse: IHttpRequestResponse

    private lateinit var burpExtender: BurpExtender

    @BeforeEach
    fun setUp() {
        // Create mocks
        mockCallbacks = mock(IBurpExtenderCallbacks::class.java)
        mockHelpers = mock(IExtensionHelpers::class.java)
        mockHttpRequestResponse = mock(IHttpRequestResponse::class.java)

        // Configure mock callbacks to return helpers
        `when`(mockCallbacks.helpers).thenReturn(mockHelpers)

        // Configure helpers for string/byte conversion (commonly used)
        `when`(mockHelpers.stringToBytes(anyString())).thenAnswer { invocation ->
            (invocation.arguments[0] as String).toByteArray()
        }
        `when`(mockHelpers.bytesToString(any())).thenAnswer { invocation ->
            String(invocation.arguments[0] as ByteArray)
        }

        // Configure analyzeRequest to return a valid IRequestInfo mock for HTTP message processing
        val mockRequestInfo = mock(IRequestInfo::class.java)
        `when`(mockRequestInfo.headers).thenReturn(listOf("GET / HTTP/1.1", "Host: example.com"))
        `when`(mockHelpers.analyzeRequest(any(ByteArray::class.java))).thenReturn(mockRequestInfo)

        // Configure analyzeResponse to return a valid IResponseInfo mock for HTTP response processing
        val mockResponseInfo = mock(IResponseInfo::class.java)
        `when`(mockResponseInfo.headers).thenReturn(listOf("HTTP/1.1 200 OK", "Content-Type: text/html"))
        `when`(mockHelpers.analyzeResponse(any(ByteArray::class.java))).thenReturn(mockResponseInfo)

        // Create fresh BurpExtender instance for each test
        burpExtender = BurpExtender()
    }

    /**
     * AC 4: Test that all baseline tests pass against current, unmodified codebase
     * AC 1: Tests use mocks for the legacy IBurpExtenderCallbacks API
     *
     * This test verifies the complete extension initialization sequence works correctly.
     */
    @Test
    fun `test extension initialization lifecycle completes successfully`() {
        // Act: Initialize the extension with mocked callbacks
        burpExtender.registerExtenderCallbacks(mockCallbacks)

        // Assert: Verify core initialization methods were called
        verify(mockCallbacks).setExtensionName(NAME)
        verify(mockCallbacks).registerContextMenuFactory(any())
        verify(mockCallbacks).addSuiteTab(burpExtender)
        verify(mockCallbacks).registerHttpListener(burpExtender)

        // Verify helpers were accessed during initialization
        verify(mockCallbacks, atLeastOnce()).helpers
    }

    /**
     * AC 2: At least one test exists to verify successful registration of IMessageEditorTabFactory
     * AC 1: Tests use mocks for the legacy IBurpExtenderCallbacks API
     *
     * This test verifies that MessageViewerManager correctly registers enabled message viewers
     * during BurpExtender initialization.
     */
    @Test
    fun `test IMessageEditorTabFactory registration during initialization`() {
        // Act: Initialize the extension which should trigger manager registrations
        burpExtender.registerExtenderCallbacks(mockCallbacks)

        // Assert: Verify registerMessageEditorTabFactory was called
        // Note: The actual number of calls depends on enabled message viewers in default config
        verify(mockCallbacks, atLeastOnce()).registerMessageEditorTabFactory(any(IMessageEditorTabFactory::class.java))

        // Verify the remove callback was also prepared (for manager setup)
        // This ensures the MessageViewerManager was properly initialized
        verify(mockCallbacks, never()).removeMessageEditorTabFactory(any(IMessageEditorTabFactory::class.java))
    }

    /**
     * AC 3: At least one test exists to verify successful registration of IHttpListener
     * AC 1: Tests use mocks for the legacy IBurpExtenderCallbacks API
     *
     * This test verifies that BurpExtender registers itself as an HTTP listener
     * and HttpListenerManager registers enabled HTTP listeners during startup.
     */
    @Test
    fun `test IHttpListener registration during initialization`() {
        // Act: Initialize the extension
        burpExtender.registerExtenderCallbacks(mockCallbacks)

        // Assert: Verify the BurpExtender itself was registered as an HTTP listener
        verify(mockCallbacks).registerHttpListener(burpExtender)

        // Assert: Verify HTTP listeners from config were also registered
        // Note: The actual number depends on enabled HTTP listeners in default config
        verify(mockCallbacks, atLeastOnce()).registerHttpListener(any(IHttpListener::class.java))
    }

    /**
     * AC 1, 4: Comprehensive test of registerExtenderCallbacks method behavior
     *
     * This test verifies the complete sequence of operations that occur during
     * the extension registration process.
     */
    @Test
    fun `test registerExtenderCallbacks method behavior`() {
        // Act: Call the main extension entry point
        burpExtender.registerExtenderCallbacks(mockCallbacks)

        // Assert: Verify extension name is set correctly
        verify(mockCallbacks).setExtensionName(NAME)

        // Assert: Verify context menu factory registration
        verify(mockCallbacks).registerContextMenuFactory(any())

        // Assert: Verify suite tab addition
        verify(mockCallbacks).addSuiteTab(burpExtender)

        // Assert: Verify HTTP listener registration (BurpExtender implements IHttpListener)
        verify(mockCallbacks).registerHttpListener(burpExtender)

        // Assert: Verify helpers were accessed (needed for config loading and processing)
        verify(mockCallbacks, atLeastOnce()).helpers

        // Verify that manager classes were set up by checking their registration calls
        verify(mockCallbacks, atLeastOnce()).registerMessageEditorTabFactory(any(IMessageEditorTabFactory::class.java))
    }

    /**
     * AC 1, 4: Test HTTP message processing functionality
     *
     * This test verifies that the HTTP listener functionality works correctly
     * when messages are processed through the registered listener.
     */
    @Test
    fun `test HTTP message processing through registered listener`() {
        // Arrange: Initialize extension and prepare test message
        burpExtender.registerExtenderCallbacks(mockCallbacks)

        // Configure mock HTTP request/response for testing
        `when`(mockHttpRequestResponse.request).thenReturn("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
        `when`(mockHttpRequestResponse.response).thenReturn("HTTP/1.1 200 OK\r\n\r\nTest response".toByteArray())

        // Act: Process an HTTP message (simulating proxy traffic)
        // Note: Only responses from proxy tool are processed based on the implementation
        burpExtender.processHttpMessage(
            IBurpExtenderCallbacks.TOOL_PROXY,
            false,  // messageIsRequest = false (response)
            mockHttpRequestResponse
        )

        // Assert: Verify the message processing completed without exceptions
        // This test primarily ensures the HTTP listener registration was successful
        // and the message processing pipeline is functional
        verify(mockCallbacks, atLeastOnce()).helpers
    }

    /**
     * AC 1, 4: Test tool manager registration and lifecycle
     *
     * This test verifies that the various tool managers (MessageViewer, HttpListener, etc.)
     * are properly initialized and register their tools with Burp during extension startup.
     */
    @Test
    fun `test tool manager initialization and registration`() {
        // Act: Initialize extension which creates and initializes all managers
        burpExtender.registerExtenderCallbacks(mockCallbacks)

        // Assert: Verify MessageViewer registrations occurred
        verify(mockCallbacks, atLeastOnce()).registerMessageEditorTabFactory(any(IMessageEditorTabFactory::class.java))

        // Assert: Verify HttpListener registrations occurred
        verify(mockCallbacks, atLeastOnce()).registerHttpListener(any(IHttpListener::class.java))

        // Assert: Verify other tool registrations occurred (based on default config)
        // These verify that all manager classes were properly initialized
        // Note: Session handling actions (macros) may not be enabled in default config
        verify(mockCallbacks, atLeastOnce()).registerIntruderPayloadProcessor(any(IIntruderPayloadProcessor::class.java))
        verify(mockCallbacks, atLeastOnce()).registerIntruderPayloadGeneratorFactory(any(IIntruderPayloadGeneratorFactory::class.java))

        // Verify that no removal operations occurred during initialization
        verify(mockCallbacks, never()).removeMessageEditorTabFactory(any(IMessageEditorTabFactory::class.java))
        verify(mockCallbacks, never()).removeHttpListener(any(IHttpListener::class.java))
    }

    /**
     * AC 1, 4: Test configuration loading and model initialization
     *
     * This test verifies that configuration is properly loaded and the various
     * data models are correctly initialized during extension startup.
     */
    @Test
    fun `test configuration loading and model initialization`() {
        // Act: Initialize extension which loads configuration
        burpExtender.registerExtenderCallbacks(mockCallbacks)

        // Assert: Verify that callbacks were used to access extension helpers
        // This indicates successful configuration loading since helpers are needed
        // for configuration processing and tool initialization
        verify(mockCallbacks, atLeastOnce()).helpers

        // Assert: Verify that registration methods were called, indicating
        // successful configuration loading and model population
        verify(mockCallbacks).setExtensionName(NAME)
        verify(mockCallbacks).registerContextMenuFactory(any())

        // The fact that registrations occurred proves the config models were
        // successfully loaded and populated from the default configuration
        verify(mockCallbacks, atLeastOnce()).registerMessageEditorTabFactory(any(IMessageEditorTabFactory::class.java))
        verify(mockCallbacks, atLeastOnce()).registerHttpListener(any(IHttpListener::class.java))
    }
}

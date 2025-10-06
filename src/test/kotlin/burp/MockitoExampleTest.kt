package burp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.mockito.Mockito.*

/**
 * Example test class to verify Mockito mocking framework integration.
 *
 * This test demonstrates that Mockito can be used to create mocks and verify
 * behavior in the testing framework.
 */
@DisplayName("Mockito Framework Tests")
class MockitoExampleTest {

    // Simple interface to demonstrate mocking
    interface ExampleService {
        fun processData(input: String): String
        fun isValid(data: String): Boolean
        fun getCount(): Int
    }

    @Test
    @DisplayName("Basic mock creation and stubbing should work")
    fun testBasicMocking() {
        // Arrange
        val mockService = mock(ExampleService::class.java)
        `when`(mockService.processData("test")).thenReturn("processed: test")
        `when`(mockService.isValid("valid")).thenReturn(true)
        `when`(mockService.getCount()).thenReturn(42)

        // Act
        val result = mockService.processData("test")
        val isValid = mockService.isValid("valid")
        val count = mockService.getCount()

        // Assert
        assertEquals("processed: test", result, "Mock should return stubbed value")
        assertTrue(isValid, "Mock should return stubbed boolean value")
        assertEquals(42, count, "Mock should return stubbed integer value")
    }

    @Test
    @DisplayName("Method verification should work correctly")
    fun testMethodVerification() {
        // Arrange
        val mockService = mock(ExampleService::class.java)
        `when`(mockService.processData(anyString())).thenReturn("processed")

        // Act
        mockService.processData("input1")
        mockService.processData("input2")
        mockService.isValid("test")

        // Assert - verify method calls
        verify(mockService, times(2)).processData(anyString())
        verify(mockService, times(1)).isValid("test")
        verify(mockService, never()).getCount()
    }

    @Test
    @DisplayName("Argument matching should work correctly")
    fun testArgumentMatching() {
        // Arrange
        val mockService = mock(ExampleService::class.java)
        // Note: In Mockito, last matching stubbing wins, so general stubs should come first
        `when`(mockService.processData(anyString())).thenReturn("default result")
        `when`(mockService.processData("specific")).thenReturn("specific result")

        // Act & Assert
        assertEquals("specific result", mockService.processData("specific"))
        assertEquals("default result", mockService.processData("other"))

        // Verify calls with any string matcher
        verify(mockService, times(2)).processData(anyString())
    }

    @Test
    @DisplayName("Exception throwing should work correctly")
    fun testExceptionThrowingMock() {
        // Arrange
        val mockService = mock(ExampleService::class.java)
        `when`(mockService.processData("error")).thenThrow(RuntimeException("Test exception"))

        // Act & Assert
        val exception = assertThrows(RuntimeException::class.java) {
            mockService.processData("error")
        }
        assertEquals("Test exception", exception.message)
    }
}

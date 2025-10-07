package burp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Component Tests for Tool Manager Classes
 *
 * These tests verify that tool manager components can be instantiated and configured correctly
 * without requiring actual Burp API integration. They focus on testing component creation,
 * configuration parsing, and basic business logic.
 */
class ComponentTests {

    private lateinit var testConfig: Piper.Config

    @BeforeEach
    fun setUp() {
        // Create a test configuration with various tools
        val yamlConfig =
                """
            messageViewers:
            - name: Test Message Viewer
              enabled: true
              prefix: [cat]
              inputMethod: stdin

            macros:
            - name: Test Macro
              enabled: true
              prefix: [echo, "test"]
              inputMethod: stdin

            httpListeners:
            - name: Test HTTP Listener
              enabled: true
              prefix: [grep, "-i"]
              inputMethod: stdin
              scope: REQUEST

            highlighters:
            - name: Test Highlighter
              enabled: true
              prefix: [grep, "error"]
              inputMethod: stdin
              color: red
              overwrite: true
              applyWithListener: false

            commentators:
            - name: Test Commentator
              enabled: true
              prefix: [awk, "'{print $1}'"]
              inputMethod: stdin
              overwrite: false
              applyWithListener: true

            intruderPayloadProcessors:
            - name: Test Payload Processor
              enabled: true
              prefix: [base64]
              inputMethod: stdin

            intruderPayloadGenerators:
            - name: Test Payload Generator
              enabled: true
              prefix: [seq, "1", "10"]
              inputMethod: stdin
        """.trimIndent()

        testConfig = configFromYaml(yamlConfig)
    }

    @Nested
    @DisplayName("Configuration Parsing Component Tests")
    inner class ConfigurationParsingTests {

        @Test
        @DisplayName("should parse all tool types from YAML correctly")
        fun `parse all tool types from YAML correctly`() {
            // Assert all tool types were parsed
            assertEquals(1, testConfig.messageViewerCount, "Message viewers should be parsed")
            assertEquals(1, testConfig.macroCount, "Macros should be parsed")
            assertEquals(1, testConfig.httpListenerCount, "HTTP listeners should be parsed")
            assertEquals(1, testConfig.highlighterCount, "Highlighters should be parsed")
            assertEquals(1, testConfig.commentatorCount, "Commentators should be parsed")
            assertEquals(
                    1,
                    testConfig.intruderPayloadProcessorCount,
                    "Payload processors should be parsed"
            )
            assertEquals(
                    1,
                    testConfig.intruderPayloadGeneratorCount,
                    "Payload generators should be parsed"
            )
        }

        @Test
        @DisplayName("should extract enabled tools correctly")
        fun `extract enabled tools correctly`() {
            // Get enabled tools for each type
            val enabledMessageViewers = testConfig.messageViewerList.filter { it.common.enabled }
            val enabledMacros = testConfig.macroList.filter { it.enabled }
            val enabledHttpListeners = testConfig.httpListenerList.filter { it.common.enabled }
            val enabledHighlighters = testConfig.highlighterList.filter { it.common.enabled }
            val enabledCommentators = testConfig.commentatorList.filter { it.common.enabled }

            // Assert all test tools are enabled
            assertEquals(1, enabledMessageViewers.size)
            assertEquals(1, enabledMacros.size)
            assertEquals(1, enabledHttpListeners.size)
            assertEquals(1, enabledHighlighters.size)
            assertEquals(1, enabledCommentators.size)

            // Verify names
            assertEquals("Test Message Viewer", enabledMessageViewers[0].common.name)
            assertEquals("Test Macro", enabledMacros[0].name)
            assertEquals("Test HTTP Listener", enabledHttpListeners[0].common.name)
            assertEquals("Test Highlighter", enabledHighlighters[0].common.name)
            assertEquals("Test Commentator", enabledCommentators[0].common.name)
        }

        @Test
        @DisplayName("should handle command prefix parsing correctly")
        fun `handle command prefix parsing correctly`() {
            // Test message viewer command
            val messageViewer = testConfig.messageViewerList[0]
            assertEquals(1, messageViewer.common.cmd.prefixList.size)
            assertEquals("cat", messageViewer.common.cmd.prefixList[0])

            // Test macro command with multiple parameters
            val macro = testConfig.macroList[0]
            assertEquals(2, macro.cmd.prefixList.size)
            assertEquals("echo", macro.cmd.prefixList[0])
            assertEquals("test", macro.cmd.prefixList[1])

            // Test HTTP listener command
            val httpListener = testConfig.httpListenerList[0]
            assertEquals(2, httpListener.common.cmd.prefixList.size)
            assertEquals("grep", httpListener.common.cmd.prefixList[0])
            assertEquals("-i", httpListener.common.cmd.prefixList[1])
        }
    }

    @Nested
    @DisplayName("Specialized Tool Component Tests")
    inner class SpecializedToolTests {

        @Test
        @DisplayName("should parse highlighter-specific properties")
        fun `parse highlighter-specific properties`() {
            val highlighter = testConfig.highlighterList[0]

            // Test highlighter-specific properties
            assertEquals("red", highlighter.color)
            assertTrue(highlighter.overwrite)
            assertFalse(highlighter.applyWithListener)

            // Test common properties
            assertEquals("Test Highlighter", highlighter.common.name)
            assertTrue(highlighter.common.enabled)
        }

        @Test
        @DisplayName("should parse commentator-specific properties")
        fun `parse commentator-specific properties`() {
            val commentator = testConfig.commentatorList[0]

            // Test commentator-specific properties
            assertFalse(commentator.overwrite)
            assertTrue(commentator.applyWithListener)

            // Test common properties
            assertEquals("Test Commentator", commentator.common.name)
            assertTrue(commentator.common.enabled)
        }
    }

    @Nested
    @DisplayName("Tool Configuration Validation Tests")
    inner class ToolConfigurationValidationTests {

        @Test
        @DisplayName("should validate required command components exist")
        fun `validate required command components exist`() {
            // Test all tools have required command components
            testConfig.messageViewerList.forEach { viewer ->
                assertTrue(viewer.common.hasCmd(), "Message viewer should have command")
                assertTrue(viewer.common.cmd.prefixList.size > 0, "Command should have prefix")
                assertTrue(viewer.common.name.isNotEmpty(), "Tool should have name")
            }

            testConfig.macroList.forEach { macro ->
                assertTrue(macro.hasCmd(), "Macro should have command")
                assertTrue(macro.cmd.prefixList.size > 0, "Command should have prefix")
                assertTrue(macro.name.isNotEmpty(), "Tool should have name")
            }
        }

        @Test
        @DisplayName("should validate input method configuration")
        fun `validate input method configuration`() {
            // All test tools should be configured with stdin input method
            testConfig.messageViewerList.forEach { viewer ->
                assertEquals(
                        Piper.CommandInvocation.InputMethod.STDIN,
                        viewer.common.cmd.inputMethod
                )
            }

            testConfig.macroList.forEach { macro ->
                assertEquals(Piper.CommandInvocation.InputMethod.STDIN, macro.cmd.inputMethod)
            }

            testConfig.httpListenerList.forEach { listener ->
                assertEquals(
                        Piper.CommandInvocation.InputMethod.STDIN,
                        listener.common.cmd.inputMethod
                )
            }
        }
    }

    @Nested
    @DisplayName("Configuration Model Component Tests")
    inner class ConfigurationModelTests {

        @Test
        @DisplayName("should create default config model without errors")
        fun `create default config model without errors`() {
            // Act - create with null API (component test - no API dependency)
            try {
                // We can't instantiate ConfigModel without MontoyaApi, but we can test
                // the static methods and configuration loading logic
                val defaultConfig = Piper.Config.getDefaultInstance()
                assertNotNull(defaultConfig)

                // Assert
                assertNotNull(defaultConfig)
            } catch (e: Exception) {
                fail("Should be able to create default config without exceptions: ${e.message}")
            }
        }

        @Test
        @DisplayName("should handle configuration serialization correctly")
        fun `handle configuration serialization correctly`() {
            // Test YAML to config conversion
            val originalConfig = testConfig

            // Test config has expected structure
            assertTrue(originalConfig.messageViewerCount > 0)
            assertTrue(originalConfig.macroCount > 0)

            // Verify the parsed configuration maintains data integrity
            val messageViewer = originalConfig.messageViewerList[0]
            assertEquals("Test Message Viewer", messageViewer.common.name)
            assertTrue(messageViewer.common.enabled)

            val macro = originalConfig.macroList[0]
            assertEquals("Test Macro", macro.name)
            assertEquals(2, macro.cmd.prefixList.size)
        }
    }

    @Nested
    @DisplayName("Tool Filtering and Selection Tests")
    inner class ToolFilteringTests {

        @Test
        @DisplayName("should filter enabled tools correctly")
        fun `filter enabled tools correctly`() {
            // Create config with mixed enabled/disabled tools
            val mixedYaml =
                    """
                messageViewers:
                - name: Enabled Viewer
                  enabled: true
                  prefix: [cat]
                  inputMethod: stdin
                - name: Disabled Viewer
                  enabled: false
                  prefix: [head]
                  inputMethod: stdin
            """.trimIndent()

            val mixedConfig = configFromYaml(mixedYaml)

            // Filter enabled tools
            val enabledViewers = mixedConfig.messageViewerList.filter { it.common.enabled }
            val disabledViewers = mixedConfig.messageViewerList.filter { !it.common.enabled }

            // Assert filtering works correctly
            assertEquals(1, enabledViewers.size)
            assertEquals(1, disabledViewers.size)
            assertEquals("Enabled Viewer", enabledViewers[0].common.name)
            assertEquals("Disabled Viewer", disabledViewers[0].common.name)
        }

        @Test
        @DisplayName("should handle tools with complex command structures")
        fun `handle tools with complex command structures`() {
            val complexYaml =
                    """
                macros:
                - name: Complex Command
                  enabled: true
                  prefix: [python3, -c, "import sys; print(sys.stdin.read().upper())"]
                  inputMethod: stdin
            """.trimIndent()

            val complexConfig = configFromYaml(complexYaml)
            val complexMacro = complexConfig.macroList[0]

            // Assert complex command is parsed correctly
            assertEquals(3, complexMacro.cmd.prefixList.size)
            assertEquals("python3", complexMacro.cmd.prefixList[0])
            assertEquals("-c", complexMacro.cmd.prefixList[1])
            assertTrue(complexMacro.cmd.prefixList[2].contains("import sys"))
        }
    }

    @Nested
    @DisplayName("Error Handling Component Tests")
    inner class ErrorHandlingTests {

        @Test
        @DisplayName("should handle empty configuration gracefully")
        fun `handle empty configuration gracefully`() {
            val emptyConfig = configFromYaml("{}")

            // Assert empty config doesn't cause errors
            assertEquals(0, emptyConfig.messageViewerCount)
            assertEquals(0, emptyConfig.macroCount)
            assertEquals(0, emptyConfig.httpListenerCount)
            assertEquals(0, emptyConfig.highlighterCount)
            assertEquals(0, emptyConfig.commentatorCount)
        }

        @Test
        @DisplayName("should provide meaningful validation for tool components")
        fun `provide meaningful validation for tool components`() {
            // Create a valid tool and verify its components
            val validTool = testConfig.messageViewerList[0]

            // Assert all required components are present and valid
            assertNotNull(validTool.common)
            assertNotNull(validTool.common.name)
            assertNotNull(validTool.common.cmd)
            assertTrue(validTool.common.name.isNotEmpty())
            assertTrue(validTool.common.cmd.prefixList.size > 0)

            // Test command validation
            val command = validTool.common.cmd
            assertTrue(command.inputMethod != null)
            assertEquals(Piper.CommandInvocation.InputMethod.STDIN, command.inputMethod)
        }
    }
}

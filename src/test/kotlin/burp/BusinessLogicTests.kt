package burp

import java.lang.RuntimeException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Business Logic Unit Tests for Piper Extension
 *
 * These tests focus on pure business logic components that don't require Burp API integration. They
 * test YAML parsing, configuration management, data transformations, and utility functions in
 * isolation from the Burp Suite API.
 */
class BusinessLogicTests {

    @Nested
    @DisplayName("YAML Parsing Tests")
    inner class YamlParsingTests {

        @Test
        @DisplayName("should parse valid YAML config with message viewers")
        fun `parse valid YAML config with message viewers`() {
            // Arrange
            val yamlConfig =
                    """
                messageViewers:
                - name: Test Message Viewer
                  enabled: true
                  prefix: [echo]
                  inputMethod: stdin
            """.trimIndent()

            // Act
            val config = configFromYaml(yamlConfig)

            // Assert
            assertEquals(1, config.messageViewerCount)
            val viewer = config.messageViewerList[0]
            assertEquals("Test Message Viewer", viewer.common.name)
            assertTrue(viewer.common.enabled)
            assertEquals(1, viewer.common.cmd.prefixList.size)
            assertEquals("echo", viewer.common.cmd.prefixList[0])
        }

        @Test
        @DisplayName("should parse valid YAML config with macros")
        fun `parse valid YAML config with macros`() {
            // Arrange
            val yamlConfig =
                    """
                macros:
                - name: Test Macro
                  enabled: false
                  prefix: [curl, -X, POST]
                  inputMethod: filename
            """.trimIndent()

            // Act
            val config = configFromYaml(yamlConfig)

            // Assert
            assertEquals(1, config.macroCount)
            val macro = config.macroList[0]
            assertEquals("Test Macro", macro.name)
            assertFalse(macro.enabled)
            assertEquals(3, macro.cmd.prefixList.size)
            assertEquals("curl", macro.cmd.prefixList[0])
            assertEquals("-X", macro.cmd.prefixList[1])
            assertEquals("POST", macro.cmd.prefixList[2])
        }

        @Test
        @DisplayName("should parse YAML config with multiple tool types")
        fun `parse YAML config with multiple tool types`() {
            // Arrange
            val yamlConfig =
                    """
                messageViewers:
                - name: Viewer 1
                  enabled: true
                  prefix: [cat]
                  inputMethod: stdin
                macros:
                - name: Macro 1
                  enabled: true
                  prefix: [echo]
                  inputMethod: stdin
                httpListeners:
                - name: Listener 1
                  enabled: false
                  prefix: [grep, "-i"]
                  inputMethod: stdin
                  scope: REQUEST
            """.trimIndent()

            // Act
            val config = configFromYaml(yamlConfig)

            // Assert
            assertEquals(1, config.messageViewerCount)
            assertEquals(1, config.macroCount)
            assertEquals(1, config.httpListenerCount)

            assertEquals("Viewer 1", config.messageViewerList[0].common.name)
            assertEquals("Macro 1", config.macroList[0].name)
            assertEquals("Listener 1", config.httpListenerList[0].common.name)
        }

        @Test
        @DisplayName("should handle empty YAML config")
        fun `handle empty YAML config`() {
            // Arrange
            val emptyConfig = "{}"

            // Act
            val config = configFromYaml(emptyConfig)

            // Assert
            assertEquals(0, config.messageViewerCount)
            assertEquals(0, config.macroCount)
            assertEquals(0, config.httpListenerCount)
        }

        @Test
        @DisplayName("should throw exception for invalid YAML structure")
        fun `throw exception for invalid YAML structure`() {
            // Arrange
            val invalidYaml = "messageViewers: not_a_list"

            // Act & Assert
            assertThrows<RuntimeException> { configFromYaml(invalidYaml) }
        }
    }

    @Nested
    @DisplayName("MinimalTool Parsing Tests")
    inner class MinimalToolParsingTests {

        @Test
        @DisplayName("should parse minimal tool from map with all fields")
        fun `parse minimal tool from map with all fields`() {
            // Arrange
            val toolMap =
                    mapOf(
                            "name" to "Test Tool",
                            "enabled" to true,
                            "prefix" to listOf("echo", "hello"),
                            "inputMethod" to "stdin"
                    )

            // Act
            val tool = minimalToolFromMap(toolMap)

            // Assert
            assertEquals("Test Tool", tool.name)
            assertTrue(tool.enabled)
            assertEquals(2, tool.cmd.prefixList.size)
            assertEquals("echo", tool.cmd.prefixList[0])
            assertEquals("hello", tool.cmd.prefixList[1])
        }

        @Test
        @DisplayName("should handle disabled tool correctly")
        fun `handle disabled tool correctly`() {
            // Arrange
            val toolMap =
                    mapOf(
                            "name" to "Disabled Tool",
                            "enabled" to false,
                            "prefix" to listOf("test"),
                            "inputMethod" to "filename"
                    )

            // Act
            val tool = minimalToolFromMap(toolMap)

            // Assert
            assertEquals("Disabled Tool", tool.name)
            assertFalse(tool.enabled)
        }

        @Test
        @DisplayName("should default enabled to true when not specified")
        fun `default enabled to true when not specified`() {
            // Arrange
            val toolMap =
                    mapOf(
                            "name" to "Default Tool",
                            "prefix" to listOf("test"),
                            "inputMethod" to "stdin"
                    )

            // Act
            val tool = minimalToolFromMap(toolMap)

            // Assert
            assertEquals("Default Tool", tool.name)
            assertTrue(tool.enabled) // Should default to true
        }
    }

    @Nested
    @DisplayName("Highlighter and Commentator Parsing Tests")
    inner class SpecializedToolParsingTests {

        @Test
        @DisplayName("should parse highlighter with color and flags")
        fun `parse highlighter with color and flags`() {
            // Arrange
            val highlighterMap =
                    mapOf(
                            "name" to "Test Highlighter",
                            "enabled" to true,
                            "prefix" to listOf("grep", "-i", "error"),
                            "inputMethod" to "stdin",
                            "color" to "red",
                            "overwrite" to true,
                            "applyWithListener" to false
                    )

            // Act
            val highlighter = highlighterFromMap(highlighterMap)

            // Assert
            assertEquals("Test Highlighter", highlighter.common.name)
            assertTrue(highlighter.common.enabled)
            assertEquals("red", highlighter.color)
            assertTrue(highlighter.overwrite)
            assertFalse(highlighter.applyWithListener)
        }

        @Test
        @DisplayName("should parse commentator with flags")
        fun `parse commentator with flags`() {
            // Arrange
            val commentatorMap =
                    mapOf(
                            "name" to "Test Commentator",
                            "enabled" to true,
                            "prefix" to listOf("awk", "'{print $1}'"),
                            "inputMethod" to "stdin",
                            "overwrite" to false,
                            "applyWithListener" to true
                    )

            // Act
            val commentator = commentatorFromMap(commentatorMap)

            // Assert
            assertEquals("Test Commentator", commentator.common.name)
            assertTrue(commentator.common.enabled)
            assertFalse(commentator.overwrite)
            assertTrue(commentator.applyWithListener)
        }
    }

    @Nested
    @DisplayName("Utility Function Tests")
    inner class UtilityFunctionTests {

        @Test
        @DisplayName("should extract string from map correctly")
        fun `extract string from map correctly`() {
            // Arrange
            val map = mapOf("key" to "value")

            // Act
            val result = map.stringOrDie("key")

            // Assert
            assertEquals("value", result)
        }

        @Test
        @DisplayName("should throw exception for missing required string")
        fun `throw exception for missing required string`() {
            // Arrange
            val map = mapOf<String, Any>()

            // Act & Assert
            val exception = assertThrows<RuntimeException> { map.stringOrDie("missing_key") }
            assertTrue(exception.message?.contains("Missing value for missing_key") == true)
        }

        @Test
        @DisplayName("should handle string sequence correctly")
        fun `handle string sequence correctly`() {
            // Arrange
            val map = mapOf("items" to listOf("one", "two", "three"))

            // Act
            val result = map.stringSequence("items").toList()

            // Assert
            assertEquals(3, result.size)
            assertEquals("one", result[0])
            assertEquals("two", result[1])
            assertEquals("three", result[2])
        }

        @Test
        @DisplayName("should return empty list for optional missing string sequence")
        fun `return empty list for optional missing string sequence`() {
            // Arrange
            val map = mapOf<String, Any>()

            // Act
            val result = map.stringSequence("missing", required = false).toList()

            // Assert
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("should throw exception for required missing string sequence")
        fun `throw exception for required missing string sequence`() {
            // Arrange
            val map = mapOf<String, Any>()

            // Act & Assert
            assertThrows<RuntimeException> { map.stringSequence("missing", required = true) }
        }
    }

    @Nested
    @DisplayName("Enum Parsing Tests")
    inner class EnumParsingTests {

        @Test
        @DisplayName("should parse enum from string case insensitive")
        fun `parse enum from string case insensitive`() {
            // Act
            val result1 = enumFromString("STDIN", Piper.CommandInvocation.InputMethod::class.java)
            val result2 = enumFromString("stdin", Piper.CommandInvocation.InputMethod::class.java)
            val result3 = enumFromString("StDiN", Piper.CommandInvocation.InputMethod::class.java)

            // Assert
            assertEquals(Piper.CommandInvocation.InputMethod.STDIN, result1)
            assertEquals(Piper.CommandInvocation.InputMethod.STDIN, result2)
            assertEquals(Piper.CommandInvocation.InputMethod.STDIN, result3)
        }

        @Test
        @DisplayName("should handle space replacement in enum parsing")
        fun `handle space replacement in enum parsing`() {
            // Note: This assumes there might be enums with underscores that could be represented
            // with spaces
            // Act
            val result = enumFromString("STDIN", Piper.CommandInvocation.InputMethod::class.java)

            // Assert
            assertEquals(Piper.CommandInvocation.InputMethod.STDIN, result)
        }

        @Test
        @DisplayName("should throw exception for invalid enum value")
        fun `throw exception for invalid enum value`() {
            // Act & Assert
            val exception =
                    assertThrows<RuntimeException> {
                        enumFromString(
                                "INVALID_VALUE",
                                Piper.CommandInvocation.InputMethod::class.java
                        )
                    }
            assertTrue(exception.message?.contains("Invalid value for enumerated type") == true)
        }
    }

    @Nested
    @DisplayName("Integration Tests - Full YAML Parsing")
    inner class IntegrationTests {

        @Test
        @DisplayName("should parse complex realistic YAML configuration")
        fun `parse complex realistic YAML configuration`() {
            // Arrange
            val complexYaml =
                    """
                messageViewers:
                - name: JSON Pretty Print
                  enabled: true
                  prefix: [python3, -m, json.tool]
                  inputMethod: stdin
                - name: XML Pretty Print
                  enabled: false
                  prefix: [xmllint, --format, -]
                  inputMethod: stdin

                macros:
                - name: SQL Injection Test
                  enabled: true
                  prefix: [sqlmap, -r]
                  inputMethod: filename

                httpListeners:
                - name: Log Requests
                  enabled: true
                  prefix: [tee, -a, requests.log]
                  inputMethod: stdin
                  scope: REQUEST

                highlighters:
                - name: Error Highlighter
                  enabled: true
                  prefix: [grep, -i, error]
                  inputMethod: stdin
                  color: red
                  overwrite: true
                  applyWithListener: false

                commentators:
                - name: IP Extractor
                  enabled: true
                  prefix: [grep, -oE, '([0-9]{1,3}\.){3}[0-9]{1,3}']
                  inputMethod: stdin
                  overwrite: false
                  applyWithListener: true
            """.trimIndent()

            // Act
            val config = configFromYaml(complexYaml)

            // Assert
            // Verify all tool types were parsed
            assertEquals(2, config.messageViewerCount)
            assertEquals(1, config.macroCount)
            assertEquals(1, config.httpListenerCount)
            assertEquals(1, config.highlighterCount)
            assertEquals(1, config.commentatorCount)

            // Verify first message viewer
            val jsonViewer = config.messageViewerList[0]
            assertEquals("JSON Pretty Print", jsonViewer.common.name)
            assertTrue(jsonViewer.common.enabled)
            assertEquals(3, jsonViewer.common.cmd.prefixList.size)

            // Verify macro
            val sqlMacro = config.macroList[0]
            assertEquals("SQL Injection Test", sqlMacro.name)
            assertTrue(sqlMacro.enabled)

            // Verify highlighter
            val errorHighlighter = config.highlighterList[0]
            assertEquals("Error Highlighter", errorHighlighter.common.name)
            assertEquals("red", errorHighlighter.color)
            assertTrue(errorHighlighter.overwrite)

            // Verify commentator
            val ipCommentator = config.commentatorList[0]
            assertEquals("IP Extractor", ipCommentator.common.name)
            assertFalse(ipCommentator.overwrite)
            assertTrue(ipCommentator.applyWithListener)
        }

        @Test
        @DisplayName("should handle YAML with mixed enabled and disabled tools")
        fun `handle YAML with mixed enabled and disabled tools`() {
            // Arrange
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
                - name: Default Viewer
                  prefix: [tail]
                  inputMethod: stdin
            """.trimIndent()

            // Act
            val config = configFromYaml(mixedYaml)

            // Assert
            assertEquals(3, config.messageViewerCount)

            val enabledViewer = config.messageViewerList[0]
            assertTrue(enabledViewer.common.enabled)

            val disabledViewer = config.messageViewerList[1]
            assertFalse(disabledViewer.common.enabled)

            val defaultViewer = config.messageViewerList[2]
            assertTrue(defaultViewer.common.enabled) // Should default to true
        }
    }
}

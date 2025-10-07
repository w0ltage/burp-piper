package burp

import burp.api.montoya.MontoyaApi
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration model for managing Piper extension settings with Montoya API integration.
 *
 * This class provides a centralized way to manage configuration loading, saving, and validation for
 * the Piper Burp Suite extension using the modern Montoya API.
 */
class ConfigModel(private val montoyaApi: MontoyaApi) {

    /** Property change support for event handling */
    private val pcs = PropertyChangeSupport(this)

    /** Current configuration instance */
    private var _config: Piper.Config = Piper.Config.getDefaultInstance()

    /** Configuration file path */
    private var configPath: Path? = null

    /** Whether configuration has been modified */
    private var isDirty: Boolean = false

    /** Developer mode property */
    private var _developer = _config.developer
    var developer: Boolean
        get() = _developer
        set(value) {
            val old = _developer
            _developer = value
            pcs.firePropertyChange("developer", old, value)
        }

    /** Get current configuration */
    val config: Piper.Config
        get() = _config

    /** Model collections for UI components */
    val macrosModel = javax.swing.DefaultListModel<Piper.MinimalTool>()
    val messageViewersModel = javax.swing.DefaultListModel<Piper.MessageViewer>()
    val userActionToolsModel = javax.swing.DefaultListModel<Piper.UserActionTool>()
    val httpListenersModel = javax.swing.DefaultListModel<Piper.HttpListener>()
    val commentatorsModel = javax.swing.DefaultListModel<Piper.Commentator>()
    val intruderPayloadProcessorsModel = javax.swing.DefaultListModel<Piper.MinimalTool>()
    val highlightersModel = javax.swing.DefaultListModel<Piper.Highlighter>()
    val intruderPayloadGeneratorsModel = javax.swing.DefaultListModel<Piper.MinimalTool>()

    /** Add property change listener */
    fun addPropertyChangeListener(listener: PropertyChangeListener) {
        pcs.addPropertyChangeListener(listener)
    }

    /** Populate UI models from configuration */
    fun fillModels(config: Piper.Config) {
        fillDefaultModel(config.macroList, macrosModel)
        fillDefaultModel(config.messageViewerList, messageViewersModel)
        fillDefaultModel(config.menuItemList, userActionToolsModel)
        fillDefaultModel(config.httpListenerList, httpListenersModel)
        fillDefaultModel(config.commentatorList, commentatorsModel)
        fillDefaultModel(config.intruderPayloadProcessorList, intruderPayloadProcessorsModel)
        fillDefaultModel(config.highlighterList, highlightersModel)
        fillDefaultModel(config.intruderPayloadGeneratorList, intruderPayloadGeneratorsModel)
    }

    /** Initialize configuration from default YAML resources */
    fun initializeFromDefaults() {
        try {
            val defaultYamlResource = javaClass.getResourceAsStream("/defaults.yaml")
            if (defaultYamlResource != null) {
                val yamlContent = defaultYamlResource.use { it.readBytes().decodeToString() }
                _config = fromYaml(yamlContent)
                fillModels(_config)
                isDirty = false
                montoyaApi.logging().logToOutput("Configuration initialized from defaults")
            } else {
                montoyaApi
                        .logging()
                        .logToError("Default configuration not found, using empty config")
                _config = Piper.Config.getDefaultInstance()
            }
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Failed to initialize from defaults: ${e.message}")
            _config = Piper.Config.getDefaultInstance()
        }
    }

    /** Update configuration with new config instance */
    fun updateConfig(newConfig: Piper.Config) {
        val oldConfig = _config
        _config = newConfig
        fillModels(_config)
        isDirty = true
        pcs.firePropertyChange("config", oldConfig, newConfig)
        montoyaApi.logging().logToOutput("Configuration updated")
    }

    /** Load configuration from file */
    fun loadFromFile(path: Path): Boolean {
        return try {
            configPath = path
            when {
                path.toString().endsWith(".yaml", ignoreCase = true) ||
                        path.toString().endsWith(".yml", ignoreCase = true) -> {
                    val yamlContent = File(path.toString()).readText()
                    _config = fromYaml(yamlContent)
                }
                path.toString().endsWith(".pb", ignoreCase = true) -> {
                    val bytes = File(path.toString()).readBytes()
                    _config = Piper.Config.parseFrom(bytes)
                }
                else -> {
                    montoyaApi.logging().logToError("Unsupported configuration file format: $path")
                    return false
                }
            }
            fillModels(_config)
            isDirty = false
            montoyaApi.logging().logToOutput("Configuration loaded from: $path")
            true
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Failed to load configuration from $path: ${e.message}")
            false
        }
    }

    /** Save configuration to file */
    fun saveToFile(path: Path? = null): Boolean {
        val targetPath = path ?: configPath ?: return false

        return try {
            when {
                targetPath.toString().endsWith(".yaml", ignoreCase = true) ||
                        targetPath.toString().endsWith(".yml", ignoreCase = true) -> {
                    val yamlContent = toYaml(_config)
                    File(targetPath.toString()).writeText(yamlContent)
                }
                targetPath.toString().endsWith(".pb", ignoreCase = true) -> {
                    val bytes = _config.toByteArray()
                    File(targetPath.toString()).writeBytes(bytes)
                }
                else -> {
                    montoyaApi
                            .logging()
                            .logToError("Unsupported configuration file format: $targetPath")
                    return false
                }
            }
            configPath = targetPath
            isDirty = false
            montoyaApi.logging().logToOutput("Configuration saved to: $targetPath")
            true
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError("Failed to save configuration to $targetPath: ${e.message}")
            false
        }
    }

    /** Check if configuration has been modified */
    fun isDirty(): Boolean = isDirty

    /** Mark configuration as clean (saved) */
    fun markClean() {
        isDirty = false
    }

    /** Mark configuration as dirty (modified) */
    fun markDirty() {
        isDirty = true
    }

    /** Get configuration file path */
    fun getConfigPath(): Path? = configPath

    /** Validate current configuration */
    fun validateConfiguration(): List<String> {
        val errors = mutableListOf<String>()

        // Validate macro tools
        config.macroList.forEachIndexed { index, tool ->
            if (tool.name.isBlank()) {
                errors.add("Macro tool at index $index has empty name")
            }
            if (!tool.hasCmd()) {
                errors.add("Macro tool '${tool.name}' has no command defined")
            }
        }

        // Validate message viewers
        config.messageViewerList.forEachIndexed { index, viewer ->
            if (viewer.common.name.isBlank()) {
                errors.add("Message viewer at index $index has empty name")
            }
            if (!viewer.common.hasCmd()) {
                errors.add("Message viewer '${viewer.common.name}' has no command defined")
            }
        }

        // Validate user action tools (menu items)
        config.menuItemList.forEachIndexed { index, tool ->
            if (tool.common.name.isBlank()) {
                errors.add("User action tool at index $index has empty name")
            }
            if (!tool.common.hasCmd()) {
                errors.add("User action tool '${tool.common.name}' has no command defined")
            }
        }

        // Validate HTTP listeners
        config.httpListenerList.forEachIndexed { index, listener ->
            if (listener.common.name.isBlank()) {
                errors.add("HTTP listener at index $index has empty name")
            }
            if (!listener.common.hasCmd()) {
                errors.add("HTTP listener '${listener.common.name}' has no command defined")
            }
        }

        // Validate commentators
        config.commentatorList.forEachIndexed { index, commentator ->
            if (commentator.common.name.isBlank()) {
                errors.add("Commentator at index $index has empty name")
            }
            if (!commentator.common.hasCmd()) {
                errors.add("Commentator '${commentator.common.name}' has no command defined")
            }
        }

        // Validate highlighters
        config.highlighterList.forEachIndexed { index, highlighter ->
            if (highlighter.common.name.isBlank()) {
                errors.add("Highlighter at index $index has empty name")
            }
            if (!highlighter.common.hasCmd()) {
                errors.add("Highlighter '${highlighter.common.name}' has no command defined")
            }
        }

        // Validate intruder payload processors
        config.intruderPayloadProcessorList.forEachIndexed { index, processor ->
            if (processor.name.isBlank()) {
                errors.add("Intruder payload processor at index $index has empty name")
            }
            if (!processor.hasCmd()) {
                errors.add("Intruder payload processor '${processor.name}' has no command defined")
            }
        }

        // Validate intruder payload generators
        config.intruderPayloadGeneratorList.forEachIndexed { index, generator ->
            if (generator.name.isBlank()) {
                errors.add("Intruder payload generator at index $index has empty name")
            }
            if (!generator.hasCmd()) {
                errors.add("Intruder payload generator '${generator.name}' has no command defined")
            }
        }

        return errors
    }

    /** Get configuration statistics */
    fun getConfigurationStats(): Map<String, Int> {
        return mapOf(
                "macros" to config.macroCount,
                "messageViewers" to config.messageViewerCount,
                "menuItems" to config.menuItemCount,
                "httpListeners" to config.httpListenerCount,
                "commentators" to config.commentatorCount,
                "highlighters" to config.highlighterCount,
                "intruderPayloadProcessors" to config.intruderPayloadProcessorCount,
                "intruderPayloadGenerators" to config.intruderPayloadGeneratorCount
        )
    }

    /** Reset configuration to defaults */
    fun resetToDefaults() {
        initializeFromDefaults()
        isDirty = true
    }

    /** Create a backup of current configuration */
    fun createBackup(): Piper.Config {
        return _config.toBuilder().build()
    }

    /** Restore from backup */
    fun restoreFromBackup(backup: Piper.Config) {
        _config = backup
        isDirty = true
    }

    /** Convert configuration to YAML format */
    private fun toYaml(config: Piper.Config): String {
        // This would use the Serialization utilities to convert to YAML
        // For now, delegating to the existing serialization logic
        return toYaml(config)
    }

    /** Convert YAML to configuration */
    private fun fromYaml(yamlContent: String): Piper.Config {
        // This would use the Serialization utilities to parse YAML
        // For now, delegating to the existing serialization logic
        return configFromYaml(yamlContent)
    }

    companion object {
        /** Create ConfigModel instance with default initialization */
        fun createWithDefaults(montoyaApi: MontoyaApi): ConfigModel {
            val model = ConfigModel(montoyaApi)
            model.initializeFromDefaults()
            return model
        }

        /** Create ConfigModel instance from file */
        fun createFromFile(montoyaApi: MontoyaApi, path: Path): ConfigModel? {
            val model = ConfigModel(montoyaApi)
            return if (model.loadFromFile(path)) model else null
        }

        /** Get default configuration directory */
        fun getDefaultConfigDirectory(): Path {
            return Paths.get(System.getProperty("user.home"), ".burp", "piper")
        }

        /** Get default configuration file path */
        fun getDefaultConfigPath(): Path {
            return getDefaultConfigDirectory().resolve("config.yaml")
        }
    }
}

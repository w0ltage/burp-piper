package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.logging.Logging
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract base class for managing tool registrations with the Montoya API.
 *
 * This class provides common functionality for registering, unregistering, and managing various
 * types of tools (Message Viewers, HTTP Listeners, Context Menus, etc.) with the Burp Suite Montoya
 * API.
 *
 * @param T The model type (e.g., Piper.MinimalTool, Piper.HttpListener)
 * @param R The Burp registration type returned by the Montoya API
 */
abstract class RegisteredToolManager<T, R>(
        protected val montoyaApi: MontoyaApi,
        protected val config: ConfigModel
) {

    /** Map of registered tools to their Burp registrations */
    protected val registrations = ConcurrentHashMap<T, Registration>()

    /** Logging interface */
    protected val logging: Logging = montoyaApi.logging()

    /**
     * Check if a model item is enabled and should be registered
     *
     * @param modelItem The configuration model item to check
     * @return true if the item should be registered
     */
    abstract fun isModelItemEnabled(modelItem: T): Boolean

    /**
     * Convert a model item to a Burp registration object
     *
     * @param modelItem The configuration model item to convert
     * @return The converted Burp object ready for registration
     */
    abstract fun modelToBurp(modelItem: T): R

    /**
     * Register a model item with Burp Suite
     *
     * @param modelItem The model item to register
     * @param burpItem The converted Burp object
     * @return The Registration object returned by Burp, or null if registration failed
     */
    abstract fun registerWithBurp(modelItem: T, burpItem: R): Registration?

    /** Get the name of the tool type for logging purposes */
    abstract fun getToolTypeName(): String

    /**
     * Register a single tool
     *
     * @param modelItem The model item to register
     * @return true if registration was successful
     */
    fun registerTool(modelItem: T): Boolean {
        return try {
            logging.logToOutput(
                    "DEBUG: Starting registration for ${getToolTypeName()}: ${getItemName(modelItem)}"
            )

            if (!isModelItemEnabled(modelItem)) {
                logging.logToOutput(
                        "DEBUG: Skipping disabled ${getToolTypeName()}: ${getItemName(modelItem)}"
                )
                return true
            }

            logging.logToOutput("DEBUG: ${getToolTypeName()} is enabled: ${getItemName(modelItem)}")

            // Check if already registered
            if (registrations.containsKey(modelItem)) {
                logging.logToOutput(
                        "DEBUG: ${getToolTypeName()} already registered: ${getItemName(modelItem)}"
                )
                return true
            }

            // Convert model to Burp object
            logging.logToOutput(
                    "DEBUG: Converting ${getToolTypeName()} to Burp object: ${getItemName(modelItem)}"
            )
            val burpItem = modelToBurp(modelItem)

            // Register with Burp
            logging.logToOutput(
                    "DEBUG: Registering ${getToolTypeName()} with Burp: ${getItemName(modelItem)}"
            )
            val registration = registerWithBurp(modelItem, burpItem)
            if (registration != null) {
                registrations[modelItem] = registration
                logging.logToOutput(
                        "DEBUG: Successfully registered ${getToolTypeName()}: ${getItemName(modelItem)}"
                )
                true
            } else {
                logging.logToError(
                        "DEBUG: Failed to register ${getToolTypeName()}: ${getItemName(modelItem)} - registration returned null"
                )
                false
            }
        } catch (e: Exception) {
            logging.logToError(
                    "DEBUG: Error registering ${getToolTypeName()} '${getItemName(modelItem)}': ${e.message}"
            )
            e.printStackTrace()
            false
        }
    }

    /**
     * Unregister a single tool
     *
     * @param modelItem The model item to unregister
     * @return true if unregistration was successful
     */
    fun unregisterTool(modelItem: T): Boolean {
        return try {
            val registration = registrations.remove(modelItem)
            if (registration != null) {
                if (registration.isRegistered) {
                    registration.deregister()
                }
                logging.logToOutput("Unregistered ${getToolTypeName()}: ${getItemName(modelItem)}")
                true
            } else {
                logging.logToOutput(
                        "${getToolTypeName()} was not registered: ${getItemName(modelItem)}"
                )
                true
            }
        } catch (e: Exception) {
            logging.logToError(
                    "Error unregistering ${getToolTypeName()} '${getItemName(modelItem)}': ${e.message}"
            )
            false
        }
    }

    /**
     * Register all tools from the configuration
     *
     * @param tools List of model items to register
     * @return Number of successfully registered tools
     */
    fun registerAll(tools: List<T>): Int {
        logging.logToOutput("DEBUG: RegisterAll called with ${tools.size} ${getToolTypeName()}s")
        var successCount = 0
        for (tool in tools) {
            logging.logToOutput("DEBUG: Processing ${getToolTypeName()}: ${getItemName(tool)}")
            if (registerTool(tool)) {
                successCount++
            }
        }
        logging.logToOutput(
                "DEBUG: Registered $successCount of ${tools.size} ${getToolTypeName()}s"
        )
        return successCount
    }

    /**
     * Unregister all currently registered tools
     *
     * @return Number of successfully unregistered tools
     */
    fun unregisterAll(): Int {
        var successCount = 0
        val toolsToUnregister = registrations.keys.toList()

        for (tool in toolsToUnregister) {
            if (unregisterTool(tool)) {
                successCount++
            }
        }

        logging.logToOutput("Unregistered $successCount ${getToolTypeName()}s")
        return successCount
    }

    /**
     * Refresh registrations - unregister all and re-register from current configuration
     *
     * @param tools List of model items to register
     * @return Number of successfully registered tools after refresh
     */
    fun refreshRegistrations(tools: List<T>): Int {
        logging.logToOutput("Refreshing ${getToolTypeName()} registrations...")
        unregisterAll()
        return registerAll(tools)
    }

    /** Get the count of currently registered tools */
    fun getRegisteredCount(): Int {
        return registrations.size
    }

    /** Check if a specific tool is registered */
    fun isRegistered(modelItem: T): Boolean {
        val registration = registrations[modelItem]
        return registration?.isRegistered ?: false
    }

    /** Get all currently registered tools */
    fun getRegisteredTools(): List<T> {
        return registrations.keys.toList()
    }

    /** Get statistics about registrations */
    fun getRegistrationStats(): Map<String, Any> {
        return mapOf(
                "toolType" to getToolTypeName(),
                "registeredCount" to getRegisteredCount(),
                "registrations" to registrations.keys.map { getItemName(it) }
        )
    }

    /**
     * Get the name of a model item for logging purposes Override this method if your model type
     * doesn't have a standard "name" field
     */
    protected open fun getItemName(modelItem: T): String {
        return when (modelItem) {
            is Piper.MinimalTool -> modelItem.name
            is Piper.HttpListener -> modelItem.common.name
            is Piper.UserActionTool -> modelItem.common.name
            is Piper.Commentator -> modelItem.common.name
            is Piper.Highlighter -> modelItem.common.name
            else -> modelItem.toString()
        }
    }

    /**
     * Validate a model item before registration Override this method to add custom validation logic
     */
    protected open fun validateModelItem(modelItem: T): List<String> {
        val errors = mutableListOf<String>()

        if (getItemName(modelItem).isBlank()) {
            errors.add("Tool name cannot be empty")
        }

        return errors
    }

    /** Handle registration failure Override this method to add custom error handling */
    protected open fun handleRegistrationFailure(modelItem: T, error: Exception) {
        logging.logToError(
                "Registration failed for ${getToolTypeName()} '${getItemName(modelItem)}': ${error.message}"
        )
    }

    /** Cleanup resources when the manager is being destroyed */
    fun cleanup() {
        unregisterAll()
        registrations.clear()
    }

    /**
     * Initialize the manager with a list of tools from configuration
     *
     * @param tools List of tools to process and register
     * @return true if initialization was successful, false otherwise
     */
    fun initialize(tools: List<T>): Boolean {
        return try {
            logging.logToOutput(
                    "DEBUG: Initializing ${getToolTypeName()} manager with ${tools.size} tools"
            )
            val result = registerAll(tools)
            logging.logToOutput(
                    "DEBUG: ${getToolTypeName()} manager initialization complete. Registered: ${getRegisteredCount()}/${tools.size}"
            )
            true
        } catch (e: Exception) {
            logging.logToError("Failed to initialize ${getToolTypeName()} manager: ${e.message}")
            false
        }
    }
}

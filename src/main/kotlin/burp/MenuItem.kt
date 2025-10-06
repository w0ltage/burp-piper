package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import java.awt.Component
import javax.swing.JMenuItem
import javax.swing.JSeparator

/**
 * Context menu provider for Piper tools using Montoya API.
 *
 * This class creates context menu items for configured Piper tools, allowing users to execute tools
 * directly from Burp's context menus.
 */
class PiperContextMenuProvider(
        private val config: Piper.Config,
        private val montoyaApi: MontoyaApi,
        private val toolExecutor: (ContextMenuEvent, Piper.MinimalTool) -> Unit
) : ContextMenuItemsProvider {

    override fun provideMenuItems(event: ContextMenuEvent): List<Component>? {
        val menuItems = mutableListOf<Component>()

        // Add minimal tools (message viewers, etc.)
        val enabledMinimalTools = config.messageViewerList.filter { it.common.enabled }
        if (enabledMinimalTools.isNotEmpty()) {
            enabledMinimalTools.forEach { tool: Piper.MessageViewer ->
                menuItems.add(createMenuItemForTool(tool.common, event))
            }
            if (menuItems.isNotEmpty()) {
                menuItems.add(createSeparator())
            }
        }

        // Add menu items (user action tools)
        val enabledMenuItems = config.menuItemList.filter { it.common.enabled }
        if (enabledMenuItems.isNotEmpty()) {
            enabledMenuItems.forEach { tool: Piper.UserActionTool ->
                menuItems.add(createMenuItemForTool(tool.common, event))
            }
            if (menuItems.isNotEmpty()) {
                menuItems.add(createSeparator())
            }
        }

        // Remove trailing separator if it exists
        if (menuItems.isNotEmpty() && menuItems.last() is JSeparator) {
            menuItems.removeAt(menuItems.size - 1)
        }

        return if (menuItems.isEmpty()) null else menuItems
    }

    /** Create a menu item for a specific Piper tool. */
    private fun createMenuItemForTool(tool: Piper.MinimalTool, event: ContextMenuEvent): Component {
        val menuItem = JMenuItem("Piper: ${tool.name}")

        menuItem.addActionListener {
            try {
                toolExecutor(event, tool)
            } catch (e: Exception) {
                montoyaApi
                        .logging()
                        .logToError("Error executing Piper tool '${tool.name}': ${e.message}")
            }
        }

        return menuItem
    }

    /** Create a separator component for the menu. */
    private fun createSeparator(): Component {
        return JSeparator()
    }
}

/** Factory object for creating Piper menu items. */
object PiperMenuItem {

    /** Create a context menu provider for the given configuration. */
    fun createProvider(
            config: Piper.Config,
            montoyaApi: MontoyaApi,
            toolExecutor: (ContextMenuEvent, Piper.MinimalTool) -> Unit
    ): ContextMenuItemsProvider {
        return PiperContextMenuProvider(config, montoyaApi, toolExecutor)
    }

    /** Check if any tools are enabled and would produce menu items. */
    fun hasEnabledTools(config: Piper.Config): Boolean {
        return config.messageViewerList.any { it.common.enabled } ||
                config.menuItemList.any { it.common.enabled }
    }
}

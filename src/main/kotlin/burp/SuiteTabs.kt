package burp

import java.awt.Component
import javax.swing.JCheckBox
import javax.swing.JTabbedPane

fun populatePiperTabs(
    tabs: JTabbedPane,
    cfg: ConfigModel,
    parent: Component?,
) {
    val switchToCommentator = {
        val index = tabs.indexOfTab("Commentators")
        if (index >= 0) {
            tabs.selectedIndex = index
        }
    }

    tabs.addTab(
        "Message viewers",
        createMessageViewerManager(
            cfg.messageViewersModel,
            parent,
            cfg.commentatorsModel,
            switchToCommentator,
        ),
    )

    tabs.addTab(
        "Context menu items",
        createMenuItemManager(
            cfg.menuItemsModel,
            parent,
        ),
    )

    tabs.addTab(
        "Macros",
        createMacroManager(
            cfg.macrosModel,
            parent,
        ),
    )

    tabs.addTab(
        "HTTP listeners",
        createHttpListenerManager(
            cfg.httpListenersModel,
            parent,
        ),
    )

    tabs.addTab(
        "Commentators",
        createCommentatorManager(
            cfg.commentatorsModel,
            parent,
        ),
    )

    tabs.addTab(
        "Intruder payload processors",
        createIntruderPayloadProcessorManager(
            cfg.intruderPayloadProcessorsModel,
            parent,
        ),
    )

    tabs.addTab(
        "Intruder payload generators",
        IntruderPayloadGeneratorManagerPanel(
            cfg.intruderPayloadGeneratorsModel,
            parent,
        ),
    )

    tabs.addTab(
        "Highlighters",
        createHighlighterManager(
            cfg.highlightersModel,
            parent,
        ),
    )

    tabs.addTab("Load/Save configuration", createLoadSaveUI(cfg, parent))
    tabs.addTab("Developer", createDeveloperUI(cfg))
}

fun createDeveloperUI(cfg: ConfigModel): Component =
    JCheckBox("show user interface elements suited for developers").apply {
        isSelected = cfg.developer
        cfg.addPropertyChangeListener { isSelected = cfg.developer }
        addChangeListener { cfg.developer = isSelected }
    }

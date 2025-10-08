package burp

import java.awt.Component
import javax.swing.JCheckBox
import javax.swing.JTabbedPane

/**
 * Populate the shared Piper configuration tabs on the supplied [JTabbedPane].
 *
 * The [queueComponentProvider] parameter allows callers to lazily provide a
 * component for the legacy queue functionality. Passing `null` will omit the
 * queue tab, which is useful for environments where it is not available (for
 * example the Montoya API).
 */
fun populatePiperTabs(
    tabs: JTabbedPane,
    cfg: ConfigModel,
    parent: Component?,
    queueComponentProvider: (() -> Component)? = null,
) {
    val switchToCommentator = {
        val index = tabs.indexOfTab("Commentators")
        if (index >= 0) {
            tabs.selectedIndex = index
        }
    }

    tabs.addTab(
        "Message viewers",
        MessageViewerListEditor(
            cfg.messageViewersModel,
            parent,
            cfg.commentatorsModel,
            switchToCommentator,
        ),
    )

    tabs.addTab(
        "Context menu items",
        MinimalToolListEditor(
            cfg.menuItemsModel,
            parent,
            ::MenuItemDialog,
            Piper.UserActionTool::getDefaultInstance,
            UserActionToolFromMap,
            Piper.UserActionTool::toMap,
        ),
    )

    tabs.addTab(
        "Macros",
        MinimalToolListEditor(
            cfg.macrosModel,
            parent,
            ::MacroDialog,
            Piper.MinimalTool::getDefaultInstance,
            ::minimalToolFromMap,
            Piper.MinimalTool::toMap,
        ),
    )

    tabs.addTab(
        "HTTP listeners",
        MinimalToolListEditor(
            cfg.httpListenersModel,
            parent,
            ::HttpListenerDialog,
            Piper.HttpListener::getDefaultInstance,
            ::httpListenerFromMap,
            Piper.HttpListener::toMap,
        ),
    )

    tabs.addTab(
        "Commentators",
        MinimalToolListEditor(
            cfg.commentatorsModel,
            parent,
            ::CommentatorDialog,
            Piper.Commentator::getDefaultInstance,
            ::commentatorFromMap,
            Piper.Commentator::toMap,
        ),
    )

    tabs.addTab(
        "Intruder payload processors",
        MinimalToolListEditor(
            cfg.intruderPayloadProcessorsModel,
            parent,
            ::IntruderPayloadProcessorDialog,
            Piper.MinimalTool::getDefaultInstance,
            ::minimalToolFromMap,
            Piper.MinimalTool::toMap,
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
        MinimalToolListEditor(
            cfg.highlightersModel,
            parent,
            ::HighlighterDialog,
            Piper.Highlighter::getDefaultInstance,
            ::highlighterFromMap,
            Piper.Highlighter::toMap,
        ),
    )

    queueComponentProvider?.invoke()?.let { queueComponent ->
        tabs.addTab("Queue", queueComponent)
    }

    tabs.addTab("Load/Save configuration", createLoadSaveUI(cfg, parent))
    tabs.addTab("Developer", createDeveloperUI(cfg))
}

fun createDeveloperUI(cfg: ConfigModel): Component =
    JCheckBox("show user interface elements suited for developers").apply {
        isSelected = cfg.developer
        cfg.addPropertyChangeListener { isSelected = cfg.developer }
        addChangeListener { cfg.developer = isSelected }
    }

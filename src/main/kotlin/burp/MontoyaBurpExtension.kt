/*
 * This file is part of Piper for Burp Suite (https://github.com/silentsignal/burp-piper)
 * Copyright (c) 2018 Andras Veres-Szentkiralyi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package burp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.sessions.SessionHandlingAction
import burp.api.montoya.http.sessions.SessionHandlingActionData
import burp.api.montoya.intruder.PayloadData
import burp.api.montoya.intruder.PayloadGeneratorProvider
import burp.api.montoya.intruder.PayloadProcessingResult
import burp.api.montoya.intruder.PayloadProcessor
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider
import burp.api.montoya.utilities.Utilities
import java.awt.Component
import java.io.File
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.swing.*
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

/**
 * Main Burp Suite extension class implementing the Montoya API. Migrated from legacy IBurpExtender
 * to burp.api.montoya.BurpExtension interface.
 */
class MontoyaBurpExtension : BurpExtension, ListDataListener, HttpHandler {

    companion object {
        const val NAME = "Piper"
        const val EXTENSION_SETTINGS_KEY = "piper_config"
    }

    private lateinit var montoyaApi: MontoyaApi
    private lateinit var utilities: Utilities
    private lateinit var configModel: ConfigModel
    private val queue by lazy { Queue(montoyaApi, configModel) }
    private val tabs = JTabbedPane()

    override fun contentsChanged(p0: ListDataEvent?) = saveConfig()
    override fun intervalAdded(p0: ListDataEvent?) = saveConfig()
    override fun intervalRemoved(p0: ListDataEvent?) = saveConfig()

    /** Manager for Message Viewer tools */
    private inner class MessageViewerManager :
            RegisteredToolManager<Piper.MessageViewer, HttpRequestEditorProvider>(
                    montoyaApi,
                    configModel
            ) {

        override fun getToolTypeName(): String = "Message Viewer"

        override fun isModelItemEnabled(item: Piper.MessageViewer): Boolean = item.common.enabled

        override fun registerWithBurp(
                modelItem: Piper.MessageViewer,
                burpItem: HttpRequestEditorProvider
        ): Registration? {
            return montoyaApi.userInterface().registerHttpRequestEditorProvider(burpItem)
        }

        override fun modelToBurp(modelItem: Piper.MessageViewer): HttpRequestEditorProvider =
                HttpRequestEditorProvider { creationContext ->
                    if (modelItem.usesColors)
                            MontoyaTerminalEditor(modelItem, utilities, montoyaApi)
                    else MontoyaTextEditor(modelItem, utilities, montoyaApi)
                }
    }

    /** Manager for Macro tools (Session Handling Actions) */
    private inner class MacroManager :
            RegisteredToolManager<Piper.MinimalTool, SessionHandlingAction>(
                    montoyaApi,
                    configModel
            ) {

        override fun getToolTypeName(): String = "Macro"

        override fun isModelItemEnabled(item: Piper.MinimalTool): Boolean = item.enabled

        override fun registerWithBurp(
                modelItem: Piper.MinimalTool,
                burpItem: SessionHandlingAction
        ): Registration? {
            return montoyaApi.http().registerSessionHandlingAction(burpItem)
        }

        override fun modelToBurp(modelItem: Piper.MinimalTool): SessionHandlingAction =
                object : SessionHandlingAction {
                    override fun performAction(
                            actionData: SessionHandlingActionData
                    ): burp.api.montoya.http.sessions.ActionResult {
                        return try {
                            val requestBytes = actionData.request().toByteArray().toByteArray()
                            val (process, tempFiles) = modelItem.cmd.execute(requestBytes)
                            val result = process.inputStream.readBytes()
                            tempFiles.forEach { it.delete() }

                            val resultString = String(result)
                            val newRequest =
                                    if (resultString.isNotBlank()) {
                                        HttpRequest.httpRequest(resultString)
                                    } else {
                                        actionData.request()
                                    }
                            burp.api.montoya.http.sessions.ActionResult.actionResult(newRequest)
                        } catch (e: Exception) {
                            montoyaApi.logging().logToError("Macro execution failed: ${e.message}")
                            burp.api.montoya.http.sessions.ActionResult.actionResult(
                                    actionData.request()
                            )
                        }
                    }

                    override fun name(): String = modelItem.name
                }
    }

    /** Manager for HTTP Listener tools */
    private inner class HttpListenerManager :
            RegisteredToolManager<Piper.HttpListener, HttpHandler>(montoyaApi, configModel) {

        override fun getToolTypeName(): String = "HTTP Listener"

        override fun isModelItemEnabled(item: Piper.HttpListener): Boolean = item.common.enabled

        override fun registerWithBurp(
                modelItem: Piper.HttpListener,
                burpItem: HttpHandler
        ): Registration? {
            return montoyaApi.http().registerHttpHandler(burpItem)
        }

        override fun modelToBurp(modelItem: Piper.HttpListener): HttpHandler =
                object : HttpHandler {
                    override fun handleHttpRequestToBeSent(
                            requestToBeSent: HttpRequestToBeSent
                    ): RequestToBeSentAction {
                        if (modelItem.scope != Piper.HttpListenerScope.REQUEST) {
                            return RequestToBeSentAction.continueWith(requestToBeSent)
                        }

                        val toolSource = requestToBeSent.toolSource()
                        if (modelItem.tool != 0 && !isToolFlagMatch(modelItem.tool, toolSource)) {
                            return RequestToBeSentAction.continueWith(requestToBeSent)
                        }

                        try {
                            modelItem.common.pipeMessage(
                                    listOf(RequestResponse.REQUEST),
                                    MontoyaHttpRequestResponseAdapter(requestToBeSent),
                                    modelItem.ignoreOutput
                            )
                        } catch (e: Exception) {
                            montoyaApi
                                    .logging()
                                    .logToError("HTTP request processing failed: ${e.message}")
                        }

                        return RequestToBeSentAction.continueWith(requestToBeSent)
                    }

                    override fun handleHttpResponseReceived(
                            responseReceived: HttpResponseReceived
                    ): ResponseReceivedAction {
                        if (modelItem.scope != Piper.HttpListenerScope.RESPONSE) {
                            return ResponseReceivedAction.continueWith(responseReceived)
                        }

                        val toolSource = responseReceived.toolSource()
                        if (modelItem.tool != 0 && !isToolFlagMatch(modelItem.tool, toolSource)) {
                            return ResponseReceivedAction.continueWith(responseReceived)
                        }

                        try {
                            modelItem.common.pipeMessage(
                                    listOf(RequestResponse.RESPONSE),
                                    MontoyaHttpRequestResponseAdapter(responseReceived),
                                    modelItem.ignoreOutput
                            )
                        } catch (e: Exception) {
                            montoyaApi
                                    .logging()
                                    .logToError("HTTP response processing failed: ${e.message}")
                        }

                        return ResponseReceivedAction.continueWith(responseReceived)
                    }
                }
    }

    /** Manager for Intruder Payload Processor tools */
    private inner class IntruderPayloadProcessorManager :
            RegisteredToolManager<Piper.MinimalTool, PayloadProcessor>(montoyaApi, configModel) {

        override fun getToolTypeName(): String = "Intruder Payload Processor"

        override fun isModelItemEnabled(item: Piper.MinimalTool): Boolean = item.enabled

        override fun registerWithBurp(
                modelItem: Piper.MinimalTool,
                burpItem: PayloadProcessor
        ): Registration? {
            return montoyaApi.intruder().registerPayloadProcessor(burpItem)
        }

        override fun modelToBurp(modelItem: Piper.MinimalTool): PayloadProcessor =
                object : PayloadProcessor {
                    override fun processPayload(payloadData: PayloadData): PayloadProcessingResult {
                        return try {
                            val payloadBytes = payloadData.currentPayload().bytes
                            val (process, tempFiles) = modelItem.cmd.execute(payloadBytes)
                            val result = process.inputStream.readBytes()
                            tempFiles.forEach { it.delete() }

                            PayloadProcessingResult.usePayload(
                                    burp.api.montoya.core.ByteArray.byteArray(*result)
                            )
                        } catch (e: Exception) {
                            montoyaApi
                                    .logging()
                                    .logToError("Payload processing failed: ${e.message}")
                            PayloadProcessingResult.usePayload(payloadData.currentPayload())
                        }
                    }

                    override fun displayName(): String = modelItem.name
                }
    }

    /** Manager for Intruder Payload Generator tools */
    private inner class IntruderPayloadGeneratorManager :
            RegisteredToolManager<Piper.MinimalTool, PayloadGeneratorProvider>(
                    montoyaApi,
                    configModel
            ) {

        override fun getToolTypeName(): String = "Intruder Payload Generator"

        override fun isModelItemEnabled(item: Piper.MinimalTool): Boolean = item.enabled

        override fun registerWithBurp(
                modelItem: Piper.MinimalTool,
                burpItem: PayloadGeneratorProvider
        ): Registration? {
            return montoyaApi.intruder().registerPayloadGeneratorProvider(burpItem)
        }

        override fun modelToBurp(modelItem: Piper.MinimalTool): PayloadGeneratorProvider =
                object : PayloadGeneratorProvider {
                    override fun displayName(): String = modelItem.name

                    override fun providePayloadGenerator(
                            attackConfiguration: burp.api.montoya.intruder.AttackConfiguration
                    ): burp.api.montoya.intruder.PayloadGenerator {
                        return MontoyaPayloadGenerator(modelItem, utilities, montoyaApi)
                    }
                }

        // Commentators and Highlighters are handled via HTTP response processing
        // No separate manager initialization needed - handled in handleHttpResponseReceived()
    }

    /**
     * Main initialization method for Montoya API extensions. Replaces the legacy
     * registerExtenderCallbacks method.
     */
    override fun initialize(api: MontoyaApi) {
        this.montoyaApi = api
        this.utilities = api.utilities()

        // Initialize configuration
        configModel = ConfigModel(api)
        configModel.initializeFromDefaults()
        loadConfig()

        // Debug: Log configuration state after loading
        montoyaApi
                .logging()
                .logToOutput(
                        "DEBUG: Configuration loaded with ${configModel.config.messageViewerCount} message viewers"
                )
        configModel.config.messageViewerList.forEachIndexed { index, viewer ->
            montoyaApi
                    .logging()
                    .logToOutput(
                            "DEBUG: MessageViewer[$index]: name='${viewer.common.name}', enabled=${viewer.common.enabled}"
                    )
        }

        // Set extension name
        montoyaApi.extension().setName(NAME)

        // Register context menu provider
        montoyaApi
                .userInterface()
                .registerContextMenuItemsProvider(
                        PiperMenuItem.createProvider(configModel.config, montoyaApi) { event, tool
                            ->
                            executeToolFromContextMenu(event, tool)
                        }
                )

        // Initialize and register tool managers
        initializeManagers()

        // Setup UI tabs
        populateTabs(configModel.config)
        montoyaApi.userInterface().registerSuiteTab(NAME, tabs)

        // Register main HTTP handler
        montoyaApi.http().registerHttpHandler(this)
    }

    /** Main HTTP request handler for the extension */
    override fun handleHttpRequestToBeSent(
            requestToBeSent: HttpRequestToBeSent
    ): RequestToBeSentAction {
        // Main extension HTTP handler - currently just passes through
        return RequestToBeSentAction.continueWith(requestToBeSent)
    }

    /** Main HTTP response handler for the extension */
    override fun handleHttpResponseReceived(
            responseReceived: HttpResponseReceived
    ): ResponseReceivedAction {
        val toolSource = responseReceived.toolSource()
        if (!isProxyTool(toolSource)) {
            return ResponseReceivedAction.continueWith(responseReceived)
        }

        try {
            val messageDetails =
                    messagesToMap(
                            Collections.singleton(
                                    MontoyaHttpRequestResponseAdapter(responseReceived)
                            )
                    )

            // Apply commentators
            configModel.config.commentatorList
                    .filter { it.common.enabled && it.applyWithListener }
                    .forEach { cfgItem: Piper.Commentator ->
                        messageDetails.filterApplicable(cfgItem.common).forEach { (_, md) ->
                            performCommentator(cfgItem, md)
                        }
                    }

            // Apply highlighters
            configModel.config.highlighterList
                    .filter { it.common.enabled && it.applyWithListener }
                    .forEach { cfgItem: Piper.Highlighter ->
                        messageDetails.filterApplicable(cfgItem.common).forEach { (_, md) ->
                            performHighlighter(cfgItem, md)
                        }
                    }
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Error processing HTTP response: ${e.message}")
        }

        return ResponseReceivedAction.continueWith(responseReceived)
    }

    /** Execute a tool from context menu */
    private fun executeToolFromContextMenu(event: ContextMenuEvent, tool: Piper.MinimalTool) {
        try {
            val selectedRequests = event.selectedRequestResponses()
            if (selectedRequests.isNotEmpty()) {
                // Convert Montoya API messages to the format expected by the tool
                val messages =
                        selectedRequests.map { requestResponse ->
                            MontoyaHttpRequestResponseAdapter(requestResponse)
                        }

                // Execute the tool with the converted messages
                executeToolWithMessages(tool, messages)
            }
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError(
                            "Error executing tool '${tool.name}' from context menu: ${e.message}"
                    )
        }
    }

    /** Execute a tool with HTTP messages */
    private fun executeToolWithMessages(
            tool: Piper.MinimalTool,
            messages: List<MontoyaHttpRequestResponseAdapter>
    ) {
        try {
            messages.forEach { message ->
                tool.pipeMessage(listOf(RequestResponse.REQUEST), message)
            }
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Error executing tool '${tool.name}': ${e.message}")
        }
    }

    /** Populate the UI tabs */
    private fun populateTabs(config: Piper.Config) {
        tabs.removeAll()

        // Add configuration tab
        val configPanel = javax.swing.JPanel()
        tabs.addTab("Configuration", configPanel)

        // Add queue tab
        tabs.addTab("Queue", queue)

        montoyaApi.logging().logToOutput("Piper UI tabs initialized")
    }

    /** Initialize all tool managers and register tools with Burp */
    private fun initializeManagers() {
        try {
            // Debug: Log tools before manager initialization
            montoyaApi
                    .logging()
                    .logToOutput(
                            "DEBUG: Initializing managers with ${configModel.config.messageViewerCount} message viewers"
                    )

            // Initialize Message Viewer Manager
            val messageViewerManager = MessageViewerManager()
            val messageViewers = configModel.config.messageViewerList
            montoyaApi
                    .logging()
                    .logToOutput("DEBUG: Passing ${messageViewers.size} message viewers to manager")
            messageViewerManager.initialize(messageViewers)

            // Initialize Macro Manager
            val macroManager = MacroManager()
            macroManager.initialize(configModel.config.macroList)

            // Initialize Intruder Payload Processor Manager
            val payloadProcessorManager = IntruderPayloadProcessorManager()
            payloadProcessorManager.initialize(configModel.config.intruderPayloadProcessorList)

            // Initialize Intruder Payload Generator Manager
            val payloadGeneratorManager = IntruderPayloadGeneratorManager()
            payloadGeneratorManager.initialize(configModel.config.intruderPayloadGeneratorList)

            // Initialize HTTP Listener Manager
            val httpListenerManager = HttpListenerManager()
            httpListenerManager.initialize(configModel.config.httpListenerList)

            // Commentators and Highlighters are handled directly in HTTP response processing
            // via performCommentator and performHighlighter functions - no separate managers needed
            montoyaApi
                    .logging()
                    .logToOutput("Commentators and Highlighters configured for HTTP processing")

            montoyaApi.logging().logToOutput("All tool managers initialized successfully")
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Failed to initialize managers: ${e.message}")
        }
    }

    /** Extension method for MinimalTool to pipe messages */
    private fun Piper.MinimalTool.pipeMessage(
            rrList: List<RequestResponse>,
            messageInfo: MontoyaHttpRequestResponseAdapter,
            ignoreOutput: Boolean = false
    ) {
        require(rrList.isNotEmpty())

        try {
            val body =
                    rrList.map { rr ->
                        val bytes = rr.getMessage(messageInfo)!!
                        val headers = rr.getHeaders(bytes, utilities)
                        val bo = if (this.cmd.passHeaders) 0 else rr.getBodyOffset(bytes, utilities)
                        val body =
                                if (this.cmd.passHeaders) bytes
                                else {
                                    if (bo < bytes.size - 1) {
                                        bytes.copyOfRange(bo, bytes.size)
                                    } else null
                                }
                        body to headers
                    }

            val (lastBody, headers) = body.last()
            if (lastBody == null) return

            if (this.hasFilter() &&
                            !this.filter.matches(
                                    MessageInfo(
                                            lastBody,
                                            utilities.bytesToString(lastBody).toString(),
                                            headers,
                                            try {
                                                getUrlFromMessage(messageInfo)
                                            } catch (_: Exception) {
                                                null
                                            }
                                    ),
                                    utilities,
                                    montoyaApi
                            )
            )
                    return

            val input = body.mapNotNull(Pair<ByteArray?, List<String>>::first).toTypedArray()
            val replacement = getStdoutWithErrorHandling(this.cmd.execute(*input), this)

            if (!ignoreOutput) {
                rrList.last()
                        .setMessage(
                                messageInfo,
                                if (this.cmd.passHeaders) replacement
                                else buildHttpMessage(headers, replacement)
                        )
            }
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError("Error piping message for tool '${this.name}': ${e.message}")
        }
    }

    /** Execute command and handle stdout/stderr */
    private fun getStdoutWithErrorHandling(
            executionResult: Pair<Process, List<File>>,
            tool: Piper.MinimalTool
    ): ByteArray =
            executionResult.processOutput { process ->
                if (configModel.developer) {
                    val stderr = process.errorStream.readBytes()
                    if (stderr.isNotEmpty()) {
                        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        val ts = LocalDateTime.now().format(formatter)
                        montoyaApi
                                .logging()
                                .logToError(
                                        "${tool.name} called ${tool.cmd.commandLine} at $ts and stderr was not empty:"
                                )
                        montoyaApi.logging().logToError(String(stderr))
                    }
                }
                process.inputStream.readBytes()
            }

    /** Setup UI tabs */
    private fun populateTabs(cfg: ConfigModel, parent: Component?) {
        val switchToCommentator = { tabs.selectedIndex = 4 }

        tabs.addTab(
                "Message viewers",
                MessageViewerListEditor(
                        cfg.messageViewersModel,
                        parent,
                        cfg.commentatorsModel,
                        switchToCommentator
                )
        )
        tabs.addTab(
                "Context menu items",
                MinimalToolListEditor(
                        cfg.userActionToolsModel,
                        parent,
                        ::MenuItemDialog,
                        Piper.UserActionTool::getDefaultInstance,
                        UserActionToolFromMap,
                        Piper.UserActionTool::toMap
                )
        )
        tabs.addTab(
                "Macros",
                MinimalToolListEditor(
                        cfg.macrosModel,
                        parent,
                        ::MacroDialog,
                        Piper.MinimalTool::getDefaultInstance,
                        ::minimalToolFromMap,
                        Piper.MinimalTool::toMap
                )
        )
        tabs.addTab(
                "HTTP listeners",
                MinimalToolListEditor(
                        cfg.httpListenersModel,
                        parent,
                        ::HttpListenerDialog,
                        Piper.HttpListener::getDefaultInstance,
                        ::httpListenerFromMap,
                        Piper.HttpListener::toMap
                )
        )
        tabs.addTab(
                "Commentators",
                MinimalToolListEditor(
                        cfg.commentatorsModel,
                        parent,
                        ::CommentatorDialog,
                        Piper.Commentator::getDefaultInstance,
                        ::commentatorFromMap,
                        Piper.Commentator::toMap
                )
        )
        tabs.addTab(
                "Intruder payload processors",
                MinimalToolListEditor(
                        cfg.intruderPayloadProcessorsModel,
                        parent,
                        ::IntruderPayloadProcessorDialog,
                        Piper.MinimalTool::getDefaultInstance,
                        ::minimalToolFromMap,
                        Piper.MinimalTool::toMap
                )
        )
        tabs.addTab(
                "Intruder payload generators",
                MinimalToolListEditor(
                        cfg.intruderPayloadGeneratorsModel,
                        parent,
                        ::IntruderPayloadGeneratorDialog,
                        Piper.MinimalTool::getDefaultInstance,
                        ::minimalToolFromMap,
                        Piper.MinimalTool::toMap
                )
        )
        tabs.addTab(
                "Highlighters",
                MinimalToolListEditor(
                        cfg.highlightersModel,
                        parent,
                        ::HighlighterDialog,
                        Piper.Highlighter::getDefaultInstance,
                        ::highlighterFromMap,
                        Piper.Highlighter::toMap
                )
        )
        tabs.addTab("Queue", queue)
        tabs.addTab("Load/Save configuration", createLoadSaveUI(cfg, parent))
        tabs.addTab("Developer", createDeveloperUI(cfg))
    }

    /** Create developer UI component */
    private fun createDeveloperUI(cfg: ConfigModel): Component =
            JCheckBox("show user interface elements suited for developers").apply {
                isSelected = cfg.developer
                cfg.addPropertyChangeListener { isSelected = cfg.developer }
                addChangeListener { cfg.developer = isSelected }
            }

    /** Create load/save configuration UI */
    private fun createLoadSaveUI(cfg: ConfigModel, parent: Component?): Component {
        val panel = JPanel()
        panel.add(JLabel("Load/Save functionality placeholder"))
        return panel
    }

    /** Helper method to check if tool flag matches tool source */
    private fun isToolFlagMatch(
            toolFlag: Int,
            toolSource: burp.api.montoya.core.ToolSource
    ): Boolean {
        return when (toolSource.toolType()) {
            burp.api.montoya.core.ToolType.PROXY -> (toolFlag and 1) != 0
            burp.api.montoya.core.ToolType.TARGET -> (toolFlag and 2) != 0
            burp.api.montoya.core.ToolType.SCANNER -> (toolFlag and 4) != 0
            burp.api.montoya.core.ToolType.INTRUDER -> (toolFlag and 8) != 0
            burp.api.montoya.core.ToolType.REPEATER -> (toolFlag and 16) != 0
            burp.api.montoya.core.ToolType.SEQUENCER -> (toolFlag and 32) != 0
            burp.api.montoya.core.ToolType.DECODER -> (toolFlag and 64) != 0
            burp.api.montoya.core.ToolType.COMPARER -> (toolFlag and 128) != 0
            burp.api.montoya.core.ToolType.EXTENSIONS -> (toolFlag and 256) != 0
            else -> false
        }
    }

    private fun isProxyTool(toolSource: burp.api.montoya.core.ToolSource): Boolean {
        return toolSource.toolType() == burp.api.montoya.core.ToolType.PROXY
    }

    private fun getUrlFromMessage(messageInfo: MontoyaHttpRequestResponseAdapter): URL? {
        return try {
            messageInfo.request?.httpService()?.let { service ->
                URL(
                        "${if (service.secure()) "https" else "http"}://${service.host()}:${service.port()}/"
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildHttpMessage(headers: List<String>, body: ByteArray): ByteArray {
        val headerString = headers.joinToString("\r\n") + "\r\n\r\n"
        return headerString.toByteArray() + body
    }

    /** Load configuration from Burp's persistence */
    private fun loadConfig() {
        try {
            val configData =
                    montoyaApi.persistence().extensionData().getString(EXTENSION_SETTINGS_KEY)
            if (configData != null) {
                val loadedConfig = parseConfig(configData)
                configModel.updateConfig(loadedConfig)
                montoyaApi.logging().logToOutput("Configuration loaded from persistence")
            } else {
                montoyaApi.logging().logToOutput("No saved configuration found, using defaults")
            }
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Error loading config: ${e.message}")
            montoyaApi.logging().logToOutput("Falling back to default configuration")
        }
    }

    /** Save configuration to Burp's persistence */
    private fun saveConfig() {
        try {
            val configBytes = configModel.config.toByteArray()
            val configString = String(configBytes)
            montoyaApi.persistence().extensionData().setString(EXTENSION_SETTINGS_KEY, configString)
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Failed to save config: ${e.message}")
        }
    }

    /** Convert messages to MessageDetails map */
    private fun messagesToMap(
            messages: Collection<MontoyaHttpRequestResponseAdapter>
    ): Map<MontoyaHttpRequestResponseAdapter, MessageDetails> {
        return messages.associateWith { msg ->
            MessageDetails(msg.request?.toString() ?: "", msg.response?.toString() ?: "")
        }
    }

    /** Apply commentator to message */
    private fun performCommentator(commentator: Piper.Commentator, messageDetails: MessageDetails) {
        // Implementation for commentator functionality
        // This would be adapted from the legacy implementation
    }

    /** Apply highlighter to message */
    private fun performHighlighter(highlighter: Piper.Highlighter, messageDetails: MessageDetails) {
        // Implementation for highlighter functionality
        // This would be adapted from the legacy implementation
    }

    /** Filter applicable messages for a tool */
    private fun Map<MontoyaHttpRequestResponseAdapter, MessageDetails>.filterApplicable(
            tool: Piper.MinimalTool
    ): Map<MontoyaHttpRequestResponseAdapter, MessageDetails> {
        return this.filter { (adapter, details) ->
            try {
                !tool.hasFilter() ||
                        tool.filter.matches(
                                MessageInfo(
                                        details.requestBody.toByteArray(),
                                        details.requestBody,
                                        emptyList(),
                                        getUrlFromMessage(adapter)
                                ),
                                utilities,
                                montoyaApi
                        )
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Execute a commentator tool on a message */
    private fun performCommentator(
            commentator: Piper.Commentator,
            messageDetails: MontoyaHttpRequestResponseAdapter
    ) {
        try {
            val tool = commentator.common
            if (tool.hasFilter()) {
                val requestBytes = messageDetails.getRequest() ?: return
                val messageInfo =
                        MessageInfo(
                                requestBytes,
                                utilities.bytesToString(requestBytes).toString(),
                                emptyList(), // headers - simplified for now
                                try {
                                    getUrlFromMessage(messageDetails)
                                } catch (_: Exception) {
                                    null
                                }
                        )
                if (!tool.filter.matches(messageInfo, utilities, montoyaApi)) {
                    return
                }
            }

            // Execute the commentator tool
            val requestBytes = messageDetails.getRequest() ?: return
            val (process, tempFiles) = tool.cmd.execute(requestBytes)

            // Apply comment to the message
            montoyaApi.logging().logToOutput("Commentator '${tool.name}' executed successfully")

            // Clean up temp files
            tempFiles.forEach { it.delete() }
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError(
                            "Error executing commentator tool '${commentator.common.name}': ${e.message}"
                    )
        }
    }

    /** Execute a highlighter tool on a message */
    private fun performHighlighter(
            highlighter: Piper.Highlighter,
            messageDetails: MontoyaHttpRequestResponseAdapter
    ) {
        try {
            val tool = highlighter.common
            if (tool.hasFilter()) {
                val requestBytes = messageDetails.getRequest() ?: return
                val messageInfo =
                        MessageInfo(
                                requestBytes,
                                utilities.bytesToString(requestBytes).toString(),
                                emptyList(), // headers - simplified for now
                                try {
                                    getUrlFromMessage(messageDetails)
                                } catch (_: Exception) {
                                    null
                                }
                        )
                if (!tool.filter.matches(messageInfo, utilities, montoyaApi)) {
                    return
                }
            }

            // Execute the highlighter tool
            val requestBytes = messageDetails.getRequest() ?: return
            val (process, tempFiles) = tool.cmd.execute(requestBytes)

            // Apply highlighting to the message
            montoyaApi
                    .logging()
                    .logToOutput(
                            "Highlighter '${tool.name}' executed with color: ${highlighter.color}"
                    )

            // Clean up temp files
            tempFiles.forEach { it.delete() }
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError(
                            "Error executing highlighter tool '${highlighter.common.name}': ${e.message}"
                    )
        }
    }

    /** Data class for message details */
    data class MessageDetails(val requestBody: String, val responseBody: String)
}

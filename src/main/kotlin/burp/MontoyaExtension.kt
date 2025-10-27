package burp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray
import burp.api.montoya.core.Registration
import burp.api.montoya.intruder.AttackConfiguration
import burp.api.montoya.intruder.GeneratedPayload
import burp.api.montoya.intruder.PayloadData
import burp.api.montoya.intruder.PayloadGenerator
import burp.api.montoya.intruder.PayloadGeneratorProvider
import burp.api.montoya.intruder.IntruderInsertionPoint
import burp.api.montoya.intruder.PayloadProcessingResult
import burp.api.montoya.intruder.PayloadProcessor
import burp.api.montoya.ui.Selection
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse
import burp.api.montoya.ui.editor.extension.EditorCreationContext
import burp.api.montoya.ui.editor.extension.ExtensionProvidedEditor
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider
import burp.api.montoya.utilities.ByteUtils
import java.awt.Component
import java.net.MalformedURLException
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.URL
import java.lang.reflect.InvocationTargetException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.text.Charsets
import burp.ui.AnsiTextPane
import javax.swing.DefaultListModel
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JTabbedPane
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

private fun parseUrlSafely(value: String?): URL? = value?.let {
    try {
        URL(it)
    } catch (_: MalformedURLException) {
        null
    }
}

class MontoyaExtension : BurpExtension {
    private lateinit var api: MontoyaApi
    private lateinit var context: PiperContext
    private lateinit var configModel: ConfigModel

    private lateinit var processorManager: MontoyaRegisteredToolManager<Piper.MinimalTool>
    private lateinit var generatorManager: MontoyaRegisteredToolManager<Piper.MinimalTool>
    private lateinit var messageViewerManager: MontoyaMessageViewerManager
    @Suppress("unused")
    private var contextMenuRegistration: Registration? = null

    private val emptyPayloadGenerator = object : PayloadGenerator {
        override fun generatePayloadFor(insertionPoint: IntruderInsertionPoint?): GeneratedPayload =
            GeneratedPayload.end()
    }

    private val saveOnChangeListener = object : ListDataListener {
        override fun contentsChanged(e: ListDataEvent) = saveConfig()
        override fun intervalAdded(e: ListDataEvent) = saveConfig()
        override fun intervalRemoved(e: ListDataEvent) = saveConfig()
    }

    private lateinit var suiteTabs: JTabbedPane
    private var suiteTabRegistration: Registration? = null

    override fun initialize(api: MontoyaApi) {
        this.api = api
        api.extension().setName(NAME)
        api.logging().logToOutput("Initializing Piper extension")

        context = object : PiperContext {
            private val byteUtils: ByteUtils = api.utilities().byteUtils()

            override fun bytesToString(data: kotlin.ByteArray): String = byteUtils.convertToString(data)
            override fun isInScope(url: URL): Boolean = api.scope().isInScope(url.toString())
        }

        configModel = ConfigModel(loadConfig())

        listOf(
            configModel.menuItemsModel,
            configModel.commentatorsModel,
            configModel.highlightersModel,
            configModel.messageViewersModel,
            configModel.macrosModel,
            configModel.httpListenersModel,
        ).forEach { it.addListDataListener(saveOnChangeListener) }

        processorManager = MontoyaRegisteredToolManager(
            configModel.intruderPayloadProcessorsModel,
            { it.enabled },
            { registerPayloadProcessor(it) }
        )

        generatorManager = MontoyaRegisteredToolManager(
            configModel.intruderPayloadGeneratorsModel,
            { it.enabled },
            { registerPayloadGenerator(it) }
        )

        messageViewerManager = MontoyaMessageViewerManager(configModel.messageViewersModel)
        contextMenuRegistration = api.userInterface().registerContextMenuItemsProvider(PiperContextMenuItemsProvider())

        suiteTabs = JTabbedPane()
        populatePiperTabs(suiteTabs, configModel, parent = null)
        suiteTabRegistration = api.userInterface().registerSuiteTab(NAME, suiteTabs)
        api.logging().logToOutput("Piper suite tab registered")

        configModel.addPropertyChangeListener { saveConfig() }
    }

    private inner class PiperContextMenuItemsProvider : ContextMenuItemsProvider {
        override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
            val editorContext = event.messageEditorRequestResponse().orElse(null)
            val selected = event.selectedRequestResponses().toMutableList()
            if (editorContext != null) {
                val editorMessage = editorContext.requestResponse()
                if (editorMessage != null && !selected.contains(editorMessage)) {
                    selected += editorMessage
                }
            }
            if (selected.isEmpty()) {
                return emptyList()
            }

            val topLevel = JMenu(NAME)
            val hasItems = generateContextMenu(selected, topLevel::add, editorContext)
            if (!hasItems) {
                return emptyList()
            }
            return listOf(topLevel)
        }
    }

    private fun registerPayloadProcessor(tool: Piper.MinimalTool): Registration? {
        if (!tool.enabled) return null

        val processor = object : PayloadProcessor {
            override fun displayName(): String = tool.name

            override fun processPayload(payloadData: PayloadData): PayloadProcessingResult {
                val currentBytes = payloadData.currentPayload().getBytes()
                if (tool.hasFilter()) {
                    val messageInfo = MessageInfo(
                        currentBytes,
                        context.bytesToString(currentBytes),
                        headers = null,
                        url = null
                    )
                    if (!tool.filter.matches(messageInfo, context)) {
                        return PayloadProcessingResult.skipPayload()
                    }
                }

                val processed = getStdoutWithErrorHandling(tool.cmd.execute(currentBytes), tool)
                return PayloadProcessingResult.usePayload(montoyaBytes(processed))
            }
        }

        return api.intruder().registerPayloadProcessor(processor)
    }

    private fun registerPayloadGenerator(tool: Piper.MinimalTool): Registration? {
        if (!tool.enabled) return null

        val provider = object : PayloadGeneratorProvider {
            override fun displayName(): String = tool.name

            override fun providePayloadGenerator(attackConfiguration: AttackConfiguration?): PayloadGenerator {
                val parameterValues = promptParameters(tool)
                if (parameterValues == null) {
                    api.logging().logToOutput("Piper: Payload generator \"${tool.name}\" was cancelled by the user.")
                    return emptyPayloadGenerator
                }
                val resolvedParameters = try {
                    tool.cmd.resolveParameterValues(parameterValues)
                } catch (e: IllegalArgumentException) {
                    api.logging().logToError("Piper: ${e.message}")
                    return emptyPayloadGenerator
                }
                return PiperPayloadGenerator(tool, resolvedParameters)
            }
        }

        return api.intruder().registerPayloadGeneratorProvider(provider)
    }

    private fun promptParameters(tool: Piper.MinimalTool): Map<String, String>? {
        val result = AtomicReference<Map<String, String>?>(null)
        val parent = if (::suiteTabs.isInitialized) suiteTabs else null
        try {
            SwingUtilities.invokeAndWait {
                result.set(promptForCommandParameters(parent, tool.name, tool.cmd))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: InvocationTargetException) {
            api.logging().logToError(
                "Piper: Failed to collect parameters for ${tool.name}: ${e.cause?.message ?: e.message}",
            )
            return emptyMap()
        }
        return result.get()
    }

    private data class ViewerRegistrations(
        val enabledFlag: AtomicBoolean,
        val request: Registration?,
        val response: Registration?,
    ) {
        fun deregister() {
            enabledFlag.set(false)
            request?.deregister()
            response?.deregister()
        }
    }

    private abstract inner class ListModelRegistrationManager<T, R>(
        private val model: DefaultListModel<T>,
    ) : ListDataListener {

        private val registrations: MutableList<R?> = mutableListOf()
        private var initialized = false

        protected fun initialize() {
            if (initialized) {
                return
            }
            initialized = true
            refreshRegistrations()
            model.addListDataListener(this)
        }

        protected abstract fun registerItem(item: T): R?
        protected abstract fun deregisterItem(registration: R)

        protected open fun afterModelChange() {
            saveConfig()
        }

        override fun contentsChanged(e: ListDataEvent) {
            if (!initialized) return
            refreshAll()
        }

        override fun intervalAdded(e: ListDataEvent) {
            if (!initialized) return
            refreshAll()
        }

        override fun intervalRemoved(e: ListDataEvent) {
            if (!initialized) return
            refreshAll()
        }

        private fun refreshRegistrations() {
            registrations.forEach { registration ->
                registration?.let { deregisterItem(it) }
            }
            registrations.clear()
            for (index in 0 until model.size()) {
                registrations.add(registerItem(model[index]))
            }
        }

        private fun refreshAll() {
            refreshRegistrations()
            afterModelChange()
        }
    }

    private inner class MontoyaMessageViewerManager(
        model: DefaultListModel<Piper.MessageViewer>,
    ) : ListModelRegistrationManager<Piper.MessageViewer, ViewerRegistrations>(model) {

        init {
            initialize()
        }

        override fun registerItem(item: Piper.MessageViewer): ViewerRegistrations? = registerViewer(item)

        override fun deregisterItem(registration: ViewerRegistrations) {
            registration.deregister()
        }

        private fun registerViewer(viewer: Piper.MessageViewer): ViewerRegistrations? {
            if (!viewer.common.enabled) {
                return null
            }

            val enabledFlag = AtomicBoolean(true)
            val requestRegistration = if (viewer.common.scope != Piper.MinimalTool.Scope.RESPONSE_ONLY) {
                api.userInterface().registerHttpRequestEditorProvider(
                    MessageViewerRequestProvider(viewer, enabledFlag),
                )
            } else {
                null
            }
            val responseRegistration = if (viewer.common.scope != Piper.MinimalTool.Scope.REQUEST_ONLY) {
                api.userInterface().registerHttpResponseEditorProvider(
                    MessageViewerResponseProvider(viewer, enabledFlag),
                )
            } else {
                null
            }
            return ViewerRegistrations(enabledFlag, requestRegistration, responseRegistration)
        }
    }

    private inner class MessageViewerRequestProvider(
        private val viewer: Piper.MessageViewer,
        private val enabledFlag: AtomicBoolean,
    ) : HttpRequestEditorProvider {
        override fun provideHttpRequestEditor(creationContext: EditorCreationContext?): ExtensionProvidedHttpRequestEditor {
            return RequestMessageViewerEditor(viewer, enabledFlag)
        }
    }

    private inner class MessageViewerResponseProvider(
        private val viewer: Piper.MessageViewer,
        private val enabledFlag: AtomicBoolean,
    ) : HttpResponseEditorProvider {
        override fun provideHttpResponseEditor(creationContext: EditorCreationContext?): ExtensionProvidedHttpResponseEditor {
            return ResponseMessageViewerEditor(viewer, enabledFlag)
        }
    }

    private enum class MessageDirection(val isRequest: Boolean, val displayName: String) {
        REQUEST(true, "request"),
        RESPONSE(false, "response");

        fun messageBytes(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): kotlin.ByteArray? = when (this) {
            REQUEST -> requestResponse.request().toByteArray().getBytes()
            RESPONSE -> if (requestResponse.hasResponse()) requestResponse.response().toByteArray().getBytes() else null
        }

        fun bodyOffset(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): Int? = when (this) {
            REQUEST -> requestResponse.request().bodyOffset()
            RESPONSE -> if (requestResponse.hasResponse()) requestResponse.response().bodyOffset() else null
        }

        fun headers(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): List<String>? = when (this) {
            REQUEST -> requestResponse.request().headers().map { it.toString() }
            RESPONSE -> if (requestResponse.hasResponse()) requestResponse.response().headers().map { it.toString() } else null
        }

        fun url(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): URL? = when (this) {
            REQUEST -> parseUrlSafely(requestResponse.request().url())
            RESPONSE -> parseUrlSafely(requestResponse.url())
        }
    }

    private enum class Region(val includeHeaders: Boolean) {
        WHOLE_MESSAGE(true),
        HTTP_BODY(false),
        SELECTION(false);
    }

    private data class MessageSource(val direction: MessageDirection, val region: Region) : Comparable<MessageSource> {
        override fun compareTo(other: MessageSource): Int =
            compareValuesBy(this, other, MessageSource::direction, MessageSource::region)
    }

    private fun generateContextMenu(
        messages: List<burp.api.montoya.http.message.HttpRequestResponse>,
        add: (Component) -> Component,
        editorContext: MessageEditorHttpRequestResponse?,
    ): Boolean {
        if (messages.isEmpty()) {
            return false
        }

        val details = messagesToMap(messages, editorContext)
        if (details.isEmpty()) {
            return false
        }

        val msize = messages.size
        val plural = if (msize == 1) "" else "s"
        val selectionMenuItems = mutableListOf<JMenuItem>()
        val categoryMenus = EnumMap<MessageDirection, JMenu>(MessageDirection::class.java)

        fun createSubMenu(source: MessageSource): JMenu =
            JMenu("Process $msize ${source.direction.displayName}$plural")

        fun EnumMap<MessageDirection, JMenu>.addMenuItemIfApplicable(
            menuItem: Piper.UserActionTool,
            viewer: Piper.MessageViewer?,
            source: MessageSource,
            info: List<MessageInfo>,
        ) {
            val (first, second) = if (viewer == null) {
                menuItem.common to null
            } else {
                viewer.common to menuItem.common
            }
            if (!isToolApplicable(first, source, info, MessageInfoMatchStrategy.ALL)) {
                return
            }
            val item = createMenuItem(first, second) {
                performMenuAction(menuItem, info, viewer)
            }
            if (source.region == Region.SELECTION) {
                selectionMenuItems.add(item)
            } else {
                this.getOrPut(source.direction) { createSubMenu(source) }.add(item)
            }
        }

        for (tool in configModel.enabledMenuItems) {
            if ((tool.maxInputs != 0 && tool.maxInputs < msize) || tool.minInputs > msize) {
                continue
            }
            for ((source, info) in details) {
                categoryMenus.addMenuItemIfApplicable(tool, null, source, info)
                if (!tool.common.cmd.passHeaders && !tool.common.hasFilter() && !tool.avoidPipe) {
                    configModel.enabledMessageViewers.forEach { viewer ->
                        categoryMenus.addMenuItemIfApplicable(tool, viewer, source, info)
                    }
                }
            }
        }

        categoryMenus.values.forEach { add(it) }
        if (selectionMenuItems.isNotEmpty()) {
            add(JMenu("Process selection").apply { selectionMenuItems.forEach { add(it) } })
        }
        return categoryMenus.isNotEmpty() || selectionMenuItems.isNotEmpty()
    }

    private fun messagesToMap(
        messages: List<burp.api.montoya.http.message.HttpRequestResponse>,
        editorContext: MessageEditorHttpRequestResponse?,
    ): Map<MessageSource, List<MessageInfo>> {
        val result = TreeMap<MessageSource, List<MessageInfo>>()
        for (direction in MessageDirection.values()) {
            val fullMessages = mutableListOf<MessageInfo>()
            val bodies = mutableListOf<MessageInfo>()
            messages.forEach { requestResponse ->
                val bytes = direction.messageBytes(requestResponse)
                if (bytes != null) {
                    val headers = direction.headers(requestResponse)
                    val url = direction.url(requestResponse)
                    fullMessages.add(MessageInfo(bytes, context.bytesToString(bytes), headers, url))
                    val offset = direction.bodyOffset(requestResponse)
                    if (offset != null && offset < bytes.size) {
                        val body = bytes.copyOfRange(offset, bytes.size)
                        if (body.isNotEmpty()) {
                            bodies.add(MessageInfo(body, context.bytesToString(body), headers, url))
                        }
                    }
                }
            }
            if (fullMessages.isNotEmpty()) {
                result[MessageSource(direction, Region.WHOLE_MESSAGE)] = fullMessages
            }
            if (bodies.isNotEmpty()) {
                result[MessageSource(direction, Region.HTTP_BODY)] = bodies
            }
        }

        if (editorContext != null) {
            val range = editorContext.selectionOffsets().orElse(null)
            if (range != null && range.startIndexInclusive() < range.endIndexExclusive()) {
                val direction = when (editorContext.selectionContext()) {
                    MessageEditorHttpRequestResponse.SelectionContext.REQUEST -> MessageDirection.REQUEST
                    MessageEditorHttpRequestResponse.SelectionContext.RESPONSE -> MessageDirection.RESPONSE
                    else -> null
                }
                if (direction != null) {
                    val bytes = direction.messageBytes(editorContext.requestResponse())
                    if (bytes != null) {
                        val start = range.startIndexInclusive().coerceAtLeast(0)
                        val end = range.endIndexExclusive().coerceAtMost(bytes.size)
                        if (start < end) {
                            val selection = bytes.copyOfRange(start, end)
                            val headers = direction.headers(editorContext.requestResponse())
                            val url = direction.url(editorContext.requestResponse())
                            result[MessageSource(direction, Region.SELECTION)] = listOf(
                                MessageInfo(selection, context.bytesToString(selection), headers, url),
                            )
                        }
                    }
                }
            }
        }

        return result
    }

    private fun createMenuItem(
        tool: Piper.MinimalTool,
        pipe: Piper.MinimalTool?,
        action: () -> Unit,
    ): JMenuItem = JMenuItem(
        tool.name + if (pipe == null) "" else " | ${pipe.name}",
    ).apply { addActionListener { action() } }

    private fun isToolApplicable(
        tool: Piper.MinimalTool,
        source: MessageSource,
        info: List<MessageInfo>,
        strategy: MessageInfoMatchStrategy,
    ): Boolean =
        tool.cmd.passHeaders == source.region.includeHeaders &&
                tool.isInToolScope(source.direction.isRequest) &&
                tool.canProcess(info, strategy, context)

    private fun performMenuAction(
        tool: Piper.UserActionTool,
        messages: List<MessageInfo>,
        viewer: Piper.MessageViewer?,
    ) {
        thread(start = true) {
            val (input, tools) = if (viewer == null) {
                messages.map(MessageInfo::asContentExtensionPair) to listOf(tool.common)
            } else {
                messages.map { message ->
                    val processed = viewer.common.cmd.execute(message.asContentExtensionPair).processOutput { process ->
                        process.inputStream.use { it.readBytes() }
                    }
                    processed to null
                } to listOf(viewer.common, tool.common)
            }

            tool.common.cmd.execute(*input.toTypedArray()).processOutput { process ->
                if (!tool.hasGUI) {
                    handleGUI(process, tools)
                }
            }
        }
    }

    private fun handleGUI(process: Process, tools: List<Piper.MinimalTool>) {
        val textPane = AnsiTextPane()
        val scrollPane = JScrollPane(textPane).apply {
            val backgroundColor = textPane.background
            background = backgroundColor
            viewport.background = backgroundColor
        }

        JFrame().apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            add(scrollPane)
            setSize(675, 300)
            title = tools.joinToString(separator = " | ", prefix = "$NAME - ", transform = Piper.MinimalTool::getName)
            isVisible = true
        }

        val streams = listOf(process.inputStream, process.errorStream)
        streams.forEach { stream ->
            thread(start = true) {
                stream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read == -1) break
                        textPane.appendAnsi(String(buffer, 0, read))
                    }
                }
            }
        }
    }

    private abstract inner class BaseMessageViewerEditor(
        protected val viewer: Piper.MessageViewer,
        private val enabledFlag: AtomicBoolean,
        private val handlesRequest: Boolean,
    ) : ExtensionProvidedEditor {
        private var current: burp.api.montoya.http.message.HttpRequestResponse? = null

        override fun caption(): String = viewer.common.name
        override fun isModified(): Boolean = false
        override fun uiComponent(): Component = component()

        override fun selectedData(): Selection? {
            val bytes = selectedBytes() ?: return null
            if (bytes.isEmpty()) return null
            return Selection.selection(this@MontoyaExtension.montoyaBytes(bytes))
        }

        override fun isEnabledFor(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): Boolean {
            if (!enabledFlag.get() || !viewer.common.isInToolScope(handlesRequest)) {
                return false
            }

            val messageBytes = messageBytes(requestResponse) ?: return false
            val payload = buildPayload(messageBytes, requestResponse) ?: return false
            if (payload.isEmpty()) {
                return false
            }

            if (!viewer.common.hasFilter()) {
                val cmd = viewer.common.cmd
                return !cmd.hasFilter || cmd.matches(payload, context)
            }

            val info = MessageInfo(
                payload,
                context.bytesToString(payload),
                headers(requestResponse),
                url(requestResponse),
            )
            return viewer.common.filter.matches(info, context)
        }

        override fun setRequestResponse(requestResponse: burp.api.montoya.http.message.HttpRequestResponse) {
            current = requestResponse
            val messageBytes = messageBytes(requestResponse)
            val payload = if (messageBytes == null) null else buildPayload(messageBytes, requestResponse)
            if (payload == null) {
                resetUi()
                return
            }

            thread(start = true) {
                try {
                    viewer.common.cmd.execute(payload).processOutput { process ->
                        processOutput(process)
                    }
                } catch (e: IOException) {
                    handleExecutionFailure(e)
                }
            }
        }

        protected fun currentMessage(): burp.api.montoya.http.message.HttpRequestResponse? = current

        protected fun logError(message: String) {
            api.logging().logToError(message)
        }

        protected fun bytesToString(bytes: kotlin.ByteArray): String =
            api.utilities().byteUtils().convertToString(bytes)

        protected fun parseUrl(value: String?): URL? =
            value?.let { runCatching { URL(it) }.getOrNull() }

        private fun buildPayload(
            content: kotlin.ByteArray,
            requestResponse: burp.api.montoya.http.message.HttpRequestResponse,
        ): kotlin.ByteArray? {
            if (viewer.common.cmd.passHeaders) {
                return content
            }
            val offset = bodyOffset(requestResponse) ?: return null
            if (offset >= content.size) {
                return null
            }
            return content.copyOfRange(offset, content.size)
        }

        protected abstract fun component(): Component
        protected abstract fun messageBytes(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): kotlin.ByteArray?
        protected abstract fun bodyOffset(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): Int?
        protected abstract fun headers(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): List<String>?
        protected abstract fun url(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): URL?
        protected abstract fun processOutput(process: Process)
        protected abstract fun handleExecutionFailure(e: IOException)
        protected abstract fun resetUi()
        protected abstract fun selectedBytes(): kotlin.ByteArray?
    }

    private inner class RequestMessageViewerEditor(
        viewer: Piper.MessageViewer,
        enabledFlag: AtomicBoolean,
    ) : BaseMessageViewerEditor(viewer, enabledFlag, true), ExtensionProvidedHttpRequestEditor {
        private val terminalView = TerminalView()

        override fun getRequest(): burp.api.montoya.http.message.requests.HttpRequest {
            return currentMessage()?.request() ?: burp.api.montoya.http.message.requests.HttpRequest.httpRequest()
        }

        override fun component(): Component = terminalView.component()

        override fun messageBytes(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): kotlin.ByteArray? {
            return requestResponse.request()?.toByteArray()?.getBytes()
        }

        override fun bodyOffset(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): Int? =
            requestResponse.request()?.bodyOffset()

        override fun headers(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): List<String>? =
            requestResponse.request()?.headers()?.map { it.toString() }

        override fun url(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): URL? =
            parseUrl(requestResponse.request()?.url())

        override fun processOutput(process: Process) = terminalView.processOutput(process)

        override fun handleExecutionFailure(e: IOException) {
            val message = "Failed to execute ${viewer.common.cmd.commandLine}: ${e.message}"
            terminalView.showMessage(message)
            logError(message)
        }

        override fun resetUi() = terminalView.clear()

        override fun selectedBytes(): kotlin.ByteArray? = terminalView.selectedBytes()
    }

    private inner class ResponseMessageViewerEditor(
        viewer: Piper.MessageViewer,
        enabledFlag: AtomicBoolean,
    ) : BaseMessageViewerEditor(viewer, enabledFlag, false), ExtensionProvidedHttpResponseEditor {
        private val terminalView = TerminalView()

        override fun getResponse(): burp.api.montoya.http.message.responses.HttpResponse {
            return currentMessage()?.response() ?: burp.api.montoya.http.message.responses.HttpResponse.httpResponse()
        }

        override fun component(): Component = terminalView.component()

        override fun messageBytes(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): kotlin.ByteArray? {
            if (!requestResponse.hasResponse()) {
                return null
            }
            return requestResponse.response()?.toByteArray()?.getBytes()
        }

        override fun bodyOffset(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): Int? =
            requestResponse.response()?.bodyOffset()

        override fun headers(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): List<String>? =
            requestResponse.response()?.headers()?.map { it.toString() }

        override fun url(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): URL? =
            parseUrl(requestResponse.url())

        override fun processOutput(process: Process) = terminalView.processOutput(process)

        override fun handleExecutionFailure(e: IOException) {
            val message = "Failed to execute ${viewer.common.cmd.commandLine}: ${e.message}"
            terminalView.showMessage(message)
            logError(message)
        }

        override fun resetUi() = terminalView.clear()

        override fun selectedBytes(): kotlin.ByteArray? = terminalView.selectedBytes()
    }

    private inner class TerminalView {
        private val textPane = AnsiTextPane()
        private val scrollPane = JScrollPane(textPane).apply {
            val backgroundColor = textPane.background
            background = backgroundColor
            viewport.background = backgroundColor
        }

        fun component(): Component = scrollPane

        fun selectedBytes(): kotlin.ByteArray? = textPane.selectedText?.toByteArray()

        fun clear() {
            textPane.clearContent()
        }

        fun showMessage(line: String) {
            textPane.setAnsiText("$line\n")
        }

        fun processOutput(process: Process) {
            clear()
            val streams = listOf(process.inputStream, process.errorStream)
            val latch = CountDownLatch(streams.size)
            streams.forEach { stream ->
                thread(start = true) {
                    stream.reader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(2048)
                        try {
                            while (true) {
                                val read = reader.read(buffer)
                                if (read == -1) break
                                textPane.appendAnsi(String(buffer, 0, read))
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                }
            }
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private inner class PiperPayloadGenerator(
        private val tool: Piper.MinimalTool,
        private val parameters: Map<String, String>,
    ) : PayloadGenerator {
        private var execution: Pair<Process, List<File>>? = null
        private var reader: BufferedReader? = null

        override fun generatePayloadFor(insertionPoint: burp.api.montoya.intruder.IntruderInsertionPoint?): GeneratedPayload {
            val line = stdout()?.readLine()
            return if (line == null) {
                close()
                GeneratedPayload.end()
            } else {
                GeneratedPayload.payload(montoyaBytes(line.toByteArray(Charsets.ISO_8859_1)))
            }
        }

        private fun stdout(): BufferedReader? {
            val existing = reader
            if (existing != null) {
                return existing
            }

            val exec = tool.cmd.execute(parameters, kotlin.ByteArray(0))
            execution = exec
            val newReader = exec.first.inputStream.bufferedReader(charset = Charsets.ISO_8859_1)
            reader = newReader
            return newReader
        }

        private fun close() {
            reader?.close()
            execution?.first?.destroy()
            execution?.second?.forEach(File::delete)
            reader = null
            execution = null
        }
    }

    private inner class MontoyaRegisteredToolManager<M>(
        model: DefaultListModel<M>,
        private val enabledPredicate: (M) -> Boolean,
        private val register: (M) -> Registration?
    ) : ListModelRegistrationManager<M, Registration>(model) {

        init {
            initialize()
        }

        override fun registerItem(item: M): Registration? =
            if (enabledPredicate(item)) register(item) else null

        override fun deregisterItem(registration: Registration) {
            registration.deregister()
        }
    }

    private fun loadConfig(): Piper.Config {
        try {
            val env = System.getenv(CONFIG_ENV_VAR)
            if (env != null) {
                val fmt = if (env.endsWith(".yml") || env.endsWith(".yaml")) {
                    ConfigFormat.YAML
                } else {
                    ConfigFormat.PROTOBUF
                }
                val configFile = java.io.File(env)
                return fmt.parse(configFile.readBytes()).updateEnabled(true)
            }

            val persisted = api.persistence().extensionData().getByteArray(EXTENSION_SETTINGS_KEY)
            if (persisted != null) {
                return Piper.Config.parseFrom(persisted.getBytes())
            }

            throw IllegalStateException("No stored configuration")
        } catch (_: Exception) {
            val defaultConfig = loadDefaultConfig()
            saveConfig(defaultConfig)
            return defaultConfig
        }
    }

    private fun saveConfig(cfg: Piper.Config = configModel.serialize()) {
        api.persistence().extensionData().setByteArray(EXTENSION_SETTINGS_KEY, montoyaBytes(cfg.toByteArray()))
    }

    private fun getStdoutWithErrorHandling(executionResult: Pair<Process, List<File>>, tool: Piper.MinimalTool): kotlin.ByteArray =
        executionResult.processOutput { process ->
            if (configModel.developer) {
                val stderr = process.errorStream.readBytes()
                if (stderr.isNotEmpty()) {
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    val ts = LocalDateTime.now().format(formatter)
                    api.logging().logToError("${tool.name} called ${tool.cmd.commandLine} at $ts and stderr was not empty:")
                    api.logging().logToError(context.bytesToString(stderr))
                }
            }
            process.inputStream.readBytes()
        }
    private fun montoyaBytes(bytes: kotlin.ByteArray): ByteArray =
        ByteArray.byteArray(*bytes.map { it.toInt() }.toIntArray())
}

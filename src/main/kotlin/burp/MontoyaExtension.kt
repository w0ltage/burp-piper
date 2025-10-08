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
import burp.api.montoya.intruder.PayloadProcessingResult
import burp.api.montoya.intruder.PayloadProcessor
import burp.api.montoya.ui.Selection
import burp.api.montoya.ui.editor.extension.EditorCreationContext
import burp.api.montoya.ui.editor.extension.ExtensionProvidedEditor
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider
import burp.api.montoya.utilities.ByteUtils
import java.awt.BorderLayout
import java.awt.Component
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.text.Charsets
import com.redpois0n.terminal.JTerminal
import javax.swing.DefaultListModel
import javax.swing.JTabbedPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class MontoyaExtension : BurpExtension {
    private lateinit var api: MontoyaApi
    private lateinit var context: PiperContext
    private lateinit var configModel: ConfigModel

    private lateinit var processorManager: MontoyaRegisteredToolManager<Piper.MinimalTool>
    private lateinit var generatorManager: MontoyaRegisteredToolManager<Piper.MinimalTool>
    private lateinit var messageViewerManager: MontoyaMessageViewerManager

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

        val queuePlaceholder = JPanel(BorderLayout()).apply {
            add(
                JLabel("Queue processing is available when using the legacy Burp Extender API."),
                BorderLayout.NORTH,
            )
        }

        suiteTabs = JTabbedPane()
        populatePiperTabs(suiteTabs, configModel, parent = null) { queuePlaceholder }
        suiteTabRegistration = api.userInterface().registerSuiteTab(NAME, suiteTabs)
        api.logging().logToOutput("Piper suite tab registered")

        configModel.addPropertyChangeListener { saveConfig() }
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
                return PiperPayloadGenerator(tool)
            }
        }

        return api.intruder().registerPayloadGeneratorProvider(provider)
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

    private inner class MontoyaMessageViewerManager(
        private val model: DefaultListModel<Piper.MessageViewer>,
    ) : ListDataListener {

        private val registrations: MutableList<ViewerRegistrations?> = mutableListOf()

        init {
            for (index in 0 until model.size()) {
                registrations.add(registerViewer(model[index]))
            }
            model.addListDataListener(this)
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

        override fun contentsChanged(e: ListDataEvent) {
            for (index in e.index0..e.index1) {
                registrations.getOrNull(index)?.deregister()
                registrations[index] = registerViewer(model[index])
            }
            saveConfig()
        }

        override fun intervalAdded(e: ListDataEvent) {
            for (index in e.index0..e.index1) {
                registrations.add(index, registerViewer(model[index]))
            }
            saveConfig()
        }

        override fun intervalRemoved(e: ListDataEvent) {
            for (index in e.index1 downTo e.index0) {
                registrations.removeAt(index)?.deregister()
            }
            saveConfig()
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
            return Selection.selection(montoyaBytes(bytes))
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

        protected fun montoyaBytes(bytes: kotlin.ByteArray): ByteArray =
            ByteArray.byteArray(*bytes.map { it.toInt() }.toIntArray())

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
        private val textArea = JTextArea().apply { isEditable = false }
        private val scrollPane = JScrollPane(textArea)

        override fun getRequest(): burp.api.montoya.http.message.requests.HttpRequest {
            return currentMessage()?.request() ?: burp.api.montoya.http.message.requests.HttpRequest.httpRequest()
        }

        override fun component(): Component = scrollPane

        override fun messageBytes(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): kotlin.ByteArray? {
            return requestResponse.request()?.toByteArray()?.getBytes()
        }

        override fun bodyOffset(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): Int? =
            requestResponse.request()?.bodyOffset()

        override fun headers(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): List<String>? =
            requestResponse.request()?.headers()?.map { it.toString() }

        override fun url(requestResponse: burp.api.montoya.http.message.HttpRequestResponse): URL? =
            parseUrl(requestResponse.request()?.url())

        override fun processOutput(process: Process) {
            val output = process.inputStream.use { it.readBytes() }
            process.errorStream.use { it.readBytes() }
            SwingUtilities.invokeLater { textArea.text = bytesToString(output) }
        }

        override fun handleExecutionFailure(e: IOException) {
            val message = "Failed to execute ${viewer.common.cmd.commandLine}: ${e.message}"
            SwingUtilities.invokeLater { textArea.text = message }
            logError(message)
        }

        override fun resetUi() {
            SwingUtilities.invokeLater { textArea.text = "" }
        }

        override fun selectedBytes(): kotlin.ByteArray? = textArea.selectedText?.toByteArray()
    }

    private inner class ResponseMessageViewerEditor(
        viewer: Piper.MessageViewer,
        enabledFlag: AtomicBoolean,
    ) : BaseMessageViewerEditor(viewer, enabledFlag, false), ExtensionProvidedHttpResponseEditor {
        private val terminal = JTerminal()
        private val scrollPane = JScrollPane(terminal)

        override fun getResponse(): burp.api.montoya.http.message.responses.HttpResponse {
            return currentMessage()?.response() ?: burp.api.montoya.http.message.responses.HttpResponse.httpResponse()
        }

        override fun component(): Component = scrollPane

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

        override fun processOutput(process: Process) {
            terminal.text = ""
            val readers = listOf(process.inputStream.bufferedReader(), process.errorStream.bufferedReader())
            val latch = CountDownLatch(readers.size)
            readers.forEach { reader ->
                thread(start = true) {
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            SwingUtilities.invokeLater { terminal.append("$line\n") }
                        }
                    } finally {
                        latch.countDown()
                        reader.close()
                    }
                }
            }
            latch.await()
        }

        override fun handleExecutionFailure(e: IOException) {
            val message = "Failed to execute ${viewer.common.cmd.commandLine}: ${e.message}"
            SwingUtilities.invokeLater {
                terminal.text = ""
                terminal.append("$message\n")
            }
            logError(message)
        }

        override fun resetUi() {
            SwingUtilities.invokeLater { terminal.text = "" }
        }

        override fun selectedBytes(): kotlin.ByteArray? = terminal.selectedText?.toByteArray()
    }

    private inner class PiperPayloadGenerator(private val tool: Piper.MinimalTool) : PayloadGenerator {
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

            val exec = tool.cmd.execute(kotlin.ByteArray(0))
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
        private val model: DefaultListModel<M>,
        private val enabledPredicate: (M) -> Boolean,
        private val register: (M) -> Registration?
    ) : ListDataListener {
        private val registrations: MutableList<Registration?> = mutableListOf()

        init {
            for (i in 0 until model.size()) {
                registrations.add(registerIfEnabled(model[i]))
            }
            model.addListDataListener(this)
        }

        private fun registerIfEnabled(item: M): Registration? =
            if (enabledPredicate(item)) register(item) else null

        override fun contentsChanged(e: ListDataEvent) {
            for (index in e.index0..e.index1) {
                registrations[index]?.deregister()
                registrations[index] = registerIfEnabled(model[index])
            }
            saveConfig()
        }

        override fun intervalAdded(e: ListDataEvent) {
            for (index in e.index0..e.index1) {
                registrations.add(index, registerIfEnabled(model[index]))
            }
            saveConfig()
        }

        override fun intervalRemoved(e: ListDataEvent) {
            for (index in e.index1 downTo e.index0) {
                registrations.removeAt(index)?.deregister()
            }
            saveConfig()
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

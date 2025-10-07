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
import burp.api.montoya.utilities.ByteUtils
import java.io.BufferedReader
import java.io.File
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.text.Charsets
import javax.swing.DefaultListModel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class MontoyaExtension : BurpExtension {
    private lateinit var api: MontoyaApi
    private lateinit var context: PiperContext
    private lateinit var configModel: ConfigModel

    private lateinit var processorManager: MontoyaRegisteredToolManager<Piper.MinimalTool>
    private lateinit var generatorManager: MontoyaRegisteredToolManager<Piper.MinimalTool>

    override fun initialize(api: MontoyaApi) {
        this.api = api
        api.extension().setName(NAME)

        context = object : PiperContext {
            private val byteUtils: ByteUtils = api.utilities().byteUtils()

            override fun bytesToString(data: kotlin.ByteArray): String = byteUtils.convertToString(data)
            override fun isInScope(url: URL): Boolean = api.scope().isInScope(url.toString())
        }

        configModel = ConfigModel(loadConfig())

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

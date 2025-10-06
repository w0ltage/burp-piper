package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.*

/**
 * Queue component for managing HTTP request/response pairs for batch processing.
 *
 * This component provides a UI for users to queue HTTP messages and then process them with various
 * Piper tools using the Montoya API.
 */
class Queue(private val montoyaApi: MontoyaApi, private val configModel: ConfigModel) :
        JPanel(BorderLayout()), ListDataListener, ListSelectionListener, MouseListener {

    /**
     * Wrapper class for HttpRequestResponse that provides immutable snapshots of HTTP messages for
     * queue processing with Montoya API.
     */
    inner class QueuedHttpRequestResponse(original: HttpRequestResponse) : HttpRequestResponse {

        /** Immutable snapshot of the HTTP service */
        inner class QueuedHttpService(original: HttpService) : HttpService {
            private val host = original.host()
            private val port = original.port()
            private val secure = original.secure()

            override fun host(): String = host
            override fun port(): Int = port
            override fun secure(): Boolean = secure
            override fun ipAddress(): String = host
        }

        // Create immutable snapshots of the original data
        private val httpService = QueuedHttpService(original.httpService())
        private val request = original.request()
        private val response = original.response()
        private val annotations = original.annotations()

        override fun httpService(): HttpService = httpService
        override fun request(): HttpRequest = request
        override fun response(): HttpResponse = response
        override fun annotations(): Annotations = annotations

        override fun url(): String = request.url()
        override fun hasResponse(): Boolean = response != null
        override fun contentType(): burp.api.montoya.http.message.ContentType =
                request.contentType()
        override fun statusCode(): Short = response?.statusCode() ?: 0

        override fun withAnnotations(annotations: Annotations): HttpRequestResponse {
            // Return a new instance with updated annotations (immutable pattern)
            return object : HttpRequestResponse {
                override fun httpService(): HttpService =
                        this@QueuedHttpRequestResponse.httpService()
                override fun request(): HttpRequest = this@QueuedHttpRequestResponse.request()
                override fun response(): HttpResponse = this@QueuedHttpRequestResponse.response()
                override fun annotations(): Annotations = annotations
                override fun url(): String = this@QueuedHttpRequestResponse.url()
                override fun hasResponse(): Boolean = this@QueuedHttpRequestResponse.hasResponse()
                override fun contentType(): burp.api.montoya.http.message.ContentType =
                        this@QueuedHttpRequestResponse.contentType()
                override fun statusCode(): Short = this@QueuedHttpRequestResponse.statusCode()
                override fun timingData():
                        java.util.Optional<burp.api.montoya.http.message.TimingData> =
                        java.util.Optional.empty()
                override fun withAnnotations(annotations: Annotations): HttpRequestResponse =
                        this@QueuedHttpRequestResponse.withAnnotations(annotations)
            }
        }

        override fun timingData(): java.util.Optional<burp.api.montoya.http.message.TimingData> =
                java.util.Optional.empty()

        override fun toString(): String = toHumanReadable(this)
    }

    // UI Components
    private val model = DefaultListModel<QueuedHttpRequestResponse>()
    private val pnToolbar = JPanel()
    private val listWidget = JList(model)
    private val btnProcess = JButton("Process")

    /** Add HTTP request/response pairs to the queue */
    fun add(values: Iterable<HttpRequestResponse>) {
        values.map(::QueuedHttpRequestResponse).forEach(model::addElement)
    }

    /** Add a single HTTP request/response to the queue */
    fun add(value: HttpRequestResponse) {
        model.addElement(QueuedHttpRequestResponse(value))
    }

    /** Get human readable description of an HTTP request/response pair */
    private fun toHumanReadable(value: HttpRequestResponse): String {
        return try {
            val request = value.request()
            val response = value.response()

            if (response != null) {
                val statusCode = response.statusCode()
                val method = request.method()
                val url = request.url()
                val bodyLength = response.body().length()
                val plural = if (bodyLength == 1) "" else "s"
                "$statusCode $method $url (response size = $bodyLength byte$plural)"
            } else {
                val method = request.method()
                val url = request.url()
                "$method $url (no response)"
            }
        } catch (e: Exception) {
            "Invalid HTTP message: ${e.message}"
        }
    }

    /** Create and configure toolbar buttons */
    private fun addButtons() {
        // Remove button
        val btnRemove =
                JButton("Remove").apply {
                    addActionListener {
                        val selectedIndices = listWidget.selectedIndices.sortedDescending()
                        for (index in selectedIndices) {
                            if (index >= 0 && index < this@Queue.model.size()) {
                                this@Queue.model.removeElementAt(index)
                            }
                        }
                    }
                }

        // Process button - shows context menu with available tools
        btnProcess.addActionListener { event ->
            val component = event.source as Component
            val location = component.locationOnScreen
            showProcessMenu(location.x, location.y + component.height)
        }

        // Add buttons to toolbar
        listOf(btnRemove, btnProcess).forEach(pnToolbar::add)
    }

    /** Show context menu with available processing tools */
    private fun showProcessMenu(x: Int, y: Int) {
        val popupMenu = JPopupMenu()

        try {
            val selectedItems = listWidget.selectedValuesList
            if (selectedItems.isEmpty()) {
                popupMenu.add(JMenuItem("No items selected").apply { isEnabled = false })
            } else {
                generateContextMenu(selectedItems, popupMenu)
            }

            popupMenu.show(this, 0, 0)
            popupMenu.setLocation(x, y)
        } catch (e: Exception) {
            montoyaApi.logging().logToError("Error showing process menu: ${e.message}")
        }
    }

    /** Generate context menu items for processing selected queue items */
    private fun generateContextMenu(
            selectedItems: List<QueuedHttpRequestResponse>,
            popupMenu: JPopupMenu
    ) {
        val config = configModel.config

        // Add minimal tools
        val enabledMinimalTools = configModel.macrosModel.toIterable().filter { it.enabled }
        if (enabledMinimalTools.isNotEmpty()) {
            enabledMinimalTools.forEach { tool ->
                val menuItem =
                        JMenuItem(tool.name).apply {
                            addActionListener { processItemsWithTool(selectedItems, tool) }
                        }
                popupMenu.add(menuItem)
            }
        }

        // Add user action tools if any
        val enabledUserActionTools =
                configModel.userActionToolsModel.toIterable().filter { it.enabled }
        if (enabledUserActionTools.isNotEmpty()) {
            if (enabledMinimalTools.isNotEmpty()) {
                popupMenu.addSeparator()
            }
            enabledUserActionTools.forEach { tool ->
                val menuItem =
                        JMenuItem(tool.common.name).apply {
                            addActionListener { _ ->
                                processItemsWithUserActionTool(selectedItems, tool)
                            }
                        }
                popupMenu.add(menuItem)
            }
        }

        // If no tools available
        if (enabledMinimalTools.isEmpty() && enabledUserActionTools.isEmpty()) {
            popupMenu.add(JMenuItem("No enabled tools available").apply { isEnabled = false })
        }
    }

    /** Process selected items with a minimal tool */
    private fun processItemsWithTool(
            items: List<QueuedHttpRequestResponse>,
            tool: Piper.MinimalTool
    ) {
        try {
            montoyaApi
                    .logging()
                    .logToOutput("Processing ${items.size} queue items with tool: ${tool.name}")

            // This would execute the tool on each selected item
            // For now, just log the action
            items.forEach { item -> montoyaApi.logging().logToOutput("Processing: ${item}") }
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError("Error processing items with tool '${tool.name}': ${e.message}")
        }
    }

    /** Process selected items with a user action tool */
    private fun processItemsWithUserActionTool(
            items: List<QueuedHttpRequestResponse>,
            tool: Piper.UserActionTool
    ) {
        try {
            montoyaApi
                    .logging()
                    .logToOutput(
                            "Processing ${items.size} queue items with user action: ${tool.name}"
                    )

            // This would execute the user action tool
            // For now, just log the action
            items.forEach { item -> montoyaApi.logging().logToOutput("Processing: ${item}") }
        } catch (e: Exception) {
            montoyaApi
                    .logging()
                    .logToError(
                            "Error processing items with user action '${tool.name}': ${e.message}"
                    )
        }
    }

    /** Handle right-click context menu */
    override fun mouseClicked(event: MouseEvent) {
        if (event.button == MouseEvent.BUTTON3) {
            showProcessMenu(event.xOnScreen, event.yOnScreen)
        }
    }

    override fun mouseEntered(event: MouseEvent?) {}
    override fun mouseExited(event: MouseEvent?) {}
    override fun mousePressed(event: MouseEvent?) {}
    override fun mouseReleased(event: MouseEvent?) {}

    /** Handle list selection changes */
    override fun valueChanged(event: ListSelectionEvent?) {
        updateButtonStates()
    }

    /** Handle list data changes */
    override fun contentsChanged(event: ListDataEvent?) {
        updateButtonStates()
    }

    override fun intervalAdded(event: ListDataEvent?) {
        updateButtonStates()
    }

    override fun intervalRemoved(event: ListDataEvent?) {
        updateButtonStates()
    }

    /** Update button enabled/disabled states based on selection */
    private fun updateButtonStates() {
        btnProcess.isEnabled = !listWidget.isSelectionEmpty
    }

    /** Get current queue size */
    fun queueSize(): Int = model.size()

    /** Check if queue is empty */
    fun isEmpty(): Boolean = model.isEmpty

    /** Clear all items from the queue */
    fun clear() {
        model.clear()
    }

    /** Get all queued items */
    fun getAllItems(): List<QueuedHttpRequestResponse> {
        return (0 until model.size()).map { model.getElementAt(it) }
    }

    /** Initialize the queue component */
    init {
        // Configure the list widget
        listWidget.apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            addListSelectionListener(this@Queue)
            addMouseListener(this@Queue)
        }

        // Configure the model
        model.addListDataListener(this)

        // Set up the UI
        addButtons()
        updateButtonStates()

        // Layout components
        add(pnToolbar, BorderLayout.NORTH)
        add(JScrollPane(listWidget), BorderLayout.CENTER)

        // Add status label at bottom
        val statusLabel =
                JLabel("Queue: 0 items").apply {
                    border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                }

        // Update status label when model changes
        val updateStatus = { statusLabel.text = "Queue: ${model.size()} items" }

        model.addListDataListener(
                object : ListDataListener {
                    override fun contentsChanged(e: ListDataEvent?) = updateStatus()
                    override fun intervalAdded(e: ListDataEvent?) = updateStatus()
                    override fun intervalRemoved(e: ListDataEvent?) = updateStatus()
                }
        )

        add(statusLabel, BorderLayout.SOUTH)
    }
}

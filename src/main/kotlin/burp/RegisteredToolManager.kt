package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.logging.Logging
import javax.swing.DefaultListModel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import kotlin.math.max
import kotlin.math.min

/**
 * A Montoya aware variant of the legacy [RegisteredToolManager] utility used by the original
 * Piper extension. The manager keeps a 1:1 mapping between items stored in a Swing
 * [DefaultListModel] and the registrations returned by the Montoya API. Whenever the model
 * changes (items are added, updated or removed) the manager automatically registers or
 * deregisters the associated Montoya integrations so that the Burp UI stays in sync with the
 * configuration editor.
 */
abstract class RegisteredToolManager<M>(
        protected val montoyaApi: MontoyaApi,
        protected val model: DefaultListModel<M>
) : ListDataListener {

    private val logging: Logging = montoyaApi.logging()

    /** Parallel list of registrations mirroring the Swing model order. */
    private val registrations = mutableListOf<Registration?>()

    private var started = false

    /** Start tracking the backing model and register currently configured items. */
    fun start() {
        if (started) return

        started = true
        registrations.clear()

        for (index in 0 until model.size()) {
            val item = model[index]
            registrations.add(registerItem(item))
        }

        model.addListDataListener(this)
    }

    /** Stop tracking the model and clean up registrations. */
    fun stop() {
        if (!started) return

        model.removeListDataListener(this)
        registrations.forEach { safeDeregister(it, null) }
        registrations.clear()
        started = false
    }

    /** Implemented by subclasses to describe the tool type for logging purposes. */
    protected abstract fun toolTypeName(): String

    /** Implemented by subclasses to determine whether the given model item should be enabled. */
    protected abstract fun isModelItemEnabled(item: M): Boolean

    /** Implemented by subclasses to perform the Montoya registration for the supplied item. */
    protected abstract fun registerModelItem(item: M): Registration?

    /** Allow subclasses to provide human readable names for log messages. */
    protected open fun itemName(item: M): String = item.toString()

    private fun registerItem(item: M): Registration? {
        if (!isModelItemEnabled(item)) {
            logging.logToOutput(
                    "DEBUG: Skipping disabled ${toolTypeName()}: ${itemName(item)}"
            )
            return null
        }

        return try {
            registerModelItem(item)?.also {
                logging.logToOutput(
                        "DEBUG: Registered ${toolTypeName()}: ${itemName(item)}"
                )
            }
        } catch (e: Exception) {
            logging.logToError(
                    "ERROR: Failed to register ${toolTypeName()} '${itemName(item)}': ${e.message}"
            )
            null
        }
    }

    private fun safeDeregister(registration: Registration?, name: String?) {
        if (registration == null) return

        try {
            if (registration.isRegistered) {
                registration.deregister()
                if (name != null) {
                    logging.logToOutput(
                            "DEBUG: Unregistered ${toolTypeName()}: $name"
                    )
                }
            }
        } catch (e: Exception) {
            logging.logToError(
                    "ERROR: Failed to deregister ${toolTypeName()} ${name ?: ""}: ${e.message}"
            )
        }
    }

    private fun calculateRange(event: ListDataEvent): IntRange? {
        if (model.size() == 0) return null

        val start = max(0, if (event.index0 < 0) 0 else event.index0)
        val end = min(model.size() - 1, if (event.index1 < 0) model.size() - 1 else event.index1)

        if (end < start) return null
        return start..end
    }

    override fun contentsChanged(event: ListDataEvent) {
        if (!started) return

        val range = calculateRange(event) ?: return
        for (index in range) {
            val existing = registrations.getOrNull(index)
            safeDeregister(existing, itemName(model[index]))
            registrations[index] = registerItem(model[index])
        }
    }

    override fun intervalAdded(event: ListDataEvent) {
        if (!started) return

        val range = calculateRange(event) ?: return
        for (index in range) {
            val registration = registerItem(model[index])
            registrations.add(index, registration)
        }
    }

    override fun intervalRemoved(event: ListDataEvent) {
        if (!started) return

        if (registrations.isEmpty()) return

        val start = max(0, if (event.index0 < 0) 0 else event.index0)
        val end = min(registrations.size - 1, if (event.index1 < 0) registrations.size - 1 else event.index1)

        for (index in end downTo start) {
            val existing = registrations.removeAt(index)
            safeDeregister(existing, null)
        }
    }

    /** Expose the currently tracked registrations for diagnostic purposes. */
    fun currentRegistrations(): List<Registration?> = registrations.toList()
}

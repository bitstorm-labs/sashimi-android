package dev.bitstorm.sashimi.core.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A message on screen. [id] differs for every message, so a repeat of the same text still re-shows. */
data class ShownMessage(
    val id: Long,
    val header: String?,
    val text: String,
)

/**
 * The server message currently on screen, if any. A new message replaces the
 * one showing and gets its own full timeout; each disappears after its
 * TimeoutMs. The UI only renders [current] and may [dismiss] early.
 */
class RemoteMessageBoard(
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<ShownMessage?>(null)
    val current: StateFlow<ShownMessage?> = _current.asStateFlow()

    private var nextId = 0L
    private var expiry: Job? = null

    @Synchronized
    fun show(message: RemoteCommand.DisplayMessage) {
        val shown = ShownMessage(++nextId, message.header, message.text)
        expiry?.cancel()
        _current.value = shown
        expiry =
            scope.launch {
                delay(message.timeoutMs)
                clearIf(shown.id)
            }
    }

    @Synchronized
    fun dismiss() {
        expiry?.cancel()
        _current.value = null
    }

    @Synchronized
    private fun clearIf(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }
}

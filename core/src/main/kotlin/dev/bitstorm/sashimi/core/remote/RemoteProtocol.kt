package dev.bitstorm.sashimi.core.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

// The Jellyfin session WebSocket (`/socket`), as checked against a live 12.2
// server with a throwaway user (issue #161):
//  - The server lists a session as remote-controllable (`SupportsRemoteControl`,
//    which the admin dashboard gates Stop and Message on) only while BOTH an
//    authenticated socket is open AND the capabilities POST declared
//    `SupportsMediaControl: true`. Either one alone reads false.
//  - The socket authenticates with the `Authorization: MediaBrowser ... Token=`
//    header. `?api_key=` is refused with 403.
//  - Right after the upgrade the server sends `ForceKeepAlive` with Data = 60
//    (seconds) and expects a `KeepAlive` well inside that window.
//  - Every message is `{"MessageType": ..., "Data": ..., "MessageId": ...}`.
//    Stop is `Playstate` / `{"Command":"Stop"}`; a message is `GeneralCommand` /
//    `{"Name":"DisplayMessage","Arguments":{"Header","Text","TimeoutMs"}}` with
//    every argument a STRING ("4000", not 4000).
//  - The server pushes commands the client never declared (Pause, GoHome were
//    both delivered to a session that declared only DisplayMessage), so the
//    parser must treat anything it does not know as ignorable.

/** A server command this client acts on. Everything else is [Inbound.Ignored]. */
sealed interface RemoteCommand {
    /** Playstate Stop: end playback as if the user had closed the player. */
    data object Stop : RemoteCommand

    /** GeneralCommand DisplayMessage, already normalised for display. */
    data class DisplayMessage(
        val header: String?,
        val text: String,
        val timeoutMs: Long,
    ) : RemoteCommand
}

/** One parsed socket frame. */
sealed interface Inbound {
    /** The server's keep-alive window; reply with [RemoteProtocol.KEEP_ALIVE] every [intervalMs]. */
    data class ForceKeepAlive(val intervalMs: Long) : Inbound

    data class Command(val command: RemoteCommand) : Inbound

    /** Unknown, undeclared, malformed, or not actionable: drop it. */
    data object Ignored : Inbound
}

/** Body of `POST /Sessions/Capabilities/Full`. No defaults on purpose: the client's JSON omits defaults. */
@Serializable
data class ClientCapabilitiesDto(
    @SerialName("PlayableMediaTypes") val playableMediaTypes: List<String>,
    @SerialName("SupportedCommands") val supportedCommands: List<String>,
    @SerialName("SupportsMediaControl") val supportsMediaControl: Boolean,
    @SerialName("SupportsPersistentIdentifier") val supportsPersistentIdentifier: Boolean,
)

object RemoteProtocol {
    const val KEEP_ALIVE = """{"MessageType":"KeepAlive"}"""

    /** Shown when the sender gives no usable TimeoutMs. */
    const val DEFAULT_MESSAGE_MS = 5_000L

    /** A message must be on screen long enough to read... */
    const val MIN_MESSAGE_MS = 1_000L

    /** ...and must not sit over the video indefinitely. */
    const val MAX_MESSAGE_MS = 60_000L

    /** Used when ForceKeepAlive carries no usable window. Jellyfin's own is 60 s. */
    const val DEFAULT_KEEP_ALIVE_WINDOW_S = 60

    /** Never ping faster than this, whatever the server asks. */
    const val MIN_KEEP_ALIVE_MS = 5_000L

    /**
     * What Sashimi accepts. `SupportsMediaControl` is what makes the server
     * route Playstate commands here; `DisplayMessage` is the one general
     * command handled. No playable media types: this client does not accept
     * "play this on that device", so it must not be offered as a cast target.
     */
    val CAPABILITIES =
        ClientCapabilitiesDto(
            playableMediaTypes = emptyList(),
            supportedCommands = listOf("DisplayMessage"),
            supportsMediaControl = true,
            supportsPersistentIdentifier = true,
        )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Inbound {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Inbound.Ignored
        val type = root.string("MessageType") ?: return Inbound.Ignored
        val data = root["Data"]
        return when (type) {
            "ForceKeepAlive" -> {
                val seconds = (data as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: DEFAULT_KEEP_ALIVE_WINDOW_S
                // Half the window, so one lost frame never lets the session lapse.
                Inbound.ForceKeepAlive((seconds * 1_000L / 2).coerceAtLeast(MIN_KEEP_ALIVE_MS))
            }
            "Playstate" -> {
                val command = (data as? JsonObject)?.string("Command")
                if (command == "Stop") Inbound.Command(RemoteCommand.Stop) else Inbound.Ignored
            }
            "GeneralCommand" -> generalCommand(data as? JsonObject)
            else -> Inbound.Ignored
        }
    }

    private fun generalCommand(data: JsonObject?): Inbound {
        if (data?.string("Name") != "DisplayMessage") return Inbound.Ignored
        val args = data["Arguments"] as? JsonObject
        val header = args?.string("Header")?.trim()?.takeIf { it.isNotEmpty() }
        val body = args?.string("Text")?.trim()?.takeIf { it.isNotEmpty() }
        // A header-only message is still a message; with neither there is nothing to show.
        val text = body ?: header ?: return Inbound.Ignored
        return Inbound.Command(
            RemoteCommand.DisplayMessage(
                header = if (body == null) null else header,
                text = text,
                timeoutMs = messageTimeout(args?.get("TimeoutMs")),
            ),
        )
    }

    /** TimeoutMs arrives as a string ("4000"); a bare number is accepted too. Clamped to [MIN_MESSAGE_MS, MAX_MESSAGE_MS]. */
    private fun messageTimeout(raw: JsonElement?): Long {
        val value = (raw as? JsonPrimitive)?.let { it.longOrNull }
        if (value == null || value <= 0) return DEFAULT_MESSAGE_MS
        return value.coerceIn(MIN_MESSAGE_MS, MAX_MESSAGE_MS)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

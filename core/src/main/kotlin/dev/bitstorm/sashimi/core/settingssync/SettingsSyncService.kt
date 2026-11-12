package dev.bitstorm.sashimi.core.settingssync

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The settings this device syncs, as the service sees them. */
interface SyncedSettings {
    /** The shared keys this platform supports. */
    val keys: List<String>

    /** Keys the user has set on this device (seeding); keys still at their default are absent. */
    fun explicitlySet(): Set<String>

    /** The local value of [key] to write, given what the server holds now (layouts carry remote rows through). */
    fun value(
        key: String,
        remote: JsonElement?,
    ): JsonElement

    /** Applies a validated remote value. Must not report it back as a local change. */
    fun apply(
        key: String,
        v: JsonElement,
    )
}

/** The device-local sync state: never synced itself. */
interface SettingsSyncState {
    val enabled: StateFlow<Boolean>

    fun setEnabled(on: Boolean)

    /** The stamp map, or null when it has never been written (the first run after the upgrade). */
    fun stamps(): Map<String, Long>?

    /** Atomically replaces the stamp map with [change] of the current one. */
    fun updateStamps(change: (Map<String, Long>?) -> Map<String, Long>)
}

/** The `sashimi-settings` DisplayPreferences entry: whole-object read and write. */
interface SettingsSyncTransport {
    suspend fun load(): String

    suspend fun save(body: String)
}

/** The transport over the active server's client, as the signed-in user. */
class JellyfinSettingsSyncTransport(
    private val client: JellyfinClient,
) : SettingsSyncTransport {
    override suspend fun load(): String = client.listsRequest("GET", PATH, query())

    override suspend fun save(body: String) {
        client.listsRequest("POST", PATH, query(), body)
    }

    private fun query() = listOf("userId" to client.listsUserId(), "client" to SettingsSyncSchema.PREFS_CLIENT)

    private companion object {
        const val PATH = "/DisplayPreferences/${SettingsSyncSchema.PREFS_ID}"
    }
}

/**
 * Cross-device settings sync (WORK-106). Each synced setting is one
 * CustomPrefs entry `{"v":…,"t":…}` in the user's `sashimi-settings`
 * DisplayPreferences on the active server; the newest `t` wins, key by key.
 *
 * Every write re-reads the entry and posts the whole object back with only
 * our keys changed, so other top-level fields and other CustomPrefs keys
 * (whatever their JSON type) stay as they were. Network work is serialized.
 */
class SettingsSyncService(
    private val transport: SettingsSyncTransport,
    private val local: SyncedSettings,
    private val state: SettingsSyncState,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    val enabled: StateFlow<Boolean> get() = state.enabled

    /** Turning sync on reconciles at once; off makes no more network calls. */
    fun setEnabled(on: Boolean): Job? {
        state.setEnabled(on)
        return if (on) reconcileInBackground() else null
    }

    /**
     * A synced setting changed on this device: stamp it now (also while sync
     * is off, so it is pushed when sync comes back on), and push it if on.
     */
    fun localChanged(key: String): Job? {
        if (key !in local.keys) return null
        val t = now()
        state.updateStamps { current -> (current ?: seed()) + (key to t) }
        return if (state.enabled.value) scope.launch { runCatchingCancellable { push(key) } } else null
    }

    /** Launch, foreground, sign-in, server or user switch. Failures wait for the next trigger. */
    fun reconcileInBackground(): Job = scope.launch { runCatchingCancellable { reconcile() } }

    /** Reads once, applies what is newer remotely, writes what is newer here in one POST. */
    suspend fun reconcile() {
        if (!state.enabled.value) return
        ensureSeeded()
        mutex.withLock {
            val prefs = load()
            val remote = customPrefs(prefs)
            val plan = SettingsSyncRules.reconcile(local.keys, remote, state.stamps().orEmpty())
            for ((key, entry) in plan.apply) applyIfStillNewer(key, entry)
            if (plan.write.isNotEmpty()) save(prefs, remote, plan.write)
        }
    }

    /** Re-read, write [key] if our stamp is newer, post. */
    suspend fun push(key: String) {
        if (!state.enabled.value) return
        mutex.withLock {
            val stamp = state.stamps()?.get(key) ?: return
            val prefs = load()
            val remote = customPrefs(prefs)
            if (SettingsSyncRules.shouldPush(key, remote, stamp)) save(prefs, remote, setOf(key))
        }
    }

    private fun ensureSeeded() {
        if (state.stamps() == null) state.updateStamps { current -> current ?: seed() }
    }

    private fun seed(): Map<String, Long> = SettingsSyncRules.seed(local.explicitlySet().intersect(local.keys.toSet()))

    /** A change made on this device while the read was in flight wins over the remote value. */
    private fun applyIfStillNewer(
        key: String,
        entry: StampedValue,
    ) {
        val stamp = state.stamps()?.get(key)
        if (stamp != null && stamp >= entry.t) return
        local.apply(key, entry.v)
        state.updateStamps { current -> (current ?: emptyMap()) + (key to entry.t) }
    }

    private suspend fun load(): JsonObject = json.parseToJsonElement(transport.load()).jsonObject

    private fun customPrefs(prefs: JsonObject): Map<String, JsonElement> =
        prefs[SettingsSyncSchema.CUSTOM_PREFS] as? JsonObject ?: emptyMap()

    private suspend fun save(
        prefs: JsonObject,
        remote: Map<String, JsonElement>,
        keys: Set<String>,
    ) {
        val stamps = state.stamps().orEmpty()
        val written =
            keys.mapNotNull { key ->
                val t = stamps[key] ?: return@mapNotNull null
                key to JsonPrimitive(StampedValueCodec.encode(StampedValue(local.value(key, remote[key]?.let(::decodedValue)), t)))
            }
        if (written.isEmpty()) return
        val remoteSchema = (remote[SettingsSyncSchema.SCHEMA_KEY] as? JsonPrimitive)?.content?.toIntOrNull()
        val schema = maxOf(remoteSchema ?: 0, SettingsSyncSchema.SCHEMA_VERSION)
        val custom = JsonObject(remote + written + (SettingsSyncSchema.SCHEMA_KEY to JsonPrimitive(schema.toString())))
        transport.save(json.encodeToString(JsonObject.serializer(), JsonObject(prefs + (SettingsSyncSchema.CUSTOM_PREFS to custom))))
    }

    private fun decodedValue(raw: JsonElement): JsonElement? = StampedValueCodec.decode(raw)?.v
}

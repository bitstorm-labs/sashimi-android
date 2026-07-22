package dev.bitstorm.sashimi.core.network

import dev.bitstorm.sashimi.core.model.AuthenticationResult
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.IntroSkipperSegment
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.ItemsResponse
import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import dev.bitstorm.sashimi.core.model.LibraryViewsResponse
import dev.bitstorm.sashimi.core.model.MediaSegmentDto
import dev.bitstorm.sashimi.core.model.MediaSegmentType
import dev.bitstorm.sashimi.core.model.PlaybackInfoResponse
import dev.bitstorm.sashimi.core.model.PublicSystemInfo
import dev.bitstorm.sashimi.core.playback.DeviceProfile
import dev.bitstorm.sashimi.core.playback.NegotiationFlags
import dev.bitstorm.sashimi.core.playback.PlaybackInfoRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Jellyfin REST client — the Android port of Shared/Services/JellyfinClient.swift.
 * Holds the active server URL / access token / user id, builds requests against
 * whichever server is configured, and carries the Swift client's hard-won retry
 * and session-expiry behaviour.
 *
 * Not a UI object (:core is Compose-free). The app constructs a single instance
 * and hands it to the SessionManager.
 */
class JellyfinClient(
    private val deviceId: String,
    private val clientName: String = "Sashimi",
    private val deviceName: String = "Sashimi Android",
    private val clientVersion: String = "0.1.0",
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : JellyfinAuthGateway {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    // Playback request bodies need defaults emitted (MaxStaticBitrate,
    // BreakOnNonKeyFrames, the Enable* flags, …) but nulls omitted (StartTimeTicks
    // etc. are only present when set). The default [json] would drop the defaults.
    private val playbackJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val api: JellyfinApi =
        Retrofit.Builder()
            // Dummy base URL: every call passes an absolute @Url, so this is
            // never used except to satisfy Retrofit's builder validation.
            .baseUrl("http://localhost/")
            .client(httpClient)
            .build()
            .create(JellyfinApi::class.java)

    @Volatile private var serverUrl: HttpUrl? = null

    @Volatile private var accessToken: String? = null

    @Volatile private var userId: String? = null

    /**
     * Invoked with the client's current server URL when a non-auth request gets
     * a 401/403. The SessionManager decides whether to expire the session — it
     * only does so when this URL is the ACTIVE server's (the 401-only-on-active
     * lesson: an Add Server probe briefly repoints the client, and a 401 there
     * must not nuke the live session). See SessionManager.
     */
    override var sessionExpiredHandler: ((serverUrl: String) -> Unit)? = null

    private val maxRetries = 3

    override val currentServerUrl: String?
        get() = serverUrl?.toString()?.trimEnd('/')

    val currentUserId: String?
        get() = userId

    val isConfigured: Boolean
        get() = serverUrl != null && accessToken != null && userId != null

    override fun clearCredentials() {
        serverUrl = null
        accessToken = null
        userId = null
    }

    override fun configure(
        serverUrl: String,
        accessToken: String?,
        userId: String?,
    ) {
        this.serverUrl = serverUrl.toHttpUrlOrNull()
        this.accessToken = accessToken
        this.userId = userId
    }

    /** MediaBrowser authorization header (X-Emby-Authorization scheme). */
    private fun authorizationHeader(): String {
        val parts =
            mutableListOf(
                "MediaBrowser Client=\"$clientName\"",
                "Device=\"$deviceName\"",
                "DeviceId=\"$deviceId\"",
                "Version=\"$clientVersion\"",
            )
        accessToken?.let { parts.add("Token=\"$it\"") }
        return parts.joinToString(", ")
    }

    private fun requireUserId(): String = userId ?: throw JellyfinError.NotConfigured

    private fun buildUrl(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): String {
        val base = serverUrl ?: throw JellyfinError.NotConfigured
        val builder = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }

    /**
     * Executes a request with the retry + session-expiry semantics ported from
     * the Swift `request(...)`:
     *  - GET/DELETE are idempotent → retried on 5xx and network errors with
     *    exponential backoff. POST is not (a delivered-but-timed-out
     *    reportPlaybackStopped must not double-apply).
     *  - 401/403 on an auth request → InvalidCredentials (wrong password, not a
     *    dead session). On any other request → notify the session-expiry handler
     *    (SessionManager gates the actual logout on the active server) and throw
     *    SessionExpired.
     */
    private suspend fun execute(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        jsonBody: String? = null,
        isAuthRequest: Boolean = false,
        retryCount: Int = 0,
    ): String {
        val url = buildUrl(path, query)
        val auth = authorizationHeader()
        val isIdempotent = method == "GET" || method == "DELETE"

        val response: Response<okhttp3.ResponseBody> =
            try {
                when (method) {
                    "GET" -> api.get(url, auth)
                    "DELETE" -> api.delete(url, auth)
                    "POST" ->
                        if (jsonBody != null) {
                            api.post(url, auth, jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                        } else {
                            api.postEmpty(url, auth)
                        }
                    else -> throw JellyfinError.InvalidResponse
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isIdempotent && retryCount < maxRetries) {
                    delay(backoffMillis(retryCount))
                    return execute(method, path, query, jsonBody, isAuthRequest, retryCount + 1)
                }
                throw JellyfinError.NetworkError(e)
            }

        val code = response.code()

        if (code == 401 || code == 403) {
            response.errorBody()?.close()
            if (isAuthRequest) throw JellyfinError.InvalidCredentials
            serverUrl?.let { sessionExpiredHandler?.invoke(it.toString().trimEnd('/')) }
            throw JellyfinError.SessionExpired
        }

        if (code in 500..599 && isIdempotent && retryCount < maxRetries) {
            response.errorBody()?.close()
            delay(backoffMillis(retryCount))
            return execute(method, path, query, jsonBody, isAuthRequest, retryCount + 1)
        }

        if (code !in 200..299) {
            response.errorBody()?.close()
            throw JellyfinError.HttpError(code)
        }

        return response.body()?.string() ?: ""
    }

    private fun backoffMillis(retryCount: Int): Long = (2.0.pow(retryCount) * 1000).toLong()

    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString(body)
        } catch (e: Exception) {
            throw JellyfinError.DecodingError
        }

    // MARK: - Auth / system

    override suspend fun authenticate(
        username: String,
        password: String,
    ): AuthenticationResult {
        val body = json.encodeToString(mapOf("Username" to username, "Pw" to password))
        val data =
            execute(
                method = "POST",
                path = "/Users/AuthenticateByName",
                jsonBody = body,
                isAuthRequest = true,
            )
        val result: AuthenticationResult = decode(data)
        accessToken = result.accessToken
        userId = result.user.id
        return result
    }

    override suspend fun getPublicSystemInfo(): PublicSystemInfo {
        val data = execute("GET", "/System/Info/Public")
        return decode(data)
    }

    // MARK: - Libraries / home rows

    suspend fun getUserViews(): List<JellyfinLibrary> {
        val uid = requireUserId()
        val data = execute("GET", "/Users/$uid/Views")
        return decode<LibraryViewsResponse>(data).items
    }

    suspend fun getResumeItems(limit: Int = 20): List<BaseItemDto> {
        val uid = requireUserId()
        val data =
            execute(
                "GET",
                "/Users/$uid/Items/Resume",
                query =
                    listOf(
                        "Limit" to "$limit",
                        "Fields" to
                            "Overview,PrimaryImageAspectRatio,CommunityRating,OfficialRating," +
                            "Genres,Taglines,ParentBackdropImageTags,UserData,Path,MediaStreams",
                        "EnableImageTypes" to "Primary,Backdrop,Thumb",
                        "Recursive" to "true",
                    ),
            )
        return decode<ItemsResponse>(data).items
    }

    suspend fun getNextUp(limit: Int = 50): List<BaseItemDto> {
        val uid = requireUserId()
        val data =
            execute(
                "GET",
                "/Shows/NextUp",
                query =
                    listOf(
                        "UserId" to uid,
                        "Limit" to "$limit",
                        "Fields" to
                            "Overview,PrimaryImageAspectRatio,CommunityRating,OfficialRating," +
                            "Genres,Taglines,UserData,ParentBackdropImageTags,Path,MediaStreams",
                        "EnableImageTypes" to "Primary,Backdrop,Thumb",
                        "EnableRewatching" to "false",
                        "DisableFirstEpisode" to "false",
                    ),
            )
        return decode<ItemsResponse>(data).items
    }

    /**
     * Latest media for a library. Mirrors the Swift branch: `includeWatched`
     * uses /Items with date sorting (TV series sort by DateLastContentAdded so
     * shows with new episodes float up); otherwise /Items/Latest (which hides
     * watched by default and returns a bare array).
     */
    suspend fun getLatestMedia(
        parentId: String? = null,
        limit: Int = 16,
        includeWatched: Boolean = false,
        collectionType: String? = null,
        isYouTubeLibrary: Boolean = false,
    ): List<BaseItemDto> {
        val uid = requireUserId()

        if (includeWatched) {
            val itemTypes =
                when (collectionType?.lowercase()) {
                    "tvshows" -> if (isYouTubeLibrary) "Episode" else "Series"
                    "movies" -> "Movie"
                    else -> "Movie,Series,Episode"
                }
            val isTvSeries = collectionType?.lowercase() == "tvshows" && !isYouTubeLibrary
            val sortBy = if (isTvSeries) "DateLastContentAdded,SortName" else "DateCreated,SortName"

            val query =
                mutableListOf(
                    "Limit" to "$limit",
                    "Fields" to
                        "Overview,PrimaryImageAspectRatio,CommunityRating,OfficialRating," +
                        "Genres,Taglines,MediaStreams",
                    "EnableImageTypes" to "Primary,Backdrop,Thumb",
                    "SortBy" to sortBy,
                    "SortOrder" to "Descending",
                    "Recursive" to "true",
                    "IncludeItemTypes" to itemTypes,
                )
            parentId?.let { query.add("ParentId" to it) }

            val data = execute("GET", "/Users/$uid/Items", query)
            return decode<ItemsResponse>(data).items
        }

        val query =
            mutableListOf(
                "Limit" to "$limit",
                "Fields" to
                    "Overview,PrimaryImageAspectRatio,CommunityRating,OfficialRating," +
                    "Genres,Taglines,MediaStreams",
                "EnableImageTypes" to "Primary,Backdrop,Thumb",
            )
        parentId?.let { query.add("ParentId" to it) }
        val data = execute("GET", "/Users/$uid/Items/Latest", query)
        return decode(data)
    }

    // MARK: - Items / paging / filters

    suspend fun getItems(
        parentId: String? = null,
        includeTypes: List<ItemType>? = null,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 100,
        startIndex: Int = 0,
        isPlayed: Boolean? = null,
        isFavorite: Boolean? = null,
        isResumable: Boolean? = null,
    ): ItemsResponse {
        val uid = requireUserId()
        val query =
            mutableListOf(
                "SortBy" to sortBy,
                "SortOrder" to sortOrder,
                "Recursive" to "true",
                "Fields" to
                    "Overview,PrimaryImageAspectRatio,CommunityRating,OfficialRating," +
                    "Genres,Taglines,MediaStreams",
                "EnableImageTypes" to "Primary,Backdrop,Thumb",
                "Limit" to "$limit",
                "StartIndex" to "$startIndex",
            )
        parentId?.let { query.add("ParentId" to it) }
        includeTypes?.let { types ->
            query.add("IncludeItemTypes" to types.joinToString(",") { it.wireName })
        }
        isPlayed?.let { query.add("IsPlayed" to if (it) "true" else "false") }
        if (isFavorite == true) query.add("IsFavorite" to "true")
        if (isResumable == true) query.add("Filters" to "IsResumable")

        val data = execute("GET", "/Users/$uid/Items", query)
        return decode(data)
    }

    suspend fun search(
        query: String,
        limit: Int = 50,
    ): List<BaseItemDto> {
        val uid = requireUserId()
        val data =
            execute(
                "GET",
                "/Users/$uid/Items",
                query =
                    listOf(
                        "SearchTerm" to query,
                        "Limit" to "$limit",
                        "Fields" to
                            "Overview,PrimaryImageAspectRatio,CommunityRating,OfficialRating," +
                            "Genres,Taglines,ParentBackdropImageTags,BackdropImageTags,UserData," +
                            "ParentId,Path,MediaStreams",
                        "EnableImageTypes" to "Primary,Backdrop,Thumb",
                        "IncludeItemTypes" to "Movie,Series",
                        "Recursive" to "true",
                    ),
            )
        return decode<ItemsResponse>(data).items
    }

    /**
     * One random item for the Shuffle button. `parentId` is the library or
     * series id; `includeTypes` scopes to movies or episodes. Port of the Swift
     * getRandomItem (SortBy=Random, limit 1).
     */
    suspend fun getRandomItem(
        parentId: String,
        includeTypes: List<ItemType>,
    ): BaseItemDto? =
        getItems(
            parentId = parentId,
            includeTypes = includeTypes,
            sortBy = "Random",
            limit = 1,
        ).items.firstOrNull()

    /** Full item fetch — same Fields list as Swift (People + LocalTrailerCount). */
    suspend fun getItem(itemId: String): BaseItemDto {
        val uid = requireUserId()
        val data =
            execute(
                "GET",
                "/Users/$uid/Items/$itemId",
                query =
                    listOf(
                        "Fields" to
                            "Overview,PrimaryImageAspectRatio,CommunityRating,OfficialRating," +
                            "Genres,Taglines,People,UserData,Chapters,ParentBackdropImageTags," +
                            "RemoteTrailers,LocalTrailerCount",
                        "EnableImageTypes" to "Primary,Backdrop,Thumb",
                    ),
            )
        return decode(data)
    }

    suspend fun getSeasons(seriesId: String): List<BaseItemDto> {
        val uid = requireUserId()
        val data =
            execute(
                "GET",
                "/Shows/$seriesId/Seasons",
                query =
                    listOf(
                        "UserId" to uid,
                        "Fields" to "Overview,PrimaryImageAspectRatio",
                    ),
            )
        return decode<ItemsResponse>(data).items
    }

    suspend fun getEpisodes(
        seriesId: String,
        seasonId: String? = null,
    ): List<BaseItemDto> {
        val uid = requireUserId()
        val query =
            mutableListOf(
                "UserId" to uid,
                "Fields" to "Overview,PrimaryImageAspectRatio,CommunityRating,ImageTags,PremiereDate,MediaStreams",
                "EnableImageTypes" to "Primary,Thumb",
            )
        seasonId?.let { query.add("SeasonId" to it) }
        val data = execute("GET", "/Shows/$seriesId/Episodes", query)
        return decode<ItemsResponse>(data).items
    }

    // MARK: - Watched / favorites

    suspend fun markPlayed(itemId: String) {
        val uid = requireUserId()
        execute("POST", "/Users/$uid/PlayedItems/$itemId")
    }

    suspend fun markUnplayed(itemId: String) {
        val uid = requireUserId()
        execute("DELETE", "/Users/$uid/PlayedItems/$itemId")
    }

    suspend fun markFavorite(itemId: String) {
        val uid = requireUserId()
        execute("POST", "/Users/$uid/FavoriteItems/$itemId")
    }

    suspend fun removeFavorite(itemId: String) {
        val uid = requireUserId()
        execute("DELETE", "/Users/$uid/FavoriteItems/$itemId")
    }

    // MARK: - Detail: media info / trailers / ancestors / admin

    /**
     * Lightweight PlaybackInfo GET for the detail media badges
     * (resolution/codec/audio). No DeviceProfile — it only reads the file's
     * MediaStreams off mediaSources.first, which are unaffected by negotiation.
     * The real DeviceProfile POST negotiation the player uses is
     * [postPlaybackInfo] (driven by PlaybackEngine).
     */
    suspend fun getPlaybackInfo(itemId: String): PlaybackInfoResponse {
        val uid = requireUserId()
        val data = execute("GET", "/Items/$itemId/PlaybackInfo", query = listOf("UserId" to uid))
        return decode(data)
    }

    /**
     * The real playback negotiation: POST /Items/{id}/PlaybackInfo with the
     * Android [DeviceProfile] and the streaming caps, returning the server's
     * chosen MediaSource(s) (SupportsDirectPlay/Stream, TranscodingUrl,
     * PlaySessionId). Port of the Swift getPlaybackInfo POST. See
     * [PlaybackInfoRequest] for the body and [NegotiationFlags] for the Enable*
     * derivation (Force Direct Play vs an explicit Quality pick).
     */
    suspend fun postPlaybackInfo(
        itemId: String,
        deviceProfile: DeviceProfile,
        maxStreamingBitrate: Int,
        startTimeTicks: Long? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        forceDirectPlay: Boolean = false,
        forceTranscode: Boolean = false,
    ): PlaybackInfoResponse {
        val uid = requireUserId()
        val flags = NegotiationFlags.derive(forceDirectPlay = forceDirectPlay, forceTranscode = forceTranscode)
        val request =
            PlaybackInfoRequest(
                userId = uid,
                maxStreamingBitrate = maxStreamingBitrate,
                startTimeTicks = startTimeTicks,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                deviceProfile = deviceProfile,
                enableDirectPlay = flags.enableDirectPlay,
                enableDirectStream = flags.enableDirectStream,
                enableTranscoding = flags.enableTranscoding,
            )
        val body = playbackJson.encodeToString(request)
        val data =
            execute(
                method = "POST",
                path = "/Items/$itemId/PlaybackInfo",
                query = listOf("UserId" to uid),
                jsonBody = body,
            )
        return decode(data)
    }

    /**
     * Tears down a running server transcode. DELETE /Videos/ActiveEncodings with
     * lowercase query keys (deviceId / playSessionId), matching the Swift
     * stopActiveEncoding. Only call when a transcode was actually running.
     */
    suspend fun stopActiveEncoding(playSessionId: String) {
        execute(
            method = "DELETE",
            path = "/Videos/ActiveEncodings",
            query = listOf("deviceId" to deviceId, "playSessionId" to playSessionId),
        )
    }

    /**
     * External VTT subtitle stream URL for app/player-side rendering (never
     * burn-in). Port of the Swift SubtitleManager URL (note the itemId appears
     * twice). The api_key rides in the URL so Media3 can side-load it without
     * custom auth headers.
     */
    fun subtitleStreamUrl(
        itemId: String,
        subtitleStreamIndex: Int,
        mediaSourceId: String? = null,
    ): String? {
        val base = serverUrl ?: return null
        val token = accessToken ?: return null
        val builder =
            base.newBuilder()
                .addPathSegments("Videos/$itemId/$itemId/Subtitles/$subtitleStreamIndex/Stream.vtt")
                .addQueryParameter("api_key", token)
        mediaSourceId?.let { builder.addQueryParameter("MediaSourceId", it) }
        return builder.build().toString()
    }

    /**
     * Local trailer items (from Trailarr etc.) that Jellyfin exposes as playable
     * Trailer items. The endpoint returns a JSON array directly. Port of Swift
     * getLocalTrailers — drives the local-first Trailer button.
     */
    suspend fun getLocalTrailers(itemId: String): List<BaseItemDto> {
        val uid = requireUserId()
        val data = execute("GET", "/Users/$uid/Items/$itemId/LocalTrailers")
        return runCatching { decode<List<BaseItemDto>>(data) }.getOrDefault(emptyList())
    }

    /**
     * Ancestors of an item (used to resolve the owning library's name for the
     * Continue Watching row). Port of Swift getItemAncestors.
     */
    suspend fun getItemAncestors(itemId: String): List<BaseItemDto> {
        val uid = requireUserId()
        val data = execute("GET", "/Items/$itemId/Ancestors", query = listOf("UserId" to uid))
        return decode(data)
    }

    /** Admin: full metadata + image refresh. Port of Swift refreshMetadata. */
    suspend fun refreshMetadata(
        itemId: String,
        replaceImages: Boolean = false,
    ) {
        val query =
            mutableListOf(
                "Recursive" to "true",
                "MetadataRefreshMode" to "FullRefresh",
                "ImageRefreshMode" to "FullRefresh",
            )
        if (replaceImages) query.add("ReplaceAllImages" to "true")
        execute("POST", "/Items/$itemId/Refresh", query)
    }

    /** Admin: delete an item from the server. Port of Swift deleteItem. */
    suspend fun deleteItem(itemId: String) {
        execute("DELETE", "/Items/$itemId")
    }

    // MARK: - Playback reporting

    suspend fun reportPlaybackStart(
        itemId: String,
        positionTicks: Long = 0,
        playSessionId: String? = null,
        playMethod: String = "DirectStream",
    ) {
        val body =
            buildMap {
                put("ItemId", itemId)
                put("PositionTicks", positionTicks.toString())
                put("IsPaused", "false")
                put("PlayMethod", playMethod)
                playSessionId?.let { put("PlaySessionId", it) }
            }
        execute("POST", "/Sessions/Playing", jsonBody = encodePlaybackBody(body))
    }

    suspend fun reportPlaybackProgress(
        itemId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playSessionId: String? = null,
    ) {
        val body =
            buildMap {
                put("ItemId", itemId)
                put("PositionTicks", positionTicks.toString())
                put("IsPaused", isPaused.toString())
                playSessionId?.let { put("PlaySessionId", it) }
            }
        execute("POST", "/Sessions/Playing/Progress", jsonBody = encodePlaybackBody(body))
    }

    suspend fun reportPlaybackStopped(
        itemId: String,
        positionTicks: Long,
        playSessionId: String? = null,
    ) {
        val body =
            buildMap {
                put("ItemId", itemId)
                put("PositionTicks", positionTicks.toString())
                playSessionId?.let { put("PlaySessionId", it) }
            }
        execute("POST", "/Sessions/Playing/Stopped", jsonBody = encodePlaybackBody(body))
    }

    /**
     * Builds the playback-report JSON. PositionTicks/IsPaused are numeric/bool
     * on the wire but held as strings above; emit them unquoted so the server
     * gets the right JSON types.
     */
    private fun encodePlaybackBody(fields: Map<String, String>): String =
        fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val jsonValue =
                when {
                    value == "true" || value == "false" -> value
                    value.toLongOrNull() != null -> value
                    else -> "\"${value.replace("\"", "\\\"")}\""
                }
            "\"$key\":$jsonValue"
        }

    // MARK: - Media segments (intro-skipper)

    suspend fun getMediaSegments(itemId: String): List<MediaSegmentDto> {
        val data = execute("GET", "/Episode/$itemId/IntroSkipperSegments")
        val segmentsDict: Map<String, IntroSkipperSegment> = decode(data)
        return segmentsDict.map { (key, segment) ->
            MediaSegmentDto(
                id = "$itemId-$key",
                type = MediaSegmentType.fromKey(key),
                startSeconds = segment.start,
                endSeconds = segment.end,
            )
        }
    }

    // MARK: - URL builders

    fun buildURL(path: String): String? {
        val base = serverUrl?.toString()?.trimEnd('/') ?: return null
        val fullPath = if (path.startsWith("/")) path else "/$path"
        return base + fullPath
    }

    fun imageURL(
        itemId: String,
        imageType: String = "Primary",
        maxWidth: Int = 400,
    ): String? {
        val base = serverUrl ?: return null
        return base.newBuilder()
            .addPathSegments("Items/$itemId/Images/$imageType")
            .addQueryParameter("maxWidth", "$maxWidth")
            .build()
            .toString()
    }

    fun userImageURL(
        userId: String,
        maxWidth: Int = 100,
    ): String? {
        val base = serverUrl ?: return null
        return base.newBuilder()
            .addPathSegments("Users/$userId/Images/Primary")
            .addQueryParameter("maxWidth", "$maxWidth")
            .build()
            .toString()
    }

    fun personImageURL(
        personId: String,
        maxWidth: Int = 150,
    ): String? {
        val base = serverUrl ?: return null
        return base.newBuilder()
            .addPathSegments("Items/$personId/Images/Primary")
            .addQueryParameter("maxWidth", "$maxWidth")
            .build()
            .toString()
    }

    /**
     * Static (direct-play) stream URL. The api_key stays in the URL on purpose:
     * this is handed to the media player, which fetches the stream itself and
     * has no supported way to attach auth headers (ported comment from Swift).
     */
    fun getPlaybackURL(
        itemId: String,
        mediaSourceId: String,
        container: String? = null,
    ): String? {
        val base = serverUrl ?: return null
        val token = accessToken ?: return null
        val ext = container ?: "mp4"
        return base.newBuilder()
            .addPathSegments("Videos/$itemId/stream.$ext")
            .addQueryParameter("Static", "true")
            .addQueryParameter("MediaSourceId", mediaSourceId)
            .addQueryParameter("Container", ext)
            .addQueryParameter("api_key", token)
            .addQueryParameter("DeviceId", deviceId)
            .build()
            .toString()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** A stable per-install device id, generated once and passed in. */
        fun newDeviceId(): String = UUID.randomUUID().toString()

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
    }
}

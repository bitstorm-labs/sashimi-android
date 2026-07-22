package dev.bitstorm.sashimi.core.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Thin Retrofit surface over the Jellyfin REST API. Every call takes a fully
 * built absolute [Url] (base URL is dynamic across multiple servers, so a fixed
 * Retrofit baseUrl can't be used) and an explicit Authorization header (the
 * MediaBrowser scheme token changes per active server). Responses come back as
 * raw [ResponseBody]; [JellyfinClient] decodes them with kotlinx.serialization
 * and inspects status codes for the retry / session-expiry logic ported from
 * the Swift client.
 */
interface JellyfinApi {
    @GET
    suspend fun get(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): Response<ResponseBody>

    @POST
    suspend fun post(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @POST
    suspend fun postEmpty(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): Response<ResponseBody>

    @HTTP(method = "DELETE", hasBody = false)
    suspend fun delete(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): Response<ResponseBody>
}

package dev.bitstorm.sashimi.core.network

/**
 * Errors surfaced by [JellyfinClient]. Ported from the Swift `JellyfinError`
 * enum (Shared/Services/JellyfinClient.swift), including the user-facing
 * messages. [userMessage] mirrors Swift's `errorDescription`.
 */
sealed class JellyfinError(message: String) : Exception(message) {
    val userMessage: String get() = message ?: "Something went wrong. Please try again."

    object NotConfigured : JellyfinError("Not connected to a server. Please sign in.")

    object InvalidResponse : JellyfinError("The server returned an unexpected response. Try again.")

    object InvalidUrl : JellyfinError("Could not connect to the server. Check server address.")

    object InvalidCredentials : JellyfinError("Incorrect username or password.")

    object SessionExpired : JellyfinError("Session expired. Please sign in again.")

    object DecodingError : JellyfinError("Could not load content. Try again.")

    data class HttpError(val statusCode: Int) : JellyfinError(
        when (statusCode) {
            401, 403 -> "Session expired. Please sign in again."
            404 -> "Content not found. It may have been removed."
            in 500..599 -> "Server is having issues. Try again later."
            else -> "Something went wrong. Please try again."
        },
    )

    class NetworkError(val underlying: Throwable) :
        JellyfinError("No internet connection. Check your network.")
}

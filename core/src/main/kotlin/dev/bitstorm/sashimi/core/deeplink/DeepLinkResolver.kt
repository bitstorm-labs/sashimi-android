package dev.bitstorm.sashimi.core.deeplink

/**
 * What a `sashimi://` deep link resolves to. Port of the iOS ContentView
 * `pendingDeepLink` targets: `sashimi://play/{id}` opens the player,
 * `sashimi://item/{id}` opens the detail page.
 */
sealed interface DeepLinkTarget {
    val itemId: String

    data class Play(override val itemId: String) : DeepLinkTarget

    data class Detail(override val itemId: String) : DeepLinkTarget
}

/**
 * Pure parser for `sashimi://` deep-link URIs, kept in :core so the cold-start
 * "stash until authenticated" flow is unit-testable without Android. Accepts both
 * `sashimi://play/{id}` (host = play) and, defensively, `sashimi:play/{id}`.
 */
object DeepLinkResolver {
    private const val SCHEME = "sashimi"

    fun resolve(uri: String?): DeepLinkTarget? {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val withoutScheme =
            when {
                raw.startsWith("$SCHEME://") -> raw.removePrefix("$SCHEME://")
                raw.startsWith("$SCHEME:") -> raw.removePrefix("$SCHEME:").trimStart('/')
                else -> return null
            }
        // withoutScheme is now "<host>/<id>[/...]" — split off query/fragment first.
        val path = withoutScheme.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val host = segments[0].lowercase()
        val id = segments[1]
        if (id.isEmpty()) return null
        return when (host) {
            "play" -> DeepLinkTarget.Play(id)
            "item" -> DeepLinkTarget.Detail(id)
            else -> null
        }
    }
}

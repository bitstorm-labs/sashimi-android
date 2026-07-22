package dev.bitstorm.sashimi.core.network

/**
 * Normalizes a user-entered server address. Ported from MobileAuthView's
 * `connectToServer` (SashimiMobile/Views/Auth/MobileAuthView.swift):
 *  1. trim surrounding whitespace/newlines
 *  2. prepend `https://` when no scheme is present
 *  3. drop a single trailing slash
 *
 * Returns null for blank input. The result is a plain string; the caller feeds
 * it to OkHttp's HttpUrl parser, which is the real validity gate.
 */
fun normalizeServerUrl(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if (!s.startsWith("http://") && !s.startsWith("https://")) {
        s = "https://$s"
    }
    if (s.endsWith("/")) {
        s = s.dropLast(1)
    }
    return s
}

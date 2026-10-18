package dev.bitstorm.sashimi.core.admin

/**
 * Hides remote addresses in server text (activity entries carry
 * "IP address: a.b.c.d"). IPv4 keeps its first octet ("185.x.x.x"); IPv6 keeps
 * its first group ("2001:…").
 */
object IpRedaction {
    private val ipv4 = Regex("""(?<![\d.])(\d{1,3})\.\d{1,3}\.\d{1,3}\.\d{1,3}(?![\d.])""")
    private val ipv6 = Regex("""(?<![\w:])([0-9A-Fa-f]{0,4})((?::[0-9A-Fa-f]{0,4}){2,7})(?![\w:])""")

    fun redact(text: String?): String? {
        if (text == null) return null
        val v4 = ipv4.replace(text) { "${it.groupValues[1]}.x.x.x" }
        return ipv6.replace(v4) { match ->
            val colons = match.value.count { it == ':' }
            // "12:30:45" is a time, not an address: an address has "::" or 3+ colons.
            if (colons >= 3 || "::" in match.value) "${match.groupValues[1]}:…" else match.value
        }
    }
}

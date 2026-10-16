package sill.protocol

/** `host`, `host:port`, `[ipv6]` or `[ipv6]:port` (AddressParser.swift's forms), and a bare IPv6. */
object Address {
    fun parse(text: String, defaultPort: Int): Pair<String, Int>? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end <= 1) return null
            val rest = t.substring(end + 1)
            val port = if (rest.isEmpty()) defaultPort else rest.removePrefix(":").toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return t.substring(1, end) to port
        }
        return when (t.count { it == ':' }) {
            0 -> t to defaultPort
            1 -> {
                val port = t.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                t.substringBefore(':').takeIf { it.isNotEmpty() }?.let { it to port }
            }
            else -> t to defaultPort          // a bare IPv6 address
        }
    }
}

/** Text from a Mac or a link, safe to show: no control characters, at most [limit] characters. */
object SafeText {
    fun label(s: String, limit: Int = 64): String =
        s.filter { !it.isISOControl() && it != '\u202E' && it != '\u202D' }.trim().take(limit)
}

package sill.protocol

/**
 * A small JSON reader and writer, so the protocol module runs on the JVM (tests) and on Android
 * without org.json or a serialization plugin.
 *
 * Values: null, Boolean, Long (integers), Double, String, List<Any?>, Map<String, Any?>.
 * Maps keep their key order. The Mac's payloads are Swift JSONEncoder output, so nothing exotic.
 */
object Json {
    class ParseError(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.i != text.length) throw ParseError("trailing characters at ${p.i}")
        return v
    }

    fun parse(bytes: ByteArray): Any? = parse(String(bytes, Charsets.UTF_8))

    fun parseObject(bytes: ByteArray): Map<String, Any?>? =
        try { @Suppress("UNCHECKED_CAST") (parse(bytes) as? Map<String, Any?>) } catch (_: ParseError) { null }

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    fun bytes(value: Any?): ByteArray = write(value).toByteArray(Charsets.UTF_8)

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is UInt -> sb.append(v.toLong())
            is Float -> writeDouble(sb, v.toDouble())
            is Double -> writeDouble(sb, v)
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, e)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("not JSON: ${v::class}")
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        require(!d.isNaN() && !d.isInfinite()) { "not a JSON number: $d" }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString())
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 64) throw ParseError("nested too deep")
            if (i >= s.length) throw ParseError("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw ParseError("unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseError("bad literal at $i")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw ParseError("expected a key at $i")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw ParseError("expected ':' at $i")
                i++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (i >= s.length) throw ParseError("unexpected end in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw ParseError("expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (i >= s.length) throw ParseError("unexpected end in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw ParseError("expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseError("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseError("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseError("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw ParseError("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            var isDouble = false
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') { i++; continue }
                if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { isDouble = true; i++; continue }
                break
            }
            val t = s.substring(start, i)
            return try {
                if (isDouble) t.toDouble() else t.toLong()
            } catch (_: NumberFormatException) {
                t.toDoubleOrNull() ?: throw ParseError("bad number '$t'")
            }
        }
    }
}

// Helpers for reading parsed JSON.
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Double -> v.toLong()
    else -> null
}
fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) {
    is Long -> v.toDouble()
    is Double -> v
    else -> null
}
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>

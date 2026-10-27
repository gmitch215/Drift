package atlaslibs

class ProbeContext {
    private val lines = mutableListOf<String>()

    fun line(label: String, value: Any?) {
        lines += escape("$label = $value")
    }

    inline fun attempt(label: String, block: () -> Any?) {
        var message: String? = null
        val outcome = try {
            "ok:" + block()
        } catch (e: Exception) {
            message = e.message
            "err:" + e::class.simpleName
        }
        line(label, outcome)
        if (message != null) line("$label~msg", message)
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            if (c.code in 0x20..0x7e) {
                append(c)
            } else {
                append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
    }

    fun transcript(): String = lines.joinToString("\n", postfix = "\n")
}

class Probe(val id: String, val body: suspend ProbeContext.() -> Unit)

fun String.codes(): String = map { it.code.toString(16) }.joinToString(" ")

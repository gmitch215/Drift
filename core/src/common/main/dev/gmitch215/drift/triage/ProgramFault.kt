package dev.gmitch215.drift.triage

/**
 * Statements that put a failure in the program's own logic or text, each one a mechanism: the
 * report says the program's computation is wrong ([NotEnvSignal.LOGIC]), dereferences nothing
 * ([NotEnvSignal.NULL_DEREF]), indexes outside its data ([NotEnvSignal.BOUNDS]), loses memory
 * ([NotEnvSignal.MEMORY]), fails to produce an output it should ([NotEnvSignal.NOT_SHOWN]), or
 * names the place in the project where the defect sits ([NotEnvSignal.LOCATED]). No regex, so
 * every target reads text alike.
 */
internal object ProgramFault {
    private val logic = listOf(
        "false positive", "false negative", "incorrectly", "silently ignor", "silently fail",
        "silently drop", "off by one", "off-by-one", "off_by_one",
    )
    private val nulls = listOf("nullpointerexception", "null pointer", "nil pointer")
    private val bounds = listOf(
        "index out of range",
        "index out of bounds",
        "out of bounds",
        "arrayindexoutofbounds",
    )
    private val memory = listOf(
        "memory leak",
        "use after free",
        "use-after-free",
        "use_after_free",
        "double free",
        "heap corruption",
    )
    private val missing = listOf(
        "not rendered",
        "not displayed",
        "not shown",
        "not updated",
        "not saved",
        "disappear",
        "disappears",
    ) + listOf("doesn't", "doesnt", "does not", "didn't", "didnt", "did not").flatMap { aux ->
        listOf("show", "render", "appear", "update", "save", "display").map { "$aux $it" }
    }
    private val broken = listOf("wrong", "broken", "dead", "incorrect").flatMap { adj ->
        listOf("link", "target", "url", "anchor").map { "$adj $it" }
    }
    private val contrast = listOf(
        "works on ", "works fine", "works locally", "works for me", "works in ", "works with ",
        "worked on ", "worked in ", "worked before", "used to work", "only fails", "only on ",
        "since upgrad", "since updat", "after upgrad", "after updat", "after the update",
        "after the upgrade", "downgrad",
    )

    /** True when the text says it works somewhere else or since a change: an environment story. */
    fun contrast(text: String): Boolean {
        val low = Tokens.lower(text.replace('’', '\''))
        return contrast.any { low.contains(it) }
    }

    /** The first line of the first mechanism that fires, in a fixed order, or null. */
    fun find(lines: List<String>): Pair<NotEnvSignal, Int>? {
        val found = mutableMapOf<NotEnvSignal, Int>()
        for ((n, raw) in lines.withIndex()) {
            val low = Tokens.lower(raw.replace('’', '\''))
            fun hit(signal: NotEnvSignal, phrases: List<String>) {
                if (signal !in found && phrases.any { low.contains(it) }) found[signal] = n
            }
            hit(NotEnvSignal.LOGIC, logic)
            hit(NotEnvSignal.NULL_DEREF, nulls)
            if (NotEnvSignal.NULL_DEREF !in found && low.contains("cannot read propert") &&
                (low.contains("of undefined") || low.contains("of null"))
            ) {
                found[NotEnvSignal.NULL_DEREF] = n
            }
            hit(NotEnvSignal.BOUNDS, bounds)
            if (NotEnvSignal.BOUNDS !in found && low.contains("string index out of range")) {
                found[NotEnvSignal.BOUNDS] = n
            }
            hit(NotEnvSignal.MEMORY, memory)
            if (NotEnvSignal.NOT_SHOWN !in found && missing.any { Tokens.hasWord(low, it) }) {
                found[NotEnvSignal.NOT_SHOWN] = n
            }
            if (NotEnvSignal.LOCATED !in found && located(low)) found[NotEnvSignal.LOCATED] = n
        }
        for (signal in ORDER) found[signal]?.let { return signal to it }
        return null
    }

    private val ORDER = listOf(
        NotEnvSignal.LOGIC,
        NotEnvSignal.NULL_DEREF,
        NotEnvSignal.BOUNDS,
        NotEnvSignal.MEMORY,
        NotEnvSignal.NOT_SHOWN,
        NotEnvSignal.LOCATED,
    )

    /** A typo, a wrong link, `line N of FILE` or a permalink to a source line of the project. */
    private fun located(low: String): Boolean {
        if (Tokens.hasWord(low, "typo") || Tokens.hasWord(low, "typos") ||
            low.contains("misspell")
        ) {
            return true
        }
        if (broken.any { Tokens.hasWord(low, it) }) return true
        if (low.contains("github.com/") && low.contains("/blob/") && permalinkLine(low)) {
            return true
        }
        return lineOfFile(low)
    }

    private fun permalinkLine(low: String): Boolean {
        var at = low.indexOf("#l")
        while (at >= 0) {
            if (at + 2 < low.length && Tokens.isDigit(low[at + 2])) return true
            at = low.indexOf("#l", at + 1)
        }
        return false
    }

    private fun lineOfFile(low: String): Boolean {
        var at = low.indexOf("line ")
        while (at >= 0) {
            var end = at + "line ".length
            val start = end
            while (end < low.length && Tokens.isDigit(low[end])) end++
            if (end > start && low.startsWith(" of ", end)) {
                val name = low.substring(end + " of ".length).takeWhile { it != ' ' && it != ',' }
                val dot = name.lastIndexOf('.')
                if (dot > 0 && dot < name.length - 1) return true
            }
            at = low.indexOf("line ", at + 1)
        }
        return false
    }
}

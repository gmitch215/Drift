package dev.gmitch215.drift.triage

import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

/** The signal behind a [NotEnvironment] reading, in the order they are tried. */
enum class NotEnvSignal(val id: String) {
    FRAME("frame"),
    MISMATCH("mismatch"),
    REGRESSION("regression"),
    CRASH("crash"),
    LOGIC("logic"),
    NULL_DEREF("null-deref"),
    BOUNDS("bounds"),
    MEMORY("memory"),
    NOT_SHOWN("not-shown"),
    LOCATED("located"),
    CLEAN_RUN("clean-run"),
}

/**
 * A line that puts the failure in the program: a stack frame in project code ([NotEnvSignal.FRAME],
 * [frame] is `file:line`), an expected and an actual value that differ, the word regression, or a
 * crash reported with an exception or a stack trace ([NotEnvSignal.CRASH]), or a statement from
 * [ProgramFault] that the program's own logic or text is at fault.
 * [line] is 1-based in the text that was read; [text] is that line, trimmed and clipped.
 */
data class NotEnvironment(
    val signal: NotEnvSignal,
    val line: Int,
    val frame: String?,
    val text: String,
) {
    fun toJson(): JsonObject = obj(
        "signal" to JsonString(signal.id),
        "line" to JsonInt(line.toLong()),
        "frame" to (frame?.let(::JsonString) ?: JsonNull),
        "text" to JsonString(text),
    )

    companion object {
        private const val MAX_TEXT = 160
        private val PROJECT_JS = setOf("js", "mjs", "cjs", "ts", "tsx", "jsx", "rs")
        private val PROJECT_JVM = setOf("kt", "java", "groovy", "scala")
        private val NOT_PROJECT_PATH = listOf(
            "node_modules", "site-packages", "dist-packages", "/usr/", "/opt/", "/system/",
            "/library/", "<frozen", "node:", "internal/", "/rustc/", ".cargo", "/vendor/",
            "\\vendor\\", "/lib/python",
        )
        private val NOT_PROJECT_CLASS = listOf(
            "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.junit.", "org.gradle.",
            "org.apache.", "com.google.", "io.netty.",
        )

        private val CRASH_WORDS = listOf("crash")
        private val TRACE_WORDS = listOf(
            "exception",
            "traceback",
            "segmentation fault",
            "segfault",
            "access violation",
            "panicked",
            "stack trace",
        )

        /**
         * The first frame in project code, else the first mismatch, else the first regression
         * word, else the first line that reports a crash when the text also holds an exception
         * or a stack trace. [strict] is true when the text states no environment fact on the
         * failing side; only then do the first three apply. [crash] is true when the text states
         * nothing but the operating system, which every bug template asks for. [program] is true
         * when the text tells no environment story (no passing side, no requirement, no limit
         * hit, no "works elsewhere"); the [ProgramFault] mechanisms then apply. Null when no line
         * carries one of these; the caller then keeps its answer.
         */
        fun find(
            lines: List<String>,
            strict: Boolean = true,
            crash: Boolean = false,
            program: Boolean = false,
        ): NotEnvironment? {
            var mismatch: NotEnvironment? = null
            var regression: NotEnvironment? = null
            var crashed: NotEnvironment? = null
            var traced = false
            for ((n, raw) in lines.withIndex()) {
                val line = raw.trim()
                val low = Tokens.lower(line)
                if (strict) {
                    projectFrame(line)?.let { return reading(NotEnvSignal.FRAME, n, line, it) }
                }
                if (crashed == null && CRASH_WORDS.any { low.contains(it) }) {
                    crashed = reading(NotEnvSignal.CRASH, n, line, null)
                }
                if (TRACE_WORDS.any { low.contains(it) }) traced = true
                if (isFrame(line)) continue
                if (strict && mismatch == null && isMismatch(line)) {
                    mismatch = reading(NotEnvSignal.MISMATCH, n, line, null)
                }
                if (strict && regression == null && low.contains("regression")) {
                    regression = reading(NotEnvSignal.REGRESSION, n, line, null)
                }
            }
            val fault = if (program) ProgramFault.find(lines) else null
            return mismatch ?: regression ?: crashed.takeIf { crash && traced }
                ?: fault?.let { (signal, n) -> reading(signal, n, lines[n].trim(), null) }
        }

        private fun reading(signal: NotEnvSignal, n: Int, line: String, frame: String?) =
            NotEnvironment(
                signal,
                n + 1,
                frame,
                if (line.length > MAX_TEXT) line.take(MAX_TEXT) + "..." else line,
            )

        private fun isFrame(line: String) = line.startsWith("at ") || line.startsWith("File \"")

        /** A Markdown heading or a bold-only line is a template label, not a pair of values. */
        private fun isHeading(line: String) = line.startsWith("#") ||
            (line.length > 4 && line.startsWith("**") && line.endsWith("**"))

        private fun isMismatch(line: String): Boolean {
            if (isHeading(line)) return false
            val l = Tokens.lower(line)
            val assert = l.indexOf("assert")
            val failOrError = l.indexOf("fail", assert) >= 0 || l.indexOf("error", assert) >= 0
            if (assert >= 0 && failOrError) {
                return true
            }
            val labels = listOf("expected:", "actual:", "expected vs actual")
            if (labels.any { l.contains(it) }) {
                return true
            }
            val expected = l.indexOf("expected")
            return expected >= 0 && listOf("got", "received", "but was", "but found", "actual")
                .any { wordFrom(l, it, expected) }
        }

        private fun wordFrom(text: String, word: String, from: Int): Boolean {
            var i = text.indexOf(word, from)
            while (i >= 0) {
                val before = if (i == 0) ' ' else text[i - 1]
                val end = i + word.length
                val after = if (end >= text.length) ' ' else text[end]
                if (!Tokens.isAlnum(before) && !Tokens.isAlnum(after)) return true
                i = text.indexOf(word, i + 1)
            }
            return false
        }

        private fun projectFrame(line: String): String? {
            if (line.startsWith("File \"")) return pythonFrame(line)
            if (!line.startsWith("at ")) return null
            val inParens = line.endsWith(")") && line.contains('(')
            val where = if (inParens) line.substringAfterLast('(').dropLast(1) else line.drop(3)
            val (path, lineNo) = location(where) ?: return null
            if (!isProjectPath(path)) return null
            val ext = path.substringAfterLast('.', "")
            if (ext in PROJECT_JS) return "$path:$lineNo"
            if (ext in PROJECT_JVM && inParens) {
                val klass = line.drop(3).substringBefore('(')
                if (NOT_PROJECT_CLASS.any { klass.startsWith(it) }) return null
                return "$path:$lineNo"
            }
            return null
        }

        private fun pythonFrame(line: String): String? {
            val close = line.indexOf('"', 6)
            if (close < 0) return null
            val path = line.substring(6, close)
            val rest = line.substring(close + 1)
            if (!rest.startsWith(", line ")) return null
            val lineNo = rest.removePrefix(", line ").takeWhile { it in '0'..'9' }
            if (lineNo.isEmpty() || !isProjectPath(path)) return null
            return "$path:$lineNo"
        }

        /** `path:line` or `path:line:column`; a path with a colon of its own is not read. */
        private fun location(where: String): Pair<String, String>? {
            val parts = where.split(':')
            if (parts.size !in 2..3) return null
            val lineNo = parts[parts.size - if (parts.size == 3) 2 else 1]
            if (lineNo.isEmpty() || !lineNo.all { it in '0'..'9' }) return null
            if (parts.size == 3 && !parts[2].all { it in '0'..'9' }) return null
            return parts[0] to lineNo
        }

        private fun isProjectPath(path: String): Boolean {
            if (path.isEmpty()) return false
            val lower = Tokens.lower(path)
            return NOT_PROJECT_PATH.none { lower.contains(it) }
        }
    }
}

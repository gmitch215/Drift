package dev.gmitch215.drift.triage

import dev.gmitch215.drift.lab.ImageTags
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.symptom.Signature
import dev.gmitch215.drift.symptom.SymptomExtractor
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.math.abs

/** How the output of a clean run relates to the failure in the report. */
enum class CleanMatch(val id: String) {
    SAME("same"),
    WORKS("works"),
    DIFFERENT("different"),
}

/**
 * [seen] is the first failing line of the output and [wanted] the failure of the report, both
 * normalised and clipped; neither is stored.
 */
class CleanRunReading(val match: CleanMatch, val seen: String?, val wanted: String?)

/**
 * The failure a report shows: the normalised lines of its error symptoms and `run:signature`
 * fact, and the signal and transport symptoms as `kind:value`. [first] is the 0-based line of the
 * earliest one, or -1.
 */
class ReportFailure internal constructor(
    internal val lines: List<String>,
    internal val tags: Set<String>,
    internal val first: Int,
    internal val shown: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty() && tags.isEmpty()

    internal fun matches(normal: String) = lines.any {
        normal == it || (it.length >= MIN_CONTAINED && normal.contains(it))
    }

    private companion object {
        const val MIN_CONTAINED = 12
    }
}

/**
 * The clean-run check: run the report's own command in the official image of its toolchain, with
 * nothing of the reporter's machine around it, and compare what it prints with the failure in the
 * report. Drift prints the command and never runs it.
 */
object CleanRun {
    const val FAMILY = "clean-run"

    /** Asked when the report shows a command and no error: its output gives the failure. */
    const val ERROR_TEXT = "error-text"

    /** The fact a reply leaves in the session: [CleanMatch.id] of the reading. */
    const val KEY = "run:clean"

    private const val MAX_COMMAND = 400
    private const val MAX_LINE = 4096
    private const val SEEN_LENGTH = 120
    private const val BAD_SHARE = 10
    private const val GAP = 1000
    private const val MAX_GAP = 40

    // #region the failure of a report

    private fun pathless(text: String): String = text.split(' ').joinToString(" ") { word ->
        val core = word.trim('\'', '"', '(', ')', '[', ']', ',', ';', '`')
        if (core.length > 1 && (core.contains('/') || core.contains('\\'))) {
            word.replace(core, "<path>")
        } else {
            word
        }
    }

    private fun normal(line: String) = pathless(Signature.normalize(line))

    /** The failure in [text], read like a log and through the `run:signature` fact of [facts]. */
    internal fun failure(text: String, facts: List<Fact>): ReportFailure {
        val lines = mutableListOf<String>()
        val tags = mutableSetOf<String>()
        var first = -1
        var shown: String? = null
        fun at(line: Int) {
            if (first < 0 || line < first) first = line
        }
        for (s in SymptomExtractor.extract(text)) {
            when (s.kind) {
                SymptomKind.SIGNATURE -> {
                    lines += pathless(s.value)
                    if (shown == null) shown = s.value
                    at(s.line - 1)
                }

                SymptomKind.SIGNAL, SymptomKind.TRANSPORT -> {
                    tags += "${s.kind}:${s.value}"
                    at(s.line - 1)
                }

                else -> Unit
            }
        }
        facts.firstOrNull { it.key == "run:signature" && it.side == Side.FAILING }?.let {
            lines += pathless(it.value)
            if (shown == null) shown = it.value
            at(it.span.line - 1)
        }
        return ReportFailure(lines.distinct().filter { it.isNotEmpty() }, tags, first, shown)
    }

    /** The failure of the (already redacted) report text, as `drift why --reply` reads it. */
    fun failure(report: String): ReportFailure {
        val doc = Doc(report)
        return failure(report, Combine.facts(Extractor(doc).run()))
    }

    // #endregion

    // #region reading the output

    private val failWords = listOf(
        "error",
        "exception",
        "traceback",
        "panic",
        "fatal",
        "segmentation fault",
        "npm err",
        "cannot find",
        "no such file",
        "not found",
        "failed",
        "failure",
    )

    private fun failed(low: String) =
        failWords.any { low.contains(it) } && !low.contains("0 failed")

    /**
     * Compares the [output] of the clean run with [failure]: [CleanMatch.SAME] when a line of it
     * (or a signal or transport symptom) is the report's failure, [CleanMatch.DIFFERENT] when it
     * prints some other error, else [CleanMatch.WORKS], which an empty output also is.
     */
    fun read(failure: ReportFailure, output: String): CleanRunReading {
        var other: String? = null
        val wanted = (failure.shown ?: failure.tags.firstOrNull())?.take(SEEN_LENGTH)
        for (raw in output.lineSequence()) {
            val line = SymptomExtractor.stripAnsi(raw).trim()
            if (line.isEmpty()) continue
            val cut = if (line.length > MAX_LINE) line.substring(0, MAX_LINE) else line
            val n = normal(cut)
            if (failure.matches(n)) {
                return CleanRunReading(CleanMatch.SAME, n.take(SEEN_LENGTH), wanted)
            }
            if (other == null && failed(Tokens.lower(cut))) {
                other = Signature.normalize(cut).take(SEEN_LENGTH)
            }
        }
        for (s in SymptomExtractor.extract(output)) {
            val tag = "${s.kind}:${s.value}"
            if (tag in failure.tags) return CleanRunReading(CleanMatch.SAME, s.value, wanted)
            val error = s.kind == SymptomKind.SIGNATURE || s.kind == SymptomKind.SIGNAL ||
                s.kind == SymptomKind.TRANSPORT
            if (other == null && error) other = s.value.take(SEEN_LENGTH)
        }
        return if (other == null) {
            CleanRunReading(CleanMatch.WORKS, null, wanted)
        } else {
            CleanRunReading(CleanMatch.DIFFERENT, other, wanted)
        }
    }

    /** True when [output] is binary or mostly control characters, so no reading applies. */
    fun isBinary(output: String): Boolean {
        if (output.isEmpty()) return false
        if (output.contains('\u0000')) return true
        val bad = output.count {
            (it < ' ' && it != '\n' && it != '\r' && it != '\t' && it != '\u001B') || it == '\uFFFD'
        }
        return bad * BAD_SHARE > output.length
    }

    // #endregion

    // #region the command

    /**
     * [command] as one single-quoted shell word, or null when it is not safe to print: empty, over
     * [MAX_COMMAND] characters, a control or non-ASCII character, a backtick, `$(`, a masked
     * secret or a trailing backslash. An embedded single quote becomes `'\''`.
     */
    fun quote(command: String): String? {
        if (command.isBlank() || command.length > MAX_COMMAND) return null
        if (command.any { (it < ' ' && it != '\t') || it > '~' }) return null
        if (command.contains('`') || command.contains("$(") || command.contains(Redactor.MASK)) {
            return null
        }
        if (command.trimEnd().endsWith("\\")) return null
        return "'" + command.replace("'", "'\\''") + "'"
    }

    private val skipped = setOf("sudo", "time", "env", "exec", "nohup")

    private class Candidate(val line: Int, val text: String)

    private fun promptCommand(raw: String): String? {
        val t = raw.trim()
        val text = when {
            t.startsWith("$ ") -> t.substring(2)
            t.startsWith("> ") -> t.substring(2)
            t.startsWith("% ") -> t.substring(2)
            else -> return null
        }.trim()
        if (text.isEmpty()) return null
        val first = text.substringBefore(' ')
        val command = knownProgram(text) || text.startsWith("./")
        if (!t.startsWith("$") && (first.contains('@') || !command)) return null
        return text
    }

    /** The first word that names the program: no `sudo`, no `NAME=value`, no directory. */
    private fun program(command: String): String {
        for (w in command.trim().split(' ', '\t').filter { it.isNotEmpty() }) {
            val assign = w.indexOf('=')
            val name = if (assign > 0) w.substring(0, assign) else ""
            val variable = name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }
            if (w in skipped || variable) continue
            return w.substringAfterLast('/')
        }
        return ""
    }

    private fun chainOf(word: String): Chain? {
        val lower = Tokens.lower(word)
        val direct = chains.firstOrNull { lower in it.words }
        if (direct != null) return direct
        val stem = lower.trimEnd { it.isDigit() || it == '.' }
        return chains.firstOrNull { stem.isNotEmpty() && stem in it.words }
    }

    /** A line is a command by itself only when its program is a known toolchain program. */
    private fun knownProgram(command: String) = chainOf(program(command)) != null

    private val runVerbs = listOf("run", "ran", "running", "executing", "typing", "command:")

    private val knownPrograms: Set<String> by lazy {
        chains.flatMap { it.words }.toSet() + Asks.toolCommands.map { it.first() } +
            setOf("make", "gcc", "g++", "tsc", "mix")
    }

    /** The command in an inline code span that follows a run verb, when its program is known. */
    private fun inlineCommand(line: String): String? {
        val low = Tokens.lower(line)
        for (verb in runVerbs) {
            var at = low.indexOf(verb)
            while (at >= 0) {
                val end = at + verb.length
                val bare = Tokens.wordAt(low, at, verb.removeSuffix(":")) &&
                    (verb.endsWith(":") || Tokens.endsAt(low, end))
                if (bare) {
                    val open = line.indexOf('`', end)
                    if (open in end..end + MAX_GAP && !line.startsWith("``", open)) {
                        val close = line.indexOf('`', open + 1)
                        val text = if (close > open) line.substring(open + 1, close).trim() else ""
                        if (text.isNotEmpty() && Tokens.lower(program(text)) in knownPrograms) {
                            return text
                        }
                    }
                }
                at = low.indexOf(verb, at + 1)
            }
        }
        return null
    }

    private fun candidates(doc: Doc, sig: ReportFailure): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (i in doc.lines.indices) {
            if (doc.inCode[i]) promptCommand(doc.lines[i])?.let { out += Candidate(i, it) }
        }
        for (i in doc.lines.indices) {
            if (!doc.inCode[i]) inlineCommand(doc.lines[i])?.let { out += Candidate(i, it) }
        }
        var i = 0
        val blocks = mutableListOf<Pair<Int, Int>>()
        while (i < doc.lines.size) {
            if (!doc.fence[i]) {
                i++
                continue
            }
            var j = i + 1
            while (j < doc.lines.size && !doc.fence[j]) j++
            blocks += i to j
            i = j + 1
        }
        for ((n, block) in blocks.withIndex()) {
            val body = (block.first + 1 until minOf(block.second, doc.lines.size))
                .filter { doc.lines[it].isNotBlank() }
            if (body.size != 1 || promptCommand(doc.lines[body[0]]) != null) continue
            val text = doc.lines[body[0]].trim()
            val carries = blocks.drop(n + 1).any { later ->
                (later.first + 1 until minOf(later.second, doc.lines.size)).any {
                    sig.matches(normal(doc.lines[it].trim()))
                }
            }
            if (carries && knownProgram(text)) out += Candidate(body[0], text)
        }
        return out.sortedBy { it.line }
    }

    // #endregion

    // #region the image

    private class Chain(
        val key: String,
        val repo: String,
        val rest: String,
        val words: Set<String>,
        val families: Set<String>,
        val parts: Int? = null,
    )

    private val chains = listOf(
        Chain(
            "node",
            "node",
            "-bookworm-slim",
            setOf("node", "npm", "npx", "yarn", "pnpm", "corepack"),
            setOf("tool.node", "tool.npm", "tool.pnpm", "tool.yarn"),
        ),
        Chain(
            "python",
            "python",
            "-slim-bookworm",
            setOf("python", "python3", "pip", "pip3", "pytest", "poetry", "uv"),
            setOf("tool.python", "tool.pip", "tool.poetry"),
        ),
        Chain(
            "java",
            "eclipse-temurin",
            "-jdk",
            setOf("java", "javac", "jar", "mvn", "mvnw", "gradle", "gradlew"),
            setOf("tool.java", "tool.gradle", "tool.mvn"),
        ),
        Chain("go", "golang", "-bookworm", setOf("go"), setOf("tool.go")),
        Chain(
            "php",
            "php",
            "-cli-bookworm",
            setOf("php", "composer", "phpunit"),
            setOf("tool.php", "tool.composer"),
        ),
        Chain(
            "rust",
            "rust",
            "-slim-bookworm",
            setOf("cargo", "rustc", "rustup"),
            setOf("tool.rust", "tool.cargo"),
        ),
        Chain(
            "ruby",
            "ruby",
            "-slim-bookworm",
            setOf("ruby", "irb", "gem", "bundle", "bundler", "rake"),
            setOf("tool.ruby"),
        ),
        Chain("cc", "gcc", "", setOf("gcc", "g++", "cc", "c++"), setOf("tool.cc"), parts = 2),
        Chain(
            "elixir",
            "elixir",
            "",
            setOf("elixir", "elixirc", "iex", "mix"),
            emptySet(),
        ),
    )

    /** The program words that choose an image, for the test that matches them with the table. */
    internal val programs: Map<String, Set<String>> get() = chains.associate { it.key to it.words }

    private fun numbers(version: String): List<Int>? {
        val bare = version.trim().removePrefix("v")
        val digits = bare.takeWhile { it.isDigit() || it == '.' }.trim('.')
        if (digits.isEmpty()) return null
        return digits.split('.').map { it.toIntOrNull() ?: return null }
    }

    private fun compare(x: List<Int>, y: List<Int>): Int {
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** The newest published tag of [chain] for the stated [version], or the newest when null. */
    internal fun image(chain: String, version: String?): String? {
        val c = chains.firstOrNull { it.key == chain } ?: return null
        val published = ImageTags.repositories[c.repo]?.get(c.rest) ?: return null
        val pool = published.mapNotNull { v ->
            val n = if (v.all { it.isDigit() || it == '.' }) numbers(v) else null
            n?.takeIf { c.parts == null || it.size == c.parts }?.let { v to it }
        }
        var stated = version?.let { numbers(it) }
        if (version != null && stated == null) return null
        if (c.key == "java" && stated != null && stated.size > 1 && stated[0] == 1) {
            stated = listOf(stated[1])
        }
        val wanted = if (stated == null) {
            pool
        } else {
            pool.filter { it.second.take(stated.size) == stated }
                .ifEmpty { nearest(pool.filter { it.second[0] == stated[0] }, stated) }
        }
        val best = wanted.maxWithOrNull { a, b -> compare(a.second, b.second) } ?: return null
        return "${c.repo}:${best.first}${c.rest}"
    }

    /** The published versions closest to [stated] in minor then patch, the newest of those. */
    private fun nearest(
        pool: List<Pair<String, List<Int>>>,
        stated: List<Int>,
    ): List<Pair<String, List<Int>>> {
        fun gap(v: List<Int>, i: Int) = abs(v.getOrElse(i) { 0 } - stated.getOrElse(i) { 0 })
        val best = pool.minOfOrNull { gap(it.second, 1) * GAP + gap(it.second, 2) } ?: return pool
        return pool.filter { gap(it.second, 1) * GAP + gap(it.second, 2) == best }
    }

    private fun toolchain(doc: Doc, command: String): Chain? {
        chainOf(program(command))?.let { return it }
        val word = program(command)
        val scripted = command.trim().startsWith("./") || word.endsWith(".sh") ||
            word in setOf("make", "sh", "bash")
        if (!scripted) return null
        return Asks.mentions(doc).firstNotNullOfOrNull { m ->
            chains.firstOrNull { m.row.family in it.families }
        }
    }

    // #endregion

    /**
     * The clean-run ask for [doc], or null: the report needs a failure signature, a command in a
     * code block (`$ cmd`, `> cmd`, or the one command of a block that precedes the error block),
     * a safe form of that command and a toolchain with an official image.
     */
    internal fun derive(doc: Doc, facts: List<Fact>): Ask? {
        val sig = failure(doc.lines.joinToString("\n"), facts)
        val command = candidates(doc, sig).lastOrNull { sig.first < 0 || it.line <= sig.first }
            ?: return null
        if (quote(command.text) == null) return null
        val chain = toolchain(doc, command.text) ?: return null
        val stated = facts.firstOrNull {
            it.side == Side.FAILING && it.key == "tool.${chain.key}.version" &&
                it.value != "exceeded"
        }?.value
        val tag = image(chain.key, stated) ?: return null
        if (sig.isEmpty) {
            return Ask(
                ERROR_TEXT,
                listOf("COMMAND"),
                "the report shows a command but no error it printed, and without one a clean run " +
                    "cannot be compared",
            )
        }
        val note = if (stated == null) " (it states no version, so this is the newest)" else ""
        return Ask(
            FAMILY,
            argv(tag, command.text, network(doc, command.text)),
            "the report shows the command failing, and a clean $tag container$note shows " +
                "whether the failure depends on your machine",
        )
    }

    private val networkWords = listOf(
        "http", "https", "url", "install", "clone", "curl", "wget", "fetch", "download",
        "registry", "proxy", "socket", "network", "dns", "request", "api", "connect", "ssl",
        "tls", "timeout", "upload", "remote",
    )
    private val fetching = setOf(
        "npm", "yarn", "pnpm", "npx", "pip", "pip3", "poetry", "uv", "composer", "cargo", "go",
        "gradle", "gradlew", "mvn", "mvnw", "bundle", "gem", "mix", "dotnet", "git", "docker",
        "curl", "corepack", "rustup",
    )

    /** True when the report shows network use or the command is a package or build manager. */
    private fun network(doc: Doc, command: String): Boolean =
        Tokens.lower(program(command)) in fetching ||
            doc.lower.any { low -> networkWords.any { Tokens.hasWord(low, it) } }

    private const val COPY = "cp -R /src/. /w/ && "

    /**
     * The printed `docker run`: the working directory read-only at `/src`, copied into a
     * tmpfs `/w`, no privileges, limited processes and memory, no network unless [network].
     * Only the last argument holds text of the report, as one quoted word.
     */
    internal fun argv(tag: String, command: String, network: Boolean): List<String> = listOf(
        "docker", "run", "--rm", "--user", "\"\$(id -u):\$(id -g)\"", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges", "--pids-limit", "256", "--memory", "1g",
    ) + (if (network) emptyList() else listOf("--network", "none")) + listOf(
        "-v", "\"\$PWD\":/src:ro", "--tmpfs", "/w:exec,mode=1777", "-w", "/w", tag, "sh",
        "-c", "'" + (COPY + command).replace("'", "'\\''") + "'",
    )

    /**
     * The not-environment reading a reply of [CleanMatch.SAME] gives, or null. [facts] hold the
     * reply; [doc] gives the report line the failure came from.
     */
    internal fun confirmed(doc: Doc, facts: List<Fact>): NotEnvironment? {
        if (facts.none { it.key == KEY && it.value == CleanMatch.SAME.id }) return null
        val sig = failure(doc.lines.joinToString("\n"), facts)
        val line = sig.first.coerceAtLeast(0)
        val text = doc.lines.getOrElse(line) { "" }.trim()
        return NotEnvironment(
            NotEnvSignal.CLEAN_RUN,
            line + 1,
            null,
            if (text.length > SEEN_LENGTH) text.take(SEEN_LENGTH) + "..." else text,
        )
    }
}

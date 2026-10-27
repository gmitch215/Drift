package dev.gmitch215.drift.scan.scanner

import dev.gmitch215.drift.ci.LooseJson
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.TIMED_OUT_EXIT_CODE
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.scan.handsoff.Checkout
import dev.gmitch215.drift.scan.handsoff.HostCheckout

/**
 * Versions the [ToolRegistry] tools that the project names: package scripts, Makefile recipes,
 * Dockerfile `RUN` lines, workflow `run:` steps and shebangs. A registry tool runs only when
 * `PATH` resolves it outside the project folder; it runs by that absolute path, with no shell,
 * a 5 s timeout and a 64 KiB output cap. Names are only read as text and never run.
 */
internal object ProjectTools {
    private const val TIMEOUT_MILLIS = 5_000L
    private const val MAX_OUTPUT = 64 * 1024
    private val MAKEFILES = listOf("Makefile", "GNUmakefile", "makefile")
    private val RUN_LINE = Regex("^\\s*RUN\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val RUN_KEY = Regex("^(\\s*)(?:-\\s+)?run:\\s*(.*)$")
    private val SEPARATORS = Regex("&&|\\|\\||[;|&()`]|\\$\\(")
    private val WHITESPACE = Regex("\\s+")
    private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")
    private val NAME = Regex("[A-Za-z0-9._+-]+")
    private val SCRIPT_FILE = Regex("[^.]+|.+\\.sh")
    private val KEYWORDS = setOf(
        "env", "sudo", "exec", "time", "nohup", "command", "nice",
        "if", "then", "else", "do", "while", "until",
    )

    fun scan(host: Host): List<Attribute> {
        if (host.os.startsWith("windows")) return emptyList()
        val wanted = projectNames(HostCheckout(host, "")).let { named ->
            ToolRegistry.all.filter { it.tool in named }
        }
        if (wanted.isEmpty()) return emptyList()
        val project = projectDir(host) ?: return emptyList()
        return wanted.flatMap { version(host, it, project) }
    }

    /** Command names the project's own files mention, before the registry filters them. */
    internal fun projectNames(checkout: Checkout): Set<String> {
        val lines = mutableListOf<String>()
        checkout.read("package.json")?.let { lines += packageScripts(it) }
        MAKEFILES.firstNotNullOfOrNull { checkout.read(it) }?.let { text ->
            lines += text.lines().filter { it.startsWith("\t") }
                .map { it.trimStart().trimStart('@', '-', '+') }
        }
        checkout.read("Dockerfile")?.let { text ->
            lines += text.lines().mapNotNull { RUN_LINE.find(it)?.groupValues[1] }
        }
        for (name in checkout.list(".github/workflows").orEmpty()) {
            if (!name.endsWith(".yml") && !name.endsWith(".yaml")) continue
            checkout.read(".github/workflows/$name")?.let { lines += workflowRuns(it) }
        }
        val scriptPaths = checkout.list(".").orEmpty() +
            checkout.list("scripts").orEmpty().map { "scripts/$it" }
        for (path in scriptPaths) {
            if (!SCRIPT_FILE.matches(path.substringAfterLast('/'))) continue
            val first = checkout.read(path)?.lineSequence()?.firstOrNull() ?: continue
            if (first.startsWith("#!")) lines += first.removePrefix("#!")
        }
        return lines.flatMap(::commandWords).toSet()
    }

    internal fun commandWords(line: String): List<String> =
        SEPARATORS.split(line).mapNotNull(::commandWord)

    private fun commandWord(segment: String): String? {
        for (token in segment.trim().split(WHITESPACE)) {
            val word = token.trim('"', '\'').trimStart('@', '+')
            val name = word.substringAfterLast('/')
            when {
                word.isEmpty() || word.startsWith('-') || ASSIGNMENT.matches(word) -> continue
                name in KEYWORDS -> continue
                NAME.matches(name) -> return name
                else -> return null
            }
        }
        return null
    }

    private fun packageScripts(text: String): List<String> {
        val root = try {
            LooseJson.parse(text) as? JsonObject
        } catch (_: JsonException) {
            null
        }
        val scripts = root?.get("scripts") as? JsonObject ?: return emptyList()
        return scripts.fields.values.mapNotNull { (it as? JsonString)?.value }
    }

    /** The `run:` commands of a workflow, inline or in a `|` or `>` block. */
    internal fun workflowRuns(text: String): List<String> {
        val lines = text.lines()
        val out = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val match = RUN_KEY.find(line) ?: continue
            val indent = match.groupValues[1].length
            val value = match.groupValues[2]
            if (value.startsWith("|") || value.startsWith(">")) {
                for (next in lines.subList(i + 1, lines.size)) {
                    val depth = next.indexOfFirst { !it.isWhitespace() }
                    if (depth >= 0 && depth <= indent) break
                    out += next
                }
            } else {
                out += value.trim('"', '\'')
            }
        }
        return out
    }

    private fun projectDir(host: Host): String? = host.run(listOf("pwd", "-P"))
        ?.takeIf { it.exitCode == 0 }?.output?.trim()?.takeIf { it.startsWith("/") }

    private fun version(host: Host, scanner: Scanner, project: String): List<Attribute> {
        val path = resolve(host, scanner.tool, project) ?: return emptyList()
        val out = StringBuilder()
        var oversize = false
        val result = host.run(listOf(path) + scanner.command.drop(1), TIMEOUT_MILLIS) { chunk ->
            if (out.length + chunk.length > MAX_OUTPUT) {
                oversize = true
            } else {
                out.append(chunk)
            }
        } ?: return emptyList()
        if (oversize || result.exitCode == TIMED_OUT_EXIT_CODE) return emptyList()
        if (result.exitCode == Scanners.ABSENT) return emptyList()
        return Scanners.attributes(scanner, out.toString())
    }

    /** The absolute path `PATH` gives [name], or null when it is absent or inside [project]. */
    private fun resolve(host: Host, name: String, project: String): String? {
        val found = host.run(listOf("which", name))?.takeIf { it.exitCode == 0 }
            ?.output?.lines()?.firstOrNull()?.trim()
        if (found.isNullOrEmpty() || !found.startsWith("/")) return null
        if (found.startsWith(project.trimEnd('/') + "/")) return null
        return found
    }
}

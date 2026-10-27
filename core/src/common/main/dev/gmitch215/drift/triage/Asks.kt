package dev.gmitch215.drift.triage

/** One row of the ask table: the check for a family, run where the program fails. */
internal class AskRow(
    val family: String,
    val label: String,
    val command: List<String>,
    val names: List<String>,
    val stated: List<String> = listOf("$family.version", "$family.installed"),
    val gate: List<String> = emptyList(),
)

/**
 * Derives the checks a report leaves open from its text and facts. Every ask comes from a row of
 * a fixed table, so no other command is ever printed, and a family the report already states on
 * the failing side is never asked for. The tool rows repeat the argument vectors of the scanners
 * (`Scanners.all`, `ToolRegistry`); a test in `scan` keeps the two in step.
 */
object Asks {
    private val tools: List<AskRow> = listOf(
        row("node", "Node", listOf("node", "--version"), "node", "nodejs", "node.js"),
        row("npm", "npm", listOf("npm", "--version"), "npm"),
        row("pnpm", "pnpm", listOf("pnpm", "--version"), "pnpm"),
        row("yarn", "Yarn", listOf("yarn", "--version"), "yarn"),
        row("bun", "Bun", listOf("bun", "--version"), "bun"),
        row("deno", "Deno", listOf("deno", "--version"), "deno"),
        row(
            "python",
            "Python",
            listOf("python3", "--version"),
            "python",
            "python3",
            "cpython",
        ),
        row("pip", "pip", listOf("pip", "--version"), "pip", "pip3"),
        row("poetry", "Poetry", listOf("poetry", "--version"), "poetry"),
        row("php", "PHP", listOf("php", "-v"), "php"),
        row("composer", "Composer", listOf("composer", "--version"), "composer"),
        row(
            "java",
            "Java",
            listOf("java", "-version"),
            "java",
            "jdk",
            "jre",
            "openjdk",
            "temurin",
            "corretto",
        ),
        row("kotlin", "Kotlin", listOf("kotlinc", "-version"), "kotlin", "kotlinc"),
        row("gradle", "Gradle", listOf("gradle", "--version"), "gradle"),
        row("mvn", "Maven", listOf("mvn", "--version"), "maven", "mvn"),
        row("go", "Go", listOf("go", "version"), "golang"),
        row("rust", "Rust", listOf("rustc", "--version"), "rustc"),
        row("cargo", "Cargo", listOf("cargo", "--version"), "cargo"),
        row("ruby", "Ruby", listOf("ruby", "--version"), "ruby"),
        row("dotnet", ".NET", listOf("dotnet", "--version"), "dotnet", ".net"),
        row("git", "Git", listOf("git", "--version"), "git"),
        row("docker", "Docker", listOf("docker", "--version"), "docker"),
        row("podman", "Podman", listOf("podman", "--version"), "podman"),
        row("skopeo", "Skopeo", listOf("skopeo", "--version"), "skopeo"),
        row("curl", "curl", listOf("curl", "--version"), "curl", "libcurl"),
        row(
            "openssl",
            "OpenSSL",
            listOf("openssl", "version"),
            "openssl",
            "libssl",
            "ssl",
            "tls",
            "curl",
            "libcurl",
            "handshake",
            "certificate",
            "x509",
        ),
        row("electron", "Electron", listOf("electron", "--version"), "electron"),
        row("cmake", "CMake", listOf("cmake", "--version"), "cmake"),
        row("bazel", "Bazel", listOf("bazel", "--version"), "bazel"),
        row("pwsh", "PowerShell", listOf("pwsh", "--version"), "powershell", "pwsh"),
        row("dart", "Dart", listOf("dart", "--version"), "dart"),
        row("xcode", "Xcode", listOf("xcodebuild", "-version"), "xcode", "xcodebuild"),
        row("cocoapods", "CocoaPods", listOf("pod", "--version"), "cocoapods"),
        AskRow(
            "tool.cc",
            "the C compiler",
            listOf("gcc", "--version"),
            listOf("gcc", "g++"),
            listOf("tool.cc.version"),
        ),
        AskRow(
            "tool.clang",
            "Clang",
            listOf("clang", "--version"),
            listOf("clang"),
            listOf("tool.cc.version", "tool.clang.version"),
        ),
        AskRow(
            "tool.libc",
            "the C library",
            listOf("ldd", "--version"),
            listOf("glibc", "musl"),
            listOf("tool.libc.version"),
        ),
    )

    /** Bukkit-family servers answer `/version` in their console. */
    private val platforms = listOf(
        "paper",
        "spigot",
        "bukkit",
        "craftbukkit",
        "purpur",
        "folia",
        "pufferfish",
    ).map {
        AskRow(
            "tool.$it",
            it.replaceFirstChar { c -> c.uppercase() },
            listOf("/version"),
            listOf(it),
            gate = listOf("minecraft", "plugin", "plugins", "server", "servers"),
        )
    }
    private val everyTool = tools + platforms

    /** `go` is also a common word: only a Go command at the start of a code line counts. */
    private val goVerbs = setOf("build", "run", "test", "get", "install", "mod", "version", "vet")

    /**
     * Every check the table can ask for, as a reply could answer it: used to tell whether a pasted
     * output is the output of any of them when no session says which one.
     */
    val known: List<Ask> by lazy {
        tools.map { Ask(it.family, it.command, "") } +
            listOf(
                noFile, diskQuota, diskSpace, phpModules, release, macos, windows, zone, locale,
                kernel,
            ).map { Ask(it.family, it.command, "") }
    }

    /** The command of every tool row, for the test that matches them with the scanners. */
    val toolCommands: List<List<String>> get() = tools.map { it.command }

    private val noFile = AskRow(
        "limits.nofile",
        "the open-file limit",
        listOf("ulimit", "-n"),
        emptyList(),
        listOf("limits.nofile"),
    )
    private val diskQuota = AskRow(
        "limits.disk-quota",
        "the disk usage",
        listOf("df", "-h", "."),
        emptyList(),
        listOf("limits.disk-quota"),
    )
    private val diskSpace = AskRow(
        "limits.disk-space",
        "the disk usage",
        listOf("df", "-h", "."),
        emptyList(),
        listOf("limits.disk-space"),
    )
    private val phpModules = AskRow(
        "tool.php.extensions",
        "the PHP extensions",
        listOf("php", "-m"),
        emptyList(),
        listOf("tool.php.extensions"),
    )
    private val release = AskRow(
        "os.release",
        "the Linux release",
        listOf("cat", "/etc/os-release"),
        emptyList(),
        listOf("os.release.VERSION_ID"),
    )
    private val macos = AskRow(
        "os.version",
        "the macOS version",
        listOf("sw_vers", "-productVersion"),
        emptyList(),
        listOf("os.version"),
    )
    private val windows = AskRow(
        "os.version",
        "the Windows version",
        listOf("ver"),
        emptyList(),
        listOf("os.version"),
    )
    private val zone = AskRow(
        "env.TZ",
        "the time zone setting",
        listOf("printenv", "TZ"),
        emptyList(),
        listOf("env.TZ"),
    )
    private val locale = AskRow(
        "env.LANG",
        "the locale",
        listOf("locale"),
        emptyList(),
        listOf("env.LANG", "env.LC_ALL"),
    )
    private val kernel = AskRow(
        "kernel.release",
        "the kernel release",
        listOf("uname", "-r"),
        emptyList(),
        listOf("kernel.release"),
    )
    private val capture = AskRow(
        Ask.CAPTURE,
        "the failing machine",
        listOf("drift", "why", "--save", "there.json"),
        emptyList(),
        emptyList(),
    )

    private const val EXCEEDED = "exceeded"

    /** The whole-machine capture as an ask; [why] says what it records and what for. */
    fun captureAsk(why: String): Ask = Ask(capture.family, capture.command, why)

    private val failureWords = listOf(
        "error",
        "fail",
        "exception",
        "cannot",
        "can't",
        "unable",
        "crash",
        "not found",
        "denied",
        "invalid",
        "undefined",
        "traceback",
        "fatal",
    )
    private val contrast = listOf(
        "works on ",
        "works fine",
        "works locally",
        "works for me",
        "worked on ",
        "used to work",
        "only fails",
        "only on ",
    )
    private val linuxInstallers = listOf("apt", "apt-get", "apk", "dnf", "yum", "zypper", "pacman")
    private val macInstallers = listOf("brew")
    private val windowsInstallers = listOf("choco", "winget", "scoop")

    private fun row(name: String, label: String, command: List<String>, vararg names: String) =
        AskRow("tool.$name", label, command, names.toList())

    private fun why(row: AskRow, named: Boolean) = if (named) {
        "the report names ${row.label} but not which version fails"
    } else {
        "the report does not say ${row.label} on the machine where it fails"
    }

    /**
     * The checks for [doc] given the [facts] it produced (replies included), most useful first:
     * a limit the text says was hit, the tool the text names most, the whole capture when the
     * text says it works elsewhere, other tools, the operating system, then time zone and locale.
     * At most [Ask.MAX], and beside them the clean-run check ([CleanRun]): first when the text
     * states no environment fact to compare, last otherwise.
     */
    internal fun derive(doc: Doc, facts: List<Fact>, worksElsewhere: Boolean = false): List<Ask> {
        val failing = facts.filter { it.side == Side.FAILING }
        val stated = failing.filter { it.value != EXCEEDED }.map { it.key }.toSet()
        val hits = failing.filter { it.value == EXCEEDED }.map { it.key }.toSet()
        fun open(row: AskRow) = row.stated.none { it in stated }
        fun ask(row: AskRow, reason: String) = Ask(row.family, row.command, reason)

        val out = mutableListOf<Ask>()
        val clean = facts.firstOrNull { it.key == CleanRun.KEY && it.side == Side.FAILING }?.value
        if (clean == CleanMatch.WORKS.id) {
            out += ask(
                capture,
                "it records the tools, settings and limits of that machine, which the clean run " +
                    "shows to differ from yours",
            )
        }
        for (row in listOf(noFile, diskQuota, diskSpace)) {
            if (row.stated.first() in hits && open(row)) {
                out += ask(row, "the report says the limit was hit but not what it is")
            }
        }
        val missing = missingCommand(doc)?.takeIf {
            stated.none { key -> key == "tool.$it.version" || key == "tool.$it.installed" }
        }
        val named = mentions(doc).filter { open(it.row) && it.row.family != "tool.$missing" }
        missing?.let {
            out += Ask(
                "tool.$it",
                listOf("command", "-v", it),
                "the error says $it was not found, but not whether it is installed where it fails",
            )
        }
        named.firstOrNull()?.let { out += ask(it.row, why(it.row, true)) }
        for (family in runtimes(doc)) {
            val row = tools.first { it.family == family }
            if (open(row) && out.none { it.family == family }) {
                out += ask(
                    row,
                    "the error text is ${row.label}'s, but the report does not say which version " +
                        "runs it",
                )
            }
        }
        if ((worksElsewhere || hasContrast(doc, facts)) && out.none { it.family == Ask.CAPTURE }) {
            out += ask(
                capture,
                "it records the tools, settings and limits of that machine, which the report " +
                    "does not state",
            )
        }
        named.drop(1).forEach { out += ask(it.row, why(it.row, true)) }
        if (phpMissing(doc) && open(phpModules)) {
            out += ask(phpModules, "the report says something is missing but not what PHP has")
        }
        for (name in variables(doc).filter { "env.$it" !in stated }) {
            out += Ask(
                "env.$name",
                listOf("printenv", name),
                "the report names the variable $name but not its value",
            )
        }
        system(doc, facts)?.takeIf { open(it) }?.let {
            out += ask(it, "the report names the system but not its version")
        }
        if (hasWord(doc, listOf("kernel")) && open(kernel)) {
            out += ask(kernel, "the report is about the kernel but does not state its release")
        }
        if (hasWord(doc, listOf("timezone", "time zone", "timezones")) && open(zone)) {
            out += ask(zone, "the report is about time but does not state the setting")
        }
        if (hasWord(doc, listOf("locale", "locales", "lc_all")) && open(locale)) {
            out += ask(locale, "the report is about locale but does not state it")
        }
        val rest = out.distinct().take(Ask.MAX)
        val check = if (clean == null) CleanRun.derive(doc, facts) else null
        return when {
            check == null -> rest
            failing.none { !it.key.startsWith("run:") } -> listOf(check) + rest
            else -> rest + check
        }
    }

    internal class Mention(val row: AskRow, val score: Int, val first: Int)

    /** Rows for the tools the text names, the most prominent first. */
    internal fun mentions(doc: Doc): List<Mention> {
        val total = IntArray(everyTool.size)
        val code = IntArray(everyTool.size)
        val failing = IntArray(everyTool.size)
        val first = IntArray(everyTool.size) { -1 }
        val gated = everyTool.map { row -> row.gate.isEmpty() || hasWord(doc, row.gate) }
        for (i in doc.lines.indices) {
            if (doc.heading[i] != null && !doc.lower[i].contains("version")) continue
            val low = doc.lower[i]
            val bad = failureWords.any { low.contains(it) }
            for ((r, row) in everyTool.withIndex()) {
                if (!gated[r]) continue
                val n = row.names.sumOf { count(low, it) } + weakCount(doc, i, row)
                if (n == 0) continue
                total[r] += n
                if (doc.inCode[i]) code[r] += n
                if (bad) failing[r] += n
                if (first[r] < 0) first[r] = i
            }
        }
        return everyTool.indices.filter { total[it] > 0 }
            .map { Mention(everyTool[it], 3 * failing[it] + 2 * code[it] + total[it], first[it]) }
            .sortedWith(compareBy({ -it.score }, { it.first }, { it.row.family }))
    }

    private fun startsWord(text: String, phrase: String): Boolean {
        var at = text.indexOf(phrase)
        while (at >= 0) {
            if (at == 0 || !Tokens.isAlnum(text[at - 1])) return true
            at = text.indexOf(phrase, at + 1)
        }
        return false
    }

    private val runtimeWords = listOf(
        "tool.python" to listOf(
            "modulenotfounderror",
            "importerror",
            "traceback (most recent call last)",
            "indentationerror",
            "attributeerror: module",
        ),
        "tool.php" to listOf(
            "fatal error: uncaught",
            "call to undefined function",
            "php parse error",
        ),
        "tool.java" to listOf(
            "java.lang.",
            "exception in thread",
            "unsupportedclassversionerror",
        ),
        "tool.ruby" to listOf("nomethoderror", "loaderror"),
        "tool.dotnet" to listOf("unhandled exception. system."),
    )

    /** `ERR_` followed by capitals, the shape of a Node error code. */
    private fun hasNodeCode(doc: Doc): Boolean = doc.lines.any { line ->
        var at = line.indexOf("ERR_")
        var found = false
        while (at >= 0 && !found) {
            val end = line.indexOf(' ', at).let { if (it < 0) line.length else it }
            val code = line.substring(at, end).trimEnd(',', ':', ')', ']', '\'', '"', '.')
            found = (at == 0 || !Tokens.isAlnum(line[at - 1])) && code.length > 6 &&
                code.all { it in 'A'..'Z' || it == '_' || it in '0'..'9' }
            at = line.indexOf("ERR_", at + 1)
        }
        found
    }

    /**
     * The runtimes an error names by its own vocabulary and nothing else: a Python exception
     * name, a Node error code, a PHP fatal error, a JVM exception, a Go panic, a rustc error code,
     * a Ruby exception or a .NET one. A path or a generic word never counts.
     */
    internal fun runtimes(doc: Doc): List<String> {
        val text = doc.lower.joinToString("\n")
        val found = mutableListOf<String>()
        fun has(vararg phrases: String) = phrases.any { startsWord(text, it) }
        for ((family, phrases) in runtimeWords) {
            if (phrases.any { startsWord(text, it) }) found += family
        }
        val pythonFrame = doc.lines.any {
            it.trim().startsWith("File \"") && it.contains(", line ")
        }
        if (pythonFrame && has("syntaxerror")) found += "tool.python"
        val frames = doc.lines.any { it.trim().startsWith("at ") } || text.contains("node:")
        if (has("cannot find module") || hasNodeCode(doc) ||
            (text.contains("is not a function") && frames)
        ) {
            found += "tool.node"
        }
        if (has("cannot find package") || (has("panic:") && text.contains("goroutine"))) {
            found += "tool.go"
        }
        val rustCode = Regex("error\\[e\\d").containsMatchIn(text)
        if (rustCode || has("thread 'main' panicked")) found += "tool.rust"
        return found.distinct()
    }

    private val shellNames = Regex("^[A-Za-z][A-Za-z0-9_+-]{0,30}$")

    /**
     * The command a shell or a runtime says it could not find: `bash: git: command not found`,
     * `command not found: git` or `No such file or directory: 'git'`. A name with a dot or a slash
     * is a file, not a command.
     */
    internal fun missingCommand(doc: Doc): String? {
        for (raw in doc.lines) {
            val low = Tokens.lower(raw)
            val trailing = low.indexOf(": command not found")
            val leading = low.indexOf("command not found: ")
            val quoted = low.indexOf("no such file or directory: ")
            val name = when {
                leading >= 0 -> raw.substring(leading + "command not found: ".length).trim()
                    .split(' ').first()

                trailing > 0 -> raw.substring(0, trailing).trim().split(' ', ':').last()

                quoted >= 0 -> raw.substring(quoted + "no such file or directory: ".length).trim()
                    .trim('\'', '"').substringBefore('\'').substringBefore('"')

                else -> continue
            }
            if (shellNames.matches(name)) return name
        }
        return null
    }

    private fun weakCount(doc: Doc, i: Int, row: AskRow): Int {
        if (row.family != "tool.go") return 0
        val words = doc.lines[i].trim().removePrefix("$ ").removePrefix("% ").split(' ')
        val command = doc.inCode[i] || doc.lines[i].trim().startsWith("$ ")
        return if (command && words.size > 1 && words[0] == "go" && words[1] in goVerbs) 1 else 0
    }

    private fun count(low: String, name: String): Int {
        var n = 0
        var at = low.indexOf(name)
        while (at >= 0) {
            if (bare(low, at, at + name.length)) n++
            at = low.indexOf(name, at + 1)
        }
        return n
    }

    /** A name that stands alone: not inside a path, a package name, a URL or a longer word. */
    private fun bare(low: String, at: Int, end: Int): Boolean {
        if (at > 0 && (Tokens.isAlnum(low[at - 1]) || low[at - 1] in "/\\@-_.$")) return false
        if (end < low.length) {
            val after = low[end]
            if (Tokens.isAlnum(after) || after in "/\\-_@") return false
            val next = low.getOrNull(end + 1)
            if (after == '.' && next != null && Tokens.isAlnum(next) && !Tokens.isDigit(next)) {
                return false
            }
        }
        var s = at
        while (s > 0 && low[s - 1] != ' ') s--
        var e = end
        while (e < low.length && low[e] != ' ') e++
        return !low.substring(s, e).contains("://")
    }

    private fun system(doc: Doc, facts: List<Fact>): AskRow? {
        val os = facts.firstOrNull { it.side == Side.FAILING && it.key == "os.name" }?.value
        return when (os) {
            "linux" -> release
            "macos" -> macos
            "windows" -> windows
            null -> hinted(doc)
            else -> null
        }
    }

    /** The system a command or path in the text implies, when the text names none. */
    private fun hinted(doc: Doc): AskRow? {
        for (i in doc.lines.indices) {
            val low = doc.lower[i]
            val words = low.trim().removePrefix("$ ").removePrefix("sudo ").split(' ')
            val first = words.firstOrNull().orEmpty()
            val second = words.getOrNull(1).orEmpty()
            val installs = second == "install" || second == "add" || second == "-s"
            if (installs && first in linuxInstallers) return release
            if (installs && first in macInstallers) return macos
            if (installs && first in windowsInstallers) return windows
            if (low.contains("c:\\") || low.contains("\\users\\")) return windows
            if (low.contains("/users/") && low.contains("/library/")) return macos
        }
        return null
    }

    private val phpWords = listOf("extension", "extensions", "missing")

    private fun phpMissing(doc: Doc) = hasWord(doc, listOf("php")) && hasWord(doc, phpWords)

    private val notVariables = setOf(
        "PATH", "HOME", "USER", "SHELL", "PWD", "TERM", "HOSTNAME", "TZ", "LANG", "LC_ALL",
        "LC_CTYPE", "OLDPWD", "LOGNAME", "TMPDIR", "EDITOR", "DISPLAY",
    )
    private val variableLeads = listOf(
        "environment variable ",
        "env var ",
        "env variable ",
        "environment var ",
        "export ",
        "set ",
        "printenv ",
    )

    /**
     * Environment variable names the text uses without a value: `$NAME`, `${NAME}`, `%NAME%`,
     * `export NAME` and "environment variable NAME", in capitals, not a secret by name.
     */
    private fun variables(doc: Doc): List<String> {
        val found = linkedSetOf<String>()
        for (raw in doc.lines) {
            for (word in raw.split(' ', '\t', '(', ')', '"', '\'', '`', ',', ';')) {
                val name = when {
                    word.startsWith("\${") && word.endsWith("}") -> word.substring(
                        2,
                        word.length - 1,
                    )

                    word.startsWith("$") -> word.substring(1)

                    word.length > 2 && word.startsWith("%") && word.endsWith("%") ->
                        word.substring(1, word.length - 1)

                    else -> continue
                }
                if (variableName(name)) found += name
            }
            val low = Tokens.lower(raw)
            for (lead in variableLeads) {
                val at = low.indexOf(lead)
                if (at < 0 || !Tokens.wordAt(low, at, lead.trim())) continue
                val word = raw.substring(at + lead.length).trimStart()
                    .takeWhile { Tokens.isAlnum(it) || it == '_' }
                if (variableName(word) && !raw.substring(at).contains("$word=")) found += word
            }
        }
        return found.filter { it !in notVariables }.take(MAX_VARIABLES)
    }

    private fun variableName(name: String) = name.length in 2..40 && name[0] in 'A'..'Z' &&
        name.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' } &&
        Names.secretParts.none { Tokens.lower(name).contains(it) }

    private const val MAX_VARIABLES = 2

    private fun hasWord(doc: Doc, words: List<String>) =
        doc.lower.any { low -> words.any { Tokens.hasWord(low, it) } }

    private fun hasContrast(doc: Doc, facts: List<Fact>): Boolean {
        if (facts.any { it.side == Side.PASSING && !it.key.startsWith("run:") }) return true
        return doc.lower.any { low -> contrast.any { low.contains(it) } }
    }
}

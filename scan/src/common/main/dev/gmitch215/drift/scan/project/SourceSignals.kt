package dev.gmitch215.drift.scan.project

private const val SOURCE = "source"
private const val MAX_ARGUMENT_CHARS = 400
private const val DOT = "[ \\t]*\\.[ \\t]*"
private const val CALL = "[ \\t]*\\("
private const val NOT_NAME = "(?:^|[^A-Za-z0-9_.])"

/** A call-site or syntax signal; [name] becomes `project.<ecosystem>.calls.<name>`. */
internal class Signal(val name: String, val matches: (String) -> Boolean)

/** What one language contributes: the names it imports and the fixed signals it checks. */
internal class Language(
    val ecosystem: String,
    val imports: (String) -> Set<String>,
    val calls: List<Signal>,
)

private fun signal(name: String, pattern: String): Signal {
    val regex = Regex(pattern)
    return Signal(name) { regex.containsMatchIn(it) }
}

/** [name] and every dotted prefix of it, so a rule can ask about a package or a whole family. */
private fun dotted(name: String, into: MutableSet<String>) {
    var current = name
    while (true) {
        into += current
        val at = current.lastIndexOf('.')
        if (at <= 0) break
        current = current.substring(0, at)
    }
}

// #region python
private val pythonImport =
    Regex("^[ \\t]*import[ \\t]+([A-Za-z0-9_., \\t]+)", RegexOption.MULTILINE)
private val pythonFrom =
    Regex("^[ \\t]*from[ \\t]+([A-Za-z_][A-Za-z0-9_.]*)[ \\t]+import\\b", RegexOption.MULTILINE)
private val pythonName = Regex("[A-Za-z_][A-Za-z0-9_.]*")
private val pythonOpen = Regex("(?:^|[^A-Za-z0-9_.])open[ \\t]*\\(")
private val pythonBinary = Regex("[\"'][rwxatU+]*b[rwxatU+]*[\"']")
private val pythonEncoding = Regex("encoding[ \\t]*=")

private fun pythonImports(text: String): Set<String> {
    val names = mutableSetOf<String>()
    for (m in pythonImport.findAll(text)) {
        for (part in m.groupValues[1].split(',')) {
            val name = part.trim().split(' ', '\t').first()
            if (pythonName.matches(name)) dotted(name, names)
        }
    }
    for (m in pythonFrom.findAll(text)) dotted(m.groupValues[1], names)
    return names
}

/** The text of the call whose `(` ends at [open]; empty when it does not close in range. */
private fun arguments(text: String, open: Int): String {
    var depth = 1
    var quote = ' '
    var i = open
    while (i < text.length && i - open < MAX_ARGUMENT_CHARS) {
        val c = text[i]
        when {
            quote != ' ' -> if (c == quote) quote = ' '
            c == '"' || c == '\'' -> quote = c
            c == '(' -> depth++
            c == ')' -> if (--depth == 0) return text.substring(open, i)
        }
        i++
    }
    return ""
}

private fun openWithoutEncoding(text: String): Boolean = pythonOpen.findAll(text).any { m ->
    val head = text.substring(0, m.range.first + m.value.indexOf("open"))
    val args = arguments(text, m.range.last + 1)
    !head.trimEnd().endsWith("def") && args.isNotBlank() &&
        !pythonEncoding.containsMatchIn(args) && !pythonBinary.containsMatchIn(args)
}

private val python = Language(
    "python",
    ::pythonImports,
    listOf(
        signal("asyncio.get_event_loop", "$NOT_NAME(?:asyncio$DOT)?get_event_loop$CALL"),
        signal("ast-removed-nodes", "\\bast$DOT(?:Num|Str|Bytes|NameConstant|Ellipsis)\\b"),
        signal("urllib-urlopener", "\\b(?:Fancy)?URLopener\\b"),
        signal("ssl.wrap_socket", "\\bssl${DOT}wrap_socket$CALL"),
        signal("ssl.match_hostname", "\\bssl${DOT}match_hostname$CALL"),
        signal(
            "unittest-deprecated-aliases",
            "\\.[ \\t]*(?:assertEquals|assertNotEquals|assertAlmostEquals|assertNotAlmostEquals|" +
                "assertRegexpMatches|assertNotRegexpMatches|assertRaisesRegexp|assert_|" +
                "failUnless|failIf|failUnlessEqual|failIfEqual|failUnlessRaises|" +
                "assertDictContainsSubset)" + CALL,
        ),
        signal("annotation-readers", "__annotations__|\\b(?:get_type_hints|get_annotations)\\b"),
        signal("multiprocessing-start-method", "\\b(?:set_start_method|get_context)$CALL"),
        Signal("open-without-encoding", ::openWithoutEncoding),
        signal("copy.replace", NOT_NAME + "copy$DOT" + "replace$CALL"),
        signal("itertools.batched", "\\bitertools${DOT}batched$CALL"),
        signal("datetime.UTC", "\\bdatetime${DOT}UTC\\b"),
        signal("str.removeprefix", "\\.[ \\t]*removeprefix$CALL"),
        signal("math.lcm", "\\bmath${DOT}lcm$CALL"),
        signal(
            "itertools.pairwise",
            "\\bitertools${DOT}pairwise$CALL|" +
                "from[ \\t]+itertools[ \\t]+import[^\\n]*\\bpairwise\\b",
        ),
        signal("contextlib.chdir", "\\bcontextlib${DOT}chdir$CALL"),
        signal("hashlib.file_digest", "\\bhashlib${DOT}file_digest$CALL"),
        signal("asyncio.TaskGroup", "\\basyncio${DOT}TaskGroup\\b"),
        signal(
            "typing.override",
            "\\btyping${DOT}override\\b|from[ \\t]+typing[ \\t]+import[^\\n]*\\boverride\\b",
        ),
        signal(
            "warnings.deprecated",
            "\\bwarnings${DOT}deprecated\\b|" +
                "from[ \\t]+warnings[ \\t]+import[^\\n]*\\bdeprecated\\b",
        ),
    ),
)
// #endregion

// #region java
private val javaImport = Regex(
    "^[ \\t]*import[ \\t]+(?:static[ \\t]+)?([A-Za-z0-9_.]+?)(\\.\\*)?" +
        "[ \\t]*(?:;|\$|[ \\t]as[ \\t])",
    RegexOption.MULTILINE,
)

private fun javaImports(text: String): Set<String> {
    val names = mutableSetOf<String>()
    for (m in javaImport.findAll(text)) dotted(m.groupValues[1], names)
    return names
}

private val java = Language(
    "java",
    ::javaImports,
    listOf(
        signal("System.setSecurityManager", "\\bSystem${DOT}setSecurityManager$CALL"),
        signal("Math.clamp", "\\bMath${DOT}clamp$CALL"),
        signal("Files.readString", "\\bFiles${DOT}readString$CALL"),
        signal("Files.writeString", "\\bFiles${DOT}writeString$CALL"),
        signal("Files.mismatch", "\\bFiles${DOT}mismatch$CALL"),
        signal("Path.of", "\\bPath${DOT}of$CALL"),
        signal("Predicate.not", "\\bPredicate${DOT}not$CALL"),
        signal("Stream.mapMulti", "\\.[ \\t]*mapMulti$CALL"),
        signal("sequenced-first-last", "\\.[ \\t]*(?:getFirst|getLast)[ \\t]*\\(\\)"),
        signal(
            "virtual-threads",
            "\\bThread$DOT(?:ofVirtual|startVirtualThread)\\b|" +
                "\\bnewVirtualThreadPerTaskExecutor\\b",
        ),
    ),
)
// #endregion

// #region php
private val phpUse = Regex(
    "^[ \\t]*use[ \\t]+(?:function[ \\t]+|const[ \\t]+)?\\\\?([A-Za-z0-9_\\\\]+)" +
        "(?:[ \\t]+as[ \\t]+[A-Za-z0-9_]+)?[ \\t]*;",
    RegexOption.MULTILINE,
)
private val phpGroup = Regex(
    "^[ \\t]*use[ \\t]+(?:function[ \\t]+|const[ \\t]+)?\\\\?([A-Za-z0-9_\\\\]+)\\\\\\{([^}]*)}",
    RegexOption.MULTILINE,
)

private fun phpImports(text: String): Set<String> {
    val names = mutableSetOf<String>()
    for (m in phpUse.findAll(text)) dotted(m.groupValues[1].replace('\\', '.'), names)
    for (m in phpGroup.findAll(text)) {
        val base = m.groupValues[1].replace('\\', '.')
        for (member in m.groupValues[2].split(',')) {
            val name = member.substringBefore(" as ").trim().replace('\\', '.')
            if (name.isNotEmpty()) dotted("$base.$name", names)
        }
    }
    return names
}

private val php = Language(
    "php",
    ::phpImports,
    listOf(
        signal("each", "(?:^|[^A-Za-z0-9_>:$\\\\])each$CALL"),
        signal("create_function", "\\bcreate_function$CALL"),
        signal("mcrypt", "\\bmcrypt_[A-Za-z0-9_]+$CALL"),
        signal("json_validate", "(?:^|[^A-Za-z0-9_>:$\\\\])json_validate$CALL"),
        signal("array_is_list", "(?:^|[^A-Za-z0-9_>:$\\\\])array_is_list$CALL"),
        signal("str_contains", "(?:^|[^A-Za-z0-9_>:$\\\\])str_contains$CALL"),
        signal("str_starts_with", "(?:^|[^A-Za-z0-9_>:$\\\\])str_starts_with$CALL"),
        signal("str_ends_with", "(?:^|[^A-Za-z0-9_>:$\\\\])str_ends_with$CALL"),
        signal("fdiv", "(?:^|[^A-Za-z0-9_>:$\\\\])fdiv$CALL"),
        signal("get_debug_type", "(?:^|[^A-Za-z0-9_>:$\\\\])get_debug_type$CALL"),
        signal("mb_trim", "(?:^|[^A-Za-z0-9_>:$\\\\])mb_trim$CALL"),
        signal("array_find", "(?:^|[^A-Za-z0-9_>:$\\\\])array_find$CALL"),
        signal("array_find_key", "(?:^|[^A-Za-z0-9_>:$\\\\])array_find_key$CALL"),
        signal("array_any", "(?:^|[^A-Za-z0-9_>:$\\\\])array_any$CALL"),
        signal("array_all", "(?:^|[^A-Za-z0-9_>:$\\\\])array_all$CALL"),
    ),
)
// #endregion

// #region js
private val jsModule = Regex(
    "(?:^|[^A-Za-z0-9_$.])(?:from|import|require)[ \\t\\r\\n]*\\(?[ \\t\\r\\n]*" +
        "[\"']([^\"'\\n]+)[\"']",
)

private fun jsImports(text: String): Set<String> {
    val names = mutableSetOf<String>()
    for (m in jsModule.findAll(text)) {
        val spec = m.groupValues[1]
        if (spec.startsWith(".") || spec.startsWith("/") || spec.startsWith("#")) continue
        if ("://" in spec) continue
        val parts = spec.split('/')
        val root = if (spec.startsWith("@")) parts.take(2).joinToString("/") else parts[0]
        names += root
        names += spec
    }
    return names
}

private val js = Language(
    "js",
    ::jsImports,
    listOf(
        signal("Array.fromAsync", "\\bArray${DOT}fromAsync$CALL"),
        signal("Object.groupBy", "\\bObject${DOT}groupBy$CALL"),
        signal("Promise.withResolvers", "\\bPromise${DOT}withResolvers$CALL"),
        signal("fs.globSync", "\\bfs${DOT}globSync$CALL"),
        signal("exitcode-string", "process\\.exitCode[ \\t]*=[ \\t]*(?:[\"'`]|String[ \\t]*\\()"),
        signal(
            "import-assert",
            "[\"'][ \\t]*assert[ \\t]*\\{|\\bassert[ \\t]*:[ \\t]*\\{[ \\t]*type",
        ),
    ),
)
// #endregion

internal val sourceLanguages = listOf(python, java, php, js)

internal fun sourceFacts(language: Language, tree: SourceTree, out: ProjectScanner.Out) {
    val texts = tree.texts[language.ecosystem].orEmpty()
    val names = mutableSetOf<String>()
    for (text in texts) names += language.imports(text)
    out.imports(names, SOURCE)
    for (call in language.calls) {
        if (texts.any(call.matches)) out.put("calls.${call.name}", "true", SOURCE)
    }
    tree.omitted[language.ecosystem]?.let { out.put("source-omitted", "$it", SOURCE) }
}

package dev.gmitch215.drift.scan.project

private const val SOURCE = "source"
private const val MAX_ARGUMENT_CHARS = 400
private const val MAX_BLOCK_CHARS = 20_000
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
    val facts: List<Fact> = emptyList(),
    val source: String = ecosystem,
    val prefix: String = "calls.",
)

/** A fact over every file of one language; [name] becomes `project.<ecosystem>.<name>`. */
internal class Fact(val name: String, val value: (List<String>) -> String?)

private fun fact(name: String, pattern: String): Fact {
    val regex = Regex(pattern)
    return Fact(name) { texts -> "true".takeIf { texts.any(regex::containsMatchIn) } }
}

/** The text inside the braces that open at [open]; empty when they do not close in range. */
private fun block(text: String, open: Int): String {
    var depth = 0
    for (i in open until minOf(text.length, open + MAX_BLOCK_CHARS)) {
        when (text[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return text.substring(open + 1, i)
        }
    }
    return ""
}

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

private const val VALUE_BASED =
    "Integer|Long|Short|Byte|Character|Boolean|Double|Float|Optional(?:Int|Long|Double)?|" +
        "LocalDate|LocalTime|LocalDateTime|Duration|Instant"
private val syncOnLiteral = Regex(
    "\\bsynchronized[ \\t]*\\([ \\t]*(?:(?:$VALUE_BASED)$DOT(?:valueOf|of|empty)\\b|" +
        "new[ \\t]+(?:$VALUE_BASED)[ \\t]*\\()",
)
private val syncOnName = Regex("\\bsynchronized[ \\t]*\\([ \\t]*([A-Za-z_]\\w*)[ \\t]*\\)")
private val engineName = Regex("\\bgetEngineByName[ \\t]*\\([ \\t]*\"([A-Za-z0-9_.-]+)\"")
private val keystoreDefault = Regex("\\bKeyStore${DOT}getDefaultType$CALL")
private val keystoreNamed =
    Regex("\\bKeyStore${DOT}getInstance[ \\t]*\\([ \\t]*\"([A-Za-z0-9]+)\"")

private fun valueBasedSync(texts: List<String>): String? = texts.any { text ->
    syncOnLiteral.containsMatchIn(text) || syncOnName.findAll(text).any { m ->
        Regex("\\b(?:$VALUE_BASED)(?:<[^>;()]*>)?[ \\t]+${m.groupValues[1]}\\b")
            .containsMatchIn(text)
    }
}.let { if (it) "true" else null }

private fun scriptEngines(texts: List<String>): String? = texts
    .flatMap { text -> engineName.findAll(text).map { it.groupValues[1].lowercase() } }
    .distinct().sorted().joinToString(",").ifEmpty { null }

private fun keystoreType(texts: List<String>): String? {
    if (texts.any(keystoreDefault::containsMatchIn)) return "default"
    return texts.firstNotNullOfOrNull { keystoreNamed.find(it) }?.groupValues?.get(1)?.lowercase()
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
    listOf(
        fact(
            "dynamic-agent-load",
            "\\bVirtualMachine${DOT}attach\\b|\\.[ \\t]*loadAgent(?:Library|Path)?$CALL|" +
                "\\bByteBuddyAgent${DOT}install$CALL",
        ),
        Fact("value-based-sync", ::valueBasedSync),
        Fact("script-engines", ::scriptEngines),
        Fact("keystore-type", ::keystoreType),
    ),
)

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
    listOf(Fact("internal-interface-override-no-return-type", ::untypedInternalOverride)),
)

private const val INTERNAL_METHODS = "getIterator|offsetGet|offsetSet|offsetExists|offsetUnset|" +
    "count|jsonSerialize|current|key|next|rewind|valid"
private val internalInterface = Regex(
    "\\b(?:implements|extends)[^{]*\\b(?:IteratorAggregate|ArrayAccess|Countable|" +
        "JsonSerializable|Iterator)\\b",
)
private val untypedMethod =
    Regex("function[ \\t]+(?:$INTERNAL_METHODS)[ \\t]*\\([^)]*\\)[ \\t]*(?:\\{|\\r?\\n|$)")

private fun untypedInternalOverride(texts: List<String>): String? = texts.any { text ->
    internalInterface.containsMatchIn(text) && untypedMethod.findAll(text).any { m ->
        "ReturnTypeWillChange" !in text.substring(maxOf(0, m.range.first - 120), m.range.first)
    }
}.let { if (it) "true" else null }

private val executeMethod = Regex("function[ \\t]+execute[ \\t]*\\(")
private val bareReturn = Regex("\\breturn[ \\t]*(?:null[ \\t]*)?;", RegexOption.IGNORE_CASE)

private fun commandExecuteReturnsNull(text: String): Boolean = "Command" in text &&
    executeMethod.findAll(text).any { m ->
        val open = text.indexOf('{', m.range.last)
        open >= 0 && bareReturn.containsMatchIn(block(text, open))
    }

private val simpletest = Regex(
    "\\bextends[ \\t]+\\\\?(?:Drupal\\\\simpletest\\\\)?(?:WebTestBase|DrupalWebTestCase)\\b|" +
        "\\bDrupal\\\\simpletest\\\\(?:WebTestBase|TestBase)\\b",
)

private val drupalTests = Language(
    "drupal",
    { emptySet() },
    emptyList(),
    listOf(
        Fact("tests.simpletest-base-class") { texts ->
            "true".takeIf { texts.any(simpletest::containsMatchIn) }
        },
    ),
    source = "php",
)

private val symfony = Language(
    "symfony",
    { emptySet() },
    listOf(
        Signal("command-execute-returns-null", ::commandExecuteReturnsNull),
        signal(
            "token-getroles-call",
            "[Tt]oken(?:$CALL[ \\t]*\\))?[ \\t]*->[ \\t]*getRoles$CALL",
        ),
        signal("user-getusername-call", "->[ \\t]*getUsername$CALL"),
        signal(
            "session-service-lookup",
            "->[ \\t]*get[ \\t]*\\([ \\t]*[\"']session[\"']|\\bSessionInterface[ \\t]+\\$",
        ),
        signal(
            "command-defaultname-property",
            "\\bstatic[ \\t]+\\\$default(?:Name|Description)\\b|" +
                "\\\$default(?:Name|Description)[ \\t]*=",
        ),
        signal("parameterbag-getint-call", "->[ \\t]*(?:getInt|getBool)$CALL"),
        signal("controller-renderform-call", "->[ \\t]*renderForm$CALL"),
        signal(
            "request-get-call",
            "\\\$request[ \\t]*->[ \\t]*get$CALL|" +
                "getCurrentRequest$CALL[ \\t]*\\)?[ \\t]*->[ \\t]*get$CALL",
        ),
        signal("twig-simple-class-use", "\\bTwig_Simple(?:Filter|Function|Test)\\b"),
    ),
    source = "php",
    prefix = "source.",
)
// #endregion

// #region python-frameworks
private val torchLoad = Regex("\\btorch${DOT}load$CALL")

private fun torchLoadWithoutWeightsOnly(text: String): Boolean = torchLoad.findAll(text).any { m ->
    "weights_only" !in arguments(text, m.range.last + 1)
}

private fun pythonFramework(ecosystem: String, vararg calls: Signal) =
    Language(ecosystem, { emptySet() }, calls.toList(), source = "python", prefix = "source.")

private val django = pythonFramework(
    "django",
    signal(
        "removed-4-0-names",
        "\\b(?:ugettext(?:_lazy|_noop)?|ungettext(?:_lazy)?|force_text|smart_text|" +
            "urlquote(?:_plus)?|urlunquote(?:_plus)?|is_safe_url|unescape_entities)\\b",
    ),
    signal(
        "removed-5-0-modules",
        "django\\.utils\\.(?:datetime_safe|baseconv)\\b|" +
            "from[ \\t]+django\\.utils[ \\t]+import[^\\n]*\\b(?:datetime_safe|baseconv)\\b",
    ),
    signal(
        "removed-5-1-names",
        "\\bindex_together\\b|\\bmake_random_password\\b|\\bget_storage_class\\b|" +
            "\\b(?:DEFAULT_FILE_STORAGE|STATICFILES_STORAGE)\\b|" +
            "\\b(?:Unsalted(?:SHA1|MD5)|SHA1)PasswordHasher\\b",
    ),
)

private val numpy = pythonFramework(
    "numpy",
    signal(
        "removed-2-0-members",
        "\\b(?:np|numpy)$DOT(?:float_|complex_|string_|unicode_|round_|NaN|Inf|Infinity|infty|" +
            "NINF|PINF|NZERO|PZERO|product|cumproduct|sometrue|alltrue|asfarray|cfloat|" +
            "clongfloat|longfloat|singlecomplex|longcomplex|find_common_type|safe_eval|" +
            "obj2sctype|sctype2char|set_string_function|issubclass_)\\b",
    ),
)

private val pydantic = pythonFramework(
    "pydantic",
    signal(
        "basesettings-import",
        "from[ \\t]+pydantic[ \\t]+import[ \\t]*(?:\\([^)]*|[^\\n]*)\\bBaseSettings\\b|" +
            "\\bpydantic${DOT}BaseSettings\\b",
    ),
    signal(
        "removed-names",
        "\\bConstrained(?:Bytes|Date|Decimal|Float|FrozenSet|Int|List|Set|Str)\\b|" +
            "\\bpydantic\\.datetime_parse\\b|\\bErrorWrapper\\b|" +
            "\\bcreate_model_from_(?:namedtuple|typeddict)\\b",
    ),
)

private val flask = pythonFramework(
    "flask",
    signal(
        "removed-2-3-features",
        "\\b(?:before_first_request|before_app_first_request|htmlsafe_dumps?)\\b|" +
            "\\.[ \\t]*json_(?:encoder|decoder)\\b|" +
            "\\b(?:JSON_AS_ASCII|JSON_SORT_KEYS|JSONIFY_MIMETYPE|JSONIFY_PRETTYPRINT_REGULAR)\\b",
    ),
)

private val pandas = pythonFramework(
    "pandas",
    signal("removed-2-0-members", "\\.[ \\t]*iteritems$CALL|\\b(?:Int64|UInt64|Float64)Index\\b"),
    signal("removed-3-0-members", "\\.[ \\t]*applymap$CALL|\\bdelim_whitespace\\b"),
)

private val torch = pythonFramework(
    "torch",
    Signal("load-without-weights-only", ::torchLoadWithoutWeightsOnly),
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

internal val sourceLanguages = listOf(
    python, java, php, js, symfony, drupalTests,
    django, numpy, pydantic, flask, pandas, torch,
)

internal fun sourceFacts(language: Language, tree: SourceTree, out: ProjectScanner.Out) {
    val texts = tree.texts[language.source].orEmpty()
    val names = mutableSetOf<String>()
    for (text in texts) names += language.imports(text)
    out.imports(names, SOURCE)
    for (call in language.calls) {
        if (texts.any(call.matches)) out.put("${language.prefix}${call.name}", "true", SOURCE)
    }
    for (fact in language.facts) out.put(fact.name, fact.value(texts), SOURCE)
    if (language.source == language.ecosystem) {
        tree.omitted[language.ecosystem]?.let { out.put("source-omitted", "$it", SOURCE) }
    }
}

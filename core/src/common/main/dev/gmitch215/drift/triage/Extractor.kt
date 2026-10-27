package dev.gmitch215.drift.triage

import dev.gmitch215.drift.diff.VersionScheme

/** One value found in the text, before agreement and conflict are weighed. */
internal data class Cand(
    val key: String,
    val value: String,
    val side: Side,
    val via: Via,
    val span: Span,
    val conf: Confidence,
)

/**
 * Reads environment facts from a [Doc] along four paths, strongest first: a heading or label that
 * names the field (SECTION), a verbatim tool line (TOOL), a `Key: value` line (KEY_VALUE) and a
 * known name followed by a version in running text (PATTERN).
 */
internal class Extractor(val doc: Doc, private val bases: Map<Via, Int> = emptyMap()) {
    val out = mutableListOf<Cand>()

    val names: List<Pair<String, VersionField>> = (Names.versions + Evidence.fields(doc))
        .flatMap { f -> f.names.map { it to f } }
        .sortedWith(compareBy({ -it.first.length }, { it.first }))

    /** The tool whose name starts at [at] in [low] and the index after it, or null. */
    fun nameAt(low: String, at: Int): Pair<VersionField, Int>? {
        val hit = names.firstOrNull { (n, _) ->
            Tokens.wordAt(low, at, n) && Tokens.endsAt(low, at + n.length)
        } ?: return null
        return hit.second to at + hit.first.length
    }

    fun run(): List<Cand> {
        sections()
        for (i in doc.lines.indices) {
            if (doc.fence[i] || doc.heading[i] != null) continue
            keyValue(i)
            toolLines(i)
            versions(i)
            Requirements(this).run(i)
            operatingSystem(i, 0, doc.lines[i].length, Via.PATTERN, null)
            arch(i)
            browser(i, Via.PATTERN, false)
            continuous(i)
            platform(i)
        }
        Verbatim(this).run()
        Mentions(this).run()
        return out
    }

    // #region emit
    fun emit(
        i: Int,
        key: String,
        value: String,
        col: Int,
        length: Int,
        via: Via,
        label: String? = null,
        scale: Int = Confidence.MAX,
        side: Side? = null,
    ) {
        if (value.isEmpty() || col < 0) return
        val low = doc.lower[i]
        var base =
            (bases[via] ?: via.base) + if (via == Via.PATTERN && doc.inBag(i)) BAG_BONUS else 0
        if (Names.hedges.any { Tokens.hasWord(low, it) }) base = base * HEDGE / Confidence.MAX
        base = base * scale / Confidence.MAX
        val where = side ?: Sides.of(low, col, col + length, label)
        out += Cand(key, value, where, via, Span(i + 1, col + 1, length), Confidence(base))
    }

    fun emitVersion(
        i: Int,
        f: VersionField,
        token: String,
        col: Int,
        via: Via,
        label: String?,
        side: Side? = null,
    ) {
        emit(i, f.key, token, col, token.length, via, label, side = side)
        f.family?.let { emit(i, it.first, it.second, col, token.length, via, label, side = side) }
    }
    // #endregion

    // #region sections and labels
    private fun sections() {
        for (h in doc.lines.indices) {
            val label = doc.heading[h] ?: continue
            val end = doc.sectionEnd(h)
            var v = h + 1
            while (v < end && (doc.lines[v].isBlank() || doc.fence[v])) v++
            if (v >= end) continue
            val value = doc.lines[v]
            if (isPlaceholder(value)) continue
            apply(label, null, v, 0, Via.SECTION)
        }
    }

    private fun isPlaceholder(value: String): Boolean {
        val c = Doc.clean(value)
        return c.isEmpty() || c in Names.placeholders
    }

    /** Routes the value on line [i] from column [from] to the field the label names, if any. */
    private fun apply(label: String, rawLabel: String?, i: Int, from: Int, via: Via) {
        val text = doc.lines[i]
        val norm = Doc.clean(label).removePrefix("- ").removePrefix("+ ")
        val bare = norm.removeSuffix(" versions").removeSuffix(" version").removeSuffix(" ver")
            .trim()
        val raw = rawLabel?.trim()?.removePrefix("export ")?.trim()
        if (raw != null && raw in Names.envNames && via == Via.KEY_VALUE) {
            token(text, from)?.let { (v, c) ->
                emit(i, Names.envNames.getValue(raw), v, c, v.length, via)
            }
            return
        }
        if (raw != null && via == Via.KEY_VALUE && osRelease(raw, i, from)) return
        when {
            bare in Names.osLabels -> operatingSystem(i, from, text.length, via, norm)

            bare in Names.archLabels -> arch(i, from, via, norm)

            bare in Names.browserLabels -> browser(i, via, true, from, norm)

            bare in Names.ciLabels -> continuous(i, from, via, norm)

            bare in Names.runnerLabels -> runnerLabel(i, from, via, norm)

            bare in Names.imageLabels -> token(text, from)?.let { (v, c) ->
                emit(i, "runtime.container.image", v, c, v.length, via, norm)
            }

            bare in Names.platformLabels -> Mentions(this).platformValue(i, from, via, norm)

            bare in Names.localeLabels -> token(text, from)?.let { (v, c) ->
                if (v.any { it == '_' || it == '.' } && v[0].isLetter()) {
                    emit(i, "env.LANG", v, c, v.length, via, norm, LOCALE_SCALE)
                }
            }

            bare in Names.timezoneLabels -> token(text, from)?.let { (v, c) ->
                emit(i, "env.TZ", v, c, v.length, via, norm)
            }

            else -> versionLabel(bare, i, from, via, norm)
        }
    }

    private fun versionLabel(bare: String, i: Int, from: Int, via: Via, norm: String) {
        val padded = " $bare "
        val hit = names.firstOrNull { (n, _) ->
            val plugin = bare.contains("plugin") && !n.contains("plugin")
            bare == n || (padded.contains(" $n ") && !plugin)
        } ?: return
        val text = doc.lines[i]
        val gaps = Names.gapWords + hit.first
        val (v, start) =
            afterName(text, Tokens.lower(text), from, hit.second.scheme, gaps) ?: return
        if (hit.second.generic && (!v.contains('.') || address(v))) return
        emitVersion(i, hit.second, v, start, via, norm)
    }

    /** First run of non-space characters from [from], without quotes and backticks. */
    private fun token(text: String, from: Int): Pair<String, Int>? {
        var s = from
        while (s < text.length && (text[s] == ' ' || text[s] in "\t\"'`")) s++
        var e = s
        while (e < text.length && !(text[e] == ' ' || text[e] in "\t\"'`,;|")) e++
        return if (e > s) text.substring(s, e) to s else null
    }

    private fun osRelease(raw: String, i: Int, from: Int): Boolean {
        val text = doc.lines[i]
        val (v, c) = token(text, from) ?: return false
        when (raw) {
            "ID" -> {
                if (v !in Names.distros) return false
                emit(i, "os.release.ID", v, c, v.length, Via.TOOL)
                emit(i, "os.name", "linux", c, v.length, Via.TOOL, scale = INFER_SCALE)
            }

            "VERSION_ID" -> {
                if (Tokens.version(v, 0) == null) return false
                emit(i, "os.release.VERSION_ID", v, c, v.length, Via.TOOL)
            }

            "PRETTY_NAME" -> operatingSystem(i, from, text.length, Via.TOOL, null)

            else -> return false
        }
        return true
    }
    // #endregion

    // #region lines
    private fun keyValue(i: Int) {
        val raw = doc.lines[i]
        val lead = raw.length - raw.trimStart().length
        var s = lead
        while (s < raw.length && (raw[s] in "-*+>" || raw[s] == ' ')) s++
        if (s >= raw.length) return
        if (raw[s] == '|' || (lead < raw.length && raw[lead] == '|')) {
            val cells = raw.trim().trim('|').split('|')
            if (cells.size < 2) return
            val first = raw.indexOf('|') + 1
            val second = first + cells[0].length + 1
            apply(cells[0], null, i, second, Via.KEY_VALUE)
            return
        }
        var sep = -1
        for (k in s until minOf(raw.length, s + MAX_LABEL)) {
            if (raw[k] == ':' || raw[k] == '=') {
                sep = k
                break
            }
        }
        if (sep < 0 || (sep + 1 < raw.length && raw[sep] == ':' && raw[sep + 1] == '/')) return
        val label = raw.substring(s, sep).removePrefix("export ")
        if (label.length > MAX_LABEL || label.contains('`')) return
        val words = label.trim().split(' ').count { it.isNotEmpty() }
        if (words > MAX_LABEL_WORDS || (raw[sep] == '=' && words > 1)) return
        apply(label, label, i, sep + 1, Via.KEY_VALUE)
    }

    private fun toolLines(i: Int) {
        val raw = doc.lines[i]
        val t = raw.trim()
        if (t.startsWith("FROM ") || (t.startsWith("from ") && doc.inCode[i])) {
            val parts = t.split(' ').filter { it.isNotEmpty() && !it.startsWith("--") }
            val image = parts.getOrNull(1)
            if (image != null && !image.startsWith("$")) {
                val at = raw.indexOf(image)
                emit(i, "runtime.container.image", image, at, image.length, Via.TOOL)
            }
        }
        if (t.startsWith("Linux ") || t.startsWith("Darwin ")) {
            val low = doc.lower[i]
            val os = if (t.startsWith("Linux")) "linux" else "macos"
            emit(i, "os.name", os, raw.indexOf(t), t.indexOf(' '), Via.TOOL)
            for ((word, arch) in ARCHES) {
                val at = low.indexOf(word)
                if (at >= 0) emit(i, "os.arch", arch, at, word.length, Via.TOOL)
            }
        }
        val cmd = t.removePrefix("$ ").removePrefix("% ").removePrefix("> ")
        if (cmd.length < t.length) echoed(i, cmd)
    }

    private fun echoed(i: Int, cmd: String) {
        val parts = cmd.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[1] !in VERSION_FLAGS) return
        val hit = names.firstOrNull { it.first == Tokens.lower(parts[0]) } ?: return
        var n = i + 1
        while (n < doc.lines.size && n <= i + 2 && doc.lines[n].isBlank()) n++
        if (n >= doc.lines.size) return
        val line = doc.lines[n]
        for (j in line.indices) {
            val v = Tokens.version(line, j, hit.second.scheme) ?: continue
            val start = if (line[j] == 'v' || line[j] == 'V') j + 1 else j
            emitVersion(n, hit.second, v.first, start, Via.TOOL, null)
            return
        }
    }

    private fun versions(i: Int) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        val used = BooleanArray(raw.length)
        val first = raw.length - raw.trimStart().length
        for ((name, field) in names) {
            var at = low.indexOf(name)
            while (at >= 0) {
                val end = at + name.length
                val free = (at until end).none { used[it] }
                val rightOk = Tokens.endsAt(low, end) || Tokens.isDigit(low[end])
                if (free && rightOk && Tokens.wordAt(low, at, name) && ahead(low, at)) {
                    val hit = afterName(raw, low, end, field.scheme)
                    if (hit != null && (!field.generic || dotted(low, end, hit))) {
                        for (k in at until end) used[k] = true
                        val anchored = doc.inCode[i] && at == first
                        val via = if (anchored) Via.TOOL else Via.PATTERN
                        emitVersion(i, field, hit.first, hit.second, via, null)
                    }
                }
                at = low.indexOf(name, at + 1)
            }
        }
    }

    /**
     * A generic name needs a dotted version or the word `version` before it, and four numbers
     * joined by dots read as an address.
     */
    private fun dotted(low: String, end: Int, hit: Pair<String, Int>): Boolean {
        val token = hit.first
        return !address(token) &&
            (token.contains('.') || low.substring(end, hit.second).contains("version"))
    }

    private fun address(token: String) =
        token.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() != null } }

    private fun ahead(low: String, at: Int) =
        at == 0 || low[at - 1] != '-' || at < 2 || low[at - 2] == ' '

    /** Token and start column of the version after a name, past the [words]. */
    private fun afterName(
        raw: String,
        low: String,
        from: Int,
        scheme: VersionScheme,
        words: List<String> = Names.gapWords,
    ): Pair<String, Int>? {
        var j = from
        var count = 0
        while (true) {
            while (j < raw.length && raw[j] in " \t:=@/(\"'`-[") j++
            val v = Tokens.version(raw, j, scheme)
            if (v != null) return v.first to (if (raw[j] == 'v' || raw[j] == 'V') j + 1 else j)
            val gap = Tokens.gapWordAt(low, j, words) ?: return null
            j += gap.length
            if (++count > MAX_GAP) return null
        }
    }
    // #endregion

    // #region text fields
    private fun operatingSystem(i: Int, from: Int, to: Int, via: Via, label: String?) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        val scope = minOf(to, low.length)
        for (d in Names.distros) {
            var at = low.indexOf(d, from)
            while (at in 0 until scope) {
                val end = at + d.length
                val tag =
                    (at > 0 && low[at - 1] in "-:/._") || (end < low.length && low[end] == '-')
                if (!tag && Tokens.wordAt(low, at, d) && Tokens.endsAt(low, end)) {
                    val ver = gapVersion(raw, low, end, OS_GAP)
                    val side = sideOf(i, at, end, ver, label)
                    emit(i, "os.release.ID", d, at, d.length, via, label, side = side)
                    emit(i, "os.name", "linux", at, d.length, via, label, INFER_SCALE, side)
                    ver?.let { (v, c) ->
                        val id = if (d == "ubuntu") majorMinor(v) else v
                        emit(i, "os.release.VERSION_ID", id, c, v.length, via, label, side = side)
                    }
                }
                at = low.indexOf(d, at + 1)
            }
        }
        for (m in listOf("macos", "mac os x", "mac os", "osx", "os x", "darwin")) {
            val at = low.indexOf(m, from)
            if (at in 0 until scope && Tokens.wordAt(low, at, m)) {
                val ver = gapVersion(raw, low, at + m.length, Names.codenames + listOf("version"))
                val side = sideOf(i, at, at + m.length, ver, label)
                emit(i, "os.name", "macos", at, m.length, via, label, side = side)
                ver?.let { (v, c) ->
                    emit(i, "os.version", v, c, v.length, via, label, side = side)
                }
                break
            }
        }
        val win = low.indexOf("windows", from)
        if (win in 0 until scope && Tokens.wordAt(low, win, "windows")) {
            val ver = gapVersion(raw, low, win + "windows".length, listOf("version"))
            val side = sideOf(i, win, win + "windows".length, ver, label)
            emit(i, "os.name", "windows", win, "windows".length, via, label, side = side)
            ver?.let { (v, c) -> emit(i, "os.version", v, c, v.length, via, label, side = side) }
        } else {
            val w = low.indexOf("win1", from)
            if (w in 0 until scope && Tokens.wordAt(low, w, "win") && low.length >= w + 5 &&
                low.substring(w + 3, w + 5) in listOf("10", "11")
            ) {
                emit(i, "os.name", "windows", w, 5, via, label)
                emit(i, "os.version", low.substring(w + 3, w + 5), w + 3, 2, via, label)
            }
        }
        val lin = low.indexOf("linux", from)
        if (lin in 0 until scope && Tokens.wordAt(low, lin, "linux") &&
            (lin + 5 == low.length || !Tokens.isAlnum(low[lin + 5]))
        ) {
            emit(i, "os.name", "linux", lin, 5, via, label)
        }
        val ios = low.indexOf("ios", from)
        if (ios in 0 until scope && Tokens.wordAt(low, ios, "ios") &&
            (ios + 3 == low.length || !Tokens.isAlnum(low[ios + 3]))
        ) {
            val ver = gapVersion(raw, low, ios + 3, listOf("version"))
            val side = sideOf(i, ios, ios + 3, ver, label)
            emit(i, "os.name", "ios", ios, 3, via, label, side = side)
            ver?.let { (v, c) -> emit(i, "os.version", v, c, v.length, via, label, side = side) }
        }
        val and = low.indexOf("android", from)
        if (and in 0 until scope && Tokens.wordAt(low, and, "android") &&
            Names.androidTools.none { low.startsWith(" $it", and + 7) }
        ) {
            val ver = gapVersion(raw, low, and + 7, listOf("version"))
            val side = sideOf(i, and, and + 7, ver, label)
            emit(i, "os.name", "android", and, 7, via, label, side = side)
            ver?.let { (v, c) -> emit(i, "os.version", v, c, v.length, via, label, side = side) }
        }
    }

    private fun gapVersion(
        raw: String,
        low: String,
        from: Int,
        words: List<String>,
    ): Pair<String, Int>? {
        var j = from
        var count = 0
        while (count <= MAX_GAP) {
            while (j < raw.length && raw[j] in " \t:=-/(") j++
            val v = Tokens.version(raw, j)
            if (v != null) return v.first to (if (raw[j] == 'v' || raw[j] == 'V') j + 1 else j)
            val w = Tokens.gapWordAt(low, j, words) ?: return null
            j += w.length
            count++
        }
        return null
    }

    /** Side of a name and its version together: the version's neighbours decide. */
    private fun sideOf(i: Int, at: Int, end: Int, ver: Pair<String, Int>?, label: String?): Side =
        if (ver == null) {
            Sides.of(doc.lower[i], at, end, label)
        } else {
            Sides.of(doc.lower[i], ver.second, ver.second + ver.first.length, label)
        }

    private fun majorMinor(v: String): String {
        val parts = v.split('.')
        return if (parts.size > 2) parts.take(2).joinToString(".") else v
    }

    private fun arch(i: Int, from: Int = 0, via: Via = Via.PATTERN, label: String? = null) {
        val low = doc.lower[i]
        for ((word, arch) in ARCHES) {
            var at = low.indexOf(word, from)
            while (at >= 0) {
                val end = at + word.length
                if (Tokens.wordAt(low, at, word) && Tokens.endsAt(low, end)) {
                    emit(i, "os.arch", arch, at, word.length, via, label)
                }
                at = low.indexOf(word, at + 1)
            }
        }
        val apple = low.indexOf("apple m", from)
        if (apple >= 0 && apple + 7 < low.length && low[apple + 7] in '1'..'4') {
            emit(i, "os.arch", "aarch64", apple, 8, via, label)
        }
    }

    private fun browser(i: Int, via: Via, nameOnly: Boolean, from: Int = 0, label: String? = null) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        val chromium = low.contains("chrome/") || low.contains("chromium/")
        for ((word, canonical) in Names.browsers) {
            if (word == "safari" && chromium) continue
            var at = low.indexOf(word, from)
            while (at >= 0) {
                val end = at + word.length
                val ok = Tokens.wordAt(low, at, word) && Tokens.endsAt(low, end)
                if (ok) {
                    val webkit = word == "safari" && low.contains("version/") &&
                        low.contains("safari/")
                    val hit = if (webkit) {
                        val v = low.indexOf("version/") + "version/".length
                        Tokens.version(raw, v)?.let { it.first to v }
                    } else {
                        gapVersion(raw, low, end, listOf("version", "ver"))
                    }
                    if (hit != null) {
                        val side = sideOf(i, at, end, hit, label)
                        emit(i, "browser.name", canonical, at, word.length, via, label, side = side)
                        val v = hit.first
                        emit(i, "browser.version", v, hit.second, v.length, via, label, side = side)
                    } else if (nameOnly) {
                        emit(i, "browser.name", canonical, at, word.length, via, label)
                    }
                }
                at = low.indexOf(word, at + 1)
            }
        }
    }

    private fun continuous(i: Int, from: Int = 0, via: Via = Via.PATTERN, label: String? = null) {
        val low = doc.lower[i]
        for ((phrase, id) in Names.ci) {
            var at = low.indexOf(phrase, from)
            while (at >= 0) {
                if (Tokens.wordAt(low, at, phrase)) {
                    emit(i, "ci.provider", id, at, phrase.length, via, label)
                }
                at = low.indexOf(phrase, at + 1)
            }
        }
        for (phrase in Names.local) {
            var at = low.indexOf(phrase, from)
            while (at >= 0) {
                if (Tokens.wordAt(low, at, phrase)) {
                    emit(i, "ci.provider", "local", at, phrase.length, via, label)
                }
                at = low.indexOf(phrase, at + 1)
            }
        }
        if (via == Via.PATTERN) {
            for (word in low.split(' ')) {
                val w = word.trim('`', '"', '\'', ',', '.', ')', '(')
                if (runnerShape(w)) {
                    val at = low.indexOf(w)
                    emit(i, "ci.runner.label", w, at, w.length, via, label)
                }
            }
        }
    }

    private fun runnerLabel(i: Int, from: Int, via: Via, label: String) {
        val (v, c) = token(doc.lines[i], from) ?: return
        val low = Tokens.lower(v)
        if (runnerShape(low)) emit(i, "ci.runner.label", low, c, v.length, via, label)
    }

    private fun runnerShape(w: String): Boolean {
        val prefix = RUNNER_PREFIXES.firstOrNull { w.startsWith(it) } ?: return false
        val rest = w.substring(prefix.length)
        return rest.isNotEmpty() && rest.all { Tokens.isAlnum(it) || it == '.' || it == '-' } &&
            (rest.endsWith("latest") || rest.any { Tokens.isDigit(it) })
    }

    private fun platform(i: Int) {
        val low = doc.lower[i]
        val local = listOf("wrangler dev", "miniflare", "workerd-local")
        for (p in local) {
            val at = low.indexOf(p)
            if (at >= 0) emit(i, "runtime.platform", "workerd-local", at, p.length, Via.PATTERN)
        }
        val cf = low.indexOf("cloudflare")
        if (cf >= 0 && DEPLOYED.any { Tokens.hasWord(low, it) }) {
            emit(i, "runtime.platform", "cloudflare-deployed", cf, "cloudflare".length, Via.PATTERN)
        }
    }
    // #endregion

    companion object {
        const val BAG_BONUS = 100
        const val HEDGE = 700
        const val INFER_SCALE = 900
        const val LOCALE_SCALE = 900
        const val MAX_LABEL = 40
        const val MAX_LABEL_WORDS = 4
        const val MAX_GAP = 2

        val OS_GAP = listOf("version", "release", "lts", "linux")
        val RUNNER_PREFIXES = listOf("ubuntu-", "windows-", "macos-")
        val DEPLOYED = listOf("deployed", "in production", "in prod", "wrangler deploy", "live")

        val VERSION_FLAGS = setOf("-v", "-V", "--version", "-version", "version")

        val ARCHES = listOf(
            "x86_64" to "x86_64",
            "x86-64" to "x86_64",
            "amd64" to "x86_64",
            "x64" to "x86_64",
            "arm64" to "aarch64",
            "aarch64" to "aarch64",
            "apple silicon" to "aarch64",
        )
    }
}

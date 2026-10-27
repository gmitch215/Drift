package dev.gmitch215.drift.triage

/**
 * Facts from pasted logs, commands and configuration that the key table does not name: package
 * versions in pip output, container images, environment variables, binding names with a major
 * version, server platforms in log lines and jar names, `docker version` blocks and the limit an
 * error names. Every path needs a shape that only that kind of text has.
 */
internal class Mentions(private val ex: Extractor) {
    private val doc = ex.doc
    private val dockerInfo = doc.lower.any { it.trimStart().startsWith("storage driver:") }

    fun run() {
        for (i in doc.lines.indices) {
            if (doc.fence[i] || doc.heading[i] != null) continue
            pythonPackages(i)
            bindings(i)
            images(i)
            environment(i)
            platforms(i)
            jars(i)
            dockerBlocks(i)
            limits(i)
        }
    }

    private fun words(raw: String, from: Int = 0): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var pos = from
        for (word in raw.substring(from).split(' ')) {
            if (word.isNotEmpty()) out += word to pos
            pos += word.length + 1
        }
        return out
    }

    // #region python packages
    private fun pythonKey(name: String): String? {
        val clean = Tokens.lower(name.substringBefore('[')).replace('_', '-').replace('.', '-')
        val ok = clean.length > 1 && clean[0] in 'a'..'z' &&
            clean.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        return if (ok) "project.python.$clean" else null
    }

    /** `name-1.2.3` split at the first dash before a digit; [whole] needs the version to end it. */
    private fun dashed(word: String, whole: Boolean): Triple<String, String, Int>? {
        for (k in 1 until word.length - 1) {
            if (word[k] != '-' || !Tokens.isDigit(word[k + 1])) continue
            val v = Tokens.version(word, k + 1) ?: return null
            if (whole && v.second != word.length) return null
            return Triple(word.substring(0, k), v.first, k + 1)
        }
        return null
    }

    private fun pythonPackages(i: Int) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        val done = low.indexOf("successfully installed")
        if (done >= 0) {
            for ((w, c) in words(raw, done + "successfully installed".length)) {
                val (name, ver, at) = dashed(w.trimEnd(',', '.'), true) ?: continue
                val key = pythonKey(name) ?: continue
                ex.emit(i, key, ver, c + at, ver.length, Via.TOOL)
            }
        }
        val sat = low.indexOf("requirement already satisfied:")
        if (sat >= 0) {
            val name = words(raw, sat + "requirement already satisfied:".length).firstOrNull()
            val open = raw.lastIndexOf('(')
            val v = if (open > 0) Tokens.version(raw, open + 1) else null
            val key = name?.let { pythonKey(it.first) }
            if (key != null &&
                v != null
            ) {
                ex.emit(i, key, v.first, open + 1, v.first.length, Via.TOOL)
            }
        }
        for ((w, c) in words(raw)) {
            val file = w.substringBefore('?').substringAfterLast('/')
            val stem = ARCHIVES.firstOrNull { file.endsWith(it) }?.let { file.removeSuffix(it) }
            if (stem != null && w.contains('/')) {
                val (name, ver, at) = dashed(stem, false) ?: continue
                val key = pythonKey(name) ?: continue
                val col = c + w.lastIndexOf('/') + 1 + at
                ex.emit(i, key, ver, col, ver.length, Via.TOOL)
            }
        }
        val install = PIP.firstOrNull { low.contains(it) }
        val pinned = install != null || (doc.inCode[i] && requirementLine(doc.lines[i].trim()))
        if (pinned) {
            val from = if (install != null) low.indexOf(install) + install.length else 0
            for ((w, c) in words(raw, from)) {
                val at = w.indexOf("==")
                if (at <= 0 || w.startsWith("-")) continue
                val key = pythonKey(w.substring(0, at)) ?: continue
                val start = if (w.startsWith("===", at)) at + 3 else at + 2
                val v = Tokens.version(w, start) ?: continue
                ex.emit(i, key, v.first, c + start, v.first.length, Via.KEY_VALUE)
            }
        }
    }

    /** A line that is one `name==1.2.3` pin, as in a requirements file. */
    private fun requirementLine(t: String): Boolean {
        val at = t.indexOf("==")
        if (at < 2 || t.take(at).any { it == ' ' }) return false
        val v = Tokens.version(t, at + 2) ?: return false
        return v.first.contains('.') && (v.second >= t.length || t[v.second] in " ;#,")
    }
    // #endregion

    // #region bindings
    private fun bindings(i: Int) {
        val low = doc.lower[i]
        for ((prefix, key) in Names.bindings) {
            var at = low.indexOf(prefix)
            while (at >= 0) {
                val end = at + prefix.length
                var digits = end
                while (digits < low.length && Tokens.isDigit(low[digits])) digits++
                if (Tokens.wordAt(low, at, prefix) && digits - end == 1 &&
                    Tokens.endsAt(low, digits)
                ) {
                    ex.emit(i, key, low.substring(end, digits), end, digits - end, Via.PATTERN)
                }
                at = low.indexOf(prefix, at + 1)
            }
        }
    }
    // #endregion

    // #region images
    private fun imageRef(word: String): String? {
        var w = word.trim(*WRAP).trimEnd('.', ':').trim(*WRAP)
        w = w.removePrefix("docker://").substringBefore('@')
        if (w.isEmpty() || !w[0].isLetterOrDigit() || w.contains('$') ||
            w.contains('{')
        ) {
            return null
        }
        return w.takeIf { it.all { c -> Tokens.isAlnum(c) || c in "._-/:" } }
    }

    private fun registered(w: String): Boolean {
        val host = w.substringBefore('/')
        return w.contains('/') && Names.registries.any { host == it || host.endsWith(".$it") }
    }

    private fun images(i: Int) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        for ((word, col) in words(raw)) {
            val ref = imageRef(word) ?: continue
            if (registered(ref) || word.trimStart('`', '"', '\'', '(').startsWith("docker://")) {
                ex.emit(
                    i,
                    "runtime.container.image",
                    ref,
                    col + word.indexOf(ref),
                    ref.length,
                    Via.PATTERN,
                )
            }
        }
        for (verb in VERBS) {
            val at = low.indexOf(verb)
            if (at < 0 || !Tokens.wordAt(low, at, verb)) continue
            val list = words(raw, at + verb.length)
            var k = 0
            while (k < list.size) {
                val w = list[k].first
                if (w.startsWith("-")) {
                    k += if (w in Names.dockerValueFlags) 2 else 1
                    continue
                }
                val ref = imageRef(w)
                val shaped = doc.inCode[i] || w.any { it in ":/@" }
                if (ref != null && shaped && !ref.startsWith("http")) {
                    val via = if (doc.inCode[i]) Via.TOOL else Via.PATTERN
                    ex.emit(i, "runtime.container.image", ref, list[k].second, ref.length, via)
                }
                break
            }
        }
    }
    // #endregion

    // #region environment variables
    private fun environment(i: Int) {
        val raw = doc.lines[i]
        val t = raw.trim()
        val tokens = words(raw)
        for ((k, pair) in tokens.withIndex()) {
            val w = pair.first
            val next = tokens.getOrNull(k + 1)
            val flagged = w == "-e" || w == "--env"
            val leading = k == 0 && (w == "ENV" || w == "export" || w == "set")
            val prompted = k == 1 && tokens[0].first in PROMPTS && w in setOf("export", "set")
            val keyword = leading || prompted
            if ((flagged || keyword) && next != null) assign(i, next.first, next.second)
            if (w.startsWith("--env=")) assign(i, w.removePrefix("--env="), pair.second + 6)
        }
        val item = t.removePrefix("- ").removePrefix("* ").trim()
        if (item.contains('=') && inEnvironmentBlock(i, false)) assign(i, item, raw.indexOf(item))
        val colon = item.indexOf(": ")
        if (colon > 0 && item.substring(0, colon).contains('_') && inEnvironmentBlock(i, true)) {
            assign(i, "${item.substring(0, colon)}=${item.substring(colon + 2)}", raw.indexOf(item))
        }
    }

    private fun assign(i: Int, pair: String, col: Int) {
        val at = pair.indexOf('=')
        if (at < 2) return
        val name = pair.substring(0, at)
        if (!name[0].isUpperCase() ||
            !name.all { it.isUpperCase() || Tokens.isDigit(it) || it == '_' }
        ) {
            return
        }
        val low = Tokens.lower(name)
        if (Names.secretParts.any { low.contains(it) }) return
        val value = pair.substring(at + 1).trim().trim('"', '\'', '`')
        if (value.isEmpty() || value.length > MAX_VALUE || value[0] in "$<*{" ||
            value.contains('$')
        ) {
            return
        }
        if (value.all { it == 'x' || it == 'X' || it == '.' || it == '*' }) return
        val key = Names.envNames[name] ?: "env.$name"
        ex.emit(i, key, value, col + at + 1, value.length, Via.KEY_VALUE)
    }

    private fun inEnvironmentBlock(i: Int, nested: Boolean): Boolean {
        val indent = doc.lines[i].length - doc.lines[i].trimStart().length
        var j = i - 1
        while (j >= 0 && j >= i - ENV_LOOKBACK) {
            val line = doc.lines[j]
            if (line.isBlank()) {
                j--
                continue
            }
            val at = line.length - line.trimStart().length
            if (at < indent || (!nested && at == 0 && indent == 0)) {
                val head = Tokens.lower(line.trim()).removePrefix("- ")
                return head == "environment:" || head == "env:"
            }
            j--
        }
        return false
    }
    // #endregion

    // #region platforms

    /** The platform whose name starts at [at] in [low] and the index after it, or null. */
    fun platformAt(low: String, at: Int): Pair<String, Int>? {
        val p = Names.platforms.firstOrNull {
            Tokens.wordAt(low, at, it) &&
                (Tokens.endsAt(low, at + it.length) || low[at + it.length] in "-_")
        } ?: return null
        return p to at + p.length
    }

    /** Emits the platform at the start of a labelled value and the version after it. */
    fun platformValue(i: Int, from: Int, via: Via, label: String?) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        var j = from
        while (j < low.length && low[j] in " \t\"'`:") j++
        val (p, end) = platformAt(low, j) ?: return
        ex.emit(i, "runtime.platform", p, j, p.length, via, label)
        version(i, raw, low, p, end, via, label)
    }

    private fun version(
        i: Int,
        raw: String,
        low: String,
        p: String,
        end: Int,
        via: Via,
        label: String?,
    ): Boolean {
        var j = end
        while (j < low.length && low[j] in " \t:=-_/@") j++
        if (Tokens.gapWordAt(low, j, Names.gapWords) != null) {
            j += Tokens.gapWordAt(low, j, Names.gapWords)!!.length
            while (j < low.length && low[j] in " \t:=-_/@") j++
        }
        val v = Tokens.version(raw, j) ?: return false
        val col = if (raw[j] == 'v' || raw[j] == 'V') j + 1 else j
        ex.emit(i, "tool.$p.version", v.first, col, v.first.length, via, label)
        return true
    }

    private fun platforms(i: Int) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        for (p in Names.platforms) {
            var at = low.indexOf(p)
            while (at >= 0) {
                val end = at + p.length
                if (Tokens.wordAt(low, at, p) && Tokens.endsAt(low, end)) {
                    val running = low.lastIndexOf("running ", at).let { it >= 0 && it + 8 == at }
                    val named = low.startsWith(" version", end) || low.startsWith(" build", end)
                    val versioned = named && version(i, raw, low, p, end, Via.PATTERN, null)
                    if (running || versioned) {
                        ex.emit(i, "runtime.platform", p, at, p.length, Via.PATTERN)
                    }
                }
                at = low.indexOf(p, at + 1)
            }
        }
    }

    private fun jars(i: Int) {
        val raw = doc.lines[i]
        var pos = 0
        for (token in raw.split(' ', '[', ']', '~', '/', ':', '(', ')')) {
            val col = pos
            pos += token.length + 1
            if (!token.endsWith(".jar")) continue
            val base = token.removeSuffix(".jar")
            val lowBase = Tokens.lower(base)
            val p = Names.platforms.firstOrNull { lowBase == it || lowBase.startsWith("$it-") }
            if (p == null) continue
            ex.emit(i, "runtime.platform", p, col, p.length, Via.PATTERN)
            val (_, ver, at) = dashed(base, false) ?: continue
            ex.emit(i, "tool.$p.version", ver, col + at, ver.length, Via.PATTERN)
        }
    }
    // #endregion

    // #region docker output
    private fun dockerBlocks(i: Int) {
        val t = doc.lower[i].trim()
        val raw = doc.lines[i]
        val head = t == "client:" || t == "server:" || t == "engine:" ||
            t.startsWith("client: docker engine") || t.startsWith("server: docker engine")
        if (head) {
            for (n in i + 1..minOf(i + 3, doc.lines.size - 1)) {
                val line = doc.lines[n].trim()
                if (Tokens.lower(line).startsWith("version:")) {
                    val at = doc.lines[n].indexOf(':') + 1
                    ver(n, at)
                    break
                }
            }
        }
        if (dockerInfo && t.startsWith("server version:")) ver(i, raw.indexOf(':') + 1)
        if (dockerInfo && t.startsWith("kernel version:")) {
            val v = raw.substringAfter(':').trim()
            if (Tokens.version(v, 0) != null) {
                ex.emit(i, "kernel.release", v, raw.indexOf(v), v.length, Via.TOOL)
            }
        }
    }

    private fun ver(line: Int, from: Int) {
        val raw = doc.lines[line]
        var j = from
        while (j < raw.length && raw[j] == ' ') j++
        val v = Tokens.version(raw, j) ?: return
        ex.emit(line, "tool.docker.version", v.first, j, v.first.length, Via.TOOL)
    }
    // #endregion

    // #region limits
    private fun limits(i: Int) {
        val low = doc.lower[i]
        for ((phrase, key) in LIMITS) {
            val at = low.indexOf(phrase)
            if (at >=
                0
            ) {
                ex.emit(i, key, "exceeded", at, phrase.length, Via.PATTERN, side = Side.FAILING)
            }
        }
    }
    // #endregion

    private companion object {
        const val MAX_VALUE = 100
        val WRAP = charArrayOf('`', '"', '\'', ',', ';', '(', ')', '[', ']', '<', '>')
        const val ENV_LOOKBACK = 30
        val ARCHIVES = listOf(".whl", ".tar.gz", ".zip")
        val PIP =
            listOf("pip install ", "pip3 install ", "pipenv install ", "poetry add ", "uv add ")
        val VERBS =
            listOf("docker pull ", "docker run ", "docker create ", "podman pull ", "podman run ")
        val PROMPTS = setOf("$", "%", ">")
        val LIMITS = listOf(
            "disk quota exceeded" to "limits.disk-quota",
            "too many open files" to "limits.nofile",
            "no space left on device" to "limits.disk-space",
        )
    }
}

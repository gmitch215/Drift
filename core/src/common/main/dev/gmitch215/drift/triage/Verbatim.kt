package dev.gmitch215.drift.triage

import dev.gmitch215.drift.symptom.Signature

/** Facts that come from pasted build files, launch flags and error output. */
internal class Verbatim(private val ex: Extractor) {
    private val doc = ex.doc

    fun run() {
        var deps = NO_DEPS
        for (i in doc.lines.indices) {
            if (doc.fence[i] || doc.heading[i] != null) continue
            val t = doc.lines[i].trim()
            if (!doc.inCode[i]) {
                deps = NO_DEPS
            } else if (npmBlock(t)) {
                deps = NPM
            } else if (t.contains("\"require\"") || t.contains("\"require-dev\"")) {
                deps = COMPOSER
            } else if (deps != NO_DEPS && t.startsWith("}")) {
                deps = NO_DEPS
            }
            coordinates(i)
            pluginVersion(i)
            installCommands(i)
            if (deps != NO_DEPS) manifestLine(i, deps == COMPOSER)
            goModule(i)
        }
        flags()
        signature()
    }

    private fun npmBlock(t: String) = t.contains("\"dependencies\"") ||
        t.contains("\"devDependencies\"") || t.contains("\"peerDependencies\"")

    private fun lib(i: Int, key: String, token: String, col: Int) =
        ex.emit(i, key, token, col, token.length, Via.KEY_VALUE)

    private fun coordinates(i: Int) {
        val raw = doc.lines[i]
        var s = 0
        while (s < raw.length) {
            val q = raw[s]
            if (q != '"' && q != '\'') {
                s++
                continue
            }
            val e = raw.indexOf(q, s + 1)
            if (e < 0) return
            val parts = raw.substring(s + 1, e).split(':')
            if (parts.size in 3..4 && parts[0].contains('.') && parts.all { it.isNotEmpty() }) {
                val v = Tokens.version(parts[2], 0)
                if (v != null && v.second == parts[2].length && parts[0].all(::coordChar) &&
                    parts[1].all(::coordChar)
                ) {
                    val col = s + 1 + parts[0].length + parts[1].length + 2
                    lib(i, "project.gradle.${parts[0]}:${parts[1]}", v.first, col)
                }
            }
            s = e + 1
        }
    }

    private fun coordChar(c: Char) = Tokens.isAlnum(c) || c == '.' || c == '-' || c == '_'

    private fun pluginVersion(i: Int) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        val at = low.indexOf(" version ")
        if (at < 0) return
        val key = when {
            low.contains("kotlin(") || low.contains("org.jetbrains.kotlin") ->
                "project.gradle.kotlin.version"

            low.contains("com.android.") -> "project.gradle.agp.version"

            else -> return
        }
        var j = at + " version ".length
        while (j < raw.length && raw[j] in "\"'` ") j++
        val v = Tokens.version(raw, j) ?: return
        lib(i, key, v.first, j)
    }

    private fun installCommands(i: Int) {
        val raw = doc.lines[i]
        val low = doc.lower[i]
        val cmd = INSTALLERS.firstOrNull { low.contains(it) }
        if (cmd != null) {
            var pos = low.indexOf(cmd) + cmd.length
            for (word in raw.substring(pos).split(' ')) {
                val at = word.lastIndexOf('@')
                if (at > 0 && word[0] != '-') {
                    val range = word.substring(at + 1).trimStart('^', '~', 'v')
                    val v = Tokens.version(range, 0)
                    if (v != null) {
                        lib(i, "project.npm.${word.substring(0, at)}", v.first, pos + at + 1)
                    }
                }
                pos += word.length + 1
            }
        }
        val composer = low.indexOf("composer require ")
        if (composer >= 0) {
            var pos = composer + "composer require ".length
            for (word in raw.substring(pos).split(' ')) {
                val at = word.indexOf(':')
                if (at > 0 && word.contains('/') && word.indexOf('/') < at) {
                    val v = Tokens.version(word.substring(at + 1).trimStart('^', '~'), 0)
                    if (v != null) {
                        val key = "project.composer.${word.substring(0, at)}"
                        lib(i, key, v.first, pos + at + 1)
                    }
                }
                pos += word.length + 1
            }
        }
    }

    private fun manifestLine(i: Int, composer: Boolean) {
        val raw = doc.lines[i]
        val a = raw.indexOf('"')
        val b = if (a >= 0) raw.indexOf('"', a + 1) else -1
        val c = if (b >= 0) raw.indexOf('"', b + 1) else -1
        val d = if (c >= 0) raw.indexOf('"', c + 1) else -1
        if (d < 0 || raw.substring(b + 1, c).trim() != ":") return
        val name = raw.substring(a + 1, b)
        val range = raw.substring(c + 1, d)
        val trimmed = range.trimStart('^', '~', '>', '=', '<', ' ')
        val v = Tokens.version(trimmed, 0) ?: return
        val col = c + 1 + (range.length - trimmed.length)
        if (composer) {
            if (!name.contains('/')) return
            lib(i, "project.composer.$name", v.first, col)
        } else {
            lib(i, "project.npm.$name", v.first, col)
        }
    }

    private fun goModule(i: Int) {
        val t = doc.lines[i].trim().removePrefix("require ").trim()
        val parts = t.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2 || !parts[0].contains('.') || !parts[0].contains('/')) return
        if (!parts[1].startsWith("v")) return
        val v = Tokens.version(parts[1], 0) ?: return
        lib(i, "project.go.${parts[0]}", v.first, doc.lines[i].indexOf(parts[1]) + 1)
    }

    private fun flags() {
        val tokens = mutableListOf<String>()
        var first: Triple<Int, Int, Int>? = null
        for (i in doc.lines.indices) {
            if (doc.fence[i] || doc.heading[i] != null) continue
            val raw = doc.lines[i]
            var pos = 0
            for (word in raw.split(' ')) {
                val w = word.trim('`', '"', '\'', ',', ';', ')', '(')
                if (isFlag(w) && w !in tokens) {
                    tokens += w
                    if (first == null) first = Triple(i, raw.indexOf(w, pos), w.length)
                }
                pos += word.length + 1
            }
        }
        val f = first ?: return
        val joined = tokens.joinToString(" ")
        ex.emit(f.first, "runtime.jvm.options", joined, f.second, f.third, Via.KEY_VALUE)
    }

    private fun isFlag(w: String): Boolean {
        if (w.startsWith("-XX:") && w.length > 5) return true
        if (HEAP_FLAGS.any { w.startsWith(it) }) return true
        if (w.length > 3 && w.startsWith("-D") && w[2].isLetter()) {
            val low = Tokens.lower(w)
            return SECRET_WORDS.none { low.contains(it) }
        }
        return false
    }

    private fun signature() {
        var best = -1
        var rank = 0
        for (i in doc.lines.indices) {
            if (doc.fence[i] || doc.heading[i] != null || !doc.inCode[i]) continue
            val t = doc.lines[i].trim()
            if (t.startsWith("$ ") || t.isEmpty()) continue
            val r = errorRank(doc.lower[i])
            if (r > rank) {
                rank = r
                best = i
            }
        }
        if (best >= 0) {
            val raw = doc.lines[best]
            val text = raw.trim()
            val sig = Signature.normalize(text)
            val at = raw.indexOf(text)
            ex.emit(best, "run:signature", sig, at, text.length, Via.TOOL, side = Side.FAILING)
            return
        }
        for (i in doc.lines.indices) {
            if (doc.fence[i] || doc.heading[i] != null) continue
            val quoted = quotedError(doc.lines[i]) ?: continue
            val raw = doc.lines[i]
            val sig = Signature.normalize(quoted)
            val at = raw.indexOf(quoted)
            ex.emit(i, "run:signature", sig, at, quoted.length, Via.PATTERN, side = Side.FAILING)
            return
        }
    }

    private fun errorRank(low: String): Int = when {
        low.contains("error:") || low.contains("exception:") || low.contains("fatal:") ||
            low.contains("npm err!") || low.contains("unrecognized vm option") ||
            low.contains("traceback (most recent") -> 3

        low.contains("error") || low.contains("exception") || low.contains("cannot find") ||
            low.contains("no such file") || low.contains("not found") -> 2

        else -> 0
    }

    private fun quotedError(raw: String): String? {
        for (q in listOf('`', '"')) {
            var s = raw.indexOf(q)
            while (s >= 0) {
                val e = raw.indexOf(q, s + 1)
                if (e < 0) break
                val inner = raw.substring(s + 1, e)
                if (inner.length > 8 && errorRank(Tokens.lower(inner)) >= 3) return inner
                s = raw.indexOf(q, e + 1)
            }
        }
        return null
    }

    private companion object {
        const val NO_DEPS = 0
        const val NPM = 1
        const val COMPOSER = 2

        val INSTALLERS = listOf(
            "npm i ",
            "npm install ",
            "npm add ",
            "yarn add ",
            "pnpm add ",
            "pnpm i ",
            "bun add ",
        )
        val HEAP_FLAGS = listOf("-Xmx", "-Xms", "-Xss", "-Xshare", "-Xlog", "-Xverify")
        val SECRET_WORDS = listOf("key", "token", "secret", "password", "auth", "cred")
    }
}

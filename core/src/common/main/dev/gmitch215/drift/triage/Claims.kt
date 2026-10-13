package dev.gmitch215.drift.triage

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Detect
import dev.gmitch215.drift.know.Env
import dev.gmitch215.drift.know.Node
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Verdict
import dev.gmitch215.drift.know.paths
import kotlin.math.abs

/** What a claim is checked against: a rule of the catalog or two Atlas columns of one probe. */
sealed interface Knowledge {
    val label: String
}

data class RuleRef(val ruleId: String) : Knowledge {
    override val label get() = "rule:$ruleId"
}

/** [left] and [right] are Atlas targets that print different results for [probe] at 2.4.20. */
data class AtlasRef(val probe: String, val left: String, val right: String) : Knowledge {
    override val label get() = "atlas:$probe ($left, $right)"
}

/**
 * A statement Drift can contradict. Every list of [phrases] needs one hit in the same sentence;
 * an [affirm] claim fires on a sentence without a negation. [needs] is a detect tree over the
 * failing facts (written as JSON, side `b`); a missing fact leaves it unknown and the claim silent.
 */
data class Claim(
    val id: String,
    val phrases: List<List<String>>,
    val says: String,
    val knowledge: String,
    val source: Knowledge,
    val needs: String? = null,
    val affirm: Boolean = true,
)

/** A sentence of the issue that a [Claim] contradicts. [line] is 1-based. */
data class ClaimHit(
    val id: String,
    val line: Int,
    val excerpt: String,
    val says: String,
    val knowledge: String,
    val source: String,
    val confidence: Confidence,
) {
    fun toJson(): JsonObject = obj(
        "claim" to JsonString(id),
        "line" to JsonInt(line.toLong()),
        "excerpt" to JsonString(excerpt),
        "says" to JsonString(says),
        "knowledge" to JsonString(knowledge),
        "source" to JsonString(source),
        "confidence" to JsonInt(confidence.permille.toLong()),
    )
}

object Claims {
    /** Confidence of a phrase match before the facts it depends on are weighed in. */
    const val BASE = 700

    private val enough = listOf("same", "identical", "consistent", "equal", "portable")
    private val targets = listOf("jvm", "native", "wasm", "wasmjs", "js", "platform", "target")
    private val works = listOf("works", "working", "available", "included", "bundled", "still")

    val all: List<Claim> = listOf(
        Claim(
            "hash-order-same",
            listOf(
                listOf(
                    "hashmap",
                    "hashset",
                    "hash map",
                    "hash set",
                    "iteration order",
                    "map order",
                ),
                enough + listOf("deterministic", "stable"),
                targets,
            ),
            "HashMap and HashSet iterate in the same order on every Kotlin target",
            "At Kotlin 2.4.20 the JVM iterates in bucket order and Native, Wasm and the " +
                "browsers in insertion order.",
            AtlasRef("kotlin.collections.hash-order", "jvm", "linux"),
        ),
        Claim(
            "double-tostring-same",
            listOf(
                listOf("double", "doubles"),
                listOf("tostring", "to string", "prints", "printing", "format"),
                enough,
                listOf("wasm", "wasmjs", "kotlin/wasm"),
            ),
            "Double.toString prints the same digits on Wasm as on the JVM",
            "At Kotlin 2.4.20 the Wasm family prints some doubles differently from the JVM and " +
                "Native (KT-88414).",
            AtlasRef("kotlin.double.tostring-boundaries", "jvm", "wasm"),
        ),
        Claim(
            "regex-same",
            listOf(
                listOf("regex", "regular expression", "regular expressions"),
                enough,
                targets,
            ),
            "Kotlin Regex accepts the same syntax and gives the same matches on every target",
            "The Regex documentation says the pattern syntax and the option set differ on each " +
                "platform, and the Atlas has the JVM and Wasm in separate groups at 2.4.20.",
            AtlasRef("kotlin.regex.syntax-support", "jvm", "wasm"),
        ),
        Claim(
            "cloudflare-jvm-like-jvm",
            listOf(
                listOf("cloudflare", "workers", "workerd"),
                listOf("jvm", "teavm", "bytebox"),
                enough + listOf("behaves like", "just like", "same as"),
            ),
            "Kotlin/JVM on Workers behaves like Kotlin/JVM on the JDK",
            "The TeaVM-built JVM column on workerd differs from the JVM column, for example in " +
                "HashMap iteration order.",
            AtlasRef("kotlin.collections.hash-order", "jvm", "cloudflare-jvm"),
        ),
        Claim(
            "nashorn-available",
            listOf(listOf("nashorn"), works + listOf("use", "using", "include")),
            "the Nashorn JavaScript engine is available",
            "",
            RuleRef("jdk-15-nashorn-removed"),
            needs = """{"b":"tool.java.version","ver":">=15"}""",
        ),
        Claim(
            "cms-collector-works",
            listOf(
                listOf("concmarksweep", "useconcmarksweepgc", "cms collector", "cms gc"),
                works + listOf("use", "using", "enable", "set"),
            ),
            "the CMS garbage collector can be selected",
            "",
            RuleRef("jdk-14-cms-collector-removed"),
            needs = """{"b":"tool.java.version","ver":">=14"}""",
        ),
        Claim(
            "distutils-available",
            listOf(listOf("distutils"), works + listOf("use", "using")),
            "distutils is part of the standard library",
            "",
            RuleRef("python-3-12-distutils-removed"),
            needs = """{"b":"tool.python.version","ver":">=3.12"}""",
        ),
        Claim(
            "jaxb-in-jdk",
            listOf(
                listOf("javax.xml.bind", "jaxb"),
                works + listOf("part of the jdk", "no dependency", "without a dependency"),
            ),
            "JAXB ships with the JDK",
            "",
            RuleRef("jdk-11-java-ee-modules-removed"),
            needs = """{"b":"tool.java.version","ver":">=11"}""",
        ),
        Claim(
            "security-manager-enable",
            listOf(
                listOf("setsecuritymanager", "security manager", "securitymanager"),
                works + listOf("use", "using", "enable"),
            ),
            "the Security Manager can be enabled",
            "",
            RuleRef("jdk-24-security-manager-disabled"),
            needs = """{"b":"tool.java.version","ver":">=24"}""",
        ),
    )

    private val negations = listOf(
        "not ",
        "n't",
        "no longer",
        "never",
        "cannot",
        "differ",
        "unlike",
        "instead of",
        "removed in",
        "non-",
        "nondeterministic",
        "deprecat",
        "slated",
        "alternativ",
        "instead",
        "would",
        "please",
        "overflow",
    )

    private val parsed = mutableMapOf<String, Node>()

    private fun node(text: String): Node =
        parsed.getOrPut(text) { Detect.parse(CanonicalJson.parse(text), "claim") }

    internal fun check(doc: Doc, facts: List<Fact>, rules: List<Rule>): List<ClaimHit> {
        val failing = facts.filter { it.side == Side.FAILING && !it.key.startsWith("run:") }
        val env = Env(attrs = failing.associate { it.key to it.value })
        val hits = mutableListOf<ClaimHit>()
        for (claim in all) {
            val hit = find(claim, doc, env, failing, rules) ?: continue
            hits += hit
        }
        return hits.sortedWith(compareBy({ it.line }, { it.id }))
    }

    private fun find(
        claim: Claim,
        doc: Doc,
        env: Env,
        failing: List<Fact>,
        rules: List<Rule>,
    ): ClaimHit? {
        var confidence = Confidence(BASE)
        val needs = claim.needs?.let(::node)
        if (needs != null) {
            if (Detect.walk(needs, Env(), env, mutableSetOf()) != Verdict.MATCH) return null
            val keys = needs.paths().toSet()
            val weakest = failing.filter { it.key in keys }.minOfOrNull { it.confidence.permille }
                ?: return null
            confidence = confidence.scale(weakest)
        }
        for (i in doc.lines.indices) {
            if (doc.inCode[i] || doc.fence[i] || doc.heading[i] != null) continue
            for (sentence in sentences(doc.lines[i])) {
                val low = Tokens.lower(sentence)
                if (!linked(low, claim.phrases)) continue
                if (sentence.contains('?')) continue
                if (negations.any { low.contains(it) } == claim.affirm) continue
                val ref = claim.source
                val known = if (ref is RuleRef) {
                    rules.firstOrNull { it.id == ref.ruleId }?.mechanism ?: return null
                } else {
                    claim.knowledge
                }
                val excerpt = if (sentence.length > MAX_EXCERPT) {
                    sentence.substring(0, MAX_EXCERPT)
                } else {
                    sentence
                }
                val text = excerpt.trim()
                return ClaimHit(claim.id, i + 1, text, claim.says, known, ref.label, confidence)
            }
        }
        return null
    }

    /**
     * True when one hit of each group sits within [SPAN] characters of a hit of the group before
     * it, so a phrase and its predicate read as one assertion.
     */
    private fun linked(low: String, groups: List<List<String>>): Boolean {
        var reach = hits(low, groups.first())
        for (group in groups.drop(1)) {
            val next = hits(low, group)
            reach = next.filter { n -> reach.any { abs(it - n) <= SPAN } }
            if (reach.isEmpty()) return false
        }
        return true
    }

    private fun hits(low: String, words: List<String>): List<Int> {
        val out = mutableListOf<Int>()
        for (w in words) {
            var i = low.indexOf(w)
            while (i >= 0) {
                if (Tokens.wordAt(low, i, w) && Tokens.endsAt(low, i + w.length)) out += i
                i = low.indexOf(w, i + 1)
            }
        }
        return out
    }

    private fun sentences(line: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (i in line.indices) {
            val c = line[i]
            val stop = c == '.' || c == '!' || c == '?'
            val end = stop && (i + 1 == line.length || line[i + 1] == ' ')
            if (end) {
                out += line.substring(start, i + 1)
                start = i + 1
            }
        }
        if (start < line.length) out += line.substring(start)
        return out.filter { it.isNotBlank() }
    }

    private const val MAX_EXCERPT = 160
    private const val SPAN = 80
}

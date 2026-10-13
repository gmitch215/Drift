package dev.gmitch215.drift.triage

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Env
import dev.gmitch215.drift.know.Node
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Severity
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.know.Which
import dev.gmitch215.drift.rank.Tier

/**
 * How far a rule's detect tree holds on the stated facts. [MATCH]: every condition holds on
 * facts the issue states. [FAILING_SIDE]: every condition on the failing side holds and the
 * passing side is not stated, reported only with the rule's symptom text in the issue.
 * [SYMPTOM]: the failing-side conditions that can be checked hold, some are not stated, and the
 * issue carries the rule's symptom text.
 */
enum class Grade(val id: String) {
    MATCH("match"),
    FAILING_SIDE("failing-side"),
    SYMPTOM("symptom"),
}

/**
 * A rule that applies to the report. [tier] is the ranker's `known` for a rule whose symptoms are
 * all verified and `plausible` otherwise, and only a [Grade.MATCH] has one: the other grades lack
 * the two-sided difference a tier describes. [confidence] is the weakest fact the match rests on,
 * scaled by 0.7 for [Grade.SYMPTOM]; it is not the chance that the rule is the cause.
 */
data class RuleHit(
    val ruleId: String,
    val title: String,
    val grade: Grade,
    val tier: Tier?,
    val severity: Severity,
    val status: Status,
    val confidence: Confidence,
    val facts: List<String>,
    val symptoms: List<String>,
    val mechanism: String,
) {
    fun toJson(): JsonObject = obj(
        "rule" to JsonString(ruleId),
        "title" to JsonString(title),
        "grade" to JsonString(grade.id),
        "tier" to (tier?.name?.lowercase()?.let(::JsonString) ?: JsonNull),
        "severity" to JsonString(severity.id),
        "status" to JsonString(status.id),
        "confidence" to JsonInt(confidence.permille.toLong()),
        "facts" to JsonArray(facts.map(::JsonString)),
        "symptoms" to JsonArray(symptoms.map(::JsonString)),
        "mechanism" to JsonString(mechanism),
    )
}

internal object RuleScan {
    const val SYMPTOM_SCALE = 700

    private enum class T { NO, UNKNOWN, ASSUMED, YES }

    fun scan(rules: List<Rule>, facts: List<Fact>, text: String): List<RuleHit> {
        val failing = env(facts, Side.FAILING)
        val passing = env(facts, Side.PASSING)
        val low = Tokens.lower(text)
        val hits = mutableListOf<RuleHit>()
        for (rule in rules) {
            val used = mutableSetOf<String>()
            val state = walk(rule.detect, passing, failing, used)
            val needles = needles(rule).filter { low.contains(Tokens.lower(it)) }.sorted()
            val usedFailing = used.filter { it.startsWith("b:") }
            val grade = when {
                state == T.YES && used.isNotEmpty() -> Grade.MATCH

                state == T.ASSUMED && usedFailing.isNotEmpty() && needles.isNotEmpty() ->
                    Grade.FAILING_SIDE

                state == T.UNKNOWN && usedFailing.isNotEmpty() && needles.isNotEmpty() ->
                    Grade.SYMPTOM

                else -> continue
            }
            val backing = used.map { fact(facts, it) }
            var conf = backing.minOfOrNull { it.permille } ?: continue
            if (grade == Grade.SYMPTOM) conf = conf * SYMPTOM_SCALE / Confidence.MAX
            val verified = rule.symptoms.all { it.basis == Basis.VERIFIED }
            val tier = when {
                grade != Grade.MATCH -> null
                verified -> Tier.KNOWN
                else -> Tier.PLAUSIBLE
            }
            hits += RuleHit(
                rule.id,
                rule.title,
                grade,
                tier,
                rule.severity,
                rule.status,
                Confidence(conf),
                used.map { it.substring(2) }.distinct().sorted(),
                needles,
                rule.mechanism,
            )
        }
        return hits.sortedWith(
            compareBy(
                { it.grade.ordinal },
                { -(it.tier?.ordinal ?: -1) },
                { -it.confidence.permille },
                { -it.severity.ordinal },
                { it.ruleId },
            ),
        )
    }

    private fun env(facts: List<Fact>, side: Side): Env {
        val mine = facts.filter { it.side == side }
        return Env(
            attrs = mine.filter { !it.key.startsWith("run:") }.associate { it.key to it.value },
            run = mine.filter { it.key.startsWith("run:") }
                .associate { it.key.substring(4) to it.value },
        )
    }

    private fun fact(facts: List<Fact>, ref: String): Confidence {
        val side = if (ref.startsWith("a:")) Side.PASSING else Side.FAILING
        val key = ref.substring(2)
        return facts.first { it.side == side && it.key == key }.confidence
    }

    private fun walk(node: Node, a: Env, b: Env, used: MutableSet<String>): T = when (node) {
        is Node.AllOf -> node.nodes.map { walk(it, a, b, used) }.minOf { it }

        is Node.AnyOf -> node.nodes.map { walk(it, a, b, used) }.maxOf { it }

        is Node.Not -> when (walk(node.node, a, b, mutableSetOf())) {
            T.YES -> T.NO
            T.NO -> T.YES
            else -> T.UNKNOWN
        }

        is Node.On -> {
            val side = if (node.which == Which.A) a else b
            val mark = (if (node.which == Which.A) "a:" else "b:") + node.path
            val v = side.lookup(node.path)
            if (v == null) {
                if (node.which == Which.A) T.ASSUMED else T.UNKNOWN
            } else {
                when (node.pred.test(v)) {
                    true -> T.YES.also { used += mark }
                    false -> T.NO
                    null -> T.UNKNOWN
                }
            }
        }

        is Node.Cross -> {
            val y = node.pred.test(b.lookup(node.path))
            val x = node.pred.test(a.lookup(node.path))
            when {
                y == null -> T.UNKNOWN

                x == null -> T.ASSUMED.also { used += "b:${node.path}" }

                x != y -> T.YES.also {
                    used += "b:${node.path}"
                    used += "a:${node.path}"
                }

                else -> T.NO
            }
        }

        is Node.Changed -> {
            val y = b.lookup(node.path)
            val x = a.lookup(node.path)
            when {
                y == null -> T.UNKNOWN

                x == null -> T.ASSUMED.also { used += "b:${node.path}" }

                x != y -> T.YES.also {
                    used += "b:${node.path}"
                    used += "a:${node.path}"
                }

                else -> T.NO
            }
        }
    }

    /** Distinctive strings of a rule's symptoms: quoted text, ALL_CAPS codes, `...Error` names. */
    fun needles(rule: Rule): List<String> {
        val out = linkedSetOf<String>()
        for (s in rule.symptoms) {
            val t = s.text
            for (q in listOf('"', '\'')) {
                var i = t.indexOf(q)
                while (i >= 0) {
                    val e = t.indexOf(q, i + 1)
                    if (e < 0) break
                    val inner = t.substring(i + 1, e)
                    if (inner.length >= MIN_NEEDLE && !inner.contains(' ')) out += inner
                    i = t.indexOf(q, e + 1)
                }
            }
            for (w in t.split(' ', ',', ';', '(', ')', ':').map { it.trim('.', '`') }) {
                val code = w.length >= MIN_NEEDLE && w.contains('_') &&
                    w.all { it.isUpperCase() || it == '_' || Tokens.isDigit(it) }
                val named = w.length > MIN_NEEDLE &&
                    (w.endsWith("Error") || w.endsWith("Exception"))
                val dotted = w.count { it == '.' } >= 2 && w.all { Tokens.isAlnum(it) || it == '.' }
                if (code || named || dotted) out += w
            }
        }
        return out.toList()
    }

    private const val MIN_NEEDLE = 6
}

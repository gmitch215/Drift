package dev.gmitch215.drift.tools.corpus

import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.triage.Calibrate
import dev.gmitch215.drift.triage.Fact
import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.Via
import java.util.Locale

/** Counts for one slice of the corpus; every number in the report is a ratio of these. */
class Score {
    var scored = 0L
    var correct = 0L
    var squares = 0L
    var confidence = 0L
    var truth = 0L
    var found = 0L
    val binScored = LongArray(BINS)
    val binCorrect = LongArray(BINS)
    val binConfidence = LongArray(BINS)

    fun fact(permille: Int, ok: Boolean) {
        val miss = if (ok) 1000 - permille else permille
        scored++
        if (ok) correct++
        squares += miss.toLong() * miss
        confidence += permille
        val bin = minOf(permille / 100, BINS - 1)
        binScored[bin]++
        if (ok) binCorrect[bin]++
        binConfidence[bin] += permille.toLong()
    }

    fun truth(isFound: Boolean) {
        truth++
        if (isFound) found++
    }

    val precision get() = ratio(correct, scored)
    val recall get() = ratio(found, truth)
    val brier get() = if (scored == 0L) Double.NaN else squares.toDouble() / scored / 1_000_000
    val meanConfidence get() = if (scored == 0L) Double.NaN else confidence / 1000.0 / scored

    companion object {
        const val BINS = 10
        fun ratio(a: Long, b: Long) = if (b == 0L) Double.NaN else a.toDouble() / b
    }
}

/** How much the extractor read from issues of one kind, with or without ground truth. */
class Coverage {
    var bodies = 0L
    var withFact = 0L
    var withFailingTool = 0L
    var facts = 0L
    var forms = 0L
    var scorable = 0L
}

class Slice {
    val all = Score()
    val byStratum = sortedMapOf<String, Score>()
    val byVia = sortedMapOf<String, Score>()
    val byKey = sortedMapOf<String, Score>()
    val byPolicy = sortedMapOf<String, Score>()
    val coverage = sortedMapOf<String, Coverage>()
}

class EvalResult(
    val dev: Slice,
    val heldOut: Slice,
    val claims: Map<String, Int>,
    val bodies: Long,
    val excludedTruth: Long = 0,
)

/**
 * Scores the free-text extractor on the corpus. A form body (`### Label` sections, from a repo
 * whose issue template is a form) states its environment under labelled headings; the facts read
 * from those sections are the truth. The sections that gave a fact are cut out of the body and
 * the extractor runs again on what is left, so the tool-output, key-value and pattern paths are
 * judged against what the reporter typed into the form fields. Facts on keys the form did not
 * label cannot be judged and are not scored. Heading-to-key mapping and value normalization are
 * shared between the truth and the extractor, so the section path itself is not measured here.
 */
class EvalTriage(
    private val manifest: CorpusManifest,
    private val seed: String = SEED,
    private val onClaim: (CorpusIssue, String, Int, String) -> Unit = { _, _, _, _ -> },
    private val onMiss: (CorpusIssue, Fact, Set<String>) -> Unit = { _, _, _ -> },
) {
    private val policies = manifest.repos.associateBy { it.repo }
    private val dev = Slice()
    private val heldOut = Slice()
    private val claims = sortedMapOf<String, Int>()
    private var bodies = 0L
    private var excluded = 0L

    fun isDev(repo: String, number: Int): Boolean = Calibrate.isDev(seed, repo, number.toLong())

    fun add(issue: CorpusIssue) {
        val repo = policies[issue.repo] ?: return
        bodies++
        val slice = if (isDev(issue.repo, issue.number)) dev else heldOut
        val text = issue.title + "\n\n" + issue.body
        val full = Triage.run(text, rules = Rules.all)
        for (c in full.claims) {
            claims.merge(c.id, 1, Int::plus)
            onClaim(issue, c.id, c.confidence.permille, c.excerpt)
        }
        val cov = slice.coverage.getOrPut(repo.stratum + "/" + repo.formPolicy) { Coverage() }
        cov.bodies++
        cov.facts += full.facts.size
        if (full.facts.isNotEmpty()) cov.withFact++
        if (full.facts.any { it.key.startsWith("tool.") && it.side == Side.FAILING }) {
            cov.withFailingTool++
        }
        val form = repo.hasForms && issue.body.trimStart().startsWith("### ")
        if (!form) return
        cov.forms++
        val sections = Triage.run(issue.body, rules = emptyList()).facts
            .filter { it.via == Via.SECTION && it.side == Side.FAILING }
        val truth = sections.filter { it.key !in UNJUDGED }
        excluded += sections.size - truth.size
        if (truth.isEmpty()) return
        cov.scorable++
        val rest = Triage.run(withoutSections(issue.body, truth), rules = emptyList())
        score(issue, slice, repo, truth, rest.facts)
    }

    private fun score(
        issue: CorpusIssue,
        slice: Slice,
        repo: CorpusRepo,
        truth: List<Fact>,
        got: List<Fact>,
    ) {
        val values = truth.groupBy { it.key }.mapValues { e -> e.value.map { it.value }.toSet() }
        for (f in got) {
            if (f.side != Side.FAILING) continue
            val wanted = values[f.key] ?: continue
            val ok = wanted.any { agrees(f.value, it) }
            if (!ok) onMiss(issue, f, wanted)
            val p = f.confidence.permille
            slice.all.fact(p, ok)
            slice.byStratum.getOrPut(repo.stratum) { Score() }.fact(p, ok)
            slice.byPolicy.getOrPut(repo.formPolicy) { Score() }.fact(p, ok)
            slice.byVia.getOrPut(f.via.id) { Score() }.fact(p, ok)
            slice.byKey.getOrPut(f.key) { Score() }.fact(p, ok)
        }
        val seen = got.filter { it.side == Side.FAILING }.map { it.key to it.value }.toSet()
        for (t in truth) {
            val hit = seen.any { it.first == t.key && agrees(it.second, t.value) }
            slice.all.truth(hit)
            slice.byStratum.getOrPut(repo.stratum) { Score() }.truth(hit)
            slice.byPolicy.getOrPut(repo.formPolicy) { Score() }.truth(hit)
            slice.byKey.getOrPut(t.key) { Score() }.truth(hit)
        }
    }

    fun result() = EvalResult(dev, heldOut, claims, bodies, excluded)

    companion object {
        const val SEED = "drift-triage-split-v1"

        /** Keys whose section value is no truth: a `docker version` heading gives `Client:`. */
        private val UNJUDGED = setOf("runtime.container.image")

        fun withoutSections(body: String, truth: List<Fact>) =
            Calibrate.withoutSections(body, truth)

        fun agrees(a: String, b: String) = Calibrate.agrees(a, b)

        fun pct(d: Double) = if (d.isNaN()) "n/a" else String.format(Locale.ROOT, "%.3f", d)

        fun brier(d: Double) = if (d.isNaN()) "n/a" else String.format(Locale.ROOT, "%.4f", d)

        private fun row(name: String, s: Score) =
            "  ${name.padEnd(28)} n=${s.scored.toString().padStart(6)} " +
                "precision ${pct(s.precision)} recall ${pct(s.recall)} (${s.found}/${s.truth}) " +
                "Brier ${brier(s.brier)} mean conf ${pct(s.meanConfidence)}"

        fun render(r: EvalResult): String = buildString {
            append("bodies ${r.bodies}; claims ${r.claims}\n")
            append(
                "section facts left out as truth (runtime.container.image): ${r.excludedTruth}\n",
            )
            for ((name, slice) in listOf("dev" to r.dev, "held-out" to r.heldOut)) {
                append("\n== $name ==\n")
                append(row("all", slice.all)).append('\n')
                append("by stratum\n")
                slice.byStratum.forEach { (k, v) -> append(row(k, v)).append('\n') }
                append("by form policy\n")
                slice.byPolicy.forEach { (k, v) -> append(row(k, v)).append('\n') }
                append("by path (via)\n")
                slice.byVia.forEach { (k, v) -> append(row(k, v)).append('\n') }
                append("by field\n")
                slice.byKey.forEach { (k, v) -> append(row(k, v)).append('\n') }
                append("reliability bins (accuracy against mean confidence)\n")
                for (b in 0 until Score.BINS) {
                    val n = slice.all.binScored[b]
                    if (n == 0L) continue
                    val acc = pct(Score.ratio(slice.all.binCorrect[b], n))
                    val mean = pct(slice.all.binConfidence[b] / 1000.0 / n)
                    append("  bin 0.$b: $n facts, accuracy $acc, mean confidence $mean\n")
                }
                append("coverage by stratum/form policy\n")
                for ((k, c) in slice.coverage) {
                    val withFact = pct(Score.ratio(c.withFact, c.bodies))
                    val tool = pct(Score.ratio(c.withFailingTool, c.bodies))
                    val perBody = pct(Score.ratio(c.facts, c.bodies))
                    append(
                        "  ${k.padEnd(24)} bodies ${c.bodies} with fact $withFact " +
                            "failing tool $tool facts/body $perBody " +
                            "form bodies ${c.forms} scorable ${c.scorable}\n",
                    )
                }
            }
        }

        /** Proposed [Via] base for a path: the base scaled by accuracy over mean confidence. */
        fun proposed(via: Via, s: Score): Int? {
            if (s.scored < MIN_FACTS) return null
            val raw = (via.base.toLong() * s.correct * 1000 / s.confidence).toInt() / ROUND * ROUND
            return raw.coerceIn(MIN_BASE, MAX_BASE)
        }

        private const val MIN_FACTS = 100L
        private const val ROUND = 10
        private const val MIN_BASE = 100
        private const val MAX_BASE = 990
    }
}

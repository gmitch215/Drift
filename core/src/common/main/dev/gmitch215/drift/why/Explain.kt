package dev.gmitch215.drift.why

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Candidate
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Ranking
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import dev.gmitch215.drift.triage.NotEnvironment

/** How far the data goes; the wording of the verdict follows it. */
enum class Certainty(val id: String) {
    CAUSE("cause"),
    LIKELY("likely"),
    ONE_OF("one-of"),
    TIE("tie"),
    NONE("none"),
}

class Finding(val path: String, val described: Described)

/** What one comparison found, in the three parts the default view prints. */
class Answer(
    val sides: Sides,
    val certainty: Certainty,
    val verdict: String,
    val evidence: List<String>,
    val findings: List<Finding>,
    val next: List<String>,
    val ranking: Ranking,
    val plan: Plan,
    val works: Capsule,
    val fails: Capsule,
    val failure: List<Symptom>?,
    val notEnvironment: NotEnvironment? = null,
)

/** What the caller knows around two captures; each field only adds to the answer. */
class Inputs(
    val works: Capsule,
    val fails: Capsule,
    val sides: Sides,
    val failure: List<Symptom>? = null,
    val pin: (String) -> String? = { null },
    val skip: (String) -> Boolean = { false },
    val extraEvidence: String? = null,
    val failureHint: String? = null,
    val caseDir: String? = null,
    val closing: List<String> = emptyList(),
    val looked: String = Explain.LOOKED_AT,
    val lead: String? = null,
    val errorText: Boolean = false,
)

object Explain {
    private const val SHOWN = 3

    fun run(input: Inputs): Answer {
        val works = Noise.strip(input.works, input.skip)
        val fails = Noise.strip(input.fails, input.skip)
        val failure = input.failure?.takeIf { it.isNotEmpty() }
        val ranking = Ranker.rank(works, fails, Rules.all, failure)
        val plan = Planner.from(
            ranking,
            Frame.environments(works.label, fails.label),
            Options(TRIAL_MINUTES, 1, 1),
        )
        val changes = CapsuleDiff.diff(works, fails).changes.associateBy { it.path }
        val pointers = FailurePhrases.pointers(failure)
        val ordered = ranking.candidates.filter {
            it.path in changes && !pinOfChangedTool(it.path, changes)
        }
        val hinted = ordered.filter { c -> pointers.any { it.fits(changes.getValue(c.path)) } }
        val tokens = NameMatch.tokens(failure)
        val named = ordered.mapNotNull { c ->
            val change = changes.getValue(c.path)
            NameMatch.match(change, labelOf(change, input), tokens)?.let { c.path to it }
        }.toMap()
        val namedPool = distinct(ordered.filter { it.path in named }) { c ->
            labelOf(changes.getValue(c.path), input)
        }
        val pool = distinct(namedPool + hinted.ifEmpty { ordered }) { c ->
            labelOf(changes.getValue(c.path), input)
        }
        val findings = pool.map { c ->
            Finding(c.path, Families.describe(changes.getValue(c.path), input.sides, input.pin))
        }
        val pointed = named.isNotEmpty() || (input.errorText && hinted.isNotEmpty())
        val certainty = when (namedPool.size) {
            0 -> certainty(pool)
            1 -> Certainty.LIKELY
            else -> tied(namedPool)
        }.let { if (pointed && it == Certainty.CAUSE) Certainty.LIKELY else it }
        val fitting = changes.values.any { change ->
            pointers.any { it.fits(change) } ||
                NameMatch.match(change, labelOf(change, input), tokens) != null
        }
        val reading = if (pointed || fitting || certainty == Certainty.CAUSE) {
            null
        } else {
            val lines = failure.orEmpty().filter { it.kind == SymptomKind.SIGNATURE }
            NotEnvironment.find(lines.map { it.value })
        }
        val sides = input.sides
        val compared = (works.attributes.map { it.path } + fails.attributes.map { it.path })
            .toSet().size
        val verdictFindings = if (namedPool.size >= 2) findings.take(namedPool.size) else findings
        val pointedWording = hinted.isNotEmpty() || named.isNotEmpty()
        val verdict = (
            reading?.let {
                NotEnvironmentWording.verdict(it, NotEnvironmentWording.CAPTURE_LEAD, "error")
            } ?: verdict(certainty, verdictFindings, sides, pointedWording)
            ).let { text -> input.lead?.let { "$it $text" } ?: text }
        val evidence = reading?.let {
            listOf(NotEnvironmentWording.evidence(it, "error")) + listOfNotNull(input.extraEvidence)
        } ?: when (certainty) {
            Certainty.NONE -> listOf(
                "Compared $compared facts. I looked at ${input.looked}; none differ.",
                "I did not look at $NOT_LOOKED_AT.",
            )

            else -> listOfNotNull(
                if (pointed && named.isEmpty()) {
                    pointLine(failure, changes.getValue(findings.first().path))
                } else {
                    null
                },
            ) + findings.take(SHOWN).map { f ->
                val note = named[f.path]?.let { " (your error text names it: $it)" }.orEmpty()
                line(f.described, sides) + note
            } + listOfNotNull(otherLine(pointed, ordered, pool, changes, input))
        }.let { lines ->
            input.extraEvidence?.let { lines.take(SHOWN - 1) + it } ?: lines
        }
        val next = reading?.let { listOf(NotEnvironmentWording.next(it)) + input.closing }
            ?: next(input, certainty, findings, plan, failure != null)
        return Answer(
            sides,
            certainty,
            verdict,
            evidence,
            findings,
            next,
            ranking,
            plan,
            works,
            fails,
            failure,
            reading,
        )
    }

    /**
     * The differences the error text does not point at and the answer does not list: one line
     * that says they exist, so a reader does not take the listed ones for the only differences.
     */
    private fun otherLine(
        pointed: Boolean,
        ordered: List<Candidate>,
        pool: List<Candidate>,
        changes: Map<String, AttributeDiff>,
        input: Inputs,
    ): String? {
        if (!pointed) return null
        fun label(c: Candidate) = labelOf(changes.getValue(c.path), input)
        val shown = pool.map(::label).toSet()
        val hidden = ordered.map(::label).filter { it !in shown }.distinct()
        if (hidden.isEmpty()) return null
        val count = if (hidden.size == 1) {
            "1 other difference exists"
        } else {
            "${hidden.size} other differences exist"
        }
        val them = if (hidden.size == 1) "it" else "them"
        val names = shown.toList()
        val only = if (names.size == 1) "is the only one" else "are the only ones"
        val list = names.joinToString(" and ")
        return "$count, but the error text does not point at $them: $list $only it names."
    }

    /** A version file that pins a tool whose version differs: that tool's finding names it. */
    private fun pinOfChangedTool(path: String, changes: Map<String, AttributeDiff>) =
        path.startsWith("project.pins.") &&
            "tool.${path.removePrefix("project.pins.")}.version" in changes

    /** One candidate per label: the three `os.release` facts are one difference to read. */
    private fun distinct(pool: List<Candidate>, label: (Candidate) -> String): List<Candidate> =
        pool.groupBy(label).values
            .map { group -> group.firstOrNull { it.path.endsWith("PRETTY_NAME") } ?: group.first() }
            .sortedBy { pool.indexOf(it) }

    private fun labelOf(change: AttributeDiff, input: Inputs) =
        Families.describe(change, input.sides, input.pin).label

    private fun tied(named: List<Candidate>) =
        if (named[0].bundle != null && named[0].bundle == named[1].bundle) {
            Certainty.TIE
        } else {
            Certainty.ONE_OF
        }

    private fun certainty(pool: List<Candidate>): Certainty {
        val top = pool.firstOrNull() ?: return Certainty.NONE
        val second = pool.getOrNull(1)
        val strong = top.tier >= Tier.PLAUSIBLE && top.bundle == null
        return when {
            second == null && strong -> Certainty.CAUSE
            second == null -> Certainty.LIKELY
            strong && second.tier < Tier.PLAUSIBLE -> Certainty.CAUSE
            top.bundle != null && top.bundle == second.bundle -> Certainty.TIE
            else -> Certainty.ONE_OF
        }
    }

    private fun verdict(
        certainty: Certainty,
        findings: List<Finding>,
        sides: Sides,
        pointed: Boolean,
    ): String {
        fun sentence(text: String) = if (sides.assumed) {
            "Assuming ${sides.direction}: $text"
        } else {
            text.replaceFirstChar { it.uppercase() }
        }
        val because = findings.firstOrNull()?.described?.because
        return when (certainty) {
            Certainty.NONE ->
                "Nothing I can read differs between ${sides.works} and ${sides.fails}, so the " +
                    "cause is not in what Drift compares."

            Certainty.CAUSE -> sentence("it fails ${sides.fails} because $because.")

            Certainty.LIKELY -> {
                val why = if (pointed) {
                    "the error text points at it"
                } else {
                    "it is the only difference I found"
                }
                sentence("it most likely fails ${sides.fails} because $because; $why.")
            }

            Certainty.ONE_OF, Certainty.TIE -> {
                val names = findings.take(SHOWN).map { it.described.label }
                val more = findings.size - names.size
                val list = names.joinToString(", ") + if (more > 0) " and $more more" else ""
                val head = if (certainty == Certainty.TIE) {
                    "${findings.size} differences change together, so the data cannot " +
                        "separate them"
                } else {
                    "${findings.size} differences fit and the data cannot say which one " +
                        "causes it"
                }
                sentence("$head ($list).")
            }
        }
    }

    private fun pointLine(failure: List<Symptom>?, change: AttributeDiff): String? =
        FailurePhrases.pointedAt(failure, change)?.let { "your error text points at this: $it" }

    private fun line(d: Described, sides: Sides) =
        "${d.label}: ${d.works} ${sides.works}, ${d.fails} ${sides.fails}"

    private fun next(
        input: Inputs,
        certainty: Certainty,
        findings: List<Finding>,
        plan: Plan,
        failureKnown: Boolean,
    ): List<String> {
        val sides = input.sides
        val out = mutableListOf<String>()
        when (certainty) {
            Certainty.NONE -> {
                out += "That points at your code or data, or at something Drift does not read " +
                    "yet (files, network, timing)."
                out += "If it fails only sometimes, repeat it: drift run --repeat 10 -- COMMAND"
            }

            Certainty.CAUSE -> {
                out += findings.first().described.fix
                out += "Then run it again ${sides.fails} to check."
            }

            Certainty.LIKELY -> {
                out += findings.first().described.fix
                out += "To be sure, change only that and run it again ${sides.fails}."
            }

            Certainty.ONE_OF, Certainty.TIE -> {
                out += experiment(findings, plan, sides)
                input.caseDir?.let {
                    out += "The full experiment plan is saved: drift next $it"
                }
            }
        }
        val open = certainty == Certainty.ONE_OF || certainty == Certainty.TIE
        if (!failureKnown && open) {
            input.failureHint?.let { out += it }
        }
        return out + input.closing
    }

    private fun experiment(findings: List<Finding>, plan: Plan, sides: Sides): String {
        val flips = plan.next?.experiment?.flips.orEmpty()
        val members = flips.flatMap { id ->
            plan.hypotheses.firstOrNull { it.id == id }?.members.orEmpty()
        }
        val labels = findings.filter { it.path in members }.map { it.described.label }
            .ifEmpty { listOf(findings.first().described.label) }
        return "To tell them apart, change only ${labels.joinToString(" and ")} ${sides.fails} " +
            "so it matches ${sides.works}, and run it again. If it passes, that was the cause."
    }

    const val LOOKED_AT = "tool versions, environment variables, resource limits, memory and " +
        "CPU limits, kernel settings and the clock source, time zone and locale with the " +
        "installed locales, the file system type of /tmp, the shell behind /bin/sh, host names, " +
        "network interface names, IPv6, the DNS resolver file (counts only), CPU affinity, " +
        "and the lockfiles of the project"
    const val NOT_LOOKED_AT = "your source code and data files, network traffic and DNS " +
        "lookups, the speed of your disk, or what your code does at run time"
    private const val TRIAL_MINUTES = 5L
}

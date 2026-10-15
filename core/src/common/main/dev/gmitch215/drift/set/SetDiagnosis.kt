package dev.gmitch215.drift.set

import dev.gmitch215.drift.history.Evidence
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.spectrum.Ochiai
import dev.gmitch215.drift.spectrum.Run

/** One capsule of a set with the outcome of the run it describes. */
data class SetCapsule(val name: String, val failing: Boolean, val capsule: Capsule)

/**
 * One attribute value that failing capsules hold: [failing] and [passing] count the capsules
 * holding it on each side, and it [separates] when every failing capsule holds it and no passing
 * capsule does.
 */
data class Split(
    val path: String,
    val value: String,
    val failing: Int,
    val passing: Int,
    val score: Long,
    val separates: Boolean,
) {
    fun toJson(): JsonObject = obj(
        "path" to JsonString(path),
        "value" to JsonString(value),
        "failing" to JsonInt(failing.toLong()),
        "passing" to JsonInt(passing.toLong()),
        "score" to JsonInt(score),
        "separates" to JsonBool(separates),
    )
}

/** A capsule's name and outcome as the result lists it. */
data class Member(val name: String, val failing: Boolean) {
    fun toJson(): JsonObject = obj(
        "name" to JsonString(name),
        "outcome" to JsonString(if (failing) "fail" else "pass"),
    )
}

/**
 * The attribute values ranked by how well they split a set of passing capsules from failing
 * ones. [splits] is empty unless the set has both sides.
 */
data class SetResult(val members: List<Member>, val splits: List<Split>) {
    val failing: Int get() = members.count { it.failing }
    val passing: Int get() = members.size - failing
    val separating: List<Split> get() = splits.filter { it.separates }

    fun toJson(): JsonObject = obj(
        "capsules" to JsonArray(members.map { it.toJson() }),
        "failing" to JsonInt(failing.toLong()),
        "passing" to JsonInt(passing.toLong()),
        "splits" to JsonArray(splits.map { it.toJson() }),
    )

    /** One header line and one line per split, at most [limit] of them. */
    fun text(limit: Int = splits.size): String {
        val head = "set: $failing failing, $passing passing, ${separating.size} separating"
        val rows = splits.take(limit).mapIndexed { i, s ->
            val tail = if (s.separates) "  separates" else ""
            "${i + 1}. ${s.path} = ${s.value}  failing ${s.failing}/$failing  " +
                "passing ${s.passing}/$passing  score ${micro(s.score)}$tail"
        }
        return (listOf(head) + rows).joinToString("\n", postfix = "\n")
    }

    private fun micro(v: Long) =
        "${v / FixedPoint.ONE}.${(v % FixedPoint.ONE).toString().padStart(MICRO_DIGITS, '0')}"

    private companion object {
        const val MICRO_DIGITS = 6
    }
}

/**
 * Ranks the static attributes of N labelled capsules with the Ochiai spectrum, the same score
 * the history ranking uses, with failing capsules as the failing runs. A value present in every
 * capsule is left out because it splits nothing, and so is a value no failing capsule holds.
 * Only the failing side is scored, so a key whose failing capsules disagree ranks as a partial
 * split.
 */
object SetDiagnosis {
    fun of(capsules: List<SetCapsule>): SetResult {
        val members = capsules.map { Member(it.name, it.failing) }
            .sortedWith(compareBy({ it.name }, { it.failing }))
        val failing = members.count { it.failing }
        if (failing == 0 || failing == members.size) return SetResult(members, emptyList())
        val runs = capsules.map { Run(it.failing, Evidence.elements(it.capsule)) }
        val splits = Ochiai.rank(runs)
            .filter { it.ef > 0 && it.ef + it.ep < capsules.size }
            .map {
                val separates = it.nf == 0 && it.ep == 0
                Split(it.element.path, it.element.value, it.ef, it.ep, it.score, separates)
            }
        return SetResult(members, splits)
    }
}

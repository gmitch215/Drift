package dev.gmitch215.drift.triage

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.redact.Redactor

/**
 * Turns candidates into one fact per key and side. Spans that give the same value agree and raise
 * the confidence (see [Confidence.any]); a value that is the other's prefix on a dot (`17` and
 * `17.0.9`) agrees and the longer one is kept. A different value for the same key and side is a
 * conflict: the stronger value is kept at 0.6 of its confidence and the other is listed.
 */
internal object Combine {
    const val CONFLICT = 600

    private class Cluster(var value: String, val members: MutableList<Cand>)

    fun facts(cands: List<Cand>): List<Fact> {
        val unique = cands
            .groupBy { listOf(it.key, it.value, it.side.id, "${it.span.line}:${it.span.col}") }
            .values
            .map { group -> group.maxWith(compareBy({ it.conf }, { -it.via.ordinal })) }
        val out = mutableListOf<Fact>()
        val groups = unique.groupBy { it.key to it.side }
        for ((id, members) in groups) out += one(id.first, id.second, members)
        return out.sortedWith(compareBy({ it.key }, { it.side.ordinal }))
    }

    private fun one(key: String, side: Side, members: List<Cand>): Fact {
        val order = members.sortedWith(
            compareBy({ -it.conf.permille }, { it.span.line }, { it.span.col }, { it.value }),
        )
        val clusters = mutableListOf<Cluster>()
        for (c in order) {
            val home = clusters.firstOrNull { compatible(it.value, c.value) }
            if (home == null) {
                clusters += Cluster(c.value, mutableListOf(c))
            } else {
                home.members += c
                if (c.value.length > home.value.length) home.value = c.value
            }
        }
        val scored = clusters.map { it to Confidence.any(it.members.map { m -> m.conf }) }
            .sortedWith(
                compareBy(
                    { -it.second.permille },
                    { it.first.members.minOf { m -> m.span.line } },
                    { it.first.value },
                ),
            )
        val (best, conf) = scored.first()
        val alternatives = scored.drop(1).map { it.first.value }.distinct().sorted()
        val source = best.members.firstOrNull { it.value == best.value } ?: best.members.first()
        val final = if (alternatives.isEmpty()) conf else conf.scale(CONFLICT)
        val value = Redactor.redact(Attribute(key, best.value, "triage")).value
        val agreed = best.members.size - 1
        return Fact(key, value, side, final, source.via, source.span, agreed, alternatives)
    }

    private fun compatible(a: String, b: String) =
        a == b || a.startsWith("$b.") || b.startsWith("$a.")
}

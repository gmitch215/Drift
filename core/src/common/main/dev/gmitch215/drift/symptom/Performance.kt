package dev.gmitch215.drift.symptom

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run

/**
 * Smallest, middle and largest of [count] whole-number measurements. The median of an even count
 * is the floor of the mean of the two middle values, so every target prints the same digits.
 */
data class Spread(val count: Int, val min: Long, val median: Long, val max: Long) {
    fun toJson(): JsonObject = obj(
        "count" to JsonInt(count.toLong()),
        "min" to JsonInt(min),
        "median" to JsonInt(median),
        "max" to JsonInt(max),
    )

    companion object {
        fun of(values: List<Long>): Spread? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            val median = if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) / 2
            }
            return Spread(sorted.size, sorted.first(), median, sorted.last())
        }

        /** [large] over [small] in tenths, rounded half up: 25 reads as 2.5 times. */
        fun tenths(large: Long, small: Long): Long {
            val base = maxOf(small, 1L)
            return (large * 20 + base) / (2 * base)
        }

        fun format(tenths: Long): String = "${tenths / 10}.${tenths % 10}"
    }
}

/**
 * What two runs that carry samples say about time and memory. A measurement differs only when the
 * ranges do not overlap: the minimum of the larger side is above the maximum of the smaller side.
 * Then the larger second run gets a [SymptomKind.SLOWER] (wall time) or [SymptomKind.MEMORY] (peak
 * memory) symptom whose value is the ratio of the medians in tenths, as text such as
 * `2.5 times slower`. Overlapping ranges give a line that says so and no symptom, and a first run
 * that is larger gives a line and no symptom. A side with fewer than [MIN_SAMPLES] samples has no
 * spread and is not compared. [lines] also carry each limit that applies; none names a cause.
 */
data class Performance(
    val lines: List<String>,
    val symptoms: List<Symptom>,
    val ranges: List<Range> = emptyList(),
) {
    /** The two spreads of one measurement, for a chart; [name] and [unit] label it. */
    data class Range(val name: String, val unit: String, val first: Spread, val second: Spread) {
        fun toJson(): JsonObject = obj(
            "name" to JsonString(name),
            "unit" to JsonString(unit),
            "first" to first.toJson(),
            "second" to second.toJson(),
        )
    }

    /** `ranges` is written only when there are some, so earlier output keeps its bytes. */
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("lines", JsonArray(lines.map(::JsonString)))
            put("symptoms", JsonArray(symptoms.map { it.toJson() }))
            if (ranges.isNotEmpty()) put("ranges", JsonArray(ranges.map { it.toJson() }))
        },
    )

    private class Side(val name: String, val unit: String, val verb: String, val what: String)

    companion object {
        const val MIN_SAMPLES = 2
        const val FEW_SAMPLES = 5

        /** A JVM run shorter than this may be mostly warm-up. */
        const val SHORT_RUN_MS = 5_000L

        private val JVM_TOOLS = setOf(
            "java", "javac", "kotlin", "kotlinc", "gradle", "gradlew", "mvn", "mvnw", "sbt",
        )

        private val HARDWARE = listOf("hw.cpu.model", "cpu.count", "os.arch")

        private val WALL = Side("Wall time", "ms", "is", "slower")
        private val MEMORY = Side("Peak memory", "KiB", "used", "more peak memory")

        /** Null when either run has no samples, so a plain pair of runs prints nothing new. */
        fun compare(
            first: Run?,
            second: Run?,
            firstCapsule: Capsule,
            secondCapsule: Capsule,
        ): Performance? {
            val a = first?.samples.orEmpty()
            val b = second?.samples.orEmpty()
            if (a.isEmpty() || b.isEmpty()) return null
            if (a.size < MIN_SAMPLES || b.size < MIN_SAMPLES) {
                val line = "Timing: each run needs at least $MIN_SAMPLES samples to have a " +
                    "spread (first ${a.size}, second ${b.size}); use drift run --repeat."
                return Performance(listOf(line), emptyList())
            }
            val wallA = Spread.of(a.map { it.wallMs })!!
            val wallB = Spread.of(b.map { it.wallMs })!!
            val lines = ArrayList<String>()
            val symptoms = ArrayList<Symptom>()
            val ranges = arrayListOf(Range(WALL.name, WALL.unit, wallA, wallB))
            fun add(result: Pair<String, Symptom?>) {
                lines += result.first
                result.second?.let { symptoms += it }
            }
            add(side(WALL, wallA, wallB, SymptomKind.SLOWER))
            val memA = Spread.of(a.mapNotNull { it.peakKb })
            val memB = Spread.of(b.mapNotNull { it.peakKb })
            if (memA != null && memB != null && minOf(memA.count, memB.count) >= MIN_SAMPLES) {
                add(side(MEMORY, memA, memB, SymptomKind.MEMORY))
                ranges += Range(MEMORY.name, MEMORY.unit, memA, memB)
            }
            lines += limits(first, second, wallA, wallB, firstCapsule, secondCapsule)
            return Performance(lines, symptoms, ranges)
        }

        private fun ratio(large: Long, small: Long, s: Side) =
            "${Spread.format(Spread.tenths(large, small))} times ${s.what}"

        private fun side(s: Side, a: Spread, b: Spread, kind: SymptomKind): Pair<String, Symptom?> {
            fun cost(x: Spread) = "median ${x.median} ${s.unit} over ${x.count} samples"
            return when {
                b.min > a.max -> {
                    val text = ratio(b.median, a.median, s)
                    val line = "${s.name}: the second run ${s.verb} $text than the first " +
                        "(${cost(b)} against ${cost(a)}; the smallest second sample, " +
                        "${b.min} ${s.unit}, is above the largest first sample, " +
                        "${a.max} ${s.unit})."
                    line to Symptom(kind, text, 1)
                }

                a.min > b.max -> {
                    val text = ratio(a.median, b.median, s)
                    "${s.name}: the first run ${s.verb} $text than the second, so the second " +
                        "run gets no symptom." to null
                }

                else ->
                    "${s.name}: no difference larger than the run to run spread " +
                        "(first ${a.min} to ${a.max} ${s.unit}, second ${b.min} to ${b.max} " +
                        "${s.unit})." to null
            }
        }

        /** The limits that apply to a comparison of two runs; `drift compare` prints them too. */
        fun limits(
            first: Run?,
            second: Run?,
            wallA: Spread,
            wallB: Spread,
            ca: Capsule,
            cb: Capsule,
        ): List<String> {
            fun value(c: Capsule, path: String) =
                c.attributes.firstOrNull { it.path == path }?.value
            val out = ArrayList<String>()
            val hardware = HARDWARE.mapNotNull { path ->
                val x = value(ca, path)
                val y = value(cb, path)
                if (x != null && y != null && x != y) "$path $x against $y" else null
            }
            if (hardware.isNotEmpty()) {
                out += "Limit: the captures show different hardware " +
                    "(${hardware.joinToString("; ")}), so the times come from different machines."
            }
            if (minOf(wallA.count, wallB.count) < FEW_SAMPLES) {
                out += "Limit: fewer than $FEW_SAMPLES samples on a side (first ${wallA.count}, " +
                    "second ${wallB.count}), so the spread is a weak guide."
            }
            val jvm = listOf(first, second).any { startsJvm(it) }
            if (jvm && minOf(wallA.median, wallB.median) < SHORT_RUN_MS) {
                out += "Limit: the command starts a JVM and a run this short can be mostly " +
                    "warm-up (a median under $SHORT_RUN_MS ms)."
            }
            if (wasmOnDifferentHosts(ca, cb)) {
                out += "Limit: a Wasm program's time depends on whether it ran in Node or a " +
                    "browser, and the captures do not both say which."
            }
            return out
        }

        private fun startsJvm(run: Run?): Boolean {
            val name = run?.steps?.firstOrNull()?.name?.trim() ?: return false
            val program = name.substringBefore(' ').substringAfterLast('/').substringAfterLast('\\')
            return program.removeSuffix(".exe").removeSuffix(".bat") in JVM_TOOLS
        }

        private fun wasmOnDifferentHosts(a: Capsule, b: Capsule): Boolean {
            fun wasm(c: Capsule) = c.attributes.any {
                (it.path == "runtime.platform" || it.path == "kotlin.target") &&
                    it.value.startsWith("wasm")
            }

            fun browser(c: Capsule) = c.attributes.any { it.path.startsWith("browser.") }

            fun platform(c: Capsule) =
                c.attributes.firstOrNull { it.path == "runtime.platform" }?.value

            return (wasm(a) || wasm(b)) && (browser(a) != browser(b) || platform(a) != platform(b))
        }
    }
}

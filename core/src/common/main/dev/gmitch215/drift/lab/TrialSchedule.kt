package dev.gmitch215.drift.lab

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

/**
 * The order of the trials of one experiment. Each trial index runs both arms, so the schedule
 * says which arm runs first at every index. Blocks of two indices put one arm first once and
 * second once (control, treatment, treatment, control), which cancels a linear drift over time;
 * a hash bit of the [seed] picks which arm opens a block. The seed comes from the two capsules and
 * the experiment id, so `verify` derives the same order.
 */
class TrialSchedule(val seed: String, val perArm: Int) {
    /** For each trial index, whether the treatment side runs first. */
    val treatmentFirst: List<Boolean> = order(seed, perArm)

    /** The order as pairs, `CT` when the control runs first and `TC` otherwise. */
    val text: String get() = treatmentFirst.joinToString("") { if (it) "TC" else "CT" }

    fun toJson() = obj(
        "seed" to JsonString(seed),
        "order" to JsonString(text),
    )

    companion object {
        fun seed(passingHash: String, failingHash: String, id: String): String =
            Sha256.hex("drift-schedule-1:$passingHash:$failingHash:$id")

        fun order(seed: String, perArm: Int): List<Boolean> {
            val out = ArrayList<Boolean>(perArm)
            var block = 0
            while (out.size < perArm) {
                val first = Sha256.hex("$seed:$block")[0].digitToInt(16) % 2 == 1
                out += first
                if (out.size < perArm) out += !first
                block++
            }
            return out
        }
    }
}

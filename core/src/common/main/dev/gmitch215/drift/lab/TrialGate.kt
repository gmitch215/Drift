package dev.gmitch215.drift.lab

/**
 * Arms a fault on a fixed share of trials. Trial `i` sets [variable] to 1 when [hash] of `i` and
 * [salt] is under [permille], and to 0 otherwise; the arms of one experiment see the same
 * pattern, so an intermittent failure keeps its rate. The hash is the one `bench` computes.
 */
data class TrialGate(val permille: Int, val salt: Long, val variable: String) {
    init {
        require(permille in 0..1000) { "a gate is a permille between 0 and 1000" }
        require(VARIABLE.matches(variable)) { "the gate variable is not an environment name" }
    }

    fun armed(trial: Int): Boolean = hash(trial, salt) % 1000 < permille

    fun env(trial: Int): String = "$variable=${if (armed(trial)) 1 else 0}"

    companion object {
        private const val MOD = 4294967296L
        private val VARIABLE = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        fun hash(trial: Int, salt: Long): Long {
            var h = (trial * 2654435761L + salt) % MOD
            h = (((h shr 16) xor h) * 73244475L) % MOD
            h = (((h shr 16) xor h) * 73244475L) % MOD
            return (h shr 16) xor h
        }

        /** `permille=N,salt=S,env=NAME`; the salt defaults to 0 and the name to `DRIFT_GATE`. */
        fun parse(text: String): TrialGate {
            val items = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate {
                it.substringBefore('=') to it.substringAfter('=', "")
            }
            val unknown = items.keys - setOf("permille", "salt", "env")
            require(unknown.isEmpty()) { "--gate takes permille, salt and env, not $unknown" }
            val permille = items["permille"]?.toIntOrNull()
                ?: throw IllegalArgumentException("--gate needs permille=N")
            val salt = items["salt"]?.let {
                it.toLongOrNull() ?: throw IllegalArgumentException("--gate salt is not a number")
            } ?: 0L
            return TrialGate(permille, salt, items["env"] ?: "DRIFT_GATE")
        }
    }
}

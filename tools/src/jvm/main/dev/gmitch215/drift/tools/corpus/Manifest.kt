package dev.gmitch215.drift.tools.corpus

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.schema.FailsafeSchema

/** One tracker in `triage/corpus.yml`. [formPolicy] is the issue template policy of the repo. */
data class CorpusRepo(
    val repo: String,
    val host: String,
    val stratum: String,
    val formPolicy: String,
    val why: String,
) {
    val hasForms get() = formPolicy.startsWith("forms")
}

/** Where the frozen corpus is hosted and the hashes a download must match. */
data class CorpusAsset(val url: String, val sha256: String, val bytes: Long)

/** [cap] is the most issues kept per repo and [cutoff] the last creation day, `yyyy-mm-dd`. */
data class CorpusManifest(
    val cutoff: String,
    val cap: Int,
    val salt: String,
    val repos: List<CorpusRepo>,
    val asset: CorpusAsset?,
) {
    companion object {
        private val hosts = setOf("github", "gitlab")
        private val strata = setOf("large", "small")
        private val policies = setOf("forms-strict", "forms-blank", "md-strict", "md-blank", "none")
        private val day = Regex("\\d{4}-\\d\\d-\\d\\d")
        private val yaml = Load(LoadSettings.builder().setSchema(FailsafeSchema()).build())

        fun parse(text: String): CorpusManifest {
            val root = try {
                yaml.loadFromString(text) as? Map<*, *> ?: fail("not a mapping")
            } catch (e: YamlEngineException) {
                fail("invalid YAML: ${e.message?.lineSequence()?.first()}")
            }
            require(root["schema"] == "1") { "unsupported manifest schema: ${root["schema"]}" }
            val cutoff = field(root, "cutoff")
            require(day.matches(cutoff)) { "cutoff is not a date: $cutoff" }
            val cap = field(root, "cap").toIntOrNull()?.takeIf { it > 0 } ?: fail("bad cap")
            val entries = root["repos"] as? List<*> ?: fail("no repos")
            require(entries.isNotEmpty()) { "no repos" }
            val repos = entries.map { entry ->
                val m = entry as? Map<*, *> ?: fail("repo entry is not a mapping")
                val repo = CorpusRepo(
                    field(m, "repo"),
                    field(m, "host"),
                    field(m, "stratum"),
                    field(m, "form_policy"),
                    field(m, "why"),
                )
                require("/" in repo.repo) { "repo needs owner/name: ${repo.repo}" }
                require(repo.host in hosts) { "${repo.repo}: unknown host ${repo.host}" }
                require(repo.stratum in strata) { "${repo.repo}: unknown stratum ${repo.stratum}" }
                require(repo.formPolicy in policies) {
                    "${repo.repo}: unknown form_policy ${repo.formPolicy}"
                }
                repo
            }
            val names = repos.map { it.repo.lowercase() }
            require(names.size == names.toSet().size) { "duplicate repo in the manifest" }
            val asset = (root["asset"] as? Map<*, *>)?.let {
                val sha = field(it, "sha256")
                require(Regex("[0-9a-f]{64}").matches(sha)) { "asset sha256 is not 64 hex digits" }
                CorpusAsset(field(it, "url"), sha, field(it, "bytes").toLong())
            }
            return CorpusManifest(cutoff, cap, field(root, "salt"), repos, asset)
        }

        private fun field(map: Map<*, *>, key: String): String =
            (map[key] as? String)?.takeIf { it.isNotBlank() } ?: fail("missing $key")

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}

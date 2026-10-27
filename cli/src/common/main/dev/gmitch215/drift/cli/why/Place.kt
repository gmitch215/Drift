package dev.gmitch215.drift.cli.why

/** Where `drift why --there` looks. */
internal sealed interface Place {
    data class Docker(val name: String, val dir: String?) : Place

    data class Ssh(val alias: String) : Place

    data class File(val path: String) : Place

    companion object {
        private const val DOCKER = "docker:"
        private const val SSH_URL = "ssh://"
        private val FILE_ENDINGS = listOf(".json", ".driftcase")

        /** [exists] says whether [text] names a file or a directory that is there. */
        fun parse(text: String, exists: (String) -> Boolean): Place = when {
            text.startsWith(DOCKER) -> {
                val rest = text.removePrefix(DOCKER)
                Docker(rest.substringBefore(':'), rest.substringAfter(':', "").ifEmpty { null })
            }

            text.startsWith(SSH_URL) -> Ssh(text.removePrefix(SSH_URL).trimEnd('/'))

            exists(text) -> File(text)

            '/' in text || '#' in text || FILE_ENDINGS.any(text::endsWith) -> File(text)

            else -> Ssh(text)
        }
    }
}

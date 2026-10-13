package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Base64

abstract class EmbedRules : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rules: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun embed() {
        val root = rules.get().asFile
        fun path(f: File) = f.relativeTo(root).invariantSeparatorsPath
        val all = root.walkTopDown().filter { it.isFile }.toList()
        val stray = all.filter { it.name.endsWith(".json") }.map(::path).sorted()
        if (stray.isNotEmpty()) {
            throw GradleException("rules are written as .yml, remove or convert $stray")
        }
        val yml = all.filter { it.name.endsWith(SUFFIX) }
        val duplicates = yml.groupBy { it.name }.values.filter { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            val paths = duplicates.map { group -> group.map(::path).sorted() }
            throw GradleException("rule ids must be unique across folders: $paths")
        }
        val files = yml.associate {
            val json = RuleYaml.toJson(it.readText(), it.name)
            it.name.removeSuffix(SUFFIX) to json.toByteArray()
        }
        val out = outputDir.get().asFile.resolve("dev/gmitch215/drift/know/Rules.kt")
        out.parentFile.mkdirs()
        out.writeText(render(files))
    }

    companion object {
        private const val SUFFIX = ".yml"
        private const val CHUNK = 16000

        /** Kotlin source for the `Rules` registry; [files] maps a rule id to its JSON bytes. */
        fun render(files: Map<String, ByteArray>): String {
            val entries = files.toSortedMap().entries.joinToString("\n") { (id, bytes) ->
                val b64 = Base64.getEncoder().encodeToString(bytes)
                val chunks = b64.chunked(CHUNK).joinToString(",\n") { "                \"$it\"" }
                "        \"$id\" to listOf(\n$chunks,\n        ),"
            }
            return """
                |package dev.gmitch215.drift.know
                |
                |import kotlin.io.encoding.Base64
                |
                |object Rules {
                |    private val files = mapOf<String, List<String>>(
                |$entries
                |    )
                |
                |    val ids: Set<String> get() = files.keys
                |
                |    fun text(id: String): String =
                |        Base64.decode(files.getValue(id).joinToString(""))
                |            .decodeToString(throwOnInvalidSequence = true)
                |
                |    val all: List<Rule> by lazy { ids.map { RuleCompiler.compile(text(it), it) } }
                |}
                |
            """.trimMargin()
        }
    }
}

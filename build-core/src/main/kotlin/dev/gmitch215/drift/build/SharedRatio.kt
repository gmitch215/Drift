package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

object SharedRatio {
    data class Lines(val shared: Int, val platform: Int)

    fun codeLines(text: String): Int = text.lineSequence().count {
        val line = it.trim()
        line.isNotEmpty() && !line.startsWith("//") && !line.startsWith("/*") &&
            !line.startsWith("*")
    }

    fun percent(shared: Int, platform: Int): Double {
        val total = shared + platform
        return if (total == 0) 100.0 else 100.0 * shared / total
    }

    fun measure(root: File, modules: List<String>): Map<String, Lines> {
        val result = linkedMapOf<String, Lines>()
        for (module in modules) {
            val src = File(root, "$module/src")
            if (!src.isDirectory) continue
            var shared = 0
            var platform = 0
            for (set in src.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
                val main = File(set, "main")
                val lines = main.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kt") }
                    .sumOf { codeLines(it.readText()) }
                if (set.name == "common") shared += lines else platform += lines
            }
            result[module] = Lines(shared, platform)
        }
        return result
    }

    fun table(result: Map<String, Lines>): String {
        val out =
            StringBuilder("%-8s %8s %9s %7s\n".format("module", "common", "platform", "shared"))
        for ((module, l) in result) {
            out.append(
                "%-8s %8d %9d %6.1f%%\n".format(
                    module,
                    l.shared,
                    l.platform,
                    percent(l.shared, l.platform),
                ),
            )
        }
        val shared = result.values.sumOf { it.shared }
        val platform = result.values.sumOf { it.platform }
        out.append(
            "%-8s %8d %9d %6.1f%%\n".format("total", shared, platform, percent(shared, platform)),
        )
        return out.toString()
    }

    const val MARKER = "data-shared-ratio"

    fun pageLine(result: Map<String, Lines>): String {
        val shared = result.values.sumOf { it.shared }
        val platform = result.values.sumOf { it.platform }
        val modules = result.entries.joinToString(", ") {
            "%s %.1f%%".format(it.key, percent(it.value.shared, it.value.platform))
        }
        val total = "%.1f%%".format(percent(shared, platform))
        return "<p $MARKER>Shared code $total: $modules</p>"
    }

    fun withPageLine(text: String, result: Map<String, Lines>): String? {
        val lines = text.split("\n").toMutableList()
        val at = lines.indexOfFirst { MARKER in it }
        if (at < 0) return null
        lines[at] = lines[at].takeWhile { it == ' ' } + pageLine(result)
        return lines.joinToString("\n")
    }

    fun violations(
        result: Map<String, Lines>,
        floor: Double,
        hostBudget: Int,
        pure: List<String> = listOf("core"),
    ): List<String> {
        val problems = mutableListOf<String>()
        val shared = result.values.sumOf { it.shared }
        val platform = result.values.sumOf { it.platform }
        val total = percent(shared, platform)
        if (total <
            floor
        ) {
            problems += "total %.1f%% is below the floor of %.1f%%".format(total, floor)
        }
        val host = result["host"]?.platform ?: 0
        if (hostBudget > 0 &&
            host > hostBudget
        ) {
            problems += "host has $host platform lines, budget $hostBudget"
        }
        for (module in pure) {
            val lines = result[module]?.platform ?: 0
            if (lines > 0) problems += "$module has $lines platform lines, it must be common only"
        }
        return problems
    }
}

abstract class SharedRatioTask : DefaultTask() {
    @get:Internal
    abstract val root: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val floor: Property<Double>

    @get:Input
    abstract val hostBudget: Property<Int>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val page: RegularFileProperty

    @get:Input
    abstract val rewritePage: Property<Boolean>

    @TaskAction
    fun check() {
        val result = SharedRatio.measure(root.get().asFile, modules.get())
        print(SharedRatio.table(result))
        val problems =
            SharedRatio.violations(result, floor.get(), hostBudget.get()).toMutableList()
        val file = page.get().asFile
        val text = file.readText()
        val current = SharedRatio.withPageLine(text, result)
        if (current == null) {
            problems += "${file.name} has no line marked ${SharedRatio.MARKER}"
        } else if (current != text) {
            if (rewritePage.get()) {
                file.writeText(current)
            } else {
                problems += "${file.name} shared-ratio line is stale; " +
                    "run ./gradlew sharedRatioReadme"
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

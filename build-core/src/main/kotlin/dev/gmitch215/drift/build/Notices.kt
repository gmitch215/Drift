package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

object Notices {
    private val entry = Regex("^- (\\S+)")

    fun listed(text: String): Set<String> =
        text.lines().mapNotNull { entry.find(it)?.groupValues?.get(1) }.toSet()

    fun missing(coordinates: Collection<String>, text: String): List<String> {
        val listed = listed(text)
        return coordinates.distinct().sorted().filter { it !in listed }
    }
}

abstract class VerifyNoticesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notices: RegularFileProperty

    @get:Internal
    abstract val configurations: ListProperty<Configuration>

    init {
        notCompatibleWithConfigurationCache("reads resolved graphs from Configuration objects")
    }

    @TaskAction
    fun check() {
        val coordinates = configurations.get().flatMap { config ->
            val result = config.incoming.resolutionResult
            result.allComponents
                .filter { it != result.root && it.id !is ProjectComponentIdentifier }
                .mapNotNull { it.moduleVersion }
                .map { "${it.group}:${it.name}" }
        }
        val missing = Notices.missing(coordinates, notices.get().asFile.readText())
        if (missing.isNotEmpty()) {
            throw GradleException(
                "${notices.get().asFile.name} has no entry for ${missing.size} " +
                    "shipped component(s):\n" +
                    missing.joinToString("\n") { "- $it" },
            )
        }
    }
}

package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class AtlasSite : DefaultTask() {
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val dataset: RegularFileProperty

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val classification: RegularFileProperty

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val columns: RegularFileProperty

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val repros: DirectoryProperty

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val transcripts: DirectoryProperty

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val studio: DirectoryProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun assemble() {
		try {
			AtlasSiteBuilder.assemble(
				out = outputDir.get().asFile,
				studio = studio.get().asFile,
				datasetFile = dataset.get().asFile,
				classificationFile = classification.get().asFile,
				columnsFile = columns.get().asFile,
				repros = repros.get().asFile,
				transcripts = transcripts.get().asFile,
			)
		} catch (e: SiteDataException) {
			throw GradleException(e.message ?: "unreadable atlas data", e)
		} catch (e: RuleYamlException) {
			throw GradleException(e.message ?: "unreadable atlas data", e)
		}
	}
}

object AtlasSiteBuilder {
	private const val BODY = "<body></body>"

	private const val NOSCRIPT = "<body><noscript><p>Drift Atlas needs JavaScript for the " +
		"interactive matrix. The results are also static pages: " +
		"<a href=\"probe/\">all probes</a>.</p></noscript></body>"

	fun assemble(
		out: File,
		studio: File,
		datasetFile: File,
		classificationFile: File,
		columnsFile: File,
		repros: File,
		transcripts: File,
	) {
		val json = datasetFile.readText()
		val dataset = SiteData.dataset(json)
		val columns = SiteData.columns(columnsFile.readText(), columnsFile.name)
		val classification =
			RuleYaml.toJson(classificationFile.readText(), classificationFile.name, requireId = false)
		val measured = measured(classification)
		out.deleteRecursively()
		studio.copyRecursively(out)
		val index = out.resolve("index.html")
		val html = index.readText()
		if (BODY !in html) throw SiteDataException("the Studio index.html has no empty body to extend")
		index.writeText(html.replace(BODY, NOSCRIPT))
		write(out, "data/dataset.json", json)
		write(out, "data/classification.json", classification)
		val files = copyTranscripts(out, transcripts)
		write(out, "404.html", SiteRender.notFound())
		write(out, "probe/index.html", SiteRender.index(dataset, columns, measured, files))
		for (p in dataset.probes) {
			val repro = p.repro?.let { path ->
				val file = repros.resolve(path.substringAfterLast('/'))
				if (!file.isFile) throw SiteDataException("${p.id} names $path, which is missing")
				file.readText()
			}
			write(out, "probe/${p.id}.html", SiteRender.probe(p, columns, measured, files, repro))
		}
	}

	private fun measured(classificationJson: String): String {
		val m = Regex("\"date\": \"([^\"]+)\"").find(classificationJson)
		return m?.groupValues?.get(1) ?: throw SiteDataException("classification has no measured.date")
	}

	/** Copies every transcript and returns its path by (target, version) read from its header. */
	private fun copyTranscripts(out: File, root: File): Map<Pair<String, String>, String> {
		val found = linkedMapOf<Pair<String, String>, String>()
		val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }
			.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
		for (f in files) {
			val text = f.readText()
			val version = header(text, "kotlin.version", f)
			val target = header(text, "kotlin.target", f)
			val path = f.relativeTo(root).invariantSeparatorsPath
			found[target to version] = path
			val copy = out.resolve("data/transcripts/$path")
			copy.parentFile.mkdirs()
			f.copyTo(copy)
		}
		return found
	}

	private fun header(text: String, key: String, file: File): String =
		Regex("^# ${Regex.escape(key)} = (.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
			?: throw SiteDataException("${file.name} has no $key header")

	private fun write(out: File, path: String, text: String) {
		val file = out.resolve(path)
		file.parentFile.mkdirs()
		file.writeBytes(text.toByteArray(Charsets.UTF_8))
	}
}

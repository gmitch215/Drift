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
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object Coverage {
	data class FileLines(val path: String, val covered: Int, val missed: Int)

	data class Report(val files: List<FileLines>, val covered: Int, val missed: Int) {
		val percent: Double get() = if (covered + missed ==
			0
		) {
				0.0
			} else {
				100.0 * covered / (covered + missed)
			}
	}

	private fun lineCounter(parent: Element): Pair<Int, Int>? {
		val nodes = parent.childNodes
		for (i in 0 until nodes.length) {
			val node = nodes.item(i)
			if (node is Element && node.tagName == "counter" && node.getAttribute("type") == "LINE") {
				return node.getAttribute("covered").toInt() to node.getAttribute("missed").toInt()
			}
		}
		return null
	}

	fun parse(xml: String): Report {
		val factory = DocumentBuilderFactory.newInstance()
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
		val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
		val files = mutableListOf<FileLines>()
		val packages = doc.documentElement.getElementsByTagName("package")
		for (p in 0 until packages.length) {
			val pkg = packages.item(p) as Element
			val children = pkg.childNodes
			for (c in 0 until children.length) {
				val node = children.item(c)
				if (node is Element && node.tagName == "sourcefile") {
					val (covered, missed) = (lineCounter(node) ?: (0 to 0))
					files += FileLines(pkg.getAttribute("name") + "/" + node.getAttribute("name"), covered, missed)
				}
			}
		}
		val total =
			lineCounter(doc.documentElement) ?: (files.sumOf { it.covered } to files.sumOf { it.missed })
		return Report(files, total.first, total.second)
	}

	data class Checked(val mapped: Report, val unmapped: List<String>, val problems: List<String>)

	fun check(report: Report, index: Map<String, List<String>>, floor: Double): Checked {
		if (report.files.isEmpty()) {
			return Checked(
				report,
				emptyList(),
				listOf("the report names no source files, so it covers nothing"),
			)
		}
		val problems = mutableListOf<String>()
		val unmapped = mutableListOf<String>()
		val mapped = mutableListOf<FileLines>()
		for (file in report.files) {
			val found = index[file.path].orEmpty()
			when {
				found.isEmpty() -> unmapped += file.path

				found.size > 1 ->
					problems +=
					"${file.path} maps to ${found.size} source files: ${found.joinToString()}"

				else -> mapped += file
			}
		}
		val covered = mapped.sumOf { it.covered }
		val missed = mapped.sumOf { it.missed }
		val result = Report(mapped, covered, missed)
		if (mapped.isEmpty()) {
			problems += "no file in the report maps onto a source file"
		} else if (result.percent < floor) {
			problems += "line coverage %.2f%% is below the floor of %.2f%%".format(result.percent, floor)
		}
		return Checked(result, unmapped, problems)
	}

	fun index(root: File, sourceRoots: List<String>): Map<String, List<String>> {
		val index = linkedMapOf<String, MutableList<String>>()
		for (rel in sourceRoots) {
			val dir = File(root, rel)
			dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach {
				index.getOrPut(it.relativeTo(dir).invariantSeparatorsPath) { mutableListOf() } +=
					it.relativeTo(root).invariantSeparatorsPath
			}
		}
		return index
	}

	fun byModule(report: Report, index: Map<String, List<String>>): Map<String, Pair<Int, Int>> {
		val totals = sortedMapOf<String, Pair<Int, Int>>()
		for (file in report.files) {
			val module = index[file.path]?.singleOrNull()?.substringBefore('/') ?: continue
			val old = totals[module] ?: (0 to 0)
			totals[module] = (old.first + file.covered) to (old.second + file.missed)
		}
		return totals
	}

	data class TestCount(val tests: Int, val failed: Int)

	fun testCounts(root: File): Map<String, TestCount> {
		val counts = sortedMapOf<String, TestCount>()
		val attr = { text: String, name: String ->
			Regex("""\b$name="(\d+)"""").find(text)?.groupValues?.get(1)?.toInt()
			?: 0
		}
		root.listFiles { f -> f.isDirectory }.orEmpty().forEach { module ->
			File(module, "build/test-results").listFiles { f -> f.isDirectory }.orEmpty().forEach { target ->
				target.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().forEach {
					val head = it.readText().take(2000)
					val key = "${module.name} ${target.name}"
					val old = counts[key] ?: TestCount(0, 0)
					counts[key] = TestCount(
						old.tests + attr(head, "tests"),
						old.failed + attr(head, "failures") + attr(head, "errors"),
					)
				}
			}
		}
		return counts
	}

	fun summary(
		byModule: Map<String, Pair<Int, Int>>,
		total: Report,
		tests: Map<String, TestCount>,
	): String {
		val out = StringBuilder("Line coverage (JVM, JaCoCo)\n")
		out.append("%-10s %8s %8s %7s\n".format("module", "covered", "missed", "lines"))
		for ((module, c) in byModule) {
			val sum = c.first + c.second
			val pct = if (sum == 0) 0.0 else 100.0 * c.first / sum
			out.append("%-10s %8d %8d %6.1f%%\n".format(module, c.first, c.second, pct))
		}
		out.append("%-10s %8d %8d %6.1f%%\n".format("total", total.covered, total.missed, total.percent))
		out.append(
			"\nNot measured: Kotlin/Native targets and wasmJs (no coverage tool for Kotlin 2.4.20),\n",
		)
		out.append(
			"and platform source sets other than jvm. Common code runs the same tests on every target.\n",
		)
		out.append("\nTests executed\n")
		for ((key, t) in tests) out.append("%-24s %4d tests %2d failed\n".format(key, t.tests, t.failed))
		return out.toString()
	}
}

abstract class VerifyCoverage : DefaultTask() {
	@get:Internal
	abstract val root: DirectoryProperty

	@get:InputFile
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val report: RegularFileProperty

	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val sources: ConfigurableFileCollection

	@get:Input
	abstract val sourceRoots: ListProperty<String>

	@get:Input
	abstract val floor: Property<Double>

	@TaskAction
	fun verify() {
		val parsed = Coverage.parse(report.get().asFile.readText())
		val index = Coverage.index(root.get().asFile, sourceRoots.get())
		val checked = Coverage.check(parsed, index, floor.get())
		println(
			"coverage: %d files, %.2f%% of lines".format(checked.mapped.files.size, checked.mapped.percent),
		)
		if (checked.unmapped.isNotEmpty()) {
			println(
				"ignored (inlined from libraries): ${checked.unmapped.joinToString()}",
			)
		}
		if (checked.problems.isNotEmpty()) throw GradleException(checked.problems.joinToString("\n"))
	}
}

abstract class CoverageSummary : DefaultTask() {
	@get:Internal
	abstract val root: DirectoryProperty

	@get:InputFile
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val report: RegularFileProperty

	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val testResults: ConfigurableFileCollection

	@get:Input
	abstract val sourceRoots: ListProperty<String>

	@get:OutputFile
	abstract val output: RegularFileProperty

	@TaskAction
	fun write() {
		val parsed = Coverage.parse(report.get().asFile.readText())
		val index = Coverage.index(root.get().asFile, sourceRoots.get())
		val text = Coverage.summary(
			Coverage.byModule(parsed, index),
			parsed,
			Coverage.testCounts(root.get().asFile),
		)
		val file = output.get().asFile
		file.parentFile.mkdirs()
		file.writeText(text)
		print(text)
	}
}

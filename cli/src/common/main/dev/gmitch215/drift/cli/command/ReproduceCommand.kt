package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.lab.Reproduce
import dev.gmitch215.drift.lab.ReproduceRender
import dev.gmitch215.drift.lab.ReproduceResult

class ReproduceCommand(private val host: Host, private val files: CaseFiles) :
	CliktCommand(name = "reproduce") {
	private val capsule by argument(help = "capsule file to mirror")
	private val command by option("--run", help = "test command to run in the container").required()
	private val out by option("--out", help = "directory for the three generated files").required()
	private val digests by option("--digests", help = "JSON file mapping an image tag to a digest")
	private val envNames by option(
		"--env",
		metavar = "NAME",
		help = "also mirror this env.* attribute (repeatable); default is LANG, LC_* and TZ",
	).multiple()
	private val envAll by option(
		"--env-all",
		help = "mirror every env.* attribute; values may hold identifiers and secrets",
	).flag()
	private val force by option(
		"--force",
		help = "replace the files of an earlier reproduction in --out (only those three files)",
	).flag()
	private val detail by option("--detail", help = "how much to print")
		.choice("summary", "detail", "full").default("summary")

	override fun help(context: Context) =
		"Write a Dockerfile, run.sh and manifest.json that mirror a capsule, and say what does not"

	override fun run() {
		val dir = out.trimEnd('/')
		val names = listOf("Dockerfile", "run.sh", "manifest.json")
		val present = names.filter { files.read("$dir/$it") != null }
		if (present.isNotEmpty()) {
			val list = present.joinToString(", ")
			if (!force) {
				throw CliktError(
					"output directory already holds a reproduction: $out ($list); pass --force " +
						"to replace those files, nothing else in the directory is touched",
				)
			}
			val foreign = present.filter { !wroteByDrift(it, files.read("$dir/$it")!!) }
			if (foreign.isNotEmpty()) {
				throw CliktError(
					"--force replaces only files drift reproduce wrote, and ${foreign.first()} " +
						"in $out is not one; nothing was replaced",
				)
			}
			echo("replacing $list in $out", err = true)
		}
		val pins = digests?.let { loadDigests(it) } ?: emptyMap()
		val source = loadCapsule(host, capsule)
		val result = Reproduce.synthesize(source, command, pins, envNames.toSet(), envAll)
		val reproduction = when (result) {
			is ReproduceResult.Built -> result.reproduction
			is ReproduceResult.Refused -> throw CliktError(result.problem.message())
		}
		if (envAll) {
			echo(
				"warning: --env-all mirrors every env.* value; values may contain identifiers " +
					"and secrets, and only secret-looking values and home paths are redacted",
				err = true,
			)
		}
		for ((name, text) in reproduction.files()) {
			if (!files.write("$dir/$name", text)) throw CliktError("cannot write file: $dir/$name")
		}
		echo("wrote ${names.size} files to $out", err = true)
		echo(ReproduceRender.render(reproduction, Detail.parse(detail)!!), trailingNewline = false)
	}

	private fun wroteByDrift(name: String, text: String): Boolean {
		if (name != "manifest.json") return "# drift reproduce: capsule" in text
		return try {
			val manifest = CanonicalJson.parse(text).obj()
			"capsule" in manifest.fields && "conventions" in manifest.fields
		} catch (e: JsonException) {
			false
		}
	}

	private fun loadDigests(path: String): Map<String, String> {
		val text = host.readText(path) ?: throw CliktError("cannot read digests file: $path")
		return try {
			CanonicalJson.parse(text).obj().fields.mapValues { it.value.string() }
		} catch (e: JsonException) {
			throw CliktError("invalid digests file $path: ${e.message}")
		}
	}
}

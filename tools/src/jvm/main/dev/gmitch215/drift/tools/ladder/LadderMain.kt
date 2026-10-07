package dev.gmitch215.drift.tools.ladder

import java.io.File
import kotlin.system.exitProcess

private const val USAGE =
	"usage: ladder gen|errors|assemble <probes-dir> ... (see tools/ladder/README.md)"

private fun probes(dir: String): List<LadderProbe> = LadderProbes.all(
	File(dir).listFiles { f -> f.name.endsWith("Probes.kt") }.orEmpty().sortedBy { it.name }
		.map { it.readText() },
)

private fun excluded(path: String): Map<String, String> =
	File(path).takeIf { it.exists() }?.readText()?.let(LadderRun::parseExcluded).orEmpty()

fun main(args: Array<String>) {
	when (args.firstOrNull()) {
		"gen" -> if (args.size == 4 || args.size == 5) {
			val all = probes(args[1])
			val skip = if (args.size == 5) {
				all.map { it.id }.filter { it != args[4] }.toSet()
			} else {
				excluded(args[3]).keys
			}
			val out = File(args[2]).apply { mkdirs() }
			for ((name, text) in LadderProbes.program(all, skip)) File(out, name).writeText(text)
			println("wrote ${all.size - skip.size} probes, excluded ${skip.size}")
		} else {
			usage()
		}

		"errors" -> if (args.size == 4) {
			val all = probes(args[1])
			val log = File(args[2]).readText()
			val skip = excluded(args[3]).toMutableMap()
			val fresh = LadderRun.errors(log).filterKeys { it < all.size && all[it].id !in skip }
			for ((index, message) in fresh) skip[all[index].id] = message
			File(args[3]).writeText(LadderRun.renderExcluded(skip))
			println("excluded ${fresh.size} more probes")
			if (fresh.isEmpty() || LadderRun.hasUnattributed(log)) exitProcess(3)
		} else {
			usage()
		}

		"ids" -> if (args.size == 3) {
			val skip = excluded(args[2]).keys
			probes(args[1]).map { it.id }.filter { it !in skip }.forEach(::println)
		} else {
			usage()
		}

		"assemble" -> if (args.size == 7 || args.size == 8) {
			val ids = probes(args[1]).map { it.id }
			val text = LadderRun.assemble(
				args[2],
				args[3],
				ids,
				excluded(args[5]),
				File(args[4]).takeIf { it.exists() }?.readText().orEmpty(),
				if (args.size == 8) excluded(args[7]) else emptyMap(),
			)
			File(args[6]).writeText(text)
			println("wrote ${ids.size} probes to ${args[6]}")
		} else {
			usage()
		}

		else -> usage()
	}
}

private fun usage(): Nothing {
	System.err.println(USAGE)
	exitProcess(2)
}

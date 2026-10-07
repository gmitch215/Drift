package dev.gmitch215.drift.bench

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val USAGE = """usage: bench <command> --root <bench dir> [options]
  generate                      write data/scenarios.json and data/split.json
  prepare --out <dir> [--only a,b]   write run directories for dev scenarios
  ingest --prepared <dir> [--drift <binary>]   validate outputs, write results and capsules
  probed --solved <dir>         copy the baseline capsules of solve cases to data/probed
  calibrate                     fit on the dev split, write data/calibration/*.json"""

fun main(args: Array<String>) {
	val opts = args.drop(1).chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
	val root = opts["--root"]?.let { Path.of(it) }
	if (args.isEmpty() || root == null) {
		System.err.println(USAGE)
		exitProcess(2)
	}
	val work = Work(root)
	try {
		when (args[0]) {
			"generate" -> {
				work.writeGenerated()
				println("wrote ${work.loadScenarios().size} scenarios")
			}

			"prepare" -> {
				val out = Path.of(opts["--out"] ?: error("--out is required"))
				val only = opts["--only"]?.split(',')?.filter { it.isNotEmpty() }?.toSet()
					?: emptySet()
				println("prepared ${work.prepare(out, only).size} scenarios in $out")
			}

			"ingest" -> {
				val prepared = Path.of(opts["--prepared"] ?: error("--prepared is required"))
				val sha = opts["--drift"]?.let { Path.of(it) }?.takeIf { it.exists() }
					?.let { Work.sha256(it) }
				val verdicts = work.ingest(prepared, sha)
				verdicts.forEach { (id, v) ->
					println("$id ${if (v.valid) "valid" else "DROPPED " + v.reasons}")
				}
				println(
					"${verdicts.values.count {
					it.valid
				}} valid, ${verdicts.values.count { !it.valid }} dropped",
				)
			}

			"probed" -> {
				val solved = Path.of(opts["--solved"] ?: error("--solved is required"))
				println("imported ${work.importProbed(solved).size} scenarios from $solved")
			}

			"calibrate" -> println("chosen ${work.writeCalibration().chosen.id}")

			else -> {
				System.err.println(USAGE)
				exitProcess(2)
			}
		}
	} catch (e: TemplateException) {
		System.err.println(e.message)
		exitProcess(1)
	} catch (e: SealedException) {
		System.err.println(e.message)
		exitProcess(1)
	}
}

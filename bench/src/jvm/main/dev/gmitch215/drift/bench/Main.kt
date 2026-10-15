package dev.gmitch215.drift.bench

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val USAGE = """usage: bench <command> --root <bench dir> [options]
  generate                      write data/scenarios.json and data/split.json
  prepare --out <dir> [--only a,b] [--template t,u] [--test]   write run directories for dev
                                scenarios, or for the test scenarios after the unseal
  ingest --prepared <dir> [--drift <binary>]   validate outputs, write results and capsules
  probed --solved <dir>         copy the baseline capsules of solve cases to data/probed
  calibrate                     fit on the dev split, write data/calibration/*.json
  freeze [--commit <sha>]       write data/freeze.json from the working tree
  triage                        write triage/triage.json and print the dev triage scores
  upgrade                       write upgrade/upgrade.json and print the dev upgrade-check scores
  unseal [--dry-run] [--commit <sha>] [--operator <name>]
                                check the freeze, then log the one unseal of the test set"""

private fun csv(value: String?): Set<String> =
    value.orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

fun main(args: Array<String>) {
    val opts = mutableMapOf<String, String>()
    var i = 1
    while (i < args.size) {
        val value = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
        opts[args[i]] = value ?: ""
        i += if (value == null) 1 else 2
    }
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
                val unsealed = if ("--test" in opts) work.openUnsealed() else null
                val only = csv(opts["--only"]) +
                    work.idsOfTemplates(csv(opts["--template"]), unsealed)
                println("prepared ${work.prepare(out, only, unsealed).size} scenarios in $out")
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

            "freeze" -> {
                work.writeFreeze(opts["--commit"] ?: headCommit(root))
                println("wrote data/freeze.json")
            }

            "triage" -> {
                val data = TriageData(root)
                data.write()
                print(TriageBench.scoreDev(data.items()).report("dev"))
            }

            "upgrade" -> print(work.writeUpgrade().report("dev"))

            "unseal" -> {
                val dryRun = "--dry-run" in opts
                val unsealed = work.unseal(
                    opts["--commit"] ?: headCommit(root),
                    opts["--operator"] ?: System.getProperty("user.name"),
                    Instant.now().toString(),
                    dryRun,
                )
                println(
                    if (dryRun) {
                        "dry run: the freeze matches, ${unsealed.test.size} test scenarios"
                    } else {
                        "unsealed ${unsealed.test.size} test scenarios"
                    },
                )
            }

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
    } catch (e: UnsealException) {
        System.err.println(e.message)
        exitProcess(1)
    }
}

private fun headCommit(root: Path): String {
    val p = ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD")
        .redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() != 0) throw UnsealException("git rev-parse failed (pass --commit): $out")
    return out
}

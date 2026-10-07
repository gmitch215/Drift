package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktError
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseRead
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.Render
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.lab.Ingest
import dev.gmitch215.drift.lab.VerdictKind
import dev.gmitch215.drift.model.Capsule

internal fun loadCase(files: CaseFiles, dir: String): CaseFile =
	when (val read = CaseStore.read(files, dir)) {
		is CaseRead.Loaded -> read.case
		is CaseRead.Failed -> throw CliktError("cannot read case $dir: ${read.problem.message()}")
	}

internal fun CaseFiles.refuseCaseDirectory(path: String) {
	val dir = path.trimEnd('/')
	if (read("$dir/case.json") != null) {
		throw CliktError(
			"$path is a case directory; expected a .driftcase archive; pack the case first " +
				"(drift pack $dir --out file.driftcase)",
		)
	}
}

internal fun CaseFile.requireIntact(dir: String) {
	val problems = check()
	if (problems.isNotEmpty()) {
		val text = problems.joinToString("; ") { it.message() }
		throw CliktError("case $dir failed its checks: $text")
	}
}

internal fun CaseFile.render(dir: String, detail: Detail, next: Boolean): String = try {
	val plan = json("experiments/plan.json")
	val done = Ingest.recorded(this)
	if (next) {
		val text = Render.next(plan, detail, done)
		val rule = Ingest.rule(plan)
		if (detail == Detail.SUMMARY || rule == null) {
			text
		} else {
			"$text\nRule sha256 (ingest names it as ruleSha256): ${rule.sha256()}\n"
		}
	} else {
		Render.render(json("hypotheses/ranking.json"), plan, detail, done)
	}
} catch (e: JsonException) {
	throw CliktError("case $dir does not hold a ranking and plan: ${e.message}")
} catch (e: RuntimeException) {
	throw CliktError("case $dir does not hold a ranking and plan")
}

internal fun loadCapsule(host: Host, path: String): Capsule {
	val text = host.readText(path) ?: throw CliktError("cannot read capsule file: $path")
	return try {
		Capsule.parse(text)
	} catch (e: JsonException) {
		throw CliktError("invalid capsule file $path: ${e.message}")
	}
}

internal fun verdictExitCode(kind: VerdictKind): Int = when (kind) {
	VerdictKind.CONFIRMED -> 0
	VerdictKind.BUNDLE -> 10
	VerdictKind.NARROWED -> 11
	VerdictKind.STUCK -> 12
}

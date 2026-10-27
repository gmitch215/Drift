package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktError
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseRead
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.Render
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.DirectoryRead
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.lab.Archive
import dev.gmitch215.drift.lab.ArchiveOpen
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
    val hash = path.lastIndexOf('#')
    val selector = path.substring(hash + 1).takeIf { hash > 0 && it in CAPSULE_SELECTORS }
    val file = if (selector == null) path else path.substring(0, hash)
    val text = host.readText(file) ?: throw CliktError("cannot read capsule file: $file")
    if (!text.startsWith("ustar", USTAR_MAGIC_OFFSET)) {
        if (selector != null) throw CliktError("$file is not a .driftcase archive; drop #$selector")
        return parseCapsule(file, text)
    }
    // canonical archives are UTF-8 throughout, so the text round trip is lossless
    val case = when (val open = Archive.open(text.encodeToByteArray())) {
        is ArchiveOpen.Opened -> open.case

        is ArchiveOpen.Failed -> {
            throw CliktError("invalid archive $file: ${open.problem.message()}")
        }
    }
    if (selector == null) {
        throw CliktError(
            "$file is a .driftcase archive; select a capsule with $file#passing or $file#failing " +
                "(drift verify, drift report and drift open read the whole archive)",
        )
    }
    val member = "environments/$selector.json"
    val json = case.files[member]
        ?: throw CliktError("$file has no $member; use drift verify, drift report or drift open")
    return parseCapsule("$file#$selector", json)
}

private const val USTAR_MAGIC_OFFSET = 257
private val CAPSULE_SELECTORS = setOf("passing", "failing")

private fun parseCapsule(name: String, text: String): Capsule = try {
    Capsule.parse(text)
} catch (e: JsonException) {
    throw CliktError("invalid capsule file $name: ${e.message}")
}

internal fun loadCapsuleSource(host: Host, path: String): Capsule =
    when (val dir = CapsuleDirectories.read(host::readText, path)) {
        is DirectoryRead.Loaded -> dir.directory.capsule
        is DirectoryRead.Failed -> throw CliktError(dir.reason)
        null -> loadCapsule(host, path)
    }

internal fun verdictExitCode(kind: VerdictKind): Int = when (kind) {
    VerdictKind.CONFIRMED -> 0
    VerdictKind.BUNDLE, VerdictKind.EFFECT -> 10
    VerdictKind.NARROWED -> 11
    VerdictKind.STUCK -> 12
    VerdictKind.NEEDS -> 13
}

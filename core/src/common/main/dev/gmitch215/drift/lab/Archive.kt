package dev.gmitch215.drift.lab

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.Tar
import dev.gmitch215.drift.case.TarProblem
import dev.gmitch215.drift.case.TarRead
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

sealed interface ArchivePack {
	data class Packed(val bytes: ByteArray, val case: CaseFile) : ArchivePack

	data class Failed(val message: String) : ArchivePack
}

sealed interface ArchiveOpen {
	data class Opened(val case: CaseFile) : ArchiveOpen

	data class Failed(val problem: TarProblem) : ArchiveOpen
}

/**
 * The `.driftcase` file: a deterministic tar of a solved case directory. The arm folders move
 * from `arms/` to `reproduce/`, `confirmed/minimal-set.json` records the minimal causal set when
 * the verdict is a CONFIRMED one, `report.html` is generated from the certificate, and the
 * manifest is rebuilt over what the archive holds.
 */
object Archive {
	const val EXTENSION = ".driftcase"
	const val REPORT = "report.html"
	const val CONFIRMED = "confirmed/minimal-set.json"

	fun pack(case: CaseFile): ArchivePack {
		val certificate = try {
			SolveCertificate.certificate(case)
		} catch (e: JsonException) {
			return ArchivePack.Failed("malformed case file ${SolveCertificate.CERTIFICATE}")
		} ?: return ArchivePack.Failed("not a solved case: no ${SolveCertificate.CERTIFICATE}")
		val problems = case.check().map { it.message() } + SolveCertificate.check(case)
		if (problems.isNotEmpty()) {
			return ArchivePack.Failed("case failed its checks: ${problems.joinToString("; ")}")
		}
		val files = LinkedHashMap<String, String>()
		for ((path, text) in case.files) {
			if (path == CaseFile.MANIFEST || path == REPORT || path == CONFIRMED) continue
			val target = if (path.startsWith("arms/")) {
				"reproduce/" + path.removePrefix("arms/")
			} else {
				path
			}
			files[target] = text
		}
		try {
			confirmed(certificate)?.let { files[CONFIRMED] = it }
			files[REPORT] = ReportHtml.of(certificate)
		} catch (e: JsonException) {
			return ArchivePack.Failed("malformed case file ${SolveCertificate.CERTIFICATE}")
		}
		for (path in files.keys) {
			Tar.problem(path)?.let { return ArchivePack.Failed(it.message()) }
		}
		files[CaseFile.MANIFEST] = CaseFile.manifest(files)
		return ArchivePack.Packed(Tar.write(files), CaseFile(files))
	}

	fun open(bytes: ByteArray): ArchiveOpen = when (val read = Tar.read(bytes)) {
		is TarRead.Entries -> ArchiveOpen.Opened(CaseFile(read.files))
		is TarRead.Failed -> ArchiveOpen.Failed(read.problem)
	}

	/** The record of the minimal causal set, or null when the verdict is not a confirmed one. */
	fun confirmed(certificate: JsonObject): String? {
		val verdict = certificate.require("verdict").obj()
		val kind = verdict.require("kind").string()
		val conditions = certificate["conditions"] as? JsonObject
		if (conditions == null || (kind != "confirmed" && kind != "confirmed-bundle")) return null
		val search = conditions.require("minimalSet").obj()
		return CanonicalJson.encode(
			obj(
				"schema" to JsonInt(1),
				"verdict" to verdict.require("kind"),
				"minimalSet" to verdict.require("minimalSet"),
				"members" to verdict.require("members"),
				"notIsolated" to verdict.require("notIsolated"),
				"mechanism" to verdict.require("mechanism"),
				"effect" to conditions.require("effect"),
				"agreement" to conditions.require("agreement"),
				"search" to search,
			),
		)
	}
}

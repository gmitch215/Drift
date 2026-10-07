package dev.gmitch215.drift.case

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

/** Writes and reads a [CaseFile] under a directory; a bad file is a typed result, not a throw. */
object CaseStore {
	fun write(files: CaseFiles, dir: String, case: CaseFile): CaseWrite {
		for ((path, text) in case.archiveEntries()) {
			if (!files.write(join(dir, path), text)) return CaseWrite.Failed(join(dir, path))
		}
		return CaseWrite.Written(case.files.size)
	}

	/** Reads what the manifest lists. Hashes and the chain are [CaseFile.check]'s job. */
	fun read(files: CaseFiles, dir: String): CaseRead {
		val manifest = parse(files, dir, CaseFile.MANIFEST)
		if (manifest !is Parsed.Ok) return manifest.failure()
		CaseFile.schemaOf(CaseFile.MANIFEST, manifest.json)?.let { return CaseRead.Failed(it) }
		val paths = try {
			manifest.json.require("files").array().map {
				it.obj().require("path").string().also { p ->
					if (p.startsWith("/") || ".." in p.split('/')) {
						throw JsonException("unsafe path $p")
					}
				}
			}
		} catch (e: JsonException) {
			return CaseRead.Failed(MalformedFile(CaseFile.MANIFEST, e.message ?: "unreadable"))
		}
		val loaded = linkedMapOf(CaseFile.MANIFEST to manifest.text)
		for (path in (paths + CaseFile.CASE).distinct()) {
			if (!path.endsWith(".json")) {
				val text = files.read(join(dir, path))
					?: return CaseRead.Failed(MissingFile(path))
				loaded[path] = text
				continue
			}
			val file = parse(files, dir, path)
			if (file !is Parsed.Ok) return file.failure()
			if (path == CaseFile.CASE) {
				CaseFile.schemaOf(path, file.json)?.let { return CaseRead.Failed(it) }
			}
			loaded[path] = file.text
		}
		return CaseRead.Loaded(CaseFile(loaded))
	}

	private sealed interface Parsed {
		data class Ok(val text: String, val json: JsonObject) : Parsed

		data class Bad(val problem: CaseProblem) : Parsed
	}

	private fun Parsed.failure(): CaseRead = CaseRead.Failed((this as Parsed.Bad).problem)

	private fun parse(files: CaseFiles, dir: String, path: String): Parsed {
		val text = files.read(join(dir, path)) ?: return Parsed.Bad(MissingFile(path))
		return try {
			Parsed.Ok(text, CanonicalJson.parse(text).obj())
		} catch (e: JsonException) {
			Parsed.Bad(MalformedFile(path, e.message ?: "unreadable"))
		}
	}

	private fun join(dir: String, path: String) = dir.trimEnd('/') + "/" + path
}

package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

internal sealed interface PathEdit

internal data class ProfileEdit(
	val path: String,
	val block: String,
	val created: Boolean,
	val leadingNewline: Boolean,
) : PathEdit

internal data class RegistryEdit(
	val machine: Boolean,
	val entry: String,
	val separator: String,
	val created: Boolean,
	val type: Int,
) : PathEdit {
	val key get() = if (machine) MACHINE_KEY else USER_KEY

	companion object {
		const val USER_KEY = "HKCU\\Environment"
		const val MACHINE_KEY =
			"HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"
	}
}

/** What `drift install` did, so `drift uninstall` reverses exactly that. */
internal data class Receipt(
	val version: String,
	val scope: Scope,
	val directory: String,
	val binary: String,
	val createdDirectories: List<String>,
	val leftovers: List<String>,
	val edits: List<PathEdit>,
) {
	fun canonical(): String = CanonicalJson.encode(
		JsonObject(
			mapOf(
				"schema" to JsonInt(1),
				"version" to JsonString(version),
				"scope" to JsonString(scope.name.lowercase()),
				"directory" to JsonString(directory),
				"binary" to JsonString(binary),
				"createdDirectories" to strings(createdDirectories),
				"leftovers" to strings(leftovers),
				"pathEdits" to JsonArray(edits.map(::edit)),
			),
		),
	)

	companion object {
		const val FILE = "install-receipt.json"

		fun parse(text: String): Receipt {
			val root = CanonicalJson.parse(text).obj()
			if (root.require("schema").long() != 1L) error("unsupported receipt schema")
			return Receipt(
				root.require("version").string(),
				Scope.valueOf(root.require("scope").string().uppercase()),
				root.require("directory").string(),
				root.require("binary").string(),
				root.require("createdDirectories").array().map { it.string() },
				root.require("leftovers").array().map { it.string() },
				root.require("pathEdits").array().map { read(it.obj()) },
			)
		}

		private fun strings(list: List<String>) = JsonArray(list.map(::JsonString))

		private fun edit(e: PathEdit): JsonValue = when (e) {
			is ProfileEdit -> obj(
				"kind" to JsonString("profile"),
				"path" to JsonString(e.path),
				"block" to JsonString(e.block),
				"created" to JsonBool(e.created),
				"leadingNewline" to JsonBool(e.leadingNewline),
			)

			is RegistryEdit -> obj(
				"kind" to JsonString("registry"),
				"key" to JsonString(e.key),
				"name" to JsonString("Path"),
				"entry" to JsonString(e.entry),
				"separator" to JsonString(e.separator),
				"created" to JsonBool(e.created),
				"type" to JsonInt(e.type.toLong()),
			)
		}

		private fun read(o: JsonObject): PathEdit = when (val kind = o.require("kind").string()) {
			"profile" -> ProfileEdit(
				o.require("path").string(),
				o.require("block").string(),
				flag(o, "created"),
				flag(o, "leadingNewline"),
			)

			"registry" -> RegistryEdit(
				o.require("key").string().startsWith("HKLM"),
				o.require("entry").string(),
				o.require("separator").string(),
				flag(o, "created"),
				o.require("type").long().toInt(),
			)

			else -> error("unknown path edit $kind")
		}

		private fun flag(o: JsonObject, name: String) =
			(o.require(name) as? JsonBool)?.value ?: error("expected boolean $name")
	}
}

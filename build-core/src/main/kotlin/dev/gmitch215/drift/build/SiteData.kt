package dev.gmitch215.drift.build

import groovy.json.JsonSlurper

class SiteRef(val url: String, val quote: String)

class SiteIssue(val id: String, val state: String, val resolved: String?, val summary: String)

class SiteCell(val target: String, val version: String, val status: String, val lines: List<String>)

class SiteVersion(val version: String, val divergent: Boolean, val groups: List<List<String>>)

class SiteProbe(
	val id: String,
	val question: String,
	val source: String,
	val classification: String,
	val basis: String,
	val causes: List<String>,
	val downgradedFrom: String?,
	val references: List<SiteRef>,
	val issues: List<SiteIssue>,
	val searched: String?,
	val repro: String?,
	val note: String,
	val versions: List<SiteVersion>,
	val cells: List<SiteCell>,
) {
	val divergent: Boolean get() = versions.any { it.divergent }
}

class SiteColumn(val target: String, val how: String)

class SiteDataset(val probes: List<SiteProbe>, val versions: List<String>) {
	val columns: List<Pair<String, String>> =
		probes.first().cells.map { it.target to it.version }

	fun count(classification: String, divergentOnly: Boolean = false) = probes.count {
		it.classification == classification && (!divergentOnly || it.divergent)
	}
}

class SiteDataException(message: String) : RuntimeException(message)

/** Reads `data/dataset.json` (schema 2) and the hand-written column descriptions. */
object SiteData {
	const val SCHEMA = 2

	fun dataset(json: String): SiteDataset {
		val root = map(JsonSlurper().parseText(json), "dataset")
		if (root["schema"] != SCHEMA) throw SiteDataException("dataset schema ${root["schema"]}")
		val probes = list(root["probes"], "probes").map { probe(map(it, "probe")) }
		if (probes.isEmpty()) throw SiteDataException("dataset has no probes")
		val versions = strings(map(root["axes"], "axes")["versions"], "versions")
		return SiteDataset(probes, versions)
	}

	fun columns(yaml: String, name: String): List<SiteColumn> {
		val root = map(RuleYaml.load(yaml, name), name)
		return list(root["columns"], "columns").map {
			val c = map(it, "column")
			SiteColumn(text(c["target"], "target"), text(c["how"], "how"))
		}
	}

	private fun probe(o: Map<*, *>): SiteProbe {
		val info = map(o["classification"], "classification")
		val divergence = map(o["divergence"], "divergence")
		return SiteProbe(
			id = text(o["id"], "id"),
			question = text(o["question"], "question"),
			source = text(o["source"], "source"),
			classification = text(info["classification"], "classification"),
			basis = text(info["basis"], "basis"),
			causes = strings(info["causes"], "causes"),
			downgradedFrom = info["downgradedFrom"] as? String,
			references = list(info["references"], "references").map {
				val r = map(it, "reference")
				SiteRef(text(r["url"], "url"), text(r["quote"], "quote"))
			},
			issues = list(info["issues"], "issues").map {
				val i = map(it, "issue")
				SiteIssue(
					text(i["id"], "id"),
					text(i["state"], "state"),
					i["resolved"] as? String,
					text(i["summary"], "summary"),
				)
			},
			searched = info["searched"] as? String,
			repro = info["repro"] as? String,
			note = text(info["note"], "note"),
			versions = list(divergence["versions"], "versions").map {
				val v = map(it, "version")
				SiteVersion(
					text(v["version"], "version"),
					v["divergent"] == true,
					list(v["groups"], "groups").map { g -> strings(g, "group") },
				)
			},
			cells = list(o["cells"], "cells").map {
				val c = map(it, "cell")
				SiteCell(
					text(c["target"], "target"),
					text(c["version"], "version"),
					text(c["status"], "status"),
					strings(c["lines"], "lines"),
				)
			},
		)
	}

	private fun map(v: Any?, what: String): Map<*, *> =
		v as? Map<*, *> ?: throw SiteDataException("$what is not an object")

	private fun list(v: Any?, what: String): List<*> =
		v as? List<*> ?: throw SiteDataException("$what is not an array")

	private fun text(v: Any?, what: String): String =
		v as? String ?: throw SiteDataException("$what is not a string")

	private fun strings(v: Any?, what: String): List<String> = list(v, what).map { text(it, what) }
}

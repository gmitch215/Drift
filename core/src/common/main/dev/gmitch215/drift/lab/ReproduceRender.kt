package dev.gmitch215.drift.lab

import dev.gmitch215.drift.case.Detail

/** Plain text for a [Reproduction] at three levels; presentation only, the files hold the data. */
object ReproduceRender {
	private const val WIDTH = 100
	private const val HANG = 2

	fun render(r: Reproduction, detail: Detail): String {
		val out = Out()
		out.text(r.summary)
		if (r.notMirrored + r.partial > 0) {
			out.text(
				"This is not the original environment. Attributes that are not mirrored are " +
					"listed with their reasons at --detail detail.",
			)
		}
		if (detail == Detail.SUMMARY) return out.finish()
		out.blank()
		out.text("Files: Dockerfile, run.sh, manifest.json")
		out.text("Base image: ${r.image}; ${r.basis}")
		if (r.digest == null) {
			out.text("Pinned by tag only; a tag can move. Pass --digests to pin a digest.")
		}
		if (r.unusedDigests.isNotEmpty()) {
			out.text("Digests not used: ${r.unusedDigests.joinToString(", ")}")
		}
		val not = r.entries.filter { it.status == Status.NOT_MIRRORED }
		if (not.isNotEmpty()) {
			out.blank()
			out.text("Not mirrored (${not.size}), by reason:")
			for ((reason, group) in not.groupBy { it.detail }) {
				out.text(reason, 2)
				out.text(group.joinToString(", ") { it.path }, 4)
			}
		}
		val partial = r.entries.filter { it.status == Status.PARTIAL }
		if (partial.isNotEmpty()) {
			out.blank()
			out.text("Partially mirrored (${partial.size}):")
			for (e in partial) out.text("${e.path}: ${e.detail}", 2)
		}
		out.blank()
		out.text(
			"A different base image also changes: " +
				(r.bundleAttributes + r.bundleOthers).joinToString(", ") + ".",
		)
		if (detail == Detail.DETAIL) return out.finish()
		val mirrored = r.entries.filter { it.status == Status.MIRRORED }
		if (mirrored.isNotEmpty()) {
			out.blank()
			out.text("Mirrored (${mirrored.size}):")
			for (e in mirrored) out.text("${e.path}: ${e.detail}", 2)
		}
		out.blank()
		out.text("docker run flags from run.sh:")
		for (flag in r.flags) out.text(flag, 2)
		out.blank()
		out.text("Conventions:")
		for (c in Reproduce.conventions) out.text(c, 2)
		return out.finish()
	}

	internal class Out {
		private val lines = mutableListOf<String>()

		fun blank() {
			lines += ""
		}

		fun text(text: String, indent: Int = 0) {
			var line = " ".repeat(indent)
			var empty = true
			for (word in text.split(' ', '\n', '\t').filter { it.isNotEmpty() }) {
				if (!empty && line.length + 1 + word.length > WIDTH) {
					lines += line
					line = " ".repeat(indent + HANG)
					empty = true
				}
				line += (if (empty) "" else " ") + word
				empty = false
			}
			lines += line
		}

		fun finish() = lines.joinToString("\n") + "\n"
	}
}

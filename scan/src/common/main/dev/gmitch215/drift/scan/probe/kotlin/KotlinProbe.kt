package dev.gmitch215.drift.scan.probe.kotlin

import dev.gmitch215.drift.scan.probe.Family
import dev.gmitch215.drift.scan.probe.Probe
import dev.gmitch215.drift.scan.probe.ProbeContext

enum class Classification { DOCUMENTED, PLATFORM_DEFINED, UNCLASSIFIED }

/**
 * One Kotlin-semantics probe. [KotlinProbe.source] is the text shown to users and
 * `body` is the code that runs; they are two copies written by hand and can drift
 * apart. Classification, references and issues live in `atlas/classification.yml`.
 */
class KotlinProbe(
	val id: String,
	val question: String,
	source: String,
	body: ProbeContext.() -> Unit,
) {
	val source: String = source.trimIndent()
	val probe = Probe(id, Family.KOTLIN, body = body)
}

internal fun ProbeContext.outcome(label: String, block: () -> Any?) {
	line(
		label,
		try {
		"ok:" + block()
	} catch (e: Throwable) {
		"err:" + e::class.simpleName
	},
	)
}

internal fun ProbeContext.failure(label: String, block: () -> Any?) {
	line(
		label,
		try {
		"ok:" + block()
	} catch (e: Throwable) {
		"err:" + e::class.simpleName + ":" + e.message
	},
	)
}

internal fun hex(code: Int): String = code.toString(16)

internal fun codes(text: String): List<String> = text.map { hex(it.code) }

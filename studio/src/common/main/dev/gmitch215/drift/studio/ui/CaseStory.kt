package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.lab.SolveCertificate
import dev.gmitch215.drift.model.Capsule

class Difference(val path: String, val before: String?, val after: String?, val cause: Boolean)

/**
 * A solved case in words. [headline] says what Drift found, [sureness] how it knows, [next] what
 * to try; [experiments] and [notes] are the details behind them.
 */
class CaseStory(
    val headline: String,
    val sureness: String,
    val next: String,
    val differences: List<Difference>,
    val experiments: List<String>,
    val notes: List<String>,
) {
    companion object {
        /** Throws [JsonException] when the case lacks a file the story needs. */
        fun of(case: CaseFile): CaseStory {
            val certificate = SolveCertificate.certificate(case)
                ?: throw JsonException("missing ${SolveCertificate.CERTIFICATE}")
            val verdict = certificate.require("verdict").obj()
            val runs = certificate.require("experiments").array().map { it.obj() }
            val forward = runs.firstOrNull { it.text("role") == "forward" }
            val reverse = runs.firstOrNull { it.text("role") == "reverse" }
            val kind = verdict.text("kind")
            val members = strings(verdict["members"])
            val differences = differences(case, members)
            return CaseStory(
                headline(kind, verdict, members),
                sureness(kind, forward, reverse),
                next(kind, verdict, members, differences),
                differences,
                runs.flatMap { run -> listOf("control", "treatment").map { arm(run, it) } },
                strings(certificate["honesty"]),
            )
        }

        private fun headline(kind: String, verdict: JsonObject, members: List<String>) =
            when (kind) {
                "confirmed" -> "Drift found the cause: ${things(members)}."

                "confirmed-bundle" ->
                    "These differences together cause the failure: ${things(members)}. " +
                        "Drift could not tell which one matters, because they changed " +
                        "at the same time."

                "confirmed-effect" ->
                    "Setting ${things(members)} to the failing values reproduces the failure. " +
                        "Drift did not capture both containers, so it cannot say that nothing " +
                        "else changed."

                "narrowed" -> strings(verdict["candidates"]).let {
                    if (it.isEmpty()) {
                        "Drift could not confirm a cause."
                    } else {
                        "Drift narrowed the cause down to ${things(it)} but could not confirm it."
                    }
                }

                else -> stuck(verdict["reason"])
            }

        private fun stuck(reason: JsonValue?) = when ((reason as? JsonString)?.value) {
            "uncontrollable" ->
                "The differences that remain cannot be set from a container, so Drift could " +
                    "not test them."

            "not-reproduced" ->
                "The failure did not happen again in a container built from the failing run, " +
                    "so Drift had nothing to explain."

            "budget" -> "Drift reached its round, time or trial limit before it could decide."

            "executor" -> "Drift could not build or run the experiment containers."

            "no-candidates" ->
                "The two environments differ in nothing Drift can test. The cause may be " +
                    "outside what the capsules show."

            else -> "Drift could not decide."
        }

        private fun sureness(kind: String, forward: JsonObject?, reverse: JsonObject?): String {
            val put = forward?.let { "With the failing values put in, ${tally(it)}." }
            val back = reverse?.let {
                "With the passing values put back into the failing run, ${tally(it)}."
            }
            return when (kind) {
                "confirmed" -> listOfNotNull("Confirmed by experiment.", put, back)

                "confirmed-bundle" ->
                    listOfNotNull("The effect is confirmed for the whole group.", put, back)

                "confirmed-effect" ->
                    listOfNotNull("The effect is confirmed; the cause is not isolated.", put, back)

                "narrowed" -> listOfNotNull(
                    "Not sure.",
                    put?.let { "$it That is not a clear enough result to call." }
                        ?: "No experiment gave a clear result.",
                )

                else -> listOf("No conclusion.")
            }.joinToString(" ")
        }

        private fun next(
            kind: String,
            verdict: JsonObject,
            members: List<String>,
            differences: List<Difference>,
        ): String {
            val suggested = (verdict["next"] as? JsonString)?.value
            if (suggested != null) {
                val step = suggested.replaceFirstChar { it.uppercase() }
                return "Drift suggests this next step: $step."
            }
            val back = putBack(members, differences)
            return when (kind) {
                "confirmed" -> "$back and run the failing job again."

                "confirmed-bundle" ->
                    "$back and run the failing job again. To find which one matters, change " +
                        "them one at a time."

                "confirmed-effect" ->
                    "$back and run the failing job again. To isolate the cause, run Drift again " +
                        "where the containers can be captured."

                else ->
                    "Drift has no next experiment to suggest. The technical details show " +
                        "what it recorded."
            }
        }

        private fun putBack(members: List<String>, differences: List<Difference>): String {
            val only = differences.firstOrNull { members.size == 1 && it.path == members[0] }
            return when {
                only == null -> "Put ${things(members)} back to their passing values"
                only.before == null -> "Remove the ${Plain.name(only.path)}"
                else -> "Set the ${Plain.name(only.path)} back to ${Plain.value(only.before)}"
            }
        }

        private fun differences(case: CaseFile, members: List<String>): List<Difference> = try {
            val passing = Capsule.parse(case.text("environments/passing.json") ?: "")
            val failing = Capsule.parse(case.text("environments/failing.json") ?: "")
            CapsuleDiff.diff(passing, failing).changes
                .map { Difference(it.path, it.before, it.after, it.path in members) }
                .sortedBy { if (it.cause) 0 else 1 }
        } catch (e: JsonException) {
            emptyList()
        }

        private fun arm(run: JsonObject, which: String): String {
            val side = run.require(which).obj()
            return "${run.text("id")}: ${side.text("label")}: " +
                "${side.long("failures")} of ${side.long("trials")} runs failed"
        }

        private fun tally(run: JsonObject): String {
            val treated = run.require("treatment").obj()
            val control = run.require("control").obj()
            return "${treated.long("failures")} of ${treated.long("trials")} runs failed " +
                "(${control.long("failures")} of ${control.long("trials")} before)"
        }

        private fun things(paths: List<String>): String =
            list(paths.map { "the ${Plain.name(it)}" })

        private fun list(items: List<String>): String = when (items.size) {
            0 -> "nothing"
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }

        private fun strings(value: JsonValue?): List<String> =
            (value as? JsonArray)?.items?.map { it.string() }.orEmpty()

        private fun JsonObject.text(key: String): String = require(key).string()

        private fun JsonObject.long(key: String): Long = require(key).long()
    }
}

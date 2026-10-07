package dev.gmitch215.drift

import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.symptom.SymptomExtractor

object FixtureRuns {
	const val JOB = "Docker E2E"
	val nightlies = listOf("35585969640", "36232485230", "36311117223", "36412389380")
	val green = "36702683742"
	val greens = nightlies + green
	val reds = listOf("36995781138", "37114464625", "37195797966")

	private fun steps(id: String): List<Step> =
		CanonicalJson.parse(RunFixtures.text("runs/$id.json")).obj().require("jobs").array()
			.flatMap { j ->
				val name = j.obj().require("name").string()
				j.obj().require("steps").array().map { s ->
					val o = s.obj()
					fun time(key: String) = (o[key] as? JsonString)?.value?.let {
						SymptomExtractor.epochAt(it, 0)
					}
					val start = time("startedAt")
					val end = time("completedAt")
					Step(
						name,
						o.require("name").string(),
						StepConclusion.ofConclusion(o.require("conclusion").string()),
						if (start != null && end != null) end - start else null,
					)
				}
			}

	fun run(id: String): Run {
		val summary = CanonicalJson.parse(RunFixtures.text("runs/$id.json")).obj()
		return Run(
			id = id,
			outcome = RunOutcome.ofConclusion(summary.require("conclusion").string()),
			steps = steps(id),
			symptoms = SymptomExtractor.extract(RunFixtures.text("logs/$id.log")),
			capsule = Capsule.parse(RunFixtures.text("capsules/$id.json")),
		)
	}
}

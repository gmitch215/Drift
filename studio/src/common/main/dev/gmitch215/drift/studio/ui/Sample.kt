package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.studio.StudioState

const val GREEN_RUN = "36702683742"

val RED_RUNS = listOf("36995781138", "37114464625", "37195797966")

class Sample(val name: String, val green: Capsule, val red: Capsule) {
	val state: StudioState by lazy { StudioState.of(green, red) }
}

private fun capsule(run: String) = Capsule.parse(Fixtures.text("$run.json"))

fun samples(): List<Sample> {
	val green = capsule(GREEN_RUN)
	return RED_RUNS.map { Sample("green vs red $it", green, capsule(it)) }
}

fun sampleState(): StudioState = samples().first().state

package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlin.time.TimeSource

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(transcript, millis) => { globalThis.driftDevice = { transcript, millis }; }")
private external fun publishDevice(transcript: String, millis: Int)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
	ComposeViewport {
		StudioApp(
			measure = { dataset ->
				val start = TimeSource.Monotonic.markNow()
				Devices.measure(dataset).also {
					publishDevice(it.transcript, start.elapsedNow().inWholeMilliseconds.toInt())
				}
			},
		)
	}
}

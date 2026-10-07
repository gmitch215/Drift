package dev.gmitch215.drift.scan.probe

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal val timeProbes = listOf(
	Probe("time.duration-format", Family.TIME) {
		line("minutes", 90.minutes)
		line("fractional", 1.5.seconds)
		line("millis", 1234567.milliseconds)
		line("infinite", Duration.INFINITE)
		line("zero", 0.seconds)
		line("negative", (-5).seconds)
		line("nanos", 100.nanoseconds)
		line("days", 3.days + 4.hours)
		line("iso", 90.minutes.toIsoString())
		line("whole-units", 3725.seconds.toComponents { h, m, s, _ -> "$h:$m:$s" })
	},
	Probe("time.monotonic", Family.TIME) {
		val start = TimeSource.Monotonic.markNow()
		val first = start.elapsedNow()
		val second = start.elapsedNow()
		line("non-negative", first >= Duration.ZERO)
		line("non-decreasing", second >= first)
	},
)

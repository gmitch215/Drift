package dev.gmitch215.drift.build

object CoverageModules {
	val ratio = listOf("host", "core", "scan", "cli", "studio", "bench")
	val all = ratio + "tools"
}

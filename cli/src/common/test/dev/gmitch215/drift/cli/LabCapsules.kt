package dev.gmitch215.drift.cli

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule

object LabCapsules {
	val node = Capsule(
		"lab-node",
		listOf(
			"cgroup.cpu.max" to "120000 100000",
			"cgroup.memory.max" to "536870912",
			"env.LANG" to "en_GB.UTF-8",
			"env.TZ" to "Asia/Tokyo",
			"limits.nofile" to "2048:2048",
			"os.arch" to "x86_64",
			"os.release.ID" to "debian",
			"os.release.VERSION_ID" to "12",
			"tool.node.version" to "22.12.0",
		).map { Attribute(it.first, it.second, "test") },
	)
}

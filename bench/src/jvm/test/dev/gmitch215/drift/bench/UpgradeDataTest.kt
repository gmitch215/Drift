package dev.gmitch215.drift.bench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class UpgradeDataTest {
    @Test
    fun theCommandWritesTheCommittedRecordFromTheDevCapsules() {
        val bench = Path.of(".")
        val tmp = Files.createTempDirectory("upgrade-bench")
        val data = tmp.resolve("data").createDirectories()
        for (name in listOf("scenarios.json", "split.json")) {
            bench.resolve("data/$name").copyTo(data.resolve(name))
        }
        val capsules = data.resolve("capsules").createDirectories()
        Files.list(bench.resolve("data/capsules")).use { s ->
            s.filter { it.name.startsWith("migration-") }.forEach {
                it.copyTo(capsules.resolve(it.name))
            }
        }
        val score = Work(tmp).writeUpgrade()
        assertEquals(4, score.scenarios)
        assertEquals(
            bench.resolve("upgrade/upgrade.json").readText(),
            tmp.resolve("upgrade/upgrade.json").readText(),
        )
    }
}

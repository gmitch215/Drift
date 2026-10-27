package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SolvesDataTest {
    private val root = Path.of(".")
    private val work = Work(root)
    private val scenarios = work.loadScenarios()
    private val dev = work.devIds()

    private fun scratch(): Path {
        val tmp = Files.createTempDirectory("solves")
        for (name in listOf("scenarios.json", "split.json", "calibration/evaluation.json")) {
            val to = tmp.resolve("data/$name")
            to.parent.createDirectories()
            Files.copy(root.resolve("data/$name"), to)
        }
        return tmp
    }

    private fun strings(v: JsonValue): List<String> =
        (v as JsonArray).items.map { (it as JsonString).value }

    private fun certificate(kind: String, minimal: String): String =
        "{\"experiments\":[{\"control\":{\"trials\":8},\"treatment\":{\"trials\":8}}]," +
            "\"verdict\":{\"candidates\":[],\"kind\":\"$kind\",\"members\":[$minimal]," +
            "\"minimalSet\":[$minimal],\"reason\":null,\"statement\":\"\"}}"

    private fun case(parent: Path, id: String, text: String?) {
        val dir = parent.resolve(id).createDirectories()
        if (text != null) dir.resolve("certificate.json").writeText(text)
    }

    private val single = scenarios.first { it.klass == "single" && it.id in dev }
    private val control = scenarios.first { it.klass == "control" && it.id in dev }
    private val test = scenarios.first { it.id !in dev }

    @Test
    fun theCommandScoresTwoDirectoriesAndWritesTheSameBytesTwice() {
        val tmp = scratch()
        val a = tmp.resolve("solved-a")
        val b = tmp.resolve("solved-b")
        case(a, single.id, certificate("confirmed", "\"${single.cause!!.path}\""))
        case(b, control.id, certificate("confirmed", "\"env.TZ\""))
        val first = Work(tmp).writeSolves(listOf(a, b))
        val out = tmp.resolve("data/solves/dev.json")
        val text = out.readText()
        Work(tmp).writeSolves(listOf(b, a))
        assertEquals(text, out.readText())
        assertEquals(text, first.toJson())
        val json = CanonicalJson.parse(text) as JsonObject
        assertEquals(JsonInt(2), json.fields["scenarios"])
        val ids = (json.fields.getValue("ids") as JsonObject).fields
        assertEquals(listOf(single.id), strings(ids.getValue("confirmed-correct")))
        assertEquals(listOf(control.id), strings(ids.getValue("control-false")))
        assertEquals(dev.size - 2, strings(json.fields.getValue("not_solved")).size)
    }

    @Test
    fun aTestIdIsRefusedAndNothingIsWritten() {
        val tmp = scratch()
        val dir = tmp.resolve("solved")
        case(dir, test.id, certificate("stuck", ""))
        assertFailsWith<SealedException> { Work(tmp).writeSolves(listOf(dir)) }
        assertTrue(!tmp.resolve("data/solves/dev.json").exists())
    }

    @Test
    fun aDirectoryWithoutACertificateIsUnreadableAndTheRunContinues() {
        val tmp = scratch()
        val dir = tmp.resolve("solved")
        case(dir, single.id, null)
        val score = Work(tmp).writeSolves(listOf(dir))
        assertEquals(SolveOutcome.UNREADABLE, score.cases.single().outcome)
    }

    @Test
    fun anIdInTwoDirectoriesIsAnError() {
        val tmp = scratch()
        val a = tmp.resolve("solved-a")
        val b = tmp.resolve("solved-b")
        case(a, single.id, null)
        case(b, single.id, null)
        assertFailsWith<IllegalArgumentException> { Work(tmp).writeSolves(listOf(a, b)) }
    }

    @Test
    fun theCommittedScoreMatchesTheScenarioLabelsAndCoversTheDevSplit() {
        val text = root.resolve("data/solves/dev.json").readText()
        val json = CanonicalJson.parse(text) as JsonObject
        val rows = (json.fields.getValue("cases") as JsonArray).items
            .map { (it as JsonObject).fields }
        val byId = scenarios.associateBy { it.id }
        for (r in rows) {
            val s = byId.getValue((r.getValue("id") as JsonString).value)
            assertTrue(s.id in dev, "${s.id} is a test id")
            assertEquals(JsonString(s.klass), r["class"], s.id)
            assertEquals(s.cause?.path?.let { JsonString(it) } ?: JsonNull, r["cause"], s.id)
        }
        val solved = rows.map { (it["id"] as JsonString).value }
        val notSolved = strings(json.fields.getValue("not_solved"))
        assertEquals(dev.sorted(), (solved + notSolved).sorted())
        val listed = (json.fields.getValue("ids") as JsonObject).fields.values
            .sumOf { strings(it).size }
        assertEquals(rows.size, listed)
        val classes = (json.fields.getValue("classes") as JsonObject).fields
        val perClass = SolveBench.CLASSES.sumOf { c ->
            ((classes.getValue(c) as JsonObject).fields.getValue("scenarios") as JsonInt).value
        }
        assertEquals(rows.size.toLong(), perClass)
    }
}

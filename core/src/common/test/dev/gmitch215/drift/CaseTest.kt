package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseRead
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.CaseWrite
import dev.gmitch215.drift.case.Chain
import dev.gmitch215.drift.case.ChainBroken
import dev.gmitch215.drift.case.HashMismatch
import dev.gmitch215.drift.case.MalformedFile
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.case.MissingFile
import dev.gmitch215.drift.case.UnsupportedSchema
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CaseTest {
	private val case = DranglerCase.case()

	private val golden = mapOf(
		"case.json" to
			"fe19f15d91154e26d084f5707f97e51771a714e732f8ae2dc5f40b75258d2496",
		"eliminations/excluded.json" to
			"ec567b120e8ca226520646a1f37e542f14f688919b9a871148587051769a7653",
		"environments/failing.json" to
			"40380c709345110b5af7fd2c2af356b387b9143a25518407b6704b84091a4cc7",
		"environments/passing.json" to
			"f622259ce0290e47cf3c44a9689dbbddac72d7f6f2930623f27ffc725f49d2cf",
		"experiments/plan.json" to
			"54a0d6d4859f5a569dc61fc2bbb055dbc7955be4a0253e467b8e8123a16983b1",
		"hypotheses/prior.json" to
			"6931f16d98b8e92e6b18dad73934c3b135078527002eb5542c0c0669914fc5fd",
		"hypotheses/ranking.json" to
			"189eb08d0ef4a5bc26c929a8116e584e974d74af9b67af23e7fa8bdcc20fc8d5",
		"manifest.json" to
			"6fc01f00c6c13af7abc6efc0dd892f0bbc90ba73db5ff0713b7e08d42038a990",
		"observations/0001.json" to
			"fba59c9eb763701f21af088b3aa24736721e56792d1b4bf68d341a3b3abfdb4f",
		"observations/0002.json" to
			"06ad04b2cb47458e56184c95297ed83852eb07a6a61bdea1f3f56e64f4671768",
		"observations/0003.json" to
			"dff7548a4eac2af6661adf8f3bdfcfbc78a576d0a3386822f5bcbcd89091743b",
		"observations/0004.json" to
			"539c9635a280fdd971245daeded8dc03926c5fc0dae1fec5fe65ee93c661b1a3",
		"observations/0005.json" to
			"2eaf72dd6f58958ea3278618d690543851b36b83e6b348542ae820ce6225d082",
		"observations/0006.json" to
			"7014f863bfa93bf33189f829cb64ae0c04f880d4f7cbcbb3e420e8b3c7e20386",
		"observations/0007.json" to
			"2ac96457ef8e4b4005dfbea7919112d094e2d19774f28baf6c9e2401d47b56c1",
		"observations/0008.json" to
			"b8154fae5cd8f55ebe2c6189d7a8cf6d1a32937e39b6b19b74f1c8af22d60274",
		"observations/0009.json" to
			"2e1e2f83ea0fd214bb30236835bfe80960e51df100aff359c3747d4e785e8828",
		"observations/0010.json" to
			"281ce989ce6fc1e9c5c408d4465a31fc014c6446678abdc91982b4d2107e855a",
		"results/index.json" to
			"5021e624e752b001ce3e3846e8f158ed4aeb93a4c9a72fdb35a0c5b14a0eea84",
	)

	private fun disk(files: Map<String, String>): MemoryCaseFiles {
		val disk = MemoryCaseFiles()
		for ((path, text) in files) disk.write("case/$path", text)
		return disk
	}

	private fun reload(files: Map<String, String>): CaseRead = CaseStore.read(disk(files), "case")

	private fun loaded(files: Map<String, String>) = (reload(files) as CaseRead.Loaded).case

	private fun failure(f: Map<String, String>) = assertIs<CaseRead.Failed>(reload(f)).problem

	private fun withManifest(files: Map<String, String>) =
		files + (CaseFile.MANIFEST to CaseFile.manifest(files))

	private fun observation(n: Int) = "observations/" + n.toString().padStart(4, '0') + ".json"

	@Test
	fun everyFileOfTheDranglerCaseHasTheSameBytesOnEveryTarget() {
		assertEquals(golden, case.files.mapValues { Sha256.hex(it.value) })
	}

	@Test
	fun theLayoutHasOneFilePerPartAndNoArchive() {
		val paths = case.files.keys.toList()
		assertEquals(
			listOf(
				"case.json",
				"eliminations/excluded.json",
				"environments/failing.json",
				"environments/passing.json",
				"experiments/plan.json",
				"hypotheses/prior.json",
				"hypotheses/ranking.json",
				"manifest.json",
			),
			paths.filter { !it.startsWith("observations/") && !it.startsWith("results/") },
		)
		assertEquals(10, paths.count { it.startsWith("observations/") })
		assertEquals("{\"results\":[]}", case.text("results/index.json"))
		assertTrue(paths.none { it == "certificate.json" || it.endsWith(".driftcase") })
	}

	@Test
	fun theCaseNamesTheFrameAndHoldsTheFullResults() {
		val head = case.json("case.json")
		assertEquals(JsonString("drangler"), head["name"])
		assertEquals(DranglerCase.plan.frame.toJson(), head["frame"])
		assertEquals(DranglerCase.plan.json(), case.text("experiments/plan.json"))
		val ranking = CanonicalJson.encode(DranglerCase.ranking.toJson())
		assertEquals(ranking, case.text("hypotheses/ranking.json"))
		val eliminated = case.text("eliminations/excluded.json")!!
		assertTrue("Dump Host State on Failure" in eliminated, eliminated)
		assertTrue("ran after the failing step" in eliminated, eliminated)
	}

	@Test
	fun noWallClockTimeEntersTheCase() {
		assertTrue(case.files.values.none { "startedAt" in it || "completedAt" in it })
	}

	@Test
	fun theSameInputsGiveTheSameBytesWhateverTheRunOrder() {
		val runs = (FixtureRuns.reds + FixtureRuns.greens).map { FixtureRuns.run(it) }
		assertEquals(case.files, DranglerCase.case(runs.shuffled(Rng(3))).files)
		assertEquals(case.files, DranglerCase.case(runs.reversed()).files)
	}

	@Test
	fun writeReadRewriteGivesIdenticalBytes() {
		val disk = MemoryCaseFiles()
		assertEquals(CaseWrite.Written(case.files.size), CaseStore.write(disk, "out", case))
		val back = (CaseStore.read(disk, "out") as CaseRead.Loaded).case
		assertEquals(case.files, back.files)
		val again = MemoryCaseFiles()
		CaseStore.write(again, "out/", back)
		assertEquals(disk.files, again.files)
		assertEquals(emptyList(), back.check())
	}

	@Test
	fun theManifestListsASha256ForEveryOtherFile() {
		val listed = CanonicalJson.encode(case.json("manifest.json"))
		for ((path, text) in case.files) {
			if (path == "manifest.json") continue
			assertTrue("\"path\":\"$path\"" in listed, path)
			assertTrue("\"sha256\":\"${Sha256.hex(text)}\"" in listed, path)
		}
		assertEquals(CaseFile.manifest(case.files), case.text("manifest.json"))
	}

	@Test
	fun archiveEntriesPutTheManifestLast() {
		val entries = case.archiveEntries()
		assertEquals("manifest.json", entries.last().first)
		assertEquals(case.files.keys, entries.map { it.first }.toSet())
	}

	@Test
	fun aWriterThatFailsReportsThePath() {
		val failing = object : CaseFiles {
			override fun read(path: String): String? = null

			override fun write(path: String, text: String) = !path.endsWith("manifest.json")
		}
		assertEquals(CaseWrite.Failed("d/manifest.json"), CaseStore.write(failing, "d", case))
	}

	// #region tamper
	@Test
	fun anEditedObservationBreaksTheChainAtThatEntry() {
		val text = case.text(observation(4))!!.replace("\"id\":\"", "\"id\":\"x")
		val problems = loaded(case.files + (observation(4) to text)).check()
		val broken = problems.filterIsInstance<ChainBroken>().single()
		assertEquals(3, broken.index)
		assertEquals(observation(4), broken.path)
		assertEquals("content does not match its hash", broken.detail)
		assertEquals(observation(4), problems.filterIsInstance<HashMismatch>().single().path)
	}

	@Test
	fun anEditWithItsOwnHashRecomputedBreaksTheNextLink() {
		val record = case.json(observation(4))
		val prev = (record["prev"] as JsonString).value
		val content = JsonObject(Chain.content(record).fields + ("id" to JsonString("x")))
		val edited = JsonObject(
			content.fields + mapOf(
				"prev" to JsonString(prev),
				"hash" to JsonString(Chain.link(prev, content)),
			),
		)
		val files = withManifest(case.files + (observation(4) to CanonicalJson.encode(edited)))
		val broken = loaded(files).check().single() as ChainBroken
		assertEquals(4, broken.index)
		assertEquals(observation(5), broken.path)
		assertEquals("previous hash does not match", broken.detail)
	}

	@Test
	fun aDroppedLastObservationIsCaughtAgainstTheHead() {
		val broken = loaded(withManifest(case.files - observation(10))).check().single()
		assertEquals("head differs from case.json", (broken as ChainBroken).detail)
		assertEquals(9, broken.index)
	}

	@Test
	fun anEditedFileOtherThanAnObservationIsAHashMismatch() {
		val text = case.text("experiments/plan.json")!!.replace("\"e7\"", "\"e9\"")
		val problems = loaded(case.files + ("experiments/plan.json" to text)).check()
		assertEquals("experiments/plan.json", (problems.single() as HashMismatch).path)
	}
	// #endregion

	// #region unreadable
	@Test
	fun aTruncatedFileIsATypedResult() {
		for ((path, text) in case.files) {
			val problem = failure(case.files + (path to text.substring(0, text.length / 2)))
			assertEquals(MalformedFile::class, problem::class, path)
			assertEquals(path, problem.path)
		}
	}

	@Test
	fun anEmptyFileIsMalformedToo() {
		val problem = failure(case.files + ("case.json" to ""))
		assertEquals(MalformedFile("case.json", "unexpected end"), problem)
	}

	@Test
	fun aMissingFileIsNamed() {
		val without = case.files - "experiments/plan.json"
		assertEquals(MissingFile("experiments/plan.json"), failure(without))
		val none = assertIs<CaseRead.Failed>(CaseStore.read(MemoryCaseFiles(), "nowhere"))
		assertEquals(MissingFile("manifest.json"), none.problem)
	}

	@Test
	fun aNewerOrUnknownSchemaIsRejected() {
		fun reject(path: String, replacement: String): UnsupportedSchema {
			val text = case.text(path)!!.replace("\"schema\":1", replacement)
			return failure(case.files + (path to text)) as UnsupportedSchema
		}
		assertEquals(UnsupportedSchema("case.json", 2, 1), reject("case.json", "\"schema\":2"))
		assertEquals(UnsupportedSchema("case.json", 0, 1), reject("case.json", "\"schema\":0"))
		assertEquals(
			UnsupportedSchema("manifest.json", 99, 1),
			reject("manifest.json", "\"schema\":99"),
		)
		assertEquals(UnsupportedSchema("case.json", null, 1), reject("case.json", "\"s\":1"))
		assertEquals(
			"unsupported case schema 2 in case.json; this build reads schema 1",
			reject("case.json", "\"schema\":2").message(),
		)
	}

	@Test
	fun aManifestPathThatLeavesTheDirectoryIsMalformed() {
		for (bad in listOf("../x.json", "/etc/x.json", "a/../b.json")) {
			val manifest = CanonicalJson.encode(
				obj(
					"schema" to JsonInt(1),
					"files" to JsonArray(
						listOf(obj("path" to JsonString(bad), "sha256" to JsonString("0"))),
					),
				),
			)
			val problem = failure(case.files + ("manifest.json" to manifest))
			assertEquals(MalformedFile("manifest.json", "unsafe path $bad"), problem)
		}
	}

	@Test
	fun everyProblemHasAReadableMessage() {
		assertEquals("missing case file: a", MissingFile("a").message())
		assertEquals("malformed case file a: b", MalformedFile("a", "b").message())
		assertEquals(
			"a was changed: sha256 is 2, the manifest has 1",
			HashMismatch("a", "1", "2").message(),
		)
		assertEquals(
			"observation chain broken at entry 3 (a): c",
			ChainBroken("a", 3, "c").message(),
		)
	}
	// #endregion

	@Test
	fun anEnvironmentFrameCaseNamesLocalAndCi() {
		fun capsule(label: String, node: String) =
			Capsule(label, listOf(Attribute("tool.node.version", node, "test")))
		val local = capsule("laptop", "22.1.0")
		val ci = capsule("runner", "22.9.0")
		val ranking = Ranker.rank(local, ci, emptyList())
		val frame = Frame.environments(local.label, ci.label)
		val plan = Planner.from(ranking, frame, Options(trialMinutes = 2, failures = 1, runs = 1))
		val built = CaseBuilder.build("laptop-vs-runner", local, ci, ranking, plan)
		val kind = (built.json("case.json")["frame"] as JsonObject)["kind"]
		assertEquals(JsonString("environment"), kind)
		assertEquals(2, built.files.keys.count { it.startsWith("observations/") })
		assertEquals(emptyList(), built.check())
	}
}

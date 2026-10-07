package dev.gmitch215.drift

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Detect
import dev.gmitch215.drift.know.Env
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.rank.EvidenceKind
import dev.gmitch215.drift.rank.ProbeLinks
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.rank.Weights
import dev.gmitch215.drift.studio.Facts
import dev.gmitch215.drift.studio.StudioState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankTest {
	private fun attr(path: String, value: String, stability: Stability = Stability.STATIC) =
		Attribute(path, value, "test", stability)

	private fun probe(id: String, transcript: String, status: ProbeStatus = ProbeStatus.OK) =
		ProbeResult(id, status, transcript)

	private fun capsule(label: String, attributes: List<Attribute>, probes: List<ProbeResult>) =
		Capsule(label, attributes, probes)

	private val green = capsule(
		"green",
		listOf(
			attr("tool.java.version", "1.8.0_504"),
			attr("tool.cc.version", "13.2.1"),
			attr("os.release.VERSION_ID", "3.20.8"),
			attr("env.TZ", "UTC"),
			attr("env.HOME", "/root"),
			attr("env.PWD", "/a", Stability.VOLATILE),
			attr("drift.platform", "jvm"),
			attr("os.name", "mac"),
		),
		listOf(
			probe("numeric.double-tostring", "1.0E10\n"),
			probe("process.timezone", "UTC\n"),
			probe("resources.cgroup-cpu", "unlimited\n"),
			probe("process.echo", "hi\n"),
		),
	)

	private val red = capsule(
		"red",
		listOf(
			attr("tool.java.version", "21.0.12.1"),
			attr("tool.cc.version", "14.2.0"),
			attr("os.release.VERSION_ID", "3.22.2"),
			attr("env.TZ", "Europe/Paris"),
			attr("env.HOME", "/home/ci"),
			attr("env.PWD", "/b", Stability.VOLATILE),
			attr("drift.platform", "macosArm64"),
			attr("os.name", "macosx"),
		),
		listOf(
			probe("numeric.double-tostring", "1.0E10\n"),
			probe("process.timezone", "Europe/Paris\n"),
			probe("resources.cgroup-cpu", "requires files\n", ProbeStatus.UNAVAILABLE),
			probe("process.echo", "hi\n"),
		),
	)

	private fun shuffled(c: Capsule, seed: Long): Capsule {
		var state = seed
		fun next(bound: Int): Int {
			state = state * 6364136223846793005L + 1442695040888963407L
			return ((state ushr 33) % bound).toInt()
		}

		fun <T> List<T>.permuted(): List<T> {
			val out = toMutableList()
			for (i in out.indices.reversed()) {
				val j = next(i + 1)
				out[i] = out[j].also { out[j] = out[i] }
			}
			return out
		}
		return c.copy(attributes = c.attributes.permuted(), probes = c.probes.permuted())
	}

	@Test
	fun ranksByTierThenScoreThenPath() {
		val ranked = Ranker.rank(green, red, emptyList()).candidates
		assertEquals(
			listOf(
				"env.TZ",
				"tool.cc.version",
				"tool.java.version",
				"os.release.VERSION_ID",
				"env.HOME",
			),
			ranked.map { it.path },
		)
		assertEquals(
			listOf(Tier.PLAUSIBLE) + List(4) { Tier.DIFFERENCE },
			ranked.map { it.tier },
		)
	}

	private fun go(version: String, vararg probes: ProbeResult) =
		capsule("go", listOf(attr("tool.go.version", version)), probes.toList())

	private fun rank(a: Capsule, b: Capsule) = Ranker.rank(a, b, emptyList())

	private fun tz(value: String, vararg probes: ProbeResult) =
		capsule("tz", listOf(attr("env.TZ", value)), probes.toList())

	@Test
	fun aProbeThatReadsTheCandidatesPathMakesItPlausible() {
		val a = capsule(
			"a",
			listOf(attr("env.TZ", "UTC"), attr("env.LANG", "C"), attr("tool.cc.version", "13")),
			listOf(probe("process.timezone", "UTC\n")),
		)
		val b = capsule(
			"b",
			listOf(
				attr("env.TZ", "Asia/Tokyo"),
				attr("env.LANG", "de"),
				attr("tool.cc.version", "14"),
			),
			listOf(probe("process.timezone", "JST\n")),
		)
		val ranked = rank(a, b).candidates
		assertEquals(listOf("env.TZ", "env.LANG", "tool.cc.version"), ranked.map { it.path })
		assertEquals(
			listOf(Tier.PLAUSIBLE, Tier.CORRELATED, Tier.DIFFERENCE),
			ranked.map { it.tier },
		)
		assertEquals(
			listOf("env.TZ", "probe:process.timezone"),
			ranked.first().evidence.map { it.ref },
		)
		assertEquals(Weights.probe.id, ranked.first().evidence.last().id)
	}

	@Test
	fun aProbeInTheSameDimensionThatDoesNotReadThePathIsOnlyCorrelated() {
		val c = rank(
			go("1.21.13", probe("text.regex", "x")),
			go("1.24.13", probe("text.regex", "y")),
		).candidates.single()
		assertEquals(Tier.CORRELATED, c.tier)
		assertEquals(listOf("tool.go.version", "probe:text.regex"), c.evidence.map { it.ref })
		assertEquals(Weights.probeDimension.id, c.evidence.last().id)
		assertEquals(0L, c.evidence.last().weight)
		assertEquals(c.evidence.first().weight, c.score)
		assertEquals(emptyList(), c.matches)
	}

	@Test
	fun aProbeFromAnotherDimensionThatDoesNotReadThePathIsNoEvidence() {
		val c = rank(
			go("1.21.13", probe("resources.cgroup-cpu", "unlimited\n")),
			go("1.24.13", probe("resources.cgroup-cpu", "limited cpus=1.0\n")),
		).candidates.single()
		assertEquals(Tier.DIFFERENCE, c.tier)
		assertEquals(listOf("tool.go.version"), c.evidence.map { it.ref })
	}

	@Test
	fun aProbeLinkedToAnotherDimensionsPathSupportsIt() {
		val a =
			capsule(
				"a",
				listOf(attr("tool.libc.version", "1.2.4")),
				listOf(probe("process.resolve-localhost", "a")),
			)
		val b =
			capsule(
				"b",
				listOf(attr("tool.libc.version", "1.2.5")),
				listOf(probe("process.resolve-localhost", "b")),
			)
		assertEquals(Tier.PLAUSIBLE, rank(a, b).candidates.single().tier)
	}

	@Test
	fun theLinkTableNamesRealProbesPrefixesAndAClaimedDimension() {
		assertEquals(33, ProbeLinks.attributes.size)
		for ((id, prefixes) in ProbeLinks.attributes) {
			assertTrue(id.substringBefore('.') != "kotlin", id)
			for (prefix in prefixes) assertTrue(Dimension.of(prefix) != null, "$id $prefix")
			for (prefix in prefixes) assertTrue(ProbeLinks.informs(id, prefix + "x"), "$id $prefix")
		}
		assertTrue(!ProbeLinks.informs("text.regex", "tool.python.version"))
		assertTrue(!ProbeLinks.informs("kotlin.math.rounding", "tool.java.version"))
		assertTrue(!ProbeLinks.informs("process.missing-command", "tool.coreutils.version"))
	}

	@Test
	fun noCandidateIsPlausibleOnDimensionLevelProbeAgreementAlone() {
		val ids = ProbeLinks.attributes.keys.toList() + "text.zzz-unlisted"
		val paths = listOf(
			"env.TZ", "env.LANG", "env.HOME", "env.LC_ALL", "tool.java.version", "tool.cc.version",
			"tool.python.version", "tool.libc.version", "os.release.ID", "cgroup.cpu.max",
			"cgroup.memory.max", "tool.git.version", "limits.nofile", "runtime.platform",
		)
		for (seed in 1L..40L) {
			val rng = Rng(seed)
			val changed = paths.filter { rng.nextInt(3) == 0 }
			val differ = ids.filter { rng.nextInt(4) == 0 }
			fun side(tag: String) = capsule(
				tag,
				changed.map { attr(it, "$it-$tag") },
				differ.map { probe(it, "$it-$tag") },
			)
			for (c in rank(side("a"), side("b")).candidates) {
				val linked = differ.any { ProbeLinks.informs(it, c.path) }
				val broad = differ.any { Dimension.ofProbe(it) == Dimension.of(c.path) }
				val expected = when {
					linked -> Tier.PLAUSIBLE
					broad -> Tier.CORRELATED
					else -> Tier.DIFFERENCE
				}
				assertEquals(expected, c.tier, "$seed ${c.path}")
				assertTrue(c.tier != Tier.KNOWN, "$seed ${c.path}")
				assertEquals(c.evidence.sumOf { it.weight }, c.score, "$seed ${c.path}")
			}
		}
	}

	@Test
	fun missingProbeEvidenceNeverMakesACandidatePlausible() {
		val up = go("1.21.13", probe("text.regex", "x"))
		val down = go("1.24.13", probe("text.regex", "requires none\n", ProbeStatus.UNAVAILABLE))
		assertEquals(Tier.DIFFERENCE, rank(up, down).candidates.single().tier)
		assertEquals(Tier.DIFFERENCE, rank(up, go("1.24.13")).candidates.single().tier)
	}

	@Test
	fun aSameProbeIsNoEvidence() {
		val ranked = rank(
			go("1.21.13", probe("text.regex", "x")),
			go("1.24.13", probe("text.regex", "x")),
		)
		assertEquals(Tier.DIFFERENCE, ranked.candidates.single().tier)
	}

	@Test
	fun onlyTheFirstLinkedProbeCarriesWeight() {
		val a = tz("UTC", probe("process.timezone", "UTC\n"), probe("resources.locale-env", "1"))
		val b = tz(
			"Asia/Tokyo",
			probe("process.timezone", "JST\n"),
			probe("resources.locale-env", "2"),
		)
		val items = rank(a, b).candidates.single().evidence
		assertEquals(
			listOf("env.TZ", "probe:process.timezone", "probe:resources.locale-env"),
			items.map { it.ref },
		)
		assertEquals(listOf(true, true, false), items.map { it.weight > 0 })
		assertEquals(Weights.probe.logOdds, items[1].weight)
		assertEquals(0L, items[2].weight)
	}

	@Test
	fun captureOnlyAndUnclassifiedChangesNeverRank() {
		val a = capsule("a", listOf(attr("drift.platform", "jvm"), attr("zzz.x", "1")), emptyList())
		val b = capsule("b", listOf(attr("drift.platform", "mac"), attr("zzz.x", "2")), emptyList())
		assertEquals(emptyList(), rank(a, b).candidates)
		assertEquals(2, CapsuleDiff.diff(a, b).changes.size)
	}

	@Test
	fun selfDiffRanksNothing() {
		assertEquals(emptyList(), Ranker.rank(green, green).candidates)
		assertEquals(emptyList(), StudioState.of(green, green).relevant)
		assertEquals(emptyList(), StudioState.of(green, green).competing)
		assertEquals(emptyList(), StudioState.of(green, green).bundles)
	}

	@Test
	fun osNameSpellingsDoNotRank() {
		val ranked = rank(green, red).candidates.map { it.path }
		assertTrue("os.name" !in ranked, ranked.toString())
	}

	@Test
	fun permutingAttributesProbesAndRulesChangesNothing() {
		val rules = Rules.all
		val expected = Ranker.rank(green, red, rules)
		val state = StudioState.of(green, red, rules)
		for (seed in 1L..25L) {
			val g = shuffled(green, seed)
			val r = shuffled(red, seed + 100)
			val order = rules.shuffled(Rng(seed))
			assertEquals(expected, Ranker.rank(g, r, order), "$seed")
			assertEquals(state, StudioState.of(g, r, order), "$seed")
			assertEquals(CapsuleDiff.diff(green, red), CapsuleDiff.diff(g, r), "$seed")
		}
	}

	@Test
	fun everyDimensionAndEveryWeightHasAReason() {
		assertEquals(Dimension.entries.toSet(), Weights.dimensions.keys)
		assertEquals(Weights.all.size, Weights.all.map { it.id }.toSet().size)
		for (w in Weights.all) {
			assertTrue(w.reason.isNotBlank() && '\n' !in w.reason, w.id)
			assertTrue(w.ratio >= FixedPoint.ONE, w.id)
			assertTrue(w.logOdds >= 0, w.id)
		}
	}

	@Test
	fun theWeightsSayTheyAreNotCalibrated() {
		assertEquals(false, Weights.CALIBRATED)
		val json = CanonicalJson.encode(StudioState.of(green, red).toJson())
		val probability = """"probability":{"calibrated":true,"covers":["attribute"],""" +
			""""fit":"${Weights.FIT}","none":${Weights.NONE},""" +
			""""uncovered":["probe","rule","spectrum"]}"""
		assertTrue(""""weights":{"calibrated":false,"id":"hand-set-1",$probability}""" in json)
	}

	@Test
	fun scoreIsTheLogOddsSumOfItsEvidenceInFixedPoint() {
		val up = tz("UTC", probe("process.timezone", "UTC\n"))
		val down = tz("Asia/Tokyo", probe("process.timezone", "JST\n"))
		val c = rank(up, down).candidates.single()
		val locale = FixedPoint.ln(Weights.dimensions.getValue(Dimension.LOCALE).ratio)
		assertEquals(locale, c.evidence[0].weight)
		assertEquals(locale + FixedPoint.ln(Weights.probe.ratio), c.score)
		assertEquals(c.evidence.sumOf { it.weight }, c.score)
	}

	@Test
	fun strongerDimensionOrdersWithinATierThenPath() {
		val a = capsule("a", listOf(attr("env.HOME", "/x"), attr("tool.go.version", "1")), listOf())
		val b = capsule("b", listOf(attr("env.HOME", "/y"), attr("tool.go.version", "2")), listOf())
		assertEquals(listOf("tool.go.version", "env.HOME"), rank(a, b).candidates.map { it.path })
	}

	@Test
	fun studioStateNarrowsFromFactsToCompetingCandidates() {
		val state = StudioState.of(green, red, emptyList())
		assertEquals(Facts(attributes = 8, probes = 4), state.facts)
		assertEquals(CapsuleDiff.diff(green, red), state.changed)
		assertEquals(rank(green, red).candidates, state.relevant)
		assertEquals(
			listOf("env.TZ"),
			state.relevant.filter { it.tier == Tier.PLAUSIBLE }.map { it.path },
		)
		assertEquals(emptyList(), state.competing)
	}

	@Test
	fun competingHoldsTheTiedTopTier() {
		val a = capsule(
			"a",
			listOf(attr("tool.go.version", "1.21"), attr("tool.cc.version", "13")),
			emptyList(),
		)
		val b = capsule(
			"b",
			listOf(attr("tool.go.version", "1.24"), attr("tool.cc.version", "14")),
			emptyList(),
		)
		val state = StudioState.of(a, b, emptyList())
		assertEquals(listOf("tool.cc.version", "tool.go.version"), state.competing.map { it.path })
		assertEquals(state.relevant, state.competing)
		assertEquals(listOf("tool.cc.version", "tool.go.version"), state.bundles.single().members)
		assertEquals(listOf("bundle-1", "bundle-1"), state.relevant.map { it.bundle })
	}

	@Test
	fun aLoneTopCandidateHasNoCompetitors() {
		val a = capsule(
			"a",
			listOf(attr("tool.go.version", "1.21"), attr("env.HOME", "/x")),
			listOf(probe("text.regex", "p")),
		)
		val b = capsule(
			"b",
			listOf(attr("tool.go.version", "1.24"), attr("env.HOME", "/y")),
			listOf(probe("text.regex", "q")),
		)
		val state = StudioState.of(a, b, emptyList())
		assertEquals(listOf("tool.go.version", "env.HOME"), state.relevant.map { it.path })
		assertEquals(emptyList(), state.competing)
		assertEquals(emptyList(), state.bundles)
		assertEquals(listOf(null, null), state.relevant.map { it.bundle })
	}

	// #region probability
	@Test
	fun aLoneAttributeChangeGetsItsShareAgainstNone() {
		val c = rank(go("1.21"), go("1.24")).candidates.single()
		assertEquals(Weights.shares(listOf(c.score)).single(), c.probability)
		assertTrue(c.probability!! in 923_000L..923_100L, c.probability.toString())
	}

	@Test
	fun sharesAddUpToLessThanOneAndFollowTheScore() {
		val a = capsule(
			"a",
			listOf(
				attr("tool.go.version", "1"),
				attr("tool.cc.version", "1"),
				attr("env.HOME", "a"),
			),
			emptyList(),
		)
		val b = capsule(
			"b",
			listOf(
				attr("tool.go.version", "2"),
				attr("tool.cc.version", "2"),
				attr("env.HOME", "b"),
			),
			emptyList(),
		)
		val ranked = rank(a, b).candidates
		val p = ranked.map { it.probability!! }
		assertTrue(p.sum() < FixedPoint.ONE, p.toString())
		assertEquals(p.sortedDescending(), p)
		assertTrue(p.last() < p.first())
		assertEquals(Weights.shares(ranked.map { it.score }), p)
	}

	@Test
	fun theSharesOfTiedScoresAreEqualAndAnEmptyListHasNone() {
		val tied = Weights.shares(listOf(1_000_000L, 1_000_000L, 1_000_000L))
		assertEquals(1, tied.distinct().size)
		assertEquals(emptyList(), Weights.shares(emptyList()))
		assertEquals(2, Weights.shares(listOf(5_000_000L, 99_000_000L)).size)
	}

	@Test
	fun aProbeOrARuleMatchTakesTheProbabilityOffEveryCandidate() {
		assertTrue(rank(green, red).candidates.all { it.probability == null })
		val probed = rank(go("1", probe("text.regex", "x")), go("2", probe("text.regex", "y")))
		assertNull(probed.candidates.single().probability)
		val ruled = Ranker.rank(cc("13.2.1"), cc("14.2.0"), listOf(verifiedRule()))
		assertNull(ruled.candidates.single().probability)
	}

	@Test
	fun oneProbedCandidateTakesTheProbabilityOffItsUnprobedRivals() {
		val a = capsule(
			"a",
			listOf(attr("tool.go.version", "1.21"), attr("env.HOME", "/x")),
			listOf(probe("text.regex", "p")),
		)
		val b = capsule(
			"b",
			listOf(attr("tool.go.version", "1.24"), attr("env.HOME", "/y")),
			listOf(probe("text.regex", "q")),
		)
		assertEquals(listOf(null, null), rank(a, b).candidates.map { it.probability })
	}
	// #endregion

	// #region rules
	private fun cc(version: String) =
		capsule("cc", listOf(attr("tool.cc.version", version)), emptyList())

	private fun verifiedRule(detect: String = GCC_14) =
		demoRule(detect, "symptoms" to """[{"text":"t","basis":"verified"}]""")

	@Test
	fun aRuleMatchWithOnlyVerifiedSymptomsIsKnownWithoutAnyProbe() {
		val rule = verifiedRule()
		val c = Ranker.rank(cc("13.2.1"), cc("14.2.0"), listOf(rule)).candidates.single()
		assertEquals(Tier.KNOWN, c.tier)
		assertEquals(
			listOf(EvidenceKind.ATTRIBUTE, EvidenceKind.RULE),
			c.evidence.map { it.kind },
		)
		assertEquals(listOf("tool.cc.version", "rule:demo-rule"), c.evidence.map { it.ref })
		assertEquals("M.", c.matches.single().mechanism)
		assertEquals(Weights.rules.getValue(rule.severity).logOdds, c.evidence[1].weight)
	}

	@Test
	fun aRuleWithAnInferredSymptomIsPlausibleAndTheBasisStaysVisible() {
		val mixed = demoRule(
			GCC_14,
			"symptoms" to """[{"text":"a","basis":"verified"},{"text":"b","basis":"inferred"}]""",
		)

		fun candidate(rule: Rule) =
			Ranker.rank(cc("13.2.1"), cc("14.2.0"), listOf(rule)).candidates.single()
		for (rule in listOf(demoRule(), mixed)) {
			val c = candidate(rule)
			assertEquals(Tier.PLAUSIBLE, c.tier)
			assertEquals(Basis.INFERRED, c.basis)
			assertEquals(Basis.INFERRED, c.matches.single().basis)
			assertEquals(EvidenceKind.RULE, c.evidence.last().kind)
		}
		val verified = candidate(verifiedRule())
		assertEquals(Tier.KNOWN, verified.tier)
		assertEquals(Basis.VERIFIED, verified.basis)
	}

	@Test
	fun theRuleReadsGreenAsSideAAndRedAsSideB() {
		val swapped = Ranker.rank(cc("14.2.0"), cc("13.2.1"), listOf(demoRule()))
		assertEquals(Tier.DIFFERENCE, swapped.candidates.single().tier)
		assertEquals(emptyList(), swapped.candidates.single().matches)
		assertEquals(emptyList(), swapped.unattached)
	}

	@Test
	fun anUnknownRuleVerdictIsNoMatch() {
		val ranking = Ranker.rank(cc("unparsed"), cc("14.2.0"), listOf(demoRule()))
		assertEquals(Tier.DIFFERENCE, ranking.candidates.single().tier)
	}

	@Test
	fun aRuleThatTouchesNoChangedAttributeIsListedNotDropped() {
		val rule = demoRule("""{"b":"tool.go.version","ver":">=1.24"}""")
		val go = attr("tool.go.version", "1.24")
		val a = capsule("a", listOf(attr("tool.cc.version", "13"), go), listOf())
		val b = capsule("b", listOf(attr("tool.cc.version", "14"), go), listOf())
		val ranking = Ranker.rank(a, b, listOf(rule))
		assertEquals(Tier.DIFFERENCE, ranking.candidates.single().tier)
		assertEquals(listOf("demo-rule"), ranking.unattached.map { it.ruleId })
	}

	@Test
	fun candidatesTheSameRuleCannotSeparateShareABundle() {
		val rule = verifiedRule("""{"changed":"os.release.*"}""")
		val a = capsule(
			"a",
			listOf(attr("os.release.VERSION_ID", "3.20"), attr("os.release.ID", "alpine")),
			emptyList(),
		)
		val b = capsule(
			"b",
			listOf(attr("os.release.VERSION_ID", "3.22"), attr("os.release.ID", "wolfi")),
			emptyList(),
		)
		val ranking = Ranker.rank(a, b, listOf(rule))
		assertEquals(listOf(Tier.KNOWN, Tier.KNOWN), ranking.candidates.map { it.tier })
		assertEquals(
			listOf("os.release.ID", "os.release.VERSION_ID"),
			ranking.bundles.single().members,
		)
	}

	@Test
	fun theCatalogFiresOnAGccCrossing() {
		fun gcc(version: String) = capsule(
			"gcc",
			listOf(attr("tool.cc.family", "gcc"), attr("tool.cc.version", version)),
			emptyList(),
		)

		val c = Ranker.rank(gcc("13.2.1"), gcc("14.2.0")).candidates.single()
		assertEquals(Tier.KNOWN, c.tier)
		assertTrue("gcc-14-c-errors" in c.matches.map { it.ruleId }, c.matches.toString())
		assertTrue(c.matches.all { it.mechanism.endsWith(".") })
	}

	@Test
	fun everyPositiveCatalogFixtureSurfacesInTheRanking() {
		var checked = 0
		val inferredRules = mutableSetOf<String>()
		for (rule in Rules.all) {
			for (f in rule.fixtures.positive.filter { it.a.run.isEmpty() && it.b.run.isEmpty() }) {
				fun side(e: Env) = capsule(
					f.name,
					e.attrs.map { attr(it.key, it.value) },
					e.probes.map { probe(it.key, it.value) },
				)
				if (Detect.match(rule, f.a, f.b).match == null) continue
				val ranking = Ranker.rank(side(f.a), side(f.b), listOf(rule))
				val seen = ranking.candidates.flatMap { it.matches } + ranking.unattached
				for (c in ranking.candidates.filter { it.matches.isNotEmpty() }) {
					val verified = rule.symptoms.all { it.basis == Basis.VERIFIED }
					assertEquals(verified, c.tier == Tier.KNOWN, "${rule.id} ${c.path}")
					if (!verified) inferredRules += rule.id
				}
				val ids = seen.map { it.ruleId }.distinct()
				assertEquals(listOf(rule.id), ids, "${rule.id} ${f.name}")
				checked++
			}
		}
		assertTrue(checked >= 30, "checked $checked")
		val withInferred = Rules.all.filter { r -> r.symptoms.any { it.basis == Basis.INFERRED } }
		assertEquals(9, inferredRules.size)
		assertTrue(withInferred.map { it.id }.containsAll(inferredRules))
	}
	// #endregion

	@Test
	fun noCandidateWithoutAProbeOrARuleIsAboveCorrelated() {
		assertEquals(
			listOf("DIFFERENCE", "CORRELATED", "PLAUSIBLE", "KNOWN"),
			Tier.entries.map { it.name },
		)
		for (c in Ranker.rank(green, red, emptyList()).candidates) {
			val strong = c.evidence.any {
				it.kind == EvidenceKind.PROBE || it.kind == EvidenceKind.RULE
			}
			assertTrue(strong || c.tier <= Tier.CORRELATED, c.path)
		}
		assertNull(Tier.entries.firstOrNull { it.name == "CONFIRMED" })
	}

	@Test
	fun canonicalBytesOfTheStudioStateAreIdenticalOnEveryTarget() {
		val expected =
			"{\"bundles\":[{\"id\":\"bundle-1\",\"members\":[\"tool.cc.version\",\"tool.j" +
			"ava.version\",\"os.release.VERSION_ID\",\"env.HOME\"]}],\"changed\":{\"chang" +
			"es\":[{\"after\":\"3.22.2\",\"before\":\"3.20.8\",\"dimension\":\"os\",\"kin" +
			"d\":\"changed\",\"order\":\"less\",\"path\":\"os.release.VERSION_ID\",\"stab" +
			"ility\":\"static\"},{\"after\":\"21.0.12.1\",\"before\":\"1.8.0_504\",\"dime" +
			"nsion\":\"runtime\",\"kind\":\"changed\",\"order\":\"less\",\"path\":\"tool." +
			"java.version\",\"stability\":\"static\"},{\"after\":\"14.2.0\",\"before\":\"" +
			"13.2.1\",\"dimension\":\"compiler\",\"kind\":\"changed\",\"order\":\"less\"," +
			"\"path\":\"tool.cc.version\",\"stability\":\"static\"},{\"after\":\"/home/ci" +
			"\",\"before\":\"/root\",\"dimension\":\"env\",\"kind\":\"changed\",\"order\"" +
			":\"unordered\",\"path\":\"env.HOME\",\"stability\":\"static\"},{\"after\":\"" +
			"Europe/Paris\",\"before\":\"UTC\",\"dimension\":\"locale\",\"kind\":\"change" +
			"d\",\"order\":\"unordered\",\"path\":\"env.TZ\",\"stability\":\"static\"},{" +
			"\"after\":\"macosArm64\",\"before\":\"jvm\",\"dimension\":null,\"kind\":\"ch" +
			"anged\",\"order\":\"unordered\",\"path\":\"drift.platform\",\"stability\":\"" +
			"static\"}],\"ignored\":[{\"after\":\"/b\",\"before\":\"/a\",\"dimension\":\"" +
			"env\",\"kind\":\"changed\",\"order\":\"unordered\",\"path\":\"env.PWD\",\"st" +
			"ability\":\"volatile\"}],\"probes\":[{\"after\":\"1.0E10\\n\",\"before\":\"1" +
			".0E10\\n\",\"id\":\"numeric.double-tostring\",\"status\":\"same\"},{\"after" +
			"\":\"hi\\n\",\"before\":\"hi\\n\",\"id\":\"process.echo\",\"status\":\"same" +
			"\"},{\"after\":\"Europe/Paris\\n\",\"before\":\"UTC\\n\",\"id\":\"process.ti" +
			"mezone\",\"status\":\"different\"},{\"after\":null,\"before\":\"unlimited\\n" +
			"\",\"id\":\"resources.cgroup-cpu\",\"status\":\"unavailable\"}]},\"competing" +
			"\":[],\"facts\":{\"attributes\":8,\"probes\":4},\"relevant\":[{\"basis\":\"i" +
			"nferred\",\"bundle\":null,\"coverage\":null,\"evidence\":[{\"id\":\"dimensio" +
			"n.locale\",\"kind\":\"attribute\",\"ref\":\"env.TZ\",\"weight\":405465},{\"i" +
			"d\":\"probe.difference\",\"kind\":\"probe\",\"ref\":\"probe:process.timezone" +
			"\",\"weight\":1791759}],\"path\":\"env.TZ\",\"probability\":null,\"rules\":[" +
			"],\"score\":2197224,\"spectrum\":null,\"tier\":\"plausible\"},{\"basis\":\"i" +
			"nferred\",\"bundle\":\"bundle-1\",\"coverage\":null,\"evidence\":[{\"id\":\"" +
			"dimension.compiler\",\"kind\":\"attribute\",\"ref\":\"tool.cc.version\",\"we" +
			"ight\":1098612}],\"path\":\"tool.cc.version\",\"probability\":null,\"rules\"" +
			":[],\"score\":1098612,\"spectrum\":null,\"tier\":\"difference\"},{\"basis\":" +
			"\"inferred\",\"bundle\":\"bundle-1\",\"coverage\":null,\"evidence\":[{\"id\"" +
			":\"dimension.runtime\",\"kind\":\"attribute\",\"ref\":\"tool.java.version\"," +
			"\"weight\":1098612}],\"path\":\"tool.java.version\",\"probability\":null,\"r" +
			"ules\":[],\"score\":1098612,\"spectrum\":null,\"tier\":\"difference\"},{\"ba" +
			"sis\":\"inferred\",\"bundle\":\"bundle-1\",\"coverage\":null,\"evidence\":[{" +
			"\"id\":\"dimension.os\",\"kind\":\"attribute\",\"ref\":\"os.release.VERSION_" +
			"ID\",\"weight\":693147}],\"path\":\"os.release.VERSION_ID\",\"probability\":" +
			"null,\"rules\":[],\"score\":693147,\"spectrum\":null,\"tier\":\"difference\"" +
			"},{\"basis\":\"inferred\",\"bundle\":\"bundle-1\",\"coverage\":null,\"eviden" +
			"ce\":[{\"id\":\"dimension.env\",\"kind\":\"attribute\",\"ref\":\"env.HOME\"," +
			"\"weight\":405465}],\"path\":\"env.HOME\",\"probability\":null,\"rules\":[]," +
			"\"score\":405465,\"spectrum\":null,\"tier\":\"difference\"}],\"unattached\":" +
			"[],\"weights\":{\"calibrated\":false,\"id\":\"hand-set-1\",\"probability\":{" +
			"\"calibrated\":true,\"covers\":[\"attribute\"],\"fit\":\"dev split of 45 syn" +
			"thetic scenarios, seed 20261006\",\"none\":250000,\"uncovered\":[\"probe\"," +
			"\"rule\",\"spectrum\"]}}}"
		val json = CanonicalJson.encode(StudioState.of(green, red, emptyList()).toJson())
		assertEquals(expected, json)
		assertEquals(
			"0d9ef29b6c3910c346836510deda0124acf191cbd2182d75159f778dce74c7e1",
			Sha256.hex(json),
		)
	}
}

package dev.gmitch215.drift

import dev.gmitch215.drift.redact.Anonymizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnonymizerTest {
	private val env = mapOf(
		"env.HOME" to "/Users/zed/",
		"env.USER" to "zed",
		"env.LOGNAME" to "zedd",
		"env.COMPUTERNAME" to "ZEDBOX",
	)

	@Test
	fun ownersComeFromAccountHomeAndHostVariables() {
		val all = mapOf("zedd" to "user", "ZEDBOX" to "host", "zed" to "user")
		assertEquals(all, Anonymizer.owners(env))
		val windows = mapOf("env.HOME" to "C:\\Users\\zed")
		assertEquals(mapOf("zed" to "user"), Anonymizer.owners(windows))
		assertEquals(emptyMap(), Anonymizer.owners(mapOf("env.USER" to "ab", "env.HOME" to "/ab")))
	}

	@Test
	fun homeIsTheTrimmedDirectoryOrNull() {
		assertEquals("/Users/zed", Anonymizer.home(env))
		assertNull(Anonymizer.home(mapOf("env.HOME" to "/")))
		assertNull(Anonymizer.home(emptyMap()))
	}

	@Test
	fun homeBecomesTildeOnlyAtWordBoundaries() {
		val owners = Anonymizer.owners(env)
		val home = Anonymizer.home(env)
		val cases = mapOf(
			"/Users/zed" to "~",
			"/Users/zed/bin:/opt" to "~/bin:/opt",
			"x=/Users/zed;y" to "x=~;y",
			"/Users/zedra/bin" to "/Users/user/bin",
			"/Users/zed.old" to "/Users/user",
			"/home/other/bin" to "/home/user/bin",
			"C:\\Users\\Other\\x" to "C:\\Users\\user\\x",
			"zed and zedd at ZEDBOX" to "user and user at host",
			"zeds zedx" to "zeds zedx",
		)
		for ((input, expected) in cases) {
			assertEquals(expected, Anonymizer.anonymize(input, owners, home), input)
		}
	}

	@Test
	fun withoutHomeOnlyPatternsAndNamesChangeAndTheResultIsStable() {
		val owners = Anonymizer.owners(env)
		val once = Anonymizer.anonymize("/Users/zed/a zed", owners)
		assertEquals("/Users/user/a user", once)
		assertEquals(once, Anonymizer.anonymize(once, owners))
	}
}

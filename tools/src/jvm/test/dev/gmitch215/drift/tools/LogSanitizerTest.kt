package dev.gmitch215.drift.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSanitizerTest {
	private val stamp = "2026-10-02T10:29:52.7707929Z"

	private fun line(body: String) = "Docker E2E\tUNKNOWN STEP\t$stamp $body\n"

	private fun clean(body: String, keep: Set<String> = emptySet()): String =
		LogSanitizer.sanitize(line(body), keep).text.removeSuffix("\n").substringAfter("$stamp ")

	// each secret is assembled at run time so no real-looking token sits in the source
	private val seeds: Map<String, String> = mapOf(
		"github token" to "gh" + "p_" + "Ab1".repeat(12),
		"fine grained token" to "github_" + "pat_" + "Zz9_".repeat(8),
		"aws key" to "AK" + "IA" + "ABCDEFGHIJKLMNOP",
		"slack token" to "xo" + "xb-" + "1234567890-abcdefghij",
		"json web token" to "ey" + "Jhbgcioijiuzi1nij9" + ".eyJzdWIiOiIxMjM0NTY3ODkw" + ".sigsig",
		"npm token" to "npm" + "_" + "A1b2C3d4E5".repeat(4),
		"bearer token" to "Bearer " + "qrstuvwxyz".repeat(3),
		"email" to "alice.smith@corp-internal.io",
		"ipv4" to "10.20.30.40",
		"ipv6" to "fe80::1ff:fe23:4567:890a",
		"mac address" to "00:1a:2b:3c:4d:5e",
		"uuid" to "6f1d2c3b-4a59-4e8d-9c7b-0a1b2c3d4e5f",
		"hex digest" to "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
		"integrity" to "sha512-" + "Zm9vYmFy".repeat(8) + "==",
		"mixed token" to "aB3dE5gH7jK9mN1pQ3sT5vW7yZ9bD1fH3kM5",
		"hostname" to "build-box-7.corp-internal.dev",
		"organization" to "Drupflare",
		"second organization" to "Bytebox",
		"account" to "gmitch215",
		"person" to "Gregory Mitchell",
		"server id" to "server_uid xo3rEzIXz++mXvHDToIqwJ9mslk=",
	)

	@Test
	fun seededSecretsDoNotSurvive() {
		for ((name, seed) in seeds) {
			val out = LogSanitizer.sanitize(line("value $seed end")).text
			val sensitive = seed.removePrefix("Bearer ").removePrefix("server_uid ")
			assertFalse(out.contains(sensitive), "$name survived: $out")
			assertEquals(emptyList(), LogSanitizer.residue(out), "$name left residue: $out")
		}
	}

	@Test
	fun secretsInsideNamedValuesAndUrlsDoNotSurvive() {
		val pass = "s3cr3t" + "Value99"
		val cases = listOf(
			"DATABASE_PASSWORD=$pass",
			"export api_key: $pass",
			"Authorization: basic $pass",
			"git clone https://deploy:$pass@code.corp-internal.io/repo.git",
			"curl https://vault.corp-internal.io/v1/data?token=$pass",
			"curl https://files.corp-internal.io/a/b#frag$pass",
		)
		for (case in cases) {
			val out = clean(case)
			assertFalse(out.contains(pass), "survived: $out")
			assertFalse(out.contains("corp-internal"), "host survived: $out")
		}
	}

	@Test
	fun privateKeyBlocksAreDropped() {
		val begin = "-----BEGIN " + "RSA PRIVATE KEY-----"
		val raw =
			line("before") + line(begin) + line("MIIEowIBAAKCAQEA") +
			line("-----END RSA PRIVATE KEY-----") + line("after")
		val result = LogSanitizer.sanitize(raw)
		assertFalse(result.text.contains("MIIEow"))
		assertFalse(result.text.contains("BEGIN"))
		assertTrue(result.text.contains("after"))
		assertEquals(1, result.counts["private-key-block"])
	}

	@Test
	fun publicHostsVersionsAndTimestampsStay() {
		val kept = listOf(
			"Image: ubuntu-24.04",
			"Version: 20260920.314.1",
			"git version 2.55.0",
			"bun install v1.4.2 (744846f84)",
			"Included Software: https://github.com/actions/runner-images/blob/ubuntu24/x.md",
			"  GITHUB_TOKEN: ***",
			"  persist-credentials: true",
			"Found in cache @ /opt/hostedtoolcache/node/24.21.0/x64",
			"CC_TOKEN=\$INPUT_TOKEN",
			"env.SITE (SitePhpDurableObject)  Durable Object  local",
		)
		for (body in kept) assertEquals(body, clean(body))
		assertEquals(line("x"), LogSanitizer.sanitize("Docker E2E\tUNKNOWN STEP\t$stamp x\n").text)
	}

	@Test
	fun loopbackIsMarkedAndOtherAddressesAreMasked() {
		assertEquals("ssh tester@<loopback>:2222", clean("ssh tester@127.0.0.1:2222"))
		assertEquals("\"ip\":\"<loopback>\"", clean("\"ip\":\"::1\""))
		assertEquals("using <ip>. Set", clean("using 172.18.0.3. Set"))
		assertEquals("http://<loopback>:8902/x", clean("http://127.0.0.1:8902/x"))
		assertEquals("12:34:56 and 10.0.26100 stay", clean("12:34:56 and 10.0.26100 stay"))
	}

	@Test
	fun urlsKeepSchemeAndPathButLoseHostAndQuery() {
		assertEquals(
			"fetching https://<host>/v1.0.2/<org>-worker-1.0.2.tgz",
			clean("fetching https://cdn.vendor-private.dev/v1.0.2/drupflare-worker-1.0.2.tgz"),
		)
		assertEquals("see https://github.com/a/b?<query>", clean("see https://github.com/a/b?x=1"))
		assertEquals(
			"<org>/drangler and <private-pkg>@1.0.0",
			clean("drupflare/drangler and @drupflare/burrow@1.0.0"),
		)
	}

	@Test
	fun objectDumpBecomesOneMarkerLine() {
		val raw = line("keep") + line("=> Error contextual data: {") + line("  config: {") +
			line("    secretish: 'x'") + line("  }") + line("}") + line("keep too")
		val result = LogSanitizer.sanitize(raw)
		assertEquals(
			"Docker E2E\tUNKNOWN STEP\t$stamp keep\n" +
				"Docker E2E\tUNKNOWN STEP\t$stamp <<removed: object dump, 5 lines>>\n" +
				"Docker E2E\tUNKNOWN STEP\t$stamp keep too\n",
			result.text,
		)
		assertEquals(5, result.counts["object-dump"])
	}

	@Test
	fun unterminatedObjectDumpIsDroppedToTheEnd() {
		val raw = line("=> Error contextual data: {") + line("  config: {")
		val result = LogSanitizer.sanitize(raw)
		assertEquals("<<removed: unterminated object dump, 2 lines>>\n", result.text)
	}

	@Test
	fun longLinesAreReplacedAndCounted() {
		val long = "x".repeat(LogSanitizer.MAX_BODY + 1)
		val edge = "y".repeat(LogSanitizer.MAX_BODY)
		val result = LogSanitizer.sanitize(line(long) + line(edge))
		assertTrue(result.text.contains("<<removed: line of 501 characters>>"))
		assertTrue(result.text.contains(edge))
		assertEquals(1, result.counts["long-line"])
	}

	@Test
	fun sanitizingTwiceChangesNothing() {
		val raw = seeds.values.joinToString("") { line("a $it b") }
		val once = LogSanitizer.sanitize(raw).text
		assertEquals(once, LogSanitizer.sanitize(once).text)
	}

	@Test
	fun countsAndExamplesNameTheKindWithoutTheRawValue() {
		val raw = line("one 10.1.2.3 two 10.1.2.4") + line("mail bob@corp-internal.io")
		val result = LogSanitizer.sanitize(raw)
		assertEquals(2, result.counts["ipv4"])
		assertEquals(1, result.counts["email"])
		assertEquals("one <ip> two <ip>", result.examples.getValue("ipv4"))
		val forbidden = listOf("corp-internal", "10.1.2")
		assertFalse(result.examples.values.any { e -> forbidden.any { e.contains(it) } })
	}

	@Test
	fun theHeadShaSurvivesOnlyWhenKept() {
		val sha = "558d2a621094d66464196822f8b7d17006930f13"
		assertEquals("fetch +<hex>:refs", clean("fetch +$sha:refs"))
		assertEquals("fetch +$sha:refs", clean("fetch +$sha:refs", setOf(sha)))
		assertEquals(emptyList(), LogSanitizer.residue("fetch +$sha:refs", setOf(sha)))
	}

	@Test
	fun escapedCredentialFileNamesAreMasked() {
		val name = "git\\-credentials\\-f8020b57\\-4b79\\-46b7\\-954a\\-c15d8b4ec"
		val out = clean("--unset \\/tmp\\/$name")
		assertFalse(out.contains("f8020b57"), out)
		assertEquals(emptyList(), LogSanitizer.residue(out))
	}

	@Test
	fun residueCheckFindsWhatItShould() {
		val planted = listOf(
			"host 10.20.30.40",
			"mail dev@corp-internal.io",
			"id " + "a".repeat(40),
			"token " + "gh" + "p_" + "Ab1".repeat(12),
			"name drupflare",
			"url https://api.corp-internal.dev/v1",
			"bare vault.corp-internal.io",
			"https://u:pw@host.example",
			"pad " + "aB3dE5gH7jK9mN1pQ3sT5vW7yZ9bD1fH3kM5",
		)
		for (text in planted) assertTrue(LogSanitizer.residue(text).isNotEmpty(), "missed: $text")
		val clean = "Image: ubuntu-24.04 https://github.com/a/b <ip>"
		assertEquals(emptyList(), LogSanitizer.residue(clean))
	}

	@Test
	fun valuesAreSanitizedLikeLines() {
		val result = LogSanitizer.sanitizeValue("Install for drupflare at 10.1.2.3")
		assertEquals("Install for <org> at <ip>", result.text)
		assertEquals(mapOf("ipv4" to 1, "organization" to 1), result.counts)
	}

	@Test
	fun diffKeepsChangedLinesOnly() {
		val raw = "diff --git a/package.json b/package.json\n" +
			"index d965930c..dabf22bc 100644\n" +
			"--- a/package.json\n" +
			"+++ b/package.json\n" +
			"@@ -1,6 +1,6 @@ function context with drupflare\n" +
			" {\n" +
			"-\t\"version\": \"0.2.0\",\n" +
			"+\t\"version\": \"0.3.0\",\n" +
			" \t\"author\": \"Gregory Mitchell <me@gmitch215.xyz>\",\n"
		val result = LogSanitizer.sanitizeDiff(raw)
		assertEquals(
			"diff --git a/package.json b/package.json\n" +
				"--- a/package.json\n" +
				"+++ b/package.json\n" +
				"@@ -1,6 +1,6 @@\n" +
				"-\t\"version\": \"0.2.0\",\n" +
				"+\t\"version\": \"0.3.0\",\n",
			result.text,
		)
		assertEquals(3, result.counts["diff-context"])
	}
}

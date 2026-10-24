package dev.gmitch215.drift.build

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyTest {
    private fun kinds(text: String) = Privacy.scan("f.txt", text).map { it.category }

    private fun values(text: String) = Privacy.scan("f.txt", text).map { it.value }

    @Test
    fun namedHomePathsAreFlaggedAndGenericOnesAreNot() {
        assertEquals(listOf("/Users/alice"), values("cd /Users/alice/src"))
        assertEquals(listOf("/home/bob"), values("ls /home/bob."))
        assertEquals(listOf("C:\\Users\\carol"), values("C:\\Users\\carol\\AppData"))
        assertEquals(listOf("C:/Users/dave"), values("C:/Users/dave/x"))
        assertEquals(listOf("-Users-erin-"), values("claude-502/-Users-erin-proj/x"))
        for (generic in listOf("runner", "user", "root", "vsts", "admin", "example", "RUNNER")) {
            assertEquals(emptyList(), kinds("/home/$generic/x /Users/$generic C:\\Users\\$generic"))
        }
        assertEquals(emptyList(), kinds("/Users/<name> /home/\$USER /home/{name} src/home/x"))
        assertEquals(emptyList(), kinds("C:\\Users\\<name>` `/home/you` C:\\Users\\%USER%"))
    }

    @Test
    fun emailsAreFlaggedUnlessGeneric() {
        assertEquals(listOf("alice@corp.io"), values("mail alice@corp.io now"))
        assertEquals(listOf(PrivacyCategory.EMAIL), kinds("1234+bob@users.noreply.github.com"))
        assertEquals(listOf("me@corp.io"), values("Author <me@corp.io>"))
        assertEquals(
            emptyList(),
            kinds("a@b.example x@example.com y@mail.example.org noreply@corp.io git@github.com"),
        )
        assertEquals(emptyList(), kinds("target@linux.txt version@-linux-x64.tar.gz"))
    }

    @Test
    fun tokenShapesAreFlagged() {
        val samples = listOf(
            "ghp_" + "a".repeat(36),
            "gho_" + "b".repeat(30),
            "github_pat_" + "c".repeat(30),
            "glpat-" + "d".repeat(20),
            "AKIA" + "E".repeat(16),
            "xoxb-" + "1".repeat(12),
            "-----BEGIN RSA PRIVATE KEY-----",
            "Authorization: Bearer " + "f".repeat(30),
            "password=hunter2hunter2x",
            "TOKEN=abcdef123456789",
        )
        for (s in samples) {
            assertEquals(listOf(PrivacyCategory.TOKEN), kinds(s), s)
        }
        assertEquals(emptyList(), kinds("ghp_short Bearer abc password=short token=\$SECRET_1"))
        assertEquals(emptyList(), kinds("password=onlyletterslongvalue"))
    }

    @Test
    fun privateAddressesAreFlaggedAndDocumentationRangesAreNot() {
        for (ip in listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.20", "100.64.0.9")) {
            assertEquals(listOf(ip), values("host $ip:22"), ip)
        }
        assertEquals(listOf("169.254.1.1"), values("169.254.1.1/16"))
        assertEquals(emptyList(), kinds("NO_PROXY=10.0.0.0/8,192.168.0.0/16"))
        assertEquals(listOf("10.0.0.5"), values("10.0.0.5/8"))
        assertEquals(listOf("10.0.0.0"), values("10.0.0.0 and 10.0.0.0/x"))
        assertEquals(
            emptyList(),
            kinds("192.0.2.1 198.51.100.2 203.0.113.7 127.0.0.1 8.8.8.8 172.32.0.1 10.1.2.300"),
        )
        assertEquals(emptyList(), kinds("v10.1.2.3 1.10.1.2.3 10.1.2.3.4"))
        assertEquals(listOf(PrivacyCategory.NETWORK), kinds("addr fe80::1ff:fe23:4567:890a x"))
        assertEquals(listOf(PrivacyCategory.NETWORK), kinds("fd7a:115c:a1e0::1"))
        assertEquals(emptyList(), kinds("2001:db8::1 ::1"))
        assertEquals(listOf("3c:22:fb:aa:bb:cc"), values("mac 3c:22:fb:aa:bb:cc."))
        assertEquals(emptyList(), kinds("00:00:00:00:00:00 ff:ff:ff:ff:ff:ff 1:2:3:4:5:6"))
    }

    @Test
    fun captureAttributesCarryNamesOnlyWhenNotGeneric() {
        val json = "{\"path\":\"env.GITHUB_ACTOR\",\"source\":\"env\",\"value\":\"octocat\"}"
        assertEquals(listOf("octocat"), values(json))
        assertEquals(emptyList(), kinds(json.replace("octocat", "runner")))
        assertEquals(emptyList(), kinds("{\"path\":\"env.USER\",\"value\":\"user\"}"))
        assertEquals(listOf("zed"), values("env.LOGNAME=zed"))
        assertEquals(listOf("box7"), values("hostname: box7"))
        assertEquals(listOf("zed"), values("user.name = \"zed\""))
        assertEquals(emptyList(), kinds("hostname = \$host user.name: <redacted>"))
        assertEquals(emptyList(), kinds("{\"path\":\"env.HOME\",\"value\":\"zed\"}"))
    }

    @Test
    fun lineNumbersAndDuplicatesAreCounted() {
        val hits = Privacy.scan("f.txt", "ok\n10.1.2.3 10.1.2.3\nok\n/home/bob")
        assertEquals(listOf(2, 4), hits.map { it.line })
        assertEquals(listOf("f.txt", "f.txt"), hits.map { it.path })
    }

    @Test
    fun maskingNeverShowsAWholeValue() {
        assertEquals("a...z (length 5)", Privacy.mask("a123z"))
        assertEquals("*** (length 2)", Privacy.mask("ab"))
        assertEquals("*** (length 0)", Privacy.mask(""))
        val secret = "ghp_" + "x".repeat(36)
        val report = Privacy.render(Privacy.scan("a/b.txt", secret + " /Users/alice 10.1.2.3"))
        assertFalse(secret in report)
        assertFalse("alice" in report)
        assertFalse("10.1.2.3" in report)
        assertTrue("a/b.txt:1 token g...x (length 40)" in report)
    }

    @Test
    fun reportIsSortedGroupedAndDeterministic() {
        val hits = listOf(
            PrivacyHit("z/b.txt", 2, PrivacyCategory.NETWORK, "10.1.2.3"),
            PrivacyHit("a/x.txt", 9, PrivacyCategory.EMAIL, "q@corp.io"),
            PrivacyHit("a/x.txt", 1, PrivacyCategory.TOKEN, "t".repeat(20)),
            PrivacyHit("top.txt", 1, PrivacyCategory.HOME_PATH, "/home/bob"),
        )
        val a = Privacy.render(hits)
        assertEquals(a, Privacy.render(hits.reversed()))
        assertTrue("hits: 4" in a)
        assertTrue("  email: 1" in a)
        assertTrue("  .: 1 hits in 1 files (home-path)" in a)
        assertTrue("  a: 2 hits in 1 files (email,token)" in a)
        assertTrue(a.indexOf("a/x.txt:1") < a.indexOf("a/x.txt:9"))
        assertTrue(a.indexOf("a/x.txt:9") < a.indexOf("z/b.txt:2"))
    }

    @Test
    fun allowlistHonoursPathCategoryAndValue() {
        val allow = Privacy.parseAllow(
            """
            - path: fixtures/**
              category: network
              reason: recorded lab addresses
            - value: /home/bob
              category: home-path
              reason: placeholder
            - path: docs/*.md
              value: q@corp.io
              category: email
              reason: maintainer
            """.trimIndent(),
            "allow.yml",
        )
        val hits = listOf(
            PrivacyHit("fixtures/a/b.txt", 1, PrivacyCategory.NETWORK, "10.1.2.3"),
            PrivacyHit("fixtures/a/b.txt", 2, PrivacyCategory.EMAIL, "q@corp.io"),
            PrivacyHit("x.txt", 1, PrivacyCategory.HOME_PATH, "/home/bob"),
            PrivacyHit("x.txt", 2, PrivacyCategory.HOME_PATH, "/home/eve"),
            PrivacyHit("docs/a.md", 1, PrivacyCategory.EMAIL, "q@corp.io"),
            PrivacyHit("docs/sub/a.md", 1, PrivacyCategory.EMAIL, "q@corp.io"),
        )
        val result = Privacy.apply(hits, allow)
        assertEquals(
            listOf("docs/sub/a.md:1", "fixtures/a/b.txt:2", "x.txt:2"),
            result.remaining.map { "${it.path}:${it.line}" },
        )
        assertEquals(emptyList(), result.stale)
    }

    @Test
    fun staleAllowlistEntriesAreReported() {
        val allow = Privacy.parseAllow(
            "- path: gone/**\n  category: email\n  reason: removed long ago\n",
            "allow.yml",
        )
        val result = Privacy.apply(
            listOf(PrivacyHit("x.txt", 1, PrivacyCategory.EMAIL, "q@corp.io")),
            allow,
        )
        assertEquals(allow, result.stale)
        assertEquals(1, result.remaining.size)
    }

    @Test
    fun malformedAllowlistsAreRejected() {
        fun fail(yaml: String) =
            assertFailsWith<RuleYamlException> { Privacy.parseAllow(yaml, "a.yml") }
        fail("category: email")
        fail("- x")
        fail("- path: a\n  category: nope\n  reason: r")
        fail("- path: a\n  category: email")
        fail("- category: email\n  reason: r")
        fail("- path: a\n  category: email\n  reason: r\n  extra: 1")
        assertEquals(emptyList(), Privacy.parseAllow("", "a.yml"))
    }

    @Test
    fun globsKeepSingleStarWithinASegment() {
        assertTrue(Privacy.glob("a/*.txt").matches("a/b.txt"))
        assertFalse(Privacy.glob("a/*.txt").matches("a/b/c.txt"))
        assertTrue(Privacy.glob("a/**").matches("a/b/c.txt"))
        assertTrue(Privacy.glob("a/?.txt").matches("a/b.txt"))
        assertFalse(Privacy.glob("a.txt").matches("axtxt"))
    }

    @Test
    fun binaryFilesAreIgnored() {
        assertTrue(Privacy.isBinary(byteArrayOf(1, 0, 2)))
        assertFalse(Privacy.isBinary("text".toByteArray()))
    }

    @Test
    fun fileListWalksATreeWithoutGit() {
        val root = Files.createTempDirectory("privacy").toFile()
        try {
            root.resolve("b").mkdirs()
            root.resolve("build").mkdirs()
            root.resolve("build.gradle.kts").writeText("x")
            root.resolve("b/build").mkdirs()
            root.resolve("b/build/pkg.txt").writeText("x")
            root.resolve("node_modules").mkdirs()
            root.resolve("node_modules/n.txt").writeText("x")
            root.resolve("b/z.txt").writeText("x")
            root.resolve("a.txt").writeText("x")
            root.resolve("build/out.txt").writeText("x")
            assertEquals(
                listOf("a.txt", "b/build/pkg.txt", "b/z.txt", "build.gradle.kts"),
                Privacy.files(root),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

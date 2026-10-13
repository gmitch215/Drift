package dev.gmitch215.drift

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.redact.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapsuleTest {
    private val capsule = Capsule(
        label = "fixture",
        attributes = listOf(
            Attribute("os.arch", "x86_64", "uname"),
            Attribute("env.TZ", "UTC", "env"),
            Attribute("proc.pid", "42", "host", Stability.VOLATILE),
        ),
        probes = listOf(ProbeResult("text.sort", ProbeStatus.OK, "a\nb\n")),
    )

    @Test
    fun roundTrip() {
        assertEquals(
            capsule.copy(
                attributes = capsule.attributes.sortedBy {
                    it.path
                },
            ),
            Capsule.parse(capsule.canonical()),
        )
    }

    @Test
    fun canonicalFormIgnoresOrder() {
        val shuffled = capsule.copy(attributes = capsule.attributes.reversed())
        assertEquals(capsule.canonical(), shuffled.canonical())
        assertEquals(capsule.hash(), shuffled.hash())
    }

    @Test
    fun selfDiffIsEmpty() {
        assertEquals(emptyList(), CapsuleDiff.attributes(capsule, capsule))
    }

    @Test
    fun diffIgnoresVolatileAndReportsChanges() {
        val other = Capsule(
            "other",
            listOf(
                Attribute("os.arch", "aarch64", "uname"),
                Attribute("proc.pid", "7", "host", Stability.VOLATILE),
            ),
        )
        val changes = CapsuleDiff.attributes(capsule, other)
        assertEquals(listOf("env.TZ", "os.arch"), changes.map { it.path })
        assertEquals(null, changes.first().after)
    }

    @Test
    fun rejectsUnknownSchema() {
        val text = capsule.canonical().replace("\"schema\":1", "\"schema\":9")
        assertFailsWith<JsonException> { Capsule.parse(text) }
    }

    @Test
    fun redactsPlantedSecrets() {
        val planted = listOf(
            Attribute("env.GITHUB_TOKEN", "anything", "env"),
            Attribute("env.NOTE", "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "env"),
            Attribute("cmd.remote", "https://user:hunter2@example.com/repo", "git"),
            Attribute("env.AWS", "AKIAABCDEFGHIJKLMNOP", "env"),
            Attribute("env.CI_JOB_JWT_V2", "anything", "env"),
        ).map(Redactor::redact)
        assertTrue(planted.all { Redactor.MASK in it.value }, planted.toString())
        assertEquals("host example.com", Redactor.redactText("host example.com"))
        assertEquals("UTC", Redactor.redact(Attribute("env.TZ", "UTC", "env")).value)
    }

    @Test
    fun redactsKnownTokenFormats() {
        val tokens = listOf(
            "glpat-abcdefghijklmnopqrstuvwxyz",
            "glcbt-abcdefghijklmnopqrstuvwxyz",
            "gldt-abcdefghijklmnopqrstuvwxyz",
            "glrt-abcdefghijklmnopqrstuvwxyz",
            "glptt-abcdefghijklmnopqrstuvwxyz",
            "glrtr-abcdefghijklmnopqrstuvwxyz",
            "gloas-abcdefghijklmnopqrstuvwxyz",
            "glagent-abcdefghijklmnopqrstuvwxyz",
            "glffct-abcdefghijklmnopqrstuvwxyz",
            "npm_abcdefghijklmnopqrstuvwxyz0123456789",
            "pypi-AgEIcHlwaS5vcmcAJGYxxxxxxxxxxxxxxxxxxxx",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl",
            "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789",
            "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789",
        )
        assertEquals(tokens.map { Redactor.MASK }, tokens.map(Redactor::redactText))
        assertEquals("glue-job", Redactor.redactText("glue-job"))
    }

    @Test
    fun masksIpAddressesInEnvValues() {
        val values = listOf(
            Attribute("env.SSH_CLIENT", "203.0.113.7 51234 22", "env"),
            Attribute("env.SSH_CONNECTION", "203.0.113.7 51234 198.51.100.2 22", "env"),
            Attribute("env.REMOTE", "2001:db8::1 51234 22", "env"),
            Attribute("env.LOOPBACK", "::1", "env"),
            Attribute("env.CXXFLAGS", "std::string -O2", "env"),
            Attribute("env.TZ", "UTC", "env"),
        ).map { Redactor.redact(it).value }
        assertEquals(
            listOf(
                "${Redactor.MASK} 51234 22",
                "${Redactor.MASK} 51234 ${Redactor.MASK} 22",
                "${Redactor.MASK} 51234 22",
                Redactor.MASK,
                "std::string -O2",
                "UTC",
            ),
            values,
        )
    }

    @Test
    fun keepsVersionStringsAndMasksHostIpsInEnvValues() {
        val values = listOf(
            Attribute("env.ImageVersion", "20260920.314.1.0", "env"),
            Attribute("env.JAVA_VERSION", "21.0.4.7", "env"),
            Attribute("env.OS_BUILD", "10.0.26100.1", "env"),
            Attribute("env.TOOL", "v21.0.4.7", "env"),
            Attribute("env.HOST_IP", "10.1.2.3", "env"),
            Attribute("env.SERVER_IP", "10.1.2.3", "env"),
        ).map { Redactor.redact(it).value }
        assertEquals(
            listOf(
                "20260920.314.1.0",
                "21.0.4.7",
                "10.0.26100.1",
                "v21.0.4.7",
                Redactor.MASK,
                Redactor.MASK,
            ),
            values,
        )
    }

    @Test
    fun masksSessionIdentifiersInEnvValues() {
        val values = listOf(
            "env.XDG_SESSION_ID" to "37423",
            "env.TMUX" to "/tmp/tmux-501/default,12345,0",
            "env.WINDOWID" to "10485766",
            "env.INVOCATION_ID" to "4b2c1d9e0f1a2b3c4d5e6f7a8b9c0d1e",
            "env.JOURNAL_STREAM" to "8:41231",
            "env.XAUTHORITY" to "/tmp/xauth-AbC123",
            "env.SSH_AUTH_SOCK" to "/tmp/ssh-XYZ/agent.123",
        ).map { (path, value) -> Redactor.redact(Attribute(path, value, "env")) }
        assertEquals(values.map { Redactor.MASK }, values.map { it.value }, values.toString())
        assertEquals(setOf("env"), values.map { it.source }.toSet())
    }

    @Test
    fun anonymizesRuntimeDirUidAndDbusGuid() {
        val runtime = Redactor.redact(Attribute("env.XDG_RUNTIME_DIR", "/run/user/1000", "env"))
        assertEquals("/run/user/user", runtime.value)
        assertEquals("env (anonymized)", runtime.source)

        val bus = Redactor.redact(
            Attribute(
                "env.DBUS_SESSION_BUS_ADDRESS",
                "unix:path=/run/user/1000/bus,guid=0123456789abcdef0123456789abcdef",
                "env",
            ),
        )
        assertEquals("unix:path=/run/user/user/bus,guid=${Redactor.MASK}", bus.value)
        assertEquals("env (anonymized)", bus.source)

        assertEquals("/run/user/user", Redactor.redactText("/run/user/1000"))
    }
}

package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.json.CanonicalJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CiDiagnosisTest {
    private val sample = ciSample()
    private val token = "ghp_0123456789abcdefghijklmnopqrstuvwxyz"

    private val gitlab = "Running with gitlab-runner 17.5.1 (4a5b6c7d)\n" +
        "Preparing the \"docker\" executor\n" +
        "Using Docker executor with image ruby:3.3 ...\n"

    private fun github(version: String, env: List<String> = emptyList()) = (
        listOf(
            "##[group]Operating System",
            "Ubuntu",
            "24.04.5",
            "LTS",
            "##[endgroup]",
            "##[group]Runner Image",
            "Image: ubuntu-24.04",
            "Version: $version",
            "##[endgroup]",
            "##[group]Run echo hi",
            "echo hi",
            "env:",
        ) + env + "##[endgroup]"
        ).joinToString("\n")

    private fun done(result: CiResult) = assertIs<CiResult.Done>(result)

    private fun bad(result: CiResult) = assertIs<CiResult.Bad>(result)

    @Test
    fun detectsTheProviderFromTheLogText() {
        assertEquals(CiFormat.GITHUB, CiDiagnosis.detect(sample.passing))
        assertEquals(CiFormat.GITHUB, CiDiagnosis.detect("Current runner version: '2.337.0'"))
        assertEquals(CiFormat.GITLAB, CiDiagnosis.detect(gitlab))
        assertNull(CiDiagnosis.detect("just some text"))
        assertNull(CiDiagnosis.detect(sample.passing + gitlab))
    }

    @Test
    fun theSampleLogsAreTiedAndSayTheEnvironmentCannotSeparateACause() {
        val result = done(CiDiagnosis.run(sample.passing, sample.failing, null, sample.job))
        assertEquals(
            setOf(
                "ci.provisioner.build-date",
                "ci.provisioner.version",
                "ci.runner.image.version",
            ),
            result.state.competing.map { it.path }.toSet(),
        )
        assertTrue(result.plan.contains("passing"), result.plan)
        assertFalse(result.separated)
    }

    @Test
    fun aSingleDifferenceIsNamedWithoutTheLimit() {
        assertTrue(done(CiDiagnosis.run(github("1.0"), github("2.0"), null, null)).separated)
    }

    @Test
    fun aPastedPairIsRankedAndNamesTheDifference() {
        val result = done(CiDiagnosis.run(github("1.0"), github("2.0"), null, null))
        assertEquals(listOf("ci.runner.image.version"), result.state.relevant.map { it.path })
    }

    @Test
    fun aGitlabPairIsRankedToo() {
        val failing = gitlab.replace("ruby:3.3", "ruby:3.4")
        val result = done(CiDiagnosis.run(gitlab, failing, null, null))
        assertEquals(listOf("ci.runner.image"), result.state.relevant.map { it.path })
    }

    @Test
    fun anEmptyLogIsRefusedByName() {
        assertEquals(
            "The passing log is empty. Paste it or choose a file.",
            bad(CiDiagnosis.run(" \n", sample.failing, null, null)).message,
        )
        assertEquals(
            "The failing log is empty. Paste it or choose a file.",
            bad(CiDiagnosis.run(sample.passing, "", null, null)).message,
        )
    }

    @Test
    fun aLogOfAnUnknownProviderAsksWhichOneItIs() {
        val either = bad(CiDiagnosis.run("a", "b", null, null))
        assertEquals("Drift cannot tell which CI system made either log.", either.message)
        assertTrue(either.askFormat)
        val one = bad(CiDiagnosis.run(sample.passing, "b", null, null))
        assertEquals("Drift cannot tell which CI system made the failing log.", one.message)
        assertTrue(one.askFormat)
    }

    @Test
    fun aChosenProviderReplacesDetection() {
        val result = bad(CiDiagnosis.run("a", "b", CiFormat.GITHUB, null))
        assertFalse(result.askFormat)
        assertEquals(
            "The passing log has no runner environment section, so Drift has nothing to " +
                "compare. Use the full job log, starting at the Set up job step.",
            result.message,
        )
    }

    @Test
    fun aMissingEnvironmentSectionIsExplainedPerProvider() {
        val named = bad(CiDiagnosis.run(sample.passing, sample.failing, null, "Nope")).message
        assertEquals(
            "The passing log has no environment section for the job \"Nope\", so Drift has " +
                "nothing to compare. Check the job name, or leave it empty.",
            named,
        )
        val trace = bad(CiDiagnosis.run(gitlab, "x", CiFormat.GITLAB, null)).message
        assertEquals(
            "The failing log has no GitLab runner section, so Drift has nothing to compare. " +
                "Use the full job trace, from the line that starts with Running with " +
                "gitlab-runner.",
            trace,
        )
    }

    @Test
    fun logsFromTwoProvidersAreRefused() {
        assertEquals(
            "The two logs come from different CI systems (GitHub Actions and GitLab CI). " +
                "Use two logs from the same system.",
            bad(CiDiagnosis.run(sample.passing, gitlab, null, null)).message,
        )
    }

    @Test
    fun aTokenInAPastedLogReachesNeitherTheStateNorThePlan() {
        val env = listOf("  API_TOKEN: $token", "  NOTE: see $token")
        val result = done(CiDiagnosis.run(github("1.0", env), github("2.0", env), null, null))
        assertFalse(CanonicalJson.encode(result.state.toJson()).contains(token))
        assertFalse(result.plan.contains(token))
    }

    @Test
    fun aChosenFileBecomesTextOrAReasonItCannotBeRead() {
        val ok = CiDiagnosis.read(PickedFile("a.log", "hello".encodeToByteArray()))
        assertEquals("a.log", assertIs<Load.Ok<String>>(ok).name)
        assertEquals("hello", ok.value)
        fun reason(file: PickedFile) = assertIs<Load.Bad>(CiDiagnosis.read(file)).message
        assertEquals("cannot read a.log: no data.", reason(PickedFile("a.log", null)))
        assertEquals("cannot read a.log: denied.", reason(PickedFile("a.log", null, "denied")))
        assertEquals(
            "cannot read a.log: it is not a text file.",
            reason(PickedFile("a.log", byteArrayOf(-1, -2, -3))),
        )
        assertEquals(
            "cannot read a.log: the file is empty.",
            reason(PickedFile("a.log", "  \n".encodeToByteArray())),
        )
        assertEquals(
            "cannot read a.log: it is larger than 16 MB.",
            reason(PickedFile("a.log", ByteArray(CiDiagnosis.MAX_LOG + 1))),
        )
    }
}

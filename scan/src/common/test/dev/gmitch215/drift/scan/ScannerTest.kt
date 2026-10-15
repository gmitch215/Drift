package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.scan.scanner.Kit
import dev.gmitch215.drift.scan.scanner.KitEntry
import dev.gmitch215.drift.scan.scanner.Scanner
import dev.gmitch215.drift.scan.scanner.Scanners
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerTest {
    private fun scanner(tool: String) = Scanners.all.single { it.tool == tool }

    private fun parsed(tool: String, label: String): Map<String, String> {
        val text = Fixtures.text("$tool/$label.txt")
        return Scanners.attributes(scanner(tool), text).associate {
            it.path.removePrefix("tool.$tool.") to it.value
        }
    }

    private fun labels(tool: String) = Fixtures.names
        .filter { it.startsWith("$tool/") }
        .map { it.removePrefix("$tool/").removeSuffix(".txt") }

    @Test
    fun everyToolHasCapturedTranscripts() {
        for (s in Scanners.all) {
            assertTrue(labels(s.tool).isNotEmpty(), "no transcripts for ${s.tool}")
        }
    }

    @Test
    fun versionsMatchTheImageTheyWereCapturedFrom() {
        val prefixes = mapOf("node" to "", "python" to "", "php" to "")
        for ((tool, _) in prefixes) {
            for (label in labels(tool)) {
                val wanted = label.substringAfter('-')
                val version = parsed(tool, label).getValue("version")
                assertTrue(version.startsWith("$wanted."), "$tool $label parsed as $version")
            }
        }
        for (label in labels("go")) {
            val version = parsed("go", label).getValue("version")
            assertTrue(version.startsWith(label.substringAfter('-')), label)
        }
        assertEquals("linux", parsed("go", labels("go").first()).getValue("os"))
        for (label in labels("java")) {
            val major = label.substringAfter('-')
            val expected = if (major == "8") "1.8." else "$major."
            val result = parsed("java", label)
            assertTrue(result.getValue("version").startsWith(expected), "java $label $result")
            assertEquals("openjdk", result.getValue("implementation"), label)
        }
        for (label in labels("rust")) {
            assertTrue(parsed("rust", label).getValue("version").startsWith("1.80."), label)
        }
    }

    @Test
    fun compilerAndGitTranscriptsParseAcrossDistros() {
        for (label in labels("cc")) {
            val result = parsed("cc", label)
            assertEquals(if (label.contains("clang")) "clang" else "gcc", result["family"], label)
            val version = result.getValue("version")
            assertTrue(Regex("\\d+\\.\\d+\\.\\d+").matches(version), "$label $result")
        }
        for (label in labels("git")) {
            assertTrue(
                Regex("\\d+\\.\\d+(\\.\\d+)?").matches(parsed("git", label).getValue("version")),
                label,
            )
        }
    }

    @Test
    fun clangVendorSeparatesAppleFromLlvm() {
        assertEquals("apple", parsed("cc", "apple-clang-21")["vendor"])
        assertEquals(null, parsed("cc", "alpine-3.20")["vendor"])
        val llvm = Scanners.attributes(
            scanner("cc"),
            "Debian clang version 16.0.6 (19)\nTarget: x86_64-pc-linux-gnu\n",
        ).associate { it.path to it.value }
        assertEquals("llvm", llvm["tool.cc.vendor"])
        assertEquals("16.0.6", llvm["tool.cc.version"])
        val upstream = Scanners.attributes(scanner("cc"), "clang version 18.1.8\n")
        assertEquals("llvm", upstream.single { it.path == "tool.cc.vendor" }.value)
    }

    @Test
    fun npmReportsItsVersion() {
        assertEquals("11.19.0", parsed("npm", "node-24")["version"])
        assertEquals("10.8.2", Scanners.attributes(scanner("npm"), "10.8.2\n").single().value)
        val rc = Scanners.attributes(scanner("npm"), "9.0.0-rc.1").single()
        assertEquals("tool.npm.version" to "9.0.0-rc.1", rc.path to rc.value)
    }

    @Test
    fun pythonReportsWhetherTheGilIsDisabled() {
        fun gil(text: String) = Scanners.attributes(scanner("python-gil"), text)
            .associate { it.path to it.value }

        assertEquals(mapOf("tool.python.gil-disabled" to "false"), gil("0\n"))
        assertEquals(mapOf("tool.python.gil-disabled" to "true"), gil("1\n"))
        assertEquals(emptyMap(), gil("None\n"))
        assertEquals(emptyMap(), gil("python3: not found"))
        assertEquals(
            "false",
            gil(Fixtures.text("python-gil/python-3.13.txt")).getValue("tool.python.gil-disabled"),
        )
        assertEquals(
            "false",
            gil(Fixtures.text("python-gil/python-3.12.txt")).getValue("tool.python.gil-disabled"),
        )
    }

    @Test
    fun phpReportsThreadSafetyAndSapi() {
        for (label in labels("php")) {
            val result = parsed("php", label)
            assertEquals("cli", result["sapi"], label)
            assertTrue(result["thread-safety"] in setOf("NTS", "ZTS"), "$label $result")
        }
    }

    @Test
    fun coreutilsFlavorsAreDistinguished() {
        assertEquals("gnu", parsed("coreutils", "debian-bookworm")["flavor"])
        assertEquals("busybox", parsed("coreutils", "alpine-3.20")["flavor"])
    }

    @Test
    fun unknownFormatsDegradeToUnparsedWithRawLine() {
        val attrs = Scanners.attributes(scanner("node"), "node: something new 99\nsecond line\n")
        assertEquals(
            listOf(
                Attribute("tool.node.version", "unparsed", "scanner:node --version"),
                Attribute("tool.node.raw", "node: something new 99", "scanner:node --version"),
            ),
            attrs,
        )
    }

    @Test
    fun fuzzedAndTruncatedInputNeverThrows() {
        val random = Random(1234)
        val alphabet = "vV0123456789. \n\"()/gjP-:=ox"
        repeat(300) {
            val chars = CharArray(random.nextInt(0, 80)) { alphabet.random(random) }
            val text = chars.concatToString()
            for (s in Scanners.all) Scanners.attributes(s, text)
        }
        for (name in Fixtures.names) {
            val tool = name.substringBefore('/')
            val full = Fixtures.text(name)
            for (n in 0..full.length step 3) Scanners.attributes(scanner(tool), full.take(n))
        }
    }

    @Test
    fun absentToolsProduceNoAttributes() {
        val missing =
            FakeHost(
                commands = mapOf(
                    listOf("node", "--version") to CommandResult(127, "sh: node: not found\n"),
                ),
            )
        assertEquals(emptyList(), Scanners.scan(missing))
        assertEquals(emptyList(), Scanners.scan(FakeHost(canRun = false)))
    }

    @Test
    fun scanReadsCommandOutputFromTheHost() {
        val host =
            FakeHost(commands = mapOf(listOf("node", "--version") to CommandResult(0, "v22.4.1\n")))
        assertEquals(
            listOf(Attribute("tool.node.version", "22.4.1", "scanner:node --version")),
            Scanners.scan(host),
        )
    }

    @Test
    fun kitScriptsListEveryScannerCommand() {
        val sh = Kit.sh()
        val ps1 = Kit.ps1()
        for (s in Scanners.all) {
            assertTrue("run ${s.tool} " in sh, s.tool)
            assertTrue("Run-Tool '${s.tool}' '${s.command.first()}'" in ps1, s.tool)
        }
    }

    @Test
    fun kitOutputParsesBackIntoEntries() {
        val text = "@@ node exit=0\nv22.4.1\n@@ end\n" +
            "@@ java exit=127\nsh: java: not found\n@@ end\n"
        val entries = Kit.parse(text)
        assertEquals(0, entries.getValue("node").exitCode)
        assertEquals("v22.4.1", entries.getValue("node").output)
        assertEquals(127, entries.getValue("java").exitCode)
        assertEquals(
            listOf(Attribute("tool.node.version", "22.4.1", "scanner:node --version")),
            Scanners.fromTranscripts(entries),
        )
    }

    @Test
    fun kitRunOnTheRealHostParsesIdenticallyToNativeScan() {
        val host = systemHost()
        if (host.os.startsWith("windows") || host.run(listOf("sh", "-c", "exit 0")) == null) return
        val script = host.run(listOf("sh", "-c", Kit.sh()))
        assertTrue(script != null)
        val viaKit = Scanners.fromTranscripts(Kit.parse(script.output))
        val native = Scanners.scan(host)
        assertEquals(native.sortedBy { it.path }, viaKit.sortedBy { it.path })
        assertTrue(native.any { it.path == "tool.coreutils.flavor" })
    }

    @Test
    fun capsuleIncludesToolAttributesOnRequest() {
        val host =
            FakeHost(
                commands = mapOf(
                    listOf("git", "--version") to CommandResult(0, "git version 2.45.2\n"),
                ),
            )
        val capsule = Capture.run(host, "t", tools = true)
        assertEquals("2.45.2", capsule.attributes.single { it.path == "tool.git.version" }.value)
        assertTrue(Capture.run(host, "t").attributes.none { it.path.startsWith("tool.") })
        assertEquals(KitEntry(0, "x").output, "x")
        assertEquals(Scanner::class, scanner("git")::class)
    }

    @Test
    fun libcTranscriptsNameTheFamilyAndVersion() {
        val expected = mapOf(
            "musl-alpine-3.18" to ("musl" to "1.2.4"),
            "musl-alpine-3.20" to ("musl" to "1.2.5"),
            "musl-alpine-3.22" to ("musl" to "1.2.5"),
            "glibc-debian-bookworm" to ("glibc" to "2.36"),
            "glibc-ubuntu-24.04" to ("glibc" to "2.39"),
        )
        assertEquals(expected.keys, labels("libc").toSet())
        for ((label, want) in expected) {
            val result = parsed("libc", label)
            assertEquals(want, result.getValue("family") to result.getValue("version"), label)
        }
    }

    @Test
    fun libcWithoutAVersionLineKeepsTheFamily() {
        val text = "musl libc (x86_64)\nDynamic Program Loader\n"
        val attrs = Scanners.attributes(scanner("libc"), text)
        assertEquals(listOf("tool.libc.family"), attrs.map { it.path })
        assertEquals("musl", attrs.single().value)
    }

    @Test
    fun unknownLibcOutputDegradesToUnparsed() {
        val attrs = Scanners.attributes(scanner("libc"), "ldd: unrecognized\n")
        assertEquals(
            listOf("tool.libc.version" to "unparsed", "tool.libc.raw" to "ldd: unrecognized"),
            attrs.map { it.path to it.value },
        )
    }

    @Test
    fun pythonMarkerTranscriptsDistinguishManagedInterpreters() {
        val managed = mapOf(
            "alpine-3.18" to "false",
            "alpine-3.20" to "true",
            "alpine-3.22" to "true",
            "python-3.12-alpine" to "false",
        )
        assertEquals(managed.keys, labels("python-managed").toSet())
        for ((label, want) in managed) {
            val attrs = Scanners.attributes(
                scanner("python-managed"),
                Fixtures.text("python-managed/$label.txt"),
            )
            assertEquals(listOf("tool.python.externally-managed"), attrs.map { it.path }, label)
            assertEquals(want, attrs.single().value, label)
        }
    }

    @Test
    fun pythonMarkerNeverClobbersThePythonVersion() {
        val entries = mapOf(
            "python" to KitEntry(0, "Python 3.12.3\n"),
            "python-managed" to KitEntry(0, "True\n"),
        )
        val byPath = Scanners.fromTranscripts(entries).associate { it.path to it.value }
        assertEquals(
            mapOf(
                "tool.python.version" to "3.12.3",
                "tool.python.externally-managed" to "true",
            ),
            byPath,
        )
        val broken = Scanners.fromTranscripts(mapOf("python-managed" to KitEntry(1, "Traceback\n")))
        assertEquals(emptyList(), broken)
    }

    private fun emitted(tool: String, label: String) =
        Scanners.attributes(scanner(tool), Fixtures.text("$tool/$label.txt"))
            .associate { it.path to it.value }

    private fun assertTranscripts(tool: String, expected: Map<String, Map<String, String>>) {
        assertEquals(expected.keys, labels(tool).toSet(), tool)
        for ((label, want) in expected) assertEquals(want, emitted(tool, label), "$tool $label")
    }

    @Test
    fun opensslTranscriptsNameTheFamilyAndTheLetteredVersion() {
        fun openssl(version: String) =
            mapOf("tool.openssl.family" to "openssl", "tool.openssl.version" to version)
        assertTranscripts(
            "openssl",
            mapOf(
                "centos-7" to openssl("1.0.2k-fips"),
                "ubuntu-20.04" to openssl("1.1.1f"),
                "alpine-3.16" to openssl("1.1.1w"),
                "ubuntu-22.04" to openssl("3.0.2"),
                "debian-bookworm" to openssl("3.0.22"),
                "alpine-3.20" to openssl("3.3.7"),
                "alpine-3.22" to openssl("3.5.9"),
                "homebrew-macos-26" to openssl("3.6.4"),
                "libressl-macos-26" to mapOf(
                    "tool.openssl.family" to "libressl",
                    "tool.openssl.libressl-version" to "3.3.6",
                ),
            ),
        )
    }

    @Test
    fun opensslProvidersReportWhetherTheLegacyProviderIsLoaded() {
        val legacy = "runtime.openssl.legacy-provider"
        assertTranscripts(
            "openssl-providers",
            mapOf(
                "alpine-3.20" to mapOf(legacy to "false"),
                "debian-bookworm" to mapOf(legacy to "false"),
                "homebrew-macos-26" to mapOf(legacy to "false"),
                "ubuntu-20.04" to emptyMap(),
                "libressl-macos-26" to emptyMap(),
            ),
        )
        val loaded = "Providers:\n  default\n    name: OpenSSL Default Provider\n" +
            "    status: active\n  legacy\n    name: OpenSSL Legacy Provider\n"
        val attrs = Scanners.attributes(scanner("openssl-providers"), loaded)
        assertEquals(listOf(legacy to "true"), attrs.map { it.path to it.value })
        assertEquals(emptyList(), Scanners.attributes(scanner("openssl-providers"), "oops"))
    }

    @Test
    fun sshVersionDropsThePortableSuffixIntoRelease() {
        fun ssh(version: String, release: String? = "p1") = listOfNotNull(
            "tool.ssh.version" to version,
            release?.let { "tool.ssh.release" to it },
        ).toMap()
        assertTranscripts(
            "ssh",
            mapOf(
                "ubuntu-20.04" to ssh("8.2"),
                "ubuntu-22.04" to ssh("8.9"),
                "debian-bookworm" to ssh("9.2"),
                "alpine-3.20" to ssh("9.7"),
                "macos-26" to ssh("10.2", "p1"),
            ),
        )
        val windows =
            Scanners.attributes(scanner("ssh"), "OpenSSH_for_Windows_9.5p2, LibreSSL 3.8.2")
        assertEquals(
            mapOf("tool.ssh.version" to "9.5", "tool.ssh.release" to "p2"),
            windows.associate { it.path to it.value },
        )
        val bare = Scanners.attributes(scanner("ssh"), "OpenSSH_9.0")
        assertEquals(mapOf("tool.ssh.version" to "9.0"), bare.associate { it.path to it.value })
    }

    @Test
    fun phpModulesListTheLoadedExtensionsInTheModulesSectionOnly() {
        val got = emitted("php-modules", "homebrew-macos").getValue("tool.php.extensions")
        val names = got.split(',')
        assertTrue("json" in names && "pcre" in names, got)
        assertTrue(names.none { it.startsWith("[") || it.isEmpty() }, got)
        assertEquals(names.sorted(), names)
        val text = "[PHP Modules]\nMcrypt\nCore\n\n[Zend Modules]\nZend OPcache\n"
        assertEquals(
            "core,mcrypt",
            Scanners.attributes(scanner("php-modules"), text).single().value,
        )
        assertEquals(emptyList(), Scanners.attributes(scanner("php-modules"), "usage: php\n"))
    }

    @Test
    fun gitAutocrlfKeepsOnlyTheThreeDocumentedValues() {
        assertTranscripts(
            "git-autocrlf",
            mapOf(
                "true" to mapOf("git.config.core.autocrlf" to "true"),
                "false" to mapOf("git.config.core.autocrlf" to "false"),
                "input" to mapOf("git.config.core.autocrlf" to "input"),
            ),
        )
        assertEquals(emptyList(), Scanners.attributes(scanner("git-autocrlf"), ""))
        assertEquals(emptyList(), Scanners.attributes(scanner("git-autocrlf"), "weird\n"))
    }

    @Test
    fun composerVersionSurvivesDeprecationNoise() {
        assertTranscripts(
            "composer",
            mapOf(
                "composer-1" to mapOf("tool.composer.version" to "1.10.28"),
                "composer-2" to mapOf("tool.composer.version" to "2.10.3"),
                "homebrew-macos" to mapOf("tool.composer.version" to "2.9.5"),
            ),
        )
    }

    @Test
    fun sudoFlavorSeparatesSudoWsFromSudoRs() {
        fun ws(version: String, release: String) = mapOf(
            "tool.sudo.flavor" to "ws",
            "tool.sudo.version" to version,
            "tool.sudo.release" to release,
        )
        assertTranscripts(
            "sudo",
            mapOf(
                "debian-bookworm" to ws("1.9.13", "p3"),
                "ubuntu-24.04" to ws("1.9.15", "p5"),
                "macos-26" to ws("1.9.17", "p2"),
                "ubuntu-26.04" to mapOf(
                    "tool.sudo.flavor" to "rs",
                    "tool.sudo.version" to "0.2.13-0ubuntu1.2",
                ),
            ),
        )
    }

    @Test
    fun rubyReportsItsVersionAndPatchLevel() {
        assertTranscripts(
            "ruby",
            mapOf(
                "ruby-2.7" to mapOf("tool.ruby.version" to "2.7.8", "tool.ruby.release" to "p225"),
                "ruby-3.3" to mapOf("tool.ruby.version" to "3.3.12"),
                "macos-26" to mapOf("tool.ruby.version" to "3.4.1"),
            ),
        )
        val dev = Scanners.attributes(scanner("ruby"), "ruby 3.5.0dev (2026-01-01) [x86_64-linux]")
        assertEquals("3.5.0dev", dev.single().value)
    }

    @Test
    fun namedReportsTheBindVersionToken() {
        assertTranscripts(
            "named",
            mapOf(
                "alpine-3.20" to mapOf("tool.named.version" to "9.18.49"),
                "debian-bookworm" to mapOf("tool.named.version" to "9.18.49-1~deb12u2-Debian"),
                "ubuntu-22.04" to mapOf("tool.named.version" to "9.18.39-0ubuntu0.22.04.6-Ubuntu"),
            ),
        )
    }

    @Test
    fun iptablesBackendIsOnlyEmittedWhenTheVersionLineNamesIt() {
        fun iptables(version: String, backend: String) =
            mapOf("tool.iptables.version" to version, "tool.iptables.backend" to backend)
        assertTranscripts(
            "iptables",
            mapOf(
                "alpine-3.18" to iptables("1.8.9", "legacy"),
                "alpine-3.20" to iptables("1.8.10", "nft"),
                "debian-bookworm" to iptables("1.8.9", "nft"),
                "ubuntu-22.04" to iptables("1.8.7", "nft"),
            ),
        )
        val old = Scanners.attributes(scanner("iptables"), "iptables v1.6.1\n")
        assertEquals(
            mapOf("tool.iptables.version" to "1.6.1"),
            old.associate { it.path to it.value },
        )
    }

    @Test
    fun curlReportsTheClientVersion() {
        assertTranscripts(
            "curl",
            mapOf(
                "ubuntu-22.04" to mapOf("tool.curl.version" to "7.81.0"),
                "debian-bookworm" to mapOf("tool.curl.version" to "7.88.1"),
                "macos-26" to mapOf("tool.curl.version" to "8.7.1"),
                "alpine-3.18" to mapOf("tool.curl.version" to "8.12.1"),
                "alpine-3.20" to mapOf("tool.curl.version" to "8.14.1"),
            ),
        )
    }

    @Test
    fun rDartJuliaElixirAndErlangReportTheirVersions() {
        assertTranscripts("r", mapOf("r-base-4.6.1" to mapOf("tool.r.version" to "4.6.1")))
        assertTranscripts(
            "dart",
            mapOf(
                "dart-3.13.5" to
                    mapOf("tool.dart.version" to "3.13.5", "tool.dart.channel" to "stable"),
            ),
        )
        assertTranscripts(
            "julia",
            mapOf("julia-1.13.1" to mapOf("tool.julia.version" to "1.13.1")),
        )
        assertTranscripts(
            "elixir",
            mapOf(
                "elixir-1.20.4-otp-29" to
                    mapOf("tool.elixir.version" to "1.20.4", "tool.elixir.compiled-otp" to "29"),
            ),
        )
        assertTranscripts("erlang", mapOf("otp-29" to mapOf("tool.erlang.otp" to "29")))
        assertTranscripts("xcode", mapOf("xcode-26.6" to mapOf("tool.xcode.version" to "26.6")))
        assertTranscripts(
            "cocoapods",
            mapOf("cocoapods-1.16.2" to mapOf("tool.cocoapods.version" to "1.16.2")),
        )
        val old = Scanners.attributes(scanner("elixir"), "Elixir 1.14.0\n")
        assertEquals(
            mapOf("tool.elixir.version" to "1.14.0"),
            old.associate {
                it.path to it.value
            },
        )
        val beta = Scanners.attributes(
            scanner("dart"),
            "Dart SDK version: 3.14.0-315.1.beta (beta)",
        )
        assertEquals("3.14.0-315.1.beta", beta.first().value)
    }

    @Test
    fun newToolsDegradeToUnparsedAndStayAbsentWhenMissing() {
        val tools = listOf(
            "openssl", "ssh", "composer", "sudo", "ruby", "named", "iptables", "curl",
            "r", "dart", "julia", "elixir", "erlang", "xcode", "cocoapods",
        )
        for (tool in tools) {
            val attrs = Scanners.attributes(scanner(tool), "something else entirely\n")
            val first = attrs.first()
            assertEquals("tool.$tool.version" to "unparsed", first.path to first.value)
        }
        val missing = FakeHost(
            commands = Scanners.all.associate { it.command to CommandResult(127, "not found") },
        )
        assertEquals(emptyList(), Scanners.scan(missing))
    }

    @Test
    fun visualStudioVersionComesFromVswhere() {
        val vs2022 = parsed("visualstudio", "vs-2022-synthetic")
        val vs2026 = parsed("visualstudio", "vs-2026-synthetic")
        assertEquals("17.14.36301.6", vs2022.getValue("version"))
        assertEquals("18.0.11222.15", vs2026.getValue("version"))
        val noise = Scanners.attributes(scanner("visualstudio"), "no installation found\n")
        assertEquals("unparsed", noise.first().value)
    }

    @Test
    fun newScannersRunThroughTheHostAndTheKit() {
        val host = FakeHost(
            commands = mapOf(
                listOf("ldd", "--version") to
                    CommandResult(1, Fixtures.text("libc/musl-alpine-3.20.txt")),
                scanner("python-managed").command to CommandResult(0, "False\n"),
            ),
        )
        val byPath = Scanners.scan(host).associate { it.path to it.value }
        assertEquals("musl", byPath["tool.libc.family"])
        assertEquals("1.2.5", byPath["tool.libc.version"])
        assertEquals("false", byPath["tool.python.externally-managed"])
        assertTrue("run python-managed 'python3' '-c' " in Kit.sh())
        assertTrue("Run-Tool 'visualstudio' 'vswhere'" in Kit.ps1())
    }
}

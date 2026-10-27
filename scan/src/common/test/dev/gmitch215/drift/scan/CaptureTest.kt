package dev.gmitch215.drift.scan

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.scan.probe.Catalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureTest {
    private val host = FakeHost(
        platform = "linux",
        arch = "amd64",
        env = mapOf(
            "TZ" to "UTC",
            "PWD" to "/work",
            "GITHUB_TOKEN" to "abc",
            "NOTE" to "ghp_0123456789abcdefghijABCDEFGHIJ",
        ),
        files = mapOf(
            "/etc/os-release" to "ID=ubuntu\nVERSION_ID=\"24.04\"\nHOME_URL=x\n",
            "/sys/fs/cgroup/cpu.max" to "200000 100000\n",
        ),
        commands = mapOf(
            listOf("uname", "-r") to CommandResult(0, "6.8.0-1\n"),
            listOf("getconf", "_NPROCESSORS_ONLN") to CommandResult(0, "4\n"),
        ),
    )

    @Test
    fun collectsFactsFromHost() {
        val byPath = Capture.run(host, "t").attributes.associateBy { it.path }
        assertEquals("x86_64", byPath.getValue("os.arch").value)
        assertEquals("ubuntu", byPath.getValue("os.release.ID").value)
        assertEquals("24.04", byPath.getValue("os.release.VERSION_ID").value)
        assertEquals("6.8.0-1", byPath.getValue("kernel.release").value)
        assertEquals("4", byPath.getValue("cpu.count").value)
        assertEquals("200000 100000", byPath.getValue("cgroup.cpu.max").value)
        assertNull(byPath["os.release.HOME_URL"])
        assertEquals(Stability.VOLATILE, byPath.getValue("env.PWD").stability)
        assertEquals(Stability.STATIC, byPath.getValue("env.TZ").stability)
    }

    private fun facts(
        env: Map<String, String> = emptyMap(),
        files: Map<String, String> = emptyMap(),
        link: String? = null,
        os: String = "linux",
    ): Map<String, String> {
        val commands = link?.let {
            mapOf(listOf("readlink", "/etc/localtime") to CommandResult(0, "$it\n"))
        }.orEmpty()
        return Capture.run(FakeHost(os = os, env = env, files = files, commands = commands), "t")
            .attributes.associate { it.path to it.value }
    }

    @Test
    fun cgroupVersionComesFromTheProcessHierarchyFile() {
        val v2 = "0::/user.slice/session-1.scope\n"
        val v1 = "12:memory:/docker/abc\n1:name=systemd:/docker/abc\n0::/docker/abc\n"
        assertEquals("v2", facts(files = mapOf("/proc/self/cgroup" to v2))["cgroup.version"])
        assertEquals("v1", facts(files = mapOf("/proc/self/cgroup" to v1))["cgroup.version"])
        assertNull(facts(files = mapOf("/proc/self/cgroup" to "\n"))["cgroup.version"])
        assertNull(facts()["cgroup.version"])
    }

    @Test
    fun systemZoneComesFromTheLocaltimeLinkThenEtcTimezone() {
        val zone = "tz.system-zone"
        assertEquals("America/Chicago", facts(link = "/usr/share/zoneinfo/America/Chicago")[zone])
        assertEquals(
            "America/Chicago",
            facts(link = "/var/db/timezone/zoneinfo/America/Chicago")[zone],
        )
        assertEquals("UTC", facts(link = "/usr/share/zoneinfo/Etc/UTC")[zone])
        assertEquals("UTC", facts(files = mapOf("/etc/timezone" to "Etc/UTC\n"))[zone])
        assertEquals("Etc/GMT", facts(files = mapOf("/etc/timezone" to "Etc/GMT\n"))[zone])
        assertEquals(
            "Europe/Paris",
            facts(
                link = "/usr/share/zoneinfo/Europe/Paris",
                files = mapOf("/etc/timezone" to "UTC"),
            )[zone],
        )
        assertNull(facts()[zone])
        assertNull(facts(link = "/etc/elsewhere")[zone])
        assertNull(facts(os = "windows", link = "/usr/share/zoneinfo/Etc/UTC")[zone])
    }

    @Test
    fun timeZoneDatabaseVersionComesFromTheZoneinfoFiles() {
        val key = "tz.database.version"
        val zi = "/usr/share/zoneinfo/tzdata.zi"
        assertEquals("2024a", facts(files = mapOf(zi to "# version 2024a\nR x\n"))[key])
        assertEquals("2026b", facts(files = mapOf(zi to "# version 2026b-rearguard\n"))[key])
        assertEquals(
            "2026b",
            facts(files = mapOf("/usr/share/zoneinfo/+VERSION" to "2026b\n"))[key],
        )
        assertNull(facts(files = mapOf(zi to "R x\n"))[key])
        assertNull(facts()[key])
    }

    @Test
    fun kernelAndPolicyFilesGiveOnlyTheSettingsRulesRead() {
        val got = facts(
            files = mapOf(
                "/proc/cmdline" to "BOOT_IMAGE=/vmlinuz root=UUID=abc ro net.ifnames=0 quiet\n",
                "/proc/sys/kernel/apparmor_restrict_unprivileged_userns" to "1\n",
                "/etc/crypto-policies/config" to "# comment\nDEFAULT\n",
            ),
        )
        assertEquals("ro net.ifnames=0 quiet", got["kernel.cmdline"])
        assertEquals("1", got["sysctl.kernel.apparmor_restrict_unprivileged_userns"])
        assertEquals("DEFAULT", got["crypto.policy"])
        assertNull(facts()["kernel.cmdline"])
        assertNull(facts(files = mapOf("/proc/cmdline" to "root=/dev/sda1\n"))["kernel.cmdline"])
        assertNull(
            facts(files = mapOf("/etc/crypto-policies/config" to "# only\n"))["crypto.policy"],
        )
    }

    @Test
    fun limitsComeFromTheProcessLimitsFile() {
        val got = facts(files = mapOf("/proc/self/limits" to PROC_LIMITS))
        assertEquals(
            mapOf(
                "limits.cpu" to "unlimited:unlimited",
                "limits.fsize" to "100000:100000",
                "limits.data" to "unlimited:unlimited",
                "limits.stack" to "8388608:unlimited",
                "limits.core" to "0:0",
                "limits.nproc" to "unlimited:unlimited",
                "limits.nofile" to "1024:2048",
                "limits.memlock" to "8388608:8388608",
                "limits.as" to "unlimited:unlimited",
            ),
            got.filterKeys { it.startsWith("limits.") },
        )
        val source = Capture.run(
            FakeHost(files = mapOf("/proc/self/limits" to PROC_LIMITS)),
            "t",
        ).attributes.single { it.path == "limits.nofile" }
        assertEquals("/proc/self/limits", source.source)
        assertEquals(Stability.STATIC, source.stability)
    }

    @Test
    fun theOpenFilesLimitIsLeftOutWhereTheRuntimeRaisesItAtStart() {
        val files = mapOf("/proc/self/limits" to PROC_LIMITS)
        for (platform in listOf("jvm", "wasm")) {
            val got = Capture.run(FakeHost(platform = platform, files = files), "t")
                .attributes.associate { it.path to it.value }
            assertNull(got["limits.nofile"], platform)
            assertEquals("0:0", got["limits.core"], platform)
        }
        for (platform in listOf("linux", "ssh", "android")) {
            val got = Capture.run(FakeHost(platform = platform, files = files), "t")
                .attributes.associate { it.path to it.value }
            assertEquals("1024:2048", got["limits.nofile"], platform)
        }
    }

    @Test
    fun noLimitsFileOrAMalformedLineGivesNoAttribute() {
        fun limitKeys(got: Map<String, String>) = got.keys.filter { it.startsWith("limits.") }
        assertEquals(emptyList(), limitKeys(facts()))
        assertEquals(emptyList(), limitKeys(facts(os = "macos")))
        assertEquals(emptyList(), limitKeys(facts(files = mapOf("/proc/self/limits" to "\n"))))
        val text = "Max open files            many                 2048                 files\n" +
            "Max core file size        0                    \n" +
            "Max file size             -1                   -1                   bytes\n" +
            "Max processes             4096                 8192                 processes\n"
        val got = facts(files = mapOf("/proc/self/limits" to text))
        assertEquals(listOf("limits.nproc"), limitKeys(got))
        assertEquals("4096:8192", got["limits.nproc"])
    }

    @Test
    fun fsTypeIsTheFilesystemOfTheLongestMountThatHoldsThePath() {
        val mounts = MOUNTINFO + "3800 3739 0:99 / /mnt/with\\040space rw - xfs /dev/x rw\n"
        assertEquals("overlay", SystemFacts.fsType(mounts, "/"))
        assertEquals("overlay", SystemFacts.fsType(mounts, "/tmpfile"))
        assertEquals("tmpfs", SystemFacts.fsType(mounts, "/tmp"))
        assertEquals("tmpfs", SystemFacts.fsType(mounts, "/tmp/build/out"))
        assertEquals("ext4", SystemFacts.fsType(mounts, "/bindtest/a.txt"))
        assertEquals("ext4", SystemFacts.fsType(mounts, "/etc/hosts"))
        assertEquals("xfs", SystemFacts.fsType(mounts, "/mnt/with space/data"))
        assertNull(SystemFacts.fsType(null, "/tmp"))
        assertNull(SystemFacts.fsType(mounts, null))
        assertNull(SystemFacts.fsType("3739 1 0:1 / / rw\n", "/"))
    }

    @Test
    fun localeNamesComeFromTheArchiveAndTheDirectoryListing() {
        val archive = "\u0000\u0001C\u0000POSIX\u0000en_US.utf8\u0000\u0002de_DE@euro\u0000" +
            "LC_CTYPE\u0000ab\u0000utf8\u0000"
        assertEquals(
            "C,C.utf8,POSIX,de_DE@euro,en_US.utf8",
            SystemFacts.locales(archive, "C.utf8\nPOSIX\n"),
        )
        assertEquals("C.utf8", SystemFacts.locales(null, "C.utf8\nlocale-archive\n"))
        assertEquals("", SystemFacts.locales(null, ""))
    }

    @Test
    fun hostsNamesEveryCustomEntryAndKeepsLoopbackAddresses() {
        val hosts = "127.0.0.1\tlocalhost\n127.0.1.1\tworkstation\n127.0.0.1 db.internal\n" +
            "::1\tlocalhost ip6-localhost ip6-loopback\n127.0.0.2 cache.internal api.internal\n" +
            "fe00::\tip6-localnet\nff02::1\tip6-allnodes\n" +
            "172.17.0.3\thost\n# 10.0.0.1 commented\n10.0.0.9 vault.internal # note\n"
        assertEquals(
            listOf(
                "db.internal@127.0.0.1",
                "cache.internal@127.0.0.2",
                "api.internal@127.0.0.2",
                "host",
                "vault.internal",
            ),
            SystemFacts.hostNames(hosts),
        )
        assertEquals(
            listOf("vault.internal"),
            SystemFacts.hostNames("10.0.0.2 f54aee10db6e vault.internal\n", "f54aee10db6e"),
        )
        assertEquals(emptyList(), SystemFacts.hostNames("127.0.0.1 localhost\n"))
        assertNull(SystemFacts.hostNames(null))
    }

    @Test
    fun nsswitchRecordsTheSourcesOfTheHostsDatabase() {
        val text = "# comment\npasswd:         files\nhosts:          files   dns # inline\n"
        assertEquals("files dns", SystemFacts.nsswitchHosts(text))
        assertEquals(
            "files mdns4_minimal [NOTFOUND=return] dns",
            SystemFacts.nsswitchHosts("hosts: files mdns4_minimal [NOTFOUND=return] dns\n"),
        )
        assertNull(SystemFacts.nsswitchHosts("passwd: files\n"))
        assertNull(SystemFacts.nsswitchHosts(null))
    }

    @Test
    fun cpuAffinityCountsTheCpusTheProcessMayUse() {
        val status = "Name:\tgrep\nCpus_allowed_list:\t0-23\nMems_allowed_list:\t0\n"
        assertEquals("24", SystemFacts.cpuAffinity(status))
        assertEquals("7", SystemFacts.cpuAffinity("Cpus_allowed_list:\t0-3,8,10-11\n"))
        assertEquals("1", SystemFacts.cpuAffinity("Cpus_allowed_list:\t5\n"))
        assertNull(SystemFacts.cpuAffinity("Cpus_allowed_list:\tx\n"))
        assertNull(SystemFacts.cpuAffinity("Name:\tgrep\n"))
    }

    @Test
    fun newSystemFactsComeFromTheHostAndAreAbsentWithoutTheirSources() {
        val files = mapOf(
            "/proc/self/mountinfo" to MOUNTINFO,
            "/etc/hosts" to "127.0.0.1 localhost\n127.0.0.1\tdb.internal\n172.17.0.3\thost\n",
            "/etc/nsswitch.conf" to "hosts: dns\n",
            "/proc/self/status" to "Cpus_allowed_list:\t0-23\n",
        )
        val commands = mapOf(
            listOf("readlink", "/proc/self/cwd") to CommandResult(0, "/work\n"),
            listOf("ls", "-1", "/usr/lib/locale") to CommandResult(0, "C.utf8\nlocale-archive\n"),
            listOf("ls", "-1", "/sys/class/net") to CommandResult(0, "lo\neth0\n"),
        )
        val got = Capture.run(FakeHost(files = files, commands = commands), "t")
            .attributes.associate { it.path to it.value }
        assertEquals("overlay", got["fs.cwd.type"])
        assertEquals("tmpfs", got["fs.tmp.type"])
        assertEquals("C.utf8", got["locale.installed"])
        assertEquals("db.internal@127.0.0.1,host", got["network.hosts.names"])
        assertEquals("dns", got["network.nsswitch.hosts"])
        assertEquals("eth0,lo", got["network.interfaces"])
        assertEquals("24", got["cpu.affinity"])

        val bare = facts(os = "macos")
        val absent = listOf(
            "fs.tmp.type",
            "fs.cwd.type",
            "locale.installed",
            "network.hosts.names",
            "network.nsswitch.hosts",
            "network.interfaces",
            "cpu.affinity",
        )
        for (path in absent) assertNull(bare[path], path)
    }

    @Test
    fun memoryLimitIsPresentOnlyWhenTheCgroupCapsMemory() {
        val key = "cgroup.memory.limit"
        val v2 = "/sys/fs/cgroup/memory.max"
        val v1 = "/sys/fs/cgroup/memory/memory.limit_in_bytes"
        assertEquals("true", facts(files = mapOf(v2 to "536870912\n"))[key])
        assertEquals("true", facts(files = mapOf(v1 to "536870912\n"))[key])
        assertNull(facts(files = mapOf(v2 to "max\n"))[key])
        assertNull(facts(files = mapOf(v1 to "9223372036854771712\n"))[key])
        assertNull(facts()[key])
    }

    @Test
    fun macosReportsItsProductVersion() {
        val host = FakeHost(
            os = "macos",
            commands = mapOf(listOf("sw_vers", "-productVersion") to CommandResult(0, "26.5.2\n")),
        )
        assertEquals(
            "26.5.2",
            Capture.run(host, "t").attributes.single {
                it.path == "os.version"
            }.value,
        )
        assertNull(facts()["os.version"])
    }

    @Test
    fun jvmOptionsJoinTheToolAndLauncherVariables() {
        val options = "runtime.jvm.options"
        assertNull(facts()[options])
        assertNull(facts(env = mapOf("JAVA_TOOL_OPTIONS" to " "))[options])
        assertEquals("-Xmx1g", facts(env = mapOf("JAVA_TOOL_OPTIONS" to "-Xmx1g"))[options])
        assertEquals("-Xmx1g", facts(env = mapOf("JDK_JAVA_OPTIONS" to "-Xmx1g"))[options])
        val both = mapOf("JDK_JAVA_OPTIONS" to "-XX:+UseZGC", "JAVA_TOOL_OPTIONS" to "-Xmx1g")
        assertEquals("-Xmx1g -XX:+UseZGC", facts(env = both)[options])
    }

    @Test
    fun preferIpv6AddressesIsOnlyEmittedWhenTheOptionsSetIt() {
        val key = "java.net.preferIPv6Addresses"
        assertNull(facts()[key])
        assertNull(facts(env = mapOf("JAVA_TOOL_OPTIONS" to "-Xmx1g"))[key])
        val set = "-Xmx1g -Djava.net.preferIPv6Addresses=true"
        assertEquals("true", facts(env = mapOf("JAVA_TOOL_OPTIONS" to set))[key])
        val twice = mapOf(
            "JAVA_TOOL_OPTIONS" to "-Djava.net.preferIPv6Addresses=true",
            "JDK_JAVA_OPTIONS" to "-Djava.net.preferIPv6Addresses=system",
        )
        assertEquals("system", facts(env = twice)[key])
    }

    @Test
    fun redactsSecretsBeforeTheyEnterTheCapsule() {
        val byPath = Capture.run(host, "t").attributes.associateBy { it.path }
        assertEquals(Redactor.MASK, byPath.getValue("env.GITHUB_TOKEN").value)
        assertEquals(Redactor.MASK, byPath.getValue("env.NOTE").value)
    }

    @Test
    fun proxyAndCertificateVariablesAreCapturedWithoutProxyCredentials() {
        val proxies = FakeHost(
            env = mapOf(
                "HTTP_PROXY" to "http://alice:hunter2@proxy.corp:3128",
                "https_proxy" to "http://alice:hunter2@proxy.corp:3128",
                "NO_PROXY" to "localhost,.corp",
                "REQUESTS_CA_BUNDLE" to "/etc/ssl/corp.pem",
                "CURL_CA_BUNDLE" to "/etc/ssl/corp.pem",
                "SSL_CERT_FILE" to "/etc/ssl/cert.pem",
                "SSL_CERT_DIR" to "/etc/ssl/certs",
                "NODE_EXTRA_CA_CERTS" to "/etc/ssl/extra.pem",
            ),
        )
        val byPath = Capture.run(proxies, "t").attributes.associateBy { it.path }
        for (name in proxies.env().keys) assertTrue("env.$name" in byPath, name)
        for (name in listOf("HTTP_PROXY", "https_proxy")) {
            val value = byPath.getValue("env.$name").value
            assertTrue("hunter2" !in value && "alice" !in value, value)
            assertTrue("proxy.corp:3128" in value, value)
        }
        assertEquals("localhost,.corp", byPath.getValue("env.NO_PROXY").value)
        assertEquals("/etc/ssl/corp.pem", byPath.getValue("env.REQUESTS_CA_BUNDLE").value)
    }

    @Test
    fun hostWithoutProcessesReportsOnlyWhatItKnows() {
        val paths = Capture.run(
            FakeHost(platform = "wasm", os = "unknown", arch = "wasm32", canRun = false),
            "w",
        )
            .attributes.map { it.path }
        assertEquals(
            listOf(
                "drift.platform",
                "kotlin.target",
                "kotlin.version",
                "os.arch",
                "os.name",
                "runtime.platform",
            ),
            paths,
        )
    }

    @Test
    fun windowsUsesVerAndEnvCount() {
        val win = FakeHost(
            platform = "mingw",
            os = "windows",
            env = mapOf("NUMBER_OF_PROCESSORS" to "8"),
            commands = mapOf(
                listOf("cmd", "/c", "ver") to
                    CommandResult(0, "\r\nMicrosoft Windows [Version 10.0.26100.1]\r\n"),
            ),
        )
        val byPath = Capture.run(win, "w").attributes.associateBy { it.path }
        assertEquals(
            "Microsoft Windows [Version 10.0.26100.1]",
            byPath.getValue("os.version").value,
        )
        assertEquals("8", byPath.getValue("cpu.count").value)
        assertTrue(win.ran.none { it.first() == "uname" })
    }

    @Test
    fun osNameIsTheSharedVocabulary() {
        val os = Capture.run(systemHost(), "os").attributes.single { it.path == "os.name" }.value
        assertTrue(os in OS_NAMES, os)
    }

    @Test
    fun selfDiffOnTheRealHostIsEmpty() {
        val real = systemHost()
        val a = Capture.run(real, "a")
        val b = Capture.run(real, "b")
        assertEquals(emptyList(), CapsuleDiff.attributes(a, b))
        if (real.platform != "wasm") {
            assertTrue(a.attributes.any { it.path == "os.name" }, "os.name missing")
            assertTrue(
                a.attributes.any {
                    it.path.startsWith("env.")
                },
                "no env captured on ${real.platform}",
            )
        }
    }

    @Test
    fun runtimePlatformDefaultsToTheHostAndCanBeDeclared() {
        val plain = Capture.run(host, "t").attributes.single { it.path == "runtime.platform" }
        assertEquals("linux", plain.value)
        assertEquals("drift", plain.source)
        val declared =
            FakeHost(platform = "jvm", env = mapOf("DRIFT_RUNTIME_PLATFORM" to "workerd-local"))
        val attr = Capture.run(declared, "t").attributes.single { it.path == "runtime.platform" }
        assertEquals("workerd-local", attr.value)
        assertEquals("env", attr.source)
        assertEquals(Stability.STATIC, attr.stability)
        assertTrue(Capture.run(declared, "t").attributes.none { it.path.startsWith("env.DRIFT_") })
        val blank = FakeHost(platform = "jvm", env = mapOf("DRIFT_RUNTIME_PLATFORM" to " "))
        assertEquals(
            "jvm",
            Capture.run(blank, "t").attributes.single {
                it.path == "runtime.platform"
            }.value,
        )
    }

    @Test
    fun runnerLabelComesOnlyFromTheDeclaredVariable() {
        assertTrue(Capture.run(host, "t").attributes.none { it.path == "ci.runner.label" })
        val env = mapOf("DRIFT_RUNNER_LABEL" to "ubuntu-latest", "RUNNER_OS" to "Linux")
        val ci = FakeHost(env = env)
        val label = Capture.run(ci, "t").attributes.single { it.path == "ci.runner.label" }
        assertEquals("ubuntu-latest", label.value)
        assertEquals(Stability.STATIC, label.stability)
    }

    @Test
    fun hardwareFactsAreOptIn() {
        val fact = Attribute("hw.memory.total", "1", "test")
        val withFacts = FakeHost(facts = listOf(fact))
        assertTrue(Capture.run(withFacts, "t").attributes.none { it.path.startsWith("hw.") })
        val on = Capture.run(withFacts, "t", hardware = true).attributes
        assertEquals(fact, on.single { it.path == "hw.memory.total" })
    }

    private fun leaky(account: String, home: String, name: String) = FakeHost(
        env = mapOf(
            "HOME" to home,
            "USER" to account,
            "LOGNAME" to account,
            "HOSTNAME" to name,
            "PATH" to "$home/bin:/usr/bin:$home/.nvm/node/bin",
            "NVM_DIR" to "$home/.nvm",
            "MAIL_NOTE" to "sent by $account from $name",
            "OTHER" to "/home/someoneelse/tool",
            "TZ" to "UTC",
        ),
        files = mapOf("/sys/fs/cgroup/cpu.max" to "max 100000 $home\n"),
        commands = mapOf(
            listOf("node", "--version") to CommandResult(0, "odd $home/bin/node build\n"),
            listOf("uname", "-r") to CommandResult(0, "6.8.0\n"),
        ),
        facts = listOf(Attribute("hw.model", "box of $account", "test")),
    )

    @Test
    fun anonymizesTheAccountHostAndHomePathEverywhere() {
        val host = leaky("zorbulax", "/home/zorbulax", "quuxhost")
        val capsule =
            Capture.run(host, "t", probes = Catalog.all, tools = true, hardware = true)
        val text = capsule.canonical()
        for (secret in listOf("zorbulax", "quuxhost")) {
            assertFalse(secret in text, "$secret leaked: $text")
        }
        val byPath = capsule.attributes.associateBy { it.path }
        assertEquals("~", byPath.getValue("env.HOME").value)
        assertEquals("user", byPath.getValue("env.USER").value)
        assertEquals("host", byPath.getValue("env.HOSTNAME").value)
        assertEquals("~/bin:/usr/bin:~/.nvm/node/bin", byPath.getValue("env.PATH").value)
        assertEquals("~/.nvm", byPath.getValue("env.NVM_DIR").value)
        assertEquals("sent by user from host", byPath.getValue("env.MAIL_NOTE").value)
        assertEquals("/home/user/tool", byPath.getValue("env.OTHER").value)
        assertEquals("max 100000 ~", byPath.getValue("cgroup.cpu.max").value)
        assertEquals("odd ~/bin/node build", byPath.getValue("tool.node.raw").value)
        assertEquals("box of user", byPath.getValue("hw.model").value)
        assertEquals("env (anonymized)", byPath.getValue("env.PATH").source)
        assertEquals("env", byPath.getValue("env.TZ").source)
        assertEquals("uname", byPath.getValue("kernel.release").source)
    }

    @Test
    fun runtimeDirUidAndHomePathLabelTheSourceOnce() {
        val host = FakeHost(
            env = mapOf(
                "HOME" to "/home/zorbulax",
                "XDG_RUNTIME_DIR" to "/run/user/1000",
                "MIXED" to "/home/someoneelse/x /run/user/1000",
            ),
        )
        val byPath = Capture.run(host, "t").attributes.associateBy { it.path }
        assertEquals("/run/user/user", byPath.getValue("env.XDG_RUNTIME_DIR").value)
        assertEquals("env (anonymized)", byPath.getValue("env.XDG_RUNTIME_DIR").source)
        assertEquals("/home/user/x /run/user/user", byPath.getValue("env.MIXED").value)
        assertEquals("env (anonymized)", byPath.getValue("env.MIXED").source)
    }

    @Test
    fun keepIdentifiersKeepsThemAndNothingElseChanges() {
        val host = leaky("zorbulax", "/home/zorbulax", "quuxhost")
        val kept = Capture.run(host, "t", keepIdentifiers = true)
        val byPath = kept.attributes.associateBy { it.path }
        assertEquals("/home/zorbulax", byPath.getValue("env.HOME").value)
        assertEquals("zorbulax", byPath.getValue("env.USER").value)
        assertEquals("env", byPath.getValue("env.HOME").source)
        val plain = Capture.run(host, "t")
        assertEquals(
            kept.attributes.map { it.path },
            plain.attributes.map { it.path },
        )
    }

    @Test
    fun machinesWithDifferentAccountsAnonymizeToTheSameCapsule() {
        val mac = leaky("alicexyz", "/Users/alicexyz", "alices-mac")
        val box = leaky("bobabc", "/home/bobabc", "bob-ci-7")
        val a = Capture.run(mac, "a", tools = true)
        val b = Capture.run(box, "a", tools = true)
        assertEquals(emptyList(), CapsuleDiff.attributes(a, b))
        assertEquals(a.canonical(), Capture.run(mac, "a", tools = true).canonical())
    }

    @Test
    fun shortNamesAndUnrelatedWordsAreNotRewritten() {
        val host = FakeHost(
            env = mapOf(
                "USER" to "al",
                "HOME" to "/home/al",
                "NOTE" to "al went to alabama; usertools stay",
                "PATH" to "/home/albert/bin:/home/al/bin",
            ),
        )
        val byPath = Capture.run(host, "t").attributes.associateBy { it.path }
        assertEquals("al", byPath.getValue("env.USER").value)
        assertEquals("al went to alabama; usertools stay", byPath.getValue("env.NOTE").value)
        assertEquals("/home/user/bin:~/bin", byPath.getValue("env.PATH").value)
    }

    @Test
    fun shellFlavorIsTheBaseNameOfWhatBinShLinksTo() {
        assertEquals("dash", SystemFacts.shellName("/usr/bin/dash\n"))
        assertEquals("busybox", SystemFacts.shellName("/bin/busybox"))
        assertNull(SystemFacts.shellName("/bin/sh\n"))
        assertNull(SystemFacts.shellName("dash"))
        assertNull(SystemFacts.shellName(""))
        assertNull(SystemFacts.shellName(null))
        val link = FakeHost(
            commands = mapOf(
                listOf("readlink", "-f", "/bin/sh") to CommandResult(0, "/usr/bin/bash\n"),
            ),
        )
        assertEquals(
            "bash",
            Capture.run(link, "t").attributes.single { it.path == "tool.sh.flavor" }.value,
        )
        assertNull(facts()["tool.sh.flavor"])
        assertNull(facts(os = "windows")["tool.sh.flavor"])
    }

    @Test
    fun kernelSettingsAndClockSourceAreOneWordFromTheirFixedFiles() {
        val got = facts(
            files = mapOf(
                "/proc/sys/vm/max_map_count" to "262144\n",
                "/proc/sys/fs/file-max" to "9223372036854775807\n",
                "/proc/sys/net/core/somaxconn" to "4096 8192\n",
                "/proc/sys/vm/swappiness" to "60\n",
                "/sys/devices/system/clocksource/clocksource0/current_clocksource" to "kvm-clock\n",
            ),
        )
        assertEquals("262144", got["sysctl.vm.max_map_count"])
        assertEquals("9223372036854775807", got["sysctl.fs.file-max"])
        assertEquals("kvm-clock", got["clock.source"])
        assertNull(got["sysctl.net.core.somaxconn"])
        assertNull(got["sysctl.vm.swappiness"])
        assertNull(facts()["clock.source"])
        assertNull(facts()["sysctl.vm.max_map_count"])
    }

    @Test
    fun ipv6IsTrueOnlyWhenANonLoopbackAddressIsListed() {
        val loopback = "00000000000000000000000000000001 01 80 10 80       lo\n"
        val global = "20010db8000000000000000000000001 02 40 00 80 eth0\n"
        assertEquals(
            "true",
            facts(files = mapOf("/proc/net/if_inet6" to loopback + global))["network.ipv6"],
        )
        assertEquals(
            "false",
            facts(files = mapOf("/proc/net/if_inet6" to loopback))["network.ipv6"],
        )
        assertNull(facts()["network.ipv6"])
        val disabled = "/proc/sys/net/ipv6/conf/all/disable_ipv6"
        assertEquals("true", facts(files = mapOf(disabled to "1\n"))["network.ipv6.disabled"])
        assertEquals("false", facts(files = mapOf(disabled to "0\n"))["network.ipv6.disabled"])
        assertNull(facts(files = mapOf(disabled to "maybe\n"))["network.ipv6.disabled"])
    }

    @Test
    fun resolverFilesGiveCountsAndFlagsButNeverAnAddressOrName() {
        val text = "# generated\nsearch corp.example internal.example\n" +
            "nameserver 127.0.0.53\noptions edns0 ndots:5 timeout:2\n"
        val host = FakeHost(files = mapOf("/etc/resolv.conf" to text))
        val got = Capture.run(host, "t").attributes.associate { it.path to it.value }
        assertEquals("1", got["network.dns.nameservers"])
        assertEquals("true", got["network.dns.loopback"])
        assertEquals("2", got["network.dns.search"])
        assertEquals("edns0,ndots,timeout", got["network.dns.options"])
        val canonical = Capture.run(host, "t").canonical()
        assertFalse("127.0.0.53" in canonical, canonical)
        assertFalse("corp.example" in canonical, canonical)
    }

    @Test
    fun resolverLoopbackNeedsEveryServerLocalAndCountsAreZeroWhenEmpty() {
        val mixed = "nameserver 127.0.0.53\nnameserver 10.0.0.2\n"
        val got = facts(files = mapOf("/etc/resolv.conf" to mixed))
        assertEquals("2", got["network.dns.nameservers"])
        assertEquals("false", got["network.dns.loopback"])
        assertNull(got["network.dns.search"])
        assertNull(got["network.dns.options"])
        val empty = facts(files = mapOf("/etc/resolv.conf" to "# none\n"))
        assertEquals("0", empty["network.dns.nameservers"])
        assertNull(empty["network.dns.loopback"])
        val domain = facts(files = mapOf("/etc/resolv.conf" to "domain a.example\n"))
        assertEquals("1", domain["network.dns.search"])
        assertNull(facts()["network.dns.nameservers"])
    }
}

private val OS_NAMES = setOf("linux", "macos", "windows", "android", "ios", "freebsd", "unknown")

private val PROC_LIMITS = """
    Limit                     Soft Limit           Hard Limit           Units
    Max cpu time              unlimited            unlimited            seconds
    Max file size             100000               100000               bytes
    Max data size             unlimited            unlimited            bytes
    Max stack size            8388608              unlimited            bytes
    Max core file size        0                    0                    bytes
    Max resident set          unlimited            unlimited            bytes
    Max processes             unlimited            unlimited            processes
    Max open files            1024                 2048                 files
    Max locked memory         8388608              8388608              bytes
    Max address space         unlimited            unlimited            bytes
    Max file locks            unlimited            unlimited            locks
    Max pending signals       123739               123739               signals
    Max msgqueue size         819200               819200               bytes
    Max nice priority         0                    0
    Max realtime priority     0                    0
    Max realtime timeout      unlimited            unlimited            us
""".trimIndent()

private val MOUNTINFO = """
3739 155 0:61 / / rw,relatime - overlay overlay rw,lowerdir=/l,upperdir=/u,workdir=/w,nouserxattr
3741 3739 0:182 / /proc rw,nosuid,nodev,noexec,relatime - proc proc rw
3783 3739 0:187 / /tmp rw,nosuid,nodev,noexec,relatime - tmpfs tmpfs rw,inode64
3784 3739 252:0 /srv/bind-src /bindtest ro,relatime - ext4 /dev/mapper/vg-lv rw
3789 3739 259:0 /docker/containers/0123abcd/hosts /etc/hosts rw,relatime - ext4 /dev/nvme0n1 rw
""".trimIndent() + "\n"

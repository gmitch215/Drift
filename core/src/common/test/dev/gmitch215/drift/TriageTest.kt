package dev.gmitch215.drift

import dev.gmitch215.drift.fixtures.TriageFixtures
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.know.Constraints
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.RuleCompiler
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.know.Severity
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.triage.Claims
import dev.gmitch215.drift.triage.Confidence
import dev.gmitch215.drift.triage.Disposition
import dev.gmitch215.drift.triage.Drafts
import dev.gmitch215.drift.triage.Fact
import dev.gmitch215.drift.triage.Grade
import dev.gmitch215.drift.triage.RuleRef
import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.Span
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.TriageOptions
import dev.gmitch215.drift.triage.TriageResult
import dev.gmitch215.drift.triage.TriageText
import dev.gmitch215.drift.triage.Via
import dev.gmitch215.drift.triage.Yaml
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriageTest {
    // #region helpers
    private val catalog: List<Rule> = listOf(
        "python-3-12-distutils-removed",
        "jdk-11-java-ee-modules-removed",
        "jdk-14-cms-collector-removed",
        "jdk-15-nashorn-removed",
        "jdk-24-security-manager-disabled",
    ).map { RuleCompiler.compile(Rules.text(it), it) }

    private val fence = "`".repeat(3)

    private fun run(
        text: String,
        rules: List<Rule> = emptyList(),
        options: TriageOptions = TriageOptions(),
    ) = Triage.run(text, options, rules)

    private fun v(text: String, key: String) = run(text).value(key)

    private fun TriageResult.fact(key: String, side: Side = Side.FAILING): Fact? =
        facts.firstOrNull { it.key == key && it.side == side }

    private fun TriageResult.value(key: String, side: Side = Side.FAILING) = fact(key, side)?.value

    private fun rule(
        id: String,
        detect: String,
        symptom: String = "plain words",
        basis: String = "inferred",
    ): Rule = demoRule(
        detect,
        "id" to "\"$id\"",
        "symptoms" to """[{"text":${text(symptom)},"basis":"$basis"}]""",
    )

    private fun text(s: String) = CanonicalJson.encode(JsonString(s))

    private val pythonRule = """{"all":[{"a":"tool.python.version","ver":"<3.12"},
        |{"b":"tool.python.version","ver":">=3.12"}]}
    """.trimMargin().replace("\n", "")
    // #endregion

    // #region fixtures
    private class Expect(
        val category: String,
        val facts: Set<Triple<String, String, Side>>,
        val rules: List<String>,
        val claims: Set<String>,
        val draft: Boolean,
    )

    private fun inline(line: String, key: String) =
        line.substringAfter("[").substringBeforeLast("]").split(",").map { it.trim() }
            .filter { it.isNotEmpty() }.also { assertTrue(line.startsWith("$key:"), line) }

    private fun expect(name: String): Expect {
        val facts = mutableSetOf<Triple<String, String, Side>>()
        var category = ""
        var rules = emptyList<String>()
        var claims = emptyList<String>()
        var draft = false
        var inFacts = false
        for (line in TriageFixtures.text("$name.yml").lines()) {
            when {
                line.startsWith("category:") -> category = line.substringAfter(":").trim()

                line.startsWith("facts:") -> inFacts = true

                line.startsWith("rules:") -> {
                    rules = inline(line, "rules")
                    inFacts = false
                }

                line.startsWith("claims:") -> {
                    claims = inline(line, "claims")
                    inFacts = false
                }

                line.startsWith("draft:") -> draft = line.endsWith("true")

                inFacts && line.startsWith("  - '") -> {
                    var item = line.removePrefix("  - '").removeSuffix("'").replace("''", "'")
                    val side = if (item.endsWith("@passing")) Side.PASSING else Side.FAILING
                    item = item.removeSuffix("@passing")
                    facts += Triple(item.substringBefore("="), item.substringAfter("="), side)
                }
            }
        }
        return Expect(category, facts, rules, claims.toSet(), draft)
    }

    private val names = TriageFixtures.names
        .filter { it.endsWith(".md") && !it.startsWith("golden/") }
        .map { it.removeSuffix(".md") }
        .sorted()

    @Test
    fun theFixtureSetCoversEveryFormPolicyAndHasAnExpectationFile() {
        assertTrue(names.size in 30..80, "fixtures: ${names.size}")
        for (n in names) assertTrue("$n.yml" in TriageFixtures.names, n)
        val categories = names.map { expect(it).category }.toSet()
        val all = setOf("strict-form", "forms-blank", "free-text", "beginner", "mixed")
        assertEquals(all, categories)
    }

    @Test
    fun factsMatchTheLabelledFixturesAndTheScoresStayInBounds() {
        var tp = 0
        var fp = 0
        var fn = 0
        var sum = 0L
        var n = 0
        val perKey = mutableMapOf<String, IntArray>()
        val bins = Array(10) { LongArray(3) }
        val perVia = mutableMapOf<String, LongArray>()
        val misses = mutableListOf<String>()
        for (name in names) {
            val want = expect(name).facts
            val got = run(TriageFixtures.text("$name.md")).facts
            val keys = got.map { Triple(it.key, it.value, it.side) }.toSet()
            for (f in got) {
                val ok = Triple(f.key, f.value, f.side) in want
                val c = f.confidence.permille
                val outcome = if (ok) 1000 else 0
                sum += (c - outcome).toLong() * (c - outcome)
                n++
                val bin = minOf(c / 100, 9)
                bins[bin][0]++
                bins[bin][1] += if (ok) 1 else 0
                bins[bin][2] += c.toLong()
                val v = perVia.getOrPut(f.via.id) { LongArray(3) }
                v[0]++
                v[1] += if (ok) 1 else 0
                v[2] += c.toLong()
                perKey.getOrPut(f.key) { IntArray(3) }[if (ok) 0 else 1]++
                if (ok) {
                    tp++
                } else {
                    fp++
                    misses += "$name: extra ${f.key}=${f.value} ${f.side}"
                }
            }
            for (w in want - keys) {
                fn++
                perKey.getOrPut(w.first) { IntArray(3) }[2]++
                misses += "$name: missed ${w.first}=${w.second} ${w.third}"
            }
        }
        println("triage fixtures: ${names.size} bodies, $n facts extracted")
        println("true positives $tp, false positives $fp, false negatives $fn")
        println("precision ${ratio(tp, tp + fp)}, recall ${ratio(tp, tp + fn)}")
        println("Brier ${brier(sum, n)} over $n facts")
        for ((k, c) in perKey.entries.sortedBy { it.key }) {
            val p = ratio(c[0], c[0] + c[1])
            println("  $k: precision $p, recall ${ratio(c[0], c[0] + c[2])}")
        }
        for ((i, b) in bins.withIndex()) {
            if (b[0] > 0) {
                val acc = ratio(b[1].toInt(), b[0].toInt())
                val mean = ratio(b[2].toInt(), b[0].toInt() * 1000)
                println("  bin 0.$i: ${b[0]} facts, accuracy $acc, mean confidence $mean")
            }
        }
        misses.forEach { println("  $it") }
        assertTrue(tp * 100 >= (tp + fp) * 95, "precision")
        assertTrue(tp * 100 >= (tp + fn) * 93, "recall")
        assertTrue(sum * 100 <= 12L * n * 1_000_000, "Brier")
        for ((via, v) in perVia.entries.sortedBy { it.key }) {
            assertTrue(v[1] * 1000 + 50 * v[0] >= v[2], "$via is overconfident: ${v[1]} of ${v[0]}")
        }
    }

    private fun ratio(a: Int, b: Int): String {
        if (b == 0) return "n/a"
        val milli = a * 1000 / b
        return "${milli / 1000}." + (milli % 1000).toString().padStart(3, '0')
    }

    private fun brier(sum: Long, n: Int): String {
        val ten = sum / (n * 100L)
        return "${ten / 10000}." + (ten % 10000).toString().padStart(4, '0')
    }

    @Test
    fun rulesClaimsAndDraftsMatchTheLabelledFixtures() {
        for (name in names) {
            val want = expect(name)
            val result = run(TriageFixtures.text("$name.md"), catalog)
            val found = result.rules.map { it.ruleId }.toSet()
            assertTrue(found.containsAll(want.rules), "$name rules ${want.rules} in $found")
            assertEquals(want.claims, result.claims.map { it.id }.toSet(), "$name claims")
            val drafted = result.draft?.disposition == Disposition.DRAFT
            assertEquals(want.draft, drafted, "$name draft")
        }
    }
    // #endregion

    // #region goldens
    private val goldenRule: Rule = RuleCompiler.compile(
        """{"schema":1,"id":"demo-distutils","title":"Demo distutils","applies":["runtime"],
        |"difference":"python 3.12 or newer","symptoms":[{"text":
        |"ModuleNotFoundError for distutils","basis":"inferred"}],
        |"mechanism":"Python removes distutils.","detect":{"all":[
        |{"a":"tool.python.version","ver":"<3.12"},{"b":"tool.python.version","ver":">=3.12"}]},
        |"severity":"high","provenance":[{"url":"https://example.com/x","kind":"spec",
        |"verified":"2026-10-06"}],"fixtures":{"positive":[{"name":"p","a":{"attrs":
        |{"tool.python.version":"3.11"}},"b":{"attrs":{"tool.python.version":"3.12"}}}],
        |"negative":[{"name":"n","a":{"attrs":{"tool.python.version":"3.12"}},"b":{"attrs":
        |{"tool.python.version":"3.12"}}}]}}
        """.trimMargin().replace("\n", ""),
    )

    @Test
    fun outputIsByteIdenticalOnEveryTarget() {
        for (name in listOf("s03-python-distutils", "f11-node-typeerror", "w07-env-dump")) {
            val result = run(
                TriageFixtures.text("$name.md"),
                listOf(goldenRule),
                TriageOptions(author = "OctoCat"),
            )
            assertEquals(TriageFixtures.text("golden/$name.txt"), TriageText.render(result), name)
            val json = CanonicalJson.encode(result.toJson()) + "\n"
            assertEquals(TriageFixtures.text("golden/$name.json"), json, name)
        }
    }

    @Test
    fun everyFixtureRunsAgainstTheWholeCatalog() {
        for (name in names) {
            val result = Triage.run(TriageFixtures.text("$name.md"))
            assertTrue(result.rules.all { it.ruleId in Rules.ids }, name)
        }
    }

    @Test
    fun theSameInputGivesTheSameResultTwice() {
        for (name in names) {
            val text = TriageFixtures.text("$name.md")
            assertEquals(run(text), run(text), name)
        }
    }
    // #endregion

    // #region mentions
    private fun values(text: String, key: String, side: Side = Side.FAILING) =
        run(text).facts.filter { it.key == key && it.side == side }.map { it.value }

    @Test
    fun aRequirementAndTheVersionFoundBecomeTwoFacts() {
        val composer = run(
            "Fatal error: Your dependencies require a PHP version \">= 8.2.0\". " +
                "You are running 8.0.30.",
        )
        assertEquals(">=8.2.0", composer.value("tool.php.required"))
        assertEquals("8.0.30", composer.value("tool.php.version"))
        val yarn = run(
            "error pkg@1.0.0: The engine \"node\" is incompatible with this module. " +
                "Expected version \">=20.0.0\". Got \"18.19.0\"",
        )
        assertEquals(">=20.0.0", yarn.value("tool.node.required"))
        assertEquals("18.19.0", yarn.value("tool.node.version"))
        val pip = run("ERROR: Package 'demo' requires a different Python: 3.8.10 not in '>=3.10'")
        assertEquals(">=3.10", pip.value("tool.python.required"))
        assertEquals("3.8.10", pip.value("tool.python.version"))
        val go = run("go: go.mod requires go >= 1.22 (running go 1.20; GOTOOLCHAIN=local)")
        assertEquals(">=1.22", go.value("tool.go.required"))
        assertEquals("1.20", go.value("tool.go.version"))
        val gradle = run("Minimum supported Gradle version is 8.5. Current version is 8.2")
        assertEquals("8.5", gradle.value("tool.gradle.required"))
        assertEquals("8.2", gradle.value("tool.gradle.version"))
    }

    @Test
    fun aRequirementNeedsAToolOnTheLineAndTheVersionFoundNeedsARequirement() {
        for (text in listOf(
            "This needs 2 more reviewers",
            "The task requires a restart of the server",
            "You are running 7.4.33",
            "Expected version 1.2.3",
        )) {
            val facts = run(text).facts
            assertTrue(
                facts.none {
                    it.key.endsWith(".required") || it.key.endsWith(".version")
                },
                text,
            )
        }
    }

    @Test
    fun aToolTheTextRunsGetsAVersionKeyWithoutATableEntry() {
        val r = run("$ skopeo --version\nskopeo version 1.13.3\n$ make test\nit takes make 2 hours")
        assertEquals("1.13.3", r.value("tool.skopeo.version"))
        assertNull(r.fact("tool.make.version"))
        assertNull(run("skopeo version 1.13.3").fact("tool.skopeo.version"))
        val apt = run("apt-get install -y mytool=2.1\nmytool 2.1.4 is what I see")
        assertEquals("2.1.4", apt.value("tool.mytool.version"))
        assertNull(run("$ ls -la\nls 2.5 megabytes").fact("tool.ls.version"))
        assertEquals(
            "24.0.7",
            run("Docker version 24.0.7, build afdd53b").value("tool.docker.version"),
        )
        assertEquals("3.0.2", run("OpenSSL 3.0.2 15 Mar 2022").value("tool.openssl.version"))
    }

    @Test
    fun pipOutputAndPinsNamePythonPackages() {
        val r = run(
            """
            Collecting virtualenv
              Downloading https://files.example/p/virtualenv-16.7.9-py2.py3-none-any.whl (3.4MB)
            Successfully installed Flask-3.0.2 Jinja2-3.1.3 click-8.1.7
            Requirement already satisfied: numpy in /usr/lib/python3 (1.26.4)
            ${'$'} pip install requests==2.31.0 -r other.txt
            """.trimIndent(),
        )
        assertEquals("16.7.9", r.value("project.python.virtualenv"))
        assertEquals("3.0.2", r.value("project.python.flask"))
        assertEquals("3.1.3", r.value("project.python.jinja2"))
        assertEquals("8.1.7", r.value("project.python.click"))
        assertEquals("1.26.4", r.value("project.python.numpy"))
        assertEquals("2.31.0", r.value("project.python.requests"))
        val none = run("if a == b then x==y and see Flask-3 or the 2.5 release")
        assertTrue(none.facts.none { it.key.startsWith("project.python.") })
    }

    @Test
    fun aBindingNameCarriesItsMajorVersionAndASide() {
        val r = run("It raises an error in PyQt6 but works very well in PyQt5.")
        assertEquals("6", r.value("project.python.pyqt"))
        assertEquals("5", r.value("project.python.pyqt", Side.PASSING))
        assertEquals("6", run("Built against Qt6 and GTK4").value("tool.qt.version"))
        val none = run("QtCore.Qt.DisplayRole and utf8 and sha256 and pyqt66")
        assertTrue(
            none.facts.none {
                it.key == "project.python.pyqt" || it.key == "tool.qt.version"
            },
        )
    }

    @Test
    fun anImageIsReadFromARegistryPathADockerCommandOrADockerUrl() {
        assertEquals(
            listOf("ghcr.io/acme/tool:1.4"),
            values("Using ghcr.io/acme/tool:1.4 in CI", "runtime.container.image"),
        )
        assertEquals(
            listOf("nginx:1.25"),
            values("docker run --rm -p 80:80 -e A=b nginx:1.25 sleep 1", "runtime.container.image"),
        )
        assertEquals(
            listOf("centos"),
            values("docker pull centos@sha256:0123abcd", "runtime.container.image"),
        )
        assertEquals(
            listOf("centos:7"),
            values("skopeo inspect docker://centos:7", "runtime.container.image"),
        )
        val sides = run("`quay.io/org/base:2` works but `quay.io/org/base:3` fails")
        assertEquals("quay.io/org/base:3", sides.value("runtime.container.image"))
        assertEquals("quay.io/org/base:2", sides.value("runtime.container.image", Side.PASSING))
        val newer = run("The newer one (`quay.io/org/base:3`) does not have this problem.")
        assertEquals("quay.io/org/base:3", newer.value("runtime.container.image", Side.PASSING))
        val none = run("see https://github.com/acme/tool and docs.example.com/a/b")
        assertNull(none.fact("runtime.container.image"))
    }

    @Test
    fun anEnvironmentVariableIsReadFromAnEnvBlockAFlagOrAKeywordButNeverASecret() {
        val compose = run(
            """
            services:
              app:
                environment:
                  - PUID=1000
                  - UMASK=002
                  - API_TOKEN=abc123
                  - LEVEL=${'$'}LEVEL_FROM_HOST
                  - REGION_CODE: eu
            """.trimIndent(),
        )
        assertEquals("1000", compose.value("env.PUID"))
        assertEquals("002", compose.value("env.UMASK"))
        assertNull(compose.fact("env.API_TOKEN"))
        assertNull(compose.fact("env.LEVEL"))
        assertEquals("eu", compose.value("env.REGION_CODE"))
        assertEquals("fast", run("docker run -e FOO_MODE=fast img").value("env.FOO_MODE"))
        assertEquals("debug", run("ENV LOG_LEVEL=debug").value("env.LOG_LEVEL"))
        assertEquals("8", run("export HTTP_THREADS=8").value("env.HTTP_THREADS"))
        assertEquals("UTC", run("environment:\n  - TZ=UTC").value("env.TZ"))
        val none =
            run("Environment:\n\nPHP: 8.2\n\nVERSION: 1.2.3\nNAME=value\nexport PASSWORD=hunter2")
        assertTrue(none.facts.none { it.key.startsWith("env.") })
    }

    @Test
    fun aServerPlatformIsReadFromALabelALogLineOrAJarName() {
        val form = run("### Server Version\n\nPaper 1.20.1\n\n### Other\n\nnone")
        assertEquals("paper", form.value("runtime.platform"))
        assertEquals("1.20.1", form.value("tool.paper.version"))
        val label = run("Server Core: Purpur-1.21-2300")
        assertEquals("purpur", label.value("runtime.platform"))
        assertEquals("1.21", label.value("tool.purpur.version"))
        val log = run("This server is running Waterfall version git:Waterfall-Bootstrap:1.20")
        assertEquals("waterfall", log.value("runtime.platform"))
        val named = run("Paper version: 1.21-38-master@1f5db50")
        assertEquals("1.21", named.value("tool.paper.version"))
        val jar = run("at org.bukkit.Foo.bar(Foo.java:1) [spigot-api-1.19-R0.1-SNAPSHOT.jar:?]")
        assertEquals("spigot", jar.value("runtime.platform"))
        assertEquals("1.19", jar.value("tool.spigot.version"))
        val none = run("I wrote a paper about forge and a quilt version of it")
        assertNull(none.fact("runtime.platform"))
        assertNull(run("Server Version: 1.21.6").fact("runtime.platform"))
    }

    @Test
    fun aDockerVersionBlockAndAKernelLineGiveFacts() {
        val block = run(
            """
            Client:
             Version:      20.10.7
             API version:  1.41
            Server:
             Engine:
              Version:     20.10.7
            """.trimIndent(),
        )
        assertEquals("20.10.7", block.value("tool.docker.version"))
        val info =
            run(
                "Server Version: 24.0.5\nStorage Driver: overlay2\n" +
                    "Kernel Version: 5.15.0-1068-azure",
            )
        assertEquals("24.0.5", info.value("tool.docker.version"))
        assertEquals("5.15.0-1068-azure", info.value("kernel.release"))
        assertNull(run("Version: 3.1\nServer Version: 4.2").fact("tool.docker.version"))
    }

    @Test
    fun anErrnoLineNamesTheLimitItReports() {
        assertEquals(
            "exceeded",
            run("write failed: Disk quota exceeded").value("limits.disk-quota"),
        )
        assertEquals("exceeded", run("EMFILE: Too many open files").value("limits.nofile"))
        assertNull(run("the disk is fine and quota is not an issue").fact("limits.disk-quota"))
    }

    @Test
    fun lookalikesOfAToolOrAPackageAreNotRead() {
        val f = "`".repeat(3)
        val http = run("$f\n> POST /v1.30/build\n$f\nPOST 1.30 again")
        assertNull(http.fact("tool.post.version"))
        assertNull(run("curl: (60) SSL certificate problem").fact("tool.curl.version"))
        assertNull(run("$ curl 172.17.0.3").fact("tool.curl.version"))
        assertEquals(
            "8.7.1",
            run("$ curl --version\ncurl 8.7.1 (x86_64)").value("tool.curl.version"),
        )
        assertNull(run("docker run should work").fact("runtime.container.image"))
        assertEquals(
            "alpine",
            run("$f\ndocker run alpine\n$f").value("runtime.container.image"),
        )
        assertTrue(
            run("$f\nif bitness==64:\n$f").facts.none {
                it.key.startsWith("project.python.")
            },
        )
        assertEquals(
            "3.0.2",
            run("$f\nFlask==3.0.2\n$f").value("project.python.flask"),
        )
        assertNull(run("the test expected 0: dotnet failed").fact("tool.dotnet.required"))
        assertNull(run("./run-all.sh 2.5").fact("tool.run-all.sh.version"))
    }

    @Test
    fun aChangedImageOrBindingMakesADraftWhenAnErrorIsQuoted() {
        val image =
            run("`quay.io/a/b:1` works but `quay.io/a/b:2` fails with `Error: layer missing`.")
        val draft = assertNotNull(image.draft)
        assertEquals("runtime.container.image", draft.key)
        assertContains(draft.yaml, "eq: quay.io/a/b:1")
        assertContains(draft.yaml, "eq: quay.io/a/b:2")
        val rule = Drafts.build(
            "triage-image-12345678",
            "runtime.container.image",
            "quay.io/a/b:1",
            "quay.io/a/b:2",
            image.facts.single { it.key == "run:signature" },
            "2026-10-26",
            Status.ACTIVE,
            "12345678abcdef",
        )
        assertEquals(emptyList(), RuleCompiler.validate(rule))
        assertEquals(emptyList(), Constraints.fixtures(rule))
        val binding = run("PyQt5 works but PyQt6 fails with `AttributeError: no attribute foo`.")
        assertEquals("project.python.pyqt", assertNotNull(binding.draft).key)
        val same =
            run("`quay.io/a/b:1` works and `quay.io/a/b:1` fails with `Error: layer missing`.")
        assertNull(same.draft)
    }
    // #endregion

    // #region redaction and users
    @Test
    fun secretsAndUsernamesNeverReachTheResult() {
        val text = """
            token ghp_abcdefghijklmnopqrstuvwxyz0123456789 from @octocat and @Octocat
            see /home/alice/project and /Users/bob/dev, scoped @types/node, mail a@b.example
            $fence
            @Override
            $fence
            Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789
        """.trimIndent()
        val clean = Triage.clean(text)
        assertFalse(clean.contains("ghp_"))
        assertFalse(clean.contains("alice"))
        assertFalse(clean.contains("bob"))
        assertFalse(clean.contains("octocat"))
        assertContains(clean, "@${Triage.hashUser("octocat")}")
        assertContains(clean, "@types/node")
        assertContains(clean, "a@b.example")
        assertContains(clean, "@Override")
        assertEquals(Triage.hashUser("octocat"), Triage.hashUser(" @Octocat "))
        val result = run(text, options = TriageOptions(author = "OctoCat"))
        assertEquals(Triage.hashUser("OctoCat"), result.author)
        assertEquals(11, result.author!!.length)
        val all = CanonicalJson.encode(result.toJson()) + TriageText.render(result)
        assertFalse(all.contains("OctoCat"))
        assertFalse(all.contains("ghp_"))
    }

    @Test
    fun anEnvironmentSecretOnAKeyValueLineStaysOut() {
        val result = run("LANG=en_US.UTF-8\nGITHUB_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789")
        assertEquals("en_US.UTF-8", result.value("env.LANG"))
        assertTrue(result.facts.none { it.value.contains("ghp_") })
    }

    @Test
    fun lineEndingsAndLongLinesAreNormalized() {
        val long = "x".repeat(Triage.MAX_LINE + 50)
        val clean = Triage.clean("a\r\nb\rc\n$long")
        assertEquals(listOf("a", "b", "c", "x".repeat(Triage.MAX_LINE)), clean.split("\n"))
        val big = run("y".repeat(Triage.MAX_BODY + 10))
        assertTrue(big.truncated)
        assertFalse(run("short").truncated)
    }
    // #endregion

    // #region confidence
    @Test
    fun confidenceArithmeticAndText() {
        assertEquals("0.870", Confidence(870).toString())
        assertEquals("1.000", Confidence(1000).toString())
        assertEquals("0.005", Confidence(5).toString())
        assertEquals(Confidence(450), Confidence(900).scale(500))
        assertEquals(Confidence(0), Confidence.any(emptyList()))
        assertEquals(Confidence(950), Confidence.any(listOf(Confidence(950))))
        assertEquals(Confidence(980), Confidence.any(listOf(Confidence(950), Confidence(600))))
        assertEquals(Confidence(990), Confidence.any(listOf(Confidence(1000))))
        assertTrue(Confidence(1) < Confidence(2))
        assertFailsWith<IllegalArgumentException> { Confidence(1001) }
        assertFailsWith<IllegalArgumentException> { Confidence(-1) }
        assertEquals(Confidence(900), Confidence.parse("0.9"))
        assertEquals(Confidence(950), Confidence.parse(".95"))
        assertEquals(Confidence(1000), Confidence.parse("1"))
        assertEquals(Confidence(0), Confidence.parse("0"))
        for (bad in listOf("", ".", "1.5", "2", "0.1234", "a", "-1", "0.9x")) {
            assertNull(Confidence.parse(bad), bad)
        }
    }

    @Test
    fun agreementRaisesAndConflictLowersConfidence() {
        val one = run("Node 20.11.1")
        val two = run("### Node.js version\n\n20.11.1\n\nI am on Node 20.11.1")
        val clash = run("Node 20.11.1 or maybe Node 18.0.0")
        val a = one.fact("tool.node.version")!!
        val b = two.fact("tool.node.version")!!
        assertEquals(700, a.confidence.permille)
        assertEquals(0, a.agreed)
        assertTrue(b.confidence > a.confidence)
        assertTrue(b.agreed >= 1)
        val c = clash.fact("tool.node.version")!!
        assertEquals(1, c.alternatives.size)
        assertTrue(c.confidence.permille < 700)
        val prefix = run("JDK 17 (Temurin 17.0.9)")
        assertEquals("17.0.9", prefix.value("tool.java.version"))
        assertTrue(prefix.fact("tool.java.version")!!.alternatives.isEmpty())
    }

    @Test
    fun hedgingAndBagHeadingsMoveTheBase() {
        assertEquals(490, run("probably Node 20").fact("tool.node.version")!!.confidence.permille)
        assertEquals(
            800,
            run("### Environment\n\nNode 20").fact("tool.node.version")!!.confidence.permille,
        )
    }
    // #endregion

    // #region sides
    @Test
    fun theNearestVerdictWordDecidesTheSide() {
        fun side(text: String, key: String = "tool.node.version") =
            run(text).facts.single { it.key == key }.side
        assertEquals(Side.PASSING, side("It works on Node 18"))
        assertEquals(Side.FAILING, side("It breaks on Node 18"))
        assertEquals(Side.FAILING, side("It does not work on Node 18"))
        assertEquals(Side.PASSING, side("Node 18 works"))
        assertEquals(Side.FAILING, side("Node 18 fails"))
        assertEquals(Side.PASSING, side("Downgraded to Node 18 and it is fine"))
        assertEquals(Side.FAILING, side("Node 18"))
        val both = run("Upgrading from Node 18 to Node 20 breaks it")
        assertEquals("20", both.value("tool.node.version"))
        assertEquals("18", both.value("tool.node.version", Side.PASSING))
        val installed = run("Installed from npm on Node 20")
        assertEquals(Side.FAILING, installed.facts.single { it.key == "tool.node.version" }.side)
        val label = run("### Last working Node version\n\n18.19.0")
        assertEquals("18.19.0", label.value("tool.node.version", Side.PASSING))
        assertNull(label.fact("tool.node.version"))
        val arrow = run("works on Node 18 -> fails on Node 20")
        assertEquals("20", arrow.value("tool.node.version"))
        assertEquals("18", arrow.value("tool.node.version", Side.PASSING))
        val but = run("Node 20 fails. But Node 18 works fine.")
        assertEquals("18", but.value("tool.node.version", Side.PASSING))
    }

    @Test
    fun localAndCiAreTheTwoSidesOfOneKey() {
        val r = run("Passes locally but fails on GitHub Actions (runs-on: ubuntu-24.04).")
        assertEquals("local", r.value("ci.provider", Side.PASSING))
        assertEquals("github-actions", r.value("ci.provider"))
        assertEquals("ubuntu-24.04", r.value("ci.runner.label"))
        for ((phrase, id) in listOf(
            "GitLab CI" to "gitlab-ci",
            "CircleCI" to "circleci",
            "Jenkins" to "jenkins",
            "Azure Pipelines" to "azure-pipelines",
        )) {
            assertEquals(id, run("It fails on $phrase").value("ci.provider"), phrase)
        }
        assertEquals("windows-latest", run("on windows-latest it fails").value("ci.runner.label"))
        assertNull(run("our ubuntu-latestx runner").fact("ci.runner.label"))
    }
    // #endregion

    // #region fields
    @Test
    fun versionNamesNeedAVersionAndIgnoreLookalikes() {
        assertNull(run("go to the settings").fact("tool.go.version"))
        assertNull(run("node-fetch 3.1 and python-dotenv 1.0 and javascript 5").facts.firstOrNull())
        assertNull(run("see 2024-05-01 and build 12345").facts.firstOrNull())
        assertEquals("1.22", run("Go 1.22").value("tool.go.version"))
        assertEquals("1.22.4", run("golang1.22.4").value("tool.go.version"))
        assertEquals("3.12.1", run("python3.12.1").value("tool.python.version"))
        assertEquals("20.11.1", run("Node.js version is v20.11.1").value("tool.node.version"))
        assertEquals("21.0.2", v("openjdk version \"21.0.2\" 2024-01-16", "tool.java.version"))
        assertEquals("1.8.0_391", run("java version \"1.8.0_391\"").value("tool.java.version"))
        assertEquals("1.9.20-rc1", run("Kotlin 1.9.20-rc1").value("tool.kotlin.version"))
        assertEquals("8.5", run("Gradle 8.5").value("tool.gradle.version"))
        assertEquals("2.43.0", run("git version 2.43.0").value("tool.git.version"))
        assertEquals("8.0", run("dotnet 8.0").value("tool.dotnet.version"))
        assertEquals("8.3.4", run("PHP: 8.3.4").value("tool.php.version"))
        assertEquals("gcc", run("gcc 13.2.0").value("tool.cc.family"))
        assertEquals("13.2.0", run("gcc 13.2.0").value("tool.cc.version"))
        assertEquals("clang", run("clang version 17.0.6").value("tool.cc.family"))
        assertEquals("musl", run("musl 1.2.5").value("tool.libc.family"))
        assertEquals("2.35", run("glibc 2.35").value("tool.libc.version"))
        assertNull(run("Kotlin gradle plugin").fact("tool.gradle.version"))
        assertEquals("2.0.0", v("Kotlin Gradle plugin 2.0.0", "project.gradle.kotlin.version"))
        assertNull(run("Kotlin Gradle plugin 2.0.0").fact("tool.kotlin.version"))
        assertEquals("1.20261001.1", run("workerd 1.20261001.1").value("tool.workerd.version"))
        assertEquals("24.0", v("pip 24.0 from /usr/lib/python3/pip", "tool.pip.version"))
        assertEquals("1.79.0", run("rustc 1.79.0").value("tool.rust.version"))
    }

    @Test
    fun operatingSystemsAndArchitectures() {
        assertEquals("macos", run("macOS Sonoma 14.5").value("os.name"))
        assertEquals("14.5", run("macOS Sonoma 14.5").value("os.version"))
        assertEquals("10.15.7", run("Mac OS X 10.15.7").value("os.version"))
        assertEquals("macos", run("Darwin Kernel Version 23.2.0").value("os.name"))
        assertEquals("windows", run("Windows").value("os.name"))
        assertNull(run("Windows").fact("os.version"))
        assertEquals("11", run("win11").value("os.version"))
        assertEquals("ios", run("iOS 17.4").value("os.name"))
        assertEquals("17.4", run("iOS 17.4").value("os.version"))
        assertEquals("android", run("Android 14").value("os.name"))
        assertNull(run("Android Gradle Plugin").fact("os.name"))
        assertEquals("linux", run("on Linux").value("os.name"))
        assertEquals("24.04", run("Ubuntu 24.04.1 LTS").value("os.release.VERSION_ID"))
        assertEquals("3.19.1", run("Alpine 3.19.1").value("os.release.VERSION_ID"))
        assertNull(run("node:20-alpine").fact("os.release.ID"))
        assertNull(run("ubuntu-latest").fact("os.release.ID"))
        for ((text, arch) in listOf(
            "x86-64" to "x86_64",
            "amd64" to "x86_64",
            "x64" to "x86_64",
            "arm64" to "aarch64",
            "Apple M2" to "aarch64",
            "Apple Silicon" to "aarch64",
        )) {
            assertEquals(arch, run("on $text").value("os.arch"), text)
        }
        val uname = run("Linux host 6.5.0-1010-aws #10 SMP x86_64 GNU/Linux")
        assertEquals("linux", uname.value("os.name"))
        assertEquals("x86_64", uname.value("os.arch"))
        val lines = "ID=ubuntu\nVERSION_ID=\"24.04\"\nPRETTY_NAME=\"Ubuntu 24.04.1 LTS\""
        val release = run("$fence\n$lines\n$fence")
        assertEquals("ubuntu", release.value("os.release.ID"))
        assertEquals("24.04", release.value("os.release.VERSION_ID"))
        assertNull(run("ID=widget").fact("os.release.ID"))
        assertNull(run("VERSION_ID=latest").fact("os.release.VERSION_ID"))
    }

    @Test
    fun browsersFromNamesAndUserAgents() {
        val ua = "Mozilla/5.0 (Macintosh) AppleWebKit/605.1.15 Version/17.4 Safari/605.1.15"
        val safari = run(ua)
        assertEquals("safari", safari.value("browser.name"))
        assertEquals("17.4", safari.value("browser.version"))
        val chrome = run("Mozilla/5.0 Chrome/126.0.0.0 Safari/537.36")
        assertEquals("chrome", chrome.value("browser.name"))
        assertEquals("126.0.0.0", chrome.value("browser.version"))
        assertEquals("edge", run("Edg/126.0.2592.81").value("browser.name"))
        assertNull(run("a chrome extension").fact("browser.name"))
        assertEquals("firefox", run("### Browser\n\nFirefox").value("browser.name"))
        assertNull(run("### Browser\n\nFirefox").fact("browser.version"))
    }

    @Test
    fun labelsTablesAndPlaceholders() {
        val bullets = run("- **Node**: 22.4.0\n* OS: Debian 12\n+ npm = 10.8.1\n> Python: 3.12")
        assertEquals("22.4.0", bullets.value("tool.node.version"))
        assertEquals("debian", bullets.value("os.release.ID"))
        assertEquals("3.12", bullets.value("tool.python.version"))
        val rows = "| Name | Value |\n|---|---|\n| Java | 21.0.3 |\n| Arch | arm64 |\n" +
            "| Image | node:20 |"
        val table = run(rows)
        assertEquals("21.0.3", table.value("tool.java.version"))
        assertEquals("aarch64", table.value("os.arch"))
        assertEquals("node:20", table.value("runtime.container.image"))
        assertNull(run("| only |").facts.firstOrNull())
        val blank = "### Node.js version\n\n_No response_\n\n### Operating system\n\nN/A"
        val form = run("$blank\n\n### JDK\n\n")
        assertTrue(form.facts.isEmpty())
        val env = run("export TZ=Europe/Berlin\nLC_ALL=C.UTF-8\nLocale: en_GB.UTF-8")
        assertEquals("Europe/Berlin", env.value("env.TZ"))
        assertEquals("UTC", run("Timezone: UTC").value("env.TZ"))
        assertEquals("C.UTF-8", env.value("env.LC_ALL"))
        assertEquals("en_GB.UTF-8", env.value("env.LANG"))
        assertNull(run("Locale: english").fact("env.LANG"))
        assertTrue(run("see https://example.com/path").facts.isEmpty())
    }

    @Test
    fun containersPlatformsAndFlags() {
        val docker = run("$fence\nFROM --platform=linux/amd64 node:20-alpine AS build\n$fence")
        assertEquals("node:20-alpine", docker.value("runtime.container.image"))
        assertEquals("20", docker.value("tool.node.version"))
        assertEquals(
            "python:3.12",
            v("### Docker image\n\n`python:3.12`", "runtime.container.image"),
        )
        assertEquals("workerd-local", run("using wrangler dev").value("runtime.platform"))
        assertEquals("workerd-local", run("miniflare 3").value("runtime.platform"))
        assertEquals(
            "cloudflare-deployed",
            run("Cloudflare Workers, deployed to production").value("runtime.platform"),
        )
        assertNull(run("Cloudflare Workers").fact("runtime.platform"))
        val flags = "-Xmx2g -Xss1m -XX:+UseZGC -Dapp.mode=test"
        assertEquals(
            flags,
            v("java $flags -Dpassword=hunter2 -jar x.jar", "runtime.jvm.options"),
        )
    }

    @Test
    fun dependenciesFromBuildFilesAndInstallCommands() {
        val gradle = run(
            """
            $fence
            plugins {
                kotlin("jvm") version "1.9.20"
                id("com.android.application") version "8.2.0"
                id("org.jetbrains.kotlin.android") version "2.0.0"
                id("com.example.other") version "9.9.9"
            }
            dependencies {
                api("io.ktor:ktor-client-core:2.3.7")
                api("not:a:version")
            }
            $fence
            """.trimIndent(),
        )
        assertEquals("1.9.20", gradle.value("project.gradle.kotlin.version"))
        assertEquals("8.2.0", gradle.value("project.gradle.agp.version"))
        assertEquals("2.3.7", gradle.value("project.gradle.io.ktor:ktor-client-core"))
        val manifests = run(
            """
            $fence
            {
              "dependencies": {
                "left-pad": "1.3.0",
                "weird": "latest"
              },
              "devDependencies": {"a": "^1.2.3"}
            }
            "version": "9.9.9"
            {
              "require": {
                "php": ">=8.1",
                "drupal/core": "^10.3.6"
              }
            }
            github.com/gorilla/mux v1.8.1
            require golang.org/x/net v0.23.0
            $fence
            yarn add chalk@5.3.0 -D
            pnpm add @scope/pkg@^2.0.0 bun
            """.trimIndent(),
        )
        assertEquals("1.3.0", manifests.value("project.npm.left-pad"))
        assertNull(manifests.fact("project.npm.weird"))
        assertNull(manifests.fact("project.npm.version"))
        assertEquals("10.3.6", manifests.value("project.composer.drupal/core"))
        assertNull(manifests.fact("project.composer.php"))
        assertEquals("1.8.1", manifests.value("project.go.github.com/gorilla/mux"))
        assertEquals("0.23.0", manifests.value("project.go.golang.org/x/net"))
        assertEquals("5.3.0", manifests.value("project.npm.chalk"))
        assertEquals("2.0.0", manifests.value("project.npm.@scope/pkg"))
    }

    @Test
    fun theFirstStrongestErrorLineIsTheSignature() {
        val log = "some note\nwarning: odd\nerror: first problem\nException: later"
        val code = run("see:\n$fence\n$log\n$fence")
        assertEquals("error: first problem", code.value("run:signature"))
        assertEquals(Via.TOOL, code.fact("run:signature")!!.via)
        val soft = run("$fence\nthing was not found here\n$fence")
        assertEquals("thing was not found here", soft.value("run:signature"))
        val prose = run("It prints `TypeError: x is not a function` and stops")
        assertEquals("TypeError: x is not a function", prose.value("run:signature"))
        assertEquals(Via.PATTERN, prose.fact("run:signature")!!.via)
        assertNull(run("$fence\n\$ ls\nall fine\n$fence").fact("run:signature"))
        assertNull(run("a `short` word").fact("run:signature"))
        val works = run("it works fine and then `Error: boom happened`")
        assertEquals(Side.FAILING, works.fact("run:signature")!!.side)
    }

    @Test
    fun spansPointAtTheValue() {
        val r = run("line one\nUse Node 20.11.1 here")
        val f = r.fact("tool.node.version")!!
        assertEquals(Span(2, 10, 7), f.span)
        assertEquals("2:10", f.span.toString())
        assertEquals(0, f.agreed)
    }

    @Test
    fun noInputGivesAnEmptyResult() {
        for (text in listOf("", "   \n\n", "#", "```", "| | |", "***", "@", ":", "=", "\u0000")) {
            val r = run(text)
            assertTrue(r.facts.isEmpty(), text)
            assertNull(r.draft)
        }
        assertContains(TriageText.render(run("")), "Draft rule: none")
    }

    @Test
    fun randomTextNeverThrows() {
        val alphabet = "node 20.1-v:=@/\\|#*`'\"\n\t ()[]<>,._Xx0189javaUbuntuwindowsFROM$-+~~~"
        var seed = 12345L
        repeat(400) {
            val sb = StringBuilder()
            repeat(1 + (seed % 300).toInt()) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L)
                sb.append(alphabet[((seed ushr 33) % alphabet.length).toInt()])
            }
            val text = sb.toString()
            assertEquals(run(text), run(text), text)
        }
    }
    // #endregion

    // #region rules
    @Test
    fun aRuleHoldsOnStatedFactsAtTheTierTheRankerUses() {
        val inferred = rule("demo-inferred", pythonRule)
        val verified = rule("demo-verified", pythonRule, basis = "verified")
        val text = "Worked on Python 3.11.7, now fails on Python 3.12.1."
        val r = run(text, listOf(inferred, verified))
        val hits = r.rules.associateBy { it.ruleId }
        assertEquals(Grade.MATCH, hits.getValue("demo-inferred").grade)
        assertEquals(Tier.PLAUSIBLE, hits.getValue("demo-inferred").tier)
        assertEquals(Tier.KNOWN, hits.getValue("demo-verified").tier)
        assertEquals(listOf("demo-verified", "demo-inferred"), r.rules.map { it.ruleId })
        assertEquals(listOf("tool.python.version"), hits.getValue("demo-inferred").facts)
        assertEquals(700, hits.getValue("demo-inferred").confidence.permille)
        assertEquals(Severity.LOW, hits.getValue("demo-inferred").severity)
        assertEquals(Status.ACTIVE, hits.getValue("demo-inferred").status)
    }

    @Test
    fun anUnstatedPassingSideNeedsTheSymptomText() {
        val bare = rule("demo-bare", pythonRule)
        val named = rule("demo-named", pythonRule, symptom = "ModuleNotFoundError for distutils")
        val text = "Python 3.12.1 raises ModuleNotFoundError when importing."
        val r = run(text, listOf(bare, named))
        assertEquals(listOf("demo-named"), r.rules.map { it.ruleId })
        val hit = r.rules.single()
        assertEquals(Grade.FAILING_SIDE, hit.grade)
        assertNull(hit.tier)
        assertEquals(listOf("ModuleNotFoundError"), hit.symptoms)
        assertTrue(run("Python 3.12.1 is slow", listOf(bare, named)).rules.isEmpty())
    }

    @Test
    fun aRuleWithAnUnstatedFailingConditionIsASymptomMatchAtLowerConfidence() {
        val detect = """{"all":[{"b":"tool.node.version","ver":">=20"},
            |{"b":"project.npm.left-pad","present":true}]}
        """.trimMargin().replace("\n", "")
        val r = rule("demo-symptom", detect, symptom = "fails with ERR_FAKE_CODE")
        val got = run("Node 20.1.0 prints ERR_FAKE_CODE", listOf(r)).rules.single()
        assertEquals(Grade.SYMPTOM, got.grade)
        assertEquals(490, got.confidence.permille)
        assertTrue(run("Node 20.1.0 prints nothing", listOf(r)).rules.isEmpty())
        assertTrue(run("Node 18.1.0 prints ERR_FAKE_CODE", listOf(r)).rules.isEmpty())
    }

    @Test
    fun anyOfNotCrossAndChangedLeavesReadBothSides() {
        val any = rule(
            "demo-any",
            """{"any":[{"b":"tool.node.version","ver":">=99"},
                |{"b":"tool.node.version","ver":">=20"}]}
            """.trimMargin().replace("\n", ""),
        )
        assertEquals(Grade.MATCH, run("Node 20.1.0", listOf(any)).rules.single().grade)
        val not = rule("demo-not", """{"not":{"b":"tool.node.version","ver":"<20"}}""")
        assertTrue(run("Node 20.1.0", listOf(not)).rules.isEmpty())
        assertTrue(run("Node 18.1.0", listOf(not)).rules.isEmpty())
        val cross = rule("demo-cross", """{"cross":"tool.node.version","ver":">=20"}""")
        val crossed = run("Works on Node 18.1.0 and fails on Node 20.1.0", listOf(cross)).rules
        assertEquals(Grade.MATCH, crossed.single().grade)
        assertTrue(run("Node 20.1.0", listOf(cross)).rules.isEmpty())
        assertTrue(run("works on Node 20.1.0, fails on Node 21.0.0", listOf(cross)).rules.isEmpty())
        val changed = rule("demo-changed", """{"changed":"tool.node.version"}""")
        assertEquals(
            Grade.MATCH,
            run("works on Node 18.1.0, fails on Node 20.1.0", listOf(changed)).rules.single().grade,
        )
        val same = run("works on Node 20.1.0, fails on Node 20.1.0", listOf(changed))
        assertTrue(same.rules.isEmpty())
    }

    @Test
    fun theSignatureFactFeedsRunSignatureLeaves() {
        val sig = rule("demo-sig", """{"b":"run:signature","has":"cannot find module"}""")
        val text = "$fence\nError: Cannot find module 'x'\n$fence"
        assertTrue(run(text, listOf(sig)).rules.isEmpty())
        val lower = rule("demo-sig2", """{"b":"run:signature","has":"Cannot find module"}""")
        assertEquals(Grade.MATCH, run(text, listOf(lower)).rules.single().grade)
    }

    @Test
    fun theCatalogLoadsAndAMatchNamesItsFacts() {
        val text = "Worked on Python 3.11.7, fails on Python 3.12.1 with " +
            "ModuleNotFoundError: No module named 'distutils'"
        val hit = run(text, catalog).rules
            .firstOrNull { it.ruleId == "python-3-12-distutils-removed" }
        assertNotNull(hit)
        assertEquals(Grade.MATCH, hit.grade)
        assertEquals(listOf("tool.python.version"), hit.facts)
    }
    // #endregion

    // #region claims
    @Test
    fun aClaimNeedsItsFactsAndAnAffirmingSentence() {
        val java = "Java version: 21\n\nI thought Nashorn was still available in JDK 21."
        val r = run(java, catalog)
        val hit = r.claims.single()
        assertEquals("nashorn-available", hit.id)
        assertEquals("rule:jdk-15-nashorn-removed", hit.source)
        assertEquals(3, hit.line)
        assertTrue(hit.knowledge.contains("JEP 372"))
        assertEquals(
            Claims.BASE * r.fact("tool.java.version")!!.confidence.permille / 1000,
            hit.confidence.permille,
        )
        assertTrue(run("I thought Nashorn was still available", catalog).claims.isEmpty())
        val old = run("Java version: 11\n\nNashorn was still available", catalog)
        assertTrue(old.claims.isEmpty())
        assertTrue(run("Java version: 21\n\nNashorn is not available", catalog).claims.isEmpty())
        assertTrue(run("Java version: 21\n\nNashorn is still available").claims.isEmpty())
        val code = run("Java version: 21\n\n$fence\nNashorn is still available\n$fence", catalog)
        assertTrue(code.claims.isEmpty())
        val long = "Nashorn is still available " + "word ".repeat(60)
        val clipped = run("Java version: 21\n\n$long", catalog).claims.single()
        assertEquals(160, clipped.excerpt.length)
    }

    @Test
    fun everyClaimCitesARealRuleOrAnAtlasProbeNameAndIsReachable() {
        val ids = Claims.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (claim in Claims.all) {
            val ref = claim.source
            if (ref is RuleRef) assertTrue(ref.ruleId in Rules.ids, claim.id)
            assertTrue(claim.phrases.all { it.isNotEmpty() }, claim.id)
            assertTrue(claim.says.isNotBlank(), claim.id)
        }
    }

    @Test
    fun atlasClaimsFireOnPlainStatements() {
        val text = "HashMap iteration order is the same on JVM and native.\n" +
            "Regex is portable between JVM and Wasm targets.\n" +
            "Double toString on Wasm is identical to the JVM."
        val ids = run(text).claims.map { it.id }
        assertEquals(listOf("hash-order-same", "regex-same", "double-tostring-same"), ids)
        assertTrue(run("HashMap iteration order differs between JVM and native").claims.isEmpty())
        val source = run(text).claims.first().source
        assertContains(source, "atlas:kotlin.collections.hash-order")
    }
    // #endregion

    // #region drafts
    private val draftText =
        "Worked on Node 18.19.0, now fails on Node 20.11.1 with `TypeError: bad path`."

    @Test
    fun aReportWithBothSidesAndASignatureSuggestsADraftThatNeverPublishesByDefault() {
        val d = run(draftText).draft
        assertNotNull(d)
        assertEquals(Disposition.DRAFT, d.disposition)
        assertEquals("tool.node.version", d.key)
        assertTrue(d.id.startsWith("triage-tool-node-version-"))
        assertContains(d.yaml, "status: draft")
        assertContains(d.yaml, "kind: report")
        assertContains(d.yaml, "ver: '<=18.19.0'")
        assertContains(d.yaml, "ver: '>=20.11.1'")
        assertTrue(d.notes.any { it.contains("no publish threshold") })
        assertTrue(d.notes.any { it.contains("--date") })
        assertEquals(700, d.confidence.permille)
        val json = d.toJson()
        assertEquals(JsonString("draft"), json.fields.getValue("disposition"))
    }

    @Test
    fun aDraftPublishesOnlyAtTheThresholdWithADateAndAValidRule() {
        val low = Confidence(500)
        val date = "2026-10-13"
        val high = run(draftText, options = TriageOptions(Confidence(900), date = date)).draft!!
        assertEquals(Disposition.DRAFT, high.disposition)
        assertTrue(high.notes.any { it.contains("below the publish threshold") })
        val noDate = run(draftText, options = TriageOptions(low)).draft!!
        assertEquals(Disposition.DRAFT, noDate.disposition)
        val published = run(draftText, options = TriageOptions(low, date = date)).draft!!
        assertEquals(Disposition.PUBLISH, published.disposition)
        assertTrue(published.notes.isEmpty())
        assertContains(published.yaml, "status: pending-scanner")
        assertContains(published.yaml, "verified: '$date'")
        assertFalse(published.yaml.contains("status: draft"))
        val reads = """{"b":"tool.node.version","ver":">=99"}"""
        val readers = listOf(rule("demo-reader", reads, basis = "verified"))
        val active = Triage.run(draftText, TriageOptions(low, date = date), readers).draft!!
        assertContains(active.yaml, "status: active")
    }

    @Test
    fun aPublishedDraftIsAValidRuleWhoseFixturesHold() {
        val facts = run(draftText).facts
        val sig = facts.single { it.key == "run:signature" }
        val rule = Drafts.build(
            "triage-demo-12345678",
            "tool.node.version",
            "18.19.0",
            "20.11.1",
            sig,
            "2026-10-13",
            Status.ACTIVE,
            "12345678abcdef",
        )
        assertEquals(emptyList(), RuleCompiler.validate(rule))
        assertEquals(emptyList(), Constraints.fixtures(rule))
        assertEquals(rule.id, RuleCompiler.compile(rule.toJson(), rule.id).id)
        assertEquals("fixture:triage/12345678", rule.provenance.single().url)
    }

    @Test
    fun noDraftWithoutASignatureARisingVersionOrAnUncoveredKey() {
        assertNull(run("Worked on Node 18.19.0, now fails on Node 20.11.1.").draft)
        val err = "with `TypeError: bad path`."
        assertNull(run("Worked on Node 20.11.1, now fails on Node 18.19.0 $err").draft)
        assertNull(run("Worked on Node 18, now fails on Node 18.0.1 $err").draft)
        assertNull(run("Node 20.11.1 fails with `TypeError: bad path`.").draft)
        val covers = """{"all":[{"a":"tool.node.version","ver":"<20"},
            |{"b":"tool.node.version","ver":">=20"}]}
        """.trimMargin().replace("\n", "")
        val covering = rule("demo-node", covers)
        assertNull(run(draftText, listOf(covering)).draft)
        val alpine = "Worked on Alpine 3.18, now fails on Alpine 3.19 with `Error: x broke`."
        assertNull(run(alpine).draft)
    }

    @Test
    fun theYamlWriterQuotesWhatNeedsQuotes() {
        val doc = JsonObject(
            mapOf(
                "a" to JsonString("plain.value-1"),
                "b" to JsonString("true"),
                "c" to JsonString("it's"),
                "d" to JsonString(""),
                "e" to JsonString("key: value"),
                "f" to JsonObject(emptyMap()),
                "g" to JsonArray(listOf(JsonString("x"), JsonString("y z"))),
                "h" to JsonBool(true),
                "i" to JsonNull,
            ),
        )
        assertEquals(
            "a: plain.value-1\nb: 'true'\nc: 'it''s'\nd: ''\ne: 'key: value'\n" +
                "g:\n  - x\n  - 'y z'\nh: true\ni: null\n",
            Yaml.emit(doc),
        )
    }
    // #endregion

    // #region text
    @Test
    fun theTextShowsFactsRulesClaimsAndTheDraft() {
        val text = """
            Java version: 21

            I thought Nashorn was still available in JDK 21.
            Worked on Node 18.19.0, now fails on Node 20.11.1 with `TypeError: bad path`.
            Node 22.1.0 or Node 21.0.0
        """.trimIndent()
        val out = TriageText.render(run(text, catalog, TriageOptions(author = "someone")))
        assertContains(out, "Facts (")
        assertContains(out, "also read as")
        assertContains(out, "Claims (1)")
        assertContains(out, "Author u")
        assertContains(out, "Draft rule triage-tool-node-version-")
        assertContains(out, "  note: drafts only")
        val trunc = TriageText.render(run("y".repeat(Triage.MAX_BODY + 1)))
        assertContains(trunc, "Only the first ${Triage.MAX_BODY} characters were read")
        val long = TriageText.render(run("Kotlin " + "9".repeat(17) + ".1"))
        assertContains(long, "tool.kotlin.version")
    }
    // #endregion
}

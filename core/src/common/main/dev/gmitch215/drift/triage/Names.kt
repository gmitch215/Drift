package dev.gmitch215.drift.triage

import dev.gmitch215.drift.diff.VersionScheme

/** A tool or runtime whose version has a Drift attribute key; [family] sets a second key. */
internal class VersionField(
    val key: String,
    val names: List<String>,
    val scheme: VersionScheme = VersionScheme.GENERIC,
    val family: Pair<String, String>? = null,
)

/** The alias tables the extractors read; one place to add a tool or a distribution. */
internal object Names {
    val versions: List<VersionField> = listOf(
        VersionField(
            "tool.java.version",
            listOf("java", "jdk", "jre", "openjdk", "temurin", "corretto", "zulu", "jvm"),
            VersionScheme.JAVA,
        ),
        VersionField("tool.kotlin.version", listOf("kotlin", "kotlinc")),
        VersionField(
            "project.gradle.kotlin.version",
            listOf("kotlin gradle plugin", "kotlin-gradle-plugin", "kgp", "kotlin plugin"),
        ),
        VersionField(
            "project.gradle.agp.version",
            listOf("android gradle plugin", "agp", "android plugin"),
        ),
        VersionField("tool.gradle.version", listOf("gradle", "gradlew")),
        VersionField("tool.node.version", listOf("node", "nodejs", "node.js")),
        VersionField("tool.npm.version", listOf("npm")),
        VersionField("tool.python.version", listOf("python", "python3", "cpython")),
        VersionField("tool.php.version", listOf("php")),
        VersionField("tool.go.version", listOf("go", "golang")),
        VersionField("tool.rust.version", listOf("rust", "rustc")),
        VersionField("tool.git.version", listOf("git")),
        VersionField("tool.dotnet.version", listOf("dotnet", ".net")),
        VersionField("tool.workerd.version", listOf("workerd")),
        VersionField("tool.pip.version", listOf("pip")),
        VersionField("tool.cc.version", listOf("gcc", "g++"), family = "tool.cc.family" to "gcc"),
        VersionField("tool.cc.version", listOf("clang"), family = "tool.cc.family" to "clang"),
        VersionField("tool.libc.version", listOf("glibc"), family = "tool.libc.family" to "glibc"),
        VersionField("tool.libc.version", listOf("musl"), family = "tool.libc.family" to "musl"),
    )

    /** Heading and `Label:` words for a field whose value is text, not a tool version. */
    val osLabels = setOf(
        "os",
        "operating system",
        "platform",
        "system",
        "distro",
        "distribution",
        "host os",
        "os version",
        "operating system version",
    )
    val archLabels = setOf("arch", "architecture", "cpu architecture", "cpu", "processor")
    val browserLabels = setOf("browser", "web browser", "browser name", "browsers")
    val ciLabels = setOf("ci", "ci provider", "ci system", "ci service")
    val runnerLabels = setOf("runs on", "runs-on", "runner", "runner label", "runner image")
    val imageLabels = setOf(
        "image",
        "docker image",
        "container image",
        "base image",
        "container",
        "docker",
    )
    val localeLabels = setOf("locale", "lang", "language")
    val timezoneLabels = setOf("timezone", "time zone", "tz")
    val envNames = mapOf(
        "LANG" to "env.LANG",
        "LC_ALL" to "env.LC_ALL",
        "LC_CTYPE" to "env.LC_CTYPE",
        "TZ" to "env.TZ",
    )

    /** Headings that hold a block of environment lines; their lines read as closer to the name. */
    val bagLabels = listOf(
        "environment",
        "system info",
        "system information",
        "versions",
        "configuration",
        "setup",
        "your environment",
        "software",
        "specs",
    )

    val distros = listOf(
        "ubuntu",
        "debian",
        "alpine",
        "fedora",
        "centos",
        "rhel",
        "rocky",
        "almalinux",
        "opensuse",
    )

    /** Words allowed between a name and its version: `Node version is 20`. */
    val gapWords = listOf("version", "ver", "release", "sdk", "runtime", "lts", "is")

    val codenames = listOf(
        "tahoe",
        "sequoia",
        "sonoma",
        "ventura",
        "monterey",
        "big sur",
        "catalina",
    )

    val passing = listOf(
        "worked",
        "works",
        "working",
        "work",
        "fine",
        "passes",
        "passed",
        "passing",
        "previously",
        "previous",
        "before",
        "used to",
        "last good",
        "last known good",
        "older",
        "downgrade",
        "downgraded",
        "rolled back",
        "rollback",
        "pinned",
    )

    val failing = listOf(
        "fails",
        "failed",
        "failing",
        "failure",
        "broken",
        "breaks",
        "broke",
        "crashes",
        "crashed",
        "regression",
        "regressed",
        "stopped",
        "after",
        "since",
        "now",
        "upgraded",
        "upgrade",
        "updated",
    )

    /** Phrases that read as passing words but say the opposite; blanked before the search. */
    val negated = listOf(
        "doesn't work",
        "does not work",
        "doesnt work",
        "not working",
        "no longer works",
        "no longer work",
        "stopped working",
        "isn't working",
        "won't work",
        "can't work",
        "never worked",
        "didn't work",
        "did not work",
    )

    val hedges = listOf(
        "maybe",
        "probably",
        "i think",
        "not sure",
        "afaik",
        "possibly",
        "something like",
        "approximately",
        "i believe",
        "not certain",
        "iirc",
    )

    val clauseBreaks = listOf(". ", "; ", " but ", " however ", " whereas ", " while ", " yet ")

    val local = listOf(
        "locally",
        "on my machine",
        "my machine",
        "my laptop",
        "local machine",
        "local dev",
        "on my mac",
        "on my pc",
    )

    val ci = listOf(
        "github actions" to "github-actions",
        "github-actions" to "github-actions",
        "gitlab ci" to "gitlab-ci",
        "gitlab-ci" to "gitlab-ci",
        "gitlab runner" to "gitlab-ci",
        "circleci" to "circleci",
        "jenkins" to "jenkins",
        "azure pipelines" to "azure-pipelines",
        "travis" to "travis",
        "bitbucket pipelines" to "bitbucket-pipelines",
        "buildkite" to "buildkite",
    )

    val browsers = listOf(
        "chromium" to "chromium",
        "chrome" to "chrome",
        "firefox" to "firefox",
        "safari" to "safari",
        "edge" to "edge",
        "edg" to "edge",
        "opera" to "opera",
        "brave" to "brave",
    )

    val androidTools = listOf("gradle", "studio", "sdk", "plugin", "build", "emulator")

    val placeholders = setOf(
        "_no response_",
        "no response",
        "n/a",
        "na",
        "none",
        "unknown",
        "-",
        "?",
        "tbd",
        "todo",
        "not applicable",
    )
}

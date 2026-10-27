package dev.gmitch215.drift.scan.scanner

/**
 * The tools a project can name and Drift can version. A project name outside this list is never
 * run. The tools in [Scanners.all] are not repeated here; they are versioned on every `--tools`
 * capture.
 */
internal object ToolRegistry {
    val all: List<Scanner> = listOf(
        tool("make", "^GNU Make (\\S+)"),
        tool("cmake", "^cmake version (\\S+)"),
        tool("docker", "^Docker version ([^,\\s]+)"),
        tool("podman", "^podman version (\\S+)"),
        tool("skopeo", "^skopeo version (\\S+)"),
        tool("pwsh", "^PowerShell (\\S+)"),
        tool("native-image", "^native-image (\\S+)"),
        tool("gradle", "^Gradle (\\S+)"),
        tool("mvn", "^Apache Maven (\\S+)"),
        tool("ant", "^Apache Ant\\(TM\\) version (\\S+)", "-version"),
        tool("bazel", "^bazel (\\S+)"),
        tool("cargo", "^cargo (\\S+)"),
        tool("dotnet", "^(\\d+\\.\\d+\\.\\d+\\S*)$"),
        tool("bundle", "^Bundler version (\\S+)"),
        tool("javac", "^javac (\\S+)", "-version"),
        tool("kotlinc", "kotlinc\\S* (\\d\\S*)", "-version"),
        tool("swift", "^Swift version (\\S+)"),
        tool("deno", "^deno (\\S+)"),
        tool("bun", "^(\\d+\\.\\d+\\S*)$"),
        tool("pnpm", "^(\\d+\\.\\d+\\.\\d+\\S*)$"),
        tool("yarn", "^(\\d+\\.\\d+\\.\\d+\\S*)$"),
        tool("pip", "^pip (\\S+)"),
        tool("poetry", "^Poetry \\(version (\\S+?)\\)"),
        tool("uv", "^uv (\\S+)"),
        tool("clang", "clang version (\\S+)"),
        tool("electron", "^v(\\d+\\.\\d+\\.\\d+\\S*)$"),
    )

    private fun tool(name: String, pattern: String, flag: String = "--version") =
        Scanner(name, listOf(name, flag), versionOf(pattern))

    private fun versionOf(pattern: String): (String) -> Map<String, String> {
        val regex = Regex(pattern)
        return { text ->
            text.lineSequence().firstNotNullOfOrNull { regex.find(it) }
                ?.let { mapOf("version" to it.groupValues[1]) }.orEmpty()
        }
    }
}

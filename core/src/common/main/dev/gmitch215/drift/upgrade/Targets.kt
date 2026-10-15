package dev.gmitch215.drift.upgrade

import dev.gmitch215.drift.diff.Version
import dev.gmitch215.drift.diff.VersionScheme

/** One version to move to: [name] as written, the attribute [key] it sets, and the [value]. */
data class Target(val name: String, val key: String, val value: String)

class TargetException(message: String) : Exception(message)

object Targets {
    /** The `--to` names; any other name must be an attribute path (it contains a dot). */
    val aliases: Map<String, String> = mapOf(
        "agp" to "project.gradle.agp.version",
        "composer" to "tool.composer.version",
        "dart" to "tool.dart.version",
        "docker" to "tool.docker.version",
        "drupal" to "project.composer.drupal/core",
        "elixir" to "tool.elixir.version",
        "git" to "tool.git.version",
        "go" to "tool.go.version",
        "gradle" to "project.gradle.wrapper",
        "java" to "tool.java.version",
        "jdk" to "tool.java.version",
        "julia" to "tool.julia.version",
        "kotlin" to "project.gradle.kotlin.version",
        "node" to "tool.node.version",
        "npm" to "tool.npm.version",
        "openssl" to "tool.openssl.version",
        "php" to "tool.php.version",
        "python" to "tool.python.version",
        "r" to "tool.r.version",
        "ruby" to "tool.ruby.version",
        "symfony" to "project.composer.symfony/http-kernel",
        "twig" to "project.composer.twig/twig",
        "tzdata" to "tz.database.version",
    )

    /** Reads `name=version,name=version`; an empty text gives no entries. */
    fun parseList(text: String): List<Pair<String, String>> = text.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { part ->
            val at = part.indexOf('=')
            if (at <= 0 || at == part.length - 1) {
                throw TargetException("expected name=version, found '$part'")
            }
            part.substring(0, at).trim() to part.substring(at + 1).trim()
        }

    /** Reads flat `name: version` lines; blank lines and `#` comments are skipped. */
    fun parseFile(text: String): List<Pair<String, String>> = text.lines()
        .map { it.substringBefore(" #").trimEnd() }
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .map { line ->
            val at = line.indexOf(':')
            if (line[0].isWhitespace() || line[0] == '-' || at <= 0) {
                throw TargetException("expected a flat 'name: version' line, found '$line'")
            }
            line.substring(0, at).trim() to unquote(line.substring(at + 1).trim())
        }

    /**
     * Resolves names to attribute keys. A later entry for the same name replaces an earlier one;
     * two names that reach one key with different values (`java` and `jdk`) are refused.
     */
    fun resolve(entries: List<Pair<String, String>>): List<Target> {
        val byName = LinkedHashMap<String, String>()
        for ((name, value) in entries) byName[name] = value
        val targets = byName.map { (name, value) -> target(name, value) }
        for (t in targets) {
            val clash = targets.firstOrNull { it.key == t.key && it.value != t.value }
            if (clash != null) {
                throw TargetException("${t.name} and ${clash.name} both set ${t.key}")
            }
        }
        return targets.distinctBy { it.key }.sortedBy { it.key }
    }

    private fun target(name: String, value: String): Target {
        val alias = aliases[name.lowercase()]
        if (alias != null) {
            if (Version.parse(value, VersionScheme.of(alias)) == null) {
                throw TargetException("$name=$value is not a version")
            }
            return Target(name, alias, value)
        }
        if ('.' !in name || !name.all(::pathChar)) {
            throw TargetException(
                "unknown target '$name'; use one of ${aliases.keys.joinToString(", ")} " +
                    "or an attribute path such as tool.php.version",
            )
        }
        if (value.isEmpty()) throw TargetException("$name has no value")
        return Target(name, name, value)
    }

    private fun unquote(text: String): String =
        if (text.length >= 2 && text.first() in "'\"" && text.last() == text.first()) {
            text.substring(1, text.length - 1)
        } else {
            text
        }

    private fun pathChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.:-/@"
}

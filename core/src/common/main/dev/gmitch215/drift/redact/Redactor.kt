package dev.gmitch215.drift.redact

import dev.gmitch215.drift.model.Attribute

object Redactor {
    const val MASK = "<redacted>"

    private val secretName =
        Regex(
            "(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH|COOKIE|JWT)",
            RegexOption.IGNORE_CASE,
        )

    // session names not covered by secretName (XAUTHORITY and SSH_AUTH_SOCK match AUTH)
    private val sessionNames = setOf(
        "XDG_SESSION_ID",
        "TMUX",
        "WINDOWID",
        "INVOCATION_ID",
        "JOURNAL_STREAM",
    )

    const val ANON = " (anonymized)"
    private val runtimeUid = Regex("/run/user/\\d+")
    private val dbusGuid = Regex("guid=[0-9A-Fa-f]+")

    /**
     * GitLab prefixes follow the table at https://docs.gitlab.com/security/tokens/#token-prefixes
     * (source: doc/security/tokens/_index.md in gitlab-org/gitlab); npm shows `npm_` in
     * https://docs.npmjs.com/cli/v10/commands/npm-token; the length bounds are Drift's own
     */
    private val secretValues = listOf(
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("gl(pat|oas|dt|rtr?|cbt|ptt|ft|imt|agent|wt|soat|ffct)-[A-Za-z0-9_-]{20,}"),
        Regex("npm_[A-Za-z0-9]{20,}"),
        Regex("pypi-[A-Za-z0-9_-]{20,}"),
        Regex("\\bsk-[A-Za-z0-9_-]{20,}"),
        Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
        Regex("://[^/\\s:@]+:[^/\\s@]+@"),
    )

    private const val OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"

    private val ipv4 = Regex("\\b(?:$OCTET\\.){3}$OCTET\\b")

    // ipv4 is masked before ipv6 so a mapped address leaves no tail
    private val ipv6 = Regex(
        "(?i)" +
            "\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b" +
            "|\\b(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?" +
            "|(?<![\\w:])::(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{1,4}\\b",
    )

    // a bare VER token, so SERVER_IP still counts as an address
    private val versionName =
        Regex("VERSION|RELEASE|BUILD|(?<![A-Z])VER(?![A-Z])", RegexOption.IGNORE_CASE)
    private val versionValue = Regex("^v\\d", RegexOption.IGNORE_CASE)

    fun redactText(text: String): String {
        val secrets = secretValues.fold(text) { acc, re -> re.replace(acc, MASK) }
        return dbusGuid.replace(runtimeUid.replace(secrets, "/run/user/user"), "guid=$MASK")
    }

    // a dotted quad in a version-named env or a v-prefixed value is a version, not an ip
    private fun maskAddresses(name: String, text: String): String {
        val versionShaped = versionName.containsMatchIn(name) || versionValue.containsMatchIn(text)
        val v4 = if (versionShaped) text else ipv4.replace(text) { maskUnlessLoopback(it) }
        return ipv6.replace(v4) { maskUnlessLoopback(it) }
    }

    // loopback names no host, and a proxy setting needs it to reproduce
    private fun maskUnlessLoopback(match: MatchResult): String =
        if (match.value.startsWith("127.") || match.value == "::1") match.value else MASK

    fun redact(attribute: Attribute): Attribute {
        val name = attribute.path.substringAfter("env.", "")
        val masked = when {
            name in sessionNames || secretName.containsMatchIn(name) -> MASK
            name.isEmpty() -> redactText(attribute.value)
            else -> maskAddresses(name, redactText(attribute.value))
        }
        if (masked == attribute.value) return attribute
        val uidAnonymized = masked != MASK && runtimeUid.containsMatchIn(attribute.value)
        val source = if (uidAnonymized) attribute.source + ANON else attribute.source
        return attribute.copy(value = masked, source = source)
    }
}

package dev.gmitch215.drift.cli.serve

internal object ServeText {
	fun hostsFile(windows: Boolean) =
		if (windows) "C:\\Windows\\System32\\drivers\\etc\\hosts" else "/etc/hosts"

	fun hostsLine(domain: String) = "127.0.0.1 $domain"

	fun hostsCommand(domain: String, windows: Boolean) = if (windows) {
		"Add-Content -Path \$env:SystemRoot\\System32\\drivers\\etc\\hosts " +
			"-Value '${hostsLine(domain)}'"
	} else {
		"echo '${hostsLine(domain)}' | sudo tee -a /etc/hosts"
	}

	fun mkcertCommand(names: List<String>): String {
		val quoted = names.joinToString(" ") { if ('*' in it) "\"$it\"" else it }
		return "mkcert -cert-file drift.pem -key-file drift-key.pem $quoted"
	}
}

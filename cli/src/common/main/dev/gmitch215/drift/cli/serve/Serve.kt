package dev.gmitch215.drift.cli.serve

import dev.gmitch215.drift.DRIFT_VERSION
import dev.gmitch215.drift.cli.install.InstallSystem
import dev.gmitch215.drift.cli.install.Layout
import dev.gmitch215.drift.host.Host

internal class ServeOptions(
	val dir: String?,
	val port: Int?,
	val tls: Boolean,
	val cert: String?,
	val key: String?,
	val mkcert: Boolean,
	val domains: List<String>,
	val mdns: Boolean,
	val open: Boolean,
	val bindAll: Boolean,
	val quiet: Boolean,
)

/** Starts the local web server for the Studio build and reports every step that touches trust. */
internal class ServeRunner(
	private val host: Host,
	private val install: InstallSystem,
	private val system: ServeSystem,
	private val say: (String) -> Unit,
	private val warn: (String) -> Unit,
) {
	private val windows = host.os.lowercase().startsWith("windows")
	private val layout = Layout(host.env(), windows)

	fun run(options: ServeOptions) {
		validate(options)
		val dist = findDist(options.dir)
		val tls = tlsFiles(options)
		val site = StaticSite(dist, system.files, HostPolicy(options.domains, options.bindAll))
		val port = options.port ?: if (tls != null) 8443 else 8080
		val acceptor = system.listen(if (options.bindAll) Bind.ALL else Bind.LOOPBACK, port, tls)
		var child: Child? = null
		try {
			val scheme = if (tls != null) "https" else "http"
			if (options.mdns) child = advertise(options, acceptor.port)
			report(options, dist, scheme, acceptor, tls != null)
			if (options.open) open("$scheme://localhost:${acceptor.port}/")
			system.trapInterrupt()
			val log: (String) -> Unit = if (options.quiet) ({ _ -> }) else ({ say(it) })
			Server(system, site, Limits(), log).run(acceptor)
			say("stopped")
		} finally {
			acceptor.close()
			child?.stop()
		}
	}

	private fun validate(options: ServeOptions) {
		if ((options.cert == null) != (options.key == null)) {
			throw ServeFailure("--cert and --key go together", usage = true)
		}
		if (options.mkcert && options.cert != null) {
			throw ServeFailure("--mkcert cannot be combined with --cert and --key", usage = true)
		}
		if (options.tls && options.cert == null && !options.mkcert) {
			throw ServeFailure("--tls needs --cert and --key, or --mkcert", usage = true)
		}
		if (options.port != null && options.port !in 0..65535) {
			throw ServeFailure("--port must be between 0 and 65535", usage = true)
		}
		for (domain in options.domains) {
			val name = domain.removePrefix("*.")
			val labels = name.split('.')
			val ok = labels.all { l ->
				l.isNotEmpty() && l.length <= 63 && !l.startsWith("-") && !l.endsWith("-") &&
					l.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
			}
			if (!ok || labels.size < 2) {
				val message = "--domain expects a name such as drift.local: $domain"
				throw ServeFailure(message, usage = true)
			}
		}
		if (options.mdns) {
			if (windows) throw ServeFailure("--mdns is not supported on Windows", usage = true)
			if (mdnsName(options) == null) {
				throw ServeFailure("--mdns needs a --domain that ends in .local", usage = true)
			}
		}
	}

	private fun findDist(dir: String?): String {
		if (dir != null) {
			return distAt(dir)
				?: throw ServeFailure("no Studio web build in $dir: index.html not found")
		}
		val exe = install.selfPath()?.let { layout.parent(it) }
		val places = listOfNotNull(
			exe?.let { layout.join(it, "studio") },
			exe?.let { layout.parent(it) }?.let {
				layout.join(layout.join(layout.join(it, "share"), "drift"), "studio")
			},
		)
		for (place in places) distAt(place)?.let { return it }
		throw ServeFailure(
			"no Studio web build found; pass --dir <dist> (build one with " +
				"./gradlew :studio:wasmJsBrowserDistribution)" +
				places.joinToString("") { "\nsearched: $it" },
		)
	}

	private fun distAt(dir: String): String? {
		val real = system.files.canonical(dir) ?: return null
		if (!system.files.isDirectory(real)) return null
		val separator = system.files.separator
		system.files.canonical(real.trimEnd(separator) + separator + "index.html") ?: return null
		return real
	}

	private fun tlsFiles(options: ServeOptions): TlsFiles? {
		val wants = options.tls || options.cert != null || options.mkcert
		if (!wants) return null
		system.tlsUnavailable?.let {
			throw ServeFailure(
				"this build cannot serve https: $it; serve http on localhost (a secure context) " +
					"or put a TLS proxy you installed in front of it",
			)
		}
		val cert = options.cert
		val key = options.key
		if (cert != null && key != null) {
			for (file in listOf(cert, key)) {
				if (system.files.canonical(file) == null) throw ServeFailure("cannot read $file")
			}
			if ((system.mode(key) ?: 0) and GROUP_OR_OTHER != 0) {
				warn("$key is readable by other users; run: chmod 600 $key")
			}
			return TlsFiles(cert, key)
		}
		return mkcert(options)
	}

	private fun names(options: ServeOptions) =
		listOf("localhost", "127.0.0.1", "::1") + options.domains

	private fun mkcert(options: ServeOptions): TlsFiles? {
		val names = names(options)
		val root = host.run(listOf("mkcert", "-CAROOT"))
		val caroot = root?.takeIf { it.exitCode == 0 }?.output?.trim()?.lines()?.lastOrNull()
		val files = system.files
		val problem = when {
			caroot.isNullOrEmpty() -> "mkcert was not found on PATH"

			files.canonical(layout.join(caroot, "rootCA.pem")) == null ||
				files.canonical(layout.join(caroot, "rootCA-key.pem")) == null ->
				"mkcert has no local CA in $caroot"

			else -> null
		}
		if (problem != null) return mkcertFallback(problem)
		val configDir = layout.configDir() ?: return mkcertFallback("no home directory")
		val dir = layout.join(configDir, "serve")
		if (!system.makePrivateDirectory(dir)) {
			throw ServeFailure("cannot create $dir")
		}
		val cert = layout.join(dir, "drift.pem")
		val key = layout.join(dir, "drift-key.pem")
		val unsetJava = listOf("env", "-u", "JAVA_HOME")
		val java = if (!windows && "JAVA_HOME" in host.env()) unsetJava else emptyList()
		val made = host.run(java + listOf("mkcert", "-cert-file", cert, "-key-file", key) + names)
		if (made == null || made.exitCode != 0) {
			return mkcertFallback("mkcert failed: ${made?.output?.trim().orEmpty()}")
		}
		if (NOT_INSTALLED in made.output) {
			return mkcertFallback("the mkcert local CA is not installed in the system trust store")
		}
		return TlsFiles(cert, key)
	}

	private fun mkcertFallback(problem: String): TlsFiles? {
		warn("$problem; serving plain http instead")
		say("for https, run once (this edits your system trust store, so drift never runs it):")
		say("  mkcert -install")
		say("then start drift serve --mkcert again (mkcert: https://github.com/FiloSottile/mkcert)")
		return null
	}

	private fun report(
		options: ServeOptions,
		dist: String,
		scheme: String,
		acceptor: Acceptor,
		secure: Boolean,
	) {
		say("drift serve $DRIFT_VERSION: serving $dist")
		say("$scheme://localhost:${acceptor.port}/")
		for (address in acceptor.addresses) say("$scheme://$address/")
		if (options.bindAll) {
			warn(
				"listening on every network interface; anyone who can reach this machine can " +
					"read this build. Only loopback names, IP addresses and --domain names " +
					"get an answer.",
			)
		}
		val resolved = options.domains.filter { '*' !in it }.associateWith { system.resolve(it) }
		for (domain in options.domains) {
			say("$scheme://$domain:${acceptor.port}/ (${resolution(domain, resolved[domain])})")
		}
		val unresolved = resolved.filterValues { it.none(::isLoopback) }.keys
		if (unresolved.isNotEmpty()) {
			val file = ServeText.hostsFile(windows)
			say("to make a name point here, run this yourself (drift never edits $file):")
			for (domain in unresolved) say("  ${ServeText.hostsCommand(domain, windows)}")
		}
		if (options.domains.isNotEmpty() && !secure) {
			say("for https on these names, run: mkcert -install, then")
			say("  ${ServeText.mkcertCommand(names(options))}")
			say("and start drift serve --cert drift.pem --key drift-key.pem")
		}
		say("press Ctrl+C to stop")
	}

	private fun resolution(domain: String, found: List<String>?): String {
		if (found == null) return "wildcard; a hosts file cannot hold wildcards"
		return when {
			found.isEmpty() -> "does not resolve on this machine"
			found.all(::isLoopback) -> "resolves to ${found.joinToString(", ")}"
			else -> "resolves to ${found.joinToString(", ")}, which is not loopback"
		}
	}

	private fun isLoopback(address: String) = address.startsWith("127.") || address == "::1"

	private fun mdnsName(options: ServeOptions) =
		options.domains.firstOrNull { '*' !in it && it.endsWith(".local", ignoreCase = true) }

	private fun advertise(options: ServeOptions, port: Int): Child? {
		val name = mdnsName(options) ?: return null
		val argv = if (host.os.lowercase().startsWith("mac")) {
			listOf("dns-sd", "-P", "Drift Studio", "_http._tcp", "local", "$port", name, LOOPBACK)
		} else {
			listOf("avahi-publish", "-a", "-R", name, LOOPBACK)
		}
		val child = system.start(argv)
		if (child == null) {
			warn("cannot start ${argv.first()}; $name is not advertised")
		} else {
			say("advertising $name over mDNS with ${argv.first()}")
		}
		return child
	}

	private fun open(url: String) {
		val argv = when {
			windows -> listOf("cmd", "/c", "start", "", url)
			host.os.lowercase().startsWith("mac") -> listOf("open", url)
			else -> listOf("xdg-open", url)
		}
		if (host.run(argv)?.exitCode != 0) warn("cannot open a browser; open $url yourself")
	}

	private companion object {
		const val GROUP_OR_OTHER = 63
		const val LOOPBACK = "127.0.0.1"
		const val NOT_INSTALLED = "is not installed in the system trust store"
	}
}

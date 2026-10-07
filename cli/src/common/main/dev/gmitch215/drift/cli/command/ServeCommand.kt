package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.gmitch215.drift.cli.install.InstallSystem
import dev.gmitch215.drift.cli.serve.ServeFailure
import dev.gmitch215.drift.cli.serve.ServeOptions
import dev.gmitch215.drift.cli.serve.ServeRunner
import dev.gmitch215.drift.cli.serve.ServeSystem
import dev.gmitch215.drift.host.Host

class ServeCommand(
	private val host: Host,
	private val install: InstallSystem,
	private val system: ServeSystem,
) : CliktCommand(name = "serve") {
	private val dir by option(
		"--dir",
		help = "Studio web build to serve (a directory with index.html)",
	)
	private val port by option(
		"--port",
		help = "port to listen on; 0 picks a free one (default: 8080, or 8443 with TLS)",
	).int()
	private val tls by option(
		"--tls",
		help = "serve https; needs --cert and --key, or --mkcert",
	).flag()
	private val cert by option("--cert", help = "PEM certificate chain for https")
	private val key by option("--key", help = "PEM private key for https (PKCS#8 on the JVM build)")
	private val mkcert by option(
		"--mkcert",
		help = "https from an existing mkcert local CA; never runs mkcert -install",
	).flag()
	private val domain by option(
		"--domain",
		help = "also answer to this name, such as drift.studio or drift.local; repeatable",
	).multiple()
	private val mdns by option(
		"--mdns",
		help = "advertise the first .local --domain with dns-sd or avahi-publish",
	).flag()
	private val open by option("--open", help = "open the default browser").flag()
	private val unsafeBindAll by option(
		"--unsafe-bind-all",
		help = "listen on every interface, not only loopback",
	).flag()
	private val quiet by option("--quiet", help = "do not print a line per request").flag()

	override fun help(context: Context) =
		"Serve the Studio web build on localhost, read only, bound to the loopback interface"

	override fun run() {
		val options = ServeOptions(
			dir, port, tls, cert, key, mkcert, domain, mdns, open, unsafeBindAll, quiet,
		)
		val runner = ServeRunner(
			host,
			install,
			system,
			{ echo(it) },
			{ echo("warning: $it", err = true) },
		)
		try {
			runner.run(options)
		} catch (e: ServeFailure) {
			echo("error: ${e.message}", err = true)
			throw ProgramResult(if (e.usage) 2 else 1)
		}
	}
}

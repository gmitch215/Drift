package dev.gmitch215.drift.cli.serve

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.install.FakeInstallSystem
import dev.gmitch215.drift.cli.test
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeCommandTest {
	private val tree = FakeFiles().apply {
		put("/dist/index.html", "<title>Drift Studio</title>")
		put("/opt/drift/share/drift/studio/index.html", "<title>Drift Studio</title>")
		put("/home/u/.config/drift/serve/.keep", "")
		put("/ca/rootCA.pem", "ca")
		put("/ca/rootCA-key.pem", "key")
		put("/keys/cert.pem", "c")
		put("/keys/key.pem", "k")
	}

	private fun host(
		commands: Map<List<String>, CommandResult> = emptyMap(),
		os: String = "linux",
		canRun: Boolean = true,
	) = FakeHost(
		os = os,
		env = mapOf("HOME" to "/home/u"),
		commands = commands,
		canRun = canRun,
	)

	private fun serve(
		args: String,
		system: FakeServeSystem = FakeServeSystem(tree),
		host: FakeHost = host(),
		self: String? = "/opt/drift/bin/drift",
	): CliktCommandTestResult {
		val install = FakeInstallSystem(self = self)
		return DriftCommand(host, install = install, serve = system).test("serve $args")
	}

	@Test
	fun serveDefaultsToPlainHttpOnLoopbackAndPrintsEveryUrl() {
		val system = FakeServeSystem(tree)
		val result = serve("--dir /dist", system)
		assertEquals(0, result.statusCode, result.stderr)
		val expected = listOf(Triple<Bind, Int, TlsFiles?>(Bind.LOOPBACK, 8080, null))
		assertEquals(expected, system.listened)
		assertTrue("http://localhost:8080/" in result.stdout, result.stdout)
		assertTrue("http://127.0.0.1:8080/" in result.stdout)
		assertTrue("http://[::1]:8080/" in result.stdout)
		assertTrue("press Ctrl+C to stop" in result.stdout)
		assertTrue(result.stdout.trimEnd().endsWith("stopped"), result.stdout)
		assertEquals(1, system.trapped)
		assertTrue((system.acceptor?.closed ?: 0) >= 1)
	}

	@Test
	fun portZeroIsPassedThroughAndTheRealPortIsPrinted() {
		val system = FakeServeSystem(tree).apply { hostPort = 49152 }
		val result = serve("--dir /dist --port 0", system)
		assertEquals(0, system.listened.single().second)
		assertTrue("http://localhost:49152/" in result.stdout, result.stdout)
	}

	@Test
	fun withoutDirItLooksNextToTheExecutable() {
		val result = serve("")
		assertEquals(0, result.statusCode, result.stderr)
		assertTrue("serving /opt/drift/share/drift/studio" in result.stdout, result.stdout)
	}

	@Test
	fun aStudioDirectoryBesideTheExecutableWinsOverTheShareDirectory() {
		tree.put("/opt/drift/bin/studio/index.html", "x")
		val result = serve("")
		assertTrue("serving /opt/drift/bin/studio" in result.stdout, result.stdout)
	}

	@Test
	fun noDistIsAClearErrorThatNamesWhereItLooked() {
		val result = serve("", self = "/usr/local/bin/drift")
		assertEquals(1, result.statusCode)
		assertTrue("no Studio web build found" in result.stderr, result.stderr)
		assertTrue("searched: /usr/local/bin/studio" in result.stderr)
		assertTrue("searched: /usr/local/share/drift/studio" in result.stderr)
		assertTrue(":studio:wasmJsBrowserDistribution" in result.stderr)
		val explicit = serve("--dir /nowhere")
		assertEquals(1, explicit.statusCode)
		assertTrue("no Studio web build in /nowhere: index.html not found" in explicit.stderr)
		val file = serve("--dir /dist/index.html")
		assertEquals(1, file.statusCode)
	}

	@Test
	fun aBuildThatCannotServeSaysSo() {
		val result = DriftCommand(host(), install = FakeInstallSystem(), serve = UnsupportedServe)
			.test("serve --dir /dist")
		assertEquals(1, result.statusCode)
		assertTrue("error:" in result.stderr)
	}

	@Test
	fun usageErrorsExitWithTwo() {
		val cases = mapOf(
			"--tls" to "--tls needs --cert and --key, or --mkcert",
			"--cert /keys/cert.pem" to "--cert and --key go together",
			"--key /keys/key.pem" to "--cert and --key go together",
			"--mkcert --cert /keys/cert.pem --key /keys/key.pem" to "--mkcert cannot be combined",
			"--port 70000" to "--port must be between 0 and 65535",
			"--port -1" to "--port must be between 0 and 65535",
			"--domain drift" to "--domain expects a name",
			"--domain bad_name.local" to "--domain expects a name",
			"--domain drift.local:80" to "--domain expects a name",
			"--domain -x.local" to "--domain expects a name",
			"--mdns" to "--mdns needs a --domain that ends in .local",
			"--mdns --domain drift.studio" to "--mdns needs a --domain that ends in .local",
			"--mdns --domain *.drift.local" to "--mdns needs a --domain that ends in .local",
		)
		for ((args, message) in cases) {
			val result = serve("--dir /dist $args")
			assertEquals(2, result.statusCode, args)
			assertTrue(message in result.stderr, "$args: ${result.stderr}")
		}
	}

	@Test
	fun mdnsIsRefusedOnWindows() {
		val result = serve("--dir /dist --domain drift.local --mdns", host = host(os = "windows"))
		assertEquals(2, result.statusCode)
		assertTrue("--mdns is not supported on Windows" in result.stderr)
	}

	@Test
	fun unsafeBindAllBindsEveryInterfaceAndWarns() {
		val system = FakeServeSystem(tree)
		val result = serve("--dir /dist --unsafe-bind-all", system)
		assertEquals(Bind.ALL, system.listened.single().first)
		assertTrue("warning: listening on every network interface" in result.stderr, result.stderr)
		val normal = serve("--dir /dist")
		assertTrue("warning" !in normal.stderr)
	}

	@Test
	fun aListenFailureIsAnError() {
		val result = serve("--dir /dist", FakeServeSystem(tree, failListen = "port 8080 is in use"))
		assertEquals(1, result.statusCode)
		assertTrue("error: port 8080 is in use" in result.stderr)
	}

	@Test
	fun certAndKeyServeHttpsOnTheTlsPortAndWarnAboutALooseKey() {
		val system = FakeServeSystem(tree).apply { modes["/keys/key.pem"] = 420 }
		val result = serve("--dir /dist --cert /keys/cert.pem --key /keys/key.pem", system)
		assertEquals(0, result.statusCode, result.stderr)
		val (bind, port, tls) = system.listened.single()
		assertEquals(Bind.LOOPBACK, bind)
		assertEquals(8443, port)
		assertEquals("/keys/cert.pem", tls?.cert)
		assertEquals("/keys/key.pem", tls?.key)
		assertTrue("https://localhost:8443/" in result.stdout, result.stdout)
		val loose = "/keys/key.pem is readable by other users; run: chmod 600 /keys/key.pem"
		assertTrue(loose in result.stderr)
		val strict = FakeServeSystem(tree).apply { modes["/keys/key.pem"] = 384 }
		val again = serve("--dir /dist --cert /keys/cert.pem --key /keys/key.pem", strict)
		assertEquals("", again.stderr)
	}

	@Test
	fun aMissingCertOrKeyFileIsAnError() {
		val result = serve("--dir /dist --cert /keys/cert.pem --key /keys/gone.pem")
		assertEquals(1, result.statusCode)
		assertTrue("cannot read /keys/gone.pem" in result.stderr)
	}

	@Test
	fun httpsOnABuildWithoutTlsIsRefusedNotDowngraded() {
		val system = FakeServeSystem(tree, tlsUnavailable = "no OpenSSL here")
		for (flags in listOf("--cert /keys/cert.pem --key /keys/key.pem", "--mkcert")) {
			val result = serve("--dir /dist $flags", system)
			assertEquals(1, result.statusCode, flags)
			val message = "this build cannot serve https: no OpenSSL here"
			assertTrue(message in result.stderr, result.stderr)
		}
		assertTrue(system.listened.isEmpty())
	}

	private val caroot = listOf("mkcert", "-CAROOT")
	private val certFile = "/home/u/.config/drift/serve/drift.pem"
	private val keyFile = "/home/u/.config/drift/serve/drift-key.pem"
	private val generate = listOf("mkcert", "-cert-file", certFile, "-key-file", keyFile)

	@Test
	fun mkcertWithAnInstalledCaGeneratesACertificateAndServesHttps() {
		val names = listOf("localhost", "127.0.0.1", "::1", "drift.studio")
		val commands = mapOf(
			caroot to CommandResult(0, "/ca\n"),
			generate + names to CommandResult(0, "Created a new certificate\n"),
		)
		val system = FakeServeSystem(tree)
		val result = serve("--dir /dist --mkcert --domain drift.studio", system, host(commands))
		assertEquals(0, result.statusCode, result.stderr)
		val tls = system.listened.single().third
		assertEquals(certFile, tls?.cert)
		assertEquals(keyFile, tls?.key)
		assertEquals(listOf("/home/u/.config/drift/serve"), system.directories)
		assertTrue("https://localhost:8443/" in result.stdout)
		assertEquals("", result.stderr)
	}

	@Test
	fun mkcertRunsWithoutJavaHomeBecauseItFailsOnAJdkWithoutCacerts() {
		val names = listOf("localhost", "127.0.0.1", "::1")
		val commands = mapOf(
			caroot to CommandResult(0, "/ca\n"),
			listOf("env", "-u", "JAVA_HOME") + generate + names to CommandResult(0, "ok\n"),
		)
		val env = mapOf("HOME" to "/home/u", "JAVA_HOME" to "/jdk")
		val withJava = FakeHost(env = env, commands = commands)
		val system = FakeServeSystem(tree)
		val result = serve("--dir /dist --mkcert", system, withJava)
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals(certFile, system.listened.single().third?.cert)
	}

	@Test
	fun mkcertWithoutACaOrBinaryOrInstallationFallsBackToHttpAndPrintsTheCommands() {
		val names = listOf("localhost", "127.0.0.1", "::1")
		val notInstalled = "Note: the local CA is not installed in the system trust store.\n"
		val cases = mapOf(
			"mkcert was not found on PATH" to host(canRun = false),
			"mkcert has no local CA in /empty" to
				host(mapOf(caroot to CommandResult(0, "/empty\n"))),
			"the mkcert local CA is not installed in the system trust store" to host(
				mapOf(
					caroot to CommandResult(0, "/ca\n"),
					generate + names to CommandResult(0, notInstalled),
				),
			),
			"mkcert failed: boom" to host(
				mapOf(
					caroot to CommandResult(0, "/ca\n"),
					generate + names to CommandResult(1, "boom\n"),
				),
			),
		)
		for ((problem, h) in cases) {
			val system = FakeServeSystem(tree)
			val result = serve("--dir /dist --mkcert", system, h)
			assertEquals(0, result.statusCode, problem)
			val warning = "warning: $problem; serving plain http instead"
			assertTrue(warning in result.stderr, result.stderr)
			assertTrue("  mkcert -install" in result.stdout, result.stdout)
			assertNull(system.listened.single().third, problem)
			assertEquals(8080, system.listened.single().second)
			assertTrue("http://localhost:8080/" in result.stdout)
		}
	}

	@Test
	fun domainsReportWhetherTheyResolveAndPrintTheHostsCommandOnlyWhenNeeded() {
		val system = FakeServeSystem(tree).apply {
			names["drift.local"] = listOf("127.0.0.1")
			names["other.test"] = listOf("10.1.2.3")
		}
		val result = serve(
			"--dir /dist --domain drift.studio --domain drift.local --domain other.test " +
				"--domain *.dev.local",
			system,
		)
		val out = result.stdout
		assertTrue("http://drift.studio:8080/ (does not resolve on this machine)" in out, out)
		assertTrue("http://drift.local:8080/ (resolves to 127.0.0.1)" in out, out)
		val other = "http://other.test:8080/ (resolves to 10.1.2.3, which is not loopback)"
		assertTrue(other in out, out)
		val wild = "http://*.dev.local:8080/ (wildcard; a hosts file cannot hold wildcards)"
		assertTrue(wild in out, out)
		assertTrue("echo '127.0.0.1 drift.studio' | sudo tee -a /etc/hosts" in out, out)
		assertTrue("echo '127.0.0.1 other.test' | sudo tee -a /etc/hosts" in out, out)
		assertTrue("127.0.0.1 drift.local'" !in out, out)
		assertTrue("drift never edits /etc/hosts" in out)
		val make = "mkcert -cert-file drift.pem -key-file drift-key.pem localhost 127.0.0.1 ::1"
		assertTrue(make in out)
		assertTrue("\"*.dev.local\"" in out)
	}

	@Test
	fun resolvedDomainsNeedNoHostsLineAndHttpsNeedsNoMkcertHint() {
		val system = FakeServeSystem(tree).apply { names["drift.local"] = listOf("::1") }
		val result = serve(
			"--dir /dist --domain drift.local --cert /keys/cert.pem --key /keys/key.pem",
			system,
		)
		assertTrue("hosts" !in result.stdout, result.stdout)
		assertTrue("mkcert" !in result.stdout, result.stdout)
		assertTrue("https://drift.local:8443/ (resolves to ::1)" in result.stdout)
	}

	@Test
	fun windowsGetsAPowerShellHostsCommand() {
		assertEquals(
			"Add-Content -Path \$env:SystemRoot\\System32\\drivers\\etc\\hosts " +
				"-Value '127.0.0.1 a.test'",
			ServeText.hostsCommand("a.test", windows = true),
		)
		assertEquals("C:\\Windows\\System32\\drivers\\etc\\hosts", ServeText.hostsFile(true))
	}

	@Test
	fun openLaunchesTheDefaultBrowserPerPlatform() {
		val urls = mapOf(
			"mac os x" to listOf("open", "http://localhost:8080/"),
			"linux" to listOf("xdg-open", "http://localhost:8080/"),
			"windows 11" to listOf("cmd", "/c", "start", "", "http://localhost:8080/"),
		)
		for ((os, argv) in urls) {
			val h = host(mapOf(argv to CommandResult(0, "")), os = os)
			val result = serve("--dir /dist --open", host = h)
			assertEquals(argv, h.ran.single(), os)
			assertEquals("", result.stderr, os)
		}
		val none = serve("--dir /dist --open", host = host())
		val warning = "warning: cannot open a browser; open http://localhost:8080/ yourself"
		assertTrue(warning in none.stderr)
	}

	@Test
	fun noOpenMeansNoBrowser() {
		val h = host()
		serve("--dir /dist", host = h)
		assertTrue(h.ran.isEmpty())
	}

	@Test
	fun mdnsStartsTheAdvertiserAndStopsItAfterwards() {
		val mac = FakeServeSystem(tree)
		val result = serve("--dir /dist --domain drift.local --mdns", mac, host(os = "mac os x"))
		assertEquals(
			listOf(
				listOf(
					"dns-sd",
					"-P",
					"Drift Studio",
					"_http._tcp",
					"local",
					"8080",
					"drift.local",
					"127.0.0.1",
				),
			),
			mac.started,
		)
		assertEquals(1, mac.children.single().stopped)
		assertTrue("advertising drift.local over mDNS with dns-sd" in result.stdout, result.stdout)
		val linux = FakeServeSystem(tree)
		serve("--dir /dist --domain drift.local --mdns", linux)
		val avahi = listOf("avahi-publish", "-a", "-R", "drift.local", "127.0.0.1")
		assertEquals(listOf(avahi), linux.started)
	}

	@Test
	fun mdnsThatCannotStartOnlyWarns() {
		val system = FakeServeSystem(tree).apply { startFails = true }
		val result = serve("--dir /dist --domain drift.local --mdns", system)
		assertEquals(0, result.statusCode)
		val warning = "warning: cannot start avahi-publish; drift.local is not advertised"
		assertTrue(warning in result.stderr)
	}

	@Test
	fun quietSuppressesTheRequestLog() {
		val request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
		val loud = serve("--dir /dist", FakeServeSystem(tree, connections = listOf(request)))
		assertTrue("GET / 200 27" in loud.stdout, loud.stdout)
		val system = FakeServeSystem(tree, connections = listOf(request))
		val quiet = serve("--dir /dist --quiet", system)
		assertTrue("GET / 200" !in quiet.stdout, quiet.stdout)
	}

	@Test
	fun helpListsEveryOption() {
		val result = DriftCommand(host(), install = FakeInstallSystem()).test("serve --help")
		val flags = "--dir --port --tls --cert --key --mkcert --domain --mdns --open " +
			"--unsafe-bind-all --quiet"
		for (flag in flags.split(" ")) assertTrue(flag in result.stdout, flag)
	}
}

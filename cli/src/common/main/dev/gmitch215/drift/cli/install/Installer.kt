package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.DRIFT_VERSION
import dev.gmitch215.drift.host.Host

internal class InstallFailure(message: String, val usage: Boolean = false) : Exception(message)

internal class InstallOptions(
	val scope: Scope?,
	val dir: String?,
	val modifyPath: Boolean,
	val dryRun: Boolean,
)

/** Copies the running executable into place and reverses exactly what it recorded. */
internal class Installer(
	private val host: Host,
	private val system: InstallSystem,
	private val say: (String) -> Unit,
	private val warn: (String) -> Unit,
) {
	private val env = host.env()
	private val layout = Layout(env, host.os.lowercase().startsWith("windows"))
	private var dry = false
	private val inUse = mutableListOf<String>()

	fun install(options: InstallOptions) {
		dry = options.dryRun
		val self = system.selfPath() ?: throw InstallFailure(
			if (host.platform == "jvm") {
				"the JVM distribution cannot install itself; install the native executable"
			} else {
				"cannot locate the running executable"
			},
			usage = true,
		)
		val bytes = system.read(self) ?: throw InstallFailure("cannot read $self")
		val scope = options.scope ?: if (system.isPrivileged()) Scope.GLOBAL else Scope.USER
		val explicit = options.dir
		if (explicit != null && !layout.isAbsolute(explicit)) {
			throw InstallFailure("--dir must be an absolute path: $explicit", usage = true)
		}
		if (explicit != null && explicit.any { it == '\n' || it == '\r' }) {
			throw InstallFailure("--dir cannot contain a line break", usage = true)
		}
		val dir = layout.clean(
			explicit ?: if (scope == Scope.GLOBAL) {
				layout.globalDir()
			} else {
				layout.userDir() ?: throw InstallFailure(noHome())
			},
		)
		val target = layout.join(dir, layout.binaryName)
		val configDir = layout.configDir() ?: throw InstallFailure(noHome())
		val receiptPath = layout.join(configDir, Receipt.FILE)
		checkWritable(dir, scope, self, options)
		val previous = loadReceipt(receiptPath)
		if (previous != null &&
			(!layout.sameDirectory(previous.directory, dir) || previous.scope != scope)
		) {
			throw InstallFailure(
				"drift is already installed in ${previous.directory} " +
					"(${previous.scope.name.lowercase()}); run drift uninstall first",
			)
		}
		say(if (dry) "dry run: nothing is changed" else "installing drift $DRIFT_VERSION")
		say("scope: ${scope.name.lowercase()}")
		val created = previous?.createdDirectories.orEmpty().toMutableList()
		val leftovers = previous?.leftovers.orEmpty().toMutableList()
		val edits = previous?.edits.orEmpty().toMutableList()
		var placed = false
		val advice: String?
		try {
			makeDirectories(dir, created)
			val existing = system.read(target)
			if (existing != null && existing.contentEquals(bytes)) {
				say("$target is up to date")
			} else {
				val mode = if (layout.windows) "" else "mode 0755, "
				step("write $target ($mode${bytes.size} bytes)") {
					placeBinary(target, bytes, leftovers)
				}
			}
			placed = true
			makeDirectories(configDir, created)
			advice = pathStep(options, dir, scope, edits, created)
		} catch (e: InstallFailure) {
			if (placed) {
				try {
					writeReceipt(receiptPath, scope, dir, target, created, leftovers, edits)
				} catch (_: InstallFailure) {
					warn("could not write the install receipt at $receiptPath")
				}
			}
			throw e
		}
		writeReceipt(receiptPath, scope, dir, target, created, leftovers, edits)
		if (!dry) say("installed drift $DRIFT_VERSION to $target")
		if (advice != null) say(advice)
	}

	fun uninstall(dryRun: Boolean) {
		dry = dryRun
		val configDir = layout.configDir() ?: throw InstallFailure(noHome())
		val receiptPath = layout.join(configDir, Receipt.FILE)
		val receipt = loadReceipt(receiptPath) ?: run {
			say("no install receipt at $receiptPath; nothing removed")
			return
		}
		say(if (dry) "dry run: nothing is changed" else "uninstalling drift ${receipt.version}")
		var left = 0
		for (edit in receipt.edits.reversed()) {
			left += when (edit) {
				is ProfileEdit -> removeProfile(edit)
				is RegistryEdit -> removeRegistry(edit)
			}
		}
		val stale = if (layout.windows) listOf(layout.oldName(receipt.binary)) else emptyList()
		for (path in (receipt.leftovers + stale).distinct()) {
			if (system.exists(path)) {
				if (!dry && !system.remove(path)) {
					warn("cannot remove $path; delete it by hand")
					left++
				} else {
					say("remove $path")
				}
			}
		}
		left += removeBinary(receipt.binary)
		if (left > 0) {
			throw InstallFailure(
				"$left item(s) were not removed; the receipt at $receiptPath is kept",
			)
		}
		step("remove $receiptPath") { system.remove(receiptPath) }
		val directories = receipt.createdDirectories.reversed()
		for (path in directories) {
			if (system.isDirectory(path)) {
				if (dry) {
					say("remove directory $path if it is empty")
				} else if (system.removeDirectory(path)) {
					say("remove directory $path")
				} else {
					say("keep directory $path (not empty)")
				}
			}
		}
		if (inUse.isNotEmpty()) {
			if (system.removeAfterExit(inUse, directories.filter { system.isDirectory(it) })) {
				say("remove ${inUse.joinToString()} when this process exits")
			} else {
				inUse.forEach { warn("$it is in use; delete it after this process exits") }
			}
		}
		if (!dry) say("uninstalled drift")
	}

	private fun noHome() = if (layout.windows) {
		"cannot find the profile directory: LOCALAPPDATA is not set"
	} else {
		"cannot find the home directory: HOME is not set"
	}

	private fun step(text: String, action: () -> Boolean) {
		say(text)
		if (!dry && !action()) throw InstallFailure("failed: ${text.lineSequence().first()}")
	}

	private fun loadReceipt(path: String): Receipt? {
		val bytes = system.read(path) ?: return null
		return try {
			Receipt.parse(bytes.decodeToString())
		} catch (e: Exception) {
			throw InstallFailure(
				"cannot read the install receipt at $path (${e.message}); nothing changed",
			)
		}
	}

	private fun checkWritable(dir: String, scope: Scope, self: String, options: InstallOptions) {
		var existing: String? = dir
		while (existing != null && !system.isDirectory(existing)) existing = layout.parent(existing)
		val flags = buildString {
			append(" --global".takeIf { scope == Scope.GLOBAL }.orEmpty())
			if (options.dir != null) append(" --dir \"${options.dir}\"")
			if (!options.modifyPath) append(" --no-modify-path")
		}
		val writable = existing != null && system.canWrite(existing) &&
			(scope == Scope.USER || !layout.windows || system.isPrivileged())
		if (writable) return
		val where = existing ?: dir
		val advice = when {
			scope == Scope.USER -> ""
			layout.windows -> "; run this from an elevated prompt: \"$self\" install$flags"
			else -> "; run: sudo \"$self\" install$flags"
		}
		throw InstallFailure("cannot write to $where$advice")
	}

	private fun makeDirectories(path: String, created: MutableList<String>) {
		val missing = mutableListOf<String>()
		var at: String? = path
		while (at != null && !system.isDirectory(at)) {
			missing += at
			at = layout.parent(at)
		}
		for (dir in missing.reversed()) {
			if (dir in created) continue
			step("create directory $dir") { system.makeDirectory(dir) }
			created += dir
		}
	}

	private fun placeBinary(
		target: String,
		bytes: ByteArray,
		leftovers: MutableList<String>,
	): Boolean {
		val staged = "$target.new"
		if (layout.windows) system.remove(layout.oldName(target))
		if (!system.write(staged, bytes, executable = true)) {
			system.remove(staged)
			return false
		}
		if (system.rename(staged, target)) return true
		if (layout.windows) {
			val old = layout.oldName(target)
			system.remove(old)
			if (system.rename(target, old)) {
				if (system.rename(staged, target)) {
					if (old !in leftovers) leftovers += old
					return true
				}
				system.rename(old, target)
			}
		}
		system.remove(staged)
		return false
	}

	private fun pathStep(
		options: InstallOptions,
		dir: String,
		scope: Scope,
		edits: MutableList<PathEdit>,
		created: MutableList<String>,
	): String? {
		if (!options.modifyPath) {
			say("PATH is not modified")
			return null
		}
		if (layout.onPath(layout.env("PATH").orEmpty(), dir)) {
			say("$dir is already on PATH")
			return null
		}
		if (layout.windows) return registryStep(dir, scope, edits)
		if (scope == Scope.GLOBAL) {
			say("$dir is not on PATH; add it for the users who need it")
			return null
		}
		return profileSteps(dir, edits, created)
	}

	private fun profileSteps(
		dir: String,
		edits: MutableList<PathEdit>,
		created: MutableList<String>,
	): String? {
		val home = layout.home() ?: throw InstallFailure(noHome())
		var first: String? = null
		for ((path, fish) in profileTargets(home)) {
			val existed = system.exists(path)
			val raw = if (existed) system.read(path) else ByteArray(0)
			val text = raw?.decodeToString()
			if (raw == null || !text.orEmpty().encodeToByteArray().contentEquals(raw)) {
				warn("$path cannot be read as UTF-8; leaving it alone")
				continue
			}
			val recorded = edits.filterIsInstance<ProfileEdit>().firstOrNull { it.path == path }
			if (ProfileBlock.has(text.orEmpty())) {
				if (recorded == null) {
					warn("$path already has a drift block that this install did not write")
				} else {
					say("$path already has the drift block")
				}
				continue
			}
			val block = if (fish) ProfileBlock.fish(dir, home) else ProfileBlock.sh(dir, home)
			layout.parent(path)?.let { makeDirectories(it, created) }
			val (updated, lead) = ProfileBlock.append(text.orEmpty(), block)
			val note = if (existed) "" else " (new file)"
			val shown = block.trimEnd('\n').lines().joinToString("\n") { "  $it" }
			step("append to $path$note:\n$shown") {
				system.write(path, updated.encodeToByteArray())
			}
			edits.removeAll { it is ProfileEdit && it.path == path }
			edits += ProfileEdit(path, block, !existed, lead)
			if (first == null && !fish) first = path
		}
		return if (first != null) {
			"restart your shell or run: . \"$first\""
		} else if (edits.any { it is ProfileEdit }) {
			"restart your shell for the PATH change to apply"
		} else {
			null
		}
	}

	private fun profileTargets(home: String): List<Pair<String, Boolean>> {
		val shell = layout.env("SHELL").orEmpty().substringAfterLast('/')
		val targets = mutableListOf<Pair<String, Boolean>>()
		for (name in listOf(".profile", ".bash_profile", ".bashrc")) {
			layout.join(home, name).takeIf { system.exists(it) }?.let { targets += it to false }
		}
		val zdot = layout.env("ZDOTDIR")?.takeIf { layout.isAbsolute(it) } ?: home
		val zsh = listOf(".zshenv", ".zprofile").map { layout.join(zdot, it) }
			.firstOrNull { system.exists(it) }
			?: layout.join(zdot, ".zshenv").takeIf { shell == "zsh" }
		if (zsh != null) targets += zsh to false
		val config = layout.env("XDG_CONFIG_HOME")?.takeIf { layout.isAbsolute(it) }
			?: layout.join(home, ".config")
		val fishDir = layout.join(config, "fish")
		if (shell == "fish" || system.isDirectory(fishDir)) {
			targets += layout.join(layout.join(fishDir, "conf.d"), "drift.fish") to true
		}
		if (targets.isEmpty()) targets += layout.join(home, ".profile") to false
		return targets
	}

	private fun registryStep(dir: String, scope: Scope, edits: MutableList<PathEdit>): String? {
		val machine = scope == Scope.GLOBAL
		val key = if (machine) RegistryEdit.MACHINE_KEY else RegistryEdit.USER_KEY
		val current = system.readPath(machine)
		if (current != null && current.type != REG_SZ && current.type != REG_EXPAND_SZ) {
			throw InstallFailure(
				"the Path value of $key has registry type ${current.type}; not touching it",
			)
		}
		if (dir.contains('%')) {
			warn("$dir contains a percent sign; add it to the Path value of $key by hand")
			return null
		}
		val raw = current?.data.orEmpty()
		if (WindowsPath.contains(raw, dir, env)) {
			say("$dir is already in the Path value of $key")
			return null
		}
		val added = WindowsPath.add(raw, dir)
		val type = current?.type ?: REG_EXPAND_SZ
		val name = if (type == REG_SZ) "REG_SZ" else "REG_EXPAND_SZ"
		step("set Path in $key ($name)\n  before: $raw\n  after: ${added.value}") {
			system.writePath(machine, RegistryValue(type, added.value))
		}
		step("broadcast WM_SETTINGCHANGE") {
			system.broadcastEnvironment()
			true
		}
		edits.removeAll { it is RegistryEdit && it.machine == machine }
		edits += RegistryEdit(machine, dir, added.separator, current == null, type)
		return "open a new terminal for the PATH change to apply"
	}

	private fun writeReceipt(
		path: String,
		scope: Scope,
		dir: String,
		binary: String,
		created: List<String>,
		leftovers: List<String>,
		edits: List<PathEdit>,
	) {
		val receipt = Receipt(DRIFT_VERSION, scope, dir, binary, created, leftovers, edits)
		val text = receipt.canonical() + "\n"
		if (system.read(path)?.decodeToString() == text) {
			say("$path is up to date")
		} else {
			step("write receipt $path") { system.write(path, text.encodeToByteArray()) }
		}
	}

	private fun removeProfile(edit: ProfileEdit): Int {
		val raw = system.read(edit.path)
		if (raw == null) {
			say("${edit.path} is gone; nothing to remove")
			return 0
		}
		val removal = ProfileBlock.remove(raw.decodeToString(), edit.block, edit.leadingNewline)
		return when (removal) {
			ProfileBlock.Removal.Absent -> {
				say("the drift block is already gone from ${edit.path}")
				0
			}

			ProfileBlock.Removal.Changed -> {
				warn("the drift block in ${edit.path} differs from the receipt; left in place")
				1
			}

			is ProfileBlock.Removal.Removed -> {
				val deleteFile = edit.created && removal.content.isEmpty()
				val text = if (deleteFile) {
					"remove ${edit.path} (drift created it)"
				} else {
					"remove the drift block from ${edit.path}"
				}
				say(text)
				val ok = dry || if (deleteFile) {
					system.remove(edit.path)
				} else {
					system.write(edit.path, removal.content.encodeToByteArray())
				}
				if (ok) 0 else fail(text)
			}
		}
	}

	private fun removeRegistry(edit: RegistryEdit): Int {
		val current = system.readPath(edit.machine)
		val without = current?.let { WindowsPath.remove(it.data, edit.entry, edit.separator, env) }
		if (current == null || without == null) {
			say("${edit.entry} is already gone from the Path value of ${edit.key}")
			return 0
		}
		val gone = without.isEmpty() && edit.created
		val text = if (gone) {
			"delete the Path value of ${edit.key} (drift created it)"
		} else {
			"set Path in ${edit.key}\n  before: ${current.data}\n  after: $without"
		}
		say(text)
		val ok = dry || if (gone) {
			system.writePath(edit.machine, null)
		} else {
			system.writePath(edit.machine, RegistryValue(current.type, without))
		}
		if (!ok) return fail(text)
		say("broadcast WM_SETTINGCHANGE")
		if (!dry) system.broadcastEnvironment()
		return 0
	}

	private fun removeBinary(path: String): Int {
		if (!system.exists(path)) {
			say("$path is already gone")
			return 0
		}
		say("remove $path")
		if (dry || system.remove(path)) return 0
		if (layout.windows) {
			val old = layout.oldName(path)
			system.remove(old)
			if (system.rename(path, old)) {
				if (!system.remove(old)) inUse += old
				return 0
			}
		}
		return fail("remove $path")
	}

	private fun fail(what: String): Int {
		warn("failed: ${what.lineSequence().first()}")
		return 1
	}
}

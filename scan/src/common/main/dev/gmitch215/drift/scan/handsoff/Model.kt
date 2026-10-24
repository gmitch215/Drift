package dev.gmitch215.drift.scan.handsoff

/** A runtime or tool version a file pins; [label] says which setting held it. */
data class Pin(val tool: String, val value: String, val label: String, val source: Source)

/** The setting and place a pin came from, without repeating a file name. */
val Pin.origin: String get() = if (source.file.endsWith(label)) "$source" else "$label, $source"

/** How the install step is chosen: [why] names the file or flag that made it the choice. */
data class Install(val command: String, val why: String, val source: Source?)

/**
 * A command the repository names: a package script, a Makefile target, a justfile recipe, a tox
 * environment or a nox session. [command] is how to run it, or null when no page that was
 * fetched documents that, in which case it is listed and never planned.
 */
data class Named(
    val kind: String,
    val name: String,
    val detail: String?,
    val command: String?,
    val source: Source,
    val test: Boolean = false,
)

data class Workspace(val pattern: String, val source: Source)

data class EcosystemFacts(
    val id: String,
    val manifests: List<Source>,
    val manager: String?,
    val managerSource: Source?,
    val lockfiles: List<Source>,
    val needsLockfile: Boolean,
    val pins: List<Pin>,
    val install: Install?,
    val named: List<Named>,
    val workspaces: List<Workspace>,
    val notes: List<String>,
)

/** The questions a stop can ask; each has the flag that answers it. */
enum class StopKind(val id: String, val flag: String) {
    NOTHING_FOUND("nothing-found", "CHECKOUT or --root DIR"),
    TWO_LOCKFILES("two-lockfiles", "--lockfile FILE"),
    NO_LOCKFILE("no-lockfile", "--accept-no-lockfile"),
    NO_TEST_COMMAND("no-test-command", "--test-command COMMAND"),
    SEVERAL_TEST_COMMANDS("several-test-commands", "--test-command COMMAND"),
    PACKAGE_MANAGER("package-manager", "--lockfile FILE"),
    RUNTIME_VERSION("runtime-version", "--runtime NAME=VERSION"),
    MONOREPO("monorepo", "--root DIR"),
    MATRIX_CELL("matrix-cell", "--cell KEY=VALUE"),
    CI_CONFIG("ci-config", "--job NAME or --workflow FILE"),
}

/** What is ambiguous ([what]) and the one question the person has to answer ([question]). */
data class Stop(val kind: StopKind, val what: String, val question: String)

data class CiService(val name: String, val image: String?, val line: Int)

data class CiAction(val uses: String, val with: List<Pair<String, String>>, val source: Source)

data class CiStep(
    val index: Int,
    val name: String?,
    val command: String,
    val source: Source,
    val directory: String? = null,
    val condition: String? = null,
)

data class CiJobFacts(
    val provider: String,
    val file: String,
    val id: String,
    val line: Int,
    val runner: String?,
    val image: String?,
    val services: List<CiService>,
    val actions: List<CiAction>,
    val steps: List<CiStep>,
    val cell: List<Pair<String, String>>,
    val notes: List<String>,
)

/** What `--from-ci` read from a case that `drift ci` wrote, and the image chosen from it. */
data class CiCase(val dir: String, val job: String, val image: String?, val basis: String?)

data class CiFacts(
    val job: CiJobFacts?,
    val jobsFound: List<String>,
    val files: List<String>,
    val case: CiCase?,
    val notes: List<String>,
)

data class PlannedCommand(val command: String, val source: String)

data class HandsoffRequest(
    val root: String = "",
    val job: String? = null,
    val workflow: String? = null,
    val fromCi: String? = null,
    val cell: List<Pair<String, String>> = emptyList(),
    val lockfile: String? = null,
    val acceptNoLockfile: Boolean = false,
    val testCommand: String? = null,
    val runtime: List<Pair<String, String>> = emptyList(),
)

/** The result of planning one checkout; nothing in it has been run. */
data class HandsoffPlan(
    val checkout: String,
    val root: String,
    val ecosystems: List<EcosystemFacts>,
    val notRead: List<String>,
    val ci: CiFacts?,
    val stops: List<Stop>,
    val commands: List<PlannedCommand>,
    val image: String?,
    val imageBasis: String?,
    val byHand: List<String>,
    val readFiles: List<String>,
) {
    val ready: Boolean get() = stops.isEmpty()
}

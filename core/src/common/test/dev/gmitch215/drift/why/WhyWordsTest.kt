package dev.gmitch215.drift.why

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhyWordsTest {
    private fun change(path: String, works: String?, fails: String?) = AttributeDiff(
        path,
        Dimension.of(path),
        DiffKind.CHANGED,
        works,
        fails,
        Stability.STATIC,
        null,
    )

    @Test
    fun everyFamilyHasPlainWordsAndTheFallbackNamesThePath() {
        val sides = Sides.HERE_THERE
        fun words(path: String, works: String?, fails: String?) =
            Families.describe(change(path, works, fails), sides)

        assertEquals("Node is 20 there and 22 here", words("tool.node.version", "22", "20").because)
        val git = words("tool.git.version", "2.39", null)
        assertEquals("Git is not installed there (2.39 here)", git.because)
        assertEquals("FOO is set to a there and not set here", words("env.FOO", null, "a").because)
        val nofile = words("limits.nofile", "1024:1024", "64:64")
        assertTrue("the open-file limit is 64 there and 1024 here" in nofile.because)
        val memory = words("cgroup.memory.max", "2147483648", "536870912")
        assertTrue("the memory limit is 512 MiB there and 2 GiB here" in memory.because)
        val cpu = words("cgroup.cpu.max", "200000 100000", "100000 100000")
        assertTrue("the number of CPUs is 1 there and 2 here" in cpu.because)
        assertTrue("the time zone differs" in words("env.TZ", "UTC", "Asia/Tokyo").because)
        assertTrue("Set TZ=UTC there" in words("env.TZ", "UTC", "Asia/Tokyo").fix)
        assertTrue("Unset TZ there" in words("env.TZ", null, "Asia/Tokyo").fix)
        assertTrue("the locale differs" in words("env.LANG", "C.UTF-8", "POSIX").because)
        val locked = words("project.node.tinyfmt", "2.0.0", "1.1.0")
        assertTrue("tinyfmt is locked to 1.1.0 there and 2.0.0 here" in locked.because)
        assertTrue("npm install tinyfmt@2.0.0" in locked.fix)
        val odd = words("something.odd", "a", "b")
        assertEquals("something.odd is b there and a here", odd.because)
        assertTrue("Make something.odd the same there as here" in odd.fix)
    }

    @Test
    fun aToolFixSaysWhatToDoWhenThereIsNoVersionManager() {
        val fix = Families.describe(change("tool.python.version", "3.12", "3.10"), Sides.HERE_THERE)
            .fix
        assertTrue(
            "If there is no version manager there, change the image or package that provides " +
                "Python." in fix,
            fix,
        )
    }

    @Test
    fun aTimeZoneFixSaysWhatItCoversAndWhatMakesItTheSameEverywhere() {
        fun fix(path: String, works: String?, fails: String?) =
            Families.describe(change(path, works, fails), Sides.HERE_THERE).fix
        val set = fix("env.TZ", "UTC", "Asia/Tokyo")
        assertTrue("That fixes only that machine" in set, set)
        assertTrue("Making the code name its zone (UTC) gives the same" in set, set)
        assertTrue("That fixes only that machine" in fix("env.TZ", null, "Asia/Tokyo"))
        assertTrue("That fixes only that machine" in fix("tz.system-zone", "UTC", "Asia/Tokyo"))
    }

    @Test
    fun aToolFixNamesTheFileTheProjectPinsItIn() {
        val described = Families.describe(
            change("tool.node.version", "22", "20"),
            Sides.HERE_THERE,
        ) { if (it == "node") ".nvmrc" else null }
        assertTrue("your project records it in .nvmrc" in described.fix, described.fix)
        val engines = mapOf("package.json" to "{\"engines\":{\"node\":\"22\"}}")
        assertEquals("package.json", Families.pin(engines::get, "node"))
        assertNull(Families.pin(mapOf("package.json" to "{}")::get, "node"))
    }

    @Test
    fun failurePhrasesPointAtTheKindOfDifferenceTheyFit() {
        fun pointers(text: String) = FailurePhrases.pointers(
            listOf(Symptom(SymptomKind.SIGNATURE, text, 1)),
        )

        val groupBy = pointers("typeerror: object.groupby is not a function")
        assertEquals(listOf(Pointer.RUNTIME), groupBy)
        val module = pointers("modulenotfounderror: no module named 'tomllib'")
        assertEquals(listOf(Pointer.MODULE), module)
        assertTrue(Pointer.MISSING_TOOL in pointers("sh: 1: git: not found"))
        assertTrue(Pointer.LIMIT in pointers("oserror: [errno 24] too many open files"))
        assertTrue(Pointer.MEMORY in pointers("fatal: out of memory"))
        assertTrue(Pointer.NETWORK in pointers("econnrefused 127.0.0.1:5432"))
        assertTrue(Pointer.PERMISSION in pointers("eacces: permission denied"))
        assertTrue(Pointer.LOCALE in pointers("unicodeencodeerror: 'ascii' codec can't encode"))
        assertTrue(pointers("all good").isEmpty())
        assertTrue(FailurePhrases.pointers(null).isEmpty())
        assertTrue(FailurePhrases.pointers(emptyList()).isEmpty())
        val node = change("tool.node.version", "22", "20")
        assertTrue(Pointer.RUNTIME.fits(node) && !Pointer.LIMIT.fits(node))
        assertTrue(Pointer.MISSING_TOOL.fits(change("tool.git.version", "2", null)))
        assertFalse(Pointer.MISSING_TOOL.fits(node))
    }

    @Test
    fun noiseIsRemovedAndRestatedVersionsAreDroppedOnlyWhenTheToolFactExists() {
        val capsule = Capsule(
            "x",
            listOf(
                "env.HOSTNAME" to "a",
                "env.PATH" to "/bin",
                "env.NODE_VERSION" to "22",
                "env.FOO_VERSION" to "1",
                "env.XDG_RUNTIME_DIR" to "/run",
                "runtime.platform" to "linux",
                "tool.node.version" to "22",
                "env.TZ" to "UTC",
            ).map { Attribute(it.first, it.second, "t", Stability.STATIC) },
        )
        val kept = Noise.strip(capsule).attributes.map { it.path }
        assertEquals(listOf("env.FOO_VERSION", "env.TZ", "tool.node.version"), kept.sorted())
        assertTrue(Noise.isCiLabel("ci.runner.image") && !Noise.isCiLabel("tool.node.version"))
    }

    @Test
    fun theWordsOfCertaintyAreStable() {
        assertEquals(
            listOf("cause", "likely", "one-of", "tie", "none"),
            Certainty.entries.map { it.id },
        )
        assertEquals("it works here and fails there", Sides.HERE_THERE.direction)
        assertEquals("it works there and fails here", Sides.HERE_THERE.swapped().direction)
    }
}

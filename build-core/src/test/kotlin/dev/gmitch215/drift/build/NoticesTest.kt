package dev.gmitch215.drift.build

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class NoticesTest {
    private val notices = """
        Prose that names org.example:ignored is not an entry.
        - org.jetbrains.kotlin:kotlin-stdlib
          Apache-2.0
        - com.example:lib
    """.trimIndent()

    @Test
    fun listedReadsOnlyBulletCoordinates() {
        val expected = setOf("org.jetbrains.kotlin:kotlin-stdlib", "com.example:lib")
        assertEquals(expected, Notices.listed(notices))
    }

    @Test
    fun missingReturnsSortedUniqueUnlistedCoordinates() {
        val coordinates = listOf("org.example:ignored", "com.example:lib", "a.b:c", "a.b:c")
        assertEquals(listOf("a.b:c", "org.example:ignored"), Notices.missing(coordinates, notices))
    }

    @Test
    fun fullyListedSetIsEmpty() {
        assertEquals(emptyList(), Notices.missing(listOf("com.example:lib"), notices))
    }

    @Test
    fun theRootOfADetachedResolutionIsNotAShippedComponent() {
        val project = ProjectBuilder.builder().build()
        val file = project.layout.projectDirectory.file("NOTICES.txt").asFile
        file.writeText(notices)
        val task = project.tasks.register("verifyNotices", VerifyNoticesTask::class.java) {
            notices.set(file)
            configurations.set(listOf(project.configurations.detachedConfiguration()))
        }.get()
        task.check()
    }
}

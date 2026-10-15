package dev.gmitch215.drift.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class PathPrefixesTest {
    @Test
    fun listsEveryLeadingRunShortestFirst() {
        assertEquals(listOf("", "/a", "/a/b"), pathPrefixes("/a/b", '/', 1))
        assertEquals(listOf("a", "a/b"), pathPrefixes("a/b", '/', 1))
    }

    @Test
    fun parentsOnlyLeavesTheLastPartOut() {
        assertEquals(listOf("", "/a"), pathPrefixes("/a/b", '/', 1, parentsOnly = true))
        assertEquals(emptyList(), pathPrefixes("file", '/', 1, parentsOnly = true))
    }

    @Test
    fun startsAtTheGivenPartCountForADrivePrefix() {
        assertEquals(
            listOf("C:\\a", "C:\\a\\b"),
            pathPrefixes("C:\\a\\b", '\\', 2),
        )
        assertEquals(emptyList(), pathPrefixes("C:", '\\', 2))
    }
}

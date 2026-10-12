package dev.ideatab.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EditorRestoreTest {
    private class Editors(initial: List<String>, override var tabLimit: Int) : EditorAccess<String> {
        val opened = initial.associateWith { it }.toMutableMap()
        val resolvable = mutableSetOf<String>()
        var focused: String? = null
        var reuse = true
        var forceClose: String? = null
        var failOpen = false
        var capacityChanges = 0
        override val openFiles get() = opened.toMap()
        override fun resolve(url: String) = url.takeIf { it in resolvable }
        override fun increaseTabLimit(limit: Int) { tabLimit = limit; capacityChanges++ }
        override fun open(file: String, focus: Boolean) {
            if (failOpen) error("Editor failure")
            if (file !in opened) {
                if (reuse && opened.isNotEmpty()) opened.remove(opened.keys.first())
                opened[file] = file
                while (opened.size > tabLimit) opened.remove(opened.keys.first())
                forceClose?.let { opened.remove(it) }
            }
            if (focus) focused = file
        }
        override fun withRetentionProtection(operation: () -> Unit) {
            val previous = reuse; reuse = false
            try { operation() } finally { reuse = previous }
        }
    }

    @Test fun `capacity rejection changes neither editor state nor group state`() {
        val editors = Editors(listOf("old"), 1).also { it.resolvable.add("new") }
        val restorer = EditorRestore(editors)
        var groupsReplaced = false
        val result = restorer.execute(restorer.prepare(listOf("new")), { limit, needed ->
            assertEquals(1, limit); assertEquals(2, needed); false
        }, { groupsReplaced = true })
        assertNull(result)
        assertFalse(groupsReplaced)
        assertEquals(setOf("old"), editors.openFiles.keys)
        assertEquals(0, editors.capacityChanges)
    }

    @Test fun `approved capacity protects previous tabs and focuses requested active file`() {
        val editors = Editors(listOf("old1", "old2"), 3).also { it.resolvable.addAll(listOf("new1", "new2", "new3")) }
        val restorer = EditorRestore(editors)
        var groupsReplaced = false
        val result = restorer.execute(restorer.prepare(listOf("new1", "new2", "new3", "new1"), "new2"), { _, needed ->
            assertEquals(5, needed); true
        }, { groupsReplaced = true })!!
        assertTrue(groupsReplaced)
        assertEquals(5, editors.tabLimit)
        assertEquals(setOf("old1", "old2", "new1", "new2", "new3"), editors.openFiles.keys)
        assertEquals("new2", editors.focused)
        assertEquals("", result.message())
        assertTrue(editors.reuse)
    }

    @Test fun `loss of previous tab is reported even when every requested file remains open`() {
        val editors = Editors(listOf("old"), 10).also { it.resolvable.add("new"); it.forceClose = "old" }
        val restorer = EditorRestore(editors)
        val result = restorer.execute(restorer.prepare(listOf("new")), { _, _ -> error("Unexpected prompt") })!!
        assertEquals(setOf("old"), result.lostExisting)
        assertTrue(result.lostRequested.isEmpty())
        assertTrue(result.message().contains("1 个原有文件"))
        assertTrue(result.message().contains("old"))
    }

    @Test fun `open transient files need no URL resolver and no capacity increase`() {
        val editors = Editors(listOf("mock:///temporary"), 1)
        val restorer = EditorRestore(editors)
        val result = restorer.execute(restorer.prepare(listOf("mock:///temporary")), { _, _ -> error("Unexpected prompt") })!!
        assertEquals("mock:///temporary", editors.focused)
        assertEquals("", result.message())
        assertEquals(0, editors.capacityChanges)
    }

    @Test fun `missing URLs are distinct and never consume capacity`() {
        val editors = Editors(listOf("old"), 2).also { it.resolvable.add("new") }
        val restorer = EditorRestore(editors)
        val result = restorer.execute(restorer.prepare(listOf("missing", "missing", "new"), "missing"), { _, _ -> error("Unexpected prompt") })!!
        assertEquals(listOf("missing"), result.missing)
        assertEquals("new", editors.focused)
        assertTrue(result.message().contains("1 个文件不存在"))
    }

    @Test fun `retention setting is restored when editor opening fails`() {
        val editors = Editors(emptyList(), 10).also { it.resolvable.add("new"); it.failOpen = true }
        val restorer = EditorRestore(editors)
        assertFailsWith<IllegalStateException> {
            restorer.execute(restorer.prepare(listOf("new")), { _, _ -> true })
        }
        assertTrue(editors.reuse)
    }
}

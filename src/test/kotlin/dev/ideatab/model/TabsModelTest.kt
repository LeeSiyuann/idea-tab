package dev.ideatab.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabsModelTest {
    private val a = "file:///project/src/A.kt"
    private val b = "file:///project/src/B.kt"
    private val c = "file:///project/src/C.kt"

    @Test fun `moving between groups leaves one membership`() {
        val model = TabsModel()
        val first = model.createGroup("First")
        val second = model.createGroup("Second")
        model.moveFile(a, first); model.moveFile(a, second); model.moveFile(a, second)
        assertTrue(model.snapshot().groups[0].files.isEmpty())
        assertEquals(listOf(a), model.snapshot().groups[1].files)
    }

    @Test fun `same group drop positions account for removed source`() {
        val model = TabsModel()
        val id = model.createGroup("Group")
        listOf(a, b, c).forEach { model.moveFile(it, id) }
        model.moveFile(a, id, 2)
        assertEquals(listOf(b, a, c), model.snapshot().groups.single().files)
        model.moveFile(c, id, 0)
        assertEquals(listOf(c, b, a), model.snapshot().groups.single().files)
        model.moveFile(c, id)
        assertEquals(listOf(b, a, c), model.snapshot().groups.single().files)
    }

    @Test fun `invalid destination does not remove source`() {
        val model = TabsModel()
        val id = model.createGroup("Group")
        model.moveFile(a, id); model.moveFile(a, "missing")
        assertEquals(listOf(a), model.snapshot().groups.single().files)
    }

    @Test fun `deleting group moves members into ungrouped order`() {
        val model = TabsModel()
        val id = model.createGroup("Group")
        listOf(b, a).forEach { model.moveFile(it, id) }
        model.deleteGroup(id)
        assertTrue(model.snapshot().groups.isEmpty())
        assertEquals(listOf(b, a, c), model.orderedUngrouped(listOf(a, b, c)))
    }

    @Test fun `ungrouped ordering excludes closed and grouped files`() {
        val model = TabsModel()
        val id = model.createGroup("Group")
        model.moveFile(b, null); model.moveFile(c, null); model.moveFile(a, id)
        assertEquals(listOf(b), model.orderedUngrouped(listOf(a, b)))
        assertEquals(listOf(b, c), model.orderedUngrouped(listOf(c, b)))
    }

    @Test fun `group reordering supports insertion before and after source`() {
        val model = TabsModel()
        val ids = listOf("One", "Two", "Three").map { model.createGroup(it) }
        model.moveGroup(ids[0], 3)
        assertEquals(listOf(ids[1], ids[2], ids[0]), model.snapshot().groups.map { it.id })
        model.moveGroup(ids[0], 0)
        assertEquals(ids, model.snapshot().groups.map { it.id })
    }

    @Test fun `ungrouped drop ignores hidden closed entries`() {
        val model = TabsModel()
        model.moveFile(c, null)
        model.syncUngroupedOrder(listOf(a, b))
        model.moveFile(b, null, 0)
        assertEquals(listOf(b, a), model.orderedUngrouped(listOf(a, b)))
        assertEquals(listOf(b, a, c), model.snapshot().ungroupedOrder)
    }

    @Test fun `directory rename updates live and saved session references`() {
        val model = TabsModel()
        val id = model.createGroup("Group")
        model.moveFile(a, id); model.moveFile("file:///project/src2/Keep.kt", id)
        model.saveSession("Session", listOf(a), a)
        model.replaceUrlPrefix("file:///project/src", "file:///project/code")
        val state = model.snapshot()
        assertEquals(listOf("file:///project/code/A.kt", "file:///project/src2/Keep.kt"), state.groups.single().files)
        assertEquals(listOf("file:///project/code/A.kt"), state.sessions.single().openFiles)
        assertEquals("file:///project/code/A.kt", state.sessions.single().activeFile)
    }

    @Test fun `session restoration restores independent group copies`() {
        val model = TabsModel()
        val id = model.createGroup("Original")
        model.moveFile(a, id); model.moveFile(b, id); model.setCollapsed(id, true)
        val session = model.saveSession("Saved", listOf(a, c), c)
        model.renameGroup(id, "Changed"); model.forgetFile(a)
        val restored = model.applySession(session)!!
        assertEquals("Original", model.snapshot().groups.single().name)
        assertEquals(listOf(a, b), model.snapshot().groups.single().files)
        assertTrue(model.snapshot().groups.single().collapsed)
        assertEquals(listOf(a, c), restored.openFiles)
        model.renameGroup(id, "Later")
        assertEquals("Original", model.snapshot().sessions.single().groups.single().name)
    }

    @Test fun `snapshot cannot mutate service state`() {
        val model = TabsModel()
        val id = model.createGroup("Group")
        model.moveFile(a, id); model.saveSession("Session", listOf(a), a)
        val snapshot = model.snapshot()
        snapshot.groups.single().files.clear()
        snapshot.sessions.single().groups.single().name = "Broken"
        assertEquals(listOf(a), model.snapshot().groups.single().files)
        assertEquals("Group", model.snapshot().sessions.single().groups.single().name)
    }

    @Test fun `loading repairs duplicate ids memberships and invalid colors`() {
        val state = TabsState().also {
            it.groups.add(GroupState().also { g -> g.id = "same"; g.name = " "; g.color = -1; g.files.addAll(listOf(a, a, "")) })
            it.groups.add(GroupState().also { g -> g.id = "same"; g.files.addAll(listOf(a, b)) })
            it.ungroupedOrder.addAll(listOf(b, c, c))
        }
        val model = TabsModel(state)
        val loaded = model.snapshot()
        assertNotEquals(loaded.groups[0].id, loaded.groups[1].id)
        assertEquals("新分组", loaded.groups[0].name)
        assertEquals(7, loaded.groups[0].color)
        assertEquals(listOf(a), loaded.groups[0].files)
        assertEquals(listOf(b), loaded.groups[1].files)
        assertEquals(listOf(c), loaded.ungroupedOrder)
        state.groups.clear()
        assertEquals(2, model.snapshot().groups.size)
    }

    @Test fun `rename to existing path removes duplicate membership`() {
        val model = TabsModel()
        val id = model.createGroup("Group")
        model.moveFile(a, id); model.moveFile(b, id)
        model.replaceUrlPrefix(a, b)
        assertEquals(listOf(b), model.snapshot().groups.single().files)
    }

    @Test fun `removed session cannot be restored`() {
        val model = TabsModel()
        val id = model.saveSession("Session", listOf(a), a)
        model.deleteSession(id)
        assertNull(model.applySession(id))
        assertTrue(model.snapshot().sessions.isEmpty())
    }

    @Test fun `collapse and display options survive state reload`() {
        val model = TabsModel()
        val id = model.createGroup("Group")
        model.setCollapsed(id, true); model.setShowSaved(false); model.setNativeColors(true)
        model.setUngroupedCollapsed(true)
        val reloaded = TabsModel(model.snapshot()).snapshot()
        assertTrue(reloaded.groups.single().collapsed)
        assertFalse(reloaded.showSavedFiles)
        assertTrue(reloaded.colorNativeTabs)
        assertTrue(reloaded.ungroupedCollapsed)
    }

    @Test fun `replacement backup restores unsaved groups and ungrouped collapse`() {
        val model = TabsModel()
        val oldGroup = model.createGroup("Saved")
        model.moveFile(a, oldGroup)
        val saved = model.saveSession("Manual", listOf(a), a)
        val currentGroup = model.createGroup("Unsaved")
        model.moveFile(b, currentGroup); model.setUngroupedCollapsed(true)
        model.applySessionWithBackup(saved, listOf(a, b, c), b, "Before restore")
        assertEquals(listOf("Saved"), model.snapshot().groups.map { it.name })
        assertFalse(model.snapshot().ungroupedCollapsed)
        val backup = model.snapshot().sessions.single { it.recoveryBackup }
        assertEquals(listOf(a, b, c), backup.openFiles)
        assertEquals(b, backup.activeFile)
        model.applySession(backup.id)
        assertEquals(listOf("Saved", "Unsaved"), model.snapshot().groups.map { it.name })
        assertTrue(model.snapshot().ungroupedCollapsed)
    }

    @Test fun `automatic backups are bounded without removing manual sessions`() {
        val model = TabsModel()
        val manual = model.saveSession("Manual", listOf(a), a)
        repeat(8) { model.applySessionWithBackup(manual, listOf(b), b, "Backup $it") }
        assertEquals(listOf("Backup 3", "Backup 4", "Backup 5", "Backup 6", "Backup 7"),
            model.snapshot().sessions.filter { it.recoveryBackup }.map { it.name })
        assertEquals("Manual", model.session(manual)!!.name)
        val before = model.snapshot().sessions.size
        assertNull(model.applySessionWithBackup("missing", listOf(b), b, "Unused"))
        assertEquals(before, model.snapshot().sessions.size)
    }

    @Test fun `lightweight view and color reads remain isolated from saved histories`() {
        val model = TabsModel()
        val group = model.createGroup("Live", 2); model.moveFile(a, group)
        model.saveSession("History", listOf(a), a)
        assertNull(model.nativeColorIndex(a))
        model.setNativeColors(true)
        assertEquals(2, model.nativeColorIndex(a))
        assertEquals(mapOf(a to 2), model.nativeColorAssignments())
        val view = model.viewState(); view.groups.single().files.clear()
        assertEquals(listOf(a), model.viewState().groups.single().files)
        model.setColor(group, 3)
        assertEquals(3, model.nativeColorIndex(a))
        assertEquals(2, model.snapshot().sessions.single().groups.single().color)
        model.setNativeColors(false)
        assertTrue(model.nativeColorAssignments().isEmpty())
    }
}

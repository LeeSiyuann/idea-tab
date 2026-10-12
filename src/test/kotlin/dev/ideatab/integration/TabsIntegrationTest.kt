package dev.ideatab.integration

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightVirtualFile
import dev.ideatab.service.TabsStateListener
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.ideatab.model.TabsState
import dev.ideatab.service.TabsService
import dev.ideatab.ui.TabsPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import java.awt.event.MouseEvent

class TabsIntegrationTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // LightPlatformTestCase reuses its project, so project-service state must be isolated.
        TabsService.get(project).loadState(TabsState())
    }
    fun testStateXmlRoundTrip() {
        val service = TabsService.get(project)
        val id = service.model.createGroup("中文分组")
        service.model.moveFile("file:///project/A.txt", id)
        service.model.setCollapsed(id, true)
        service.model.setUngroupedCollapsed(true)
        service.model.saveSession("工作会话", listOf("file:///project/A.txt"), "file:///project/A.txt")
        val xml = XmlSerializer.serialize(service.state)
        val decoded = XmlSerializer.deserialize(xml, TabsState::class.java)
        assertEquals("中文分组", decoded.groups.single().name)
        assertTrue(decoded.groups.single().collapsed)
        assertTrue(decoded.ungroupedCollapsed)
        assertTrue(decoded.sessions.single().ungroupedCollapsed)
        assertEquals("file:///project/A.txt", decoded.sessions.single().activeFile)
        service.loadState(decoded)
        assertEquals(decoded.groups.single().files, service.state.groups.single().files)
    }

    fun testSidePanelUsesRealOpenFilesAndFiltersSearch() {
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            val first = myFixture.addFileToProject("src/First.txt", "first").virtualFile
            val second = myFixture.addFileToProject("src/Second.txt", "second").virtualFile
            val manager = FileEditorManager.getInstance(project)
            manager.openFile(first, false); manager.openFile(second, true)
            val service = TabsService.get(project)
            val id = service.model.createGroup("任务")
            service.model.moveFile(first.url, id)
            val panel = TabsPanel(project)
            try {
                panel.setSize(340, 560); panel.doLayout()
                val tree = (panel.components.filterIsInstance<JScrollPane>().single().viewport.view as JTree)
                assertEquals(4, tree.rowCount) // two group headers, two real open files
                fun layout(container: Container) {
                    container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout)
                }
                layout(panel)
                panel.searchField.text = "First"
                panel.searchField.text = ""
                val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                try { panel.printAll(graphics) } finally { graphics.dispose() }
                val output = Path.of("build", "qa", "idea-tab-panel.png")
                Files.createDirectories(output.parent)
                ImageIO.write(image, "png", output.toFile())
                panel.searchField.text = "First"
                panel.flushSearch()
                assertEquals(2, tree.rowCount)
                panel.searchField.text = "no-match-at-all"
                panel.flushSearch()
                assertEquals(0, tree.rowCount)
                panel.searchField.text = ""
                assertEquals(4, tree.rowCount)
                panel.searchField.text = "First"
                panel.flushSearch()
                val bounds = tree.getRowBounds(1)
                tree.dispatchEvent(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    bounds.x + bounds.width - 6, bounds.y + bounds.height / 2, 1, false, MouseEvent.BUTTON1))
                assertFalse(manager.isFileOpen(first)) // the row's close affordance uses the real editor manager
                panel.searchField.text = "First " // forces immediate view refresh after the close event
                panel.flushSearch()
                assertEquals(2, tree.rowCount) // closed group members remain available for reopening
            } finally { Disposer.dispose(panel) }
        }
    }

    fun testVfsRenameUpdatesGroupsAndSavedSessions() {
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            val file = myFixture.addFileToProject("src/Original.txt", "content").virtualFile
            val service = TabsService.get(project)
            val id = service.model.createGroup("任务")
            service.model.moveFile(file.url, id)
            service.model.saveSession("会话", listOf(file.url), file.url)
            WriteCommandAction.runWriteCommandAction(project) { file.rename(this, "Renamed.txt") }
            assertEquals(file.url, service.state.groups.single().files.single())
            assertEquals(file.url, service.state.sessions.single().activeFile)
        }
    }

    private fun tree(panel: TabsPanel) = panel.components.filterIsInstance<JScrollPane>().single().viewport.view as JTree

    fun testEmptyAndHiddenGroupsRemainSearchableByName() = EdtTestUtil.runInEdtAndWait<RuntimeException> {
        val model = TabsService.get(project).model
        model.createGroup("EmptyTask")
        val hidden = model.createGroup("HiddenTask")
        model.moveFile("file:///missing/Hidden.txt", hidden); model.setShowSaved(false)
        val panel = TabsPanel(project)
        try {
            panel.searchField.text = "emptytask"; panel.flushSearch()
            assertEquals(1, tree(panel).rowCount)
            panel.searchField.text = "HiddenTask"; panel.flushSearch()
            assertEquals(1, tree(panel).rowCount)
            panel.searchField.text = "absent"; panel.flushSearch()
            assertEquals(0, tree(panel).rowCount)
        } finally { Disposer.dispose(panel) }
    }

    fun testTransientEditorCanBeActivatedFromSidebar() = EdtTestUtil.runInEdtAndWait<RuntimeException> {
        val transient = LightVirtualFile("Transient.txt", "temporary")
        val other = myFixture.addFileToProject("Other.txt", "x").virtualFile
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(transient, false); manager.openFile(other, true)
        val panel = TabsPanel(project)
        try {
            panel.searchField.text = "Transient"; panel.flushSearch()
            val tree = tree(panel)
            val bounds = tree.getRowBounds(1)
            tree.dispatchEvent(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                bounds.x + 4, bounds.y + bounds.height / 2, 1, false, MouseEvent.BUTTON1))
            assertEquals(transient, manager.selectedFiles.single())
        } finally { Disposer.dispose(panel) }
    }

    fun testWindowsProjectFileRendersRelativeDirectory() = EdtTestUtil.runInEdtAndWait<RuntimeException> {
        val directory = Files.createDirectories(Path.of(project.basePath!!, "module"))
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(directory.resolve("Sample.txt"), "x"))!!
        FileEditorManager.getInstance(project).openFile(file, false)
        val panel = TabsPanel(project)
        try {
            val tree = tree(panel)
            val node = tree.getPathForRow(1).lastPathComponent as DefaultMutableTreeNode
            val component = tree.cellRenderer.getTreeCellRendererComponent(tree, node, false, false, true, 1, false) as JPanel
            assertEquals("Sample.txt  ·  module", component.components.filterIsInstance<JLabel>().first().text)
        } finally { Disposer.dispose(panel) }
    }

    fun testUngroupedCollapseSurvivesSerializedStateAndPanelRecreation() = EdtTestUtil.runInEdtAndWait<RuntimeException> {
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(myFixture.addFileToProject("A.txt", "x").virtualFile, false)
        val service = TabsService.get(project)
        val panel = TabsPanel(project)
        try {
            tree(panel).collapsePath(tree(panel).getPathForRow(0))
            assertEquals(1, tree(panel).rowCount)
        } finally { Disposer.dispose(panel) }
        service.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(service.state), TabsState::class.java))
        val reopened = TabsPanel(project)
        try { assertEquals(1, tree(reopened).rowCount) } finally { Disposer.dispose(reopened) }
    }

    fun testUntrackedVfsRenameDoesNotPublishSidebarChange() = EdtTestUtil.runInEdtAndWait<RuntimeException> {
        val file = myFixture.addFileToProject("Untracked.txt", "x").virtualFile
        var changes = 0
        val disposable = Disposer.newDisposable()
        project.messageBus.connect(disposable).subscribe(TabsStateListener.TOPIC, TabsStateListener { changes++ })
        try {
            WriteCommandAction.runWriteCommandAction(project) { file.rename(this, "UntrackedRenamed.txt") }
            assertEquals(0, changes)
        } finally { Disposer.dispose(disposable) }
    }

    fun testSearchEnterFlushesPendingQueryBeforeActivatingFile() = EdtTestUtil.runInEdtAndWait<RuntimeException> {
        val manager = FileEditorManager.getInstance(project)
        val first = myFixture.addFileToProject("First.txt", "first").virtualFile
        val second = myFixture.addFileToProject("Second.txt", "second").virtualFile
        manager.openFile(first, false); manager.openFile(second, true)
        val panel = TabsPanel(project)
        try {
            panel.searchField.text = "First"
            panel.searchField.postActionEvent()
            assertEquals(first, manager.selectedFiles.single())
            assertEquals(2, tree(panel).rowCount)
        } finally { Disposer.dispose(panel) }
    }
}

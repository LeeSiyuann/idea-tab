package dev.ideatab.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ideatab.actions.GroupDialogs
import dev.ideatab.service.TabsService
import dev.ideatab.service.TabsStateListener
import dev.ideatab.integration.EditorRestore
import dev.ideatab.integration.PlatformEditorAccess
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.DropMode
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.ToolTipManager
import javax.swing.TransferHandler
import javax.swing.Timer
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.event.DocumentListener as SwingDocumentListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath

private sealed interface Row {
    data class Group(val id: String?, val name: String, val color: Int, val total: Int, val open: Int) : Row
    data class File(val url: String, val groupId: String?, val file: VirtualFile?, val open: Boolean, val active: Boolean, val dirty: Boolean) : Row
}

class TabsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    val searchField = JBTextField()
    private val service = TabsService.get(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private val root = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val footer = JLabel()
    private val refreshQueued = AtomicBoolean(false)
    private var rebuilding = false
    private var disposed = false
    private val searchTimer = Timer(120) { refresh() }.also { it.isRepeats = false }
    @Volatile private var openUrlCache: Set<String> = emptySet()
    @Volatile private var dirtyByUrl: Map<String, Boolean> = emptyMap()

    init {
        border = JBUI.Borders.empty(6)
        val top = JPanel(BorderLayout(0, JBUI.scale(6)))
        val toolbar = JPanel(GridLayout(1, 4, JBUI.scale(3), 0))
        toolbar.add(button("新建组", "创建彩色标签组") { GroupDialogs.newGroup(project) })
        toolbar.add(button("保存", "保存当前打开文件与分组为会话") { saveSession() })
        toolbar.add(button("会话", "恢复或删除已保存的会话") { showSessions() })
        toolbar.add(button("选项", "标签栏显示选项") { showOptions() })
        top.add(toolbar, BorderLayout.NORTH)
        searchField.emptyText.text = "搜索文件名、路径或分组…"
        searchField.toolTipText = "Enter 切换到首个匹配文件；Escape 清空搜索"
        top.add(searchField, BorderLayout.CENTER)
        top.border = JBUI.Borders.emptyBottom(6)
        add(top, BorderLayout.NORTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = JBUI.scale(30)
        tree.cellRenderer = RowRenderer()
        tree.selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
        if (!GraphicsEnvironment.isHeadless()) tree.dragEnabled = true
        tree.dropMode = DropMode.ON_OR_INSERT
        tree.transferHandler = TabTransferHandler()
        tree.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = requestRefresh()
        })
        ToolTipManager.sharedInstance().registerComponent(tree)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        footer.border = JBUI.Borders.empty(6, 2, 0, 2)
        footer.foreground = UIUtil.getContextHelpForeground()
        add(footer, BorderLayout.SOUTH)

        searchField.document.addDocumentListener(object : SwingDocumentListener {
            override fun insertUpdate(e: SwingDocumentEvent) = scheduleSearchRefresh()
            override fun removeUpdate(e: SwingDocumentEvent) = scheduleSearchRefresh()
            override fun changedUpdate(e: SwingDocumentEvent) = scheduleSearchRefresh()
        })
        searchField.addActionListener { flushSearch(); firstFilePath()?.let { activate(it) } }
        bind(searchField, KeyEvent.VK_ESCAPE, "clear-search") { searchField.text = "" }
        bind(searchField, KeyEvent.VK_DOWN, "focus-tree") {
            flushSearch()
            tree.requestFocusInWindow(); firstFilePath()?.let { tree.selectionPath = it }
        }
        bind(tree, KeyEvent.VK_ENTER, "activate-file") { tree.selectionPath?.let(::activate) }
        bind(tree, KeyEvent.VK_DELETE, "close-file") {
            (selectedRow() as? Row.File)?.takeIf { it.open }?.let { closeFiles(listOf(it.url)) }
        }
        bind(tree, KeyEvent.VK_ESCAPE, "focus-search") { searchField.requestFocusInWindow() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val row = row(path) as? Row.File ?: return
                when {
                    e.button == MouseEvent.BUTTON2 && row.open -> closeFiles(listOf(row.url))
                    e.button == MouseEvent.BUTTON1 -> {
                        val bounds = tree.getPathBounds(path) ?: return
                        if (row.open && e.x >= bounds.x + bounds.width - JBUI.scale(24)) closeFiles(listOf(row.url))
                        else activate(path)
                    }
                }
            }
        })
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(e: TreeExpansionEvent) = collapse(e.path, false)
            override fun treeCollapsed(e: TreeExpansionEvent) = collapse(e.path, true)
        })
        val connection = project.messageBus.connect(this)
        connection.subscribe(TabsStateListener.TOPIC, TabsStateListener { requestRefresh() })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = requestRefresh()
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = requestRefresh()
            override fun selectionChanged(event: FileEditorManagerEvent) = requestRefresh()
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = refreshForDocument(event.document, true)
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun afterDocumentSaved(document: Document) = refreshForDocument(document)
                override fun fileContentReloaded(file: VirtualFile, document: Document) = refreshForDocument(document)
                override fun unsavedDocumentsDropped() = requestRefresh()
            })
        refresh()
    }

    private fun scheduleSearchRefresh() {
        if (searchField.text.isBlank()) flushSearch() else searchTimer.restart()
    }

    internal fun flushSearch() { searchTimer.stop(); refresh() }

    private fun button(text: String, tip: String, action: () -> Unit) = JButton(text).also {
        it.toolTipText = tip; it.addActionListener { action() }
    }
    private fun bind(component: JComponent, key: Int, name: String, action: () -> Unit) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name)
        component.actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = action() })
    }
    private fun row(path: TreePath?): Row? = (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Row
    private fun selectedRow(): Row? = row(tree.selectionPath)
    private fun firstFilePath(): TreePath? {
        for (i in 0 until root.childCount) {
            val group = root.getChildAt(i) as DefaultMutableTreeNode
            if (group.childCount > 0) return TreePath((group.getChildAt(0) as DefaultMutableTreeNode).path)
        }
        return null
    }

    private fun requestRefresh() {
        if (disposed || project.isDisposed || !refreshQueued.compareAndSet(false, true)) return
        ToolWindowManager.getInstance(project).invokeLater {
            refreshQueued.set(false)
            if (!disposed && !project.isDisposed) refresh()
        }
    }

    private fun refreshForDocument(document: Document, knownDirty: Boolean? = null) {
        val url = FileDocumentManager.getInstance().getFile(document)?.url ?: return
        val dirty = knownDirty ?: FileDocumentManager.getInstance().isDocumentUnsaved(document)
        if (url in openUrlCache && dirtyByUrl[url] != dirty) requestRefresh()
    }

    private fun refresh() {
        if (disposed || project.isDisposed) return
        val state = service.model.viewState()
        val open = manager.openFiles.associateBy { it.url }
        openUrlCache = open.keys.toSet()
        val active = manager.selectedEditor?.file?.url
        val selected = selectedRow()
        val query = searchField.text.trim().lowercase()
        val dirtyFiles = open.mapValues { (_, file) ->
            val document = FileDocumentManager.getInstance().getCachedDocument(file)
            document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)
        }
        rebuilding = true
        try {
            root.removeAllChildren()
            fun addGroup(id: String?, name: String, color: Int, urls: List<String>) {
                val visible = urls.filter { it in open || state.showSavedFiles }
                val groupMatches = name.lowercase().contains(query)
                val filtered = visible.filter { url ->
                    query.isEmpty() || groupMatches || url.lowercase().contains(query)
                }
                if (query.isNotEmpty() && !groupMatches && filtered.isEmpty()) return
                val node = DefaultMutableTreeNode(Row.Group(id, name, color, urls.size, urls.count { it in open }))
                root.add(node)
                filtered.forEach { url ->
                    val file = open[url] ?: VirtualFileManager.getInstance().findFileByUrl(url)
                    val document = file?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
                    val dirty = document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)
                    node.add(DefaultMutableTreeNode(Row.File(url, id, file, url in open, url == active, dirty)))
                }
                // Expansion is reapplied after reloading the model below.
            }
            state.groups.forEach { addGroup(it.id, it.name, it.color, it.files) }
            val ungrouped = service.model.orderedUngrouped(open.keys.toList())
            addGroup(null, "未分组", 7, ungrouped)
            treeModel.reload()
            var activePath: TreePath? = null
            var selectedPath: TreePath? = null
            for (i in 0 until root.childCount) {
                val node = root.getChildAt(i) as DefaultMutableTreeNode
                val group = node.userObject as Row.Group
                val collapsed = if (group.id == null) state.ungroupedCollapsed else state.groups.find { it.id == group.id }?.collapsed == true
                if (!collapsed || query.isNotEmpty()) tree.expandPath(TreePath(node.path))
                if (selected is Row.Group && group.id == selected.id) selectedPath = TreePath(node.path)
                for (j in 0 until node.childCount) {
                    val child = node.getChildAt(j) as DefaultMutableTreeNode
                    val entry = child.userObject as Row.File
                    if (entry.active) activePath = TreePath(child.path)
                    if (selected is Row.File && entry.url == selected.url) selectedPath = TreePath(child.path)
                }
            }
            val path = if (tree.hasFocus()) selectedPath ?: activePath else activePath ?: selectedPath
            // Selecting a child expands its ancestors; respect the user's collapsed groups.
            if (path != null && tree.isVisible(path)) tree.selectionPath = path else tree.clearSelection()
            footer.text = if (open.isEmpty()) "从项目中打开文件，或恢复已保存的分组" else "${open.size} 个已打开文件 · ${state.groups.size} 个分组"
        } finally { dirtyByUrl = dirtyFiles; rebuilding = false }
    }

    private fun collapse(path: TreePath, collapsed: Boolean) {
        if (rebuilding || searchField.text.isNotBlank()) return
        val group = row(path) as? Row.Group ?: return
        service.change { if (group.id == null) setUngroupedCollapsed(collapsed) else setCollapsed(group.id, collapsed) }
    }

    private fun activate(path: TreePath) {
        when (val entry = row(path)) {
            is Row.File -> {
                val file = entry.file?.takeIf { it.isValid }
                    ?: manager.openFiles.find { it.url == entry.url && it.isValid }
                    ?: VirtualFileManager.getInstance().findFileByUrl(entry.url)
                if (file == null || !file.isValid || file.isDirectory) {
                    Messages.showWarningDialog(project, "文件已不存在或暂时无法访问：\n${entry.url}\n\n可以在右键菜单中移除记录。", "无法打开文件")
                } else manager.openFile(file, true, true)
            }
            is Row.Group -> if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
            null -> Unit
        }
    }

    private fun closeFiles(urls: List<String>) {
        val files = manager.openFiles.filter { it.url in urls }
        // Use the platform's closing path; never discard documents or manipulate editor widgets.
        files.forEach { manager.closeFile(it) }
        requestRefresh()
    }

    private fun restoreFiles(urls: List<String>, activeUrl: String? = null, beforeOpen: () -> Unit = {}) {
        val restorer = EditorRestore(PlatformEditorAccess(project))
        val plan = restorer.prepare(urls, activeUrl)
        val result = restorer.execute(plan, { limit, required ->
            Messages.showYesNoDialog(project,
                "本次追加后最多有 $required 个文件，当前标签上限为 $limit。\n\n为避免原有标签被自动关闭，是否将 IDEA 标签上限提高到 $required？\n该设置对所有项目生效，并保留提高后的值；分屏情况下按项目文件总数保守估算。\n选择取消不会修改文件或分组。",
                "标签容量不足", "提高上限并继续", "取消", Messages.getQuestionIcon()) == Messages.YES
        }, beforeOpen) ?: return
        result.message().takeIf { it.isNotEmpty() }?.let { Messages.showWarningDialog(project, it, "恢复结果") }
        requestRefresh()
    }

    private fun saveSession() {
        val name = Messages.showInputDialog(project, "会话名称：", "保存标签会话", null, "", GroupDialogs.nonBlank) ?: return
        service.change { saveSession(name, manager.openFiles.map { it.url }, manager.selectedEditor?.file?.url) }
    }

    private fun showSessions() {
        val popup = JPopupMenu()
        val sessions = service.state.sessions
        if (sessions.isEmpty()) popup.add(JMenuItem("尚无会话，点击“保存”创建").also { it.isEnabled = false })
        sessions.forEach { session ->
            val menu = JMenu("${session.name} · ${session.openFiles.size} 个文件")
            menu.add(item("仅追加文件（保留当前分组）") {
                service.model.session(session.id)?.let { restoreFiles(it.openFiles, it.activeFile) }
            })
            menu.add(item("替换分组并追加文件（自动备份）") {
                val saved = service.model.session(session.id) ?: return@item
                val previous = manager.openFiles.map { it.url }
                val active = manager.selectedEditor?.file?.url
                restoreFiles(saved.openFiles, saved.activeFile) {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    service.change { applySessionWithBackup(saved.id, previous, active, "恢复前备份 · $stamp") }
                }
            })
            menu.add(item("删除已保存会话") {
                if (Messages.showYesNoDialog(project, "删除会话“${session.name}”？当前打开文件不会关闭。", "删除会话", Messages.getQuestionIcon()) == Messages.YES) {
                    service.change { deleteSession(session.id) }
                }
            })
            popup.add(menu)
        }
        popup.show(searchField, 0, searchField.height)
    }

    private fun showOptions() {
        val state = service.state
        val popup = JPopupMenu()
        popup.add(JCheckBoxMenuItem("显示分组中已关闭的文件", state.showSavedFiles).also {
            it.addActionListener { _ -> service.change { setShowSaved(it.isSelected) } }
        })
        popup.add(JCheckBoxMenuItem("为原生编辑器标签添加组颜色", state.colorNativeTabs).also {
            it.addActionListener { _ -> service.change { setNativeColors(it.isSelected) } }
        })
        popup.addSeparator()
        popup.add(item("展开所有分组") { service.change { setAllCollapsed(false) }; refresh() })
        popup.add(item("折叠所有分组") { service.change { setAllCollapsed(true) }; refresh() })
        popup.addSeparator()
        popup.add(item("显示操作说明") {
            Messages.showInfoMessage(project,
                "点击文件切换编辑器；×、中键或 Delete 关闭文件。\n拖拽文件调整顺序或移动到其他分组；拖拽组头调整组顺序。\n右键文件或组头查看更多操作。\n\n折叠仅影响此侧栏；关闭组内文件会保留成员记录。\n会话可仅追加文件，或先自动备份再替换分组。\n标签容量不足时先提示提高上限或取消；异常关闭会报告原有及新增文件。\n同一文件在多个分屏中合并显示；关闭操作针对该文件的全部编辑器。\n\n可在 IDEA 的编辑器标签设置中手动隐藏顶部标签。\n固定标签和分屏布局仍由 IDEA 管理。", "Tab Harbor 操作说明")
        })
        popup.show(searchField, 0, searchField.height)
    }

    private fun item(label: String, action: () -> Unit) = JMenuItem(label).also { it.addActionListener { action() } }
    private fun popup(event: MouseEvent) {
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        tree.selectionPath = path
        val popup = JPopupMenu()
        when (val entry = row(path)) {
            is Row.Group -> {
                val urls = if (entry.id == null) service.model.orderedUngrouped(manager.openFiles.map { it.url })
                    else service.model.viewState().groups.find { it.id == entry.id }?.files.orEmpty()
                popup.add(item("重新打开组内文件") { restoreFiles(urls) })
                popup.add(item("关闭组内文件（保留分组）") { closeFiles(urls) })
                popup.add(item("关闭其他分组的文件…") {
                    if (Messages.showYesNoDialog(project, "关闭此组以外的所有已打开文件？固定标签也会关闭。", "关闭其他文件", Messages.getQuestionIcon()) == Messages.YES) {
                        closeFiles(manager.openFiles.map { it.url }.filter { it !in urls })
                    }
                })
                if (entry.id != null) {
                    popup.addSeparator()
                    popup.add(item("重命名…") {
                        val name = Messages.showInputDialog(project, "分组名称：", "重命名分组", null, entry.name, GroupDialogs.nonBlank)
                        if (name != null) service.change { renameGroup(entry.id, name) }
                    })
                    val colors = JMenu("分组颜色")
                    GroupColors.names.forEachIndexed { index, name -> colors.add(item(name) { service.change { setColor(entry.id, index) } }) }
                    popup.add(colors)
                    popup.add(item("移除失效文件记录") {
                        val access = PlatformEditorAccess(project)
                        val open = access.openFiles
                        val invalid = urls.filter { it !in open && access.resolve(it) == null }
                        service.change { invalid.forEach(::forgetFile) }
                    })
                    popup.add(item("删除分组（文件保持打开）") {
                        if (Messages.showYesNoDialog(project, "删除分组“${entry.name}”？文件保持打开并移入“未分组”。", "删除分组", Messages.getQuestionIcon()) == Messages.YES) service.change { deleteGroup(entry.id) }
                    })
                }
            }
            is Row.File -> {
                popup.add(item(if (entry.open) "切换到文件" else "重新打开文件") { activate(path) })
                if (entry.open) popup.add(item("关闭文件（所有分屏）") { closeFiles(listOf(entry.url)) })
                val groups = JMenu("移动到分组")
                groups.add(item("新建分组…") { GroupDialogs.newGroup(project)?.let { service.change { moveFile(entry.url, it) } } })
                groups.add(item("未分组") { service.change { moveFile(entry.url, null) } })
                service.model.viewState().groups.forEach { g -> groups.add(item(g.name) { service.change { moveFile(entry.url, g.id) } }) }
                popup.add(groups)
                if (entry.groupId != null) popup.add(item("从分组移除（保持文件状态）") { service.change { moveFile(entry.url, null) } })
                if (!entry.open) popup.add(item("移除记录") { service.change { forgetFile(entry.url) } })
            }
            null -> return
        }
        popup.show(tree, event.x, event.y)
    }

    private inner class RowRenderer : JPanel(BorderLayout(JBUI.scale(6), 0)), TreeCellRenderer {
        private val label = JLabel()
        private val close = JLabel("×", SwingConstants.CENTER)
        private var indent = 0
        init { border = JBUI.Borders.empty(2, 3); add(label, BorderLayout.CENTER); add(close, BorderLayout.EAST); close.preferredSize = Dimension(JBUI.scale(22), JBUI.scale(24)) }
        override fun getTreeCellRendererComponent(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component {
            val node = value as DefaultMutableTreeNode
            indent = (node.level * JBUI.scale(20))
            background = if (selected) UIUtil.getTreeSelectionBackground(hasFocus) else UIUtil.getTreeBackground()
            label.foreground = if (selected) UIUtil.getTreeSelectionForeground(hasFocus) else UIUtil.getTreeForeground()
            close.foreground = label.foreground
            close.isVisible = false
            label.font = UIUtil.getLabelFont()
            when (val entry = node.userObject) {
                is Row.Group -> {
                    label.text = "${entry.name}  ${entry.open}/${entry.total}"
                    label.icon = DotIcon(GroupColors.get(entry.color))
                    label.font = label.font.deriveFont(Font.BOLD)
                    toolTipText = "${entry.name}：${entry.open} 个已打开 / ${entry.total} 个成员；右键管理"
                }
                is Row.File -> {
                    val name = entry.file?.name ?: entry.url.substringAfterLast('/')
                    val path = entry.file?.presentableUrl ?: entry.url
                    val directory = FileLabels.directory(entry.file?.path ?: entry.url, project.basePath)
                    label.text = buildString {
                        if (entry.dirty) append("● ")
                        append(name)
                        if (directory.isNotEmpty()) append("  ·  $directory")
                        if (!entry.open) append(if (entry.file == null) "  ·  已失效" else "  ·  已关闭")
                    }
                    label.icon = entry.file?.fileType?.icon ?: AllIcons.FileTypes.Unknown
                    if (entry.active) label.font = label.font.deriveFont(Font.BOLD)
                    if (!selected && !entry.open) label.foreground = UIUtil.getContextHelpForeground()
                    close.isVisible = entry.open
                    toolTipText = path + if (entry.open) "（× 关闭全部分屏中的此文件）" else "（点击重新打开）"
                }
                else -> { label.text = ""; label.icon = null; toolTipText = null }
            }
            // Tree UIs often paint renderers without validating a composite JPanel.
            // Lay out the label and close affordance explicitly at the current row width.
            setSize(preferredSize.width, tree.rowHeight)
            doLayout()
            return this
        }
        override fun getPreferredSize(): Dimension {
            val preferred = super.getPreferredSize()
            // Keep close buttons visible even for long file paths in a narrow sidebar.
            return Dimension((tree.visibleRect.width - indent - JBUI.scale(8)).coerceAtLeast(JBUI.scale(80)), preferred.height)
        }
    }

    private class DotIcon(private val color: Color) : Icon {
        override fun getIconWidth() = JBUI.scale(16)
        override fun getIconHeight() = JBUI.scale(16)
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            g.color = color; g.fillOval(x + JBUI.scale(3), y + JBUI.scale(3), JBUI.scale(10), JBUI.scale(10))
        }
    }

    private data class DragEntry(val projectKey: String, val groupId: String? = null, val fileUrl: String? = null)
    private inner class TabTransferHandler : TransferHandler() {
        private val flavor = DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + DragEntry::class.java.name,
            "Tab Harbor", DragEntry::class.java.classLoader)
        override fun getSourceActions(c: JComponent) = MOVE
        override fun createTransferable(c: JComponent): Transferable? {
            val payload = when (val selected = selectedRow()) {
                is Row.File -> DragEntry(project.locationHash, fileUrl = selected.url)
                is Row.Group -> selected.id?.let { DragEntry(project.locationHash, groupId = it) }
                null -> null
            } ?: return null
            return object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(flavor)
                override fun isDataFlavorSupported(f: DataFlavor) = f == flavor
                override fun getTransferData(f: DataFlavor): Any {
                    if (!isDataFlavorSupported(f)) throw java.awt.datatransfer.UnsupportedFlavorException(f)
                    return payload
                }
            }
        }
        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop || !support.isDataFlavorSupported(flavor) || searchField.text.isNotBlank()) return false
            val drop = support.dropLocation as? JTree.DropLocation ?: return false
            val payload = support.transferable.getTransferData(flavor) as? DragEntry ?: return false
            if (payload.projectKey != project.locationHash) return false
            val target = row(drop.path)
            return if (payload.groupId != null) target is Row.Group || drop.path?.lastPathComponent === root
                else target is Row.Group || target is Row.File
        }
        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val payload = support.transferable.getTransferData(flavor) as DragEntry
            val drop = support.dropLocation as JTree.DropLocation
            val target = row(drop.path)
            if (payload.groupId != null) {
                val targetIndex = if (drop.path?.lastPathComponent === root) drop.childIndex
                    else service.model.viewState().groups.let { groups -> groups.indexOfFirst { it.id == (target as Row.Group).id }.let { if (it < 0) groups.size else it } }
                service.change { moveGroup(payload.groupId, targetIndex) }
            } else if (payload.fileUrl != null) {
                val groupId = when (target) { is Row.Group -> target.id; is Row.File -> target.groupId; else -> return false }
                val urls = if (groupId == null) service.model.orderedUngrouped(manager.openFiles.map { it.url })
                    else service.model.viewState().groups.find { it.id == groupId }?.files.orEmpty()
                val index = when {
                    target is Row.File -> urls.indexOf(target.url).coerceAtLeast(0)
                    drop.childIndex >= 0 -> {
                        val parent = drop.path.lastPathComponent as DefaultMutableTreeNode
                        val next = if (drop.childIndex < parent.childCount) (parent.getChildAt(drop.childIndex) as DefaultMutableTreeNode).userObject as? Row.File else null
                        next?.let { urls.indexOf(it.url).coerceAtLeast(0) } ?: urls.size
                    }
                    else -> urls.size
                }
                service.change {
                    // Populate ungrouped order before interpreting a displayed insertion position.
                    if (groupId == null) syncUngroupedOrder(urls)
                    moveFile(payload.fileUrl, groupId, index)
                    if (groupId != null) setCollapsed(groupId, false)
                }
            }
            return true
        }
    }

    override fun dispose() {
        disposed = true
        searchTimer.stop()
        ToolTipManager.sharedInstance().unregisterComponent(tree)
    }
}

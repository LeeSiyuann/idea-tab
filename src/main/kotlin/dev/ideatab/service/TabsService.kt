package dev.ideatab.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import dev.ideatab.model.TabsModel
import dev.ideatab.model.TabsState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

fun interface TabsStateListener {
    fun changed()
    companion object { val TOPIC = Topic.create("IDEA Tab state", TabsStateListener::class.java) }
}

@Service(Service.Level.PROJECT)
@State(name = "IdeaTabState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TabsService(private val project: Project) : PersistentStateComponent<TabsState>, Disposable {
    val model = TabsModel()
    private val pendingPresentationUrls = ConcurrentHashMap.newKeySet<String>()
    private val presentationQueued = AtomicBoolean(false)

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                private val renames = HashMap<VFileEvent, Pair<String, String>>()
                override fun before(events: List<VFileEvent>) {
                    events.forEach { event ->
                        val paths = when (event) {
                            is VFileMoveEvent -> event.oldPath to event.newPath
                            is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) event.oldPath to event.newPath else null
                            else -> null
                        }
                        if (paths != null) {
                            val protocol = event.fileSystem.protocol
                            renames[event] = VirtualFileManager.constructUrl(protocol, paths.first) to
                                VirtualFileManager.constructUrl(protocol, paths.second)
                        }
                    }
                }
                override fun after(events: List<VFileEvent>) {
                    val oldColors = if (renames.isNotEmpty()) model.nativeColorAssignments() else emptyMap()
                    var changed = false
                    events.forEach { event -> renames.remove(event)?.let { (old, new) ->
                        if (model.replaceUrlPrefix(old, new)) changed = true
                    } }
                    if (changed) queueColorChanges(oldColors, model.nativeColorAssignments())
                    val openUrls = FileEditorManager.getInstance(project).openFiles.map { it.url }
                    val relevant = events.any { event ->
                        val url = VirtualFileManager.constructUrl(event.fileSystem.protocol, event.path)
                        model.affectsView(url) || openUrls.any { it == url || it.startsWith("$url/") }
                    }
                    if (changed || relevant) notifyChanged()
                }
            }
        )
    }

    override fun getState(): TabsState = model.snapshot()
    override fun loadState(state: TabsState) {
        val before = model.nativeColorAssignments()
        model.load(state); notifyChanged()
        queueColorChanges(before, model.nativeColorAssignments())
    }

    fun change(operation: TabsModel.() -> Unit) {
        val before = model.nativeColorAssignments()
        model.operation()
        notifyChanged()
        queueColorChanges(before, model.nativeColorAssignments())
    }

    private fun queueColorChanges(before: Map<String, Int>, after: Map<String, Int>) {
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        if (changed.isEmpty() || project.isDisposed) return
        pendingPresentationUrls.addAll(changed)
        if (!presentationQueued.compareAndSet(false, true)) return
        ToolWindowManager.getInstance(project).invokeLater {
            val urls = pendingPresentationUrls.toSet()
            pendingPresentationUrls.removeAll(urls)
            presentationQueued.set(false)
            if (!project.isDisposed) {
                val manager = FileEditorManager.getInstance(project)
                manager.openFiles.filter { it.url in urls }.forEach { manager.updateFilePresentation(it) }
            }
        }
    }

    fun notifyChanged() {
        if (!project.isDisposed) project.messageBus.syncPublisher(TabsStateListener.TOPIC).changed()
    }
    override fun dispose() = Unit
    companion object { fun get(project: Project): TabsService = project.service() }
}

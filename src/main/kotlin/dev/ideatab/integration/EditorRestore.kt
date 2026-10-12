package dev.ideatab.integration

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

internal interface EditorAccess<F> {
    val openFiles: Map<String, F>
    val tabLimit: Int
    fun resolve(url: String): F?
    fun open(file: F, focus: Boolean)
    fun increaseTabLimit(limit: Int)
    fun withRetentionProtection(operation: () -> Unit)
}

internal data class RestorePlan<F>(
    val files: Map<String, F>, val missing: List<String>, val previousUrls: Set<String>,
    val activeUrl: String?, val requiredLimit: Int
)

internal data class RestoreResult(val missing: List<String>, val lostExisting: Set<String>, val lostRequested: Set<String>) {
    fun message(): String = buildString {
        if (missing.isNotEmpty()) append("${missing.size} 个文件不存在或无法访问，已跳过。\n")
        if (lostExisting.isNotEmpty()) append("${lostExisting.size} 个原有文件未保持打开，请检查 IDEA 标签设置或其他插件。\n")
        if (lostRequested.isNotEmpty()) append("${lostRequested.size} 个待恢复文件未保持打开，请检查 IDEA 标签设置或其他插件。\n")
        val lost = lostExisting + lostRequested
        if (lost.isNotEmpty()) append("未保持打开的文件：\n${lost.joinToString("\n")}\n文件内容未被删除。")
    }.trim()
}

/** Plan before changing groups; declined capacity changes must leave both editors and groups untouched. */
internal class EditorRestore<F>(private val access: EditorAccess<F>) {
    fun prepare(urls: List<String>, activeUrl: String? = null): RestorePlan<F> {
        val previous = access.openFiles.toMap()
        val files = LinkedHashMap<String, F>()
        val missing = ArrayList<String>()
        urls.distinct().forEach { url ->
            val file = previous[url] ?: access.resolve(url)
            if (file == null) missing.add(url) else files[url] = file
        }
        val newUrls = files.keys - previous.keys
        // Conservative across splits: no window can contain more files than this project-wide union.
        val required = if (newUrls.isEmpty()) 0 else (previous.keys + files.keys).size
        return RestorePlan(files, missing, previous.keys.toSet(), activeUrl, required)
    }

    fun execute(plan: RestorePlan<F>, approveCapacity: (Int, Int) -> Boolean, beforeOpen: () -> Unit = {}): RestoreResult? {
        val limit = access.tabLimit.coerceAtLeast(1)
        if (plan.requiredLimit > limit) {
            if (!approveCapacity(limit, plan.requiredLimit)) return null
            access.increaseTabLimit(plan.requiredLimit)
        }
        beforeOpen()
        access.withRetentionProtection {
            plan.files.values.forEach { access.open(it, false) }
            (plan.files[plan.activeUrl] ?: plan.files.values.firstOrNull())?.let { access.open(it, true) }
        }
        val stillOpen = access.openFiles.keys
        return RestoreResult(plan.missing, plan.previousUrls - stillOpen, plan.files.keys - stillOpen)
    }
}

internal class PlatformEditorAccess(project: Project) : EditorAccess<VirtualFile> {
    private val manager = FileEditorManager.getInstance(project)
    override val openFiles get() = manager.openFiles.filter { it.isValid && !it.isDirectory }.associateBy { it.url }
    override val tabLimit get() = UISettings.getInstance().editorTabLimit
    override fun resolve(url: String): VirtualFile? = VirtualFileManager.getInstance().findFileByUrl(url)?.takeIf { it.isValid && !it.isDirectory }
    override fun open(file: VirtualFile, focus: Boolean) { manager.openFile(file, focus, true) }
    override fun increaseTabLimit(limit: Int) {
        UISettings.getInstance().let { it.editorTabLimit = limit; it.fireUISettingsChanged() }
    }
    override fun withRetentionProtection(operation: () -> Unit) {
        val settings = UISettings.getInstance()
        val reuse = settings.reuseNotModifiedTabs
        settings.reuseNotModifiedTabs = false
        try { operation() } finally { settings.reuseNotModifiedTabs = reuse }
    }
}

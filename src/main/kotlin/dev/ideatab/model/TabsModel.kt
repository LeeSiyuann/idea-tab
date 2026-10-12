package dev.ideatab.model

import java.util.UUID

class GroupState {
    var id: String = ""
    var name: String = ""
    var color: Int = 0
    var collapsed: Boolean = false
    var files: MutableList<String> = ArrayList()
    fun copy(): GroupState = GroupState().also {
        it.id = id; it.name = name; it.color = color; it.collapsed = collapsed; it.files = ArrayList(files)
    }
}

class SessionState {
    var id: String = ""
    var name: String = ""
    var groups: MutableList<GroupState> = ArrayList()
    var openFiles: MutableList<String> = ArrayList()
    var activeFile: String = ""
    var ungroupedCollapsed: Boolean = false
    var recoveryBackup: Boolean = false
    fun copy(): SessionState = SessionState().also {
        it.id = id; it.name = name; it.groups = groups.mapTo(ArrayList()) { g -> g.copy() }
        it.openFiles = ArrayList(openFiles); it.activeFile = activeFile
        it.ungroupedCollapsed = ungroupedCollapsed; it.recoveryBackup = recoveryBackup
    }
}

class TabsState {
    var schemaVersion: Int = 1
    var groups: MutableList<GroupState> = ArrayList()
    var ungroupedOrder: MutableList<String> = ArrayList()
    var sessions: MutableList<SessionState> = ArrayList()
    var showSavedFiles: Boolean = true
    var colorNativeTabs: Boolean = false
    var ungroupedCollapsed: Boolean = false
    fun copy(): TabsState = TabsState().also {
        it.schemaVersion = schemaVersion; it.groups = groups.mapTo(ArrayList()) { g -> g.copy() }
        it.ungroupedOrder = ArrayList(ungroupedOrder)
        it.sessions = sessions.mapTo(ArrayList()) { s -> s.copy() }
        it.showSavedFiles = showSavedFiles; it.colorNativeTabs = colorNativeTabs
        it.ungroupedCollapsed = ungroupedCollapsed
    }
}

/** Sidebar reads need no saved-session history. All returned groups are independent copies. */
data class TabsViewState(val groups: List<GroupState>, val showSavedFiles: Boolean, val ungroupedCollapsed: Boolean)

/** Pure, synchronized state model: one file has at most one group in a project. */
class TabsModel(initial: TabsState = TabsState()) {
    private var state = initial.copy()
    init { normalize() }

    @Synchronized fun snapshot(): TabsState = state.copy()
    @Synchronized fun viewState() = TabsViewState(state.groups.map { it.copy() }, state.showSavedFiles, state.ungroupedCollapsed)
    @Synchronized fun session(id: String): SessionState? = state.sessions.find { it.id == id }?.copy()
    @Synchronized fun nativeColorIndex(url: String): Int? =
        if (state.colorNativeTabs) state.groups.find { url in it.files }?.color else null
    @Synchronized fun nativeColorAssignments(): Map<String, Int> =
        if (state.colorNativeTabs) state.groups.flatMap { g -> g.files.map { it to g.color } }.toMap() else emptyMap()
    @Synchronized fun affectsView(url: String): Boolean = state.groups.any { g -> g.files.any { it == url || it.startsWith("$url/") } }
    @Synchronized fun load(value: TabsState) { state = value.copy(); normalize() }

    private fun normalize() {
        val ids = HashSet<String>()
        val urls = HashSet<String>()
        state.groups.forEach { g ->
            if (g.id.isBlank() || !ids.add(g.id)) { g.id = UUID.randomUUID().toString(); ids.add(g.id) }
            g.name = g.name.trim().ifBlank { "新分组" }
            g.color = Math.floorMod(g.color, 8)
            g.files = g.files.filterTo(ArrayList()) { it.isNotBlank() && urls.add(it) }
        }
        state.ungroupedOrder = state.ungroupedOrder.filterTo(ArrayList()) { it.isNotBlank() && urls.add(it) }
        val sessionIds = HashSet<String>()
        state.sessions.forEach { s ->
            if (s.id.isBlank() || !sessionIds.add(s.id)) { s.id = UUID.randomUUID().toString(); sessionIds.add(s.id) }
            s.openFiles = s.openFiles.filter { it.isNotBlank() }.distinct().toMutableList()
        }
    }

    @Synchronized fun createGroup(name: String, color: Int = state.groups.size % 8): String {
        require(name.isNotBlank())
        val g = GroupState().also { it.id = UUID.randomUUID().toString(); it.name = name.trim(); it.color = Math.floorMod(color, 8) }
        state.groups.add(g)
        return g.id
    }

    @Synchronized fun renameGroup(id: String, name: String) {
        require(name.isNotBlank())
        state.groups.find { it.id == id }?.name = name.trim()
    }
    @Synchronized fun setColor(id: String, color: Int) { state.groups.find { it.id == id }?.color = Math.floorMod(color, 8) }
    @Synchronized fun setCollapsed(id: String, value: Boolean) { state.groups.find { it.id == id }?.collapsed = value }
    @Synchronized fun setUngroupedCollapsed(value: Boolean) { state.ungroupedCollapsed = value }
    @Synchronized fun setAllCollapsed(value: Boolean) {
        state.groups.forEach { it.collapsed = value }; state.ungroupedCollapsed = value
    }
    @Synchronized fun setShowSaved(value: Boolean) { state.showSavedFiles = value }
    @Synchronized fun setNativeColors(value: Boolean) { state.colorNativeTabs = value }

    @Synchronized fun deleteGroup(id: String) {
        val g = state.groups.find { it.id == id } ?: return
        state.groups.remove(g)
        state.ungroupedOrder.addAll(g.files)
        normalize()
    }

    /** index is a drop position in the destination BEFORE removing the source. */
    @Synchronized fun moveFile(url: String, targetId: String?, index: Int = Int.MAX_VALUE) {
        require(url.isNotBlank())
        val destination = if (targetId == null) state.ungroupedOrder else state.groups.find { it.id == targetId }?.files ?: return
        val oldIndex = destination.indexOf(url)
        val corrected = if (oldIndex >= 0 && oldIndex < index) index - 1 else index
        state.groups.forEach { it.files.remove(url) }
        state.ungroupedOrder.remove(url)
        destination.add(corrected.coerceIn(0, destination.size), url)
    }

    @Synchronized fun moveGroup(id: String, index: Int) {
        val oldIndex = state.groups.indexOfFirst { it.id == id }
        if (oldIndex < 0) return
        val group = state.groups.removeAt(oldIndex)
        state.groups.add((if (oldIndex < index) index - 1 else index).coerceIn(0, state.groups.size), group)
    }

    @Synchronized fun forgetFile(url: String) {
        state.groups.forEach { it.files.remove(url) }; state.ungroupedOrder.remove(url)
    }

    @Synchronized fun orderedUngrouped(openUrls: List<String>): List<String> {
        val grouped = state.groups.flatMap { it.files }.toSet()
        val open = openUrls.filter { it !in grouped }.distinct()
        return state.ungroupedOrder.filter { it in open } + open.filter { it !in state.ungroupedOrder }
    }

    @Synchronized fun syncUngroupedOrder(displayedUrls: List<String>) {
        val grouped = state.groups.flatMap { it.files }.toSet()
        val displayed = displayedUrls.filter { it !in grouped }.distinct()
        state.ungroupedOrder = (displayed + state.ungroupedOrder.filter { it !in displayed }).toMutableList()
    }

    @Synchronized fun saveSession(name: String, openUrls: List<String>, activeUrl: String?): String {
        require(name.isNotBlank())
        val s = SessionState().also {
            it.id = UUID.randomUUID().toString(); it.name = name.trim()
            it.groups = state.groups.mapTo(ArrayList()) { g -> g.copy() }
            val groupedOpen = state.groups.flatMap { g -> g.files }.filter { url -> url in openUrls }
            it.openFiles = (groupedOpen + orderedUngrouped(openUrls)).distinct().toMutableList()
            it.activeFile = activeUrl.orEmpty()
            it.ungroupedCollapsed = state.ungroupedCollapsed
        }
        state.sessions.add(s)
        return s.id
    }

    @Synchronized fun applySession(id: String): SessionState? {
        val session = state.sessions.find { it.id == id }?.copy() ?: return null
        state.groups = session.groups.mapTo(ArrayList()) { it.copy() }
        state.ungroupedOrder = ArrayList(session.openFiles)
        state.ungroupedCollapsed = session.ungroupedCollapsed
        normalize()
        return session
    }
    /** Back up before replacing, retaining the newest five automatic backups and every manual session. */
    @Synchronized fun applySessionWithBackup(id: String, openUrls: List<String>, activeUrl: String?, backupName: String): SessionState? {
        if (state.sessions.none { it.id == id }) return null
        val backupId = saveSession(backupName, openUrls, activeUrl)
        state.sessions.last { it.id == backupId }.recoveryBackup = true
        val restored = applySession(id)
        val expired = state.sessions.filter { it.recoveryBackup }.dropLast(5).map { it.id }.toSet()
        state.sessions.removeIf { it.id in expired }
        return restored
    }
    @Synchronized fun deleteSession(id: String) { state.sessions.removeIf { it.id == id } }

    /** A directory rename rewrites descendants, never similarly named sibling paths. */
    @Synchronized fun replaceUrlPrefix(oldUrl: String, newUrl: String): Boolean {
        if (oldUrl == newUrl) return false
        fun matches(url: String) = url == oldUrl || url.startsWith("$oldUrl/")
        if (state.groups.none { it.files.any(::matches) } && state.ungroupedOrder.none(::matches) &&
            state.sessions.none { it.openFiles.any(::matches) || matches(it.activeFile) || it.groups.any { g -> g.files.any(::matches) } }) return false
        var changed = false
        fun rewrite(url: String): String = when {
            url == oldUrl -> newUrl.also { if (it != url) changed = true }
            url.startsWith("$oldUrl/") -> (newUrl + url.removePrefix(oldUrl)).also { if (it != url) changed = true }
            else -> url
        }
        fun rewriteGroups(groups: List<GroupState>) { groups.forEach { g -> g.files = g.files.mapTo(ArrayList(), ::rewrite) } }
        rewriteGroups(state.groups)
        state.ungroupedOrder = state.ungroupedOrder.mapTo(ArrayList(), ::rewrite)
        state.sessions.forEach { s ->
            rewriteGroups(s.groups); s.openFiles = s.openFiles.mapTo(ArrayList(), ::rewrite)
            s.activeFile = rewrite(s.activeFile)
        }
        if (changed) normalize()
        return changed
    }
}

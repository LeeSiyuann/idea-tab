package dev.ideatab.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import dev.ideatab.service.TabsService

object GroupDialogs {
    val nonBlank = object : InputValidator {
        override fun checkInput(inputString: String) = inputString.isNotBlank()
        override fun canClose(inputString: String) = checkInput(inputString)
    }
    fun newGroup(project: com.intellij.openapi.project.Project): String? {
        val name = Messages.showInputDialog(project, "分组名称：", "创建标签组", null, "", nonBlank) ?: return null
        var id: String? = null
        TabsService.get(project).change { id = createGroup(name) }
        return id
    }
}

class AddToGroupAction : DumbAwareAction() {
    private data class Choice(val index: Int, val label: String) { override fun toString() = label }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = TabsService.get(project)
        val groups = service.state.groups
        val options = arrayOf("新建分组…", "未分组", *groups.map { it.name }.toTypedArray())
        JBPopupFactory.getInstance().createPopupChooserBuilder(options.mapIndexed { i, label -> Choice(i, label) })
            .setTitle("将 ${file.name} 加入")
            .setItemChosenCallback { choice ->
                val index = choice.index
                val target = when (index) {
                    0 -> GroupDialogs.newGroup(project) ?: return@setItemChosenCallback
                    1 -> null
                    else -> groups[index - 2].id
                }
                service.change { moveFile(file.url, target) }
                FileEditorManager.getInstance(project).openFile(file, true, true)
                ToolWindowManager.getInstance(project).getToolWindow("Tab Harbor")?.show()
            }.createPopup().showInBestPositionFor(e.dataContext)
    }
}

class ShowTabsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Tab Harbor")?.activate(null)
    }
}

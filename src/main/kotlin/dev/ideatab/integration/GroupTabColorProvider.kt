package dev.ideatab.integration

import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ideatab.service.TabsService
import dev.ideatab.ui.GroupColors
import java.awt.Color

class GroupTabColorProvider : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? {
        val index = TabsService.get(project).model.nativeColorIndex(file.url) ?: return null
        val color = GroupColors.get(index)
        return Color(color.red, color.green, color.blue, 45)
    }
}

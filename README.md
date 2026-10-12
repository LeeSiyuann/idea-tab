# Tab Harbor

适配 IntelliJ IDEA 的 Edge 式垂直标签管理插件。当前版本 **0.1.1**，支持 IDEA **2025.3（253 系列）**，开发基线是 **2025.3.4**。

## 安装

1. 获取 `build/distributions/idea-tab-0.1.1.zip`。
2. 在 IDEA 打开 **Settings → Plugins → 齿轮 → Install Plugin from Disk…**，选择该 ZIP。
3. 按 IDEA 的提示重启，然后打开 **View → Tool Windows → Tab Harbor**。

插件使用左侧的第二工具窗口区域，可通过 IDEA 的工具窗口菜单移动到其他位置。

## 使用

- **新建组**：创建标签组；右键组头可重命名、选颜色和删除。
- **加入分组**：右键原生编辑器标签，选择“加入 Tab Harbor 分组…”；也可在侧栏右键文件。
- **拖拽**：在侧栏调整文件顺序、跨组移动文件、调整组顺序。搜索期间禁用拖拽，避免隐藏成员导致位置歧义。
- **搜索**：匹配文件名、路径或组名，包括空分组和隐藏了全部关闭成员的分组。输入后合并刷新；Enter 和 Down 会先立即完成搜索，Escape 清空搜索。
- **切换与关闭**：点击文件切换编辑器；点击 ×、中键或 Delete 关闭文件。Enter 打开列表中选中的文件。
- **折叠**：折叠只影响侧栏，文件保持打开。普通分组及“未分组”的折叠状态均按项目保存。
- **关闭分组**：关闭成员文件并保留分组；右键组头可重新打开。
- **保存 / 会话**：保存当前打开文件、分组、折叠状态和当前文件；恢复时可选择“仅追加文件（保留当前分组）”或“替换分组并追加文件（自动备份）”。
- **选项**：显示或隐藏已关闭的组成员；可选择为原生编辑器标签添加组颜色。

灰色成员是已关闭文件；“已失效”说明原路径不可访问，可在右键菜单移除。文件或目录重命名会更新分组与已保存会话中的路径。

替换分组前会自动保存“恢复前备份”，可从会话菜单恢复此前的分组整理；保留最近 **5** 个自动备份，手动保存的会话不受此限制。恢复备份同样属于追加操作，不会关闭后来打开的文件。

批量恢复会在修改分组或打开文件前检查标签容量。容量不足时可选择提高 IDEA 标签上限或取消；取消不修改文件、分组，也不生成备份。上限按“原有文件 + 可恢复文件”的去重总数保守估算，因此多分屏情况下可能高于实际需要。提高上限需在插件弹窗中选择继续，此修改对所有项目生效并保留，之后可在 IDEA 设置中手动调回。

## 行为边界

- 以文件为管理单位。同一文件在多个分屏中只显示一项；关闭该项会关闭它在所有分屏中的编辑器。
- 会话只保存文件列表、分组、顺序、折叠状态和当前文件，不保存编辑器内容、光标位置或分屏布局。
- 批量恢复期间暂时禁用 IDEA 的“复用未修改标签”策略，结束后恢复原设置。恢复结果同时检查原有标签和新增标签；其他插件或 IDE 行为导致文件未保持打开时会报告数量及路径，不会删除文件内容。
- 已打开的临时 / 内存文件可在侧栏切换和分组；其内容不随会话保存。临时文件关闭后或重启后，若原 URL 无法解析，恢复会提示跳过该文件。
- 原生固定标签仍由 IDEA 管理。显式关闭文件会关闭固定标签；批量“关闭其他文件”会在操作前说明这一点。
- 侧栏顺序与原生标签顺序独立。可在 IDEA 的编辑器标签设置中手动隐藏原生顶部标签；插件不会自动修改该设置。
- 状态存储在每个项目的 `.idea/workspace.xml` 中，不包含文件内容，不上传到服务器。
- 原生标签着色默认关闭；开启后可能与其他标签着色插件产生优先级冲突。
- 2026.1 / 2026.2 适配、按规则自动分组、最近关闭标签、多选批量操作和完整工作区切换留待后续版本实现与验证；不能把首版侧栏视为完整 Edge 标签功能。

## 构建和测试

需要 Java 21；可以直接使用 IDEA 随附的 JBR。工程包含 Gradle Wrapper。

Windows PowerShell：

```powershell
.\build.ps1
.\build.ps1 -Verify
.\build.ps1 -RunIde
.\build.ps1 -IdePath 'D:\Apps\IntelliJ IDEA 2025.3.4' -Verify
```

通用 Gradle 命令（先将 `JAVA_HOME` 指向 Java 21）：

```text
gradlew test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
gradlew verifyPlugin
gradlew runIde
```

不传 `localIdePath` 时，构建工具会下载固定版本 IDEA 2025.3.4；使用本地安装可传 `-PlocalIdePath=...`。
`runIde` 使用工程下的开发沙箱，不把插件安装到日常 IDEA 配置中。

测试覆盖分组归属、拖拽排序、隐藏成员排序、会话快照隔离与自动备份、路径重写、XML 保存、空分组搜索、临时标签切换、Windows 相对目录、折叠恢复，以及批量恢复容量取消 / 保护 / 异常丢失提示。

当前版本的验证结果（2026-10-12）：

- 35 项测试全部通过：17 项模型测试、9 项平台集成测试、6 项批量恢复测试和 3 项目录显示测试。
- 官方插件结构检查通过。
- Plugin Verifier 对 IDEA 2025.3.4（IU-253.32098.37）的结果为 `Compatible`，无插件 API 兼容性问题或弃用/实验 API 警告。
- 标签容量边界使用可淘汰标签的模拟编辑器验证；平台测试验证真实编辑器对象和侧栏操作。真实桌面拖拽、多分屏容量边界、重启恢复、主题与缩放比例仍需手工验证。

本地测试报告位于 `build/reports/tests/test/index.html`，兼容性报告位于 `build/reports/pluginVerifier/IU-253.32098.37/report.html`。

## 代码结构

```text
src/main/kotlin/dev/ideatab/
  model/         分组及会话模型，无 IDE 依赖
  service/       项目状态保存、VFS 重命名同步
  ui/            垂直侧栏、拖拽、搜索和操作菜单
  actions/       原生标签右键入口
  integration/   原生标签着色、批量恢复计划与容量保护
```

## 开发资料

- [IntelliJ Tool Windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)
- [Editors API](https://plugins.jetbrains.com/docs/intellij/editors.html)
- [状态持久化](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html)
- [插件兼容性检查](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
- [Gradle 插件](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)

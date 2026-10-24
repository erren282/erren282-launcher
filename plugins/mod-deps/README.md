# 前置 Mod 自动下载插件（org.dsh.mod-deps）

下载 Mod 后自动补齐它的**必需前置依赖**——包括前置的前置（传递依赖）。
解析 Modrinth / CurseForge 的依赖关系，只把目标实例中**缺失**的前置下载到 `mods` 目录。

## 工作原理

1. 启动器在“下载 → Mod”（以及 Mod 列表的更新安装）完成一次下载后，通过
   `PluginAddonService.fireAddonDownloaded` 通知插件（新扩展点，见 `docs/plugin/plugin-api.md`）；
2. 插件读取该实例的游戏版本与 Mod 加载器（Fabric / Forge / NeoForge / Quilt…）；
3. 后台线程广度优先解析依赖图：
   - 只跟随 `REQUIRED`（必需）依赖，忽略 OPTIONAL / EMBEDDED / INCLUDE / TOOL / INCOMPATIBLE；
   - 每个依赖项目选取「游戏版本匹配 + 加载器匹配」中发布日期最新的版本；
   - 按项目 id 去重，循环依赖不会死循环；
   - 已安装的依赖跳过下载，但仍会继续检查它的前置；
4. 有缺失前置时弹出启动器任务对话框批量下载（带进度、可取消、校验哈希）；
   全部已满足时只写日志，不打扰用户。

“已安装”的判定：`mods` 目录存在同名文件（含 `.disabled`），或已安装 Mod 的 id
与依赖项目的 slug 相同。

## 行为细节

- 只在从下载页安装 Mod 时触发；手动拖入 jar、Mod 包安装等其他入口不会触发；
- 资源包 / 光影（`resourcepacks` / `shaderpacks`）不受影响；
- 插件所有输出带 `[org.dsh.mod-deps]` 前缀，位于 `.hmcl\logs\`；
- 部分依赖解析失败（网络错误、项目被删除）只记日志，不影响其他依赖的下载。

## 构建与安装

```powershell
# 本仓库先构建出 HMCL jar，然后：
cd docs\plugin\mod-deps
.\build.ps1
```

产物 `dist\org.dsh.mod-deps_1.0.0.jar` 放入启动器工作目录的 `.hmcl\plugins\`，
重启启动器或在 设置 → 插件 点击“重新加载插件”。

要求启动器版本 ≥ 3.16.4（本仓库含 `PluginAddonService` 扩展点的版本）。

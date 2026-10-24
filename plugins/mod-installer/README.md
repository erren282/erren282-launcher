# 拖入安装 Mod

把一个 Mod 文件拖进 HMCL 主窗口，插件读取它的元数据，找出本机与之匹配的游戏版本，确认后直接安装。

## 功能

- 从 `fabric.mod.json`、`META-INF/mods.toml`、`quilt.mod.json`、`mcmod.info` 等元数据里解析
  Mod 支持的游戏版本与加载器（Fabric / Forge / NeoForge / Quilt）
- 列出本机符合条件的所有游戏版本，让用户选择安装到哪一个（而不是静默猜一个）
- 安装前检查前置 Mod 是否缺失，缺失时提供自动下载
- 一个文件同时支持多个版本/加载器时给出提示，交给用户判断

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

产物放入 `.hmcl/plugins/`。

## 使用

1. 确保插件已安装并在「设置 → 插件」中启用；
2. 在主页面把 `.jar` 格式的 Mod 文件拖入窗口；
3. 在弹出的窗口里选择目标游戏版本并确认。

## 清单

```json
{
  "id": "org.dsh.mod-installer",
  "entry": "org.dsh.modinstall.ModInstallerPlugin",
  "minLauncherVersion": "3.16.4"
}
```

## 相关插件

- [`mod-deps`](../mod-deps) —— 前置 Mod 依赖解析（本插件安装前会用到同样的能力）
- [`mod-to-version`](../mod-to-version) —— 从下载页直接把内容下到指定版本

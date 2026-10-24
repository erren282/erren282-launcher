# HMCL 定制版 · 插件合集

[Hello Minecraft! Launcher](https://github.com/HMCL-dev/HMCL) 的定制版源码，附带一套在其插件系统上运行的插件。

[![Topic](https://img.shields.io/badge/topic-erren282s--launcher--plugins-blue)](https://github.com/topics/erren282s-launcher-plugins)

- 主 topic：`erren282s-launcher-plugins` —— 聚合页 <https://github.com/topics/erren282s-launcher-plugins>
- 通用 topic：`hmcl-plugin` —— <https://github.com/topics/hmcl-plugin>

- **启动器源码**：`HMCL/`、`HMCLCore/`、`HMCLBoot/`（含 UI、下载、账户、Mod 解析与插件系统）
- **插件源码**：`plugins/`（10 个插件，逐个目录，附 `hmcl.plugin.json` 与构建脚本）
- **插件清单与说明**：见 [**PLUGINS.md**](PLUGINS.md)
- **插件开发文档**：`docs/plugin/plugin-api.md`、`docs/plugin/plugin-format.md`

> 插件依赖本仓库定制版 HMCL 3.16.4 的插件系统（清单式加载、进程内运行）。
> 上游 HMCL 没有该机制，插件不能直接装到官方版上。

## 快速开始

```bash
# 构建启动器
./gradlew :HMCL:build

# 构建某个插件
cd plugins/server-manager
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

生成的插件 jar 放进启动器目录的 `.hmcl/plugins/`，重启或在「设置 → 插件」里重新加载即可。

## 插件列表

| 插件 | 目录 |
| --- | --- |
| 账户皮肤切换 | `plugins/account-skin` |
| CurseForge Key | `plugins/curseforge-key` |
| Lunar 离线客户端 | `plugins/lunar-offline` |
| MciLm 联机 | `plugins/mcilm-link` |
| 前置 Mod 自动下载 | `plugins/mod-deps` |
| 拖入安装 Mod | `plugins/mod-installer` |
| 下载到指定版本 | `plugins/mod-to-version` |
| 服务器模块 | `plugins/server-hub` |
| 服务器列表 | `plugins/server-list` |
| 服务器管理器 | `plugins/server-manager` |

每个插件的功能、用法与构建方式见其目录下的 `README.md`，汇总说明见 [PLUGINS.md](PLUGINS.md)。

## 说明

- `plugins/*/dist`、`out`、`resources` 等构建产物与游戏客户端大文件未纳入版本库（见 `.gitignore`）。
- 启动器主体遵循上游 HMCL 的 GPL-3.0 许可，见 [LICENSE](LICENSE)。
- 上游项目：<https://github.com/HMCL-dev/HMCL>

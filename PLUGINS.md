# HMCL 插件合集

面向 [Hello Minecraft! Launcher (HMCL)](https://github.com/HMCL-dev/HMCL) 3.16.4+ 的插件源码合集。

本仓库是 **HMCL 定制版启动器 + 插件集合**：根目录是启动器源码，`plugins/` 下是随该启动器一同使用的插件。

> 这些插件依赖 HMCL 3.16.4 的插件系统（`hmcl.plugin.json` 清单 + 进程内加载），
> 上游 HMCL 没有该机制，因此插件只在配套的定制版启动器上工作。

## 插件一览

| 插件 | 目录 | 说明 |
| --- | --- | --- |
| 账户皮肤切换 | [`plugins/account-skin`](plugins/account-skin) | 切换/管理账户皮肤，隐藏原生上传按钮 |
| CurseForge Key | [`plugins/curseforge-key`](plugins/curseforge-key) | 配置 CurseForge API Key 并把请求转发到可用镜像 |
| Lunar 离线客户端 | [`plugins/lunar-offline`](plugins/lunar-offline) | 下载页集成 Lunar 客户端，支持离线启动 |
| MciLm 联机 | [`plugins/mcilm-link`](plugins/mcilm-link) | 通过 MciLm-link 跨网联机 |
| 前置 Mod 自动下载 | [`plugins/mod-deps`](plugins/mod-deps) | 解析并批量下载 Mod 的传递前置依赖 |
| 拖入安装 Mod | [`plugins/mod-installer`](plugins/mod-installer) | 主界面拖入 Mod 文件即可选择版本安装 |
| 下载到指定版本 | [`plugins/mod-to-version`](plugins/mod-to-version) | Mod/资源包/光影/世界的下载弹窗增加「下载到对应版本」 |
| 服务器模块 | [`plugins/server-hub`](plugins/server-hub) | 分组页，聚合服务器相关子页面 |
| 服务器列表 | [`plugins/server-list`](plugins/server-list) | 服务器列表管理与快速加入 |
| 服务器管理器 | [`plugins/server-manager`](plugins/server-manager) | 服务端下载、配置与控制台 |

## 目录结构

```
.
├── HMCL/                  # 启动器主模块（UI、设置、插件系统）
├── HMCLCore/              # 核心模块（游戏、下载、账户、Mod 解析）
├── HMCLBoot/              # 引导模块
├── buildSrc/              # Gradle 构建逻辑
├── gradle/                # Gradle Wrapper
├── plugins/               # 插件源码（每个插件一个目录）
│   └── <plugin-id>/
│       ├── src/                    # Java 源码
│       ├── hmcl.plugin.json        # 插件清单
│       ├── build.ps1               # 编译打包脚本
│       └── README.md               # 插件说明
├── docs/                  # 插件开发文档（plugin-api.md / plugin-format.md）
├── build.gradle.kts
└── settings.gradle.kts
```

## 构建启动器

```bash
./gradlew :HMCL:build
```

在 Windows 上也可以直接用 `gradlew.bat`。产物位于 `HMCL/build/libs/`。

## 构建插件

每个插件目录下的 `build.ps1` 会调用 `javac` 编译源码并打包成插件 jar：

```powershell
cd plugins/server-manager
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

编译期 classpath 只需要启动器 jar（插件 API 由启动器提供），产物是标准 Java jar。
把生成的 jar 放进启动器工作目录的 `.hmcl\plugins\` 即可。

## 安装插件

1. 构建启动器并运行；
2. 打开 `设置 → 插件`，或直接把插件 jar 放入 `<启动器目录>\.hmcl\plugins\`；
3. 重启启动器或点击「重新加载插件」。

## 插件开发

插件系统的完整说明见：

- [`docs/plugin/plugin-format.md`](docs/plugin/plugin-format.md) —— 清单字段与加载规则
- [`docs/plugin/plugin-api.md`](docs/plugin/plugin-api.md) —— 生命周期、上下文、界面扩展点

最小插件：

```java
public final class MyPlugin extends HmclPlugin {
    @Override public void onEnable() {
        getContext().logInfo("hello");
    }
    @Override public void onDisable() { }
}
```

配套 `hmcl.plugin.json`：

```json
{
  "id": "org.example.my-plugin",
  "name": "我的插件",
  "version": "1.0.0",
  "entry": "org.example.myplugin.MyPlugin",
  "minLauncherVersion": "3.16.4",
  "access": "root"
}
```

## 许可证

启动器部分遵循上游 HMCL 的 GPL-3.0（见 [`LICENSE`](LICENSE)）。
各插件的许可与署名见其目录下的 `README.md`。

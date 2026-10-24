# HMCL 定制版启动器

[Hello Minecraft! Launcher](https://github.com/HMCL-dev/HMCL) 3.16.4 的定制版源码，主要改动是**内建插件系统**。

- 插件以标准 Java jar + `hmcl.plugin.json` 清单的形式加载，运行在启动器进程内
- 插件可注册设置页、侧边栏页面、下载标签页、崩溃处理等扩展点
- 插件开发文档见 [`docs/plugin/plugin-api.md`](docs/plugin/plugin-api.md) 与
  [`docs/plugin/plugin-format.md`](docs/plugin/plugin-format.md)

> 上游 HMCL 没有插件机制，基于本仓库开发的插件无法装到官方版上。

## 模块

| 模块 | 说明 |
| --- | --- |
| `HMCL/` | 启动器主模块：UI、设置、下载、账户、插件系统 |
| `HMCLCore/` | 核心模块：游戏管理、版本解析、Mod 解析 |
| `HMCLBoot/` | 引导模块 |
| `buildSrc/` | Gradle 构建逻辑 |

## 构建

```bash
./gradlew :HMCL:build
```

Windows 用 `gradlew.bat`。产物在 `HMCL/build/libs/`。

## 安装插件

把插件 jar 放进启动器工作目录的 `.hmcl/plugins/`，重启或在「设置 → 插件」里重新加载。

## 配套插件

插件已拆分为独立仓库，可用下面的 topic 查看全部：

- <https://github.com/topics/erren282s-launcher-plugins>

## 许可证

遵循上游 HMCL 的 GPL-3.0，见 [LICENSE](LICENSE)。

上游项目：<https://github.com/HMCL-dev/HMCL>

---

## 说明：本项目为 Vibe Coding 产物

**本项目的全部代码均由 AI（大语言模型）生成**，通过对话式编程（vibe coding）完成——
人类只提出需求、给出反馈与验收，不手写实现代码。

- 代码可能包含未经验证的边界情况、不一致的风格，或与常规工程实践不同的设计取舍
- 请在生产环境使用前自行审查、测试与评估
- 基于上游 [HMCL](https://github.com/HMCL-dev/HMCL) 修改，上游版权归其作者所有
- 欢迎提交 issue 或 PR 改进

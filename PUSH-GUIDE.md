# 仓库维护指南

本仓库已推送到 GitHub：

- 仓库：<https://github.com/erren282/hmcl-plugins>
- 默认分支：`main`
- 主 topic：`erren282s-launcher-plugins`（聚合页 <https://github.com/topics/erren282s-launcher-plugins>）

## 日常更新流程

插件源码的**日常维护位置**是启动器的插件目录：

```
E:\game\gamea\mcl\HMCL\docs\plugin\<插件名>\
```

改完之后，把改动同步到本仓库再推送：

```powershell
cd E:\game\gamea\mcl\hmcl-github
git add -A
git commit -m "更新 <插件名>：<说明>"
git push
```

## 从源目录同步某个插件

只同步源码与文档，跳过构建产物与大二进制：

```powershell
$src = 'E:\game\gamea\mcl\HMCL\docs\plugin\server-manager'
$dst = 'E:\game\gamea\mcl\hmcl-github\plugins\server-manager'
robocopy $src $dst /E /XD out out-test out-probe out-wrapper dist resources /NFL /NDL /NJH /NJS
```

> `resources/` 只对 `lunar-offline` 有意义（里面是游戏客户端），本仓库不收录。

## 新增插件

1. 在 `E:\game\gamea\mcl\HMCL\docs\plugin\<新插件>\` 下开发；
2. 复制到 `plugins/<新插件>/`（同上排除规则）；
3. 在 [`PLUGINS.md`](PLUGINS.md) 的表格里加一行；
4. 提交推送。

## 目录约定

每个插件目录应包含：

| 文件 | 说明 |
| --- | --- |
| `src/` | Java 源码 |
| `hmcl.plugin.json` | 插件清单（id、entry、版本） |
| `build.ps1` | 编译打包脚本 |
| `README.md` | 功能、构建与使用说明 |

## 构建验证

构建启动器（作为插件编译的 classpath）：

```bash
./gradlew :HMCL:build
```

构建单个插件：

```powershell
cd plugins/server-manager
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

产物在 `dist/`，放入启动器目录的 `.hmcl/plugins/` 即可。

## 不要提交的内容

`.gitignore` 已排除：`out/`、`dist/`、`*.jar`（`lib/` 与 `gradle/wrapper/` 除外）、
`plugins/*/resources/`、`.hmcl/`、日志与 IDE 配置。提交前可用下面的命令自查：

```powershell
git status --porcelain
git diff --cached --name-only | Where-Object { $_ -match '\.(jar|class|exe)$' }
```

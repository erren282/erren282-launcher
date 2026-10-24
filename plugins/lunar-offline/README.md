# Lunar 客户端插件（官方源直接下载 + 一键启动，可选内置 Celestial 核心）

把 **Lunar Client** 搬进 HMCL：在启动器的「下载」页面里选一个 Lunar 版本，下载并安装成 HMCL
自己的游戏版本，然后直接启动——**全程不需要 Celestial，也不需要 Lunar 官方启动器**。

## 推荐用法：下载页的「Lunar 客户端」标签

「下载」页面侧边栏 → **Lunar 客户端**。这个页面是**照着启动器自己的「游戏」下载页做的**：
搜索栏、分类筛选、列表单元格、加载/失败/空列表的转场动画、两步式流程，全部同构，
只是下载对象从 Minecraft 换成了 Lunar 客户端。

### 第一步：选版本

与启动器版本下载页一一对应：

| 启动器版本下载页 | Lunar 客户端页 |
| --- | --- |
| 搜索栏（`version.search` / `version.search.prompt`） | 同左，按 Lunar 版本号过滤，支持 `regex:` 前缀 |
| 「版本类型」下拉（`version.game.type`） | 同左：全部 / 正式版 / 1.8 / 1.9+ |
| 「刷新」按钮 | 同左，重新拉取官方列表 |
| 列表：32px 图标 + 两行文字 + 右侧进入按钮 | 同左，图标为 Lunar 官方 logo（客户端已下载时）或启动器自带图标 |
| 加载中转圈 → 列表 / 失败 / 空 淡入切换 | 同左（`TransitionPane` + `ContainerAnimations.FADE`） |

文案直接复用启动器自己的 i18n key（`version.search`、`version.game.type`、`button.refresh`、
`download.failed.refresh`、`download.failed.empty`），所以中文界面上两个页面读起来完全一致。

### 第二步：选模块

点某一行的箭头后进入，对应启动器的加载器选择页：

- 顶部卡片显示安装目标（`26.1.1 → Lunar-26.1.1` 与目标游戏目录）；
- 中间是模块单选列表（Lunar / Lunar + Fabric …，来自官方接口，默认取官方标记的默认模块）；
- 底部是状态与操作：缺少基础版本时提示并提供「前往下载原版」，已安装过则出现「启动游戏」。

流程细节：

1. 选一个 Lunar 版本 → 进入模块页；
2. 点「下载并安装」：从 Lunar 官方源下载整份客户端（classpath jar、OptiFine 等外部文件、
   native、贴图、UI、许可证，首次约 130 MB），解压到 `<游戏目录>/lunar-client/<版本>/`，
   生成 `versions/Lunar-<版本>/Lunar-<版本>.json`（非默认模块会带上模块名后缀）；
3. 点「启动游戏」（或直接在启动器版本列表里选 `Lunar-1.8.9`）。

实现细节：

- 版本目录来自 `GET https://api.lunarclientprod.com/launcher/metadata/versions/lunar`；
  工件清单来自 `POST https://api.lunarclientprod.com/launcher/launch`。两个都是 Lunar 启动器
  自己在用的公开接口，不需要账号。注意请求里 `os` 要用 Node 风格的 `win32`（用 `windows` 会被
  服务端以 `NO_JRE_FOUND` 拒绝）。
- 下载按 sha1 校验，重复下载直接命中缓存；native 压缩包与 UI 会自动解压；单个文件中断会自动重试 3 次
  （Lunar 的 CDN 偶尔会在传输中途断连）。
- JVM 参数用清单里的 `jre.extraArguments` + `-Djava.library.path=natives`，并且**自己写上
  `-cp ${classpath}`**：启动器只在版本 json 没有 `arguments.jvm` 时才补默认 JVM 参数
  （默认参数里才有 `-cp ${classpath}`，见 `Arguments.DEFAULT_JVM_ARGUMENTS`）；一旦提供了
  `arguments.jvm`，启动器就原样使用这一份 —— 少了 `-cp` 就是空 classpath，报
  `ClassNotFoundException: com.moonsworth.lunar.genesis.Genesis`。
- **入口用包装类，`--version` 在启动时归一化**：启动器会把基础版本的参数也拼进来
  （老式 `minecraftArguments` 或新式 `arguments.game`），其中必定有一条
  `--version ${version_name}`，值是启动器里的实例名（例如 `Lunar-1.21.11`）。Genesis 用 joptsimple
  解析参数：`--version` 出现两次会抛 `MultipleArgumentsForOptionException`，值不是合法游戏版本号
  又会抛 `IllegalArgumentException: Invalid Minecraft version`。而这条参数在 json 里**删不掉**。
  所以版本的主类写成插件自带的小包装类 `org.dsh.lunarofl.wrapper.GenesisLauncher`
  （`wrapper-src/`，随插件以 `resources/wrapper/lunar-wrapper.jar` 分发、安装到 `libraries/`）：
  它丢掉命令行里所有 `--version`，再用 `-Ddsh.lunar.gameVersion=<版本>` 补上正确的一个，
  最后反射调用 Genesis。老式/新式两种基础版本写法都能用。
- 账号、内存、Java、分辨率仍由启动器提供（账号参数来自基础版本的参数模板，
  `--width/--height` 由启动器在设置了分辨率时补上）。我们只往 `arguments.game` 里加 Lunar 专有参数，
  不重复启动器已经会给的选项。
- **需要 Java 17 及以上**：Lunar 的参数里有 `--add-modules/--add-exports/--add-opens`，
  用 Java 8 启动会被 JVM 直接拒绝（`Unrecognized option: --add-modules`，进程秒退、
  崩因显示为“未知”）。生成的版本 json 里声明了 `javaVersion`（17 / `java-runtime-beta`），
  启动器据此会挑一个 Java 17+，一个都没有时会提示下载；下载页的标签里也会显示本机是否已有 Java 17+。
- **需要基础版本已安装**（如原版 1.8.9）：HMCL 只列出继承链完整的版本；缺失时页面会提示，
  并提供「前往下载原版」按钮。可以先下载 Lunar 再补装原版。
- **继承链指向「零库壳版本」**：Lunar 的游戏类由 ichor 在运行时装配，官方启动命令的 `-cp` 只有
  Lunar 自己的 jar。而 HMCL 的版本继承会把基础版本的 `libraries` 合并进 classpath——1.8.9 原版带的
  gson 2.2.4 / guava 17 等老库会抢先于 Lunar 内置版本被加载，Client 线程直接抛
  `NoSuchMethodError`。所以安装时另外生成一个删掉 `libraries` 的壳版本
  （`<基础版本>-lunar-base`），让 Lunar 版本继承它，classpath 上就不会混进任何原版库。
  （壳版本还要把 `patches[].libraries` 一起清掉，否则 HMCL 仍会把原版库并进 classpath，
  见下面「启动失败自动修复」里的 `CreateProcess error=206`。）
- **下载进度**：安装页有一条醒目的进度条（`LunarProgressPane`）——步骤标题 + 粗进度条 + 大号百分比，
  下面一行是 `已下载 / 总量 · 速度 · 剩余时间 · 已用时间`。复制 jar 的安装阶段总量未知，
  进度条转为不确定态并显示已处理字节数；失败时进度条保留并标注「已中断」。
  外观见 `dist/progress-states.png`（三种状态）与 `dist/progress-downloading.png`（一次真实下载到 75% 时的样子）。

## 可选：内置 Celestial 核心（保留给旧流程）

插件仍然**自带 Celestial 核心**，供仍想运行 Celestial 本体的用户使用（主页面侧边栏入口）：

- **内置核心**：`resources/celestial-core/` 随插件分发（Celestial fatjar + 配套 BrowserOffline
  java agent + 许可证），首次启动释放到插件数据目录，之后复用。
- **可选外部核心**：取消勾选「使用内置核心」后可指定自己准备的 Celestial jar。
- **JDK 21**：Celestial 需要 JDK（会用到 javac），插件内置深度扫描自动识别。
- 不改动任何客户端目录，只释放到插件数据目录并以该目录为工作目录启动。

该页面同样提供「安装为 HMCL 版本」：读取 `%APPDATA%\celestial\launch-data.json`（Celestial 已下载
好的客户端）生成 HMCL 版本，用于已经用 Celestial 下过客户端的场景。

## 第三方组件与许可证

- Celestial（Apache License 2.0，来源 https://codeberg.org/earthsworth/celestial）与 BrowserOffline
  原样内置，未做任何修改；许可证与版权声明随核心一同释放，详见 `resources/celestial-core/CREDITS.md`。
- Lunar Client 的客户端文件从 Lunar 官方 CDN 直接下载，不随插件分发；插件会把官方给出的
  许可证文件（`licenses/`）一并下载到客户端目录。

## 启动失败自动修复

Lunar 的启动链路（Genesis → ichor → Forge/Mixin）对**库版本与文件位置**很敏感，任何一处不对
就是一次「启动即崩」。插件把踩过的坑固化成一张「症状 → 修法」表（`LunarRepair`），并在两个时机自动执行：

- **启动前**（`onBeforeLaunch`）：体检 + 就地修复该版本；
- **启动失败后**（`onAfterLaunch` 盯进程）：读客户端输出与最新的 `crash-reports\*.txt`，匹配到已知症状就
  再修一次，然后提示「已自动修复，请再启动一次」。

「设置 → 插件 → Lunar 启动修复」页面里能看到上次失败的原因、已做的修复、已知故障表，也可以手动全面修复。

| 症状（日志里的原话） | 插件做的事 |
|---|---|
| `NoSuchMethodError: IntArraySet.<init>(IntSet)` 等 | 按 Lunar 的 `versions.conf` 补齐它自己声明的运行库（fastutil 8.5.6、netty 4.1.16、log4j 2.22.1 …） |
| `NoSuchMethodError: BoundedInputStream.getCount()` | 运行库排在 Lunar 自己的 jar **之后**（Lunar jar 里自带 shaded 的新版类，不能被 commons-io 2.5 盖住） |
| `Could not find external file replaymod` | `--ichorExternalFiles` 归一成文件名（Genesis 拿它和 `Path.getFileName()` 做等值比较） |
| `@Mixin target ... was not found` | 把 `libraries/` 里的 classpath 副本与客户端目录重新对齐（Lunar 会自我更新 SNAPSHOT jar） |
| `Unrecognized option: --add-modules` / `depends java >= 21` | 版本 json 声明合适的 `javaVersion` |
| `Multiple garbage collectors selected` | 移除 Lunar 清单自带的 GC 选项（与启动器自带 GC 冲突） |
| `cannot inherit from final class FabricLoaderImpl` | 把 `inheritsFrom` 改指原版副本（Fabric/Forge 版会带官方 fabric-loader） |
| `ClassNotFoundException: ...genesis.Genesis` | 补上 `-cp ${classpath}` |
| `zip END header not found` | 无法凭空补回坏文件，提示重新下载客户端 |

**classpath 顺序**（两条都不能反，反了就是上面那两种 `NoSuchMethodError`）：

```
Lunar 自己的 jar  →  Lunar 声明的运行库  →  继承来的原版库（fastutil 7.1.0 等）
```

HMCL 合并继承链时子版本在前且不去重（`Lang.merge`），所以顺序就是版本 json 里 `libraries` 的顺序；
运行库由 `LunarLibraries` 从 `<客户端目录>/genesis-*.jar` 内的 `versions.conf` 读出并下载到
`<客户端目录>/lunar-libs/`（Mojang 库站 → Maven Central → BMCLAPI 依次尝试）。

## 构建与测试

```powershell
powershell -File build.ps1                       # 编译并打包到 dist/
powershell -File tools\run-test.ps1 -Class LunarDownloadTest            # 端到端：下载 1.8.9 并真实启动
powershell -File tools\run-test.ps1 -Class LunarDownloadTest -TestArgs "1.21.11"   # 换一个版本
# 第三个参数是模块 id（forge 会走 ReplayMod/Forge 那条最容易踩库冲突的路）
powershell -File tools\run-test.ps1 -Class LunarDownloadTest -TestArgs "1.12.2 E:/tmp/e2e forge"
powershell -File tools\run-test.ps1 -Class DownloadTabTest              # 下载页标签的注册与渲染
powershell -File tools\run-test.ps1 -Class LunarVersionTest             # 从 Celestial 数据安装并启动

# 给用早期版本装好的 Lunar 版本补上启动要求（javaVersion、-cp ${classpath}、外部文件名）：
# 缺 javaVersion → 启动器按 1.8.9 推断出 Java 8，跑 --add-modules 直接崩；
# 缺 -cp       → 空 classpath，报 ClassNotFoundException: ...Genesis
# 外部文件写成绝对路径 → Genesis 拿它和候选文件的 Path.getFileName() 做等值比较，永远不相等，
#                        按前缀查找外部文件的 1.12.2-forge ReplayMod 报 Could not find external file replaymod
powershell -File tools\run-test.ps1 -Class RepairLunarVersion `
    -TestArgs "E:/game/game/minecraft/.minecraft/versions/Lunar-1.8.9/Lunar-1.8.9.json"
```

> `--ichorExternalFiles` / `--ichorClassPath` 里的每一项都必须是**文件名**（官方 Celestial 传的也是 `File.getName()`），
> 因为 `--classpathDir` 已经指明了目录。绝对路径不仅多余，还会让 Genesis 的「允许的外部文件」白名单失效。

注意：`run-test.ps1` 共用 `out-test/` 目录，**不要同时跑两个测试**（后一个会清掉前一个的 class）。

`LunarDownloadTest` 会拉版本目录、下载完整客户端、生成 HMCL 版本、确认启动器的版本仓库能发现它、
验证启动器会拒绝 Java 8 而选中 Java 21，然后**完全按生成的 json + 启动器提供的 `${classpath}`
占位符**组装命令真实启动（不手工加 `-cp`，这样“忘了写 -cp”的回归会被测试抓住），
等待客户端输出 `LUNARCLIENT_STATUS_STARTED`。

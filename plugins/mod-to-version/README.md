# 下载内容到指定版本

在启动器「下载」页的**模组 / 资源包 / 光影 / 世界**四个分区的下载弹窗里，为「下载到本地文件夹」**旁边**增加一个按钮：**下载到对应版本**。

点击后弹出**符合该内容要求的已安装游戏版本**列表，选中后直接下载进那个版本：

| 分区 | 落到哪里 |
| --- | --- |
| 模组 | `mods/` |
| 资源包 | `resourcepacks/` |
| 光影 | `shaderpacks/` |
| 世界 | 解压安装到 `saves/<世界名>/` |

## 效果

```
┌─ 光影下载 - Complementary Reimagined ─────────────┐
│  ● Complementary Reimagined   稳定版本            │
│    2026 年 05 月 01 日 15:08:41                   │
│                                                   │
│  ┌─ 前置 Mod ────────────────────────────────┐    │
│  │ （启动器自己加载的依赖列表）               │    │
│  └───────────────────────────────────────────┘    │
│                                                   │
│      [下载到对应版本] [下载到本地文件夹] [取消]     │
└───────────────────────────────────────────────────┘
                    ↓ 点击
┌─ 选择要下载到哪个版本 ────────────────────────────┐
│  把「Complementary Reimagined」下载到哪个版本的    │
│  shaderpacks 目录：                               │
│                                                   │
│  1.20.4-Fabric                                    │
│  游戏版本 1.20.4  ·  Fabric                        │
│                                                   │
│  1.20.4-Forge                                     │
│  游戏版本 1.20.4  ·  Forge                         │
│                                                   │
│                                        [取消]      │
└───────────────────────────────────────────────────┘
```

## 只列出「能用」的版本

列表不是所有已安装版本，而是**满足该内容要求**的那些。各分区的规则不同：

### 模组：游戏版本 + 加载器都要匹配

| 情况 | 结果 |
| --- | --- |
| 游戏版本一致 + 加载器匹配 | ✅ 列出 |
| 游戏版本一致但加载器不同（如 Forge 版装了 Fabric Mod） | ❌ 不列出 |
| 游戏版本不一致 | ❌ 不列出 |
| Mod 没有声明加载器 | ✅ 只要游戏版本一致就列出 |
| 实例检测不到加载器，但 Mod 要求加载器 | ❌ 不列出 |

### 资源包 / 光影 / 世界：只看游戏版本

这三类**不按加载器过滤**。它们在下载源那里也会报「加载器」，但报的是 `minecraft`、`optifine`、`iris`、`canvas` 这类**启动器根本检测不到**的值；如果要求匹配，列表会被清空——功能看起来就像坏了。世界包则通常一个加载器都不报。

| 情况 | 结果 |
| --- | --- |
| 游戏版本一致 | ✅ 列出 |
| 游戏版本不一致 | ❌ 不列出 |
| 内容没有声明游戏版本 | ✅ 所有已安装版本都列出 |
| 实例的游戏版本无法识别 | ❌ 不列出（不做无法验证的承诺） |
| 一个都没有 | 弹窗说明该内容要求什么，并提示改用「下载到本地文件夹」 |

## 世界为什么要单独处理

模组、资源包、光影都是「一个文件丢进某个目录」，所以复用启动器自己的下载器即可。

世界不是——游戏只认 `saves/<世界名>/` 这样的**目录**，把 zip 丢进 `saves` 里游戏是看不到的。所以世界走另一条路，和启动器「拖入世界压缩包」时做的事一致：

1. 先问世界要用的文件夹名（预填内容名，非法字符已替换掉）；
2. 下载到临时文件；
3. 用启动器自己的 `World.install(saves, 名字)` 解压并重命名 —— 这一步会改写 `level.dat` 里的 `LevelName`，所以世界在游戏里显示成你选的名字；
4. 删掉临时文件。

已经存在同名世界时，在**下载之前**就会被拒绝，不会白下一次。

## 语言

弹窗标题是唯一能识别弹窗的抓手，而它是从启动器自己的语言文件里取的（`<section>.download.title`），所以**所有语言都能用**，不只是中文和英文：

| 语言 | 模组弹窗标题 |
| --- | --- |
| zh_CN | 模组下载 - … |
| zh_TW | 模組下載 - … |
| en | Download Mod - … |
| ja | Modダウンロード- … |
| ru | Скачать мод - … |
| es | Descargar mod - … |
| uk | Завантажити мод - … |
| ar | تنزيل مود - … |
| lzh | 引改囊 - … |

启动时会把这四种标题形状写进日志，方便排查「按钮为什么没出现」。

## 窗口很小时也能操作

启动器窗口最小只有约 **800×492** 内容区。版本列表如果直接把所有行铺开，装的游戏版本一多，弹窗就会撑到窗口底部之外——而弹窗被放在 `StackPane` 里，超出部分会被**裁掉**而不是滚动，「取消」按钮随之消失，弹窗就没法关了。

所以列表高度是**跟着窗口算的**，不是固定值：

| 窗口高度 | 列表上限 | 结果 |
| --- | --- | --- |
| 492（最小） | 154 px | 列表内部滚动，取消按钮始终可见 |
| 900 | 440 px | 列表变高，不用滚那么多 |

标题、说明和按钮行不参与压缩，只有列表伸缩。弹窗宽度也跟随窗口（380–640 px 之间），不会比窗口还宽。

顺带一提，弹窗里**没有**用启动器的 `FXUtils.smoothScrolling`：它会读启动器配置，而 `AnimationUtils` 的静态初始化在没有配置时会失败并「毒化」该类，之后弹窗在 JavaFX 线程上就会抛 `NoClassDefFoundError`。平滑滚动只是好看，能打开的弹窗才要紧。

## 为什么下载源只剩下 Modrinth

这不是插件造成的，是启动器本身的既有行为。`HMCLLocalizedDownloadListPage` 里下载源是**有条件添加**的：

```java
boolean supportedCurseForge = CurseForgeRemoteAddonRepository.isAvailable() && curseForge != null;
downloadSources.setAll("addon.modrinth");
if (supportedCurseForge) {
    downloadSources.add("addon.curseforge");
}
```

而 `isAvailable()` 只做一件事：

```java
public static boolean isAvailable() {
    return !API_KEY.isEmpty();
}
```

`API_KEY` 来自 `build.gradle.kts`：

```kotlin
val curseForgeApiKey = System.getenv("CURSEFORGE_API_KEY") ?: ""
...
add("hmcl.curseforge.apikey" to curseForgeApiKey)
```

**所以只有官方构建管道（设置了 `CURSEFORGE_API_KEY` 环境变量）打出来的 jar 才有 CurseForge。** 本地自己构建、或从别处拿到的 jar，这个 key 是空的，CurseForge 就会被静默跳过——界面上不会有任何提示，看起来就像“只剩 Modrinth 了”。

实测确认（`HMCL-test\HMCL-3.16.4.jar` 的清单里没有该属性）：

```
=== CurseForge ===
  API_KEY compiled in : <empty>
  isAvailable()       : false

  mods (模组)        -> Modrinth  (CurseForge dropped: no API key)
  resource packs     -> Modrinth  (CurseForge dropped: no API key)
  shaders (光影)      -> Modrinth  (CurseForge dropped: no API key)
  modpacks (整合包)   -> Modrinth  (CurseForge dropped: no API key)
  worlds (世界)       -> <none>    (CurseForge dropped: no API key)
```

日志里也是这个原因：

```
Failed to search addons: https://api.curseforge.com/v1/mods/search?... 
response code: 403, data: Forbidden: API Key missing or invalid
```

注意**世界分区一个源都没有**：Modrinth 根本没有世界分区（`worldTab` 用的是 `new DownloadListPage(CurseForgeRemoteAddonRepository.WORLDS)`，纯 CurseForge）。没有 key 时它就无源可选。

### 想恢复 CurseForge

1. 申请 CurseForge API key（https://console.curseforge.com/）；
2. 构建时提供：

```powershell
$env:CURSEFORGE_API_KEY = '<你的 key>'
.\gradlew.bat :HMCL:build
```

或者运行时直接给 JVM 参数（`API_KEY` 会读这个系统属性）：

```
-Dhmcl.curseforge.apikey=<你的 key>
```

用 `-Dhmcl.curseforge.apikey=dummy` 实测过，四个分区的源立刻恢复成 `Modrinth + CurseForge`，世界分区恢复成 `CurseForge`。当然 dummy key 会被服务端拒绝，只是证明隐藏逻辑就是这一条。

顺带说明：“游戏”分区那栏的「下载源」是另一套东西（下载提供方，如 BMCLAPI / 官方源），和这里的内容源无关，别混淆。

## 实现要点

启动器这个弹窗是私有类（`DownloadPage$AddonVersion`）：**没有 id、没有 style class、也没有保存它正在显示的版本**。所以插件靠三个公开可达的东西切入：

1. `Controllers.getDecorator().getDecorator().getDrawerWrapper()` —— 公开的弹窗容器，`JFXDialog.show()` 会把弹窗加进它的 children；
2. 弹窗内容是 `JFXDialogPane`，启动器用 `push()` 往里塞内容；
3. 任何 `JFXDialogLayout` 都公开 `getActions()`，也就是按钮所在那一行。

**顺序很关键**：`DialogUtils.show` 先 `dialog.show()`（把弹窗加进容器），**之后**才 `dialogPane.push(content)`。所以监听容器拿到 `JFXDialog`、再监听它的 pane 拿到内容，才能接住弹窗。

> 一开始我改成等 `DialogUtils` 公开的 `PROPERTY_DIALOG_PANE_INSTANCE` 属性 —— 那个属性**只在第一次显示弹窗时才写入**，所以启动后永远等不到，实测 40 秒后放弃。这个 bug 是靠实机跑日志发现的，静态测试抓不到。

版本名从**标题文字**读取（`<分区>下载 - <版本名>`），这是唯一能拿到「弹窗正在显示哪个版本」的途径。拿到名字后在该内容的版本列表里找回对应版本。

文件类下载复用启动器自己的下载器（`DownloadPage.download(..., subdirectoryName)`），所以文件落点正确，启动器下载后的处理也会照常执行。

## 测试

| 测试 | 断言数 | 覆盖内容 |
| --- | --- | --- |
| `DialogHeaderTest` | 53 | 四个分区都能识别；**九种语言**的标题都能解析；整合包与其他弹窗不认；名称里的破折号/加号完整保留 |
| `VersionMatcherTest` | 31 | 模组按加载器过滤；**资源包/光影/世界不按加载器过滤**；未知游戏版本不误判；回退到另一个平台构建 |
| `WorldNameTest` | 21 | 世界文件夹名的非法字符替换、空格折叠、退化输入；临时文件扩展名回退 |
| `WorldInstallTest` | 11 | **真实 zip + 真实 NBT**：世界解压进 `saves`、`level.dat` 名字被改写、同名世界被拒绝 |
| `DialogSizingTest` | 22 | **真实弹窗**在最小窗口下的布局：20 个版本时取消按钮仍有正常尺寸且在窗口内；列表上限跟随窗口；窗口变大列表变高 |
| `RepositorySelectionTest` | 27 | 每个分区只向自己的仓库查找（资源包不会去问模组仓库）；CurseForge 无 key 时不出现在候选里；世界分区无 key 时确实为空 |
| `InjectorTest` | 40 | 真实 JavaFX：四个分区各自的按钮注入、分区与版本名回传、取消按钮仍在最右、英文界面、完整复现启动器的显示顺序 |
| `LoadedReleasesTest` | 13 | 反射读取启动器已加载版本列表的快路径与降级 |
| **合计** | **218** | 全部通过 |

`DialogSizingTest` 是照着用户报的问题写的：把列表上限去掉，它会有 3 条断言失败（已实测验证），所以能真正防住回归。

运行：

```powershell
$env:JAVA_HOME='E:\ZULU25'
javac "@javac-main.args"
javac "@javac-test.args"
java -cp "out-test;out;<HMCL jar>;<JavaFX jars>" org.dsh.modtoversion.DialogSizingTest
```

需要图形环境：`DialogSizingTest`、`InjectorTest`、`WorldInstallTest` 都要 JavaFX，其余可无头运行。**顺序运行**这些测试——连续并发启动多个 JavaFX 实例会互相干扰。

想看弹窗长什么样：

```powershell
java -cp "out-test;out;<HMCL jar>;<JavaFX jars>" org.dsh.modtoversion.DialogShot <输出目录>
```

会渲染三张图：最小窗口 20 个版本、最小窗口 1 个版本、高窗口 20 个版本。

## 构建与安装

```powershell
.\build.ps1 -HMCLJar E:\game\gamea\mcl\HMCL-test\HMCL-3.16.4.jar `
            -JavaFXDir E:\game\gamea\mcl\HMCL-test\.hmcl\dependencies\windows-x86_64\openjfx
```

产物：`dist\org.dsh.mod-to-version_1.1.2.jar`，复制到 `.hmcl\plugins\` 后重启启动器。

## 日志

启用后会记录识别到的标题形状与挂载情况：

```
[org.dsh.mod-to-version] 下载内容到指定版本已启用：模组、资源包、光影、世界的下载弹窗都将多出一个「下载到对应版本」按钮
[org.dsh.mod-to-version] 将识别的下载弹窗标题：
  MOD: 模组下载 - <名称>
  RESOURCE_PACK: 资源包下载 - <名称>
  SHADER_PACK: 光影下载 - <名称>
  WORLD: 世界下载 - <名称>
[org.dsh.mod-to-version] 已开始监视模组、资源包、光影、世界的下载弹窗
[org.dsh.mod-to-version] 已在资源包下载弹窗中加入「下载到对应版本」按钮
[org.dsh.mod-to-version] 正在把「Faithful 32x」下载到「1.20.4-Fabric」的 resourcepacks 目录
[org.dsh.mod-to-version] 已把世界「SkyBlock Adventure」解压安装到 1.20.4-Fabric 的 saves/SkyBlock Adventure
```

若 40 秒内拿不到弹窗容器，会记录：「未能取得启动器弹窗容器，「下载到对应版本」按钮未生效」。

## 与其他插件的关系

- 与 **拖入安装 Mod**（`org.dsh.mod-installer`）互不干扰：那个接管主页面拖入，这个在下载弹窗里加按钮。
- 与 **前置 Mod 自动下载**（`org.dsh.mod-deps`）互补：那个在下载完成后补齐前置，这个决定下载到哪个版本。
- 不改启动器源码，纯插件。

## 版本历史

- **1.1.2**：在线回退查找改为按分区选择仓库（原来固定用「模组」仓库，导致资源包/光影/世界的网络回退必然落空）；CurseForge 仅在编译进了 API key 时才被尝试，避免每次查找都白等一次 403。
- **1.1.1**：修复窗口最小化时弹窗被撑出窗口、「取消」按钮无法点击的问题——版本列表改为按窗口高度限制并内部滚动。
- **1.1.0**：新增资源包、光影、世界三个分区（原本只有模组）；标题识别改为跟随启动器语言，不再只认中文/英文；世界走解压安装。
- **1.0.0**：模组分区的「下载到对应版本」。

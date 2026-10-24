# HMCL 插件 API 参考

> 本文档同时内嵌于启动器（设置 → 插件 → 插件开发文档）。修改本文后，请同步拷贝到 `HMCL/src/main/resources/assets/docs/plugin/` 并重新构建，否则启动器内的版本不会更新。

> 与 [plugin-format.md](./plugin-format.md) 配套使用。权威实现：
> `HMCL/src/main/java/org/jackhuang/hmcl/plugin/`。

## 0. 权限模型（本版本重写）

本版本的插件 API 与官方 HMCL 不同：**插件默认以最高权限（ROOT）运行**，可以直接拿到启动器的
根服务对象 `LauncherRoot`，无需反射、无需知道内部类名。

| 权限级别 | 清单写法 | 能力 |
| --- | --- | --- |
| `root` | `"access": "root"` 或**省略该字段** | 全部：设置、账户、游戏目录、启动游戏、杀进程、窗口/导航、插件管理、重启/关闭启动器 |
| `elevated` | `"access": "elevated"` | 同上，但 `LauncherRoot` 中的破坏性操作（安装插件、关闭启动器）会被拒绝 |
| `standard` | `"access": "standard"` | 仅 `PluginContext` 的低风险服务；`getContext().getRoot()` 抛 `IllegalStateException` |

- 插件仍然是**启动器进程内的可信代码，没有沙箱**：即使声明 `standard`，也只是 API 层面的礼貌约定，插件依旧可以用反射访问任何类。权限级别的作用是**显式声明意图**并限制 `LauncherRoot` 这个便捷入口。
- 插件无法被远程下载安装后才提权：`access` 写在 jar 内的 `hmcl.plugin.json` 里，安装时可见（设置 → 插件 的条目会显示权限级别）。
- `PluginContext.getApiVersion()` 返回本启动器实现的 API 版本（当前为 `2`），用于插件做兼容判断。

## 1. 入口类：继承 `HmclPlugin`

```java
package org.example.my;

import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.LauncherRoot;
import org.jackhuang.hmcl.plugin.LaunchContext;
import org.jackhuang.hmcl.util.platform.ManagedProcess;

public class MyPlugin extends HmclPlugin {

    @Override
    public void onEnable() throws Exception {  // 必须实现
        LauncherRoot root = getRoot();         // 等价于 getContext().getRoot()
        root.runAsync(() -> root.showToast("launcher " + root.getLauncherVersion()));
    }

    @Override
    public void onDisable() {                  // 可选
        // 插件被禁用、重载或启动器退出时调用，释放资源
    }

    @Override
    public void onBeforeLaunch(LaunchContext context) {   // 可选
        // 每次游戏进程启动前
    }

    @Override
    public void onAfterLaunch(LaunchContext context, ManagedProcess process) {  // 可选
        // 游戏进程创建成功后
    }
}
```

要求：`public` 类 + `public` 无参构造；`onEnable` 抛异常会被捕获并把插件标记为"加载失败"。

## 2. `getContext()` — 插件服务上下文

| 方法 | 说明 |
| --- | --- |
| `getPluginId()` | 本插件 id |
| `getApiVersion()` | 插件 API 版本（当前 `2`） |
| `getAccessLevel()` | 当前插件权限级别（`PluginAccess`） |
| `getRoot()` | **启动器根服务对象**（见第 3 节）；`standard` 权限下抛异常 |
| `getDataDirectory()` | 私有数据目录 `Path`（`.hmcl\plugins\data\<id>\`），不存在会自动创建 |
| `getDataFile(name)` | 数据目录下的文件，例如 `getDataFile("config.json")` |
| `logInfo(String)` / `logWarning(String)` | 写启动器日志，自动加 `[<id>]` 前缀 |
| `logError(String, Throwable)` | 同上，带异常堆栈 |
| `showMessage(title, message)` / `showWarning(...)` / `showError(...)` | 启动器风格对话框（自动切到 UI 线程） |
| `showToast(message)` | 底部提示条 |
| `confirm(title, message, yes, no)` | 是/否确认框，回调在 JavaFX 线程 |
| `openLink(String url)` / `openDirectory(Path)` | 系统浏览器 / 文件管理器 |
| `copyToClipboard(String)` | 写系统剪贴板 |
| `runInFX(Runnable)` / `runAsync(Runnable)` | 切到 JavaFX 线程 / 启动器后台线程 |
| `getLauncherVersion()` | 当前启动器版本，如 `3.16.4` |

线程规则：钩子运行在启动器后台线程。上下文中的日志方法线程安全；对话框/打开链接等方法内部已切到 UI 线程，可直接调用。其余 JavaFX 操作请用 `runInFX`。

## 3. `LauncherRoot` — 启动器最高权限 API

`getRoot()` / `getContext().getRoot()` 返回服务对象，覆盖启动器自身能做的全部事情。

### 3.1 身份与环境

| 方法 | 说明 |
| --- | --- |
| `getPluginId()` / `getAccess()` | 当前插件 id / 权限级别 |
| `getLauncherVersion()` | 启动器版本 |
| `getLauncherJarPath()` | 当前运行的 jar 路径（可能为 `null`） |
| `getWorkingDirectory()` / `getLocalHome()` / `getUserHome()` | 工作目录 / `.hmcl` / 用户级目录 |
| `getPluginsDirectory()` | 插件目录 |
| `getLauncherClassLoader()` | 启动器类加载器（可自行加载任意启动器类） |
| `readLauncherResource(path)` | 读取启动器 jar 内资源，如 `assets/docs/plugin/plugin-api.md` |
| `getSystemProperties()` | 实时 JVM 系统属性（可写） |

### 3.2 线程与 UI

| 方法 | 说明 |
| --- | --- |
| `runInFX(Runnable)` / `runAsync(Runnable)` / `supplyAsync(Supplier)` | JavaFX 线程 / 启动器后台线程 / 带返回值 |
| `isFXThread()` | 当前是否在 JavaFX 线程 |
| `getStage()` / `getScene()` | 主窗口 / 场景（可能为 `null`） |
| `navigate(Node)` | 切换启动器页面 |
| `showToast(String)` / `showDialog(title, message, type)` | 提示条 / 对话框 |
| `runTask(Task<?>, title)` | 用启动器的任务对话框执行 `org.jackhuang.hmcl.task.Task` |

### 3.3 设置与账户

| 方法 | 说明 |
| --- | --- |
| `getSettings()` | `LauncherSettings`（工作区设置，JavaFX 属性，可直接读写） |
| `getUserSettings()` / `getState()` / `getUserState()` | 用户级设置 / 工作区状态 / 用户状态 |
| `getAccounts()` | `ObservableList<Account>`，可在 FX 线程增删 |
| `getSelectedAccount()` / `setSelectedAccount(Account)` | 当前账户 |

### 3.4 游戏目录与实例

| 方法 | 说明 |
| --- | --- |
| `getGameDirectories()` / `getSelectedGameDirectory()` | 游戏目录列表 / 当前目录 |
| `getSelectedRepository()` | `HMCLGameRepository`（版本、依赖、运行目录等） |
| `setSelectedGameDirectory(GameDirectory)` | 切换游戏目录 |
| `getSelectedInstance()` / `setSelectedInstance(String)` | 当前实例 |
| `getInstanceIds()` | 当前游戏目录下所有实例 id |

### 3.5 启动游戏与进程

| 方法 | 说明 |
| --- | --- |
| `launchGame(String instanceId)` | 用启动器完整流程启动实例（`null` = 当前实例） |
| `countGameProcesses()` | 本启动器启动的游戏进程数 |
| `stopGameProcesses()` | 结束全部游戏进程 |
| `manageProcess(Process, name)` | 把任意 `Process` 包装成 `ManagedProcess` |

### 3.6 插件管理与生命周期

| 方法 | 说明 |
| --- | --- |
| `getPlugins()` | 全部插件 `PluginHandle` |
| `setPluginEnabled(id, enabled)` | 启用/禁用其它插件（持久化） |
| `reloadPlugins()` | 重新加载全部插件 |
| `installPlugin(Path jar)` | 安装插件 jar（elevated 及以上） |
| `restartLauncher()` | 重启启动器 |
| `shutdownLauncher(int exitCode)` | 关闭启动器（root 专用） |

## 4. `LaunchContext` — 启动信息

| 方法 | 说明 |
| --- | --- |
| `getVersionId()` | 启动的实例版本名，如 `1.21.4`（可能为 `null`） |
| `getGameVersion()` / `gameVersion()` | 解析出的游戏版本（后者是 `Optional<String>`） |
| `getRunDirectory()` | 游戏运行目录 `.minecraft`（实例级） |
| `getRepository()` | 游戏仓库对象（`GameRepository`，可查询实例路径等） |
| `getLaunchOptions()` | 本次启动的 `LaunchOptions`（只读快照，请勿修改） |

## 5. `ManagedProcess` — 游戏进程

`onAfterLaunch` 中可用：`getProcess()`（`java.lang.Process`）、`getCommands()`（完整命令行）、`getExitCode()`、`getLines(Predicate<String>)`（读取输出行）。需要监听进程退出时，可自行开守护线程调用 `getProcess().waitFor()`。

## 6. 编译与打包

以 HMCL 构建产物（`HMCL/build/libs/HMCL-3.16.4.jar`）为编译依赖：

```bash
# 编译
javac --release 17 -encoding UTF-8 -cp HMCL-3.16.4.jar -d out src/org/example/my/*.java

# 打包（hmcl.plugin.json 必须在 jar 根目录）
jar --create --file org.example.my-plugin_1.0.0.jar -C . hmcl.plugin.json -C out .
```

Windows 下可直接用示例自带脚本：`docs/plugin/example/build.ps1`。

## 7. 安装、启用与调试

1. 把插件 jar 放进 `.hmcl\plugins\`；
2. 启动器 → **设置 → 插件**：列表中出现插件（副标题会显示权限级别），开关控制启用/禁用（即时生效）；
3. "重新加载插件"按钮：卸载全部插件并重新扫描（用于安装/更新/删除插件后生效，之后才能替换 jar 文件）；
4. 日志：`.hmcl\logs\` 下按日期滚动，插件输出带 `[<id>]` 前缀；
5. 构建自己的 HMCL：仓库根目录执行 `BUILD_NUMBER=4 gradle :HMCL:makeExecutables`，产物在 `HMCL/build/libs/`（`.exe` / `.jar` / `.sh`）。

## 8. 账户列表扩展点（PluginMenuService）

插件可以在**账户列表**的每个账户行上注入自己的操作按钮：

```java
// onEnable 中注册（org.jackhuang.hmcl.plugin.PluginMenuService）
PluginMenuService.registerAccountAction(new PluginAccountAction() {
    public String getName() { return "切换皮肤"; }              // 按钮提示文字
    public Node createIcon() { return SVG.STYLE.createIcon(); } // 按钮图标（每次新建）
    public void run(Account account) {                          // FX 线程回调
        // account 为用户点击的账户，可打开自己的对话框
    }
});

// onDisable 中注销
PluginMenuService.unregisterAccountAction(action);
```

- `run` 在 JavaFX 线程调用；账户类型可用 `account instanceof MicrosoftAccount / AuthlibInjectorAccount / OfflineAccount` 区分；
- 按钮实时出现/消失（启用插件后打开账户列表即可见，无需重启）；
- 完整示例见 `account-skin/`（皮肤与披风切换插件）。

## 9. 下载完成事件（PluginAddonService）

插件可以监听**用户从下载页安装了一个 Mod / 资源包 / 光影**这一事件，例如据此自动补齐前置依赖：

```java
// onEnable 中注册（org.jackhuang.hmcl.plugin.PluginAddonService）
PluginAddonService.registerListener(event -> {
    // event.directory()        —— 文件保存目录，如 <实例运行目录>\mods
    // event.instanceId()       —— 目标实例名（无选中实例时为 null）
    // event.repository()       —— HMCLGameRepository
    // event.addonVersion()     —— 被下载的 RemoteAddon.Version（含依赖列表）
    // event.downloadProvider() —— 当前下载服务提供者
});

// onDisable 中注销（需持有同一个监听器引用）
PluginAddonService.unregisterListener(listener);
```

- 回调在 **JavaFX 线程**执行，只在下载**成功**后触发（用户取消或下载失败不触发）；耗时操作请用 `getContext().runAsync(...)` 或 `Schedulers.io()`；
- 下载页 Mod / 资源包 / 光影 tab 与 Mod 列表的“更新”安装共用同一入口，可用 `directory` 判断类型（`mods` / `resourcepacks` / `shaderpacks`）；
- 某个监听器抛异常只记日志，不影响启动器与其他插件；
- 完整示例见 `mod-deps/`（前置 Mod 自动下载插件）：后台解析传递依赖、用 `Controllers.taskDialog` 批量下载缺失前置。

## 10. 界面扩展点（PluginMenuService）

插件可以往启动器界面注入自己的页面，共四个层级：

### 10.1 主窗口侧边栏页面（registerLauncherPage）

在主窗口左侧边栏的**游戏分区**（版本管理、下载之后）注册一个完整页面，带标题栏与返回：

```java
// onEnable 中注册（org.jackhuang.hmcl.plugin.PluginMenuService）
PluginMenuService.registerLauncherPage(new PluginLauncherPage() {
    @Override public String getTitle() { return "服务端管理"; }
    @Override public SVG getIcon() { return SVG.PUBLIC; }                    // 侧边栏图标（默认 SVG.EXTENSION）
    @Override public Node createPage() { return new MyPage(); }              // JavaFX 线程调用，仅创建一次并被缓存
});

// onDisable 中注销（需持有同一个引用）
PluginMenuService.unregisterLauncherPage(launcherPage);

// 任意位置跳转到该页面（切换导航 + 标题栏）
PluginMenuService.showLauncherPage(launcherPage);
```

- `createPage()` 返回的节点会被启动器**缓存**（整个会话内复用），页面可保留自身状态（控制台日志、选中项等）；
- 页面外面套的是启动器统一的装饰页（标题栏、返回按钮），内容建议使用启动器组件（`ComponentList`、`LineButton`、`FXUtils.newRaisedButton`、JFX 控件）以保持风格一致。

### 10.2 分组页与子页面（registerSubPage / PluginSubPage）

一个插件常常想提供多个功能。把每个功能发布成**子页面**，再由一个**分组页**把它们收在一起，启动器会
根据**实际装了哪些插件**决定侧边栏怎么排，插件之间不需要互相知道：

- 装了分组页插件时：侧边栏只显示分组页（例如「服务器」），进入后由分组页列出各子页面；
- 没装分组页时：每个子页面自动升级为侧边栏上自己的入口，功能不会消失。

```java
// 功能插件：把功能发布成子页面
// pageFactory 的参数是「返回宿主页」的动作；作为独立入口显示时收到 null，
// 因此页面在任何托管方式下都只画一个返回按钮。
PluginMenuService.registerSubPage(new PluginSubPage(
        "org.example.my-feature",   // 稳定 key（用于 showSubPage）
        "我的功能",                  // 侧边栏/卡片标题
        "一句话说明",                // 卡片描述
        SVG.EXTENSION,              // 图标
        back -> new MyFeaturePage(back)));
PluginMenuService.unregisterSubPage(subPage);

// 分组插件：注册分组页（isPageGroup() 返回 true）
PluginMenuService.registerLauncherPage(new PluginLauncherPage() {
    @Override public String getTitle() { return "我的模块"; }
    @Override public SVG getIcon() { return SVG.PUBLIC; }
    @Override public boolean isPageGroup() { return true; }        // 关键：声明这是分组页
    @Override public Node createPage() { return new MyHubPage(); } // 自己的入口卡片列表
});

// 分组页里打开某个子页面（传入「返回我自己」的动作）
for (PluginSubPage subPage : PluginMenuService.getSubPages())
    cards.add(entry(subPage.title(), subPage.description(),
            () -> contentPane.setContent(subPage.pageFactory().apply(() -> showLanding()))));

// 任意位置直接打开某个子页面；以及查询当前有没有分组页
PluginMenuService.showSubPage("org.example.my-feature");
boolean grouped = PluginMenuService.hasPageGroup();
```

- 参考实现：`server-hub/`（分组页）+ `server-manager/`、`server-list/`（子页面）。

### 10.3 下载页标签页（registerDownloadTab）

在 **下载页** 侧边栏末尾注册一个标签页（如服务端下载）：

```java
PluginMenuService.registerDownloadTab(new PluginDownloadTab() {
    @Override public String getTitle() { return "服务端"; }
    @Override public SVG getIcon() { return SVG.DOWNLOAD; }
    @Override public Node createPage() { return new MyDownloadPage(); }
});

PluginMenuService.unregisterDownloadTab(downloadTab);

// 从插件页面直接跳转到下载页的这个标签页
PluginMenuService.showDownloadTab(downloadTab);
```

- 标签页动态出现/消失（插件禁用后自动移除），`createPage()` 惰性创建并被缓存，与内置标签页行为一致。

### 10.4 设置页内的插件页面（registerPage）

小体量的插件页面也可以注册到 **设置 → 插件** 页面的“插件页面”区块（点击进入，带返回按钮）：

```java
PluginMenuService.registerPage(new PluginPageAction() {
    @Override public String getTitle() { return "..."; }
    @Override public String getDescription() { return "..."; }
    @Override public Node createPage() { return new MySmallPage(); }
});

PluginMenuService.unregisterPage(pageAction);
```

- 完整示例见 `server-manager/`（服务端管理器插件）：子页面 + 下载页服务端下载标签页；
  `lunar-offline/`（Lunar 客户端插件）：下载页标签页里做完整的版本列表 → 下载 → 一键启动。

## 11. 崩溃窗口扩展点（registerCrashAction）

插件可以在**游戏崩溃窗口**底部的按钮行（导出日志 / 日志 之后）注入按钮，
例如调用 AI 分析崩溃日志：

```java
// onEnable 中注册（org.jackhuang.hmcl.plugin.PluginMenuService）
PluginMenuService.registerCrashAction(new PluginCrashAction() {
    @Override public String getName() { return "AI 分析崩溃原因"; }
    @Override public void run(GameCrashContext context) {
        // JavaFX 线程调用；耗时操作自行切后台线程
        String log = context.getLatestLog();      // logs/latest.log
        context.getLog();                          // 控制台全部输出（懒拼接）
        context.getRunDirectory();                 // 实例运行目录
        context.getLoaders();                      // forge=47.2.0 ...
        context.getExitType(); context.getExitCode();
    }
});

// onDisable 中注销（需持有同一个引用）
PluginMenuService.unregisterCrashAction(action);
```

- `GameCrashContext` 上的读取方法线程安全、只读，无副作用；
- 完整示例见 `ai-agent/`（AI 修复代理）与 `crash-ai/`（AI 崩溃分析）。

## 12. 常见问题

| 现象 | 原因 |
| --- | --- |
| 插件列表显示"加载失败" | 清单缺失/字段非法、entry 类不存在、没继承 `HmclPlugin`、id 与已有插件重复；点击条目查看详情 |
| 显示"与当前启动器版本不兼容" | 清单 `minLauncherVersion` 高于当前启动器版本 |
| 开关打开后又弹回 | `onEnable()` 抛异常，查看日志 |
| `getRoot()` 抛 `IllegalStateException` | 清单里写了 `"access": "standard"`，改成 `root` 或删掉该字段 |
| 无法删除/替换 jar 文件 | 启动器运行中持有文件句柄；先"重新加载插件"或退出启动器 |
| 对话框/日志没出现 | 确认调用的是 `getContext()` 的方法；异常时查日志 |
| 修改代码不生效 | 需重新打包 jar 并"重新加载插件" |

## 13. 本版本移除的启动器功能

- **启动器自我更新**（检查更新、更新通道选择、更新弹窗、`--apply-to` 自更新流程）已整体移除；启动器不会再联网检查新版本，也不会替换自身文件。游戏 / 整合包 / Mod 的下载与更新不受影响。
- **帮助入口**已移除：标题栏帮助按钮、设置中的"帮助"页、崩溃窗口的帮助按钮均不再存在。
- 插件如需实现"检查新版本"之类功能，可用 `LauncherRoot` 自行实现（例如读取 `getSettings()`、发起 HTTP 请求、打开链接）。

# 服务器列表插件

浏览社区服务器：实时在线人数、连接延迟、版本与类型标签，支持搜索与分类筛选、一键进入游戏，
并可连接 mcstat.org 在线目录发现 6000+ 服务器。

- 只发布一个“子页面”：未安装汇聚页（如「服务器」模块）时，启动器会把它作为主页面侧边栏入口；
  安装了汇聚页后则收进汇聚页内。
- 构建：`powershell -File build.ps1`，产物在 `dist/`。

## 1.1.2

### 去掉页内的二级返回按钮

详情、添加表单、在线目录（「发现更多服务器」）、目录里的服务器详情，本来各自在左上角画了
一个「返回…」按钮。启动器标题栏本来就有原生返回箭头，页内再画一个等于同一屏两个返回，
既重复又占掉一行首屏空间。现在这些按钮全部移除，**返回只走标题栏那一个箭头**（ESC 同效）。

按钮去掉后，"退到哪一层"的职责从按钮转移到 `ServerListPage#back()`：

- `subViewBack` 记录当前子视图的上一层：详情 / 添加表单 / 在线目录 → 服务器列表；
  **目录里的服务器详情 → 在线目录**（而不是一步跳到已保存列表）；
- 每退一层就重新登记新的上一层，连按返回会逐级退回，不会跳错层。

回归测试：`python run_test.py`（4 个测试类，35 条断言）。

## 1.1.1

### 窗口返回箭头回到「服务器」选择界面

以前在本页按标题栏左上角的返回箭头，会被启动器一路退出到主界面——即使本页是从
「服务器」汇聚页点进来的。现在本页实现 `DecoratorPage`，把返回分成三级：

1. 停在某个子视图（详情 / 添加表单 / 在线目录 / 目录里的服务器详情）→ 退一层（`false`）；
2. 停在列表顶层且由汇聚页托管 → 回到「服务器」卡片选择界面（`back()` 返回 `false`）；
3. 作为独立侧边栏入口（没有汇聚页）→ 返回 `true`，让启动器正常退出本功能。

> 返回值语义常被写反：`DecoratorController.back()` 里是 `if (page.back()) navigator.close()`，
> 所以 **`true` = 请启动器弹栈**，`false` = 本页已处理。箭头本身的接管在汇聚页
> （`org.dsh.server-hub`）完成，本页只负责回答“这一级我处理了没有”。

回归测试：`python run_test.py`（`tools/BackSemanticsTest.java`，14 条断言）。

## 1.1.0

### 恢复内置的 10 个服务器

内置目录来自插件 jar 里的 `resources/servers.json`（Hypixel、CubeCraft、EarthMC、Jartex、
ManaCube、CraftYourTown、OpBlocks、Skyblock.net、MCC Island、PURPLE）。

> **之前它们消失过一次**：那次 jar 是用手工 `jar` 命令打的，漏掉了 `resources` 目录，
> 于是 `getResourceAsStream("/servers.json")` 返回 null，页面显示「共 0 个服务器」且没有任何提示。
> `build.ps1` 本身会带 `resources`，所以现在统一走它打包；`BundledCatalogTest` 也直接读 jar 验证
> 十条都在，防止再被漏掉。

### 去掉标题左侧的箭头

「服务器列表」标题左侧原来有个返回箭头，但模块入口自身已经带「返回」，重复且碍眼。现在标题
是纯文字。`ListUi.heading` 的三参重载（带返回回调）保留——它本身没错，只是这个页面不再使用。

### 「发现更多服务器」等切换加入转场动画

内容区从普通 `VBox` 换成启动器的 `TransitionPane`，所有视图切换（列表 / 详情 / 添加表单 /
在线目录）都走 Material 3 的 forward / backward 过渡：进入用 forward，返回用 backward，
方向和导航语义一致。

> `TransitionPane` 只在**有尺寸且动画开关打开**时才播放过渡，否则直接替换内容——这是它自己的
> 保护逻辑。所以构造函数里的首次挂载（彼时还没有尺寸）不会有动画，用户点击产生的切换才有。

## 测试

| 测试 | 断言数 | 覆盖内容 |
| --- | --- | --- |
| `BundledCatalogTest` | 12 | jar 内 `servers.json` 存在且能读出**全部 10 条**；每条都有名称/地址/说明；地址不含 scheme、无尾斜杠；标题无箭头 |
| `ServerListPageTest` | 6 | **真实页面**上没有任何返回箭头、标题仍在、模块返回动作未被触发；挂载后能找到外壳滚动容器；没有滚动容器时也不报错 |
| `PageLayoutTest` | 9 | `heading` 三参重载把箭头放在标题行（仅一行）、点击触发回调；两参重载不加箭头但保留标题与说明 |
| `TransitionTest` | 6 | 内容区是 `TransitionPane`；点「发现更多服务器」进入、再按返回箭头（`back()`）退出，**两次切换都在播放过渡**（两个子节点、新节点仍处于偏移/透明中间态）；返回后列表恢复 |
| `ServerPingerTest` | — | 状态查询协议 |
| `AddServerTest` | — | 自定义服务器的增删 |

带包的用 `org.dsh.serverlist.<Name>` 运行，`ServerPingerTest` 在默认包（无 package 声明）：

```powershell
javac -encoding UTF-8 --release 21 -cp "out;<HMCL jar>;<JavaFX jars>" -d out-test tools/*.java
java -cp "out-test;<插件jar>;<HMCL jar>;<JavaFX jars>" org.dsh.serverlist.TransitionTest
```

> `TransitionTest` 需要 `--release 21`（用到 `instanceof` 模式匹配）；构造真实页面的测试都要先
> `SettingsManager.init()`，否则控件静态初始化会抛 `ExceptionInInitializerError`。
> 测试环境是软件渲染，启动器会自动关掉动画——这时过渡退化为直接替换，属预期行为。

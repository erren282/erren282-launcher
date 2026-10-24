# 服务端管理器 —— HMCL 插件

在 HMCL 内直接管理多个 Minecraft 服务端：**下载原版 / Paper 服务端或导入现有服务端，
一键开设并自动向局域网广播**，内置控制台。

安装：把 `dist/org.dsh.server-manager_<版本>.jar` 放入 HMCL 数据目录的 `.hmcl\plugins\`
文件夹（或拖入启动器窗口 / 设置→插件→从本地导入）。装好后：

- **主窗口左侧边栏 → 游戏分区 →「服务端管理」**：管理已登记的服务端实例（列表、
  详情、一键开设、控制台）；
- **下载页 → 侧边栏「服务端」**：下载原版 / Paper 服务端，或导入现有服务端；
  创建完成后会自动跳回「服务端管理」页面。

窗口左上角的返回箭头按层级分流：玩家管理 / 插件管理这类二级页面 → 先回到服务端主视图；
主视图 → 回到「服务器」卡片选择界面；作为独立入口时 → 退出本功能（实现见
`ServerManagerPage.back()`）。

**页面内不再有任何返回按钮**（既没有「← 返回服务端」，主视图顶部也没有「返回」），
返回只有一条路径：窗口左上角那个启动器原生的箭头（ESC 同效）。这样任何界面上都只有一个
返回入口，不会出现"页内一个、标题栏又一个"的重复。

## 功能

### 1. 各版本服务端管理

- **下载原版服务端**：从 Mojang 版本清单选择任意版本（release / snapshot / 旧版本），
  自动下载 `server.jar` 并校验 SHA-1；官方源不可用时自动回退 BMCLAPI 镜像；
- **下载 Paper 服务端**：通过 PaperMC Fill v3 API 获取版本与最新构建，校验 SHA-256；
- **导入现有服务端**：选择已有的服务端目录和启动 jar（Forge / Fabric / 任意实现均可），
  只登记不搬动文件；目录里缺启动 jar 或 jar 损坏时，可用 **核心修复/安装** 选项按版本号
  自动下载原版或 Paper 核心补齐（已有的 jar 会被覆盖修复）；
- 每个实例独立目录（`.hmcl\plugins\data\org.dsh.server-manager\servers\<id>\`），
  可配置端口、MOTD、最小/最大内存、Java 路径——**「扫描」按钮自动查找本机全部 Java**
  （启动器自带、JAVA_HOME、Program Files 各发行版、HMCL 与官方启动器的 runtime 目录），
  版本号取自各 JDK 的 release 文件，下拉直接选用；也可手动输入或点「选择」浏览。

### 2. 一键开设

点击「一键开设并广播」即完成：

1. 写入 `eula.txt`（首次需在设置中勾选同意 EULA）；
2. 创建 / 更新 `server.properties`（端口、MOTD 及下方列出的玩法选项，其余配置不受影响）；
3. 以配置的 Java、内存和附加 JVM 参数启动服务端进程（`nogui`）；
4. 启动**广播桥**（见下）；
5. 若勾选了「创建陶瓦联机房间」，驱动启动器的陶瓦联机守护进程扫描广播，房间建立后
   在页面顶部显示**房间码**（点击复制），好友在任意启动器的「多人联机 → 加入房间」输入即可跨网络加入。

### 3. 可直接修改的选项

实例详情中可直接编辑并保存（启动时写入 `server.properties`）：

| 选项 | server.properties 键 | 默认 |
| --- | --- | --- |
| 端口 / MOTD | `server-port` / `motd` | 25565 / A Minecraft Server |
| 正版验证 | `online-mode` | true |
| 最大玩家数 | `max-players` | 20 |
| 难度 | `difficulty` | normal |
| 游戏模式 | `gamemode` | survival |
| 视距 / 模拟距离 | `view-distance` / `simulation-distance` | 10 / 10 |
| PvP / 允许飞行 | `pvp` / `allow-flight` | true / false |
| 白名单 / 命令方块 | `white-list` / `enable-command-block` | false / false |
| 世界种子 | `level-seed`（留空不写入） | — |

此外还有实例级设置：最小/最大内存、Java 路径、**附加 JVM 参数**（空格分隔，追加在
`-Xmx` 之后）、局域网广播开关与陶瓦联机房间开关。

页面底部是实时控制台：查看启动日志、发送命令（`list` / `say` / `stop` 等），
**输入框支持 Tab 补全**（见下），「停止」按钮向服务端发送 `stop` 并在 30 秒后强制结束。

### 4. 控制台 Tab 补全（可点击列表）

在命令输入框里按 <kbd>Tab</kbd> 即可补全，**候选会显示成一个可直接点击的列表**，
样式与游戏内聊天框的指令提示一致：

| 输入 | 按 Tab 之后 |
| --- | --- |
| `gam` | `gamemode `（补全命令名后自动加一个空格） |
| `gamemode ` | `adventure` / `creative` / `spectator` / `survival` |
| `gamemode cr` | `creative` |
| `op ` | 该服务端的全部玩家名 + `@a` `@p` `@r` `@s` |
| `gamerule ` | 常用游戏规则名 |
| `summon ` | 实体 id |
| `whitelist ` | `add` / `list` / `off` / `on` / `reload` / `remove` |
| `time ` | `add` / `query` / `set`（再按一次补 `day` / `noon` / …） |

- **鼠标点击**：列表里每一项都可点击，点一下就补进输入框并关闭列表，无需键盘操作；
- **键盘**：<kbd>Tab</kbd> 在候选间前进，<kbd>Shift</kbd>+<kbd>Tab</kbd> 后退，当前项高亮并自动
  滚动到可见位置，输入框下方显示 `(2/4) 继续按 Tab 切换，Shift+Tab 返回，或直接点击下面的候选项`；
- **只有一个候选时直接补上**，并把光标移到下一个参数；
- 补全内容来自三处：
  1. **服务端自己的数据** —— `usercache.json` / `ops.json` / `whitelist.json` /
     `banned-players.json` 里的玩家名，以及含 `level.dat` 的世界目录；
  2. **内置命令表** —— 原版命令加上常见 Bukkit/Spigot/Paper 命令（约 100 条），
     以及 `gamemode` / `difficulty` / `weather` / `gamerule` 等命令的固定参数；
  3. **`commands.txt`** —— 数据目录下 `.hmcl\plugins\data\org.dsh.server-manager\commands.txt`，
     一行一个命令名，`#` 开头为注释，带不带 `/` 都行。**插件注册的命令无法自动发现**
     （服务端没有"查询已注册命令列表"的接口），需要补全就写在这里：

     ```
     # 我的插件命令
     myplugin
     /spawn
     ```

补全是纯本地的：专用服务端不提供通过 stdin 请求补全的通道，因此不会为了补全向服务端发任何数据。

### 5. 玩家管理

在主页面「服务端内容管理 → 玩家管理」进入。页面按**玩家列表**组织：每个玩家一行，
行内显示该玩家当前的状态（● 在线 / OP / 白名单 / 封禁及到期时间），右侧是**一排明确的按钮**
——授予 OP、加入/移出白名单、封禁…、解禁、踢出，能做什么一目了然。**点击行本身不触发任何操作**，
误点不会悄悄改掉别人的权限。不在列表里的玩家用顶部「添加玩家」输入框手动加入。

| 功能 | 说明 |
| --- | --- |
| **白名单开关** | 页面顶部独立一行，显示当前状态（已开启/已关闭）与名单人数，点「开启白名单 / 关闭白名单」切换；**立即写入 `server.properties`**，不会弹回 |
| **OP 权限** | 每行一个按钮，按当前状态显示「授予 OP」或「取消 OP」 |
| **白名单** | 每行一个按钮，按当前状态显示「加入白名单」或「移出白名单」 |
| **封禁** | 点「封禁…」弹出表单填原因与时长（分钟 / 小时 / 天 / 周 / 月 / 年 / 永久）；已封禁的玩家行显示到期时间，按钮变为「解禁」 |
| **踢出** | 仅对该玩家真的在线时可用（离线时按钮置灰） |

所有操作会自动选择正确的生效方式：

- **服务端运行中** → 通过控制台命令修改（`op` / `deop` / `whitelist add` / `ban` / `pardon`），
  因为运行中的服务端把名单放在内存里，直接改文件会在它下次保存时被覆盖；
- **服务端已停止** → 直接写 `ops.json` / `whitelist.json` / `banned-players.json`。

#### 页面会自动跟随服务器

这个页面以前只在手动操作后刷新，玩家进进出出、或别人在服务端控制台里操作时，界面不会变。
现在它**订阅服务端控制台**：

- 玩家 **加入 / 离开**、以及 `list` 命令的输出，都会让页面自动刷新；
- 刷新是**防抖**的：一次加入会产生好几行日志（连接、UUID、join），只重建一次，
  否则每行重建一次会和你正在输入的「添加玩家」输入框抢焦点；
- 重建后会把**已经输入的文字和光标位置还原**，并保持焦点，所以刷新不会打断你打字；
- 离开页面时**解除订阅**，不会留下看不见的页面继续跟着控制台刷新。

> **踩过的坑（1.1.1 修掉）**：识别加入/离开的正则只允许 `名字 + 空格 + joined`，而服务端
> 实际打印的是 `erren_s[/127.0.0.1:7381] logged in with entity id 2`——名字后面先跟一个
> `[/ip:端口]`。于是**一次加入都没被识别到**，页面看起来永远不刷新。现在正则允许这个括号，
> 并且 `PlayerPresenceTest` 直接拿真实日志行做断言（16 项）。
>
> 同一处还修了输出读取：控制台窗口运行时会一直持有 `output.log` 的写句柄，Windows 上
> 读取会**间歇性**失败；原来的实现把异常吞掉返回空列表，于是「没有新行」的假象让刷新
> 永远不发生。现在读取失败会重试，而不是静默当作空。

「白名单开关」只在玩家管理里出现。服务器设置页不再重复提供这个开关——两处都能改同一个
`white-list`，很容易互相覆盖，而且设置页那份不会读回 `server.properties` 的真实值。

**关于封禁时长**：原版 `ban` 命令无法设置到期时间（只能永久），所以带时长的封禁会直接写入
`banned-players.json` 的 `expires` 字段（格式与服务器一致），并立即把在线的该玩家踢出；
解禁则用 `pardon`，让服务端自己同步内存与文件。写入的 UUID 优先取 `usercache.json` 中
服务器记录的真实值，找不到时按离线模式规则计算（`MD5("OfflinePlayer:<名字>")`），
因此 `online-mode=false` 的服务器也能正确匹配。

### 6. 插件管理与插件商店

在主页面「服务端内容管理 → 插件管理」进入。整个体验对齐启动器对游戏 Mod 的管理与下载。

**已安装的插件**（扫描 `plugins/` 与 `plugins/.disabled/`）：每个插件一张卡片，
**左侧是插件图标**，名称、版本、文件名、大小、API 版本、作者、前置插件、官网与简介在中间，
**停用/启用、官网、删除**三个按钮固定在右侧——不用点开详情，每个操作在列表里就直接可见：

- **图标**：已安装插件本地的 jar 里没有图标（Bukkit 的 `plugin.yml` 没有图标字段，
  实测 19 个 jar 里 0 个带图片），所以按插件名在 Modrinth 检索并匹配，
  下载后缓存在 `icon-cache/` 目录，之后离线也能秒读；商店结果则直接显示 Modrinth 图标
  （**一页结果只发一次批量请求**解析全部 PNG 图标，因为搜索接口返回的 `icon_url` 是
  JavaFX 解不了的 WebP）。图标加载失败时安静地留空，绝不会因此影响页面；
- **停用 / 启用**：停用会把 jar 移到 `plugins/.disabled/`，启用移回 —— 服务端只加载
  `plugins/*.jar`，所以移开即等于停用，且完全可逆。列表中以「［已停用］」标记；
- **官网**：`plugin.yml` 声明了 `website` 时显示「官网」，没有则显示「商店搜索」
  （跳转到该插件在商店的检索页）。实测只有少数插件声明官网，所以两种情况都处理；
- **删除**：红色按钮，确认后只删 jar，**保留插件的配置目录**（`plugins/<名字>/`），
  重新安装后配置还能用。

**插件商店**（内置在同一页面）：

- 数据来自 **Modrinth**；搜索时会**自动带上本服务端的 Minecraft 版本与核心**
  （Paper / Spigot / Purpur / Folia / Velocity / BungeeCord 等，从服务端 jar 自动识别），
  所以不会给 1.8.8 的服推只支持 1.21 的插件；
- 每个结果标明**适配的游戏版本、支持的服务端核心、下载量、作者、简介**，
  右侧「商店页面」与「下载到本服务端」两个按钮；
- 下载后**直接安装进当前服务端的 `plugins/` 目录**，并校验 Modrinth 提供的 **SHA-512**，
  校验不通过不会写入；重名时自动改名（`xxx (1).jar`），不会覆盖已有插件；
- 可切换「显示全部版本与核心」放宽筛选（此时状态栏会明确提示未按版本筛选）。

安装/删除/启停后如果服务端正在运行，需要重启服务端才会生效（页面顶部有提示）。

两个管理页都**不再自带返回按钮**：以前页面顶部那条高亮的「← 返回服务端」占掉整整一行，
而窗口左上角本来就有启动器原生的返回箭头，两个并排出现既重复又浪费首屏空间。现在返回统一
走左上角的箭头（或 ESC），由 `ServerManagerPage.back()` 判断该退回主视图还是退回汇聚页；
返回时主页面会重新读取配置，所以在子页里改过的白名单状态不会被子页面背后遗留的旧值覆盖。

**两个页面都是同一种单列卡片布局**（同宽、图标位相同、按钮同样靠右对齐），玩家列表和插件
列表在视觉上是同一种东西，来回切换不会迷失。**整个页面只有一层滚动**：卡片直接排进页面
（没有页内的独立滚动容器），从顶部一路滚到底部由页面外壳统一接管，不存在"滚动条套滚动条"、
也不用在小窗口里先滚完一段再滚下一段。窗口太窄放不下所有操作按钮时，按钮会自动换到第二行
而不是被压成省略号。

**每次进入页面都从顶部开始**。启动器把插件页包在一个外壳级滚动容器里，且页面节点在多次
访问之间是缓存的——上一次滚到的位置会原样带到下一次进入，于是重进一个页面看到的可能是
它的中段。现在玩家管理、插件管理在挂载时会把所有祖先滚动容器归零；因为内容（尤其是插件
商店的结果）是异步补充的，晚到的布局变化会把位置再拖离顶部，所以重置会分几次、跨约一秒
重复执行。等待放在后台线程上——调用方在 JavaFX 线程上，在那里睡觉会冻住整个界面。

### 7. Java 版本检查与一键安装

点击「一键开设」或「重新启动」时，会先检查该实例配置的 Java 是否能真正跑起来；
不匹配时**不直接启动**（避免服务端几秒后闪退、只留下一段看不懂的报错），
而是弹出说明并给出可立即执行的选项。

识别三类问题：

| 情况 | 后果 | 处理 |
| --- | --- | --- |
| **Java 版本过低** | 立即 `UnsupportedClassVersionError` | 阻止启动，建议安装所需版本 |
| **根证书库过旧** | Paper 核心在 `Downloading vanilla jar...` 处 `PKIX path building failed` | 阻止启动，建议安装较新的 Java |
| **Java 版本过高** | 服务端通常仍能启动，老插件/NMS 可能报错 | 仅提示，可选择「仍然启动」 |

弹窗提供三个选项：

- **下载并安装 Java X，然后切换**：从 Foojay/Disco 索引下载当前厂商构建
  （优先 Azul Zulu，其次 Temurin/Liberica/Corretto），安装进启动器的 Java 目录并
  自动把实例切换过去，随后重新启动；进度、取消、错误提示复用启动器自身的任务对话框；
- **选择已安装的 Java…**：列出本机所有能跑这个服务端的 Java，选中即切换；
- **取消**（`WARNING` 时另有「仍然启动」）。

两点实现要点：

- **判定证书库不能只看根证书名字**。实测同一台机器上，`jdk 17.0.2` 的 cacerts 里
  没有 `DigiCert Global Root G2` 却仍能正常连接 Mojang，而 `jre-legacy (8u51)` 有
  `DigiCert Global Root CA` 却失败。因此检查方式是**加载目标 Java 自己的 `cacerts`、
  在本进程内对 Mojang 发起真实 TLS 握手**，且只把 `PKIX` / `CertPathBuilder` 这类
  证书链错误判为「证书库过旧」——纯网络故障（离线、超时）一律不算问题，不会误报。
- **不能依赖 Mojang 下载 Java**。Mojang 官方的 Java 8 就是 `jre-legacy`（1.8.0_51），
  正是证书库过旧的那个版本，用它来修这个问题只会原样复现。所以走 Disco 源取现代构建
  （如 Zulu 8.0.504），并且下载由启动器自身（Java 21）执行，不受目标 Java 的 TLS 能力限制。

### 8. 局域网广播与心跳保活

独立服务端默认**不会**出现在局域网玩家的“局域网世界”列表里，也不会应答心跳探测，
挂机几分钟就会被联机守护进程判定掉线。广播桥替它完成两件事：

- 每 1.5 秒按 Minecraft 局域网发现协议向组播组 `224.0.2.60:4445` 广播
  `[MOTD]…[/MOTD][AD]端口[/AD]`（并同时单播给本机回环，便于本机守护进程发现）；
- 在 **服务端端口 + 1**（被占用则顺延，最多 +24）开启代理：
  自己应答老版 0xFE 心跳 ping（服务端重启期间也不掉线），其余 TCP 连接原样转发给真实服务端。

玩家在多人游戏列表里看到的服务器端口是**代理端口**，连接代理即可进入；服务端重启、
崩溃、挂机都不影响广播与房间。不勾选“向局域网广播”则只在本机启动服务端。

### 9. 独立控制台窗口（关闭启动器后仍可管理）

服务端不再作为启动器的子进程运行，而是由一个**独立的控制台窗口**托管：

```
启动器 ──启动──▶ cmd /c start "服务端 - 名称" java -cp <插件jar> ConsoleHost <会话目录>
                                                    └─ 自己持有服务端进程的 stdin/stdout
```

这样带来三个结果：

1. **窗口是独立进程**，启动器退出后它继续跑，服务端也就不受影响；
2. **窗口里可以直接输入命令**（`stop`、`list`、任何服务端命令），不依赖启动器；
3. 启动器**重启后会自动接入**这个窗口，控制台输出与命令发送恢复可用。

启动器内的控制台**保留**：它和窗口看到的是同一份输出，输入的命令也会送到同一个服务端。

#### 双方怎么通信

全都通过会话目录里的几个文件（`<数据目录>/console/<会话ID>/`）：

| 文件 | 谁写 | 用途 |
| --- | --- | --- |
| `launch.txt` | 启动器 | 服务端命令行，一行一个参数（不涉及 shell 引号解析） |
| `dir.txt` / `port.txt` / `profile-id.txt` | 启动器 | 这个窗口属于哪个服务端 |
| `session.json` | 窗口 | 服务端 pid、目录、端口；启动器据此判断"还活着" |
| `commands.txt` | 启动器追加 | 要发给服务端的命令 |
| `input.pos` | 窗口 | 已经处理到第几行，避免重复执行 |
| `output.log` | 窗口 | 全部输出，启动器的控制台就读这里 |
| `exit.code` | 窗口 | 服务端退出码，让启动器知道"停了"和"为什么停" |

#### 只接入"对得上"的服务端

这是需求里明确要求的：**窗口开的服务端必须和启动器里保存的一致才接入**。判定条件是
**目录 + 端口 + profile id 三者全部相同**（目录比较前会规范化，尾斜杠和 `..` 不算差异）。

任何一项不同就不接入——因为接入错了会把 `stop` 发给另一个服务端。会话的存活判定也
不看端口（端口可能被别的程序占用），而是看窗口公布的 **pid 是否还存在**。

`ConsoleSessionTest` 的 21 条断言就是在钉这条规则。

#### 启动失败时

如果插件不是从 jar 加载（例如开发时直接跑 class 目录），就没有可交给 `java -cp` 的路径，
此时会**回退**为在启动器内启动服务端，并在控制台说明"关闭启动器会停止它"。
独立窗口启动失败时同样回退，而不是让你只看到一条错误。

## 目录结构

```
server-manager/
├── hmcl.plugin.json            # 插件清单
├── build.ps1                   # 一键编译打包（产物在 dist\）
├── src/org/dsh/servermanager/
│   ├── ServerManagerPlugin.java    # 入口：注册侧边栏页面与下载页标签页
│   ├── ServerManagerPage.java      # 主窗口侧边栏管理页（列表 / 详情 / 控制台）
│   ├── ServerDownloadPage.java     # 下载页「服务端」标签页（原版/Paper/导入向导）
│   ├── ServerManagerService.java   # 实例编排（eula / properties / 进程 / 桥接）
│   ├── ServerProcess.java          # 服务端进程与控制台（三种模式：进程内 / 独立窗口 / 外部接管）
│   ├── ConsoleHost.java            # 独立控制台窗口的宿主进程（不依赖启动器类）
│   ├── ConsoleSessions.java        # 会话目录的启动、识别与命令投递
│   ├── ServerBridge.java           # 局域网广播 + 0xFE 心跳代理 + TCP 转发
│   ├── ServerVersions.java         # 原版/Paper 版本解析与下载（含镜像回退、校验）
│   ├── CommandCompleter.java       # 控制台 Tab 补全（命令表 / 玩家名 / 参数 / commands.txt）
│   ├── PlayerManagerPage.java      # 玩家管理页（玩家列表 / OP / 白名单 / 封禁与解禁）
│   ├── ServerPlayers.java          # 玩家名单读写（ops/whitelist/banned json + 控制台命令）
│   ├── PluginIcons.java            # 插件图标（Modrinth 解析 + PNG 磁盘缓存 + 批量请求）
│   ├── ManageUi.java               # 子页面共用组件（页面标题、单列卡片、按钮样式）
│   ├── ManageDialog.java           # 表单对话框（封禁原因与时长）
│   ├── PluginManagerPage.java      # 插件管理页（启停 / 删除 / 内置插件商店）
│   ├── ServerPlugins.java          # 插件扫描与操作（读 plugin.yml、移入 .disabled）
│   ├── PluginStore.java            # 插件商店（Modrinth 检索、按版本/核心筛选、SHA-512 校验）
│   ├── JavaCompatibility.java      # Java 兼容性判定（版本 + 真实 TLS 握手验证书库）
│   ├── JavaDownloader.java         # 从 Foojay/Disco 下载并安装合适的 Java
│   ├── JavaFixDialog.java          # Java 不匹配弹窗（下载 / 选择已装 Java / 仍然启动）
│   ├── ServerProfile.java          # 实例配置
│   └── ProfileStore.java           # servers.json 持久化
└── tools/                      # 无头验证工具（不参与打包）
    ├── HeadlessTest.java           # 端到端：下载→开设→广播→代理→命令→停止→删除
    ├── MiniBridgeTest.java         # 桥接转发隔离测试
    ├── ProxyRealTest.java          # 桥接对真实服务端的转发验证
    ├── PaperProbe.java             # Paper Fill API 解析验证
    ├── JavaCheckTest.java          # Java 判定规则（版本表 / 证书库 / 端到端判定）36 项
    ├── JavaDownloadTest.java       # 下载 Java→验证书库→启动 Paper 全链路
    ├── CompletionTest.java         # Tab 补全引擎（命令/参数/玩家名/循环）50 项
    ├── TabCompletionUiTest.java    # 在真实 JavaFX 场景里驱动 Tab 键（含焦点不跳转）13 项
    ├── ServerContentTest.java      # 玩家名单与插件管理（真实文件读写、plugin.yml 解析、白名单写盘）89 项
├── ConsoleSessionTest.java     # 控制台窗口的接入判定与会话文件协议 21 项
    ├── PluginStoreTest.java        # 插件商店（真实检索/下载/SHA-512/兼容性拒绝）23 项
    ├── ManagerUiTest.java          # 补全列表点击、玩家页与插件页在真实 JavaFX 上的构建 39 项
    ├── DialogRenderTest.java       # 弹窗在真实 JavaFX 线程上的构建与布局
    └── PluginLoadTest.java         # 插件包清单与入口类校验
```

## 构建与验证

```powershell
# 依赖 JDK 17+（javac 在 PATH 或 JAVA_HOME）；自动从 HMCL/build/libs 找启动器 jar
.\build.ps1

# UI 回归（39 项 + 插件包校验）：在真实 JavaFX 场景里构建主视图/玩家页/插件页，
# 并断言这三个界面上都没有「返回…」按钮
python run_test.py

# 端到端无头验证（下载 1.20.4 服务端 → 一键开设 → 广播/代理 → 控制台 → 停止 → 删除）
javac -encoding UTF-8 -cp "out;<HMCL jar>" -d out tools/HeadlessTest.java tools/Ping.java
java -cp "out;<HMCL jar>" HeadlessTest 1.20.4

# Java 判定规则 36 项（无需网络，含真实 TLS 握手探测本机各 Java）
javac -encoding UTF-8 -cp "out;<HMCL jar>" -d out-test tools/JavaCheckTest.java
java -cp "out-test;out;<HMCL jar>" JavaCheckTest

# Tab 补全 50 项（纯逻辑，无需 JavaFX）
javac -encoding UTF-8 -cp "out;<HMCL jar>" -d out-test tools/CompletionTest.java
java -cp "out-test;out;<HMCL jar>" CompletionTest

# 玩家管理 + 插件管理 85 项（真实 json/plugin.yml 读写，无需网络）
javac -encoding UTF-8 -cp "out;<HMCL jar>" -d out-test tools/ServerContentTest.java
# 控制台窗口接入判定 + 会话文件协议 21 项（无需网络，无图形环境）
javac -encoding UTF-8 -cp "out;<HMCL jar>" -d out-test tools/ConsoleSessionTest.java
java -cp "out-test;out;<HMCL jar>" ServerContentTest

# 插件商店 23 项（需网络：真实检索 + 下载 + SHA-512 校验）
javac -encoding UTF-8 -cp "out;<HMCL jar>" -d out-test tools/PluginStoreTest.java
java -cp "out-test;out;<HMCL jar>" PluginStoreTest

# 全链路：下载 Java 8 → 验证能连 Mojang → 启动 Paper 1.8.8（需网络，约 1 分钟）
# 需带 JavaFX 模块，因为安装步骤在 JavaFX 线程上完成注册
java --module-path "<javafx 21 jars>" --add-modules javafx.controls,javafx.graphics,javafx.base `
     -cp "out-test;out;<HMCL jar>" JavaDownloadTest 8
```

无头端到端当前 16 项检查全部通过；Java 判定 36 项、Tab 补全 50 项、玩家/插件管理 89 项、
插件商店 23 项、补全 UI 13 项、管理页 UI 39 项通过；截图工具在渲染前会实际验证"进入页面
滚动回顶"（0.5 → 0.0）；桥接对真实服务端的转发另由 `ProxyRealTest` 验证。

## 界面截图

`dist/` 下的 PNG 由 `tools/ManagerUiShot.java` 在真实 JavaFX 场景（已加载启动器样式表）
中渲染，对应本文档所述界面：

| 文件 | 内容 |
| --- | --- |
| `ui-tab-completion.png` | 控制台的可点击补全列表（首项高亮） |
| `ui-player-manager.png` | 玩家管理：在线 / OP / 白名单 / 封禁（含到期时间） |
| `ui-plugin-manager.png` | 插件管理与插件商店（含已安装插件列表） |
| `ui-plugin-store.png` | 插件商店检索结果（适配版本 + 支持核心 + 下载量） |

界面说明：插件页面与下载标签页均使用启动器自带组件（ComponentList / LineButton /
JFXTextField 等）与统一装饰页，风格与启动器其他界面一致。

## 注意

- 一键开设前会**预检端口占用**：如果端口已被占用（最常见是之前开设的服务端还在运行），
  会直接提示而不是启动后闪退；旧启动器实例关闭后残留的服务端进程需手动结束；

- 广播桥监听的是回环地址 + 局域网组播，不会修改系统防火墙规则；若好友搜不到服务器，
  检查服务端端口（或代理端口）是否被防火墙拦截；
- 停用插件不会终止已开设的服务端；HMCL 退出时服务端进程继续运行（控制台输出会丢失，
  可到实例目录 `logs\` 查看）。

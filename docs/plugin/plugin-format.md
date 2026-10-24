# HMCL 插件格式规范

> 本文档同时内嵌于启动器（设置 → 插件 → 插件开发文档）。修改本文后，请同步拷贝到 `HMCL/src/main/resources/assets/docs/plugin/` 并重新构建，否则启动器内的版本不会更新。

> 适用版本：本仓库 HMCL 3.16.4（插件系统为本次新增功能，上游官方版无此系统）。
> 实现位置：`HMCL/src/main/java/org/jackhuang/hmcl/plugin/`（加载器与 API）、
> `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginPage.java`（设置页 UI）。
> 规范与代码不一致时以代码为准。

HMCL 插件是一个**标准 Java jar**，随启动器启动自动加载，运行在启动器进程内、拥有完整权限（与放进游戏实例里的 mod 同级，属于**可信代码，无沙箱**）。插件可以：

- 在启动器启动/退出、每次游戏启动前后执行自定义逻辑；
- 读写自己的私有数据目录（插件更新/重载后仍保留）；
- 通过启动器日志输出调试信息、弹出启动器风格的消息对话框、打开链接或文件夹；
- **通过 `getContext().getRoot()` 直接操作启动器**：读写设置、管理账户与游戏目录、启动游戏、结束游戏进程、切换页面、安装/重载插件、重启或关闭启动器；
- 监听从下载页安装 Mod / 资源包 / 光影完成的事件（`PluginAddonService`），例如自动补齐前置 Mod；
- 在 **设置 → 插件** 页面被用户启用/禁用/重载。

---

## 1. 插件包结构

```
my-plugin.jar
├── hmcl.plugin.json          ← 必需，插件清单（必须位于 jar 根目录）
└── org/example/my/           ← 插件代码（可打包依赖库）
    └── MyPlugin.class
```

插件 jar 直接放进插件文件夹即可安装：

- 插件文件夹位置：**启动器工作目录下 `.hmcl\plugins\`**（即 exe/jar 同级的 `.hmcl\plugins`，也可通过环境变量 `HMCL_LOCAL_HOME` 或系统属性 `hmcl.dir` 重定向）；
- 启动器启动时自动扫描并加载其中所有 `.jar`；
- 插件加载后不释放 jar 文件句柄；**重新加载插件**（设置 → 插件 → 重新加载插件）会先卸载全部插件再重新扫描，之后即可替换/删除 jar 文件。

与插件相关的目录：

| 路径 | 用途 |
| --- | --- |
| `.hmcl\plugins\*.jar` | 插件本体 |
| `.hmcl\plugins\plugins.json` | 启用状态记录（启动器自动维护，格式 `{"disabled": ["id"]}`） |
| `.hmcl\plugins\data\<id>\` | 插件私有数据目录（`context.getDataDirectory()`），重载/更新后保留 |

## 2. 清单 hmcl.plugin.json

```json
{
  "id": "org.example.my-plugin",
  "name": "我的插件",
  "version": "1.0.0",
  "author": "your-name",
  "description": "一句话说明插件功能",
  "entry": "org.example.my.MyPlugin",
  "minLauncherVersion": "3.16.4",
  "access": "root"
}
```

| 字段 | 类型 | 必填 | 校验规则 |
| --- | --- | --- | --- |
| `id` | string | ✅ | 匹配 `^[a-z][a-z0-9_-]*(\.[a-z0-9_-]+)*$`：小写字母开头，可含数字、`-`、`_` 和点分命名空间段（如 `org.example.my-plugin`）。全启动器唯一，也是数据目录名 |
| `name` | string | ✅ | 非空，展示在 设置 → 插件 列表 |
| `version` | string | ❌ | 展示用，建议 semver |
| `author` | string | ❌ | 展示用 |
| `description` | string | ❌ | 展示用 |
| `entry` | string | ✅ | 插件入口类的全限定名，必须是 jar 内 `public` 类、有 `public` 无参构造、继承 `org.jackhuang.hmcl.plugin.HmclPlugin` |
| `minLauncherVersion` | string | ❌ | 最低启动器版本。当前启动器版本更低时插件标记为"不兼容"，不会实例化 |
| `access` | string | ❌ | 权限级别：`root`（默认）/ `elevated` / `standard`。缺省即 `root`，插件可直接使用 `getContext().getRoot()` 的全部能力；写错的值按 `root` 处理。详见 [plugin-api.md](./plugin-api.md) 第 0 节 |

> 本版本插件**默认拥有启动器最高权限**：插件与启动器同进程、无沙箱，`access` 只是让插件显式声明意图，并在 API 层限制 `LauncherRoot` 这个便捷入口。安装第三方插件前请自行确认来源可信。

加载失败的行为（对应 `PluginHandle.State`）：

| 状态 | 触发原因 |
| --- | --- |
| `FAILED` | 清单缺失/JSON 损坏/id 非法/entry 缺失、entry 类不存在或不继承 `HmclPlugin`、**id 重复**（后加载的失败）、`onEnable()` 抛异常 |
| `INCOMPATIBLE` | `minLauncherVersion` 高于当前启动器版本 |
| `ENABLED` / `DISABLED` | 正常状态；新安装的插件**默认启用**，用户可在设置页关闭 |

失败详情会写入启动器日志，并在 设置 → 插件 页面点击对应条目查看。

## 3. 生命周期与事件

```
启动器启动（后台线程）
  └─ 扫描 .hmcl/plugins/*.jar → 读清单 → 实例化 entry → 未被禁用的插件调用 onEnable()
游戏启动（每次）
  └─ onBeforeLaunch(LaunchContext) → 游戏进程创建 → onAfterLaunch(LaunchContext, ManagedProcess)
用户在 设置→插件 切换开关
  └─ 启用：立即调用 onEnable()；禁用：立即调用 onDisable()（选择持久化到 plugins.json）
重新加载插件 / 启动器退出
  └─ 对所有启用的插件调用 onDisable()，随后卸载（jar 句柄释放）
```

注意：

- 所有钩子运行在**启动器后台线程**，不要长时间阻塞；不要直接操作 JavaFX 控件，需要 UI 时用 `Platform.runLater` 或 `PluginContext` 提供的对话框方法；
- 同一插件 jar 在启动期间只实例化一次；"重新加载插件"会重新实例化；
- 插件异常不会影响启动器本体与其他插件（每次钩子调用都有独立异常捕获）。

## 4. 给 AI / 自动化编写插件的操作清单

1. 确定功能范围与 `id`（建议点分命名空间，如 `org.example.foo`）；
2. 创建 `hmcl.plugin.json` + 继承 `HmclPlugin` 的入口类（可复制 `docs/plugin/example/`）；
3. 编译：`javac --release 17 -cp HMCL-3.16.4.jar`（用本仓库构建产物，或从 `HMCL/build/libs` 获取）；
4. 打 jar：清单放 jar 根目录（`jar --create --file xxx.jar -C <目录> hmcl.plugin.json -C <classes> .`，或用 `example/build.ps1`）；
5. 放入 `.hmcl\plugins\`，重启启动器，或在 设置 → 插件 点击"重新加载插件"；
6. 验证：插件列表出现且状态为"已启用"，启动器日志（`.hmcl\logs\`）出现 `[<id>]` 前缀的输出；
7. 迭代：修改代码 → 重新打包 → "重新加载插件"（无需重启启动器）。

最小示例见 [../example/README.md](./example/README.md)，API 细节见 [plugin-api.md](./plugin-api.md)。

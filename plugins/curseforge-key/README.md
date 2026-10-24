# CurseForge 下载源

让下载页的**模组、整合包、资源包、光影**分区出现 **CurseForge** 下载源；第一次选用时要求填写 **CurseForge API Key**，验证通过后加密保存、立即生效。

## 解决什么问题

官方构建里 CurseForge 是否可用，只看一件事：编译时有没有注入 API Key。

```java
public static boolean isAvailable() { return !API_KEY.isEmpty(); }
```

自己构建的 jar 没有 key，于是：

- 下载源列表里**只有 Modrinth**；
- **世界分区一个源都没有**（它只支持 CurseForge，Modrinth 没有世界分区）；
- 界面上没有任何提示，看起来像功能坏了。

这个插件把「有没有 key」从**编译期**变成**运行期**可填。

## 效果

```
┌─ 下载源 ────────────────┐
│ Modrinth               │
│ CurseForge             │  ← 现在会出现
└────────────────────────┘
        ↓ 第一次点 CurseForge
┌─ 填写 CurseForge API Key ─────────────────────────┐
│ CurseForge 的下载源需要一枚 API Key 才能使用。     │
│ 只需填写一次，之后会自动保存。                     │
│                                                   │
│ 到 CurseForge 控制台申请一枚 Key   ← 可点击        │
│                                                   │
│ [ ●●●●●●●●●●●●●●●● ]  ← 密码框，不明文显示         │
│                                                   │
│                          [保存并启用] [取消]       │
└───────────────────────────────────────────────────┘
```

## 怎么用

1. 把 `dist\org.dsh.curseforge-key_1.2.0.jar` 放进 `.hmcl\plugins\`；
2. 重启启动器（或 设置 → 插件 → 重新加载）；
3. 下载页 → 任意内容分区 → 右上「下载源」→ 选 **CurseForge**；
4. 弹出窗口里填 key → 保存 → 源立刻可用。

Key 在 https://console.curseforge.com/ 免费申请。

想换 key 或清除：删掉 `.hmcl\plugins\data\org.dsh.curseforge-key\curseforge-key.txt`，重启后重新填。

## 实现要点

### 1. 让 CurseForge 出现在列表里

列表是在页面构造时根据 `API_KEY` 生成的，所以 key 必须在页面构造**之前**就位。插件启用时：

- **有保存的 key** → 直接写入，CurseForge 正常可用；
- **没有 key** → 写入一个**哨兵值**（`unset-curseforge-key`），让 `isAvailable()` 为 true，条目因此出现在列表里。

### 2. 写进 `static final` 字段 —— 而且必须写两处

`API_KEY` 是 `public static final String`，`Field.set` 会抛 `IllegalAccessException`（实测）。Java 12 之后清除 `final` 修饰符的老办法也失效了（`Field.modifiers` 字段已不存在）。真正可用的手段是 `sun.misc.Unsafe` 直接写字段存储。

**但只写 `API_KEY` 是不够的——这是 1.1.0 的一个严重 bug。** 启动器发请求时并不读 `API_KEY`，而是在 `NetworkUtils` 的静态初始化里**快照一次**：

```java
private static final List<Pair<String, String>> API_KEYS;
static {
    if (CurseForgeRemoteAddonRepository.API_KEY.isEmpty())
        API_KEYS = List.of();                       // ← 永久空表
    else
        API_KEYS = List.of(pair("api.curseforge.com", ...), pair("forgecdn.net", ...));
}
```

插件运行时这个类**早就初始化过了**（空表）。于是：

- `API_KEY` 被改写 → `isAvailable()` 为 true → CurseForge **出现在列表里**；
- 但 `API_KEYS` 仍是空表 → **每个请求都不带 header** → CurseForge 一律返回 403。

现象就是：**填入完全正确的 key，仍然提示"CurseForge 拒绝了这个 Key"**。实测确认 `API_KEY` 已是新值，而 `API_KEYS` 仍是 `[]`。

所以现在**两处都写**：`API_KEY` 加上重建 `API_KEYS` 表。实测注入生效：

```
install => Result[ok=true, ...]
injected x-api-key = 00000000-0000-0000-0000-000000000000   ← 修复前这里是 null
```

`Unsafe` 在 Java 25 已被标记为**终止弃用**、未来会移除。所以失败是显式处理的：写不进去就告诉用户「当前 Java 版本可能已移除 sun.misc.Unsafe」，而不是静默失效。

### 3. 校验 key 是真的能用

**这一条也踩过坑。** 最初我用 `/v1/categories` 做校验，判断「请求没抛异常 = key 有效」。实测发现**这个端点根本不需要 key 就返回正常数据**，于是任何字符串——包括我瞎编的——都被判为有效、被保存。

现在改用 `/v1/mods/search`，这个端点真正需要 `X-API-KEY`。

另外，**不能靠启动器的 `HttpRequest` 判断状态码**：`ignoreHttpErrorCode` 其实不生效，403 时 `getInputStream()` 仍会抛一个不带状态码的裸 `IOException`，无法区分「key 错了」和「网络不通」。所以校验直接用 `HttpURLConnection`，状态码和错误流都可读。

### 4. 选用时的拦截（有一处已知妥协）

选中 CurseForge 后，插件把选择还原并弹出填写框。

**无法阻止的一次请求**：启动器自己也在这个下拉框上注册了监听器（用来发起搜索），**注册早于插件**，而监听器按注册顺序触发。所以启动器那次搜索必然先发出——同步还原拦不住，延迟还原反而会让它发两次。这两种顺序我都实测过，都会在弹窗出现前产生一次 403。

那个请求无法从外部阻止，所以让它发生：返回 403、搜索失败、界面不显示结果，**没有任何实际损害**（不消耗配额、不下载任何东西）。填写 key 之后就不会再走到这条分支。我把这点写在这里而不是假装它不存在。

### 5. key 怎么保存

存在 `.hmcl\plugins\data\org.dsh.curseforge-key\curseforge-key.txt`：

```
v1:<base64 salt>:<base64 混淆后的 key>
```

混淆用「per-install 随机盐 + 本机特征（OS/架构/用户名/主机名）」派生的密钥流做 XOR。

**这是混淆，不是加密。** 有意为之并明确说明：有源码的人一定能还原。理由是 key 无论如何都要明文发给 CurseForge，而启动器手上没有可用于真加密的秘密——声称「加密保存」会是误导。它挡住的是被随手看到、被截图、被同步到别的目录；把文件拷到另一台机器也就失效了。

## 测试

| 测试 | 断言数 | 覆盖内容 |
| --- | --- | --- |
| `KeyKeyStoreTest` | 27 | `API_KEY` 能被写入且 `isAvailable()` 跟随；空/纯空格/null 被拒；保存→读回→清除往返；文件里**不含明文**；损坏/版本不符/base64 非法/空文件都当「没有 key」且不抛；新实例能读回（模拟重启） |
| `NetworkSyncTest` | 9 | 先强制初始化 `NetworkUtils`（复现空表状态），再写入 key，断言 `API_KEYS` **从 `[]` 变成含两个 host 的表**且带上新 key |
| `KeyVerifierTest` | 10 | 空输入本地拒绝、不发网络请求；**瞎编的 key 必须被拒**；校验不抛异常 |

合计 **46 条**，全部通过。

```powershell
javac "@javac-main.args"
javac "@javac-test.args"
java -cp "out-test;out;<HMCL jar>;<JavaFX jars>" org.dsh.cfkey.KeyKeyStoreTest
java -cp "out-test;out;<HMCL jar>;<JavaFX jars>" org.dsh.cfkey.KeyVerifierTest
```

`KeyVerifierTest` 需要网络；离线时相关断言记为 `skip` 而不是失败。

## 已知限制

- **依赖 `sun.misc.Unsafe`**。Java 未来移除它之后需要换方案（例如 `--add-opens` + 别的手段，或等启动器把这字段改成可写）。当前在 JDK 17/21/25 上实测可用。
- **只影响本次运行**。写入的是内存里的字段；重启后插件重新从文件读取并再次写入，所以对用户是无感的。但如果同时用官方构建运行，官方构建不受影响。
- **哨兵值可能触发一次 403 搜索**（见上文第 4 点）。
- 插件**不修改启动器源码**，纯运行时干预。

## 版本历史

- **1.2.0**：**修复「正确 key 仍被拒绝」**。启动器在 `NetworkUtils` 里快照了一份 key 表，只改 `API_KEY` 不会更新它，导致请求不带 header、一律 403。现在两处同步写入。校验也改用 `HttpURLConnection` 读真实状态码（启动器的 `ignoreHttpErrorCode` 不生效）。
- **1.1.0**：修复校验漏洞——`/v1/categories` 无需 key 即返回数据，导致任何 key 都被判为有效（会把错误 key 保存下来）。改用 `/v1/mods/search`。
- **1.0.0**：首次发布。

# 用 GitHub Topic 收录插件

GitHub 的 **topic** 是贴在仓库上的公开标签，访问
`https://github.com/topics/<topic>` 就能看到所有打了该标签的仓库——
这正是「单独建一个 topic，把插件全部收录进去」的原生做法。

本仓库采用的 topic 命名：**`hmcl-plugin`**

## 为什么用 topic

- 不需要额外建"索引仓库"，GitHub 自带聚合页
- 每个插件可以独立成仓库，也可以像本仓库这样放在子目录里，两者都能被检索
- 用户、搜索引擎、GitHub 搜索都能通过 topic 找到

## 操作步骤

### 1. 给本仓库打 topic

打开仓库页面 → 右侧 **About** 区域的齿轮图标 → **Topics** 填入：

```
hmcl-plugin
hmcl
minecraft
minecraft-launcher
launcher-plugin
```

或者在仓库页面直接访问：

```
https://github.com/<owner>/<repo>/topics
```

### 2. 让每个插件也能被单独检索

如果后续把某个插件拆成独立仓库，同样给它打上 `hmcl-plugin`，
这样 `https://github.com/topics/hmcl-plugin` 会把它们全部聚合在一起。

### 3. 用 GitHub 搜索交叉检索

```
topic:hmcl-plugin
topic:hmcl-plugin language:java
topic:hmcl-plugin plugins
```

## 建议的 topic 组合

| topic | 用途 |
| --- | --- |
| `hmcl-plugin` | **主标签**，所有插件统一使用 |
| `hmcl` | 启动器相关项目 |
| `minecraft-launcher` | 归类到启动器大类 |
| `launcher-plugin` | 与其它启动器插件并列 |
| `minecraft` | 最宽泛的兜底标签 |

## 配套：仓库描述

在 About 里填写一句能直接说明用途的描述，例如：

```
HMCL 3.16.4 定制版启动器与其插件合集（10 个插件）
```

并勾选 **Releases** / **Packages** 之外的 **Website** 留空即可。
Topics 与 Description 会一起出现在 `github.com/topics/hmcl-plugin` 的卡片上。

## 本仓库内的插件索引

除了 GitHub topic，仓库内还有一份人工维护的索引：[PLUGINS.md](../PLUGINS.md)，
列出每个插件的目录、功能与构建方式，方便不走 topic 的人直接查阅。

# 用 GitHub Topic 收录插件

GitHub 的 **topic** 是贴在仓库上的公开标签，访问
`https://github.com/topics/<topic>` 就能看到所有打了该标签的仓库——
这正是「单独建一个 topic，把插件全部收录进去」的原生做法。

本仓库采用的**主 topic**：**`erren282s-launcher-plugins`**

完整标签组合（已配置好）：

| topic | 用途 |
| --- | --- |
| `erren282s-launcher-plugins` | **主标签**，本系列的插件都打这个 |
| `hmcl-plugin` | 通用标签，便于和别人的 HMCL 插件一起被检索 |
| `hmcl` | 启动器相关项目 |
| `minecraft-launcher` | 启动器大类 |
| `launcher-plugin` | 与其它启动器插件并列 |
| `minecraft` | 最宽泛的兜底标签 |

## 聚合页

- 主标签：<https://github.com/topics/erren282s-launcher-plugins>
- 通用标签：<https://github.com/topics/hmcl-plugin>

## 为什么用 topic

- 不需要额外维护「索引仓库」，GitHub 自带聚合页
- 每个插件可以独立成仓库，也可以像本仓库这样放在子目录里，两者都能被检索
- 用户、搜索引擎、GitHub 搜索都能通过 topic 找到

## 如何修改 topic

仓库页面 → 右侧 **About** 旁的齿轮 ⚙ → **Topics** 增删后保存；
或用 API：

```bash
curl -X PUT \
  -H "Authorization: Bearer <token>" \
  -H "Accept: application/vnd.github.mercy-preview+json" \
  https://api.github.com/repos/erren282/hmcl-plugins/topics \
  -d '{"names":["erren282s-launcher-plugins","hmcl-plugin","hmcl","minecraft-launcher","launcher-plugin","minecraft"]}'
```

> topic 名称只能用小写字母、数字和连字符，空格与撇号会被规范成连字符，
> 所以「erren282's launcher plugins」最终是 `erren282s-launcher-plugins`。

## 让后续插件也进同一个 topic

把新插件拆成独立仓库时，同样给那个仓库打上 `erren282s-launcher-plugins`，
它就会与主仓库一起出现在聚合页里；主标签相当于「这一系列插件」的集合。

## 配套：仓库内索引

除了 GitHub topic，仓库里还有一份人工维护的索引：[PLUGINS.md](../PLUGINS.md)，
列出每个插件的目录、功能与构建方式，方便不走 topic 的人直接查阅。

# 账户皮肤切换插件（org.dsh.account-skin）

在**账户列表**的每个账户上注入“切换皮肤”“切换披风”两个按钮，
支持三种账户类型的皮肤与披风切换：

| 账户类型 | 在线皮肤（LittleSkin） | 自定义皮肤（本地文件） | 披风 |
| --- | --- | --- | --- |
| 微软账户 | 从 LittleSkin 皮肤库挑选 → 上传到 Mojang（**全局生效**） | 本地文件 → 上传到 Mojang | 切换账户**已拥有的官方披风**（Mojang 不支持上传披风） |
| 皮肤站账户（外置登录） | 从 LittleSkin 皮肤库挑选 → 上传到皮肤站（**站内生效**，需皮肤站支持材质上传，LittleSkin 支持） | 本地文件 → 上传到皮肤站 | 同皮肤（Yggdrasil 披风上传接口） |
| 离线账户 | “跟随 LittleSkin 皮肤源”（皮肤+披风按用户名跟随）或从皮肤库挑选下载 | 本地文件（**游戏内生效**，经 HMCL 本地皮肤源注入） | 本地披风文件 / 跟随 LittleSkin |

对话框内含**整体 3D 实时预览**（拖动旋转 / 滚轮缩放）：

- 皮肤库中选择任意皮肤/披风时，右侧即时渲染穿戴后的整体效果；
- 皮肤可切换**史蒂夫形态 / 艾利克斯形态**（宽臂 / 纤细手臂）；
- 披风可切换**普通形态**（穿在史蒂夫背上的披风）与**鞘翅形态**（按原版方式渲染：
  原版玩家鞘翅直接使用玩家披风贴图，翼面 UV 为披风贴图 (36,4)-(46,24) 区域
  （texOffs(34,2) 10x20x2 双翼的原版姿态 xRot=15°、zRot=∓15°），切换时自动转到背面视角）；
- 列表中选中的行以蓝色圆角方框高亮。

## 使用

把 `dist/org.dsh.account-skin_1.0.0.jar` 放入 `.hmcl\plugins\`，重启启动器。
账户列表里每个账户行会多出两个按钮：

- 🎨 **切换皮肤** → 选择“在线皮肤（LittleSkin 皮肤库）”或“自定义皮肤（本地文件）”；离线账户额外有“跟随 LittleSkin 皮肤源”；
- 🚪 **切换披风** → 同上；微软账户改为“选择已拥有的官方披风”。

## 实现说明

- 账户行按钮通过启动器的 `PluginMenuService.registerAccountAction` 扩展点注册（`onEnable` 注册，`onDisable` 注销）；
- 微软：`MicrosoftAccount.uploadSkin`（Mojang `POST /minecraft/profile/skins`）+ 披风 `GET/PUT /minecraft/profile/capes`；
- 皮肤站：`YggdrasilAccount.uploadSkin`（authlib-injector 材质上传接口），披风为同构的 `/cape` 端点；
- 离线：`OfflineAccount.setSkin`（`Skin.Type.LOCAL_FILE` / `LITTLE_SKIN`）；
- 皮肤库：`GET https://littleskin.cn/skinlib/list?filter={skin|cape}`（Laravel 简单分页，按 `next_page_url`/`prev_page_url` 翻页），列表项带材质缩略图（`/skinlib/info/{tid}` 取 hash → `/textures/{hash}`）。

## 构建

```powershell
.\build.ps1 -HMCLJar <HMCL-3.16.4.jar 路径>
```

需要 JDK 17+；JavaFX 依赖会自动从 Gradle 缓存发现（或用 `-JavaFXDir` 指定）。

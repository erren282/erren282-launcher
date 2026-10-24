# MciLm 联机

通过 [MciLm-link](https://github.com/) 实现跨网络联机，入口内置在 HMCL 的「多人联机」页。

## 功能

- 在多人联机页新增 MciLm 标签页，提供创建/加入房间的界面
- 负责启动并管理本地的 `MciLm-link` 进程，把联机状态反馈到界面
- 房间号记录在启动器目录的 `.mcilm-room-id`，重启后仍可回到同一房间

## 依赖

需要本机的 `MciLm-linkc.exe`。插件会从数据目录
`.hmcl/plugins/data/org.dsh.mcilm-link/` 下查找并启动它。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

产物是 `org.dsh.mcilm-link_<version>.jar`，放入 `.hmcl/plugins/` 即可。

## 结构

| 文件 | 职责 |
| --- | --- |
| `MciLmPlugin.java` | 插件入口，注册联机标签页 |
| `MciLmPage.java` | 多人联机页的界面 |
| `MciLmTab.java` | 联机页内的标签页封装 |
| `MciLmProcess.java` | `MciLm-linkc.exe` 进程的启动与生命周期 |
| `MciLmApi.java` | 与本地进程通信的接口封装 |
| `UiPatcher.java` | 对启动器原生界面的小改动 |
| `Strings.java` | 界面文案 |

## 清单

```json
{
  "id": "org.dsh.mcilm-link",
  "entry": "org.dsh.mcilm.MciLmPlugin",
  "minLauncherVersion": "3.16.4",
  "access": "root"
}
```

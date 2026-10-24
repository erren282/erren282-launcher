# 推送到 GitHub 的操作说明

本地仓库已经准备好（3 次提交、1577 个文件、约 16 MB），只差最后一步推送。
由于本机没有 GitHub 凭据，这一步需要你自己执行。

## 一、在 GitHub 上新建仓库

1. 打开 <https://github.com/new>
2. **Repository name** 填：`hmcl-plugins`（或你喜欢的名字）
3. **Public**（想让 topic 聚合页收录，必须是公开仓库）
4. **不要**勾选 "Add a README file" / ".gitignore" / "license"（本地已有）
5. 点 **Create repository**

## 二、在本地推送

在本机执行（把 `<你的用户名>` 换成实际值）：

```powershell
cd E:\game\gamea\mcl\hmcl-github

git remote add origin https://github.com/<你的用户名>/hmcl-plugins.git
git push -u origin main
```

首次推送会弹出 GitHub 登录窗口（Git Credential Manager），
在浏览器里授权一次即可，之后不再询问。

> 建议用 `git push -u origin main`，`-u` 会把 origin/main 设为上游，
> 之后直接 `git push` 就行。

## 三、给仓库打 topic（关键一步）

推送完成后：

1. 打开仓库页面，点右侧 **About** 旁的齿轮 ⚙
2. **Description** 填：
   ```
   HMCL 3.16.4 定制版启动器与其插件合集（10 个插件）
   ```
3. **Topics** 依次添加：
   ```
   hmcl-plugin
   hmcl
   minecraft-launcher
   launcher-plugin
   minecraft
   ```
4. 保存

完成后访问 <https://github.com/topics/hmcl-plugin> 就能看到这个仓库。

## 四、验证

```powershell
cd E:\game\gamea\mcl\hmcl-github
git log --oneline          # 应有 3 次提交
git ls-files | Measure-Object -Line   # 1577
```

仓库页面应能看到：`README.md`、`PLUGINS.md`、`plugins/` 下 10 个插件目录、
`docs/plugin/` 下的三份文档（plugin-api.md、plugin-format.md、GITHUB-TOPIC.md）。

## 说明

- 本仓库是**独立的新仓库**，与 `E:\game\gamea` 下那个 Scary-game 仓库无关，
  没有动过它的任何内容。
- 若之后要更新：在 `E:\game\gamea\mcl\hmcl-github` 里修改后 `git add -A`、
  `git commit`、`git push` 即可。
- 插件源码的日常维护位置仍是 `E:\game\gamea\mcl\HMCL\docs\plugin\`，
  更新时把改动同步到本仓库的 `plugins/` 下。

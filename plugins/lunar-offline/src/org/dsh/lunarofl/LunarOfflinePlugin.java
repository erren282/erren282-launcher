package org.dsh.lunarofl;

import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.plugin.PluginDownloadTab;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.PluginPageAction;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.util.logging.Logger;

import java.nio.file.Path;

/// Lunar 客户端插件：在下载页提供「Lunar 客户端」标签，把客户端整份下载下来并安装成
/// 启动器里的一个游戏版本，之后一键启动，完全不需要 Celestial。
///
/// <p>旧版插件还会在主页侧边栏注册一个「Lunar 离线客户端」入口（用于启动 / 停止 Celestial），
/// 那个入口与下载页功能重叠、还容易让人误以为是启动游戏的主入口，现已移除；下载页的
/// Lunar 标签保留，Celestial 相关代码仅为兼容老用户的数据而保留。</p>
public class LunarOfflinePlugin extends HmclPlugin {

    private static PluginContext pluginContext;
    private static Path dataDirectory;
    private static PluginDownloadTab downloadTab;

    @Override
    public void onEnable() {
        pluginContext = getContext();
        dataDirectory = getContext().getDataDirectory();
        getContext().logInfo("Lunar 离线客户端已启用，数据目录：" + dataDirectory);

        // 下载页里的「Lunar 客户端」标签：不经过 Celestial，直接下载并一键启动
        downloadTab = new LunarDownloadTab(dataDirectory);
        PluginMenuService.registerDownloadTab(downloadTab);

        // 「Lunar 微软会话」页面：查看 accounts.json 状态、手动触发刷新
        PluginMenuService.registerPage(new PluginPageAction() {
            @Override
            public String getTitle() {
                return "Lunar 微软会话";
            }

            @Override
            public String getDescription() {
                return "查看/刷新 Lunar 客户端的微软账户会话（多人游戏验证）";
            }

            @Override
            public javafx.scene.Node createPage() {
                return new LunarSessionPage();
            }
        });

        // 「Lunar 启动修复」不再往「插件页面」列表里放入口（那个列表只留插件自己的
        // 页面）。修复能力本身没有任何删减，也不需要用户手动进页面：
        //   * 插件启用时做一次全量体检 + 自动修复（见下面的后台线程）；
        //   * 每次启动前做 preflight 修复（onBeforeLaunch）；
        //   * 异常退出后按已知症状自动诊断 + 修复（onAfterLaunch）。
        // LunarRepairPage 这个类保留着，随时可以再接回某个入口。

        // 把新版入口包装 jar 推给已安装的 Lunar 版本（包装类现在会自带
        // 微软会话刷新 + gameipc 服务，老安装必须换上新 jar 才有这套行为）
        Thread upgrader = new Thread(() -> {
            try {
                // retryWhenLocked：游戏正在运行时包装 jar 被它的 classpath 锁着，
                // 传 true 让它在游戏退出后自动补上，不必让用户重启两次启动器
                java.util.List<String> upgraded = LunarWrapperUpgrader.upgradeAll(true);
                if (!upgraded.isEmpty())
                    getContext().logInfo("已更新 Lunar 入口包装的版本：" + String.join(", ", upgraded));
            } catch (Throwable t) {
                getContext().logWarning("升级 Lunar 入口包装失败：" + t);
            }
        }, "lunar-wrapper-upgrade");
        upgrader.setDaemon(true);
        upgrader.start();

        // 老格式的「零库壳版本」自带一份原版客户端 jar（每个 Lunar 版本多占一份游戏本体）。
        // 后台就地升级成“引用原版 jar”的形式，并把那份副本删掉——用户不必重装。
        Thread shellUpgrader = new Thread(() -> {
            try {
                org.jackhuang.hmcl.game.HMCLGameRepository repository =
                        org.jackhuang.hmcl.setting.GameDirectoryManager.getSelectedRepository();
                if (repository == null)
                    return;
                LunarVersionInstaller.upgradeInstalledShellVersions(repository.getBaseDirectory());
                // 插件生成的 `<版本>-vanilla` 原版副本也要隐藏：它带着原版 mainClass 与资产索引，
                // 不隐藏就会在版本列表里显示成「第二个原版游戏」
                LunarVersionInstaller.hideInstalledVanillaCopies(repository.getBaseDirectory());
                // 修正已安装版本的启动要求：Java 要求低于基础版本（原版 26.x 要 25）、
                // 以及 Lunar 清单的 GC 参数与启动器自带 GC 冲突（Multiple garbage collectors selected）
                LunarVersionInstaller.repairInstalledVersions(repository.getBaseDirectory());
                // Lunar 客户端会自我更新自己的 SNAPSHOT jar；把 libraries 里的 classpath 副本重新对齐，
                // 否则新旧混用会让 Mixin 初始化崩掉（@Mixin target ... was not found）
                LunarVersionInstaller.refreshInstalledClasspaths(repository.getBaseDirectory());
                // 全量体检 + 自动修复：外部文件名、Lunar 运行库（缺则下载并按顺序装好）、Java 要求等。
                // 这是「下次启动不会再撞同一堵墙」的关键一步，首次会下载几十 MB 运行库。
                java.util.List<LunarRepair.Report> repaired = LunarRepair.repairAll(repository.getBaseDirectory());
                for (LunarRepair.Report report : repaired)
                    getContext().logInfo("已自动修复 Lunar 版本：" + report.summary());

                // 最后无条件让启动器重新读一遍 versions/：上面这几步（升级壳版本、对齐 classpath、
                // 修版本 json）都是在启动器加载完版本列表之后写盘的，不刷新的话用户第一次进去看到的
                // 还是旧列表，得手动点「刷新」——这正是那个「第一次进版本列表没有版本」的毛病。
                LunarRepair.refreshVersionList(repository);
            } catch (Throwable t) {
                getContext().logWarning("升级 Lunar 壳版本失败：" + t);
            }
        }, "lunar-shell-upgrade");
        shellUpgrader.setDaemon(true);
        shellUpgrader.start();

        // 启动器退出时一并结束 Celestial，避免留下孤儿进程（老版本用户可能还在用它）
        Runtime.getRuntime().addShutdownHook(new Thread(CelestialProcess.get()::stopOnShutdown,
                "lunar-offline-shutdown"));
    }

    @Override
    public void onDisable() {
        if (downloadTab != null) {
            PluginMenuService.unregisterDownloadTab(downloadTab);
            downloadTab = null;
        }
        getContext().logInfo("Lunar 离线客户端已停用");
    }

    /// 非微软账户启动 Lunar 时置位：客户端一启动就会被关掉，并弹出提示。
    ///
    /// <p>启动钩子不能取消启动（[HmclPlugin#onBeforeLaunch] 返回 void），所以只能
    /// "起来就关"：在启动前记下判定结果，进程起来后立刻 [ManagedProcess#stop]。</p>
    private volatile boolean refuseNonGenuineLaunch;

    /// 当前选中的账户是否为微软（正版）账户。
    ///
    /// <p>判据是账户实现类本身：离线账户（{@code auth.offline.OfflineAccount}）与
    /// Authlib-Injector / Yggdrasil 这类第三方验证都不是正版登录。不用
    /// {@code AccountFactory.getLoginType()}——它只说明登录需要用户名还是密码，
    /// 第三方验证同样需要，区分不出来。</p>
    private static boolean isGenuineMicrosoftLogin() {
        try {
            org.jackhuang.hmcl.auth.Account account =
                    org.jackhuang.hmcl.setting.Accounts.getSelectedAccount();
            return account instanceof org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
        } catch (Throwable t) {
            // 读不到账户信息时按"非正版"处理：这是一道限制，宁可拒绝。
            return false;
        }
    }

    /// 启动 Lunar 版本之前做两件事：
    /// <ol>
    ///   <li><b>正版校验</b>：只允许微软账户，非正版直接记下拒绝标记（客户端起来后立即关闭）；</li>
    ///   <li>体检 + 修复（外部文件名、Lunar 运行库顺序与缺失、Java 要求、GC 参数、基础版本、classpath 副本）。</li>
    /// </ol>
    @Override
    public void onBeforeLaunch(org.jackhuang.hmcl.plugin.LaunchContext launchContext) {
        try {
            String versionId = launchContext.getVersionId();
            if (versionId == null || !versionId.startsWith("Lunar-"))
                return;

            // 正版校验放最前面：非正版时后面的修复工作没有意义，客户端马上就会被关掉。
            refuseNonGenuineLaunch = !isGenuineMicrosoftLogin();
            if (refuseNonGenuineLaunch) {
                getContext().logWarning("拒绝以非微软账户启动 " + versionId
                        + "：Lunar 客户端仅支持正版登录，客户端启动后会被立即关闭");
                return;
            }

            org.jackhuang.hmcl.game.GameRepository repository = launchContext.getRepository();
            if (!(repository instanceof org.jackhuang.hmcl.game.DefaultGameRepository gameRepository))
                return;

            // 把启动器里选中的正版账户同步进 Lunar 的 accounts.json：
            // 游戏只认自己那份文件，不同步的话起起来还是上一次的账户（或者干脆停在账户选择界面）。
            // 放在体检之前：这一步决定了"以谁的身份进游戏"，失败也不阻断启动。
            try {
                org.dsh.lunarofl.auth.LunarSessionRefresher.Log accountLog =
                        (org.dsh.lunarofl.auth.LunarSessionRefresher.Log)
                                message -> getContext().logInfo(message);
                // 先规整 accounts.json：游戏内「点头像切换账户」要求每个账户都有 localId /
                // type(XBOX|MOJANG) / remoteId / minecraftProfile，缺一个卡片就点不动
                // （能显示、点了没反应，只能重新登录）。
                org.dsh.lunarofl.auth.LunarAccountBridge.normalizeAccounts(accountLog);

                org.dsh.lunarofl.auth.LunarAccountBridge.Result synced =
                        org.dsh.lunarofl.auth.LunarAccountBridge.syncSelectedAccount(accountLog);
                if (synced != null)
                    getContext().logInfo("已把启动器账户 " + synced.profileName()
                            + " 同步为 Lunar 当前账户");
            } catch (Throwable t) {
                getContext().logWarning("同步启动器账户到 Lunar 失败（游戏可能显示上一次的账户）：" + t);
            }

            LunarRepair.Report report = LunarRepair.preflight(gameRepository.getBaseDirectory(), versionId);
            if (report != null && report.repaired())
                getContext().logInfo("启动前自动修复：" + report.summary());

            // Windows 命令行上限 32767 字符，超了就是「CreateProcess error=206, 文件名或扩展名太长」。
            // 库一多（Lunar 自己的库 + 运行库 + 原版库）很容易撞上，所以提前提醒。
            int classpathLength = LunarRepair.classpathLength(gameRepository, versionId);
            if (classpathLength > 20000)
                getContext().logWarning("Lunar 版本 " + versionId + " 的 classpath 长达 " + classpathLength
                        + " 字符（Windows 命令行上限 32767），启动可能报 CreateProcess error=206；"
                        + "插件会在下次启动前自动重新对齐 classpath，也可以删掉该版本后重新下载");
        } catch (Throwable t) {
            getContext().logWarning("启动前修复 Lunar 版本失败：" + t);
        }
    }

    /// 启动之后盯着这个进程：异常退出就按已知症状诊断 + 修复，并提示用户再启动一次。
    ///
    /// <p>这样用户不必把崩溃报告发给作者：下一次启动前就已经把能修的都修好了。</p>
    ///
    /// <p>非正版登录的启动会在这里被立刻掐断，不再进入崩溃诊断——进程是插件自己
    /// 杀掉的，交给诊断只会被误判成崩溃、白跑一轮"修复"。</p>
    @Override
    public void onAfterLaunch(org.jackhuang.hmcl.plugin.LaunchContext launchContext,
                              org.jackhuang.hmcl.util.platform.ManagedProcess process) {
        try {
            String versionId = launchContext.getVersionId();
            if (versionId == null || !versionId.startsWith("Lunar-") || process == null)
                return;

            if (refuseNonGenuineLaunch) {
                refuseNonGenuineLaunch = false;
                getContext().logWarning("已关闭以非微软账户启动的 Lunar 客户端 " + versionId);
                try {
                    process.stop();
                } catch (Throwable t) {
                    getContext().logWarning("关闭 Lunar 客户端失败：" + t);
                }
                javafx.application.Platform.runLater(() -> getContext().showWarning(
                        "无法启动", "该客户端仅支持正版登录，非正版用户不可使用！"));
                return;
            }

            org.jackhuang.hmcl.game.GameRepository repository = launchContext.getRepository();
            if (!(repository instanceof org.jackhuang.hmcl.game.DefaultGameRepository gameRepository))
                return;

            Path gameDirectory = gameRepository.getBaseDirectory();
            Thread watcher = new Thread(() -> watchLaunch(gameDirectory, versionId, process),
                    "lunar-launch-watcher");
            watcher.setDaemon(true);
            watcher.start();
        } catch (Throwable t) {
            getContext().logWarning("启动后监视 Lunar 版本失败：" + t);
        }
    }

    /// 等进程结束；判为崩溃时诊断 + 修复，修好了就弹提示让用户重试。
    private void watchLaunch(Path gameDirectory, String versionId,
                             org.jackhuang.hmcl.util.platform.ManagedProcess process) {
        try {
            while (process.isRunning())
                Thread.sleep(1000);

            int exitCode = process.getExitCode();
            java.util.List<String> lines = process.getLines(line -> true);
            boolean crashed = exitCode != 0 || lines.stream().anyMatch(LunarOfflinePlugin::looksLikeCrash);
            getContext().logInfo("Lunar 版本 " + versionId + " 退出（exit=" + exitCode + "，"
                    + (crashed ? "疑似崩溃" : "正常退出") + "）");
            if (!crashed)
                return;

            LunarRepair.Report report = LunarRepair.afterFailure(gameDirectory, versionId, lines);
            if (report.repaired())
                notifyOnFx("Lunar 启动失败已自动修复", report.summary() + "\n\n请再启动一次试试。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            getContext().logWarning("诊断 Lunar 启动失败时出错：" + t);
        }
    }

    /// 输出里有没有崩溃特征（客户端自己打印的崩溃、Lunar 的启动失败、常见致命异常）。
    private static boolean looksLikeCrash(String line) {
        return line.contains("An error occurred while launching Lunar Client")
                || line.contains("Minecraft Crash Report")
                || line.contains("LUNARCLIENT_STATUS_CRASHED")
                || line.contains("java.lang.NoSuchMethodError")
                || line.contains("java.lang.NoClassDefFoundError")
                || line.contains("java.lang.UnsatisfiedLinkError")
                || line.contains("Multiple garbage collectors selected")
                || line.contains("ExceptionInInitializerError");
    }

    /// 跳到「下载」页面并选中 Lunar 标签。
    public static void showDownloadTab() {
        PluginDownloadTab tab = downloadTab;
        if (tab == null)
            return;
        Controllers.getDownloadPage().showPluginTab(tab);
        Controllers.navigate(Controllers.getDownloadPage());
    }

    static Path dataDirectory() {
        return dataDirectory;
    }

    /// 写入启动器日志。
    public static void log(String message) {
        PluginContext context = pluginContext;
        if (context != null)
            context.logInfo(message);
    }

    public static void error(String title, String message) {
        Controllers.dialog(message == null || message.isEmpty() ? "未知错误" : message,
                title, MessageDialogPane.MessageType.ERROR);
    }

    /// 弹出一个信息提示（成功类反馈）。
    public static void info(String title, String message) {
        Controllers.dialog(message == null ? "" : message,
                title, MessageDialogPane.MessageType.INFO);
    }

    /// 在 JavaFX 线程上弹提示（后台线程不能直接碰 UI）。
    public static void notifyOnFx(String title, String message) {
        javafx.application.Platform.runLater(() -> info(title, message));
    }

    /// 用系统默认程序打开一个文件（日志）。
    static void openFile(Path file) {
        try {
            java.awt.Desktop.getDesktop().open(file.toFile());
        } catch (Exception e) {
            error("打开文件失败", e.getMessage());
        }
    }
}

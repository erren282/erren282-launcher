package org.dsh.lunarofl.wrapper;

import org.dsh.lunarofl.auth.LunarGameIpcServer;
import org.dsh.lunarofl.auth.LunarSessionRefresher;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/// Lunar 版本的入口类。
///
/// <p><b>1. 参数归一化。</b>HMCL 启动时会把自己的参数和版本 json 的参数**拼在一起**，
/// 而 `--version` 在两边都会出现（基础版本的 `minecraftArguments`/`arguments.game` 里有
/// `--version ${version_name}`，也就是启动器里的实例名，例如 `Lunar-1.21.11`）。
/// Genesis 用 joptsimple 解析参数，`--version` 只允许一个值，出现两次会直接抛
/// `MultipleArgumentsForOptionException`；而且它的值必须是合法的 Minecraft 版本号，
/// 否则抛 `IllegalArgumentException: Invalid Minecraft version: ...`。
/// 所以先把命令行里所有的 `--version` 丢掉，再补上安装时写进 JVM 属性的那个游戏版本
/// （`-Ddsh.lunar.gameVersion=1.21.11`），最后通过反射调用 Genesis。</p>
///
/// <p><b>2. 扮演启动器（核心新增）。</b>Lunar 游戏进程的微软账户验证依赖官方启动器：
/// accounts.json 里的 MC 令牌过期后，游戏会连 `--ipcPort` 指向的 WebSocket 服务
/// （`lunarclient.gameipc.auth.v1.AuthService.RefreshAccount`）要求刷新；没有这个服务，
/// 游戏日志会出现 `[LC Microsoft Auth] No launcher open`，多人游戏也会因会话失效进不去。
/// 因此在把控制权交给 Genesis 之前，本类：</p>
/// <ol>
///   <li>把 `~/.lunarclient/settings/game/accounts.json` 里过期的 MC 令牌静默刷新一遍
///       （微软 MSA 刷新令牌 → XBL → XSTS → login_with_xbox，见 LunarSessionRefresher）；</li>
///   <li>在 127.0.0.1 上起一个 gameipc WebSocket 服务（LunarGameIpcServer），承接游戏
///       运行中的 RefreshAccount / AddAccount / OpenUrl 请求——这就是"把 Lunar 的微软
///       验证请求转发出来、用户验证完再授权回去"的那条通道；</li>
///   <li>把命令行里的 `--ipcPort 0` 换成服务的实际端口再交给 Genesis。</li>
/// </ol>
public final class GenesisLauncher {

    public static final String GAME_VERSION_PROPERTY = "dsh.lunar.gameVersion";

    private static final String GENESIS_MAIN_CLASS = "com.moonsworth.lunar.genesis.Genesis";

    private GenesisLauncher() {
    }

    public static void main(String[] args) {
        String gameVersion = System.getProperty(GAME_VERSION_PROPERTY, "").trim();

        List<String> normalized = new ArrayList<>(args.length + 2);
        for (int i = 0; i < args.length; i++) {
            if ("--version".equals(args[i])) {
                // 丢掉它的值，也一起丢掉（值可能是实例名，或者本身重复）
                if (i + 1 < args.length)
                    i++;
                continue;
            }
            normalized.add(args[i]);
        }
        if (!gameVersion.isEmpty()) {
            normalized.add("--version");
            normalized.add(gameVersion);
        }

        int ipcPort = startLauncherServices(normalized);
        if (ipcPort > 0)
            replaceArgumentValue(normalized, "--ipcPort", String.valueOf(ipcPort));

        try {
            Class<?> genesis = Class.forName(GENESIS_MAIN_CLASS, true, GenesisLauncher.class.getClassLoader());
            Method main = genesis.getMethod("main", String[].class);
            main.invoke(null, (Object) normalized.toArray(new String[0]));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.err.println("An error occurred while launching Lunar Client.");
            cause.printStackTrace(System.err);
            System.exit(1);
        } catch (ReflectiveOperationException e) {
            System.err.println("无法启动 Lunar 客户端：找不到 " + GENESIS_MAIN_CLASS);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /// 启动"扮演启动器"的两件事：会话预刷新 + gameipc 服务。任何失败都不阻断游戏启动。
    ///
    /// @return gameipc 服务的端口；服务没起来返回 -1（此时保留原来的 --ipcPort 值）
    private static int startLauncherServices(List<String> args) {
        String workingDirectory = argumentValue(args, "--workingDirectory");
        LauncherLog log = new LauncherLog(workingDirectory);
        Runtime.getRuntime().addShutdownHook(new Thread(log::flush, "dsh-launcher-log-flush"));

        String installationId = argumentValue(args, "--installationId");

        // 1) 预刷新：游戏加载账户前先把过期的 MC 令牌续上（官方启动器启动前也会做）
        try {
            LunarSessionRefresher.Result result = LunarSessionRefresher.refreshAll(log, false);
            log.line("会话预刷新完成：刷新 " + result.refreshed() + "，跳过 " + result.skipped()
                    + "，失败 " + result.failed());
            if (!result.notes().isEmpty())
                for (String note : result.notes())
                    log.line("  注意：" + note);
        } catch (Throwable t) {
            log.line("会话预刷新失败（游戏内仍可请求刷新）：" + t);
        }
        log.flush();

        // 2) gameipc 服务：承接游戏运行中的微软验证请求
        try {
            LunarGameIpcServer server = new LunarGameIpcServer(log, installationId,
                    new SwingDeviceCodeUi());
            int port = server.start();
            log.flush();

            // 3) 账户切换监视：游戏内的「点头像换账户」需要启动器主动推一条
            //    NotifySwitchAccount —— 游戏自己不会去重读 accounts.json，它只是把
            //    activeAccountLocalId 变了这件事等启动器告诉它。官方启动器在用户切换后立刻推，
            //    我们不推的话就是"点了没反应，只能重新登录"。
            //
            //    这里盯 accounts.json 的 activeAccountLocalId：无论变化是本进程（AddAccount）
            //    还是别处（HMCL 插件同步、官方启动器）写入的，都能感知到并转告游戏。
            startAccountSwitchWatcher(server, log);

            return port;
        } catch (Throwable t) {
            log.line("gameipc 服务启动失败，游戏会退回『No launcher open』的老行为：" + t);
            return -1;
        }
    }

    /// 盯 accounts.json 的 `activeAccountLocalId`，一变就向游戏推 NotifySwitchAccount。
    ///
    /// <p>轮询而不是 WatchService：Windows 上 WatchService 对"原子改名"的写入方式会报
    /// MODIFY 而不是 CREATE，且同一秒多次改动会合并，漏事件就直接导致账户切不过去；
    /// 每秒读一个小文件（几 KB）的开销可以忽略。</p>
    private static void startAccountSwitchWatcher(LunarGameIpcServer server, LauncherLog log) {
        Thread watcher = new Thread(() -> {
            String lastActive = null;
            String lastPushedProfile = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    java.util.Map<String, Object> root =
                            org.dsh.lunarofl.auth.LunarAccounts.load();
                    Object activeValue = root.get("activeAccountLocalId");
                    if (activeValue == null) {
                        continue;
                    }
                    String active = String.valueOf(activeValue);
                    if (active.equals(lastActive)) {
                        continue;
                    }
                    lastActive = active;

                    // 找到这个 localId 对应的账户，取它的角色 UUID
                    java.util.Map<String, Object> accounts =
                            org.dsh.lunarofl.auth.LunarAccounts.accounts(root);
                    Object accountValue = accounts.get(active);
                    if (!(accountValue instanceof java.util.Map<?, ?> raw)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> account = (java.util.Map<String, Object>) raw;
                    String profileId = org.dsh.lunarofl.auth.LunarAccounts.profileId(account);
                    String profileName = org.dsh.lunarofl.auth.LunarAccounts.profileName(account);
                    if (profileId == null || profileId.isBlank()) {
                        continue;
                    }
                    if (profileId.equals(lastPushedProfile)) {
                        continue;
                    }
                    lastPushedProfile = profileId;
                    log.line("检测到账户切换 → " + profileName + "，通知游戏");
                    server.notifySwitchAccount(profileId);
                    log.flush();
                } catch (Throwable t) {
                    // 文件可能正被另一个进程改写：下一轮再读
                    log.line("账户切换监视读取出错（忽略）：" + t);
                }
            }
        }, "dsh-account-switch-watcher");
        watcher.setDaemon(true);
        watcher.start();
        log.line("账户切换监视已启动（游戏内换账户会即时通知客户端）");
    }

    /// gameipc / 预刷新共用的日志：stderr 立即输出，缓存后写进游戏目录的 dsh-launcher.log。
    static final class LauncherLog implements LunarSessionRefresher.Log {
        private static final DateTimeFormatter TIME =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        private final String workingDirectory;
        private final StringBuilder buffer = new StringBuilder();

        LauncherLog(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        @Override
        public void line(String message) {
            String line = "[dsh-launcher] " + message;
            System.err.println(line);
            synchronized (buffer) {
                buffer.append(LocalDateTime.now().format(TIME)).append(' ')
                        .append(message).append(System.lineSeparator());
            }
        }

        /// 把缓存的日志追加到 `<workingDirectory>/dsh-launcher.log`（应用启动期与退出时各一次）。
        void flush() {
            String text;
            synchronized (buffer) {
                if (buffer.length() == 0)
                    return;
                text = buffer.toString();
                buffer.setLength(0);
            }
            if (workingDirectory == null || workingDirectory.isBlank())
                return;
            try {
                Path logFile = Path.of(workingDirectory.replace('\\', '/'), "dsh-launcher.log");
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    /// 取 `--flag value` 形式参数的值。
    static String argumentValue(List<String> args, String flag) {
        for (int i = 0; i + 1 < args.size(); i++)
            if (flag.equals(args.get(i)))
                return args.get(i + 1);
        return null;
    }

    /// 就地替换 `--flag value` 的 value（不存在时追加）。
    static void replaceArgumentValue(List<String> args, String flag, String value) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (flag.equals(args.get(i))) {
                args.set(i + 1, value);
                return;
            }
        }
        args.add(flag);
        args.add(value);
    }
}

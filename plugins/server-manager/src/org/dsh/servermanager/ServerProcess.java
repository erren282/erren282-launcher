package org.dsh.servermanager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Runs one Minecraft server process and captures its console output.
///
/// <p>A server can also be *adopted*: when the launcher restarts while a previously
/// started server is still running in the background, the service detects it by its
/// port and takes it over — console becomes a tail of `logs/latest.log`, commands
/// are unavailable (no stdin) and stopping force-kills the process by port.</p>
public final class ServerProcess {

    /// Number of console lines kept per server so re-selecting it restores the log.
    private static final int HISTORY_LIMIT = 2000;
    /// How long a liveness probe result is trusted (probes run on the FX thread too).
    private static final long PROBE_CACHE_MS = 5000;

    private final ServerProfile profile;
    private final Deque<String> history = new ArrayDeque<>();

    private Process process;
    private volatile BufferedWriter stdin;
    private volatile boolean stopping = false;

    // external (adopted) mode
    private final boolean external;
    private volatile long lastProbeAt;
    private volatile boolean lastProbeResult;

    /// Set when the server runs in its own console window rather than as a child of the
    /// launcher. Commands travel through the session's command file and output is read from
    /// the session's log, so this mode behaves like the in-process one from the page's point
    /// of view — but it survives the launcher closing.
    private ConsoleSessions.Session consoleSession;
    private ConsoleSessions consoleSessions;
    private Thread consoleTail;

    public ServerProcess(ServerProfile profile) {
        this.profile = profile;
        this.external = false;
    }

    private ServerProcess(ServerProfile profile, boolean external) {
        this.profile = profile;
        this.external = external;
    }

    /// Wraps a server that is already running in its own console window.
    ///
    /// <p>Used on launcher start-up to attach to a console the user left open. Unlike
    /// [ServerProcess#adopt] this keeps full control: commands are delivered through the
    /// session file the host polls, so the console window and the launcher can both drive the
    /// same server.</p>
    ///
    /// @param profile        the saved profile the window belongs to
    /// @param sessions       the session store that found it
    /// @param session        the live session
    /// @return a process wrapper following that session
    public static ServerProcess attach(ServerProfile profile, ConsoleSessions sessions,
                                       ConsoleSessions.Session session) {
        ServerProcess process = new ServerProcess(profile, false);
        process.consoleSessions = sessions;
        process.consoleSession = session;
        process.startConsoleTail();
        return process;
    }

    /// True when this server is driven through a separate console window.
    public boolean isConsoleWindow() {
        return consoleSession != null;
    }

    /// The console session, or null when this server is not in a separate window.
    public ConsoleSessions.Session getConsoleSession() {
        return consoleSession;
    }

    /// Follows the output a console session's host is capturing.
    ///
    /// <p>The host writes the server's output to {@code output.log}; this reads new lines as
    /// they appear so the launcher's own console shows the same thing the window does. When
    /// the host records an exit code the tail ends and the exit event fires, which is what
    /// flips the page back to “stopped”.</p>
    private void startConsoleTail() {
        Thread tail = new Thread(() -> {
            long position = 0;
            while (consoleSession != null) {
                List<String> lines = consoleSessions.outputOf(consoleSession);
                if (lines.size() > position) {
                    for (int i = (int) position; i < lines.size(); i++) {
                        String line = lines.get(i);
                        appendHistory(line);
                        ServerProcess.ServiceEvents.fireLog(this, line);
                    }
                    position = lines.size();
                }
                java.util.Optional<Integer> exit = consoleSessions.exitCodeOf(consoleSession);
                if (exit.isPresent()) {
                    appendHistory("[server-manager] 服务端已退出，退出码 " + exit.get());
                    ServerProcess.ServiceEvents.fireExit(this, exit.get());
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "server-manager-console-tail");
        tail.setDaemon(true);
        consoleTail = tail;
        tail.start();
    }

    /// Stops following a console session.
    private void stopConsoleTail() {
        Thread tail = consoleTail;
        consoleTail = null;
        consoleSession = null;
        if (tail != null)
            tail.interrupt();
    }

    /// Takes over a server from a previous session that is still listening on its port.
    public static ServerProcess adopt(ServerProfile profile) {
        ServerProcess process = new ServerProcess(profile, true);
        String note = "[server-manager] 已接管仍在后台运行的服务端（控制台为 logs/latest.log 尾跟踪，无法发送命令；停止为强制结束）";
        process.appendHistory(note);
        ServerProcess.ServiceEvents.fireLog(process, note);
        process.startTail();
        return process;
    }

    public boolean isExternal() {
        return external;
    }

    public ServerProfile getProfile() {
        return profile;
    }

    public boolean isRunning() {
        if (external)
            return probeRunning();
        if (consoleSession != null) {
            // A console-hosted server is alive while its host has not recorded an exit.
            return consoleSessions != null && consoleSessions.exitCodeOf(consoleSession).isEmpty();
        }
        return process != null && process.isAlive();
    }

    public synchronized List<String> getHistory() {
        return new ArrayList<>(history);
    }

    /// Builds the java command line for this profile.
    ///
    /// <p>Shared by both start paths so a server started in a console window runs exactly the
    /// same java as one started in-process.</p>
    ///
    /// @return the command, ready to be executed
    /// @throws IOException when the java executable cannot be resolved
    public List<String> buildCommand() throws IOException {
        String java = resolveJavaExecutable(profile.getJavaPath());

        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Xms" + profile.getMinMemory() + "M");
        command.add("-Xmx" + profile.getMaxMemory() + "M");
        command.add("-Dfile.encoding=UTF-8");
        if (profile.getJvmArgs() != null && !profile.getJvmArgs().isBlank())
            for (String arg : profile.getJvmArgs().trim().split("\\s+"))
                command.add(arg);
        command.add("-jar");
        command.add(profile.getJarFile());
        command.add("nogui");
        return command;
    }

    /// Starts the server in its own console window instead of as a child process.
    ///
    /// <p>This is what the launcher now does by default: the window outlives the launcher, and
    /// the launcher attaches to it again on the next start.</p>
    ///
    /// @param sessions  the session store to publish through
    /// @param pluginJar this plugin's jar, which contains the host class
    /// @throws IOException when the window cannot be started
    public synchronized void startInConsoleWindow(ConsoleSessions sessions, Path pluginJar)
            throws IOException {
        if (isRunning())
            throw new IllegalStateException("服务端已在运行");

        Path directory = Path.of(profile.getDirectory());
        if (!Files.isDirectory(directory))
            throw new IOException("服务端目录不存在：" + directory);

        consoleSessions = sessions;
        consoleSession = sessions.start(profile, buildCommand(), pluginJar, true);
        appendHistory("[server-manager] 已在独立窗口启动服务端；关闭启动器不会停止它");
        ServerProcess.ServiceEvents.fireLog(this,
                "[server-manager] 已在独立窗口启动服务端；关闭启动器不会停止它");
        startConsoleTail();
    }

    /// Starts the server with the profile's java path, memory and jar. Blocks until
    /// the process is spawned (not until the server finished booting).
    public synchronized void start() throws IOException {
        if (isRunning())
            throw new IllegalStateException("服务端已在运行");

        List<String> command = buildCommand();

        Path directory = Path.of(profile.getDirectory());
        if (!Files.isDirectory(directory))
            throw new IOException("服务端目录不存在：" + directory);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);

        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        Thread pump = new Thread(this::pumpOutput, "server-manager-console");
        pump.setDaemon(true);
        pump.start();

        Thread watcher = new Thread(() -> {
            try {
                int code = process.waitFor();
                synchronized (this) {
                    stdin = null;
                    stopping = false;
                }
                appendHistory("[server-manager] 服务端已退出，退出码 " + code);
                ServerProcess.ServiceEvents.fireExit(this, code);
            } catch (InterruptedException ignored) {
            }
        }, "server-manager-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    /// Sends `stop` to the server console and waits up to 30 seconds; kills it after.
    /// An adopted (external) server can only be force-killed by port.
    public synchronized void stop() throws IOException {
        if (external) {
            killExternal();
            return;
        }
        if (consoleSession != null) {
            stopConsoleHosted();
            return;
        }
        if (!isRunning())
            return;
        stopping = true;
        try {
            BufferedWriter writer = stdin;
            if (writer != null) {
                writer.write("stop");
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            // fall through to destroy
        }
        try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /// Stops a server that is running in its own console window.
    ///
    /// <p>{@code stop} is sent through the session file, which is the clean way and lets the
    /// server save its world. If it is still alive after thirty seconds the process is killed
    /// by its published pid rather than by port: the pid identifies the server the user
    /// started, while a port could by then belong to something else.</p>
    private void stopConsoleHosted() throws IOException {
        ConsoleSessions.Session session = consoleSession;
        if (session == null || consoleSessions == null)
            return;
        try {
            consoleSessions.sendCommand(session, "stop");
        } catch (IOException e) {
            // fall through to the forced kill
        }
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (consoleSessions.exitCodeOf(session).isPresent())
                return;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        ProcessHandle.of(session.pid()).ifPresent(handle -> {
            handle.destroyForcibly();
            appendHistory("[server-manager] 服务端未在 30 秒内退出，已强制结束（PID " + session.pid() + "）");
        });
    }

    /// Sends a raw command to the server console.
    public synchronized void sendCommand(String command) throws IOException {
        if (external)
            throw new IOException("外部接管的服务端无法从启动器发送控制台命令（缺少标准输入）");
        if (consoleSession != null) {
            // The host polls the command file and forwards it to the server's stdin, so the
            // launcher and the console window end up talking to the same process.
            consoleSessions.sendCommand(consoleSession, command);
            appendHistory("> " + command);
            ServerProcess.ServiceEvents.fireLog(this, "> " + command);
            return;
        }
        BufferedWriter writer = stdin;
        if (writer == null)
            throw new IOException("服务端未在运行");
        writer.write(command);
        writer.newLine();
        writer.flush();
        appendHistory("> " + command);
        ServerProcess.ServiceEvents.fireLog(this, "> " + command);
    }

    // -------------------------------------------------- external helpers ---

    /// Liveness = something is listening on the server port; results are cached for
    /// a few seconds because this is also called from the UI thread.
    private boolean probeRunning() {
        long now = System.currentTimeMillis();
        if (now - lastProbeAt < PROBE_CACHE_MS)
            return lastProbeResult;
        // An adopted server is only "running" while a Minecraft server answers a status ping;
        // a stale listener that merely accepts TCP is not our server.
        lastProbeResult = ServerManagerService.isMinecraftServer(profile.getPort());
        lastProbeAt = now;
        return lastProbeResult;
    }

    static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /// Force-kills whatever process is listening on the server port (Windows).
    private void killExternal() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!windows)
            throw new IOException("当前系统暂不支持强制停止外部接管的服务端");

        Set<String> pids = new LinkedHashSet<>();
        try {
            Process netstat = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "netstat -ano -p tcp"});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(netstat.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.contains(":" + profile.getPort() + " "))
                        continue;
                    if (!trimmed.toUpperCase().contains("LISTENING"))
                        continue;
                    String[] tokens = trimmed.split("\\s+");
                    if (tokens.length > 0)
                        pids.add(tokens[tokens.length - 1]);
                }
            }
            netstat.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (pids.isEmpty())
            throw new IOException("未找到监听端口 " + profile.getPort() + " 的进程（服务端可能已经退出）");

        for (String pid : pids)
            new ProcessBuilder("taskkill", "/F", "/T", "/PID", pid).start();
        appendHistory("[server-manager] 已强制结束外部服务端进程（PID " + String.join(", ", pids) + "）");
    }

    /// Follows `logs/latest.log` while an adopted server is alive, appending new
    /// lines to the console history; fires the exit event when the server stops.
    private void startTail() {
        Thread tail = new Thread(() -> {
            try {
                Path log = Path.of(profile.getDirectory(), "logs", "latest.log");
                long pos = Files.isRegularFile(log) ? Files.size(log) : 0;
                while (probeRunning()) {
                    if (Files.isRegularFile(log)) {
                        long size = Files.size(log);
                        if (size < pos)
                            pos = 0; // log rotated
                        if (size > pos) {
                            try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
                                file.seek(pos);
                                byte[] buffer = new byte[(int) (size - pos)];
                                file.readFully(buffer);
                                pos = size;
                                for (String line : new String(buffer, StandardCharsets.UTF_8).split("\r?\n"))
                                    if (!line.isBlank()) {
                                        appendHistory(line);
                                        ServerProcess.ServiceEvents.fireLog(this, line);
                                    }
                            }
                        }
                    }
                    Thread.sleep(1000);
                }
                appendHistory("[server-manager] 检测到服务端已退出");
                ServerProcess.ServiceEvents.fireExit(this, -1);
            } catch (InterruptedException ignored) {
            } catch (Exception ignored) {
                // missing logs directory or read hiccups must not kill the watcher
            }
        }, "server-manager-tail");
        tail.setDaemon(true);
        tail.start();
    }

    // ------------------------------------------------------------- shared ---

    private void pumpOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendHistory(line);
                ServerProcess.ServiceEvents.fireLog(this, line);
            }
        } catch (IOException ignored) {
            // stream closed when the process exits
        }
    }

    private synchronized void appendHistory(String line) {
        history.addLast(line);
        while (history.size() > HISTORY_LIMIT)
            history.removeFirst();
    }

    /// Resolves a configured java home / executable to the java launcher binary;
    /// an empty value falls back to the Java the launcher itself runs on.
    private static String resolveJavaExecutable(String configured) throws IOException {
        String candidate = configured == null || configured.isBlank()
                ? System.getProperty("java.home")
                : configured.trim();
        Path path = Path.of(candidate);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String executable = windows ? "java.exe" : "java";
        if (Files.isDirectory(path)) {
            Path resolved = path.resolve("bin").resolve(executable);
            if (!Files.isRegularFile(resolved))
                throw new IOException("Java 目录里找不到 " + resolved);
            return resolved.toString();
        }
        if (!Files.isRegularFile(path))
            throw new IOException("找不到 Java 可执行文件：" + candidate);
        return path.toString();
    }

    /// Static event bus decoupling the service from JavaFX collections so the
    /// manager also works headlessly (tests).
    static final class ServiceEvents {
        interface LogListener {
            void onLog(ServerProcess process, String line);
        }

        interface ExitListener {
            void onExit(ServerProcess process, int exitCode);
        }

        private static final List<LogListener> logListeners = new ArrayList<>();
        private static final List<ExitListener> exitListeners = new ArrayList<>();

        private ServiceEvents() {
        }

        static synchronized void addLogListener(LogListener listener) {
            logListeners.add(listener);
        }

        /// Unregisters a listener.
        ///
        /// <p>Needed because the player page registers one to refresh itself: without this a
        /// closed page would keep being notified and keep rebuilding a UI nobody can see.</p>
        static synchronized void removeLogListener(LogListener listener) {
            logListeners.remove(listener);
        }

        static synchronized void removeExitListener(ExitListener listener) {
            exitListeners.remove(listener);
        }

        static synchronized void addExitListener(ExitListener listener) {
            exitListeners.add(listener);
        }

        static void fireLog(ServerProcess process, String line) {
            for (LogListener listener : snapshot(logListeners))
                listener.onLog(process, line);
        }

        static void fireExit(ServerProcess process, int exitCode) {
            for (ExitListener listener : snapshot(exitListeners))
                listener.onExit(process, exitCode);
        }

        private static <T> List<T> snapshot(List<T> listeners) {
            synchronized (ServiceEvents.class) {
                return new ArrayList<>(listeners);
            }
        }
    }
}

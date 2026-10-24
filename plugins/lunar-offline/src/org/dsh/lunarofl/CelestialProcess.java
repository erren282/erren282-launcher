package org.dsh.lunarofl;

import org.jackhuang.hmcl.util.logging.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/// 管理 Celestial 进程：以 jar 所在目录为工作目录启动，捕获输出并转成页面控制台里的行。
///
/// <p>状态保存在单例里，因此插件页面每次重建（启动器现在每次进入都重建页面）都能立刻
/// 恢复“运行中/已退出”与历史输出。</p>
public final class CelestialProcess {

    private static final int MAX_HISTORY = 2000;

    /// 全局单例：进程状态与页面生命周期无关。
    private static final CelestialProcess INSTANCE = new CelestialProcess();

    public static CelestialProcess get() {
        return INSTANCE;
    }

    private final Deque<String> history = new ArrayDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();

    private Process process;
    private Path logFile;
    private long startedAt;

    private CelestialProcess() {
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized long startedAt() {
        return startedAt;
    }

    public Path logFile() {
        return logFile;
    }

    /// 当前进程 PID；未运行时为 -1。
    public synchronized long pid() {
        return process == null || !process.isAlive() ? -1 : process.pid();
    }

    /// 历史输出（页面重建时回填控制台）。
    public synchronized List<String> history() {
        return new ArrayList<>(history);
    }

    public void addLogListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeLogListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    public void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(Runnable listener) {
        stateListeners.remove(listener);
    }

    /// 启动 Celestial。`javaPath` 与 `jar` 由调用方校验过存在性。
    public synchronized void start(String javaPath, Path jar, Path dataDirectory, String jvmArgs) throws IOException {
        if (isRunning())
            throw new IllegalStateException("Celestial 已在运行");

        List<String> command = new ArrayList<>();
        command.add(javaPath);
        command.add("-Dfile.encoding=UTF-8");
        if (jvmArgs != null && !jvmArgs.isBlank())
            for (String arg : jvmArgs.trim().split("\\s+"))
                command.add(arg);
        command.add("-jar");
        command.add(jar.getFileName().toString());

        Path workingDirectory = jar.getParent() == null ? Path.of(".") : jar.getParent();
        Files.createDirectories(dataDirectory);
        logFile = dataDirectory.resolve("celestial.log");
        Files.writeString(logFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        process = builder.start();
        startedAt = System.currentTimeMillis();

        append("[lunar-offline] 已启动：" + String.join(" ", command));
        append("[lunar-offline] 工作目录：" + workingDirectory);

        Thread pump = new Thread(() -> pumpOutput(process), "lunar-offline-console");
        pump.setDaemon(true);
        pump.start();

        Thread watcher = new Thread(() -> {
            Process current = process;
            try {
                int code = current.waitFor();
                append("[lunar-offline] Celestial 已退出，退出码 " + code);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                fireStateChanged();
            }
        }, "lunar-offline-watcher");
        watcher.setDaemon(true);
        watcher.start();

        // 手动输入通道：Celestial 偶尔会等待控制台回车
        try {
            BufferedWriter stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdin.write("");
            stdin.flush();
        } catch (IOException ignored) {
            // 不需要交互时忽略
        }

        fireStateChanged();
    }

    /// 停止：先 destroy，5 秒未退出则强制结束整个进程树。
    public synchronized void stop() {
        Process current = process;
        if (current == null || !current.isAlive())
            return;
        append("[lunar-offline] 正在停止 Celestial…");
        current.destroy();
        try {
            if (!current.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                current.destroyForcibly();
                String pid = String.valueOf(current.pid());
                try {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", pid).start().waitFor();
                } catch (Exception e) {
                    Logger.LOG.warning("Failed to taskkill celestial " + pid, e);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        fireStateChanged();
    }

    /// 关闭钩子：启动器退出时一并结束 Celestial，避免留下后台进程。
    public synchronized void stopOnShutdown() {
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroy();
            try {
                if (!current.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                    current.destroyForcibly();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void pumpOutput(Process source) {
        try (BufferedReader reader = JavaLocator.reader(source.getInputStream())) {
            String line;
            while ((line = reader.readLine()) != null) {
                append(line);
                if (logFile != null) {
                    try {
                        Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException ignored) {
                        // 日志文件写不进去不影响使用
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程结束时流被关闭是正常的
        }
    }

    private synchronized void append(String line) {
        history.addLast(line);
        while (history.size() > MAX_HISTORY)
            history.removeFirst();
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(line);
            } catch (Throwable ignored) {
                // 监听器异常不影响进程
            }
        }
    }

    private void fireStateChanged() {
        for (Runnable listener : stateListeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }
}

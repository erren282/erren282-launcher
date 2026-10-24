package org.dsh.servermanager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Orchestrates the managed servers: profiles, running processes and broadcast bridges.
///
/// <p>All methods are safe to call from background threads; UI listeners receive events
/// on the emitting thread and must marshal themselves onto the JavaFX thread.</p>
public final class ServerManagerService {

    /// Observes service state; implemented by the management page.
    public interface Listener {
        default void onProfilesChanged() {}

        default void onLog(ServerProfile profile, String line) {}

        default void onStateChanged(ServerProfile profile) {}
    }

    private static ServerManagerService instance;

    /// Returns the service singleton, creating it with the given data directory on
    /// first use (called by the plugin on enable).
    public static synchronized ServerManagerService of(Path dataDirectory) {
        if (instance == null)
            instance = new ServerManagerService(dataDirectory);
        return instance;
    }

    /// What to do with the server core when importing an existing directory.
    public enum CoreRepair {
        /// keep everything as-is (missing jar is an error)
        NONE,
        /// download / overwrite a vanilla core
        VANILLA,
        /// download / overwrite a Paper core
        PAPER
    }

    private final Path serversRoot;
    private final ProfileStore store;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, ServerProcess> processes = new ConcurrentHashMap<>();
    private final Map<String, ServerBridge> bridges = new ConcurrentHashMap<>();

    /// The console windows this plugin has started or attached to.
    private final ConsoleSessions consoleSessions;

    private ServerManagerService(Path dataDirectory) {
        this.serversRoot = dataDirectory.resolve("servers");
        this.store = new ProfileStore(dataDirectory);
        this.consoleSessions = new ConsoleSessions(dataDirectory);
        ServerProcess.ServiceEvents.addLogListener((process, line) ->
                fireLog(process.getProfile(), line));
        ServerProcess.ServiceEvents.addExitListener((process, exitCode) -> {
            String id = process.getProfile().getId();
            ServerBridge bridge = bridges.remove(id);
            if (bridge != null)
                bridge.stop();
            fireStateChanged(process.getProfile());
        });
    }

    /// The console-window store, for pages that need to talk to one.
    public ConsoleSessions getConsoleSessions() {
        return consoleSessions;
    }

    /// Attaches to console windows left running by a previous launcher session.
    ///
    /// <p>A window is only attached when its directory and port still match a saved profile:
    /// a console whose server was moved or re-configured is ignored, so the launcher can never
    /// end up managing a different server than the one the user saved.</p>
    ///
    /// @return the profiles that were attached
    public List<ServerProfile> attachConsoleWindows() {
        List<ServerProfile> attached = new ArrayList<>();
        for (ServerProfile profile : store.getProfiles()) {
            if (processes.containsKey(profile.getId()))
                continue;
            java.util.Optional<ConsoleSessions.Session> session =
                    consoleSessions.liveSessionOf(profile);
            if (session.isEmpty())
                continue;

            ServerProcess process = ServerProcess.attach(profile, consoleSessions, session.get());
            processes.put(profile.getId(), process);
            fireLog(profile, "[server-manager] 已接入该服务端的控制台窗口；"
                    + "关闭启动器后仍可在窗口中管理");
            restoreBroadcast(profile);
            fireStateChanged(profile);
            attached.add(profile);
        }
        return attached;
    }

    /// Restarts the LAN broadcast for an attached server, if the profile asks for one.
    private void restoreBroadcast(ServerProfile profile) {
        if (!profile.isBroadcast() || bridges.containsKey(profile.getId()))
            return;
        try {
            ServerBridge bridge = new ServerBridge(profile.getPort(), profile.getMotd());
            bridge.start();
            bridges.put(profile.getId(), bridge);
        } catch (IOException e) {
            fireLog(profile, "[server-manager] 广播恢复失败：" + e.getMessage());
        }
    }

    public List<ServerProfile> getProfiles() {
        return store.getProfiles();
    }

    /// Persists edits made to a profile in the management page.
    public void updateProfile(ServerProfile profile) throws IOException {
        store.update(profile);
        fireProfilesChanged();
    }

    public Path getServersRoot() {
        return serversRoot;
    }

    public Path getDataDirectory() {
        return serversRoot.getParent();
    }

    public synchronized void addListener(Listener listener) {
        listeners.add(listener);
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning(ServerProfile profile) {
        ServerProcess process = processes.get(profile.getId());
        return process != null && process.isRunning();
    }

    /// Returns the broadcast proxy port while the server is running and broadcasting, else -1.
    public int getProxyPort(ServerProfile profile) {
        ServerBridge bridge = bridges.get(profile.getId());
        return bridge != null && bridge.isRunning() ? bridge.getProxyPort() : -1;
    }

    public ServerProcess getProcess(ServerProfile profile) {
        return processes.get(profile.getId());
    }

    /// Detects servers from previous launcher sessions that are still running in
    /// the background and adopts them: marks them running, restores the broadcast
    /// bridge and follows their logs. Safe to call more than once.
    ///
    /// <p>Console windows are attached first, because an attached window gives full control
    /// (commands can be sent) whereas adoption only tails the log. A server that is running
    /// without such a window — started by an older version of this plugin, or launched by
    /// hand — still gets adopted, so an existing setup keeps working.</p>
    public void adoptRunningServers() {
        new Thread(() -> {
            for (ServerProfile attached : attachConsoleWindows()) {
                fireLog(attached, "[server-manager] 已重新接入控制台窗口");
            }
            for (ServerProfile profile : store.getProfiles()) {
                if (processes.containsKey(profile.getId()))
                    continue;
                // Only a real Minecraft server is adopted. A leftover listener (for example an
                // old broadcast proxy) would otherwise be taken over as a running server, which
                // blocks the start button and creates rooms nobody can join.
                if (!isMinecraftServer(profile.getPort()))
                    continue;

                ServerProcess process = ServerProcess.adopt(profile);
                processes.put(profile.getId(), process);
                if (profile.isBroadcast()) {
                    try {
                        ServerBridge bridge = new ServerBridge(profile.getPort(), profile.getMotd());
                        bridge.start();
                        bridges.put(profile.getId(), bridge);
                    } catch (IOException e) {
                        fireLog(profile, "[server-manager] 广播恢复失败：" + e.getMessage());
                    }
                }
                fireLog(profile, "[server-manager] 检测到服务端仍在后台运行，已接管并恢复广播");
                fireStateChanged(profile);
            }
        }, "server-manager-adopt").start();
    }

    // -------------------------------------------------------- lifecycle ---

    /// One-click start: writes the EULA answer, patches server.properties, spawns the
    /// server and starts the broadcast bridge.
    public void start(ServerProfile profile) throws IOException {
        if (isRunning(profile))
            throw new IllegalStateException("服务端已在运行");
        if (!profile.isEulaAccepted())
            throw new IOException("请先在设置中勾选“同意 Minecraft EULA”");
        if (!Files.isRegularFile(profile.getJarPath()))
            throw new IOException("找不到服务端 jar：" + profile.getJarPath());
        if (isPortListening(profile.getPort()))
            throw new IOException("端口 " + profile.getPort() + " 已被其他程序占用"
                    + "（常见原因：之前开设的服务端还在运行）。请更换端口，或结束占用该端口的进程后重试。");

        writeEula(profile);
        patchServerProperties(profile);

        ServerProcess process = new ServerProcess(profile);
        processes.put(profile.getId(), process);
        try {
            startServer(profile, process);
        } catch (IOException e) {
            processes.remove(profile.getId());
            throw e;
        }
        fireStateChanged(profile);

        if (profile.isBroadcast()) {
            try {
                ServerBridge bridge = new ServerBridge(profile.getPort(), profile.getMotd());
                bridge.start();
                bridges.put(profile.getId(), bridge);
            } catch (IOException e) {
                // the server itself still runs; surface the broadcast failure in the console
                fireLog(profile, "[server-manager] 广播启动失败：" + e.getMessage());
            }
        } else {
            fireLog(profile, "[server-manager] 已按设置停用局域网广播");
        }
    }

    public void stop(ServerProfile profile) throws IOException {
        ServerProcess process = processes.get(profile.getId());
        if (process != null && process.isRunning())
            process.stop();
    }

    /// Starts a server, preferring its own console window.
    ///
    /// <p>The window is the default because it keeps working after the launcher closes, which
    /// is what makes the server manageable from outside the launcher. When the plugin is not
    /// running from a jar — during development, or if the launcher was started in a way that
    /// hides its code source — there is nothing to point the host at, so the server is started
    /// in-process instead and the console stays inside the launcher. That fallback is logged,
    /// because the difference matters to the user.</p>
    ///
    /// @param profile the server to start
    /// @param process the wrapper to start it through
    /// @throws IOException when the server cannot be started at all
    private void startServer(ServerProfile profile, ServerProcess process) throws IOException {
        Path jar = ConsoleSessions.ownJar();
        if (jar != null) {
            try {
                process.startInConsoleWindow(consoleSessions, jar);
                return;
            } catch (IOException e) {
                // Starting the window failed; the in-process path below still works, so the
                // user gets a running server rather than an error about a window.
                fireLog(profile, "[server-manager] 独立控制台窗口启动失败（" + e.getMessage()
                        + "），改为在启动器内启动");
            }
        } else {
            fireLog(profile, "[server-manager] 插件未从 jar 加载，无法启动独立控制台窗口；"
                    + "服务端将在启动器内运行，关闭启动器会停止它");
        }
        process.start();
    }

    public void sendCommand(ServerProfile profile, String command) throws IOException {
        ServerProcess process = processes.get(profile.getId());
        if (process == null || !process.isRunning())
            throw new IOException("服务端未在运行");
        process.sendCommand(command);
    }

    /// Deletes a profile; refuses while its server is running. Instance directories
    /// created by the plugin are removed, external ones are left untouched.
    public void delete(ServerProfile profile) throws IOException {
        if (isRunning(profile))
            throw new IOException("请先停止服务端再删除");

        ServerProcess dead = processes.remove(profile.getId());
        ServerBridge bridge = bridges.remove(profile.getId());
        if (bridge != null)
            bridge.stop();
        if (dead != null && dead.isRunning())
            dead.stop();

        Path directory = Path.of(profile.getDirectory());
        Path externalRoot = serversRoot.toAbsolutePath().normalize();
        if (directory.toAbsolutePath().normalize().startsWith(externalRoot)) {
            deleteRecursively(directory);
        }

        store.remove(profile);
        fireProfilesChanged();
    }

    // ---------------------------------------------------------- create ---\

    /// Creates a vanilla instance and downloads its server jar.
    public ServerProfile createVanilla(String name, String versionId, String versionUrl,
                                       int port, String motd, int minMemory, int maxMemory,
                                       String javaPath, boolean eula, boolean broadcast,
                                       ServerVersions.Progress progress) throws IOException {
        ServerProfile profile = newProfile(name, ServerProfile.Type.VANILLA, versionId,
                port, motd, minMemory, maxMemory, javaPath, eula, broadcast);
        try {
            ServerVersions.downloadVanillaServer(versionId, versionUrl, profile.getJarPath(), progress);
        } catch (IOException e) {
            cleanupFailedCreate(profile);
            throw e;
        }
        return finishCreate(profile);
    }

    /// Creates a Paper instance and downloads its latest build.
    public ServerProfile createPaper(String name, String versionId,
                                     int port, String motd, int minMemory, int maxMemory,
                                     String javaPath, boolean eula, boolean broadcast,
                                     ServerVersions.Progress progress) throws IOException {
        ServerProfile profile = newProfile(name, ServerProfile.Type.PAPER, versionId,
                port, motd, minMemory, maxMemory, javaPath, eula, broadcast);
        try {
            ServerVersions.downloadPaperServer(versionId, profile.getJarPath(), progress);
        } catch (IOException e) {
            cleanupFailedCreate(profile);
            throw e;
        }
        return finishCreate(profile);
    }

    /// Registers an existing server directory; the jar must already be present.
    /// Registers an existing server directory. With [CoreRepair#VANILLA] or
    /// [CoreRepair#PAPER] the server core is downloaded into the directory (fresh
    /// install when missing, overwrite-repair when present); `mcVersion` is
    /// required for a download.
    public ServerProfile importLocal(String name, Path directory, String jarFile,
                                     String mcVersion, int port, String motd,
                                     int minMemory, int maxMemory, String javaPath,
                                     boolean eula, boolean broadcast,
                                     CoreRepair coreRepair,
                                     ServerVersions.Progress progress) throws IOException {
        boolean hasJar = Files.isRegularFile(directory.resolve(jarFile));
        if (!hasJar && coreRepair == CoreRepair.NONE)
            throw new IOException("目录里找不到 " + jarFile
                    + "；可选择“核心修复/安装”自动下载，或选择正确的启动 jar");
        if (coreRepair != CoreRepair.NONE && (mcVersion == null || mcVersion.isBlank()))
            throw new IOException("自动下载核心需要先填写游戏版本号");

        ServerProfile profile = newProfile(name, ServerProfile.Type.CUSTOM, mcVersion,
                port, motd, minMemory, maxMemory, javaPath, eula, broadcast);
        profile.setDirectory(directory.toAbsolutePath().toString());
        profile.setJarFile(jarFile);

        // The directory's own start script knows how much memory the pack needs; the launcher's
        // generic default can leave a plugin-heavy server short while it loads its worlds.
        int[] scriptMemory = scriptMemory(directory);
        if (scriptMemory != null) {
            profile.setMinMemory(scriptMemory[0]);
            profile.setMaxMemory(scriptMemory[1]);
        }

        if (coreRepair == CoreRepair.PAPER) {
            ServerVersions.downloadPaperServer(mcVersion, profile.getJarPath(), progress);
        } else if (coreRepair == CoreRepair.VANILLA) {
            ServerVersions.VersionEntry entry = ServerVersions.resolveVanilla(mcVersion);
            ServerVersions.downloadVanillaServer(mcVersion, entry.getUrl(), profile.getJarPath(), progress);
        }

        return finishCreate(profile);
    }

    private ServerProfile newProfile(String name, ServerProfile.Type type, String mcVersion,
                                     int port, String motd, int minMemory, int maxMemory,
                                     String javaPath, boolean eula, boolean broadcast) throws IOException {
        ServerProfile profile = new ServerProfile();
        profile.setId(java.util.UUID.randomUUID().toString());
        profile.setName(name);
        profile.setType(type);
        profile.setMcVersion(mcVersion);
        profile.setPort(port);
        profile.setMotd(motd);
        profile.setMinMemory(minMemory);
        profile.setMaxMemory(maxMemory);
        profile.setJavaPath(javaPath == null ? "" : javaPath);
        profile.setEulaAccepted(eula);
        profile.setBroadcast(broadcast);
        // allocate the instance directory up front so downloads have a target
        if (type != ServerProfile.Type.CUSTOM) {
            Path directory = serversRoot.resolve(profile.getId());
            Files.createDirectories(directory);
            profile.setDirectory(directory.toAbsolutePath().toString());
        }
        return profile;
    }

    /// Removes the instance directory when the download failed before registration.
    private void cleanupFailedCreate(ServerProfile profile) {
        if (profile.getType() == ServerProfile.Type.CUSTOM)
            return;
        try {
            deleteRecursively(Path.of(profile.getDirectory()));
        } catch (IOException ignored) {
        }
    }

    private ServerProfile finishCreate(ServerProfile profile) throws IOException {
        if (profile.getType() == ServerProfile.Type.CUSTOM) {
            // external directories are recorded as-is; nothing to allocate
        }
        store.add(profile);
        fireProfilesChanged();
        return profile;
    }

    // ------------------------------------------------------- properties ---

    /// TCP probe used to fail fast when the port is already taken.
    /// True when *any* program accepts a TCP connection here — used before starting, because
    /// the bind would fail regardless of who holds the port.
    private static boolean isPortListening(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /// True only when a Minecraft server answers a status ping on this port. A plain TCP
    /// probe is not enough: a leftover broadcast/keep-alive proxy (the bridge answers legacy
    /// 0xFE pings) or any unrelated listener looks "running", so the launcher would refuse to
    /// start the server, or create a room backed by nothing that guests can never join.
    public static boolean isMinecraftServer(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 1000);
            socket.setSoTimeout(2000);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = socket.getInputStream();

            java.io.ByteArrayOutputStream handshake = new java.io.ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);          // packet id: handshake
            writeVarInt(handshake, -1);            // protocol: -1 = status only
            writeString(handshake, "127.0.0.1");
            handshake.write((port >> 8) & 0xFF);   // port, big endian
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);             // next state: status
            writeVarInt(out, handshake.size());
            out.write(handshake.toByteArray());
            writeVarInt(out, 1);                   // status request length
            writeVarInt(out, 0x00);
            out.flush();

            int length = readVarInt(in);
            if (length <= 0 || length > (1 << 21))
                return false;
            if (readVarInt(in) != 0x00)
                return false;                      // not a status response
            return readString(in, length).contains("\"version\"");
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeVarInt(java.io.OutputStream out, int value) throws IOException {
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0)
                current |= 0x80;
            out.write(current);
        } while (remaining != 0);
    }

    private static int readVarInt(java.io.InputStream in) throws IOException {
        int result = 0, position = 0;
        while (true) {
            int current = in.read();
            if (current < 0)
                throw new IOException("连接已关闭");
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0)
                return result;
            position += 7;
            if (position > 35)
                throw new IOException("VarInt 过长");
        }
    }

    private static void writeString(java.io.OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(java.io.InputStream in, int packetLength) throws IOException {
        int stringLength = readVarInt(in);
        if (stringLength < 0 || stringLength > packetLength)
            throw new IOException("字符串长度非法");
        byte[] bytes = in.readNBytes(stringLength);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /// Reads `-Xms`/`-Xmx` from the directory's own start script (start.bat, run.bat, start.sh…),
    /// so an imported server keeps the memory it was designed for. Returns {min, max} in MB, or
    /// null when no script carries a heap setting.
    public static int[] scriptMemory(Path directory) {
        if (!Files.isDirectory(directory))
            return null;
        try (var files = Files.list(directory)) {
            for (Path script : files.toList()) {
                String fileName = script.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!(fileName.endsWith(".bat") || fileName.endsWith(".cmd")
                        || fileName.endsWith(".sh") || fileName.endsWith(".txt")))
                    continue;
                String text;
                try {
                    text = Files.readString(script, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    try {
                        text = Files.readString(script, java.nio.charset.Charset.forName("GB18030"));
                    } catch (IOException ignored) {
                        continue;
                    }
                }
                Integer min = heapValue(text, "-Xms");
                Integer max = heapValue(text, "-Xmx");
                if (max == null)
                    continue;
                int resolvedMin = min == null || min > max ? max : min;
                return new int[]{resolvedMin, max};
            }
        } catch (IOException ignored) {
            // unreadable directory: keep the caller's defaults
        }
        return null;
    }

    private static Integer heapValue(String text, String flag) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(flag) + "(\\d+)([kKmMgG]?)")
                .matcher(text);
        if (!matcher.find())
            return null;
        long value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "g" -> (int) (value * 1024);
            case "k" -> (int) Math.max(1, value / 1024);
            default -> (int) value;
        };
    }

    private void writeEula(ServerProfile profile) throws IOException {
        Path eula = Path.of(profile.getDirectory(), "eula.txt");
        if (!Files.exists(eula))
            Files.writeString(eula, "# Accepted through the HMCL server manager\neula=true\n",
                    StandardCharsets.UTF_8);
    }

    /// Creates or patches `server.properties` so `server-port` and `motd` match the profile.
    private void patchServerProperties(ServerProfile profile) throws IOException {
        Path file = Path.of(profile.getDirectory(), "server.properties");
        List<String> lines = new ArrayList<>();
        if (Files.exists(file))
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        setProperty(lines, "server-port", String.valueOf(profile.getPort()));
        setProperty(lines, "motd", profile.getMotd());
        setProperty(lines, "online-mode", String.valueOf(profile.isOnlineMode()));
        setProperty(lines, "max-players", String.valueOf(profile.getMaxPlayers()));
        setProperty(lines, "difficulty", profile.getDifficulty());
        setProperty(lines, "gamemode", profile.getGamemode());
        setProperty(lines, "view-distance", String.valueOf(profile.getViewDistance()));
        setProperty(lines, "simulation-distance", String.valueOf(profile.getSimulationDistance()));
        setProperty(lines, "pvp", String.valueOf(profile.isPvp()));
        setProperty(lines, "allow-flight", String.valueOf(profile.isAllowFlight()));
        setProperty(lines, "white-list", String.valueOf(profile.isWhitelist()));
        setProperty(lines, "enable-command-block", String.valueOf(profile.isCommandBlock()));
        if (profile.getLevelSeed() != null && !profile.getLevelSeed().isBlank())
            setProperty(lines, "level-seed", profile.getLevelSeed().trim());

        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /// Writes `white-list` into the server's `server.properties` immediately.
    ///
    /// <p>Normally the flag is written when the server starts, but the whitelist switch must take
    /// effect right away: a stopped server reads the file on start, and the management page reads
    /// it to show the current state, so deferring the write made the switch look broken.</p>
    public static void writeWhitelistFlag(ServerProfile profile, boolean enabled) throws IOException {
        Path file = Path.of(profile.getDirectory(), "server.properties");
        List<String> lines = new ArrayList<>();
        if (Files.exists(file))
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        setProperty(lines, "white-list", String.valueOf(enabled));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static void setProperty(List<String> lines, String key, String value) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("#") && trimmed.startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                return;
            }        }
        lines.add(key + "=" + value);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path))
            return;
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    // ---------------------------------------------------------- events ---

    private void fireProfilesChanged() {
        for (Listener listener : snapshotListeners())
            listener.onProfilesChanged();
    }

    private void fireLog(ServerProfile profile, String line) {
        for (Listener listener : snapshotListeners())
            listener.onLog(profile, line);
    }

    private void fireStateChanged(ServerProfile profile) {
        for (Listener listener : snapshotListeners())
            listener.onStateChanged(profile);
    }

    private List<Listener> snapshotListeners() {
        synchronized (this) {
            return new ArrayList<>(listeners);
        }
    }
}

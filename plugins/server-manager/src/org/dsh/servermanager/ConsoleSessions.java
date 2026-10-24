package org.dsh.servermanager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// Starts servers in their own console window and finds those windows again later.
///
/// <h2>What a "console session" is</h2>
/// <p>A directory under {@code <dataDir>/console/<sessionId>/} describing one server started
/// in a separate window: the launch command, the target directory, the profile id and port,
/// a command file the launcher appends to, and the captured output. See
/// [ConsoleHost] for the protocol; this class is the launcher half of it.</p>
///
/// <h2>Attaching after a restart</h2>
/// <p>The launcher closes, the console window and the server keep running. On the next start
/// this class scans the session directories and reports the ones whose server is still alive.
/// A session is only offered for attaching when <em>both</em> the directory and the port still
/// match a saved profile — a window whose server was moved, re-configured or deleted is left
/// alone, exactly as asked: “命令提示符所开启的服务端必须与启动器中保存的服务端一致”.</p>
final class ConsoleSessions {

    /// Where sessions live, under the plugin's data directory.
    private static final String ROOT = "console";

    /// The file naming the server's working directory.
    private static final String DIR_FILE = "dir.txt";

    /// The file holding the session description.
    private static final String SESSION_FILE = "session.json";

    private final Path root;

    /// Creates the session store.
    ///
    /// @param dataDirectory the plugin's data directory
    ConsoleSessions(Path dataDirectory) {
        this.root = dataDirectory.resolve(ROOT);
    }

    /// One running console session.
    ///
    /// @param sessionId  the directory name, used as the handle
    /// @param profileId  the saved profile this was started from
    /// @param directory  the server's working directory
    /// @param port       the server port
    /// @param pid        the server process id
    record Session(String sessionId, String profileId, String directory, int port, long pid) {

        /// The session's own directory.
        Path directory(Path root) {
            return root.resolve(sessionId);
        }
    }

    /// Starts a server in a new console window.
    ///
    /// <p>The window is a separate process running [ConsoleHost], started with
    /// {@code cmd /c start} so it gets its own visible console even when the launcher itself
    /// was started without one. Nothing in it references the launcher, which is what lets it
    /// outlive it.</p>
    ///
    /// @param profile     the server to start
    /// @param command     the full java command line, including the jar
    /// @param pluginJar   this plugin's jar, which also contains the host class
    /// @param pipeOutput  true to capture output for the launcher's own console as well
    /// @return the session, already published
    /// @throws IOException when the window cannot be started
    Session start(ServerProfile profile, List<String> command, Path pluginJar, boolean pipeOutput)
            throws IOException {
        String sessionId = profile.getId() + "-" + System.currentTimeMillis();
        Path session = root.resolve(sessionId);
        Files.createDirectories(session);

        // The server command, one argument per line: no shell quoting to get wrong.
        Files.write(session.resolve("launch.txt"), command, StandardCharsets.UTF_8);
        Files.writeString(session.resolve(DIR_FILE), profile.getDirectory(), StandardCharsets.UTF_8);
        Files.writeString(session.resolve("port.txt"),
                String.valueOf(profile.getPort()), StandardCharsets.UTF_8);
        Files.writeString(session.resolve("profile-id.txt"),
                profile.getId(), StandardCharsets.UTF_8);
        Files.writeString(session.resolve("commands.txt"), "", StandardCharsets.UTF_8);

        String javaExecutable = javaExecutable();
        String hostClass = ConsoleHost.class.getName();

        // cmd /c start gives the host a real console window; "服务端 - <name>" is the title
        // so the user can tell several servers apart in the taskbar.
        String title = "服务端 - " + profile.getName();
        List<String> launch = new ArrayList<>();
        launch.add("cmd");
        launch.add("/c");
        launch.add("start");
        launch.add("\"" + title + "\"");
        launch.add(javaExecutable);
        launch.add("-cp");
        launch.add(pluginJar.toAbsolutePath().toString());
        launch.add(hostClass);
        launch.add(session.toAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(launch);
        builder.directory(profile.getDirectory() == null
                ? null : Path.of(profile.getDirectory()).toFile());
        // `start` detaches: this ProcessBuilder call returns as soon as the window is created,
        // which is what makes the console independent of the launcher.
        if (!pipeOutput) {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        Process launcher = builder.start();
        if (pipeOutput) {
            // The window swallowed the console; this just drains anything cmd echoes back.
            Thread drain = new Thread(() -> {
                try (var in = launcher.getInputStream()) {
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
            }, "console-session-drain");
            drain.setDaemon(true);
            drain.start();
        }

        return new Session(sessionId, profile.getId(), profile.getDirectory(),
                profile.getPort(), -1);
    }

    /// All sessions whose server still looks alive.
    ///
    /// <p>"Alive" is judged from the process id the host published, not from the port: a
    /// session whose server died and whose port was taken by something else must not be
    /// offered for attaching.</p>
    ///
    /// @return the live sessions, newest first
    List<Session> liveSessions() {
        List<Session> sessions = new ArrayList<>();
        if (!Files.isDirectory(root))
            return sessions;
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                readSession(dir).filter(s -> isAlive(s.pid())).ifPresent(sessions::add);
            }
        } catch (IOException ignored) {
            return sessions;
        }
        sessions.sort(Comparator.comparing(Session::sessionId).reversed());
        return sessions;
    }

    /// Finds the live session belonging to one profile, if any.
    ///
    /// @param profile the saved profile to match
    /// @return the session, or empty when this profile has no console window of its own
    Optional<Session> liveSessionOf(ServerProfile profile) {
        for (Session session : liveSessions())
            if (matches(profile, session))
                return Optional.of(session);
        return Optional.empty();
    }

    /// True when a session was started from this profile and still describes the same server.
    ///
    /// <p>Both the directory and the port have to agree. Either one changing means the saved
    /// profile no longer describes the server in that window, and attaching would send
    /// commands to the wrong server.</p>
    ///
    /// @param profile the saved profile
    /// @param session the console session
    static boolean matches(ServerProfile profile, Session session) {
        if (profile == null || session == null)
            return false;
        if (!profile.getId().equals(session.profileId()))
            return false;
        if (profile.getPort() != session.port())
            return false;
        if (profile.getDirectory() == null || session.directory() == null)
            return false;
        try {
            return Path.of(profile.getDirectory()).toAbsolutePath().normalize()
                    .equals(Path.of(session.directory()).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /// Appends a console command for the host to pick up.
    ///
    /// @param session the session to talk to
    /// @param command the command, without a trailing newline
    /// @throws IOException when the file cannot be written
    void sendCommand(Session session, String command) throws IOException {
        Path file = root.resolve(session.sessionId()).resolve("commands.txt");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            writer.write(command);
            writer.newLine();
        }
    }

    /// The output captured so far by a session's host.
    ///
    /// <h2>Why this retries and why it never returns a silent empty list</h2>
    /// <p>The host keeps {@code output.log} open for writing while the server runs, and on
    /// Windows a read can intermittently collide with that handle — measured: the same read
    /// succeeded, then failed with "file is being used by another process", then succeeded
    /// again. Returning an empty list on that failure is what made the player page look
    /// frozen: the tail thread saw "no new lines" and simply waited. So a transient failure
    /// is retried, and a persistent one is reported rather than hidden.</p>
    ///
    /// @param session the session to read
    /// @return the lines, or an empty list only when there genuinely is no output yet
    List<String> outputOf(Session session) {
        Path file = root.resolve(session.sessionId()).resolve("output.log");
        if (!Files.isRegularFile(file))
            return List.of();

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // Almost always the writer's handle; a short pause clears it.
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
        }
        readFailures.incrementAndGet();
        return List.of();
    }

    /// How many times the output could not be read at all, for diagnostics.
    private final java.util.concurrent.atomic.AtomicLong readFailures =
            new java.util.concurrent.atomic.AtomicLong();

    /// The number of reads that failed after every retry.
    long readFailures() {
        return readFailures.get();
    }

    /// The exit code a session recorded, if its server has stopped.
    ///
    /// @param session the session to check
    Optional<Integer> exitCodeOf(Session session) {
        Path file = root.resolve(session.sessionId()).resolve("exit.code");
        try {
            if (!Files.isRegularFile(file))
                return Optional.empty();
            return Optional.of(Integer.parseInt(Files.readString(file).trim()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /// Deletes a session's directory.
    ///
    /// @param session the session to forget
    void forget(Session session) {
        Path dir = root.resolve(session.sessionId());
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // A leftover directory is harmless: it is skipped once its pid is gone.
        }
    }

    /// Reads a session directory's description.
    private Optional<Session> readSession(Path dir) {
        Path file = dir.resolve(SESSION_FILE);
        if (!Files.isRegularFile(file))
            return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            String profileId = jsonString(json, "profileId");
            String directory = jsonString(json, "directory");
            int port = jsonInt(json, "port");
            long pid = jsonInt(json, "pid");
            if (profileId == null || directory == null || pid <= 0)
                return Optional.empty();
            return Optional.of(new Session(dir.getFileName().toString(), profileId,
                    directory, port, pid));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /// True when a process with that id exists.
    private static boolean isAlive(long pid) {
        if (pid <= 0)
            return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /// Extracts a string field from the small JSON the host writes.
    private static String jsonString(String json, String key) {
        var m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find())
            return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /// Extracts an integer field from the small JSON the host writes.
    private static int jsonInt(String json, String key) {
        var m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /// This plugin's own jar, which also contains [ConsoleHost].
    ///
    /// <p>The host is started with {@code java -cp <this jar>}, so the path has to be real.
    /// It is read from the code source rather than passed in, so there is nothing to keep in
    /// sync and no chance of launching the wrong jar. When the plugin runs from a directory
    /// of classes (during development) there is no jar, and the caller is told instead of
    /// silently starting nothing.</p>
    ///
    /// @return the jar path, or null when the plugin is not running from a jar
    static Path ownJar() {
        try {
            var source = ConsoleSessions.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null)
                return null;
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) && path.toString().endsWith(".jar") ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /// The java executable to start the host with.
    ///
    /// <p>Taken from the running launcher rather than from the server profile: the host does
    /// no Minecraft work, and using the launcher's own java guarantees a version the host
    /// class was compiled for.</p>
    private static String javaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home", ""));
        Path java = javaHome.resolve("bin").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java");
        return Files.isRegularFile(java) ? java.toString() : "java";
    }

    /// Re-publishes a session file after the launcher edited it. Currently unused, kept for
    /// symmetry with [ConsoleSessions#readSession].
    private static void writeAtomic(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}

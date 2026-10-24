package org.dsh.servermanager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/// A standalone host that runs one Minecraft server in its own window and survives the
/// launcher.
///
/// <h2>Why a separate process</h2>
/// <p>A server started as a child of the launcher dies with it, and its console lives inside
/// the launcher window. The requested behaviour is the opposite: a real console window that
/// keeps working after the launcher closes, and that the launcher can attach to again on its
/// next start.</p>
///
/// <p>So the server is started by this class, in a process of its own. It owns the server's
/// stdin/stdout, shows the output in a Windows console window, and publishes its state through
/// small files in the plugin's data directory. The launcher supervises by reading and writing
/// those files; if the launcher exits, nothing here notices.</p>
///
/// <h2>Why it depends on nothing from the launcher</h2>
/// <p>It is started with {@code java -cp <plugin.jar>}, with no launcher classes on the
/// classpath. That makes it startable at any time and keeps a launcher upgrade from breaking a
/// console that is already running.</p>
///
/// <h2>Protocol</h2>
/// <p>Everything lives in {@code <dataDir>/console/<sessionId>/}:</p>
/// <ul>
///   <li>{@code session.json} — what is running: directory, port, profile id, pid;</li>
///   <li>{@code commands.txt} — the launcher appends console commands, one per line;</li>
///   <li>{@code input.pos} — how far into that file this host has already read;</li>
///   <li>{@code output.log} — everything the server printed;</li>
///   <li>{@code exit.code} — written when the server stops, so the launcher learns why.</li>
/// </ul>
///
/// <h2>Usage</h2>
/// <pre>
///   java -cp server-manager.jar org.dsh.servermanager.ConsoleHost &lt;sessionDir&gt;
/// </pre>
/// <p>Arguments for the server itself are passed as a file in the session directory
/// ({@code launch.txt}), one argument per line, so quoting never has to be guessed.</p>
final class ConsoleHost {

    /// The server's own working directory, read from the session.
    private static Path serverDirectory;

    /// Where this host's protocol files live.
    private static Path sessionDirectory;

    /// The server process.
    private static Process server;

    /// The server's stdin.
    private static BufferedWriter serverInput;

    /// Offset already consumed from {@code commands.txt}.
    private static long commandPosition;

    private ConsoleHost() {
    }

    /// Entry point.
    ///
    /// @param args exactly one element: the session directory
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: ConsoleHost <会话目录>");
            System.exit(2);
            return;
        }
        sessionDirectory = Path.of(args[0]);
        try {
            run();
        } catch (Throwable t) {
            System.err.println("[console-host] 启动失败: " + t);
            writeExit(-1);
            System.exit(1);
        }
    }

    /// Reads a small text file written by the launcher.
    ///
    /// <p>A byte-order mark is stripped: other tools writing these files (a script, an editor)
    /// commonly add one, and a {@code \ufeff} glued to the front of a path turns it into an
    /// invalid path with a message that gives no hint where the character came from.</p>
    ///
    /// @param file the file to read
    /// @return its text, trimmed, without a leading BOM
    private static String readText(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\ufeff')
            text = text.substring(1);
        return text.trim();
    }

    /// Reads a list of lines, dropping a byte-order mark from the first one.
    private static List<String> readLines(Path file) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        if (!lines.isEmpty() && !lines.get(0).isEmpty() && lines.get(0).charAt(0) == '\ufeff')
            lines.set(0, lines.get(0).substring(1));
        return lines;
    }

    /// Starts the server, then pumps its output and the command file until it exits.
    private static void run() throws Exception {
        List<String> command = new ArrayList<>(readLines(sessionDirectory.resolve("launch.txt")));
        command.removeIf(String::isBlank);
        if (command.isEmpty())
            throw new IOException("launch.txt 为空");

        String directory = readText(sessionDirectory.resolve("dir.txt"));
        serverDirectory = Path.of(directory);

        System.out.println("================================================================");
        System.out.println(" 服务端控制台：" + serverDirectory.getFileName());
        System.out.println(" 目录：" + serverDirectory);
        System.out.println(" 关闭此窗口不会停止服务端；停止服务端请输入 stop 并回车。");
        System.out.println("================================================================");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(serverDirectory.toFile());
        builder.redirectErrorStream(true);
        // The server reads its commands from this host's stdin, which the host feeds from
        // both the console window and the command file, so both paths reach the same place.
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);

        server = builder.start();
        serverInput = new BufferedWriter(
                new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        writeSession(server.pid());

        Thread output = new Thread(ConsoleHost::pumpServerOutput, "console-host-output");
        output.setDaemon(true);
        output.start();

        Thread commands = new Thread(ConsoleHost::pumpCommands, "console-host-commands");
        commands.setDaemon(true);
        commands.start();

        Thread console = new Thread(ConsoleHost::pumpConsoleInput, "console-host-stdin");
        console.setDaemon(true);
        console.start();

        int code = server.waitFor();
        writeExit(code);
        System.out.println();
        System.out.println("[console-host] 服务端已退出，退出码 " + code + "。本窗口可以关闭。");
    }

    /// Copies the server's output to both the console window and {@code output.log}.
    private static void pumpServerOutput() {
        Path log = sessionDirectory.resolve("output.log");
        try (BufferedWriter writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException ignored) {
            // The server closing its output is the normal way this ends.
        }
    }

    /// Forwards commands the launcher appends to {@code commands.txt}.
    private static void pumpCommands() {
        Path file = sessionDirectory.resolve("commands.txt");
        Path positionFile = sessionDirectory.resolve("input.pos");
        try {
            if (Files.isRegularFile(positionFile))
                commandPosition = Long.parseLong(
                        Files.readString(positionFile, StandardCharsets.UTF_8).trim());
        } catch (Exception ignored) {
            commandPosition = 0;
        }

        while (server.isAlive()) {
            try {
                Thread.sleep(300);
                if (!Files.isRegularFile(file))
                    continue;
                List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (all.size() <= commandPosition)
                    continue;
                for (int i = (int) commandPosition; i < all.size(); i++) {
                    String command = all.get(i).trim();
                    if (!command.isEmpty()) {
                        sendToServer(command);
                        System.out.println("> " + command);
                    }
                }
                commandPosition = all.size();
                Files.writeString(positionFile, String.valueOf(commandPosition),
                        StandardCharsets.UTF_8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // A partially written file is retried on the next pass.
            }
        }
    }

    /// Forwards what the user types in the console window.
    ///
    /// <p>This is what makes the window genuinely useful on its own: with the launcher gone,
    /// typing {@code stop} here is the only way to shut the server down cleanly.</p>
    private static void pumpConsoleInput() {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty())
                    continue;
                // `exit` closes only this window; the server keeps running.
                if ("exit".equalsIgnoreCase(trimmed)) {
                    System.out.println("[console-host] 窗口关闭，服务端继续运行。");
                    System.out.println("[console-host] 如需停止服务端，请先输入 stop。");
                    Thread.sleep(1200);
                    System.exit(0);
                    return;
                }
                sendToServer(trimmed);
            }
        } catch (Exception ignored) {
            // stdin closed: the window is going away
        }
    }

    /// Writes one command to the server's stdin.
    private static synchronized void sendToServer(String command) {
        try {
            if (serverInput == null)
                return;
            serverInput.write(command);
            serverInput.newLine();
            serverInput.flush();
        } catch (IOException ignored) {
            // The server is gone; waitFor() will report it.
        }
    }

    /// Publishes the session description the launcher reads to attach.
    ///
    /// <p>Written after the server starts, so the pid is real. The launcher uses the
    /// directory and port in here to decide which saved server this console belongs to.</p>
    ///
    /// @param pid the server process id
    private static void writeSession(long pid) throws IOException {
        String profileId = readText(sessionDirectory.resolve("profile-id.txt"));
        String port = readText(sessionDirectory.resolve("port.txt"));

        String json = "{\n"
                + "  \"sessionId\": \"" + sessionDirectory.getFileName() + "\",\n"
                + "  \"profileId\": \"" + profileId + "\",\n"
                + "  \"directory\": \"" + escape(serverDirectory.toString()) + "\",\n"
                + "  \"port\": " + port + ",\n"
                + "  \"pid\": " + pid + ",\n"
                + "  \"startedAt\": \"" + Instant.now() + "\"\n"
                + "}\n";
        // Written to a temporary file first: the launcher polls this file, and a half-written
        // session would look like a session with no pid.
        Path target = sessionDirectory.resolve("session.json");
        Path temp = sessionDirectory.resolve("session.json.tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /// Records the server's exit code for the launcher.
    ///
    /// @param code the process exit code, or -1 when the host itself failed
    private static void writeExit(int code) {
        try {
            Files.writeString(sessionDirectory.resolve("exit.code"),
                    String.valueOf(code), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // nothing useful can be done if even this fails
        }
    }

    /// Escapes a string for the small hand-built JSON above.
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

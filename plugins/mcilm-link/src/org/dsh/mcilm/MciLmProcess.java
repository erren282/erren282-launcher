/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.dsh.mcilm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/// Runs the MciLm-link command line client as a child process and exposes its event stream.
///
/// <p>The client is driven the way its developer documentation prescribes for launchers:
/// `--json` turns stdout into an NDJSON event stream (human readable logs stay on stderr),
/// and the process is kept alive for as long as the room or link should live. Terminating
/// the process is the documented way to close the room / leave the room, so no command
/// protocol is needed.</p>
///
/// <p>Events are delivered on the reader thread; callers must marshal to the JavaFX thread
/// themselves.</p>
@SuppressWarnings("unused")
public final class MciLmProcess {

    /// One NDJSON event emitted by the client.
    ///
    /// @param type the event type, e.g. `room_meta`
    /// @param raw  the raw JSON line, kept for logging and forward compatibility
    public record Event(String type, String raw) {
    }

    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final Path executable;
    private final List<String> arguments;
    private final List<Consumer<Event>> eventListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Integer>> exitListeners = new CopyOnWriteArrayList<>();

    private volatile Process process;
    private volatile boolean stopping;

    /// Creates a process wrapper.
    ///
    /// @param executable the client executable
    /// @param arguments  the command line arguments
    public MciLmProcess(Path executable, List<String> arguments) {
        this.executable = executable;
        this.arguments = List.copyOf(arguments);
    }

    /// Registers a listener for NDJSON events.
    public void addEventListener(Consumer<Event> listener) {
        eventListeners.add(listener);
    }

    /// Registers a listener for stderr log lines.
    public void addLogListener(Consumer<String> listener) {
        logListeners.add(listener);
    }

    /// Registers a listener invoked with the exit code once the process ends.
    public void addExitListener(Consumer<Integer> listener) {
        exitListeners.add(listener);
    }

    /// Starts the process and the two reader threads.
    ///
    /// @throws IOException when the executable cannot be started
    public synchronized void start() throws IOException {
        if (process != null && process.isAlive())
            throw new IllegalStateException("MciLm-link is already running");

        List<String> command = new ArrayList<>();
        command.add(executable.toAbsolutePath().toString());
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        // The client resolves relative paths (logs, received files) against the working
        // directory; the launcher's own directory is the least surprising choice.
        Path workingDirectory = executable.toAbsolutePath().getParent();
        if (workingDirectory != null && Files.isDirectory(workingDirectory))
            builder.directory(workingDirectory.toFile());

        stopping = false;
        Process started = builder.start();
        this.process = started;

        Thread stdoutReader = new Thread(() -> readEvents(started), "MciLm-link events");
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        Thread stderrReader = new Thread(() -> readLogs(started), "MciLm-link logs");
        stderrReader.setDaemon(true);
        stderrReader.start();

        Thread waiter = new Thread(() -> awaitExit(started), "MciLm-link exit");
        waiter.setDaemon(true);
        waiter.start();
    }

    /// Whether the process is currently running.
    public boolean isRunning() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /// Writes one line to the client's stdin (the interactive command channel).
    ///
    /// @param line the command line, e.g. `status`
    public void writeCommand(String line) {
        Process current = process;
        if (current == null || !current.isAlive())
            return;
        try {
            OutputStream stdin = current.getOutputStream();
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ignored) {
            // the process died in between; the exit listener reports it
        }
    }

    /// Stops the process, which closes the room or leaves the room.
    public synchronized void stop() {
        Process current = process;
        if (current == null || !current.isAlive())
            return;

        stopping = true;
        try {
            writeCommand("quit");
        } catch (Throwable ignored) {
        }

        try {
            if (!current.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                current.destroy();
            if (current.isAlive() && !current.waitFor(2, TimeUnit.SECONDS))
                current.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private void readEvents(Process current) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty())
                    continue;
                Event event = new Event(extractType(trimmed), trimmed);
                for (Consumer<Event> listener : eventListeners) {
                    try {
                        listener.accept(event);
                    } catch (Throwable ignored) {
                        // a failing listener must not stop the stream
                    }
                }
            }
        } catch (IOException ignored) {
            // the process ended
        }
    }

    private void readLogs(Process current) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (Consumer<String> listener : logListeners) {
                    try {
                        listener.accept(line);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
            // the process ended
        }
    }

    private void awaitExit(Process current) {
        int exitCode;
        try {
            exitCode = current.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitCode = -1;
        }
        if (stopping)
            exitCode = 0; // a user-requested stop is not a failure
        for (Consumer<Integer> listener : exitListeners) {
            try {
                listener.accept(exitCode);
            } catch (Throwable ignored) {
            }
        }
    }

    /// Extracts the `type` member of an event line without a JSON dependency.
    private static String extractType(String json) {
        int index = json.indexOf("\"type\"");
        if (index < 0)
            return "";
        int colon = json.indexOf(':', index + 6);
        if (colon < 0)
            return "";
        int start = json.indexOf('"', colon + 1);
        if (start < 0)
            return "";
        int end = json.indexOf('"', start + 1);
        if (end < 0)
            return "";
        return json.substring(start + 1, end);
    }

    /// Returns the file name of the client executable for the current platform.
    public static String executableName() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "MciLm-linkc.exe"
                : "MciLm-linkc";
    }

    /// Copies a freshly downloaded client into place, replacing the old one atomically.
    ///
    /// @param download the downloaded file
    /// @param target   the final location
    public static void installExecutable(Path download, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(download, temporary, REPLACE_EXISTING);
        try {
            Files.move(temporary, target, REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    /// Generates a random room password.
    ///
    /// <p>The core treats a room without a password as a public room and refuses to create it
    /// without a sponsor entitlement, so a fresh password makes every room private by default.
    /// The alphabet omits characters that are easy to confuse when read out loud.</p>
    ///
    /// @return an eight character password
    public static String generateRoomPassword() {
        final String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder builder = new StringBuilder(8);
        for (int i = 0; i < 8; i++)
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return builder.toString();
    }
}

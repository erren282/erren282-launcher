package org.dsh.servermanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Verifies the rules that decide whether a console window may be attached to a saved server.
 *
 * <h2>Why these rules matter</h2>
 * <p>Attaching the wrong window would send this server's commands to a different server — for
 * example a `stop` meant for one world shutting down another. The requirement is explicit: a
 * console window may only be attached when the server it runs still matches what the plugin
 * has saved. These checks pin that down, together with the file protocol the host and the
 * launcher use to talk.</p>
 */
public final class ConsoleSessionTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("cf-session-test-");
        try {
            identityRules();
            protocolFiles(work);
        } finally {
            deleteTree(work);
        }
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// A session belongs to a profile only when directory and port both still match.
    private static void identityRules() {
        System.out.println("=== a console window is attached only to the same server ===");

        ServerProfile saved = profile("p1", "/srv/world-a", 25565);
        ConsoleSessions.Session session = new ConsoleSessions.Session(
                "s1", "p1", "/srv/world-a", 25565, 4242);

        check("the same directory and port match",
                ConsoleSessions.matches(saved, session));

        System.out.println();
        System.out.println("=== either one differing means no attach ===");
        ServerProfile otherPort = profile("p1", "/srv/world-a", 25566);
        check("a different port does not match",
                !ConsoleSessions.matches(otherPort, session));

        ServerProfile otherDir = profile("p1", "/srv/world-b", 25565);
        check("a different directory does not match",
                !ConsoleSessions.matches(otherDir, session));

        ServerProfile otherId = profile("p2", "/srv/world-a", 25565);
        check("a different profile id does not match",
                !ConsoleSessions.matches(otherId, session));

        System.out.println();
        System.out.println("=== normalisation and degenerate input ===");
        // Both sides are absolute in practice (the host publishes its working directory), so
        // the comparison has to normalise rather than compare strings.
        Path baseDir = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("cf-session-identity").toAbsolutePath().normalize();
        ServerProfile plain = profile("p1", baseDir.toString(), 25565);
        ConsoleSessions.Session plainSession = new ConsoleSessions.Session(
                "s1", "p1", baseDir.toString(), 25565, 4242);
        check("identical absolute paths match",
                ConsoleSessions.matches(plain, plainSession));

        ServerProfile trailingSlash = profile("p1", baseDir + "/", 25565);
        check("a trailing separator still matches",
                ConsoleSessions.matches(trailingSlash, plainSession));

        ServerProfile redundant = profile("p1",
                baseDir.resolve("sub").resolve("..").toString(), 25565);
        check("a redundant path segment still matches",
                ConsoleSessions.matches(redundant, plainSession));

        check("a null profile does not match", !ConsoleSessions.matches(null, session));
        check("a null session does not match", !ConsoleSessions.matches(saved, null));

        ServerProfile nullDir = profile("p1", null, 25565);
        check("a profile with no directory does not match",
                !ConsoleSessions.matches(nullDir, session));
    }

    /// The file protocol the host polls and the launcher writes.
    private static void protocolFiles(Path work) throws Exception {
        System.out.println();
        System.out.println("=== the command file is append-only and line based ===");

        ConsoleSessions sessions = new ConsoleSessions(work);
        Path sessionDir = Files.createDirectories(work.resolve("console").resolve("s1"));
        Files.writeString(sessionDir.resolve("commands.txt"), "", StandardCharsets.UTF_8);

        ConsoleSessions.Session session = new ConsoleSessions.Session(
                "s1", "p1", "/srv/world-a", 25565, 4242);

        sessions.sendCommand(session, "stop");
        sessions.sendCommand(session, "list");

        List<String> lines = Files.readAllLines(
                sessionDir.resolve("commands.txt"), StandardCharsets.UTF_8);
        System.out.println("  commands.txt = " + lines);
        check("both commands were appended", lines.size() == 2);
        check("the first is the one sent first", "stop".equals(lines.get(0)));
        check("the second follows it", "list".equals(lines.get(1)));

        System.out.println();
        System.out.println("=== an exit code is reported only when it exists ===");
        check("a session with no exit code reports none",
                sessions.exitCodeOf(session).isEmpty());
        Files.writeString(sessionDir.resolve("exit.code"), "0", StandardCharsets.UTF_8);
        check("a recorded code is reported",
                sessions.exitCodeOf(session).orElse(-99) == 0);

        System.out.println();
        System.out.println("=== output is read back in order ===");
        Files.writeString(sessionDir.resolve("output.log"),
                "line one\nline two\n", StandardCharsets.UTF_8);
        List<String> output = sessions.outputOf(session);
        check("both lines are read", output.size() == 2);
        check("the order is preserved",
                "line one".equals(output.get(0)) && "line two".equals(output.get(1)));

        System.out.println();
        System.out.println("=== a missing session directory is skipped, not fatal ===");
        ConsoleSessions.Session missing = new ConsoleSessions.Session(
                "does-not-exist", "p1", "/srv/world-a", 25565, 4242);
        check("reading its output yields an empty list",
                sessions.outputOf(missing).isEmpty());
        check("reading its exit code yields none",
                sessions.exitCodeOf(missing).isEmpty());
        check("sending to it fails rather than silently doing nothing",
                throwsIo(() -> sessions.sendCommand(missing, "stop")));

        System.out.println();
        System.out.println("=== a session directory with no description is ignored ===");
        Files.createDirectories(work.resolve("console").resolve("empty"));
        check("live sessions skips it", sessions.liveSessions().isEmpty());
    }

    /// A profile with the given identity.
    private static ServerProfile profile(String id, String directory, int port) {
        ServerProfile profile = new ServerProfile();
        profile.setId(id);
        profile.setName("test");
        profile.setDirectory(directory);
        profile.setPort(port);
        return profile;
    }

    private static boolean throwsIo(IoRunnable action) {
        try {
            action.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /// An action that may throw.
    private interface IoRunnable {
        void run() throws Exception;
    }

    private static void deleteTree(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir))
            return;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(p);
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}

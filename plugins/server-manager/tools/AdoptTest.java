import org.dsh.servermanager.ServerManagerService;
import org.dsh.servermanager.ServerProfile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// Headless test for adopted servers: spawns a child process that fakes a running
/// Minecraft server (a listening port + a growing logs/latest.log), then verifies
/// that a fresh service instance adopts it on startup: marks it running, restores
/// the broadcast bridge, tails the log, and can force-stop it.
public final class AdoptTest {

    /** The fake "Minecraft server" run as a child process. */
    public static final class FakeServer {
        public static void main(String[] args) throws Exception {
            int port = Integer.parseInt(args[0]);
            Path dir = Path.of(args[1]);
            Files.createDirectories(dir.resolve("logs"));
            ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
            long lines = 0;
            long end = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < end) {
                // Answer the server-list status ping, like a real Minecraft server: the launcher
                // only adopts a server it can actually talk to.
                Socket client = socket.accept();
                answerStatus(client);
                lines++;
                Files.writeString(dir.resolve("logs").resolve("latest.log"),
                        "[FakeServer] tick " + lines + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                Thread.sleep(500);
            }
            socket.close();
        }
    }

    /// Minimal Minecraft status responder: handshake + status request in, JSON status out.
    private static void answerStatus(Socket socket) {
        try (socket) {
            socket.setSoTimeout(3000);
            InputStream in = socket.getInputStream();
            readVarInt(in);
            readVarInt(in);
            readVarInt(in);
            in.readNBytes(readVarInt(in));
            in.readNBytes(2);
            readVarInt(in);
            readVarInt(in);
            readVarInt(in);

            byte[] json = "{\"version\":{\"name\":\"1.20.4\",\"protocol\":765},\"players\":{\"max\":20,\"online\":0}}"
                    .getBytes(StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
            payload.write(0x00);
            writeVarInt(payload, json.length);
            payload.write(json);

            java.io.OutputStream out = socket.getOutputStream();
            writeVarInt(out, payload.size());
            out.write(payload.toByteArray());
            out.flush();
        } catch (Exception ignored) {
            // the probe only needs to see a valid answer
        }
    }

    private static void writeVarInt(java.io.OutputStream out, int value) throws IOException {
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) current |= 0x80;
            out.write(current);
        } while (remaining != 0);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int result = 0, position = 0;
        while (true) {
            int current = in.read();
            if (current < 0) throw new IOException("closed");
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) return result;
            position += 7;
            if (position > 35) throw new IOException("too long");
        }
    }

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dataDir = Files.createTempDirectory("adopt-test-data");
        Path serverDir = Files.createTempDirectory("adopt-test-server");
        Files.writeString(serverDir.resolve("server.jar"), "fake jar bytes");

        int port = 25701;
        // a previously-registered profile whose server is (about to be) still running
        ServerManagerService service = ServerManagerService.of(dataDir);
        ServerProfile profile = service.importLocal("后台服", serverDir, "server.jar",
                "1.20.4", port, "adopt-test", 512, 1024, "", true, true,
                ServerManagerService.CoreRepair.NONE, null);

        // ---- the "previous session": server started, launcher "closed" ----
        Process server = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"),
                AdoptTest.class.getName() + "$FakeServer", String.valueOf(port), serverDir.toString())
                .redirectErrorStream(true)
                .start();
        try {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && !isPortOpen(port))
                Thread.sleep(300);
            check(isPortOpen(port), "fake server listens on " + port);

            // ---- adoption: fresh service state (simulating the launcher restart) ----
            final List<String> captured = new CopyOnWriteArrayList<>();
            service.addListener(new ServerManagerService.Listener() {
                @Override
                public void onLog(ServerProfile p, String line) {
                    captured.add(line);
                }
            });

            service.adoptRunningServers();
            deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && !service.isRunning(profile))
                Thread.sleep(300);
            check(service.isRunning(profile), "adopted server reported as running");

            int proxyPort = service.getProxyPort(profile);
            check(proxyPort == port + 1, "broadcast bridge restored on " + proxyPort);
            check(legacyPing(proxyPort).contains("adopt-test"),
                    "bridge answers 0xFE with the profile MOTD");

            deadline = System.currentTimeMillis() + 15_000;
            long known = captured.size();
            boolean tailed = false;
            while (System.currentTimeMillis() < deadline) {
                if (captured.size() > known + 1) {
                    tailed = true;
                    break;
                }
                Thread.sleep(300);
            }
            check(tailed, "log tail forwards new log lines (" + (captured.size() - known) + " new)");

            // ---- force stop by port ----
            service.stop(profile);
            deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && isPortOpen(port))
                Thread.sleep(300);
            check(!isPortOpen(port), "force stop killed the external server");
            deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && service.isRunning(profile))
                Thread.sleep(300);
            check(!service.isRunning(profile), "adopted process no longer reported as running");
            // Releasing the bridge is asynchronous (it happens once the exit is observed), so
            // wait for it instead of asserting on the very next instruction.
            deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && service.getProxyPort(profile) != -1)
                Thread.sleep(200);
            check(service.getProxyPort(profile) == -1, "bridge released after stop");
        } finally {
            server.destroyForcibly();
        }

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Legacy 0xFE ping; returns the UTF-16BE body text. */
    private static String legacyPing(int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(0xFE);
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            int header = in.read();
            if (header != 0xFF)
                throw new AssertionError("expected 0xFF, got " + header);
            int chars = (in.read() << 8) | in.read();
            byte[] body = new byte[chars * 2];
            int read = 0;
            while (read < body.length)
                read += in.read(body, read, body.length - read);
            return new String(body, StandardCharsets.UTF_16BE);
        }
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok)
            failures++;
    }
}

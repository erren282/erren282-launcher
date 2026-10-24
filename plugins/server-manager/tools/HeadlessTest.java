import org.dsh.servermanager.ServerProfile;
import org.dsh.servermanager.ServerManagerService;
import org.dsh.servermanager.ServerVersions;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/// Headless end-to-end test of the server manager (run without JavaFX):
/// version lists, vanilla download, one-click start (eula/properties/process),
/// LAN broadcast packet, 0xFE keep-alive proxy answer, TCP forward and stop.
///
/// Usage: java -cp <plugin-out>;<gson-jar>;tools HeadlessTest [vanillaVersion]
public final class HeadlessTest {

    private static int failures = 0;

    static final int PORT = Integer.parseInt(System.getProperty("sm.test.port", "25599"));

    public static void main(String[] args) throws Exception {

        Path temp = Files.createTempDirectory("hmcl-server-manager-test");
        System.out.println("dataDir: " + temp);

        // 1) version manifests (official -> bmclapi fallback)
        List<ServerVersions.VersionEntry> versions = ServerVersions.fetchVanillaVersions();
        check(!versions.isEmpty(), "vanilla manifest has versions (" + versions.size() + ")");
        String pick = args.length > 0 ? args[0] : "1.20.4";
        ServerVersions.VersionEntry chosen = null;
        for (ServerVersions.VersionEntry entry : versions) {
            if (entry.getId().equals(pick)) {
                chosen = entry;
                break;
            }
        }
        check(chosen != null, "manifest contains " + pick);
        System.out.println();

        // 2) service + profile persistence
        ServerManagerService service = ServerManagerService.of(temp);
        final ServerProfile[] created = new ServerProfile[1];
        ServerVersions.Progress progress = (done, total) -> {
            if (total > 0 && done == total)
                System.out.println("download finished: " + (total / 1048576) + " MB");
        };
        ServerProfile profile = service.createVanilla("测试服", chosen.getId(), chosen.getUrl(),
                PORT, "HMCL ServerManager 测试服", 512, 1024, "", true, true, progress);
        created[0] = profile;
        check(Files.isRegularFile(profile.getJarPath()), "server.jar downloaded to " + profile.getJarPath());
        check(!service.getProfiles().isEmpty() && Files.exists(temp.resolve("servers.json")),
                "profile persisted to servers.json");
        System.out.println();

        // 3) one-click start: eula + properties + process + bridge
        ServerProcessCaptor captor = new ServerProcessCaptor();
        service.addListener(captor);
        service.start(profile);

        long deadline = System.currentTimeMillis() + 180_000;
        boolean listening = false;
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(profile.getPort())) {
                listening = true;
                break;
            }
            Thread.sleep(1000);
        }
        check(listening && service.isRunning(profile), "real server listens on " + profile.getPort());

        // the port binds early during boot; the server only accepts commands and
        // answers status pings once it reports Done
        boolean done = false;
        while (System.currentTimeMillis() < deadline && !done) {
            done = captor.sawLine(s -> s.contains("Done"));
            if (!done)
                Thread.sleep(1000);
        }
        check(done, "console reports \"Done\"");
        check(captor.sawLine(s -> s.contains("Starting Minecraft server")), "console captured boot log");

        int proxyPort = service.getProxyPort(profile);
        if (proxyPort <= 0 || !service.isRunning(profile)) {
            captor.lines.stream().skip(Math.max(0, captor.lines.size() - 10))
                    .forEach(l -> System.out.println("    [server] " + l));
            System.out.println("FAIL - server process exited before the bridge check");
            System.exit(1);
        }
        check(proxyPort == profile.getPort() + 1, "bridge proxy on port " + proxyPort);

        // 4) legacy 0xFE ping answered by the proxy
        byte[] motd = legacyPing(proxyPort);
        check(motd != null && motd.length > 0, "proxy answers 0xFE with MOTD: "
                + (motd == null ? "<none>" : "\"" + new String(motd, StandardCharsets.UTF_16BE) + "\""));

        // 5) discovery packet seen on the loopback unicast copy
        String discovery = receiveDiscovery(proxyPort, 15_000);
        check(discovery != null,
                "discovery packet contains proxy port: " + discovery);

        // 6) TCP forwarding through the proxy reaches the real server (status ping).
        // Rapid reconnects trigger the vanilla server's silent per-IP rate limiting,
        // so probes stay at a gentle 5s cadence (real clients never hammer either).
        // A/B: identical client code from this JVM vs a fresh subprocess JVM
        boolean directInProcess = forwardWorks(profile.getPort(), profile.getPort(), 765);
        check(directInProcess, "in-process status ping");
        boolean directSubprocess = subprocessPing(profile.getPort(), profile.getPort());
        check(directSubprocess, "subprocess-JVM status ping");
        boolean direct = directInProcess || directSubprocess;
        boolean forwarded = false;
        for (int i = 0; i < 6 && !forwarded; i++) {
            forwarded = forwardWorks(proxyPort, profile.getPort(), 765);
            if (!forwarded)
                Thread.sleep(5000);
        }
        check(forwarded, "proxy forwards modern status ping to the real server");
        if (System.getenv("SM_PAUSE") != null) {
            System.out.println("PAUSED for external probing: port " + profile.getPort()
                    + ", proxy " + proxyPort + " (90s)");
            Files.createFile(temp.resolve("PAUSED"));
            Thread.sleep(90_000);
        }
        if (!direct)
            captor.lines.stream().skip(Math.max(0, captor.lines.size() - 8)).forEach(l -> System.out.println("    [server] " + l));

        // 7) console command
        service.sendCommand(profile, "list");
        boolean answered = false;
        deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !answered) {
            answered = captor.sawLine(s -> s.contains("There are"));
            if (!answered)
                Thread.sleep(500);
        }
        check(answered, "console command \"list\" answered");

        // 8) stop; bridge must follow
        service.stop(profile);
        deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && service.isRunning(profile))
            Thread.sleep(500);
        check(!service.isRunning(profile), "server stopped via console command");
        Thread.sleep(1000);
        check(!isPortOpen(proxyPort), "bridge proxy released after stop");

        // 9) delete (only possible while stopped)
        service.delete(profile);
        check(service.getProfiles().isEmpty(), "profile deleted with its directory");

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------ helpers ---

    private static final class ServerProcessCaptor implements ServerManagerService.Listener {
        private final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onLog(ServerProfile profile, String line) {
            lines.add(line);
        }

        boolean sawLine(java.util.function.Predicate<String> predicate) {
            for (String line : lines)
                if (predicate.test(line))
                    return true;
            return false;
        }
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /// Sends the legacy 0xFE ping and returns the UTF-16BE body of the 0xFF response.
    private static byte[] legacyPing(int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3000);
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(0xFE);
            out.flush();
            InputStream in = socket.getInputStream();
            int header = in.read();
            if (header != 0xFF)
                throw new AssertionError("expected 0xFF response, got 0x" + Integer.toHexString(header));
            int high = in.read();
            int low = in.read();
            int chars = (high << 8) | low; // the length field counts UTF-16 code units
            byte[] body = new byte[chars * 2];
            int read = 0;
            while (read < body.length)
                read += in.read(body, read, body.length - read);
            return body;
        }
    }

    /// Binds the discovery port and waits for a broadcast packet announcing the given
    /// proxy port (the loopback copy), skipping foreign announcements on a busy LAN.
    private static String receiveDiscovery(int proxyPort, long timeoutMillis) throws Exception {
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 4445))) {
            socket.setSoTimeout(2000);
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                if (text.contains("[AD]" + proxyPort + "[/AD]"))
                    return text;
            }
            return null;
        }
    }

    /// Sends a well-formed modern status handshake through the proxy; a JSON status
    /// reply proves the proxy forwarded both directions to the real server.
    private static boolean forwardWorks(int targetPort, int serverPort, int protocol) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), 3000);
            socket.setSoTimeout(5000);

            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream data = new java.io.DataOutputStream(body);
            writeVarint(data, 0x00); // packet id: handshake
            writeVarint(data, protocol); // protocol version under test
            byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            writeVarint(data, host.length);
            data.write(host);
            data.writeShort(serverPort);
            writeVarint(data, 1);    // next state: status
            byte[] handshake = body.toByteArray();

            java.io.ByteArrayOutputStream out1 = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream packet = new java.io.DataOutputStream(out1);
            writeVarint(packet, handshake.length);
            packet.write(handshake);
            packet.write(0x01);      // status request: length 1
            packet.write(0x00);

            OutputStream out = socket.getOutputStream();
            byte[] burst = out1.toByteArray();
            if (Boolean.getBoolean("sm.test.hexdump")) {
                StringBuilder dump = new StringBuilder("    burst:");
                for (byte b : burst)
                    dump.append(String.format(" %02X", b));
                System.out.println(dump);
            }
            out.write(burst);
            out.flush();

            InputStream in = socket.getInputStream();
            // reply: varint length, packet id 0x00, varint string length + JSON
            int length = readVarint(in);
            int id = in.read();
            return id == 0x00 && in.read() > 0; // string-length varint of the JSON status
        } catch (Exception e) {
            System.out.println("    forwardWorks exception: " + e);
            return false;
        }
    }

    private static void writeVarint(java.io.DataOutputStream out, int value) throws Exception {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    /// One ping from a fresh JVM, same code path as the in-process check.
    private static boolean subprocessPing(int targetPort, int serverPort) throws Exception {
        Path pingSrc = Path.of("tools", "Ping.java");
        Path pingDir = Files.createTempDirectory("sm-ping");
        ProcessBuilder compile = new ProcessBuilder("javac", "-d", pingDir.toString(), pingSrc.toString());
        compile.inheritIO();
        if (compile.start().waitFor() != 0)
            return false;
        ProcessBuilder run = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", pingDir.toString(), "Ping",
                String.valueOf(targetPort), String.valueOf(serverPort));
        run.redirectErrorStream(true);
        Process process = run.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        System.out.println("    subprocess ping: " + output.replace(System.lineSeparator(), " | "));
        return process.exitValue() == 0;
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS - " : "FAIL - ") + label);
        if (!ok)
            failures++;
    }

    static int readVarint(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0)
                throw new EOFException("varint ended early");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return value;
            shift += 7;
            if (shift > 28)
                throw new IOException("varint too long");
        }
    }
}
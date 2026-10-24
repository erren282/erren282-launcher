import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Verifies the two behaviours that made "the server won't start" and "guests wait forever":
/// a plain listener must not be mistaken for a running Minecraft server, and an imported
/// server must keep the memory its own start script asks for.
public final class ServerProbeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 1. A listener that only accepts TCP (like a leftover broadcast/keep-alive proxy)
        //    must not count as a Minecraft server.
        try (ServerSocket plain = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            int port = plain.getLocalPort();
            Thread acceptor = new Thread(() -> {
                while (!plain.isClosed()) {
                    try {
                        Socket socket = plain.accept();
                        // answer a legacy 0xFE ping exactly like the launcher's own bridge does
                        InputStream in = socket.getInputStream();
                        if (in.read() == 0xFE) {
                            OutputStream out = socket.getOutputStream();
                            out.write(0xFF);
                            out.write(new byte[]{0x00, 0x04, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
                            out.flush();
                        }
                        socket.close();
                    } catch (IOException ignored) {
                        return;
                    }
                }
            }, "plain-listener");
            acceptor.setDaemon(true);
            acceptor.start();

            check(!org.dsh.servermanager.ServerManagerService.isMinecraftServer(port),
                    "a bare listener answering legacy 0xFE is NOT a running Minecraft server");
        }

        // 2. A fake server that speaks the status protocol IS recognised (the launcher's own
        //    Paper server answers exactly like this).
        try (ServerSocket minecraft = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            int port = minecraft.getLocalPort();
            Thread server = new Thread(() -> {
                while (!minecraft.isClosed()) {
                    try {
                        Socket socket = minecraft.accept();
                        new Thread(() -> answerStatus(socket), "status").start();
                    } catch (IOException ignored) {
                        return;
                    }
                }
            }, "mc-listener");
            server.setDaemon(true);
            server.start();

            check(org.dsh.servermanager.ServerManagerService.isMinecraftServer(port),
                    "a server answering the status ping IS recognised (launcher may adopt it)");
        }

        // 3. A dead port is not a server.
        int deadPort;
        try (ServerSocket temporary = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            deadPort = temporary.getLocalPort();
        }
        check(!org.dsh.servermanager.ServerManagerService.isMinecraftServer(deadPort),
                "a closed port is not a running server");

        // 4. Memory comes from the directory's own start script.
        Path dir = Files.createTempDirectory("server-script");
        Files.writeString(dir.resolve("start.bat"), "java -Xms4G -Xmx8G -jar server.jar\npause\n",
                StandardCharsets.UTF_8);
        int[] memory = org.dsh.servermanager.ServerManagerService.scriptMemory(dir);
        check(memory != null && memory[0] == 4096 && memory[1] == 8192,
                "start.bat -Xms4G -Xmx8G is adopted: " + (memory == null ? "null" : memory[0] + "/" + memory[1]));

        Path dir2 = Files.createTempDirectory("server-script2");
        Files.writeString(dir2.resolve("run.bat"), "java -Xmx2048M -jar server.jar\n", StandardCharsets.UTF_8);
        int[] memory2 = org.dsh.servermanager.ServerManagerService.scriptMemory(dir2);
        check(memory2 != null && memory2[0] == 2048 && memory2[1] == 2048,
                "a script with only -Xmx uses it for both: " + (memory2 == null ? "null" : memory2[0] + "/" + memory2[1]));

        Path dir3 = Files.createTempDirectory("server-script3");
        Files.writeString(dir3.resolve("server.properties"), "server-port=25565\n", StandardCharsets.UTF_8);
        check(org.dsh.servermanager.ServerManagerService.scriptMemory(dir3) == null,
                "no script means no override");

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /// Minimal Minecraft status server: reads the handshake + status request, replies with JSON.
    private static void answerStatus(Socket socket) {
        try (socket) {
            socket.setSoTimeout(3000);
            InputStream in = socket.getInputStream();
            readVarInt(in);                       // packet length
            readVarInt(in);                       // packet id (handshake)
            readVarInt(in);                       // protocol version
            int hostLength = readVarInt(in);
            in.readNBytes(hostLength);
            in.readNBytes(2);                     // port
            readVarInt(in);                       // next state
            readVarInt(in);                       // status request length
            readVarInt(in);                       // status request id

            byte[] json = "{\"version\":{\"name\":\"1.21.4\",\"protocol\":769},\"players\":{\"max\":20,\"online\":0}}"
                    .getBytes(StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
            payload.write(0x00);
            writeVarInt(payload, json.length);
            payload.write(json);

            OutputStream out = socket.getOutputStream();
            writeVarInt(out, payload.size());
            out.write(payload.toByteArray());
            out.flush();
        } catch (Exception ignored) {
            // the client just needs to see the answer
        }
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
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

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok) failures++;
    }
}

import org.dsh.servermanager.ServerBridge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/// Isolated ServerBridge test: a fake TCP "MC server" verifies that the bridge
/// forwards modern handshake bursts, while the 0xFE ping is answered locally.
public final class MiniBridgeTest {

    public static void main(String[] args) throws Exception {
        int realPort = 25591;
        FakeServer server = new FakeServer(realPort);
        server.start();

        ServerBridge bridge = new ServerBridge(realPort, "隔离测试服");
        bridge.start();
        int proxyPort = bridge.getProxyPort();
        System.out.println("proxy port: " + proxyPort);

        // direct hit: proves the encoder matches the fake server's expectations
        boolean direct = forwardWorks(realPort, realPort);
        System.out.println((direct ? "PASS" : "FAIL") + " - direct modern status ping (encoder)");

        boolean viaProxy = forwardWorks(proxyPort, realPort);
        System.out.println((viaProxy ? "PASS" : "FAIL") + " - modern status ping through bridge (forwarding)");

        Thread.sleep(300);
        bridge.stop();
        server.stop();
        System.out.println((direct && viaProxy ? "ALL OK" : "SOMETHING FAILED"));
        System.exit(direct && viaProxy ? 0 : 1);
    }

    /// Replies with a status JSON only after seeing the exact handshake burst.
    private static final class FakeServer implements Runnable {
        private final int port;
        private ServerSocket serverSocket;
        private Thread thread;
        private volatile boolean running = true;

        FakeServer(int port) {
            this.port = port;
        }

        void start() throws Exception {
            serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
            thread = new Thread(this, "fake-server");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            while (running) {
                try (Socket socket = serverSocket.accept()) {
                    InputStream in = socket.getInputStream();
                    byte[] burst = new byte[19];
                    int read = 0;
                    socket.setSoTimeout(2000);
                    while (read < burst.length) {
                        int n = in.read(burst, read, burst.length - read);
                        if (n < 0)
                            break;
                        read += n;
                    }
                    if (read == burst.length && burst[0] == 0x10 && burst[17] == 0x01 && burst[18] == 0x00) {
                        // status response: varint length 0x0C, id 0x00, varint strlen 0x0A, "1234567890"
                        byte[] reply = {0x0C, 0x00, 0x0A, '1', '2', '3', '4', '5', '6', '7', '8', '9', '0'};
                        OutputStream out = socket.getOutputStream();
                        out.write(reply);
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        void stop() throws Exception {
            running = false;
            serverSocket.close();
        }
    }

    private static boolean forwardWorks(int targetPort, int serverPort) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), 3000);
            socket.setSoTimeout(5000);

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(body);
            writeVarint(data, 0x00);
            writeVarint(data, 754);
            byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            writeVarint(data, host.length);
            data.write(host);
            data.writeShort(serverPort);
            writeVarint(data, 1);
            byte[] handshake = body.toByteArray();

            ByteArrayOutputStream out1 = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(out1);
            writeVarint(packet, handshake.length);
            packet.write(handshake);
            packet.write(0x01);
            packet.write(0x00);

            OutputStream out = socket.getOutputStream();
            out.write(out1.toByteArray());
            out.flush();

            InputStream in = socket.getInputStream();
            int first = in.read();
            System.out.println("    reply first byte: " + first);
            if (first <= 0)
                return false;
            int id = in.read();
            return id == 0x00 && in.read() > 0;
        } catch (Exception e) {
            System.out.println("    exception: " + e);
            return false;
        }
    }

    private static void writeVarint(DataOutputStream out, int value) throws Exception {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }
}

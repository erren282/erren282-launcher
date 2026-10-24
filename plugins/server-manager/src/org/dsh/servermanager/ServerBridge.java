package org.dsh.servermanager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/// Makes a dedicated Minecraft server visible on the LAN and keeps it alive.
///
/// <p>The bridge announces the server on the Minecraft LAN-discovery multicast group
/// `224.0.2.60:4445` (plus a unicast copy to the loopback, so a local Terracotta
/// daemon discovers it as well) and listens on a proxy port next to the real server
/// port. The proxy answers the legacy 0xFE ping itself — the same ping the Terracotta
/// daemon and LAN clients use to check the world — and forwards every other
/// connection to the real server. Clients therefore see the server even though
/// dedicated servers never announce themselves, and the announcement survives
/// server restarts.</p>
public final class ServerBridge {

    private static final InetAddress MULTICAST_GROUP = createGroup();
    private static final int DISCOVERY_PORT = 4445;
    private static final int PORT_RANGE = 24;

    private final int realPort;
    private final String motd;

    private volatile boolean running = false;
    private Thread acceptThread;
    private Thread broadcasterThread;
    private ServerSocket serverSocket;
    private DatagramSocket broadcastSocket;
    private int proxyPort = -1;

    public ServerBridge(int realPort, String motd) {
        this.realPort = realPort;
        this.motd = motd == null || motd.isEmpty() ? "A Minecraft Server" : motd;
    }

    /// Returns the proxy port the bridge announces; only valid while started.
    public int getProxyPort() {
        return proxyPort;
    }

    /// Starts broadcasting and proxying. Throws when no proxy port is available.
    public synchronized void start() throws IOException {
        if (running)
            return;

        IOException lastError = null;
        for (int offset = 1; offset <= PORT_RANGE; offset++) {
            try {
                serverSocket = new ServerSocket();
                serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), realPort + offset));
                proxyPort = realPort + offset;
                lastError = null;
                break;
            } catch (IOException e) {
                lastError = e;
                try { serverSocket.close(); } catch (IOException ignored) {}
            }
        }
        if (lastError != null)
            throw new IOException("无法绑定广播代理端口（" + (realPort + 1) + "–" + (realPort + PORT_RANGE) + "）", lastError);

        running = true;

        byte[] packet = ("[MOTD]" + motd + "[/MOTD][AD]" + proxyPort + "[/AD]")
                .getBytes(StandardCharsets.UTF_8);
        broadcastSocket = new DatagramSocket();
        InetSocketAddress multicast = new InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT);
        InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), DISCOVERY_PORT);

        broadcasterThread = new Thread(() -> {
            while (running) {
                try {
                    broadcastSocket.send(new DatagramPacket(packet, packet.length, multicast));
                } catch (IOException ignored) {
                    // multicast may fail on networks without multicast support; the
                    // unicast copy below is what local daemons depend on
                }
                try {
                    broadcastSocket.send(new DatagramPacket(packet, packet.length, loopback));
                } catch (IOException ignored) {
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "server-manager-broadcaster");
        broadcasterThread.setDaemon(true);
        broadcasterThread.start();

        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    Thread handler = new Thread(() -> handleClient(client), "server-manager-proxy");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running)
                        try { Thread.sleep(200); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "server-manager-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null)
            try { serverSocket.close(); } catch (IOException ignored) {}
        if (broadcastSocket != null)
            broadcastSocket.close();
        if (broadcasterThread != null)
            broadcasterThread.interrupt();
        acceptThread = null;
        broadcasterThread = null;
        serverSocket = null;
        broadcastSocket = null;
    }

    public boolean isRunning() {
        return running;
    }

    /// Answers the legacy 0xFE ping with a UTF-16BE MOTD; forwards everything else.
    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            int first = in.read();
            if (first < 0)
                return;

            if (first == 0xFE) {
                byte[] body = motd.getBytes(StandardCharsets.UTF_16BE);
                // the legacy protocol carries the string length in UTF-16 code units
                int units = body.length / 2;
                byte[] response = new byte[3 + body.length];
                response[0] = (byte) 0xFF;
                response[1] = (byte) ((units >> 8) & 0xFF);
                response[2] = (byte) (units & 0xFF);
                System.arraycopy(body, 0, response, 3, body.length);
                OutputStream out = socket.getOutputStream();
                out.write(response);
                out.flush();
                Thread.sleep(200);
                return;
            }

            try (Socket real = new Socket()) {
                real.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), realPort), 5000);
                real.setTcpNoDelay(true);
                OutputStream realOut = real.getOutputStream();
                realOut.write(first);
                realOut.flush();

                final InputStream clientIn = socket.getInputStream();
                final OutputStream clientOut = socket.getOutputStream();
                final InputStream realIn = real.getInputStream();
                Thread upstream = new Thread(() -> pump(realIn, clientOut));
                upstream.setDaemon(true);
                upstream.start();
                pump(clientIn, realOut);
                upstream.join(1500);
            }
        } catch (Exception ignored) {
            // connection errors are normal while the real server restarts
        }
    }

    private static void pump(InputStream from, OutputStream to) {
        byte[] buffer = new byte[16 * 1024];
        try {
            int n;
            while ((n = from.read(buffer)) > 0) {
                to.write(buffer, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { to.close(); } catch (IOException ignored) {}
        }
    }

    private static InetAddress createGroup() {
        try {
            return InetAddress.getByName("224.0.2.60");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /// Exposed for tests: reads a discovery packet on the given socket.
    static void receiveOnce(MulticastSocket socket, byte[] buffer) throws IOException {
        DatagramPacket p = new DatagramPacket(buffer, buffer.length);
        socket.receive(p);
    }
}

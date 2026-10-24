/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.terracotta;

import org.jackhuang.hmcl.util.Lang;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.locks.LockSupport;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Bridges a standalone Minecraft dedicated server (Paper/Spigot/vanilla, etc.) into the
/// Terracotta multiplayer network.
///
/// <p>The Terracotta host scanner discovers local games through the standard Minecraft LAN
/// discovery protocol: the game periodically announces itself with a packet of the form
/// `[MOTD]...[/MOTD][AD]port[/AD]` sent to the multicast group `224.0.2.60:4445`. Dedicated
/// servers never send such announcements, so this bridge sends them on the server's behalf.</p>
///
/// <p>While a hosting session is alive, the Terracotta daemon probes the advertised server
/// with a legacy Minecraft ping (`0xFE`) once a minute and closes the room after three
/// consecutive failures ("您已退出游戏世界"). A dedicated server that is restarting or
/// briefly down would trip this check, so instead of advertising the real server port the
/// bridge advertises a local proxy port: the proxy always answers the legacy ping itself and
/// forwards every other connection to the real server. The room therefore survives server
/// restarts, and guests are transparently tunneled to the real server.</p>
///
/// <p>Usage: {@link #detectServer(Path)} reads `server.properties` of the selected server
/// directory, {@link #isPortListening(int)} checks whether the server is running, and
/// {@link #startHosting(ServerInfo)} starts the proxy and the announcement loop until the
/// session leaves the host flow or {@link #stopHosting()} is called.</p>
@NotNullByDefault
public final class TerracottaServerBridge {

    /// The multicast group Minecraft clients use to announce opened-to-LAN worlds,
    /// which the Terracotta host scanner listens on.
    private static final String MULTICAST_GROUP = "224.0.2.60";
    private static final int MULTICAST_PORT = 4445;
    private static final long BROADCAST_INTERVAL_NANOS = 1_200_000_000L;
    private static final long STATE_GRACE_MILLIS = 15_000;

    private static volatile boolean broadcasting = false;
    private static volatile @Nullable ServerSocket proxyListener;

    private TerracottaServerBridge() {
    }

    /// One detected dedicated server.
    public record ServerInfo(Path directory, int port, String serverIp, String motd) {
    }

    /// Reads `server.properties` from the given directory.
    ///
    /// @return the server info, or `null` when the directory does not contain a `server.properties`
    public static @Nullable ServerInfo detectServer(Path directory) {
        Path propertiesFile = directory.resolve("server.properties");
        if (!Files.isRegularFile(propertiesFile))
            return null;

        Properties properties = new Properties();
        try (var input = Files.newInputStream(propertiesFile)) {
            // 现代服务端（Paper/Spigot 1.13+）的 server.properties 为 UTF-8 编码
            properties.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warning("Failed to read server.properties of " + directory, e);
            return null;
        }

        int port;
        try {
            port = Integer.parseInt(properties.getProperty("server-port", "25565").trim());
        } catch (NumberFormatException e) {
            port = 25565;
        }
        // server-ip 为空表示监听全部网卡；转发目标使用回环地址即可
        String serverIp = properties.getProperty("server-ip", "").trim();
        if (serverIp.isEmpty())
            serverIp = "127.0.0.1";
        String motd = properties.getProperty("motd", "A Minecraft Server").trim();
        if (motd.isEmpty())
            motd = "A Minecraft Server";

        return new ServerInfo(directory, port, serverIp, motd);
    }

    /// Returns `true` when something is listening on the given local port.
    public static boolean isPortListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /// Returns `true` only when a Minecraft server answers a status ping on this port. A bare
    /// TCP probe is not enough: a leftover listener from an earlier session would pass, and
    /// the launcher would create a room pointing at nothing — guests then wait forever in
    /// "joining the room". The ping also proves the game is really accepting players.
    public static boolean isMinecraftServerRunning(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);          // packet id: handshake
            writeVarInt(handshake, -1);            // protocol: -1 = status only
            writeVarInt(handshake, "127.0.0.1".getBytes(StandardCharsets.UTF_8).length);
            handshake.write("127.0.0.1".getBytes(StandardCharsets.UTF_8));
            handshake.write((port >> 8) & 0xFF);
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);             // next state: status
            writeVarInt(out, handshake.size());
            out.write(handshake.toByteArray());
            writeVarInt(out, 1);                   // status request
            writeVarInt(out, 0x00);
            out.flush();

            int length = readVarInt(in);
            if (length <= 0 || length > (1 << 21))
                return false;
            if (readVarInt(in) != 0x00)
                return false;
            int stringLength = readVarInt(in);
            if (stringLength < 0 || stringLength > length)
                return false;
            String json = new String(in.readNBytes(stringLength), StandardCharsets.UTF_8);
            return json.contains("\"version\"");
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0)
                current |= 0x80;
            out.write(current);
        } while (remaining != 0);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int result = 0, position = 0;
        while (true) {
            int current = in.read();
            if (current < 0)
                throw new IOException("connection closed");
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0)
                return result;
            position += 7;
            if (position > 35)
                throw new IOException("VarInt too long");
        }
    }

    /// Starts announcing the server through the Minecraft LAN discovery protocol and opens
    /// the local keep-alive proxy, so that the Terracotta host scanner can pick the server up
    /// and the room survives server restarts.
    ///
    /// <p>The announcement loop stops itself once the session leaves the host flow (a room
    /// has been created, the user backed out, or {@link #stopHosting()} is called); the proxy
    /// keeps running until {@link #stopHosting()} so that the daemon's pings keep succeeding.</p>
    ///
    /// @param info the detected server
    public static void startHosting(ServerInfo info) {
        stopHosting();
        broadcasting = true;

        // 代理端口优先选用真实端口之后的空闲端口，避免与真实服务端冲突
        ServerSocket listener = null;
        int proxyPort = -1;
        for (int p = info.port() + 1; p <= info.port() + 24 && listener == null; p++) {
            try {
                listener = new ServerSocket();
                listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 32);
                proxyPort = p;
            } catch (IOException e) {
                try {
                    if (listener != null) listener.close();
                } catch (IOException ignore) {
                }
                listener = null;
            }
        }
        if (listener == null) {
            try {
                listener = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
                proxyPort = listener.getLocalPort();
            } catch (IOException e) {
                LOG.warning("Cannot open the Terracotta bridge proxy port.", e);
                return;
            }
        }
        proxyListener = listener;

        final int advertisedPort = proxyPort;
        final String forwardHost = info.serverIp();
        final int forwardPort = info.port();
        final String motd = info.motd();

        // 代理线程：应答 daemon 的 0xFE 心跳，其余连接转发到真实服务端
        Thread acceptThread = Lang.thread(() -> {
            while (broadcasting && proxyListener != null && !proxyListener.isClosed()) {
                try {
                    Socket client = proxyListener.accept();
                    Lang.thread(() -> handleProxyConnection(client, forwardHost, forwardPort, motd),
                            "Terracotta Bridge Proxy", true);
                } catch (IOException e) {
                    if (broadcasting)
                        LOG.warning("Terracotta bridge proxy accept failed.", e);
                    return;
                }
            }
        }, "Terracotta Bridge Acceptor", true);

        // 广播线程：让主机扫描器发现本服务端
        Thread broadcastThread = Lang.thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                byte[] packet = ("[MOTD]" + motd + "[/MOTD][AD]" + advertisedPort + "[/AD]")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                DatagramPacket datagram = new DatagramPacket(packet, packet.length,
                        InetAddress.getByName(MULTICAST_GROUP), MULTICAST_PORT);
                // 单播目标：daemon 绑定 0.0.0.0:4445，单播不依赖组播路由/网卡选择
                InetAddress loopback = InetAddress.getLoopbackAddress();

                // 注意：循环内不要触碰 TerracottaManager——其 stateProperty() 关联的
                // JavaFX Platform 分发器在 toolkit 未就绪的线程上会永久阻塞。
                // 停止条件由 UI 侧（状态监听器）显式调用 stopHosting() 管理。
                while (broadcasting) {
                    socket.send(datagram);
                    try {
                        // 双保险：组播可能因本机多网卡/路由选择而丢失，单播始终可达
                        socket.send(new DatagramPacket(datagram.getData(), datagram.getLength(),
                                loopback, MULTICAST_PORT));
                    } catch (IOException e) {
                        LOG.warning("Failed to send unicast announcement to Terracotta.", e);
                    }
                    LockSupport.parkNanos(BROADCAST_INTERVAL_NANOS);
                }
            } catch (IOException e) {
                LOG.warning("Failed to announce the dedicated server to Terracotta.", e);
            }
        }, "Terracotta Bridge Broadcast", true);
    }

    /// Stops the announcement loop and the keep-alive proxy, if running.
    public static void stopHosting() {
        broadcasting = false;
        ServerSocket listener = proxyListener;
        proxyListener = null;
        if (listener != null && !listener.isClosed())
            try {
                listener.close();
            } catch (IOException ignore) {
            }
    }

    private static void handleProxyConnection(Socket client, String forwardHost, int forwardPort, String motd) {
        int first;
        try {
            client.setSoTimeout(4000);
            first = client.getInputStream().read();
            if (first < 0)
                return;
        } catch (IOException e) {
            closeQuietly(client);
            return;
        }

        if (first == 0xFE) {
            // Terracotta daemon 的旧版 Minecraft ping：代理直接应答，保证房间存活
            try (Socket socket = client) {
                socket.getOutputStream().write(legacyPingResponse(motd));
                socket.getOutputStream().flush();
            } catch (IOException ignore) {
            }
            return;
        }

        // 其余连接（游戏客户端/数据包）转发到真实服务端
        Socket backend = new Socket();
        try {
            backend.connect(new InetSocketAddress(forwardHost, forwardPort), 3000);
            backend.getOutputStream().write(first);
        } catch (IOException e) {
            // 真实服务端暂时不可用（正在重启）：直接断开该连接
            closeQuietly(client);
            closeQuietly(backend);
            return;
        }

        Thread pump = Lang.thread(() -> {
            copy(client, backend);
            closeQuietly(backend);
        }, "Terracotta Bridge Pump", true);
        copy(backend, client);
        closeQuietly(client);
    }

    private static void copy(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException ignore) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignore) {
        }
    }

    /// 构造旧版 Minecraft ping（0xFE）应答：FF + u16 长度 + UTF-16BE 的 MOTD。
    private static byte[] legacyPingResponse(String motd) {
        byte[] body = motd.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        byte[] response = new byte[3 + body.length];
        response[0] = (byte) 0xFF;
        response[1] = (byte) ((body.length >> 8) & 0xFF);
        response[2] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, response, 3, body.length);
        return response;
    }
}

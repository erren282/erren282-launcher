package org.dsh.serverlist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/// Queries a Minecraft server with the real status protocol (handshake + status request
/// over TCP), so player counts, versions, MOTD, icons and — above all — genuine connection
/// latency come straight from the server instead of a third-party web API.
///
/// <p>Deliberately minimal: no SRV resolution (catalog entries carry explicit ports), no
/// Forge channel negotiation; enough for a server browser.</p>
public final class ServerPinger {

    /// The live status of one server.
    public record Status(long latencyMs, int playersOnline, int playersMax, String version,
                         String motd, String iconBase64) {
    }

    private ServerPinger() {
    }

    /// Pings the server once. Throws on timeout or unreachable — the caller decides how to
    /// display an offline server.
    public static Status ping(String address, int port, int timeoutMs) throws IOException {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            long latency = System.currentTimeMillis() - start;

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // handshake: packet 0x00, protocol 765 (1.20.6), hostname, port, next-state = status
            byte[] host = address.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeVarInt(body, 0x00);
            writeVarInt(body, 765);
            writeVarInt(body, host.length);
            body.write(host);
            body.write((port >> 8) & 0xFF);
            body.write(port & 0xFF);
            writeVarInt(body, 0x01);
            writeVarInt(out, body.size());
            out.write(body.toByteArray());

            // status request: empty packet 0x00
            writeVarInt(out, 1);
            writeVarInt(out, 0x00);
            out.flush();

            int length = readVarInt(in);
            if (length <= 0 || length > (1 << 22))
                throw new IOException("响应长度异常: " + length);
            byte[] packet = readExactly(in, length);
            int[] cursor = {0};
            int packetId = readVarInt(packet, cursor);
            if (packetId != 0x00)
                throw new IOException("意外的响应包: " + packetId);
            int jsonLength = readVarInt(packet, cursor);
            String json = new String(packet, cursor[0], Math.min(jsonLength, packet.length - cursor[0]),
                    StandardCharsets.UTF_8);

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            int online = 0, max = 0;
            if (root.has("players") && root.getAsJsonObject("players").has("online")) {
                JsonObject players = root.getAsJsonObject("players");
                online = players.get("online").isJsonPrimitive() ? players.get("online").getAsInt() : 0;
                max = players.has("max") && players.get("max").isJsonPrimitive() ? players.get("max").getAsInt() : 0;
            }
            String version = "?";
            if (root.has("version") && root.getAsJsonObject("version").has("name"))
                version = root.getAsJsonObject("version").get("name").getAsString();
            String motd = "";
            if (root.has("description"))
                motd = flattenDescription(root.get("description"));
            // 原版状态协议的字段名是 favicon；mcstatus.io 一类的聚合接口才叫 icon
            String icon = null;
            for (String key : new String[]{"favicon", "icon"}) {
                if (root.has(key) && root.get(key).isJsonPrimitive()
                        && !root.get(key).getAsString().isBlank()) {
                    icon = root.get(key).getAsString();
                    break;
                }
            }

            return new Status(System.currentTimeMillis() - start - latency + latency,
                    online, max, version, motd, icon);
        }
    }

    /// Measures the pure TCP connect latency (the handshake latency includes server think time).
    public static long measureLatency(String address, int port, int timeoutMs) throws IOException {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
        }
        return System.currentTimeMillis() - start;
    }

    /// MOTD comes as either a plain string or a chat-component tree; flatten both to text.
    private static String flattenDescription(com.google.gson.JsonElement element) {
        StringBuilder out = new StringBuilder();
        flatten(element, out);
        return out.toString().strip();
    }

    private static void flatten(com.google.gson.JsonElement element, StringBuilder out) {
        if (element == null)
            return;
        if (element.isJsonPrimitive()) {
            out.append(element.getAsString());
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("text") && object.get("text").isJsonPrimitive())
                out.append(object.get("text").getAsString());
            if (object.has("extra") && object.get("extra").isJsonArray())
                for (var child : object.getAsJsonArray("extra"))
                    flatten(child, out);
            return;
        }
        if (element.isJsonArray())
            for (var child : element.getAsJsonArray())
                flatten(child, out);
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int chunk = in.read(data, read, length - read);
            if (chunk < 0)
                throw new IOException("连接提前关闭");
            read += chunk;
        }
        return data;
    }

    private static int readVarInt(InputStream in) throws IOException {
        int result = 0, position = 0;
        while (true) {
            int current = in.read();
            if (current < 0)
                throw new IOException("连接提前关闭");
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0)
                return result;
            position += 7;
            if (position > 35)
                throw new IOException("VarInt 过长");
        }
    }

    private static int readVarInt(byte[] data, int[] cursor) {
        int result = 0, position = 0;
        while (true) {
            int current = data[cursor[0]] & 0xFF;
            cursor[0]++;
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0)
                return result;
            position += 7;
            if (position > 35)
                throw new IllegalArgumentException("VarInt 过长");
        }
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int remaining = value;
        while (true) {
            if ((remaining & ~0x7F) == 0) {
                out.write(remaining);
                return;
            }
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while (true) {
            if ((remaining & ~0x7F) == 0) {
                out.write(remaining);
                return;
            }
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
    }
}

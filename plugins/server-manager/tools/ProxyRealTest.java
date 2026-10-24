import org.dsh.servermanager.ServerBridge;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class ProxyRealTest {
    public static void main(String[] args) throws Exception {
        int realPort = Integer.parseInt(args[0]);
        ServerBridge bridge = new ServerBridge(realPort, "真实服务端转发测试");
        bridge.start();
        int proxy = bridge.getProxyPort();
        System.out.println("proxy: " + proxy);

        // legacy ping through the proxy
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), proxy), 2000);
            s.setSoTimeout(3000);
            s.getOutputStream().write(0xFE);
            InputStream in = s.getInputStream();
            int h = in.read(); int hi = in.read(); int lo = in.read();
            int units = (hi << 8) | lo;
            byte[] body = new byte[units * 2];
            int r = 0; while (r < body.length) r += in.read(body, r, body.length - r);
            System.out.println("legacy via proxy: " + new String(body, StandardCharsets.UTF_16BE));
        }

        // modern status ping through the proxy, gentle cadence, 6 tries over 30s
        for (int i = 0; i < 6; i++) {
            String result;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), proxy), 2000);
                s.setSoTimeout(4000);
                s.getOutputStream().write(burst(realPort));
                s.getOutputStream().flush();
                // The response length is a VarInt: a >127-byte status JSON uses two bytes, so
                // reading a single byte per field misparses perfectly good replies.
                int length = readVarint(s.getInputStream());
                int id = readVarint(s.getInputStream());
                result = length > 0 && id == 0
                        ? "FORWARDED, server replied (payload=" + length + " bytes)"
                        : "no reply (length=" + length + ", id=" + id + ")";
            } catch (Exception e) { result = e.getClass().getSimpleName(); }
            System.out.println("try#" + i + ": " + result);
            Thread.sleep(5000);
        }
        bridge.stop();
    }

    static byte[] burst(int port) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        writeVarint(d, 0x00); writeVarint(d, 765);
        byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
        writeVarint(d, host.length); d.write(host);
        d.writeShort(port); writeVarint(d, 1);
        byte[] hs = body.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(o);
        writeVarint(p, hs.length); p.write(hs);
        p.write(0x01); p.write(0x00);
        return o.toByteArray();
    }

    static void writeVarint(DataOutputStream out, int v) throws Exception {
        while (true) {
            if ((v & ~0x7F) == 0) { out.write(v); return; }
            out.write((v & 0x7F) | 0x80); v >>>= 7;
        }
    }

    static int readVarint(InputStream in) throws Exception {
        int result = 0, position = 0;
        while (true) {
            int current = in.read();
            if (current < 0) throw new EOFException("closed");
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) return result;
            position += 7;
            if (position > 35) throw new IOException("varint too long");
        }
    }
}

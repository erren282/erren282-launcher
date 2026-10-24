import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class Ping {
    public static void main(String[] args) throws Exception {
        int target = Integer.parseInt(args[0]);
        int serverPort = Integer.parseInt(args[1]);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), target), 3000);
            socket.setSoTimeout(5000);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(body);
            writeVarint(d, 0x00);
            writeVarint(d, 765);
            byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            writeVarint(d, host.length);
            d.write(host);
            d.writeShort(serverPort);
            writeVarint(d, 1);
            byte[] hs = body.toByteArray();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(o);
            writeVarint(p, hs.length);
            p.write(hs);
            p.write(0x01);
            p.write(0x00);
            socket.getOutputStream().write(o.toByteArray());
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            int length = readVarint(in);
            int id = in.read();
            System.out.println("length=" + length + " id=" + id);
            System.exit(length > 0 && id == 0 ? 0 : 2);
        }
    }

    static void writeVarint(DataOutputStream out, int v) throws Exception {
        while (true) {
            if ((v & ~0x7F) == 0) { out.write(v); return; }
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
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
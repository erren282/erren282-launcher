import org.dsh.serverlist.ServerCatalog;
import org.dsh.serverlist.ServerPinger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Live check: the bundled catalog parses, and the real protocol ping returns player
/// counts and latency for a known-online server.
public final class ServerPingerTest {
    public static void main(String[] args) throws Exception {
        int failures = 0;

        List<ServerCatalog.Entry> bundled = ServerCatalog.bundled();
        System.out.println("内置目录: " + bundled.size() + " 个服务器");
        if (bundled.size() < 8) { System.out.println("FAIL - 目录太小"); failures++; }
        for (ServerCatalog.Entry e : bundled) {
            if (e.address().isBlank() || e.categories().isEmpty() || e.description().isBlank()) {
                System.out.println("FAIL - 条目不完整: " + e.name()); failures++;
            }
        }
        if (failures == 0) System.out.println("PASS - 目录条目完整");

        ServerPinger.Status status = ServerPinger.ping("mc.hypixel.net", 25565, 8000);
        System.out.println("Hypixel: 在线 " + status.playersOnline() + "/" + status.playersMax()
                + "  延迟 " + status.latencyMs() + "ms  版本 " + status.version());
        if (status.playersOnline() <= 0 || status.playersMax() <= status.playersOnline() * 0 + 0) {
            System.out.println("FAIL - 人数异常"); failures++;
        }
        if (status.latencyMs() <= 0 || status.latencyMs() > 20000) { System.out.println("FAIL - 延迟异常"); failures++; }
        if (status.version().isBlank()) { System.out.println("FAIL - 版本缺失"); failures++; }
        if (status.iconBase64() == null || !status.iconBase64().startsWith("data:image/png;base64,")) {
            System.out.println("FAIL - favicon 缺失或格式异常"); failures++;
        } else {
            System.out.println("PASS - favicon 已随状态返回");
        }

        try {
            ServerPinger.ping("127.0.0.1", 1, 1500);
            System.out.println("FAIL - 不存在的端口不该成功"); failures++;
        } catch (IOException expected) {
            System.out.println("PASS - 离线端口正确抛出异常");
        }

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

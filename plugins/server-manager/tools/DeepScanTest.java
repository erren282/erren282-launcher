import org.dsh.servermanager.JavaScanner;

import java.nio.file.Path;
import java.util.List;

/// Runs the deep scan against the real machine and prints every Java it finds, with the
/// number of inspected directories, so "真的去扫描" is verifiable.
public final class DeepScanTest {
    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        List<JavaScanner.JavaInfo> javas = JavaScanner.scanDeep(
                Path.of("E:/game/gamea/mcl/HMCL-test/.hmcl/plugins/data/org.dsh.server-manager"),
                checked -> { if (checked % 1000 == 0) System.out.println("  已检查 " + checked + " 个目录…"); });
        System.out.println("耗时 " + (System.currentTimeMillis() - start) / 1000 + " 秒，找到 " + javas.size() + " 个 Java：");
        for (JavaScanner.JavaInfo info : javas)
            System.out.println("  " + info);
        if (javas.isEmpty()) {
            System.out.println("FAIL - 什么都没找到");
            System.exit(1);
        }
    }
}

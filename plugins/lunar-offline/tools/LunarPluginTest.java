import org.dsh.lunarofl.BundledCore;
import org.dsh.lunarofl.CelestialProcess;
import org.dsh.lunarofl.JavaLocator;
import org.dsh.lunarofl.LunarSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// 无头验证内置版 Lunar 插件：
/// 1) 插件包内自带 Celestial 核心（无需外部下载）；
/// 2) 内置核心能释放到本地并含配套 agent 与许可证；
/// 3) 用内置核心真实启动一次 Celestial，观察后停止。
public final class LunarPluginTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 1) 内置核心是否随插件分发
        BundledCore.Core core = BundledCore.describe();
        System.out.println("内置核心: " + (core == null ? "(无)" : core.version() + "  jar=" + core.jar()
                + "  配套=" + core.extra() + "  来源=" + core.origin()));
        check(core != null, "插件包内读到内置核心描述");
        check(BundledCore.available(), "内置核心文件确实在插件包内");

        // 2) Java 扫描（Celestial 需要 JDK 21）
        List<JavaLocator.JavaEntry> javas = JavaLocator.scan();
        System.out.println("扫描到 " + javas.size() + " 个 Java：");
        for (JavaLocator.JavaEntry entry : javas)
            System.out.println("   " + entry.display());
        String jdk21 = javas.stream().filter(JavaLocator.JavaEntry::isJdk21)
                .map(JavaLocator.JavaEntry::path).findFirst().orElse("");
        check(!jdk21.isBlank(), "识别出 JDK 21：" + jdk21);

        // 3) 释放内置核心（模拟用户第一次点击启动）
        Path dataDirectory = Files.createTempDirectory("lunar-bundled");
        LunarSettings settings = LunarSettings.load(dataDirectory);
        settings.setUseBundledCore(true);
        settings.setJavaPath(jdk21);
        settings.save();

        long started = System.currentTimeMillis();
        Path jar = settings.resolveJar(dataDirectory);
        System.out.println("内置核心释放耗时 " + (System.currentTimeMillis() - started) + " ms -> " + jar);
        check(Files.isRegularFile(jar), "内置核心已释放为可启动的 jar");
        check(Files.isRegularFile(jar.resolveSibling("BrowserOffline-1.2.jar")), "配套 BrowserOffline agent 已释放");
        check(Files.isRegularFile(jar.resolveSibling("LICENSE-celestial-Apache-2.0.md")), "Apache-2.0 许可证已随附");

        // 再次释放应当命中缓存（大小一致即跳过），耗时明显更短
        long again = System.currentTimeMillis();
        settings.resolveJar(dataDirectory);
        long cachedMs = System.currentTimeMillis() - again;
        System.out.println("再次释放耗时 " + cachedMs + " ms（命中缓存应远小于首次）");
        check(cachedMs < 1000, "已释放的核心不会重复拷贝");

        // 4) 用内置核心真实启动
        CelestialProcess process = CelestialProcess.get();
        process.start(jdk21, jar, dataDirectory, "");
        System.out.println("已用内置核心启动，PID " + process.pid() + "，等待 12 秒观察…");
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline && process.isRunning())
            Thread.sleep(500);

        boolean alive = process.isRunning();
        System.out.println("12 秒后仍在运行: " + alive);
        System.out.println("--- Celestial 输出（末 12 行）---");
        List<String> history = process.history();
        int from = Math.max(0, history.size() - 12);
        for (String line : history.subList(from, history.size()))
            System.out.println(line.substring(0, Math.min(150, line.length())));
        check(alive, "内置核心能直接启动 Lunar 客户端");
        check(history.stream().anyMatch(l -> l.contains("Celestial v")),
                "确认运行的是内置的 Celestial 核心");

        process.stop();
        Thread.sleep(2000);
        check(!process.isRunning(), "能够停止");

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok) failures++;
    }
}

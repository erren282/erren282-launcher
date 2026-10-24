import org.dsh.servermanager.JavaCompatibility;
import org.dsh.servermanager.ServerProfile;

import java.nio.file.Files;
import java.nio.file.Path;

/// Headless verification of [JavaCompatibility] against the real runtimes and server jars on
/// this machine. Run with the plugin `out` directory plus the HMCL jar on the classpath.
public final class JavaCheckTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        String repo = args.length > 0 ? args[0] : "E:\\game\\gamea\\mcl\\HMCL-test\\.hmcl\\plugins\\data\\org.dsh.server-manager";

        System.out.println("=========== 1. Java 版本解析 ===========");
        checkMajor("1.8.0_51", 8);
        checkMajor("1.8.0_472", 8);
        checkMajor("21.0.3", 21);
        checkMajor("17.0.13", 17);
        checkMajor("25.0.1", 25);
        checkMajor("11.0.31", 11);
        checkMajor("", 0);
        checkMajor("jdk-17.0.2", 17);

        System.out.println();
        System.out.println("=========== 2. MC 版本 -> 所需 Java ===========");
        checkRequired("1.8.8", 8);
        checkRequired("1.12.2", 8);
        checkRequired("1.16.5", 8);
        checkRequired("1.17.1", 16);
        checkRequired("1.18.2", 17);
        checkRequired("1.20.4", 17);
        checkRequired("1.20.5", 21);
        checkRequired("1.21.4", 21);
        checkRequired("", 0);

        System.out.println();
        System.out.println("=========== 3. 证书库（真实 TLS 握手） ===========");
        checkTrust("jre-legacy 8u51 应为坏", "C:\\Users\\Administrator.DESKTOP-DURGV41\\AppData\\Roaming\\.minecraft\\runtime\\jre-legacy\\windows\\jre-legacy\\bin\\java.exe", true);
        checkTrust("zulu 8u472 应为好", "C:\\Users\\Administrator.DESKTOP-DURGV41\\AppData\\Roaming\\.hmcl\\java\\windows-x86_64\\zulu-8.0.472-jre\\bin\\java.exe", false);
        checkTrust("jdk17.0.2 应为好", "E:\\MCLDownload\\ext\\jre-v64-220420\\jdk17\\bin\\java.exe", false);
        checkTrust("ZULU21 应为好", "E:\\ZULU21\\bin\\java.exe", false);
        checkTrust("java14 应为好", "E:\\java14\\bin\\java.exe", false);

        System.out.println();
        System.out.println("=========== 4. jar 内 MC 版本探测 ===========");
        Path paper188Src = Path.of("E:\\game\\gamea\\mcl\\HMCL-test\\.hmcl\\plugins\\data\\org.dsh.server-manager\\servers\\7b6e7f44-67cc-491f-91d9-163da0999a69\\server.jar");
        Path paper188 = paper188Src;
        if (Files.isRegularFile(paper188Src)) {
            ServerProfile p = new ServerProfile();
            p.setDirectory(paper188.getParent().toString());
            p.setJarFile("server.jar");
            String detected = JavaCompatibility.detectMcVersion(p);
            check("Paper 1.8.8 jar 探测出 1.8.8（实际 " + detected + "）", "1.8.8".equals(detected));
        } else {
            System.out.println("  (跳过：找不到 " + paper188 + ")");
        }

        Path vanilla = Path.of("E:\\game\\game\\minecraft\\\u670d\u52a1\u7aef\\\u5e03\u5409\u5c9b\\server.jar");
        if (Files.isRegularFile(vanilla)) {
            ServerProfile p = new ServerProfile();
            p.setDirectory(vanilla.getParent().toString());
            p.setJarFile("server.jar");
            p.setMcVersion("");
            String detected = JavaCompatibility.detectMcVersion(p);
            check("Paper 1.21.4 jar 的 patch.properties 探测（实际 " + detected + "）", !detected.isBlank());
        } else {
            System.out.println("  (跳过：找不到 " + vanilla + ")");
        }

        System.out.println();
        System.out.println("=========== 5. 端到端判定：真实服务端 ===========");
        // Use a throwaway directory holding only the jar. The real server folder may already
        // contain cache/mojang_1.8.8.jar from a previous run, and in that case no download is
        // needed, so the trust store is correctly not consulted — which would make this test
        // depend on machine state instead of on the logic under test.
        Path isolated = null;
        if (Files.isRegularFile(paper188Src)) {
            isolated = Files.createTempDirectory("java-check-isolated");
            Files.copy(paper188Src, isolated.resolve("server.jar"));
            paper188 = isolated.resolve("server.jar");
        }
        if (isolated != null) {
            // paper 1.8.8 + jre-legacy(8u51) -> 证书库过旧，必须 BLOCKING
            ServerProfile broken = profileFor(paper188, "54",
                    "C:\\Users\\Administrator.DESKTOP-DURGV41\\AppData\\Roaming\\.minecraft\\runtime\\jre-legacy\\windows\\jre-legacy\\bin\\java.exe");
            JavaCompatibility.Verdict v1 = JavaCompatibility.check(broken);
            System.out.println("  [1.8.8 + jre-legacy 8u51] " + v1.getSeverity() + " / " + v1.getHeadline()
                    + " / trustBroken=" + v1.isTrustBroken() + " / 建议 Java=" + v1.getRecommendedMajor());
            check("判定为 BLOCKING", v1.getSeverity() == JavaCompatibility.Severity.BLOCKING);
            check("识别为证书库问题", v1.isTrustBroken());
            check("建议版本为 8", v1.getRecommendedMajor() == 8);

            // paper 1.8.8 + zulu 8u472 -> 正常
            ServerProfile good = profileFor(paper188, "54",
                    "C:\\Users\\Administrator.DESKTOP-DURGV41\\AppData\\Roaming\\.hmcl\\java\\windows-x86_64\\zulu-8.0.472-jre\\bin\\java.exe");
            JavaCompatibility.Verdict v2 = JavaCompatibility.check(good);
            System.out.println("  [1.8.8 + zulu 8u472]      " + v2.getSeverity() + " / " + v2.getHeadline());
            check("判定为 OK", v2.getSeverity() == JavaCompatibility.Severity.OK);

            // paper 1.8.8 + Java 21 -> 仅警告（实测能启动）
            ServerProfile high = profileFor(paper188, "54", "E:\\ZULU21\\bin\\java.exe");
            JavaCompatibility.Verdict v3 = JavaCompatibility.check(high);
            System.out.println("  [1.8.8 + ZULU21]          " + v3.getSeverity() + " / " + v3.getHeadline());
            check("判定为 WARNING（不阻止）", v3.getSeverity() == JavaCompatibility.Severity.WARNING);

            // 不存在的 Java -> BLOCKING
            ServerProfile missing = profileFor(paper188, "54", "E:\\no\\such\\java.exe");
            JavaCompatibility.Verdict v4 = JavaCompatibility.check(missing);
            System.out.println("  [1.8.8 + 不存在的Java]    " + v4.getSeverity() + " / " + v4.getHeadline());
            check("判定为 BLOCKING", v4.getSeverity() == JavaCompatibility.Severity.BLOCKING);

            // Already-cached vanilla jar -> the download cannot fail, so the trust store must
            // NOT block the start even though this Java cannot reach Mojang.
            Files.createDirectories(isolated.resolve("cache"));
            Files.copy(paper188, isolated.resolve("cache").resolve("mojang_1.8.8.jar"));
            JavaCompatibility.Verdict cached = JavaCompatibility.check(broken);
            System.out.println("  [1.8.8 + 8u51 + 已缓存原版 jar] " + cached.getSeverity()
                    + " / " + cached.getHeadline());
            check("已缓存原版 jar 时不再拦截", cached.getSeverity() == JavaCompatibility.Severity.OK);
        }

        System.out.println();
        System.out.println("=========== 6. 版本过低场景（构造） ===========");
        // 用 1.21.4 服务端（需要 21）+ Java 8 -> 必须 BLOCKING "Java 版本过低"
        if (Files.isRegularFile(vanilla)) {
            ServerProfile low = new ServerProfile();
            low.setName("1.21.4-vs-java8");
            low.setDirectory(vanilla.getParent().toString());
            low.setJarFile("server.jar");
            low.setMcVersion("1.21.4");
            low.setJavaPath("C:\\Users\\Administrator.DESKTOP-DURGV41\\AppData\\Roaming\\.hmcl\\java\\windows-x86_64\\zulu-8.0.472-jre\\bin\\java.exe");
            JavaCompatibility.Verdict v5 = JavaCompatibility.check(low);
            System.out.println("  [1.21.4 + zulu8]  " + v5.getSeverity() + " / " + v5.getHeadline()
                    + "（需要 Java " + v5.getRequiredMajor() + "，实际 " + v5.getActualMajor() + "，建议 " + v5.getRecommendedMajor() + "）");
            check("判定为 BLOCKING", v5.getSeverity() == JavaCompatibility.Severity.BLOCKING);
            check("识别为版本过低", v5.getHeadline().contains("过低"));
            check("建议版本为 21", v5.getRecommendedMajor() == 21);
        }

        System.out.println();
        System.out.println("=========== 7. 可用 Java 筛选 ===========");
        ServerProfile probe = profileFor(paper188, "54", "");
        var usable = JavaCompatibility.findUsableJavas(probe, Path.of(repo));
        System.out.println("  1.8.8 服务端可用 Java 数量：" + usable.size());
        for (var j : usable)
            System.out.println("    - " + j);
        check("筛选结果不含 8u51", usable.stream().noneMatch(j -> j.getVersion().contains("1.8.0_51")));
        check("筛选结果非空", !usable.isEmpty());

        System.out.println();
        System.out.println("==================================================");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println("==================================================");
        if (failed > 0)
            System.exit(1);
    }

    private static ServerProfile profileFor(Path jar, String name, String javaPath) {
        ServerProfile p = new ServerProfile();
        p.setName(name);
        p.setDirectory(jar.getParent().toString());
        p.setJarFile(jar.getFileName().toString());
        p.setMcVersion("");
        p.setJavaPath(javaPath);
        return p;
    }

    private static void checkMajor(String version, int expected) {
        int actual = JavaCompatibility.majorOf(version);
        check("majorOf(\"" + version + "\") == " + expected + "（实际 " + actual + "）", actual == expected);
    }

    private static void checkRequired(String mc, int expected) {
        int actual = JavaCompatibility.requiredMajor(mc);
        check("requiredMajor(\"" + mc + "\") == " + expected + "（实际 " + actual + "）", actual == expected);
    }

    private static void checkTrust(String label, String exe, boolean expectBroken) {
        Path path = Path.of(exe);
        if (!Files.isRegularFile(path)) {
            System.out.println("  (跳过：" + label + " 不存在)");
            return;
        }
        boolean broken = JavaCompatibility.isTrustStoreBroken(path);
        check(label + " => broken=" + broken, broken == expectBroken);
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }
}

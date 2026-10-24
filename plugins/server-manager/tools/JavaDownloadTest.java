import org.dsh.servermanager.JavaCompatibility;
import org.dsh.servermanager.JavaDownloader;
import org.jackhuang.hmcl.download.java.JavaPackageType;
import org.jackhuang.hmcl.download.java.disco.DiscoFetchJavaListTask;
import org.jackhuang.hmcl.download.java.disco.DiscoJavaDistribution;
import org.jackhuang.hmcl.download.java.disco.DiscoJavaRemoteVersion;
import org.jackhuang.hmcl.java.JavaManager;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Verifies that the plugin's automatic Java download really produces a runtime that can
/// reach Mojang and boot the server — the entire point of the feature.
///
/// <p>[JavaManager.getInstallJavaTask] registers the new runtime on the JavaFX thread, so the
/// toolkit has to be running; the download itself therefore runs off the FX thread, exactly as
/// it does inside the launcher.</p>
public final class JavaDownloadTest {

    public static void main(String[] args) throws Exception {
        int major = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        Platform platform = Platform.SYSTEM_PLATFORM;
        System.out.println("平台: " + platform + "，目标 Java 主版本: " + major);

        // FetchTask needs a cache directory; the launcher normally configures this at startup.
        Path cacheRoot = Files.createTempDirectory("java-download-cache");
        CacheRepository.getInstance().changeDirectory(cacheRoot);

        // JavaManager scans installed runtimes through the launcher's settings, so load them
        // (the real launcher does this before any plugin runs).
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable t) {
            System.out.println("（设置初始化提示：" + t + "）");
        }

        // Force the fresh-download path: a runtime left over from an earlier run would take the
        // "already installed" shortcut and skip the code under test.
        Path managed = org.jackhuang.hmcl.java.JavaManager.REPOSITORY
                .getPlatformRoot(platform).resolve("zulu-8.0.504");
        if (Files.isDirectory(managed)) {
            try (var walk = Files.walk(managed)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
            System.out.println("已清理上次安装的 " + managed + "，强制走全新下载路径");
        }

        CountDownLatch fxReady = new CountDownLatch(1);
        javafx.application.Platform.startup(fxReady::countDown);
        if (!fxReady.await(60, TimeUnit.SECONDS))
            throw new IllegalStateException("JavaFX 启动超时");

        var provider = DownloadProviders.getDownloadProvider();

        AtomicReference<Path> installedExe = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                try {
                    JavaManager.initialize();
                } catch (Throwable t) {
                    System.out.println("（JavaManager 初始化提示：" + t + "）");
                }

                System.out.println();
                System.out.println("=== 1. 从 Foojay/Disco 解析可用构建 ===");
                DiscoJavaDistribution distribution = DiscoJavaDistribution.ZULU;
                EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>> versions =
                        new DiscoFetchJavaListTask(provider, distribution, platform)
                                .setExecutor(Schedulers.io()).run();
                System.out.println("  发行版: " + distribution.getDisplayName());
                for (var entry : versions.entrySet())
                    System.out.println("  " + entry.getKey() + " 可选主版本: " + entry.getValue().keySet());

                DiscoJavaRemoteVersion chosen = pick(versions, major);
                if (chosen == null) {
                    System.out.println("  [FAIL] 没有找到 Java " + major);
                    failure.set(new IllegalStateException("没有找到 Java " + major));
                    return;
                }
                System.out.println("  [OK] 选中: " + chosen.getFileName()
                        + "（java_version=" + chosen.getJavaVersion()
                        + ", package_type=" + chosen.getPackageType() + "）");

                System.out.println();
                System.out.println("=== 2. 用插件自己的 JavaDownloader 下载并安装 ===");
                Path exe = JavaDownloader.download(provider, major)
                        .setExecutor(Schedulers.io())
                        .run();
                System.out.println("  [OK] 已安装: " + exe);
                if (!Files.isRegularFile(exe)) {
                    failure.set(new IllegalStateException("可执行文件不存在: " + exe));
                    return;
                }
                installedExe.set(exe);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                finished.countDown();
            }
        }, "java-download-test");
        worker.setDaemon(true);
        worker.start();

        if (!finished.await(20, TimeUnit.MINUTES)) {
            System.out.println("[FAIL] 下载超时");
            javafx.application.Platform.exit();
            System.exit(1);
        }
        if (failure.get() != null) {
            System.out.println("[FAIL] 下载/安装失败：");
            failure.get().printStackTrace(System.out);
            javafx.application.Platform.exit();
            System.exit(1);
        }

        Path exe = installedExe.get();

        System.out.println();
        System.out.println("=== 3. 验证新 Java 能连上 Mojang ===");
        String version = JavaCompatibility.readJavaVersion(exe);
        System.out.println("  版本: " + version + "（解析主版本 " + JavaCompatibility.majorOf(version) + "）");
        boolean broken = JavaCompatibility.isTrustStoreBroken(exe);
        System.out.println("  证书库过旧: " + broken);
        if (broken) {
            System.out.println("  [FAIL] 下载来的 Java 仍然无法验证 Mojang 证书！");
            javafx.application.Platform.exit();
            System.exit(1);
        }
        System.out.println("  [PASS] 证书库可用");

        System.out.println();
        System.out.println("=== 4. 用新 Java 启动 Paper 1.8.8 ===");
        Path paper = Path.of("E:\\game\\gamea\\mcl\\HMCL-test\\.hmcl\\plugins\\data\\org.dsh.server-manager\\servers\\7b6e7f44-67cc-491f-91d9-163da0999a69\\server.jar");
        if (Files.isRegularFile(paper)) {
            Path serverDir = Files.createTempDirectory("java-download-server");
            Files.copy(paper, serverDir.resolve("server.jar"));
            Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n");
            Files.createDirectories(serverDir.resolve("cache"));
            // The trust check above already proves the Java itself can reach Mojang; seeding the
            // cached vanilla jar just keeps this step from depending on the network again.
            Path cached = Path.of("E:\\game\\game\\minecraft\\\u670d\u52a1\u7aef\\pvp\u670d\u52a1\u7aef\\cache\\mojang_1.8.8.jar");
            if (Files.isRegularFile(cached))
                Files.copy(cached, serverDir.resolve("cache").resolve("mojang_1.8.8.jar"));

            ProcessBuilder pb = new ProcessBuilder(exe.toString(), "-Xms512M", "-Xmx1024M",
                    "-jar", "server.jar", "nogui");
            pb.directory(serverDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var in = process.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0)
                        output.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean done = false;
            long deadline = System.currentTimeMillis() + 240_000;
            while (System.currentTimeMillis() < deadline && process.isAlive()) {
                if (output.toString().contains("Done (")) {
                    done = true;
                    break;
                }
                Thread.sleep(1000);
            }
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
            reader.join(3000);

            String text = output.toString();
            if (!done) {
                System.out.println("  ---- 服务端输出（末尾） ----");
                String[] lines = text.split("\\R");
                for (int i = Math.max(0, lines.length - 25); i < lines.length; i++)
                    System.out.println("    " + lines[i]);
            }
            System.out.println(done ? "  [PASS] 服务端成功启动，达到 Done ("
                    : "  [FAIL] 服务端未达到 Done (");
            if (!done) {
                javafx.application.Platform.exit();
                System.exit(1);
            }
        } else {
            System.out.println("  (跳过：找不到 Paper 1.8.8 jar)");
        }

        System.out.println();
        System.out.println("全部通过。已安装的 Java: " + exe);
        javafx.application.Platform.exit();
        System.exit(0);
    }

    private static DiscoJavaRemoteVersion pick(
            EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>> versions, int major) {
        for (JavaPackageType type : new JavaPackageType[]{JavaPackageType.JRE, JavaPackageType.JDK}) {
            TreeMap<Integer, DiscoJavaRemoteVersion> map = versions.get(type);
            if (map == null || map.isEmpty())
                continue;
            DiscoJavaRemoteVersion exact = map.get(major);
            if (exact != null)
                return exact;
            var above = map.ceilingEntry(major);
            if (above != null)
                return above.getValue();
            var below = map.floorEntry(major);
            if (below != null)
                return below.getValue();
        }
        return null;
    }
}

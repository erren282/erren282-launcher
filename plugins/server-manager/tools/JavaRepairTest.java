import org.dsh.servermanager.JavaScanner;
import org.dsh.servermanager.ServerManagerService;
import org.dsh.servermanager.ServerProfile;
import org.dsh.servermanager.ServerVersions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Headless verification for the two new server-manager features:
/// 1) JavaScanner detects synthetic JDK homes (release file + bin/java(.exe)),
///    dedupes and reads versions;
/// 2) importLocal with CoreRepair.VANILLA downloads the core into a jar-less
///    directory; NONE with a missing jar fails with a helpful message.
public final class JavaRepairTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // ---- 1) java scanning on synthetic layouts ----
        Path base = Files.createTempDirectory("java-scan-test");
        Path jdkA = base.resolve("jdk-17.0.6");
        Files.createDirectories(jdkA.resolve("bin"));
        Files.writeString(jdkA.resolve("release"), "JAVA_VERSION=\"17.0.6\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        Files.writeString(jdkA.resolve("bin").resolve(javaExe()), "fake");
        Path vendor = base.resolve("Zulu");
        Path jdkB = vendor.resolve("zulu-21").resolve("zulu-21.28.85");
        Files.createDirectories(jdkB.resolve("bin"));
        Files.writeString(jdkB.resolve("release"), "JAVA_VERSION=\"21.0.3\"\n");
        Files.writeString(jdkB.resolve("bin").resolve(javaExe()), "fake");
        Path notJava = base.resolve("notjava");
        Files.createDirectories(notJava);

        List<JavaScanner.JavaInfo> found = JavaScanner.scanRoots(List.of(base));
        boolean hasA = found.stream().anyMatch(j -> j.getVersion().equals("17.0.6")
                && j.getPath().endsWith(javaExe()));
        boolean hasB = found.stream().anyMatch(j -> j.getVersion().equals("21.0.3"));
        check(hasA, "flat JDK home detected with version from release file");
        check(hasB, "nested vendor JDK detected (" + found.size() + " total)");
        check(found.stream().noneMatch(j -> j.getPath().contains("notjava")), "non-JDK dir ignored");

        // real-environment scan must not throw and should at least find the launcher's Java
        List<JavaScanner.JavaInfo> real = JavaScanner.scan(Path.of(".").toAbsolutePath());
        check(!real.isEmpty(), "real-machine scan found " + real.size() + " javas");

        // ---- 2) import with core repair ----
        Path dataDir = Files.createTempDirectory("server-manager-repair");
        ServerManagerService service = ServerManagerService.of(dataDir);
        Path emptyServer = Files.createTempDirectory("import-empty");

        // NONE + missing jar must fail with a helpful message
        try {
            service.importLocal("坏导入", emptyServer, "server.jar", "1.20.4", 25565,
                    "motd", 512, 1024, "", true, false,
                    ServerManagerService.CoreRepair.NONE, null);
            check(false, "NONE + missing jar should fail");
        } catch (IOException e) {
            check(e.getMessage().contains("核心修复"), "NONE + missing jar fails with guidance: " + e.getMessage());
        }

        // repair download without version must fail early
        try {
            service.importLocal("坏版本", emptyServer, "server.jar", "", 25565,
                    "motd", 512, 1024, "", true, false,
                    ServerManagerService.CoreRepair.VANILLA, null);
            check(false, "repair without version should fail");
        } catch (IOException e) {
            check(e.getMessage().contains("版本号"), "repair without version fails early: " + e.getMessage());
        }

        // VANILLA repair downloads the core into the imported dir
        final long[] downloaded = {0};
        ServerVersions.Progress progress = (done, total) -> downloaded[0] = done;
        ServerProfile profile = service.importLocal("修复导入", emptyServer, "server.jar",
                "1.20.4", 25599, "repair-test", 512, 1024, "", true, false,
                ServerManagerService.CoreRepair.VANILLA, progress);
        check(Files.isRegularFile(emptyServer.resolve("server.jar")), "vanilla core downloaded into the imported dir");
        check(downloaded[0] > 10_000_000, "download size looks right: " + downloaded[0] / 1048576 + " MB");
        check(!service.getProfiles().isEmpty(), "profile registered");

        // repair also works when a jar is already present (overwrite path compiles/executes)
        ServerProfile repaired = service.importLocal("覆盖修复", emptyServer, "server.jar",
                "1.20.4", 25598, "repair-test2", 512, 1024, "", true, false,
                ServerManagerService.CoreRepair.VANILLA, progress);
        check(Files.isRegularFile(repaired.getJarPath()), "overwrite repair keeps a valid jar");

        // ---- cleanup ----
        for (ServerProfile p : List.copyOf(service.getProfiles())) {
            if (!Path.of(p.getDirectory()).equals(emptyServer))
                continue; // never touch the user-facing dir in this test beyond the downloaded jar
        }
        Files.deleteIfExists(emptyServer.resolve("server.jar"));
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String javaExe() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok)
            failures++;
    }
}

package org.dsh.lunarofl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// 查找本机 Java，并判断它是不是 Celestial 需要的 **JDK 21**。
///
/// <p>Celestial 启动脚本明确要求 JDK 而非 JRE（它会用到 javac），所以这里既找 java.exe，
/// 也检查同目录是否有 javac.exe，并从 `release` 文件或 `java -version` 读取版本。</p>
public final class JavaLocator {

    /// 一个候选 Java。
    public record JavaEntry(String path, String version, boolean jdk) {

        /// 是否满足 Celestial 的要求：主版本 21 且是 JDK。
        public boolean isJdk21() {
            return jdk && version.startsWith("21");
        }

        /// 主版本是否不低于给定值（兼容 `1.8.0_504` 这种老格式）。
        public boolean isAtLeast(int major) {
            String normalized = version == null ? "" : version.trim();
            if (normalized.startsWith("1."))
                normalized = normalized.substring(2);
            int dot = normalized.indexOf('.');
            if (dot >= 0)
                normalized = normalized.substring(0, dot);
            int underscore = normalized.indexOf('_');
            if (underscore >= 0)
                normalized = normalized.substring(0, underscore);
            try {
                return Integer.parseInt(normalized.trim()) >= major;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /// 供下拉框显示的文本。
        public String display() {
            String name = version.isBlank() ? "未知版本" : version;
            return (jdk ? "JDK " : "JRE ") + name + "  ·  " + path;
        }
    }

    private static final Pattern VERSION = Pattern.compile("version \"([^\"]+)\"");
    private static final Pattern RELEASE = Pattern.compile("JAVA_VERSION=\"([^\"]+)\"");

    private JavaLocator() {
    }

    /// 扫描常见位置（含所有盘符的关键词目录），按 java.exe 去重。
    public static List<JavaEntry> scan() {
        Map<String, JavaEntry> found = new LinkedHashMap<>();
        List<Path> homes = new ArrayList<>();

        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isBlank())
            homes.add(Path.of(javaHome));
        for (String env : new String[]{"JAVA_HOME", "JDK_HOME"}) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank())
                homes.add(Path.of(value.trim()));
        }
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            homes.add(Path.of(userHome, ".minecraft", "runtime"));
            homes.add(Path.of(userHome, ".hmcl", "java"));
        }

        for (Path home : homes)
            inspect(home, found, 2);

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)"}) {
                String base = System.getenv(env);
                if (base == null)
                    continue;
                for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Microsoft", "Zulu",
                        "Amazon Corretto", "BellSoft", "JetBrains"})
                    inspect(Path.of(base, vendor), found, 3);
            }
            // 所有盘符的根与关键词目录：兜住 E:\ZULU21、D:\jdk-21 这类自定义位置
            for (Path drive : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
                inspect(drive, found, 1);
                try (var children = Files.list(drive)) {
                    for (Path child : children.toList()) {
                        String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (name.contains("jdk") || name.contains("java") || name.contains("jre")
                                || name.contains("zulu") || name.contains("corretto")
                                || name.contains("adoptium") || name.contains("temurin")
                                || name.contains("graal") || name.contains("liberica")
                                || name.contains("dragonwell") || name.contains("openjdk"))
                            inspect(child, found, 2);
                    }
                } catch (IOException ignored) {
                    // 盘符不可读：跳过
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /// 递归查找 `bin/java.exe`（或 bin/java），最多 `depth` 层。
    private static void inspect(Path directory, Map<String, JavaEntry> found, int depth) {
        if (directory == null || depth < 0 || !Files.isDirectory(directory))
            return;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path executable = directory.resolve("bin").resolve(windows ? "java.exe" : "java");
        if (Files.isRegularFile(executable)) {
            String key = executable.toAbsolutePath().toString().toLowerCase(Locale.ROOT);
            if (!found.containsKey(key)) {
                Path javac = directory.resolve("bin").resolve(windows ? "javac.exe" : "javac");
                found.put(key, new JavaEntry(executable.toAbsolutePath().toString(),
                        readVersion(directory, executable), Files.isRegularFile(javac)));
            }
            return;
        }
        if (depth == 0)
            return;
        try (var children = Files.list(directory)) {
            for (Path child : children.toList())
                inspect(child, found, depth - 1);
        } catch (IOException ignored) {
            // 目录不可读：跳过
        }
    }

    /// 版本优先取 `release` 文件（快且无需起进程），失败再跑一次 `java -version`。
    private static String readVersion(Path home, Path executable) {
        Path release = home.resolve("release");
        if (Files.isRegularFile(release)) {
            try {
                for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                    Matcher matcher = RELEASE.matcher(line);
                    if (matcher.find())
                        return matcher.group(1);
                }
            } catch (IOException ignored) {
                // 落到进程探测
            }
        }
        return probeVersion(executable.toString());
    }

    /// 运行 `java -version` 读取版本号。
    public static String probeVersion(String javaPath) {
        try {
            Process process = new ProcessBuilder(javaPath, "-version")
                    .redirectErrorStream(true).start();
            String output = decode(process.getInputStream().readAllBytes());
            process.waitFor(10, TimeUnit.SECONDS);
            Matcher matcher = VERSION.matcher(output);
            return matcher.find() ? matcher.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0)
            return utf8;
        try {
            return new String(bytes, Charset.forName("GB18030"));
        } catch (Exception e) {
            return utf8;
        }
    }

    /// 后台扫描（扫描会走一遍盘符，不能卡住 FX 线程）。
    public static void scanAsync(java.util.function.Consumer<List<JavaEntry>> callback) {
        Thread thread = new Thread(() -> {
            List<JavaEntry> entries;
            try {
                entries = scan();
            } catch (Throwable throwable) {
                entries = List.of();
            }
            List<JavaEntry> result = entries;
            javafx.application.Platform.runLater(() -> callback.accept(result));
        }, "lunar-offline-java-scan");
        thread.setDaemon(true);
        thread.start();
    }

    /// 读取进程输出的一行（供日志尾随使用）。
    static BufferedReader reader(java.io.InputStream stream) {
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}

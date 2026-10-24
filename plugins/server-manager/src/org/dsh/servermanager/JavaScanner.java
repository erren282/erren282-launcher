package org.dsh.servermanager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Scans the computer for Java installations so users can pick one from a list
/// instead of hunting for java.exe manually.
///
/// <p>Covered locations: the launcher's own Java, `JAVA_HOME` / `JDK_HOME`, the
/// common Windows install folders (Oracle / Adoptium / Microsoft / Zulu / Corretto /
/// BellSoft), the launcher's downloaded runtimes (`.hmcl\runtime`) and the vanilla
/// launcher's runtimes (`%APPDATA%\.minecraft\runtime`). Version numbers are read
/// from each JDK's `release` file — no process is spawned.</p>
public final class JavaScanner {

    private static final Pattern JAVA_VERSION = Pattern.compile("JAVA_VERSION=\"([^\"]+)\"");

    /// One detected installation; toString renders as `17.0.6  ·  E:\ZULU21\bin\java.exe`.
    public static final class JavaInfo {
        private final String version;
        private final String path;

        public JavaInfo(String version, String path) {
            this.version = version == null ? "" : version;
            this.path = path;
        }

        public String getVersion() { return version; }
        public String getPath() { return path; }

        @Override
        public String toString() {
            return (version.isBlank() ? "Java" : version) + "  ·  " + path;
        }
    }

    private JavaScanner() {
    }

    /// Scans well-known locations. `pluginDataDir` is used to locate the launcher's
    /// `.hmcl` directory for its downloaded runtimes.
    public static List<JavaInfo> scan(Path pluginDataDir) {
        return scanRoots(wellKnownRoots(pluginDataDir));
    }

    /// The well-known scan roots: the running JRE, JAVA_HOME/JDK_HOME, the vendor folders
    /// under Program Files, the launcher's own runtimes and the user's `.minecraft` runtimes.
    static List<Path> wellKnownRoots(Path pluginDataDir) {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("java.home", ".")));

        for (String env : new String[]{"JAVA_HOME", "JDK_HOME"}) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank())
                roots.add(Path.of(value.trim()));
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            String pf = System.getenv("ProgramFiles");
            String pf86 = System.getenv("ProgramFiles(x86)");
            List<String> bases = new ArrayList<>();
            if (pf != null) {
                bases.add(pf + "\\Java");
                bases.add(pf + "\\Eclipse Adoptium");
                bases.add(pf + "\\Microsoft");
                bases.add(pf + "\\Zulu");
                bases.add(pf + "\\Amazon Corretto");
                bases.add(pf + "\\BellSoft");
            }
            if (pf86 != null)
                bases.add(pf86 + "\\Java");
            for (String base : bases)
                addChildren(roots, Path.of(base));

            String appdata = System.getenv("APPDATA");
            if (appdata != null)
                addChildren(roots, Path.of(appdata, ".minecraft", "runtime"));
        }

        // the launcher's own runtime downloads: <hmcl>\runtime\*
        // and its Java-management downloads: <hmcl>\java\* (both homes)
        Path hmclDir = pluginDataDir == null ? null
                : pluginDataDir.toAbsolutePath().getParent() == null ? null
                : pluginDataDir.toAbsolutePath().getParent().getParent() == null ? null
                : pluginDataDir.toAbsolutePath().getParent().getParent().getParent();
        if (hmclDir != null) {
            addChildren(roots, hmclDir.resolve("runtime"));
            addChildren(roots, hmclDir.resolve("java"));
        }
        Path userHome = Path.of(System.getProperty("user.home", "."));
        addChildren(roots, userHome.resolve(".hmcl").resolve("java"));
        addChildren(roots, userHome.resolve(".minecraft").resolve("runtime"));

        return roots;
    }

    /// Directory names whose subtrees are never worth walking when scanning whole drives.
    private static final java.util.Set<String> PRUNED = java.util.Set.of(
            "windows", "$recycle.bin", "system volume information", "node_modules", ".git",
            ".svn", "games", "steam", "steamapps", "epic games", "windowsapps", "recovery",
            "perflogs", "intel", "nvidia", "amd", "drivers", "dell", "hp");

    /// Directory-name keywords that hint at a JDK installation.
    private static final java.util.List<String> KEYWORDS = java.util.List.of(
            "java", "jdk", "jre", "zulu", "corretto", "adoptium", "temurin", "graal",
            "bellsoft", "liberica", "dragonwell", "openjdk", "jbr", "microsoft", "amazon");

    private static boolean isJavaKeywordDir(String name) {
        for (String keyword : KEYWORDS)
            if (name.contains(keyword))
                return true;
        return false;
    }

    /// 真正的全盘扫描：所有盘符的根与关键词目录、Program Files 两棵树、PATH 上的 java，
    /// 以及快速扫描覆盖的知名目录。`progress` 每检查约 250 个目录回调一次已检查数，供界面反馈。
    ///
    /// <p>快速扫描 [scan] 只看知名位置；用户的 JDK 装在 {@code E:\ZULU21}、{@code D:\jdk} 这类
    /// 自定义位置时它什么也找不到，看起来就像“假装扫描”。本方法把整个磁盘过一遍。</p>
    public static List<JavaInfo> scanDeep(Path pluginDataDir, java.util.function.IntConsumer progress) {
        java.util.TreeMap<String, JavaInfo> result = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int[] checked = {0};

        for (Path root : wellKnownRoots(pluginDataDir))
            scanCounting(root, 3, result, checked, progress);

        collectPathJavas(result);

        for (Path drive : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
            scanCounting(drive, 2, result, checked, progress);
            for (Path child : listChildren(drive)) {
                String name = child.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (PRUNED.contains(name))
                    continue;
                if (isJavaKeywordDir(name) || name.equals("program files") || name.equals("program files (x86)"))
                    scanCounting(child, 3, result, checked, progress);
            }
            report(checked, progress);
        }
        return new ArrayList<>(result.values());
    }

    private static void scanCounting(Path dir, int depth, java.util.TreeMap<String, JavaInfo> result,
                                     int[] checked, java.util.function.IntConsumer progress) {
        checked[0]++;
        if (progress != null && checked[0] % 250 == 0)
            progress.accept(checked[0]);
        Optional<JavaInfo> info = inspect(dir);
        if (info.isPresent()) {
            result.putIfAbsent(info.get().getPath(), info.get());
            return;
        }
        if (depth <= 0 || !Files.isDirectory(dir))
            return;
        for (Path child : listChildren(dir)) {
            if (PRUNED.contains(child.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                continue;
            scanCounting(child, depth - 1, result, checked, progress);
        }
    }

    private static void report(int[] checked, java.util.function.IntConsumer progress) {
        if (progress != null)
            progress.accept(checked[0]);
    }

    /// PATH 上的 java：`where java`（Windows）/ `which -a java`（其他），并向上找 JDK home。
    private static void collectPathJavas(java.util.Map<String, JavaInfo> result) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] command = windows ? new String[]{"cmd.exe", "/c", "where java"}
                : new String[]{"/bin/sh", "-c", "which -a java"};
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = decodeConsole(process.getInputStream().readAllBytes());
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : output.split("\\r?\\n")) {
                String path = line.trim();
                if (path.isEmpty())
                    continue;
                Path exe = Path.of(path);
                if (!Files.isRegularFile(exe))
                    continue;
                Path home = exe.getParent() == null ? null : exe.getParent().getParent();
                Optional<JavaInfo> info = home == null ? Optional.empty() : inspect(home);
                if (info.isPresent())
                    result.putIfAbsent(info.get().getPath(), info.get());
                else
                    result.putIfAbsent(exe.toString(), new JavaInfo("", exe.toString()));
            }
        } catch (Exception ignored) {
            // PATH 探测只是补充手段，失败不影响其余扫描
        }
    }

    /// 控制台输出按 GB18030 兜底解码（中文 Windows）。
    private static String decodeConsole(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0)
            return utf8;
        return new String(bytes, java.nio.charset.Charset.forName("GB18030"));
    }

    /// Inspects the given directories (and, for vendor folders, their nested
    /// children up to three levels deep) as potential JDK homes. Public so tests
    /// can feed synthetic directories.
    public static List<JavaInfo> scanRoots(List<Path> roots) {
        Map<String, JavaInfo> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Path root : roots) {
            if (root == null)
                continue;
            scanTree(root, 3, result);
        }
        return new ArrayList<>(result.values());
    }

    private static void scanTree(Path dir, int depth, Map<String, JavaInfo> result) {
        Optional<JavaInfo> info = inspect(dir);
        if (info.isPresent()) {
            result.putIfAbsent(info.get().getPath(), info.get());
            return;
        }
        if (depth <= 0 || !Files.isDirectory(dir))
            return;
        for (Path child : listChildren(dir))
            scanTree(child, depth - 1, result);
    }

    /// A directory is a JDK home when it contains `bin/java(.exe)`; the version comes
    /// from the `release` marker file.
    static Optional<JavaInfo> inspect(Path dir) {
        if (dir == null || !Files.isDirectory(dir))
            return Optional.empty();
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        Path executable = dir.resolve("bin").resolve(exe);
        if (!Files.isRegularFile(executable))
            return Optional.empty();
        return Optional.of(new JavaInfo(readVersion(dir), executable.toString()));
    }

    private static String readVersion(Path home) {
        Path release = home.resolve("release");
        if (Files.isRegularFile(release)) {
            try {
                for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                    Matcher matcher = JAVA_VERSION.matcher(line);
                    if (matcher.find())
                        return matcher.group(1);
                }
            } catch (IOException ignored) {
            }
        }
        return "";
    }

    private static void addChildren(List<Path> roots, Path dir) {
        if (dir != null && Files.isDirectory(dir))
            roots.addAll(listChildren(dir));
    }

    private static List<Path> listChildren(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}

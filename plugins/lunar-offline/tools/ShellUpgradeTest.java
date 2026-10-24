import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.dsh.lunarofl.LunarVersionInstaller;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.Version;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// 验证「壳版本不再自带一份原版客户端 jar」：
///
/// <ol>
///   <li>在真实游戏目录上跑一次 `LunarVersionInstaller.regenerate`（启动时会走的那段修复逻辑），
///       确认旧格式壳版本被就地升级成 `jar` 引用原版；</li>
///   <li>确认 `getVersionJar(resolved)` 解析到原版目录里的 jar（只此一份），且旧副本已被删除；</li>
///   <li>照启动器的算法组装命令行，真实启动客户端，等待 LUNARCLIENT_STATUS_STARTED。</li>
/// </ol>
///
/// 用法：ShellUpgradeTest [游戏目录] [版本id] [java.exe]
public final class ShellUpgradeTest {

    /// 直接调用受保护的 refreshVersionsImpl，绕开事件总线（测试环境里没有启动器的刷新监听器）。
    static final class ProbeRepository extends DefaultGameRepository {
        ProbeRepository(Path baseDirectory) {
            super(baseDirectory);
        }

        void forceRefresh() {
            refreshVersionsImpl();
        }
    }

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path gameDirectory = Path.of((args.length > 0
                ? args[0]
                : "E:/game/game/minecraft/.minecraft").replace('/', File.separatorChar));
        String versionId = args.length > 1 ? args[1] : "Lunar-1.8.9";
        String java = args.length > 2 ? args[2] : javaExecutable();

        Path versionJson = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        System.out.println("游戏目录: " + gameDirectory);
        System.out.println("版本    : " + versionId + "（" + versionJson + "）");
        check(Files.isRegularFile(versionJson), "版本 json 存在");

        ProbeRepository repository = new ProbeRepository(gameDirectory);
        repository.forceRefresh();

        Version before = repository.getVersion(versionId);
        String oldShellId = before.getInheritsFrom();
        System.out.println("当前继承: " + oldShellId);
        Path oldShellDir = gameDirectory.resolve("versions").resolve(oldShellId);
        Path oldStaleJar = oldShellDir.resolve(oldShellId + ".jar");
        System.out.println("升级前壳目录: " + mb(directorySize(oldShellDir))
                + (Files.isRegularFile(oldStaleJar) ? "（自带 jar 副本 " + mb(Files.size(oldStaleJar)) + "）" : "（无 jar 副本）"));

        // 记录迁移前整个 versions 目录里壳版本占用的空间
        long shellsBefore = shellsSize(gameDirectory);

        // ---------- 1) 触发启动时的修复/迁移，并跑一遍插件启用时的壳版本清理 ----------
        LunarVersionInstaller.regenerate(versionJson);
        LunarVersionInstaller.upgradeInstalledShellVersions(gameDirectory);
        LunarVersionInstaller.repairInstalledVersions(gameDirectory);
        LunarVersionInstaller.refreshInstalledClasspaths(gameDirectory);
        // 与启动钩子 LunarOfflinePlugin#onBeforeLaunch 调用的同一条路径
        LunarVersionInstaller.refreshClasspathFor(gameDirectory, versionId);
        repository.forceRefresh();

        // Java 要求必须不低于基础版本自己的要求（原版 26.x 要 25；只声明 21 会让启动器挑 Java 21，
        // 再去执行带 --sun-misc-unsafe-memory-access 的命令行 → Unrecognized option → 秒退）
        JsonObject baseJava = readJavaVersion(gameDirectory, repository.getVersion(versionId).getInheritsFrom());
        JsonObject declaredJava = readJavaVersion(gameDirectory, versionId);
        System.out.println("基础版本 Java 要求: " + (baseJava == null ? "无" : baseJava)
                + "，本版本声明: " + (declaredJava == null ? "无" : declaredJava));
        check(declaredJava != null, "版本声明了 javaVersion");
        if (baseJava != null && declaredJava != null) {
            int baseMajor = baseJava.has("majorVersion") ? baseJava.get("majorVersion").getAsInt() : 0;
            int declaredMajor = declaredJava.has("majorVersion") ? declaredJava.get("majorVersion").getAsInt() : 0;
            check(declaredMajor >= baseMajor && declaredMajor >= 21,
                    "声明的 Java " + declaredMajor + " ≥ 基础版本要求的 " + baseMajor + " 且 ≥ 21");
        }

        String shellId = repository.getVersion(versionId).getInheritsFrom();
        System.out.println("迁移后继承: " + shellId);
        check(shellId != null && shellId.endsWith("-lunar-base"), "仍继承壳版本：" + shellId);
        check(shellId != null && !shellId.contains("-lunar-base-lunar-base"), "没有套娃壳版本（...-lunar-base-lunar-base）");

        Path shellDir = gameDirectory.resolve("versions").resolve(shellId);
        Path shellJson = shellDir.resolve(shellId + ".json");
        JsonObject shell = JsonParser.parseString(Files.readString(shellJson, StandardCharsets.UTF_8)).getAsJsonObject();
        String referencedJar = shell.has("jar") && shell.get("jar").isJsonPrimitive()
                ? shell.get("jar").getAsString() : null;
        check(referencedJar != null, "壳版本 json 用 jar 字段引用原版：" + referencedJar);
        check(!shell.has("libraries"), "壳版本仍然没有 libraries（原版库不会进 classpath）");
        check(!shell.has("logging"), "壳版本仍然没有 logging");
        check((referencedJar + "-lunar-base").equals(shellId),
                "壳版本名取自游戏版本（" + shellId + "）");

        // 目录里不应再有孤壳；每个壳都必须用 jar 引用原版，且不再自带 jar 副本
        List<String> shells = listShells(gameDirectory);
        System.out.println("目录里的壳版本: " + shells);
        long shellsAfter = shellsSize(gameDirectory);
        System.out.println("壳版本占用: " + mb(shellsBefore) + " → " + mb(shellsAfter));
        for (String other : shells) {
            Path otherJson = gameDirectory.resolve("versions").resolve(other).resolve(other + ".json");
            JsonObject otherShell = JsonParser.parseString(Files.readString(otherJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            check(otherShell.has("jar"), "壳版本 " + other + " 用 jar 引用原版");
            check(!otherShell.has("libraries"), "壳版本 " + other + " 没有 libraries");
            check(!Files.isRegularFile(gameDirectory.resolve("versions").resolve(other).resolve(other + ".jar")),
                    "壳版本 " + other + " 不再自带原版 jar 副本");
            check(isReferenced(gameDirectory, other), "壳版本 " + other + " 仍被某个版本继承（不是孤壳）");
        }
        check(shellsAfter < 1024 * 1024,
                "所有壳版本加起来不到 1 MB（只剩 json，不再复制游戏本体）：" + mb(shellsAfter));

        // 所有已安装的 Lunar 版本都不该再解析到壳目录里的 jar
        for (String lunar : listLunarVersions(gameDirectory)) {
            Version r = repository.getResolvedVersion(lunar);
            Path jar = repository.getVersionJar(r);
            System.out.println("  " + lunar + " → " + jar);
            check(!jar.getParent().getFileName().toString().endsWith("-lunar-base"),
                    lunar + " 的主 jar 不再落在壳目录里");
        }

        // ---------- 2) jar 解析到原版目录，且只有一份 ----------
        Version resolved = repository.getResolvedVersion(versionId);
        Path resolvedJar = repository.getVersionJar(resolved);
        Path expectedJar = gameDirectory.resolve("versions").resolve(referencedJar).resolve(referencedJar + ".jar");
        System.out.println("解析出的主 jar: " + resolvedJar);
        check(resolvedJar.toAbsolutePath().normalize().equals(expectedJar.toAbsolutePath().normalize()),
                "主 jar 指向原版版本目录：" + expectedJar);
        check(Files.isRegularFile(resolvedJar), "主 jar 真实存在（启动器不会因缺 jar 拒绝启动）");
        check(!Files.isRegularFile(shellDir.resolve(shellId + ".jar")), "壳目录里没有自带的 jar 副本");
        System.out.println("升级后壳目录: " + mb(directorySize(shellDir)));

        // 继承链仍然可解析
        check(resolved.getMainClass() != null && resolved.getMainClass().contains("GenesisLauncher"),
                "解析后的主类仍是插件入口包装类：" + resolved.getMainClass());

        // ---------- 3) 真实启动 ----------
        JsonObject lunar = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
        var arguments = lunar.getAsJsonObject("arguments");
        String assetsIndex = shell.has("assetIndex") && shell.get("assetIndex").isJsonObject()
                ? shell.getAsJsonObject("assetIndex").get("id").getAsString() : "1.8";

        // 版本 json 里不能留 GC 选择参数：启动器自己会加一套 G1，两个 GC 同时出现 JVM 直接拒绝启动
        // （Multiple garbage collectors selected）
        List<String> gcInJson = new ArrayList<>();
        for (var element : arguments.getAsJsonArray("jvm"))
            if (element.getAsString() != null && element.getAsString().matches("-XX:[+-]Use[A-Za-z0-9]*GC"))
                gcInJson.add(element.getAsString());
        System.out.println("版本 json 里的 GC 参数: " + gcInJson);
        check(gcInJson.isEmpty(), "版本 json 不再声明 GC（交给启动器选择）");

        Path workingDirectory = locateInstallation(arguments, gameDirectory, versionId);

        Map<String, String> configuration = new HashMap<>();
        configuration.put("${auth_player_name}", "Player");
        configuration.put("${auth_uuid}", "00000000000040008000000000000001");
        configuration.put("${auth_access_token}", "0");
        configuration.put("${auth_session}", "0");
        configuration.put("${user_type}", "msa");
        configuration.put("${user_properties}", "{}");
        configuration.put("${version_name}", versionId);
        configuration.put("${game_directory}", gameDirectory.toString());
        configuration.put("${assets_root}", gameDirectory.resolve("assets").toString());
        configuration.put("${assets_index_name}", assetsIndex);
        configuration.put("${resolution_width}", "854");
        configuration.put("${resolution_height}", "480");
        configuration.put("${library_directory}", gameDirectory.resolve("libraries").toString());
        configuration.put("${classpath_separator}", File.pathSeparator);
        Map<String, Boolean> features = Map.of("has_custom_resolution", true);

        // 与 DefaultLauncher 一致：${classpath} = 版本的库 + 该版本的主 jar
        var launcherClasspath = repository.getClasspath(resolved);
        launcherClasspath.add(resolvedJar.toAbsolutePath().toString());
        String classpathValue = String.join(File.pathSeparator, launcherClasspath);

        List<String> command = new ArrayList<>();
        command.add(java);
        // 启动器会先注入自己的默认 JVM 参数（DefaultLauncher 里那套 G1 优化）。这里照做，
        // 否则「版本 json 自带 GC + 启动器再塞一个 GC」这类冲突在本测试里复现不出来。
        command.add("-XX:+UnlockExperimentalVMOptions");
        command.add("-XX:+UnlockDiagnosticVMOptions");
        command.add("-XX:+UseG1GC");
        command.add("-XX:G1MixedGCCountTarget=5");
        command.add("-XX:G1NewSizePercent=20");
        command.add("-XX:G1ReservePercent=20");
        command.add("-XX:MaxGCPauseMillis=50");
        command.add("-XX:G1HeapRegionSize=32m");
        for (var element : arguments.getAsJsonArray("jvm"))
            command.add(element.getAsString().replace("${classpath}", classpathValue));
        command.add(lunar.get("mainClass").getAsString());
        if (resolved.getMinecraftArguments().isPresent())
            command.addAll(org.jackhuang.hmcl.game.Arguments.parseStringArguments(
                    org.jackhuang.hmcl.util.StringUtils.tokenize(
                            resolved.getMinecraftArguments().orElse("")), configuration));
        resolved.getArguments().ifPresent(value -> command.addAll(
                org.jackhuang.hmcl.game.Arguments.parseArguments(value.game(), configuration, features)));
        if (resolved.getMinecraftArguments().isPresent())
            command.addAll(org.jackhuang.hmcl.game.Arguments.parseArguments(
                    org.jackhuang.hmcl.game.Arguments.DEFAULT_GAME_ARGUMENTS, configuration, features));

        System.out.println("工作目录: " + workingDirectory);
        check(command.contains(classpathValue), "命令行里带上了 ${classpath}");
        for (String option : new String[]{"--version", "--classpathDir", "--ichorClassPath", "--ichorExternalFiles"})
            check(command.stream().filter(option::equals).count() == 1, "命令行里 " + option + " 恰好出现一次");

        launchAndWait(command, workingDirectory);

        System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    /// 客户端安装目录：优先取版本 json 里的 `--classpathDir`，否则按版本号推断。
    private static Path locateInstallation(com.google.gson.JsonObject arguments, Path gameDirectory, String versionId) {
        if (arguments != null && arguments.has("game") && arguments.get("game").isJsonArray()) {
            var game = arguments.getAsJsonArray("game");
            for (int i = 0; i + 1 < game.size(); i++)
                if ("--classpathDir".equals(game.get(i).getAsString()))
                    return Path.of(game.get(i + 1).getAsString());
        }
        String gameVersion = versionId.startsWith("Lunar-") ? versionId.substring("Lunar-".length()) : versionId;
        return gameDirectory.resolve("lunar-client").resolve(gameVersion);
    }

    /// 启动并等待 LUNARCLIENT_STATUS_STARTED。
    private static void launchAndWait(List<String> command, Path workingDirectory) throws Exception {
        long timeout = Long.getLong("lunar.launch.timeout.ms", 180_000L);
        System.out.println("启动参数个数: " + command.size() + "，开始启动（最多观察 "
                + (timeout / 1000) + " 秒）…");
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).start();
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<String>());
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null)
                    lines.add(line);
            } catch (Exception ignored) {
                // 进程结束时流关闭
            }
        });
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + timeout;
        boolean started = false;
        int reported = 0;
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            Thread.sleep(1000);
            // 快照后再遍历：读取线程仍在往里 add，直接 stream 会 ConcurrentModificationException
            List<String> current = new ArrayList<>(lines);
            if (current.stream().anyMatch(line -> line.contains("LUNARCLIENT_STATUS_STARTED"))) {
                started = true;
                break;
            }
            // 让长时间初始化过程可见
            int size = current.size();
            if (size >= reported + 200) {
                reported = size;
                System.out.println("  … 客户端已输出 " + size + " 行，仍在初始化");
            }
        }
        System.out.println("--- 客户端输出（末 25 行）---");
        List<String> snapshot = new ArrayList<>(lines);
        for (String line : snapshot.subList(Math.max(0, snapshot.size() - 25), snapshot.size()))
            System.out.println(line.substring(0, Math.min(200, line.length())));
        check(started, "Lunar 客户端启动完成（LUNARCLIENT_STATUS_STARTED）");
        check(process.isAlive(), "客户端进程持续运行");

        process.destroy();
        Thread.sleep(3000);
        if (process.isAlive())
            process.destroyForcibly();
    }

    private static String javaExecutable() {
        Path jdk17 = Path.of("E:/jdk-17.0.13/bin/java.exe");
        if (Files.isRegularFile(jdk17))
            return jdk17.toString();
        String home = System.getenv("JAVA_HOME");
        return home != null
                ? Path.of(home, "bin", "java.exe").toString()
                : Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }

    /// 是否还有版本继承自这个壳版本。
    private static boolean isReferenced(Path gameDirectory, String shellId) {
        for (String versionId : listLunarVersions(gameDirectory)) {
            Path json = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
            try {
                JsonObject version = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (version.has("inheritsFrom") && shellId.equals(version.get("inheritsFrom").getAsString()))
                    return true;
            } catch (Exception ignored) {
                // 读不出来的版本 json 不算引用
            }
        }
        return false;
    }

    /// 游戏目录里所有 `Lunar-*` 版本。
    private static List<String> listLunarVersions(Path gameDirectory) {
        Path versions = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versions))
            return List.of();
        try (var stream = Files.list(versions)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("Lunar-"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /// 读取版本 json 里的 `javaVersion` 节点。
    private static JsonObject readJavaVersion(Path gameDirectory, String versionId) {
        try {
            Path json = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
            if (!Files.isRegularFile(json))
                return null;
            JsonObject version = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return version.has("javaVersion") && version.get("javaVersion").isJsonObject()
                    ? version.getAsJsonObject("javaVersion") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /// 所有壳版本目录占用的总空间。
    private static long shellsSize(Path gameDirectory) {        long total = 0;
        for (String shell : listShells(gameDirectory))
            total += directorySize(gameDirectory.resolve("versions").resolve(shell));
        return total;
    }

    /// 游戏目录里的所有 `*-lunar-base` 壳版本。
    private static List<String> listShells(Path gameDirectory) {
        Path versions = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versions))
            return List.of();
        try (var stream = Files.list(versions)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("-lunar-base"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long directorySize(Path directory) {        if (!Files.isDirectory(directory))
            return 0;
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception e) {
                    return 0;
                }
            }).sum();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  PASS - " : "  FAIL - ") + message);
        if (!condition)
            failures++;
    }

    private static String mb(long bytes) {
        return String.format("%.2f MB", bytes / 1048576.0);
    }
}

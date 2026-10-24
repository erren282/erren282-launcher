package org.dsh.lunarofl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/// Lunar 启动失败的**自动诊断与修复**。
///
/// <p>把「已知症状 → 修法」固化成一张表（[#symptoms]），两处都用它：</p>
/// <ol>
///   <li><b>启动前</b>：对每个 Lunar 版本做一次体检（[LunarVersionInstaller#inspect]），有问题就
///       就地修掉（[LunarVersionInstaller#repair]）——用户不必知道细节，也不会再撞同一堵墙；</li>
///   <li><b>启动失败后</b>：读客户端输出与最新的崩溃报告，匹配到已知症状就再修一次，并把
///       「上次失败原因 + 已做的修复」记到数据目录里，界面上能直接看到。</li>
/// </ol>
///
/// <p>每修一类问题都会在这里加一条症状（正则是从真实崩溃日志里抄来的），所以这个类也是插件
/// 「踩过的坑」的清单。</p>
@NotNullByDefault
public final class LunarRepair {

    /// 一条已知故障：症状正则 → 给人看的说明与建议。
    public record Symptom(String id, String title, String advice, List<Pattern> patterns) {

        /// 这段文本（客户端输出或崩溃报告）是否命中该症状。
        public boolean matches(String text) {
            for (Pattern pattern : patterns)
                if (pattern.matcher(text).find())
                    return true;
            return false;
        }
    }

    /// 一次体检/修复的结果。
    ///
    /// @param versionId 版本 id
    /// @param symptom   命中的症状 id（启动前体检为 null）
    /// @param title     症状标题
    /// @param advice    建议
    /// @param problems  体检发现的问题
    /// @param actions   实际做过的修复
    /// @param time      时间戳（毫秒）
    public record Report(String versionId, @Nullable String symptom, String title, String advice,
                         List<String> problems, List<String> actions, long time) {

        /// 是否真的修了东西。
        public boolean repaired() {
            return !actions.isEmpty();
        }

        /// 一句话总结（日志与界面都用它）。
        public String summary() {
            StringBuilder builder = new StringBuilder(versionId);
            if (title != null && !title.isBlank())
                builder.append("：").append(title);
            if (!problems.isEmpty())
                builder.append("（体检 ").append(problems.size()).append(" 项问题）");
            if (!actions.isEmpty())
                builder.append(" → ").append(String.join("；", actions));
            return builder.toString();
        }
    }

    /// 已知故障表。新增条目时把真实日志里的报错原样写成正则，别凭印象写。
    private static final List<Symptom> SYMPTOMS = List.of(
            new Symptom("external-files", "外部文件没被 Genesis 认出来",
                    "Genesis 只认文件名（它拿 --ichorExternalFiles 与候选文件的 Path.getFileName() 等值比较），"
                            + "插件已把这一项归一成文件名。",
                    patterns("Could not find external file \\S+")),
            new Symptom("library-conflict", "Lunar 要的运行库被原版/旧库盖住了",
                    "插件已按 Lunar 自己的 versions.conf 补齐运行库，并排在 Lunar 的 jar 之后、原版库之前。",
                    patterns("NoSuchMethodError: .*(fastutil|log4j|netty|joml|slf4j|lwjgl|commons-io)",
                            "NoClassDefFoundError: (it/unimi/dsi/fastutil|org/apache/logging/log4j|org/joml|io/netty|org/slf4j)",
                            "AbstractMethodError: .*(fastutil|io/netty|logging/log4j)",
                            "IncompatibleClassChangeError: .*(fastutil|io/netty|logging/log4j)")),
            new Symptom("stale-classpath", "libraries 里的 classpath 副本与客户端目录不一致",
                    "Lunar 客户端会自我更新自己的 SNAPSHOT jar；插件已把 libraries 里的副本重新对齐，"
                            + "不用重装。",
                    patterns("@Mixin target .* was not found",
                            "Mixin apply failed",
                            "MixinTransformerError",
                            "org\\.spongepowered\\.asm\\.mixin\\..*Exception")),
            new Symptom("java-version", "用的 Java 版本不对",
                    "Lunar 需要 Java 17+（新版要 21/25）；插件已把合适的 javaVersion 写进版本 json。",
                    patterns("Unrecognized option: --add-modules",
                            "UnsupportedClassVersionError",
                            "requires at least Java \\d+",
                            "depends on java >=")),
            new Symptom("gc-conflict", "GC 参数冲突",
                    "Lunar 清单自带的 GC 选项与启动器的 GC 参数冲突（Multiple garbage collectors selected），"
                            + "插件已把它移除。",
                    patterns("Multiple garbage collectors selected")),
            new Symptom("vanilla-base", "基础版本不是原版",
                    "Fabric/Forge 版会把官方 fabric-loader 带上 classpath，与 Lunar 打过补丁的版本冲突；"
                            + "插件已把 inheritsFrom 改指原版副本。",
                    patterns("cannot inherit from final class FabricLoaderImpl",
                            "IncompatibleClassChangeError: .*FabricLoaderImpl")),
            new Symptom("missing-classpath", "JVM 没拿到 classpath",
                    "版本 json 少了 -cp ${classpath}，JVM 找不到 Genesis；插件已补上。",
                    patterns("ClassNotFoundException: com\\.moonsworth\\.lunar\\.genesis",
                            "NoClassDefFoundError: com/moonsworth/lunar/genesis")),
            new Symptom("broken-client-jar", "客户端 jar 下载不完整",
                    "zip 结构损坏，插件补不回坏文件：请在下载页对 Lunar 版本点一次「重新下载」。",
                    patterns("zip END header not found",
                            "invalid LOC header",
                            "error in opening zip file",
                            "zip file is empty")),
            new Symptom("no-ichor-modules", "ichor 找不到模块 jar",
                    "客户端目录的模块 jar 缺失或被占用；插件会重新对齐 classpath 副本，"
                            + "仍失败时请关闭正在运行的 Lunar 客户端后重试。",
                    patterns("Couldn't find any IchorModules on the classpath")));

    /// 上次失败记录的文件名（放在插件数据目录里）。
    private static final String STATE_FILE = "lunar-repair.json";

    private LunarRepair() {
    }

    /// 估算这个版本启动时的 classpath 字符数（Windows 命令行上限 32767，留点余量就该报警）。
    ///
    /// <p>超长的典型症状是启动器弹「启动失败 … CreateProcess error=206, 文件名或扩展名太长」——
    /// 那是**命令行太长**，不是 Java 路径错。真出现时按 [`LunarLibraries`] 的顺序精简库即可。</p>
    public static int classpathLength(org.jackhuang.hmcl.game.DefaultGameRepository repository, String versionId) {
        try {
            org.jackhuang.hmcl.game.Version resolved = repository.getResolvedVersion(versionId);
            int length = 0;
            for (String entry : repository.getClasspath(resolved))
                length += entry.length() + 1;
            length += repository.getVersionJar(resolved).toAbsolutePath().toString().length();
            return length;
        } catch (Throwable t) {
            return 0;
        }
    }

    /// 已知故障表（界面用来展示「能修什么」）。
    public static List<Symptom> symptoms() {
        return SYMPTOMS;
    }

    /// 对游戏目录里所有 `Lunar-*` 版本做体检 + 修复。
    ///
    /// @return 真正修过东西的那些报告（没问题的版本不会出现在结果里）
    public static List<Report> repairAll(Path gameDirectory) {
        List<Report> reports = new ArrayList<>();
        Path versionsDirectory = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versionsDirectory))
            return reports;

        List<Path> directories;
        try (var stream = Files.list(versionsDirectory)) {
            directories = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            Logger.LOG.warning("扫描已安装 Lunar 版本失败：" + e);
            return reports;
        }

        for (Path directory : directories) {
            Path name = directory.getFileName();
            if (name == null || !name.toString().startsWith("Lunar-"))
                continue;
            Report report = preflight(gameDirectory, name.toString());
            if (report != null && report.repaired())
                reports.add(report);
        }
        return reports;
    }

    /// 让启动器重新读一遍 `versions/`，把插件刚改过的版本显示出来。
    ///
    /// <p>插件启动时的那串修复（升级壳版本、修 Java/GC/外部文件名、对齐 classpath 副本、补运行库）
    /// 是在启动器**已经加载完版本列表之后**才写盘的。只要这一轮动过磁盘，内存里的列表就是旧的：
    /// 用户会看到「第一次进入版本列表没有版本，手动点一次刷新才出现」，日志里对应的正是
    /// `ignores version Lunar-x because it inherits from a nonexistent version`（壳版本当时还没写好）
    /// 或版本 json 刚被改写。刷一次的成本只是重读一遍 `versions/`，比让用户手动点刷新划算得多，
    /// 所以启动修复流程结束后**无条件**调用它。</p>
    public static void refreshVersionList(org.jackhuang.hmcl.game.HMCLGameRepository repository) {
        try {
            repository.refreshVersionsAsync().start();
            Logger.LOG.info("已通知启动器刷新版本列表（插件刚修过 versions/）");
        } catch (Throwable t) {
            Logger.LOG.warning("刷新版本列表失败：" + t);
        }
    }

    /// 全量修复，并在真的动过磁盘之后让启动器**自动刷新版本列表**。
    ///
    /// <p>「Lunar 启动修复」页的手动全面修复用它：没修东西就不用刷新。插件启动时的那串修复
    /// 用的是 [#refreshVersionList]，因为那时前面几步（升级壳版本、对齐 classpath）可能已经改过磁盘，
    /// 而最后一步未必还有问题可修。</p>
    ///
    /// @return 真正修过的报告（空表示什么都没改，也就不会刷新）
    public static List<Report> repairAllAndRefresh(org.jackhuang.hmcl.game.HMCLGameRepository repository) {
        List<Report> reports = repairAll(repository.getBaseDirectory());
        if (!reports.isEmpty())
            refreshVersionList(repository);
        return reports;
    }

    /// 启动前对单个版本体检并修复。
    ///
    /// @return 需要修复时的报告；本来就正常则返回 null
    public static @Nullable Report preflight(Path gameDirectory, String versionId) {
        List<String> problems = LunarVersionInstaller.inspect(gameDirectory, versionId);
        if (problems.isEmpty())
            return null;

        Logger.LOG.info("Lunar 版本 " + versionId + " 体检发现 " + problems.size() + " 项问题："
                + String.join("；", problems));
        List<String> actions = LunarVersionInstaller.repair(gameDirectory, versionId);
        Report report = new Report(versionId, null, "启动前体检", "", List.copyOf(problems),
                List.copyOf(actions), System.currentTimeMillis());
        if (report.repaired())
            Logger.LOG.info("已自动修复 " + report.summary());
        return report;
    }

    /// 启动失败后：用客户端输出与最新的崩溃报告匹配已知症状，命中就再修一次并记录下来。
    ///
    /// @param output 客户端进程的输出（[org.jackhuang.hmcl.util.platform.ManagedProcess#getLines]）
    /// @return 诊断报告；没匹配到已知症状时也会返回（symptom 为 null），便于界面显示「未知故障」
    public static Report afterFailure(Path gameDirectory, String versionId, List<String> output) {
        String text = String.join("\n", output) + "\n" + newestCrashReport(gameDirectory);

        Symptom hit = null;
        for (Symptom symptom : SYMPTOMS)
            if (symptom.matches(text)) {
                hit = symptom;
                break;
            }

        List<String> problems = LunarVersionInstaller.inspect(gameDirectory, versionId);
        List<String> actions = problems.isEmpty() ? List.of()
                : LunarVersionInstaller.repair(gameDirectory, versionId);

        Report report = new Report(versionId,
                hit == null ? null : hit.id(),
                hit == null ? "未识别的启动失败" : hit.title(),
                hit == null ? "如果是新问题，请把崩溃报告发给插件作者（crash-reports 目录里的文件）。" : hit.advice(),
                List.copyOf(problems), List.copyOf(actions), System.currentTimeMillis());

        if (hit != null)
            Logger.LOG.info("识别到已知启动失败：" + hit.title());
        if (!actions.isEmpty())
            Logger.LOG.info("已按症状自动修复：" + String.join("；", actions));
        remember(report);
        return report;
    }

    /// 上次失败/修复记录（界面用）。
    public static @Nullable Report last() {
        Path file = stateFile();
        if (file == null || !Files.isRegularFile(file))
            return null;
        try {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return new Report(string(object, "versionId"), nullableString(object, "symptom"),
                    string(object, "title"), string(object, "advice"),
                    stringList(object, "problems"), stringList(object, "actions"), number(object, "time"));
        } catch (Exception e) {
            Logger.LOG.warning("读取上次修复记录失败：" + e);
            return null;
        }
    }

    /// 把报告写到数据目录（界面下次启动也能看到）。
    private static void remember(Report report) {
        Path file = stateFile();
        if (file == null)
            return;
        try {
            JsonObject object = new JsonObject();
            object.addProperty("versionId", report.versionId());
            if (report.symptom() != null)
                object.addProperty("symptom", report.symptom());
            object.addProperty("title", report.title());
            object.addProperty("advice", report.advice());
            object.addProperty("time", report.time());
            var problems = new com.google.gson.JsonArray();
            for (String problem : report.problems())
                problems.add(problem);
            var actions = new com.google.gson.JsonArray();
            for (String action : report.actions())
                actions.add(action);
            object.add("problems", problems);
            object.add("actions", actions);

            Files.createDirectories(file.getParent());
            Files.writeString(file, object.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Logger.LOG.warning("写入修复记录失败：" + e);
        }
    }

    private static @Nullable Path stateFile() {
        Path dataDirectory = LunarOfflinePlugin.dataDirectory();
        return dataDirectory == null ? null : dataDirectory.resolve(STATE_FILE);
    }

    /// 最新的崩溃报告内容（`crash-reports` 里最近修改的那个；太旧就不看了）。
    private static String newestCrashReport(Path gameDirectory) {
        Path directory = gameDirectory.resolve("crash-reports");
        if (!Files.isDirectory(directory))
            return "";
        try (var stream = Files.list(directory)) {
            List<Path> reports = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong(LunarRepair::lastModified).reversed())
                    .limit(1)
                    .toList();
            if (reports.isEmpty())
                return "";
            Path report = reports.get(0);
            // 只认 10 分钟内写的报告：老崩溃与这次启动无关，拿它匹配会误修
            if (System.currentTimeMillis() - lastModified(report) > 10 * 60 * 1000L)
                return "";
            return Files.readString(report, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Logger.LOG.warning("读取崩溃报告失败：" + e);
            return "";
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /// 把若干正则编译成列表。
    private static List<Pattern> patterns(String... regex) {
        List<Pattern> result = new ArrayList<>();
        for (String value : regex)
            result.add(Pattern.compile(value, Pattern.CASE_INSENSITIVE));
        return List.copyOf(result);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static @Nullable String nullableString(JsonObject object, String key) {
        String value = string(object, key);
        return value.isEmpty() ? null : value;
    }

    private static long number(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static List<String> stringList(JsonObject object, String key) {
        List<String> result = new ArrayList<>();
        if (object.has(key) && object.get(key).isJsonArray())
            for (var element : object.getAsJsonArray(key))
                if (element.isJsonPrimitive())
                    result.add(element.getAsString());
        return List.copyOf(result);
    }
}

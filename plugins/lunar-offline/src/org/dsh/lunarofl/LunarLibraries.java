package org.dsh.lunarofl;

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/// Lunar 客户端**自己要求**的运行库清单（`versions.conf` 里的 `dependencies`）。
///
/// <p>为什么必须有它：Lunar 的版本 json 继承原版游戏版本，HMCL 于是把**原版那一套库**放进
/// classpath（1.12.2 是 fastutil 7.1.0、netty 4.1.9.Final、log4j 2.8.1、jna 4.4.0 …），
/// 而 Lunar 的代码是按它自己那一套编译的（fastutil 8.5.6、netty 4.1.16.Final、log4j 2.22.1、
/// jna 5.13.0 …）。原版库排在前面，于是启动直接崩在
/// `NoSuchMethodError: 'void it.unimi.dsi.fastutil.ints.IntArraySet.&lt;init&gt;(it.unimi.dsi.fastutil.ints.IntSet)'`
/// 这类地方。官方启动器的 classpath 只有 Lunar 自己那套库，本类把同一套库下载到客户端目录的
/// `lunar-libs/`，再由 [LunarVersionInstaller] 装进 HMCL 的 `libraries/` 并**排在原版库前面**
/// （HMCL 合并继承链时子版本在前、且不去重，所以同名库由我们提供的版本生效）。</p>
///
/// <p>清单来源是客户端目录里 `genesis-*.jar` 内的 `versions.conf`（Lunar 自己的版本配置），
/// 所以每个游戏版本拿到的都是它对应的那一套库，不用在插件里维护版本表。</p>
@NotNullByDefault
public final class LunarLibraries {

    /// 运行库落地的子目录（在客户端安装目录里）。
    public static final String DIRECTORY = "lunar-libs";

    /// 按顺序尝试的 maven 仓库：Mojang 的库站镜像了大部分中央仓库内容，
    /// 但缺少 slf4j 之类；`com.mojang:*`、`com.paulscode:*`、`oshi-project:*` 只有它有。
    private static final List<String> REPOSITORIES = List.of(
            "https://libraries.minecraft.net/",
            "https://repo1.maven.org/maven2/",
            "https://bmclapi2.bangbang93.com/maven/");

    /// 与当前系统无关的库：`java-objc-bridge` 只用于 macOS。
    private static final List<String> OTHER_PLATFORM_ARTIFACTS = List.of("java-objc-bridge");

    private LunarLibraries() {
    }

    /// 客户端目录里 `lunar-libs/` 下已有的运行库（按文件名排序）。
    public static List<String> installed(Path installation) {
        Path directory = installation.resolve(DIRECTORY);
        if (!Files.isDirectory(directory))
            return List.of();

        List<String> result = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            for (Path jar : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList())
                result.add(jar.toAbsolutePath().toString());
        } catch (IOException e) {
            Logger.LOG.warning("读取 Lunar 运行库目录失败：" + e);
        }
        return result;
    }

    /// 读出这个游戏版本声明的运行库坐标（`group:artifact:version[:classifier]`）。
    ///
    /// @param clientDirectory 客户端安装目录（里面应有 `genesis-*.jar`）
    /// @return 适用于当前系统的坐标；读不到配置时返回空表
    public static List<String> declared(Path clientDirectory, String gameVersion) {
        String config = readVersionsConf(clientDirectory);
        if (config == null) {
            Logger.LOG.warning("客户端目录里找不到 genesis jar，无法读取 Lunar 运行库清单");
            return List.of();
        }

        List<String> coordinates = new ArrayList<>(coordinates(arrayAfter(config, "commonDependencies")));
        coordinates.addAll(versionCoordinates(config, gameVersion));

        List<String> applicable = new ArrayList<>();
        for (String coordinate : coordinates)
            if (appliesToThisPlatform(coordinate) && !applicable.contains(coordinate))
                applicable.add(coordinate);
        Logger.LOG.info("Lunar 运行库清单：" + applicable.size() + " 项（原始 " + coordinates.size() + " 项）");
        return applicable;
    }

    /// 下载缺失的运行库到 `&lt;installation&gt;/lunar-libs/`。
    ///
    /// <p>已经存在且非空的文件直接跳过（maven 仓库不保证给出可校验的 sha1，这里只保证下载完整：
    /// 先写 `.part` 再改名）。某个坐标在所有仓库都拿不到时只记告警——它可能只是别的平台才需要的库，
    /// 不该让整次安装失败。</p>
    ///
    /// <p><b>并行下载：</b>一次安装要补三四十个库，串行下载时每个都要走「建连 → 拉取 → 换仓库重试」，
    /// 光 TLS 握手就能占掉大半时间（实测 30+ 个库串行要几十秒）。这里用线程池并发拉取，
    /// 并且把「仓库候选项」按**已知可用性**排序，命中过的仓库优先尝试，失败重试也更快。</p>
    ///
    /// @return 实际可用的 jar 路径（含此前就存在的）
    public static List<Path> download(Path installation, List<String> coordinates, BooleanSupplier cancelled) {
        return download(installation, coordinates, cancelled, null);
    }

    /// 带进度回调的并行下载。
    ///
    /// @param progress 可选回调：`(已完成数量, 总数量)`，用于在启动器原生下载弹窗里显示进度
    public static List<Path> download(Path installation, List<String> coordinates, BooleanSupplier cancelled,
                                      @Nullable java.util.function.BiConsumer<Integer, Integer> progress) {
        Path directory = installation.resolve(DIRECTORY);
        List<Path> result = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            Logger.LOG.warning("创建 Lunar 运行库目录失败：" + e);
            return result;
        }

        // 先挑出「已存在」的，剩下的才需要联网
        List<String> pending = new ArrayList<>();
        for (String coordinate : coordinates) {
            Path target = directory.resolve(jarName(coordinate));
            try {
                if (Files.isRegularFile(target) && Files.size(target) > 0) {
                    result.add(target);
                    continue;
                }
            } catch (IOException ignored) {
                // 读不到大小就当它不存在，下面重新下载
            }
            pending.add(coordinate);
        }

        int total = coordinates.size();
        int alreadyDone = result.size();
        if (progress != null)
            progress.accept(alreadyDone, total);
        if (pending.isEmpty())
            return result;

        // 并发拉取。线程数取「本机 CPU 与库数量」的较小值再压到 8：
        // 库都不大（几十 KB ~ 几 MB），并发太高反而被 CDN 限速。
        int threads = Math.max(1, Math.min(8, Math.min(pending.size(), Runtime.getRuntime().availableProcessors() * 2)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(alreadyDone);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(pending.size());
            for (String coordinate : pending) {
                futures.add(pool.submit(() -> {
                    if (cancelled.getAsBoolean())
                        throw new CancellationException("用户取消了下载");
                    Path target = directory.resolve(jarName(coordinate));
                    if (fetch(coordinate, target, cancelled)) {
                        result.add(target);
                    } else {
                        Logger.LOG.warning("Lunar 运行库下载失败（可能是其它平台专用）：" + coordinate);
                    }
                    if (progress != null)
                        progress.accept(completed.incrementAndGet(), total);
                    return null;
                }));
            }
            // 收集结果的同时让取消能尽快生效
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancellationException cancellation)
                        throw cancellation;
                    if (cause instanceof RuntimeException runtime)
                        throw runtime;
                    Logger.LOG.warning("下载 Lunar 运行库出错：" + cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("下载被中断");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    /// 依次尝试各个仓库下载一个坐标。
    ///
    /// <p>顺序不是固定的：每个仓库有「健康度」记忆（见 [REPOSITORY_HEALTH]）。命中过的仓库会被提到
    /// 前面，连续失败的仓库直接跳过——串行下载时这是次要开销，但三四十个库并行拉取时，
    /// 每少一次「必然 404 的探测」就少一次完整的 TLS 往返。</p>
    private static boolean fetch(String coordinate, Path target, BooleanSupplier cancelled) {
        String path = repositoryPath(coordinate);
        for (String repository : orderedRepositories()) {
            if (cancelled.getAsBoolean())
                throw new CancellationException("用户取消了下载");

            Path temporary = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = LunarApi.openStream(repository + path)) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (Files.size(temporary) <= 0) {
                    Files.deleteIfExists(temporary);
                    markFailure(repository);
                    continue;
                }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                markSuccess(repository);
                return true;
            } catch (IOException e) {
                markFailure(repository);
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 清理失败不影响结果
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("下载被中断");
            }
        }
        return false;
    }

    /// 仓库健康度：`仓库地址 → 连续失败次数`。命中一次就清零。
    ///
    /// <p>`libraries.minecraft.net` 对很多第三方坐标直接 404，而 `repo1.maven.org` 又缺
    /// `com.mojang:*`。先试谁不该是写死的：让实际情况决定。某个仓库连续失败 3 次后，
    /// 后续坐标就把它排到最后，不再消耗一次往返。</p>
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> REPOSITORY_HEALTH =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void markSuccess(String repository) {
        REPOSITORY_HEALTH.put(repository, 0);
    }

    private static void markFailure(String repository) {
        REPOSITORY_HEALTH.merge(repository, 1, Integer::sum);
    }

    /// 按健康度排序后的仓库列表：健康的在前，连续失败的沉底（但不剔除——万一前面全挂了还能兜住）。
    private static List<String> orderedRepositories() {
        List<String> ordered = new ArrayList<>(REPOSITORIES);
        ordered.sort((a, b) -> Integer.compare(
                REPOSITORY_HEALTH.getOrDefault(a, 0), REPOSITORY_HEALTH.getOrDefault(b, 0)));
        return ordered;
    }

    /// 坐标 → 仓库里的相对路径。
    private static String repositoryPath(String coordinate) {
        List<String> parts = List.of(coordinate.split(":"));
        String group = parts.get(0).replace('.', '/');
        String artifact = parts.get(1);
        String version = parts.get(2);
        String classifier = parts.size() > 3 ? "-" + parts.get(3) : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    /// 坐标 → 落地文件名（也用作 HMCL 库坐标的 artifact 片段）。
    public static String jarName(String coordinate) {
        List<String> parts = List.of(coordinate.split(":"));
        String artifact = parts.get(1);
        String version = parts.get(2);
        String classifier = parts.size() > 3 ? "-" + parts.get(3) : "";
        return artifact + "-" + version + classifier + ".jar";
    }

    /// 这个坐标是否适用于当前系统：无 classifier 的都要，`natives-*` 只取本平台的，
    /// 另外丢掉明显只属于别的平台的库。
    private static boolean appliesToThisPlatform(String coordinate) {
        List<String> parts = List.of(coordinate.split(":"));
        if (parts.size() > 4 || parts.size() < 3)
            return false;
        if (OTHER_PLATFORM_ARTIFACTS.contains(parts.get(1)))
            return isMac();

        if (parts.size() == 4) {
            String classifier = parts.get(3);
            if (!classifier.startsWith("natives-"))
                return true;
            return classifier.equals(nativesClassifier());
        }
        return true;
    }

    /// 本平台的 natives classifier（Lunar 的清单里只用这三种不带架构的形式）。
    private static String nativesClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win"))
            return "natives-windows";
        if (os.contains("mac"))
            return "natives-macos";
        return "natives-linux";
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /// 从客户端目录里的 `genesis-*.jar` 读出 `versions.conf`。
    private static @Nullable String readVersionsConf(Path clientDirectory) {
        if (!Files.isDirectory(clientDirectory))
            return null;

        List<Path> jars;
        try (var stream = Files.list(clientDirectory)) {
            jars = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("genesis") && name.endsWith(".jar");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            Logger.LOG.warning("扫描客户端目录失败：" + e);
            return null;
        }

        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                ZipEntry entry = file.getEntry("versions.conf");
                if (entry != null)
                    return new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Logger.LOG.warning("读取 " + jar.getFileName() + " 里的 versions.conf 失败：" + e);
            }
        }
        return null;
    }

    /// 找出这个游戏版本的依赖数组内容。优先匹配 `exact-version`（1.12.2），
    /// 其次匹配 `major-version`（1.12），都没有就返回空。
    private static List<String> versionCoordinates(String text, String gameVersion) {
        String major = majorVersion(gameVersion);
        List<String> result = new ArrayList<>();
        for (String block : blocks(text)) {
            String exact = quotedValue(block, "exact-version");
            if (gameVersion.equals(exact)) {
                result.addAll(coordinates(arrayAfter(block, "dependencies")));
                return result; // 精确命中就到此为止
            }
            if (exact == null && major != null && major.equals(quotedValue(block, "major-version")))
                result.addAll(coordinates(arrayAfter(block, "dependencies")));
        }
        return result;
    }

    /// 从 "1.12.2" 得到 "1.12"。
    private static @Nullable String majorVersion(String gameVersion) {
        List<String> parts = List.of(gameVersion.split("\\."));
        return parts.size() >= 2 ? parts.get(0) + "." + parts.get(1) : null;
    }

    /// 顶层块（`v1_12: ${legacy} { ... }`）的正文，按出现顺序。
    ///
    /// <p>只认**行首无缩进**的块头：HOCON 里顶层的版本块都顶格写，嵌套块都有缩进。块头的 `{`
    /// 还必须不是 `${...}` 模板里的那个（`v1_12: ${legacy} {` 的第一个 `{` 是模板的）。</p>
    private static List<String> blocks(String text) {
        List<String> result = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int lineEnd = text.indexOf('\n', index);
            if (lineEnd < 0)
                break;
            String line = text.substring(index, lineEnd);
            String stripped = line.strip();
            boolean indented = line.isEmpty() || Character.isWhitespace(line.charAt(0));
            int brace = indented ? -1 : blockBrace(stripped);
            if (brace >= 0 && stripped.indexOf(':') > 0 && stripped.indexOf(':') < brace
                    && !stripped.startsWith("#") && !stripped.startsWith("\"")) {
                int end = balancedEnd(text, index + brace, '{', '}');
                if (end > 0) {
                    result.add(text.substring(index, end + 1));
                    index = end + 1;
                    continue;
                }
            }
            index = lineEnd + 1;
        }
        return result;
    }

    /// 行内第一个**不属于 `${...}` 模板**的 `{` 的位置。
    private static int blockBrace(String line) {
        for (int index = 0; index < line.length(); index++) {
            if (line.startsWith("${", index)) {
                int close = line.indexOf('}', index);
                if (close < 0)
                    return -1;
                index = close;
                continue;
            }
            if (line.charAt(index) == '{')
                return index;
        }
        return -1;
    }

    /// 取出 `key` 后面的数组正文（`key: ${x} [ ... ]` 或 `key: [ ... ]`）。
    private static @Nullable String arrayAfter(String text, String key) {
        int keyIndex = indexOfKey(text, key);
        if (keyIndex < 0)
            return null;
        int start = text.indexOf('[', keyIndex);
        if (start < 0)
            return null;
        int end = balancedEnd(text, start, '[', ']');
        return end < 0 ? null : text.substring(start + 1, end);
    }

    /// 取出 `key: "value"` 里的 value。
    private static @Nullable String quotedValue(String text, String key) {
        int keyIndex = indexOfKey(text, key);
        if (keyIndex < 0)
            return null;
        int start = text.indexOf('"', keyIndex);
        int end = start < 0 ? -1 : text.indexOf('"', start + 1);
        return end < 0 ? null : text.substring(start + 1, end);
    }

    /// 找到 `key` 作为独立标识符出现的位置（避免匹配到 `xxx-key` 之类）。
    private static int indexOfKey(String text, String key) {
        int from = 0;
        while (true) {
            int index = text.indexOf(key, from);
            if (index < 0)
                return -1;
            boolean beforeOk = index == 0 || !isIdentifierChar(text.charAt(index - 1));
            int after = index + key.length();
            boolean afterOk = after >= text.length() || !isIdentifierChar(text.charAt(after));
            if (beforeOk && afterOk)
                return index;
            from = index + key.length();
        }
    }

    private static boolean isIdentifierChar(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.';
    }

    /// 从 `open` 指定的括号开始，返回与之配对的收尾括号下标（跳过字符串里的括号）。
    private static int balancedEnd(String text, int open, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        for (int index = open; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (character == '\\')
                    index++;
                else if (character == '"')
                    inString = false;
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == openChar) {
                depth++;
            } else if (character == closeChar) {
                depth--;
                if (depth == 0)
                    return index;
            }
        }
        return -1;
    }

    /// 从一段文本里取出所有形如 `group:artifact:version[:classifier]` 的坐标。
    private static List<String> coordinates(@Nullable String text) {
        List<String> result = new ArrayList<>();
        if (text == null)
            return result;

        for (String quoted : quotedStrings(text)) {
            if (isCoordinate(quoted) && !result.contains(quoted))
                result.add(quoted);
        }
        return result;
    }

    private static List<String> quotedStrings(String text) {
        List<String> result = new ArrayList<>();
        int index = 0;
        while (true) {
            int start = text.indexOf('"', index);
            if (start < 0)
                return result;
            int end = text.indexOf('"', start + 1);
            if (end < 0)
                return result;
            result.add(text.substring(start + 1, end));
            index = end + 1;
        }
    }

    /// `group:artifact:version` 或 `group:artifact:version:classifier`，且各段都非空。
    private static boolean isCoordinate(String value) {
        List<String> parts = List.of(value.split(":"));
        if (parts.size() < 3 || parts.size() > 4)
            return false;
        for (String part : parts)
            if (part.isBlank() || part.contains("/") || part.contains(" ") || part.contains("$"))
                return false;
        return true;
    }
}

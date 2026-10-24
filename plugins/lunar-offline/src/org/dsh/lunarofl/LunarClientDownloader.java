package org.dsh.lunarofl;

import org.jackhuang.hmcl.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// 把 Lunar 官方的工件清单落成一个可以启动的客户端目录。
///
/// <p>目录结构与官方启动器/Celestial 一致，因此 `LunarVersionInstaller` 生成的启动命令可以直接用：</p>
/// <pre>
/// &lt;installation&gt;/
///   &lt;classpath jars&gt;        --ichorClassPath
///   &lt;外部文件&gt;              --ichorExternalFiles（OptiFine、配置 json …）
///   &lt;native 压缩包&gt;
///   natives/                 --webosrDir、-Djava.library.path
///   textures/                --texturesDir
///   ui/                      --uiDir
///   licenses/
/// </pre>
///
/// <p><b>`--ichorExternalFiles` 里的每一项都必须是文件名，不能是绝对路径。</b>
/// Genesis 把这组字符串当作「本次启动允许使用的外部文件」清单存进 Ichor 描述符
/// （`ClientGameBootstrap` 里的 `List.of(ichorExternalFiles)`），等某个模块按名字找外部文件时，
/// 它会扫描 `--classpathDir`、并用 `清单.contains(候选文件.getFileName())` 过滤：
/// 绝对路径和文件名永远不相等，于是扫描结果为空，模块就会抛
/// `IllegalStateException: Could not find external file &lt;模块名&gt;`。</p>
///
/// <p>1.12.2-forge 的 ReplayMod 正好踩中这一点：它的外部文件按前缀
/// （`"ReplayMod-" + 版本 id`）查找，不走 `.jar` 精确路径那条分支，所以它是唯一会因此崩溃的模块；
/// Forge / OptiFine 因为声明的是 `<名字>_<版本>.jar`，走的是 `classpathDir.resolve(名字)`，
/// 才没有暴露这个问题。官方 Celestial 传的也是 `File.getName()`。</p>
public final class LunarClientDownloader {

    /// 进度回调（在工作线程里被调用）。
    @FunctionalInterface
    public interface Progress {
        void update(String step, long done, long total);
    }

    /// 下载结果。
    public record Installed(Path installation, LunarVersionInstaller.LaunchData launchData,
                            int files, long bytes, long skipped) {
    }

    private static final int PARALLELISM = 6;

    private LunarClientDownloader() {
    }

    /// 下载并解压「游戏版本 + 模块」的全部工件。
    ///
    /// @param installation 客户端安装目录（每个游戏版本一个，例如 `&lt;游戏目录&gt;/lunar-client/1.8.9`）
    public static Installed download(Path installation, LunarApi api, String gameVersion, String module,
                                     Progress progress, BooleanSupplier cancelled) throws Exception {
        progress.update("正在获取版本清单", 0, 1);
        LunarApi.Manifest manifest = api.manifest(gameVersion, module);
        Files.createDirectories(installation);

        // 贴图与 UI 的索引是纯文本清单：每行 "相对路径 sha1 大小 mtime"
        List<IndexEntry> textures = readIndex(manifest.texturesIndexUrl());
        List<IndexEntry> uiAssets = readIndex(manifest.uiAssetsIndexUrl());

        List<LunarApi.Artifact> artifacts = manifest.artifacts();
        long total = 0;
        for (LunarApi.Artifact artifact : artifacts)
            total += Math.max(0, artifact.size());
        for (LunarApi.Artifact license : manifest.licenses())
            total += Math.max(0, license.size());
        for (IndexEntry entry : textures)
            total += Math.max(0, entry.size());
        for (IndexEntry entry : uiAssets)
            total += Math.max(0, entry.size());

        AtomicLong done = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        int[] files = {0};
        // 进度上报的节流状态，整个下载过程共用：所有阶段都按同一节奏把进度
        // 推给界面（见 runAll / downloadOnce 的说明）。
        AtomicLong lastReport = new AtomicLong();
        long reportInterval = 100;

        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            // 1) classpath / 外部文件 / native 压缩包 / 许可证
            List<DownloadTask> first = new ArrayList<>();
            for (LunarApi.Artifact artifact : artifacts)
                first.add(new DownloadTask(artifact.url(), installation.resolve(safeName(artifact.name())),
                        artifact.sha1(), artifact.size()));
            for (LunarApi.Artifact license : manifest.licenses())
                first.add(new DownloadTask(license.url(), installation.resolve("licenses").resolve(safeName(license.name())),
                        license.sha1(), license.size()));
            runAll(pool, first, done, total, skipped, files, progress, "正在下载客户端文件",
                    cancelled, lastReport, reportInterval);

            // 2) native 压缩包 → natives/
            Path nativesDir = installation.resolve("natives");
            Files.createDirectories(nativesDir);
            for (LunarApi.Artifact artifact : manifest.natives()) {
                Path zip = installation.resolve(safeName(artifact.name()));
                if (Files.isRegularFile(zip)) {
                    progress.update("正在解压 " + artifact.name(), Math.min(done.get(), total), total);
                    extractZip(zip, nativesDir);
                }
            }

            // 3) 贴图与 UI 资源（数量多、体积小，并行下载）
            //    索引里的路径是分层的（assets/lunar/cosmetics/...），必须原样保留，
            //    否则不同目录下的同名文件会互相覆盖，Lunar 也找不到贴图。
            List<DownloadTask> second = new ArrayList<>();
            for (IndexEntry entry : textures) {
                if (!manifest.texturesBaseUrl().isEmpty() && !entry.sha1().isEmpty())
                    second.add(new DownloadTask(manifest.texturesBaseUrl() + entry.sha1(),
                            installation.resolve("textures").resolve(safeRelativePath(entry.path())),
                            entry.sha1(), entry.size()));
            }
            for (IndexEntry entry : uiAssets) {
                if (!manifest.uiAssetsBaseUrl().isEmpty() && !entry.sha1().isEmpty())
                    second.add(new DownloadTask(manifest.uiAssetsBaseUrl() + entry.sha1(),
                            installation.resolve("ui").resolve("assets").resolve(safeRelativePath(entry.path())),
                            entry.sha1(), entry.size()));
            }
            runAll(pool, second, done, total, skipped, files, progress, "正在下载贴图与界面资源",
                    cancelled, lastReport, reportInterval);

            // 4) UI 主程序（zip）
            if (!manifest.uiSourceUrl().isEmpty()) {
                Path uiZip = installation.resolve("ui.zip");
                long uiBytes = downloadTo(manifest.uiSourceUrl(), uiZip, manifest.uiSourceSha1(), 0,
                        cancelled, done, progress, "正在下载界面资源", total, lastReport, reportInterval);
                if (uiBytes > 0) {
                    total += uiBytes;
                    files[0]++;
                    progress.update("正在解压界面资源", Math.min(done.get(), total), total);
                }
                if (Files.isRegularFile(uiZip))
                    extractZip(uiZip, installation.resolve("ui"));
            }

            progress.update("下载完成", total, total);
            List<String> classpath = new ArrayList<>();
            for (LunarApi.Artifact artifact : manifest.classpath())
                classpath.add(installation.resolve(safeName(artifact.name())).toAbsolutePath().toString());
            // 外部文件只写文件名（相对 --classpathDir），不能写绝对路径：Genesis 会把这一项
            // 原样当成「允许的外部文件」清单，和目录里候选文件的 `Path.getFileName()` 做等值比较
            // （见 LunarClientDownloader 类注释）。官方 Celestial 同样是 `File.getName()`。
            List<String> ichorpath = new ArrayList<>();
            for (LunarApi.Artifact artifact : manifest.externalFiles())
                ichorpath.add(safeName(artifact.name()));
            if (classpath.isEmpty())
                throw new IOException("清单里没有 classpath，Lunar 服务端可能改动了接口");

            // 5) Lunar 自己声明的运行库（versions.conf 的 dependencies）。继承链带来的是**原版**
            //    那一套库（1.12.2 是 fastutil 7.1.0、netty 4.1.9、log4j 2.8.1 …），Lunar 的代码却按
            //    自己那一套编译（fastutil 8.5.6 …），所以这些库必须装进 libraries/ 并排在原版库前面。
            //
            //    这一步过去是**串行**下载三四十个库，每个都要建连 + 可能换仓库重试，是整个安装里
            //    最慢的一段（实测几十秒）。现在并行拉取，并把「已下载/总数」反馈到进度条上，
            //    让它不再是"卡住不动"的一段。
            final long librariesTotal = Math.max(total, done.get());
            progress.update("正在补齐 Lunar 运行库", Math.min(done.get(), librariesTotal), librariesTotal);
            List<String> libraries = new ArrayList<>();
            List<String> declared = LunarLibraries.declared(installation, manifest.gameVersion());
            int declaredCount = declared.size();
            for (Path jar : LunarLibraries.download(installation, declared, cancelled, (finished, count) -> {
                // 运行库不计入字节总量（清单里没有它们的大小），这里改用「个数」推进标题，
                // 但字节进度保持不动，避免总进度条倒退。
                progress.update("正在补齐 Lunar 运行库（" + finished + "/" + count + "）",
                        Math.min(done.get(), librariesTotal), librariesTotal);
            }))
                libraries.add(jar.toAbsolutePath().toString());
            Logger.LOG.info("Lunar 运行库就绪：" + libraries.size() + "/" + declaredCount);

            LunarVersionInstaller.LaunchData launchData = new LunarVersionInstaller.LaunchData(
                    installation.toAbsolutePath(),
                    manifest.mainClass(),
                    manifest.gameVersion(),
                    manifest.jvmArgs(),
                    classpath,
                    ichorpath,
                    libraries);
            return new Installed(installation.toAbsolutePath(), launchData, files[0], done.get(), skipped.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /// 一个待下载的文件。
    private record DownloadTask(String url, Path target, String sha1, long size) {
    }

    /// 索引里的一行：`相对路径 sha1 大小 mtime`。
    private record IndexEntry(String path, String sha1, long size) {
    }

    /// 并行执行一批下载，出错就整体失败。
    ///
    /// <p>进度条必须"实时"：以前字节只在**整个文件下载完**后才计入（`done.addAndGet(bytes)`），
    /// 一个 90 MB 的文件下载期间进度条一动不动，完成后突然跳一截，看起来就是"进度不明显、误导用户"。
    /// 现在每个文件把收到的字节流式累加进 [done]，并且按**时间间隔**而不是按文件数上报，这样
    /// 无论文件是大是小，进度条都持续移动。</p>
    private static void runAll(ExecutorService pool, List<DownloadTask> tasks, AtomicLong done, long total,
                               AtomicLong skipped, int[] files, Progress progress, String step,
                               BooleanSupplier cancelled, AtomicLong lastReport, long reportInterval) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
        AtomicLong completed = new AtomicLong();
        // 上报节流：工作线程里很多并发下载都在跑，每个都直接回调会把 UI 线程的
        // runLater 队列塞爆；这里只按时间间隔放行，间隔 100ms（比 250ms 的速度
        // 采样细，肉眼看起来就是"实时"）。
        for (DownloadTask task : tasks) {
            futures.add(pool.submit(() -> {
                if (cancelled.getAsBoolean())
                    throw new CancellationException("用户取消了下载");
                long bytes = downloadTo(task.url(), task.target(), task.sha1(), task.size(),
                        cancelled, done, progress, step, total, lastReport, reportInterval);
                if (bytes > 0) {
                    // 字节已经在 downloadOnce 里流式计入 done，这里只记文件数，
                    // 千万不能再 addAndGet(bytes)，否则每个文件都被算两遍。
                    synchronized (files) {
                        files[0]++;
                    }
                } else {
                    skipped.incrementAndGet();
                }
                long index = completed.incrementAndGet();
                // 文件边界也上报一次，保证"已完成文件数"这类信息不落后于真实状态。
                if (index == tasks.size() || now() - lastReport.get() >= reportInterval) {
                    lastReport.set(now());
                    // 钳制见 downloadOnce：done 偶尔会超过清单声明的总量。
                    progress.update(step, Math.min(done.get(), total), total);
                }
                return null;
            }));
        }
        Exception failure = null;
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                if (failure == null)
                    failure = e.getCause() instanceof Exception exception ? exception : new IOException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        if (failure != null)
            throw failure;
    }

    /// 下载到目标文件；内容与 sha1 一致时跳过。返回真正写入的字节数。
    ///
    /// <p>Lunar 的 CDN 偶尔会在传输中途断连（`Connection reset`），所以单个文件最多重试 3 次，
    /// 只有重试都失败才让整体失败。</p>
    ///
    /// <p>收到的字节通过 [done] 流式计入全局进度（见 [runAll] 的说明），因此**调用方不得再把
    /// 返回值加进 `done`**，否则同一个文件会被算两遍。</p>
    private static long downloadTo(String url, Path target, String sha1, long size,
                                   BooleanSupplier cancelled, AtomicLong done, Progress progress,
                                   String step, long total, AtomicLong lastReport, long reportInterval)
            throws IOException, InterruptedException {
        if (url == null || url.isBlank())
            return 0;
        boolean hasHash = sha1 != null && !sha1.isBlank();
        if (Files.isRegularFile(target)) {
            if (hasHash) {
                // 清单里的 size 并不总是可信（实测 ul-resources.zip 声明 212115、实际 2455080），
                // 所以有 sha1 时一律以内容为准，避免每次都重新下载同一个文件。
                if (sha1.equalsIgnoreCase(sha1Of(target)))
                    return 0;
            } else if (size <= 0 || Files.size(target) == size) {
                return 0;
            }
        }

        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (cancelled.getAsBoolean())
                throw new CancellationException("用户取消了下载");
            if (attempt > 0)
                Thread.sleep(1000L * attempt);
            try {
                return downloadOnce(url, target, sha1, cancelled, done, progress, step, total,
                        lastReport, reportInterval);
            } catch (CancellationException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("下载失败：" + url);
    }

    /// 单次下载尝试。
    private static long downloadOnce(String url, Path target, String sha1,
                                     BooleanSupplier cancelled, AtomicLong done, Progress progress,
                                     String step, long total, AtomicLong lastReport, long reportInterval)
            throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest digest = newDigest();
        long written = 0;
        // 失败时已经计入的字节要退回，否则一次失败的重试会让进度条凭空多出一截。
        long reported = 0;
        try (InputStream in = LunarApi.openStream(url);
             OutputStream out = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            long lastFlush = 0;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0)
                    continue;
                if (cancelled.getAsBoolean())
                    throw new CancellationException("用户取消了下载");
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                written += read;
                reported += read;
                done.addAndGet(read);
                // 边收边报：按时间节流，进度条在单个大文件的下载过程中也持续移动。
                long t = now();
                if (t - lastFlush >= reportInterval) {
                    lastFlush = t;
                    if (t - lastReport.get() >= reportInterval) {
                        lastReport.set(t);
                        // done 可能因为清单低估总量（或个别文件被取回两次）而超过
                        // total；进度条把超过 100% 渲染成回退，看起来像在倒退。
                        // 钳制到 total 只是显示问题，底层计数不受影响。
                        progress.update(step, Math.min(done.get(), total), total);
                    }
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (reported > 0)
                done.addAndGet(-reported);
            Files.deleteIfExists(temporary);
            throw e;
        }

        if (sha1 != null && !sha1.isBlank() && !sha1.equalsIgnoreCase(hex(digest.digest()))) {
            if (reported > 0)
                done.addAndGet(-reported);
            Files.deleteIfExists(temporary);
            throw new IOException("文件校验失败（sha1 不匹配）：" + target.getFileName());
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return written;
    }

    /// 读取纯文本索引。
    private static List<IndexEntry> readIndex(String url) throws IOException, InterruptedException {
        List<IndexEntry> entries = new ArrayList<>();
        if (url == null || url.isBlank())
            return entries;
        String text = LunarApi.readText(url);
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2)
                continue;
            long size = 0;
            if (parts.length >= 3) {
                try {
                    size = Long.parseLong(parts[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            entries.add(new IndexEntry(parts[0], parts[1], size));
        }
        return entries;
    }

    /// 解压 zip 到目标目录（防目录穿越，跳过 META-INF）。
    private static void extractZip(Path zip, Path targetDirectory) throws IOException {
        Files.createDirectories(targetDirectory);
        Path root = targetDirectory.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/"))
                    continue;
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root))
                    continue;
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /// 去掉工件名里可能出现的目录前缀与非法字符。
    private static String safeName(String name) {
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0)
            cleaned = cleaned.substring(slash + 1);
        return cleaned.replaceAll("[<>:\"|?*]", "_");
    }

    /// 索引里的相对路径：保留子目录结构，丢掉越界（`..`、绝对路径、盘符）的片段。
    private static String safeRelativePath(String path) {
        StringBuilder builder = new StringBuilder();
        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains(":"))
                continue;
            if (builder.length() > 0)
                builder.append('/');
            builder.append(segment.replaceAll("[<>:\"|?*]", "_"));
        }
        return builder.length() == 0 ? "unnamed" : builder.toString();
    }

    /// 当前毫秒时间戳；独立成方法是为了让节流逻辑读起来直白。
    private static long now() {
        return System.currentTimeMillis();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha1Of(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) >= 0)
                if (read > 0)
                    digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            builder.append(String.format(Locale.ROOT, "%02x", b));
        return builder.toString();
    }
}

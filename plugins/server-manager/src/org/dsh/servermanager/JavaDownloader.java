package org.dsh.servermanager;

import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.java.JavaPackageType;
import org.jackhuang.hmcl.download.java.disco.DiscoFetchJavaListTask;
import org.jackhuang.hmcl.download.java.disco.DiscoJavaDistribution;
import org.jackhuang.hmcl.download.java.disco.DiscoJavaRemoteVersion;
import org.jackhuang.hmcl.download.java.disco.DiscoRemoteFileInfo;
import org.jackhuang.hmcl.download.java.disco.DiscoResult;
import org.jackhuang.hmcl.java.JavaInfo;
import org.jackhuang.hmcl.java.JavaManager;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.GetTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/// Downloads and installs a Java runtime that can actually run a server.
///
/// <p>Deliberately uses the Foojay/Disco index instead of Mojang's own Java downloader.
/// Mojang's Java 8 is `jre-legacy` (1.8.0_51), whose certificate store is exactly the one
/// that fails against Mojang's current TLS chain — downloading it to fix a trust-store
/// problem would just reproduce the problem. Disco serves current vendor builds
/// (Azul Zulu / Eclipse Temurin) for every required major version, and installs them into
/// the launcher's own Java directory so they appear in the launcher's Java list.</p>
@NotNullByDefault
public final class JavaDownloader {

    /// Vendor preference for the automatic download. Zulu ships a current Java 8 build for
    /// Windows x86/x86_64/arm64, which is the case that matters most for legacy servers.
    private static final DiscoJavaDistribution[] PREFERRED = {
            DiscoJavaDistribution.ZULU,
            DiscoJavaDistribution.TEMURIN,
            DiscoJavaDistribution.LIBERICA,
            DiscoJavaDistribution.CORRETTO,
    };

    private JavaDownloader() {
    }

    /// Builds the task that fetches, unpacks and registers a Java runtime of `majorVersion`.
    ///
    /// <p>The resulting task installs into the launcher's managed Java folder and registers the
    /// runtime with [JavaManager], and yields the path of the `java` executable to run.</p>
    ///
    /// @param downloadProvider the active download provider
    /// @param majorVersion the Java major version to install (8, 17, 21, 25, ...)
    /// @throws IOException when no vendor offers a matching build for this platform
    public static Task<Path> download(DownloadProvider downloadProvider, int majorVersion) throws IOException {
        Platform platform = Platform.SYSTEM_PLATFORM;
        DiscoJavaDistribution distribution = chooseDistribution(platform);
        if (distribution == null)
            throw new IOException("没有适用于当前平台（" + platform + "）的 Java 下载源");

        return new DiscoFetchJavaListTask(downloadProvider, distribution, platform)
                .setExecutor(Schedulers.io())
                .thenComposeAsync(versions -> {
                    DiscoJavaRemoteVersion chosen = pick(versions, majorVersion);
                    if (chosen == null)
                        throw new IOException("下载源里没有找到 Java " + majorVersion
                                + " 的 " + platform + " 构建（发行版：" + distribution.getDisplayName() + "）");
                    return fetchAndInstall(downloadProvider, distribution, platform, chosen);
                });
    }

    /// Resolves the archive info for `chosen`, downloads it and hands it to the launcher's
    /// Java installer. Yields the installed `java` executable.
    private static Task<Path> fetchAndInstall(DownloadProvider downloadProvider,
                                              DiscoJavaDistribution distribution,
                                              Platform platform,
                                              DiscoJavaRemoteVersion chosen) {
        Path targetFile;
        try {
            targetFile = Files.createTempFile("hmcl-java-", "." + chosen.getArchiveType());
        } catch (IOException e) {
            return Task.fromCompletableFuture(failedFuture(e));
        }
        targetFile.toFile().deleteOnExit();

        String javaVersion = chosen.getJavaVersion() == null ? String.valueOf(chosen.getJdkVersion())
                : chosen.getJavaVersion();
        JavaInfo info = new JavaInfo(platform, javaVersion, distribution.getVendor());

        // The installer rejects names that collide with an existing runtime, so derive a
        // unique one from the exact vendor build (for example `zulu-8.0.504`).
        String directoryVersion = javaVersion;
        int plus = directoryVersion.indexOf('+');
        if (plus > 0)
            directoryVersion = directoryVersion.substring(0, plus);
        String name = distribution.getApiParameter() + "-" + directoryVersion;

        // Already installed by an earlier click: reuse it instead of pulling ~40 MB again.
        Path existing = JavaManager.REPOSITORY.getJavaExecutable(platform, name);
        if (existing != null) {
            try {
                Files.deleteIfExists(targetFile);
            } catch (IOException ignored) {
            }
            return Task.supplyAsync(() -> existing);
        }

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("type", "disco");
        update.put("info", chosen);

        return new GetTask(downloadProvider.injectURLWithCandidates(chosen.getLinks().pkgInfoUri()))
                .setExecutor(Schedulers.io())
                .thenComposeAsync(json -> {
                    DiscoResult<DiscoRemoteFileInfo> result = JsonUtils.fromNonNullJson(
                            json, DiscoResult.typeOf(DiscoRemoteFileInfo.class));
                    if (result.getResult().size() != 1)
                        throw new IOException("下载源返回了异常结果：" + json);

                    DiscoRemoteFileInfo fileInfo = result.getResult().get(0);
                    if (StringUtils.isBlank(fileInfo.directDownloadUri()))
                        throw new IOException("下载源没有提供直链：" + json);

                    return integrityCheck(downloadProvider, fileInfo)
                            .thenComposeAsync(check -> new FileDownloadTask(
                                            downloadProvider.injectURLWithCandidates(fileInfo.directDownloadUri()),
                                            targetFile, check)
                                    .setExecutor(Schedulers.io())
                                    .setName(fileInfo.fileName()))
                            .thenSupplyAsync(() -> targetFile);
                })
                .thenComposeAsync(file -> JavaManager.getInstallJavaTask(platform, name, update, file)
                        .setExecutor(Schedulers.io()))
                .thenApplyAsync(java -> {
                    // The archive is unpacked; drop it now instead of waiting for JVM exit.
                    // A failed install leaves it to `deleteOnExit` above.
                    try {
                        Files.deleteIfExists(targetFile);
                    } catch (IOException ignored) {
                    }
                    return java.getBinary();
                });
    }

    /// Resolves the archive checksum, tolerating sources that inline it, link it separately
    /// or omit it entirely.
    private static Task<FileDownloadTask.IntegrityCheck> integrityCheck(DownloadProvider downloadProvider,
                                                                       DiscoRemoteFileInfo fileInfo) {
        if (StringUtils.isNotBlank(fileInfo.checksumType())
                && !fileInfo.checksumType().equals("sha1")
                && !fileInfo.checksumType().equals("sha256")
                && !fileInfo.checksumType().equals("md5"))
            return Task.fromCompletableFuture(failedFuture(
                    new IOException("不支持的校验方式：" + fileInfo.checksumType())));

        if (StringUtils.isNotBlank(fileInfo.checksum()))
            return Task.completed(new FileDownloadTask.IntegrityCheck(fileInfo.checksumType(), fileInfo.checksum()));

        if (StringUtils.isNotBlank(fileInfo.checksumUri()))
            return new GetTask(downloadProvider.injectURLWithCandidates(fileInfo.checksumUri()))
                    .setExecutor(Schedulers.io())
                    .thenApplyAsync(checksum -> {
                        String trimmed = checksum.trim();
                        int space = trimmed.indexOf(' ');
                        // some checksum files are `<hash>  <filename>`
                        if (space > 0)
                            trimmed = trimmed.substring(0, space);
                        return new FileDownloadTask.IntegrityCheck(fileInfo.checksumType(), trimmed);
                    });

        return Task.completed(null);
    }

    // --------------------------------------------------------------- pick ---

    /// The first preferred distribution that supports the current platform.
    private static @Nullable DiscoJavaDistribution chooseDistribution(Platform platform) {
        for (DiscoJavaDistribution distribution : PREFERRED)
            if (distribution.isSupport(platform))
                return distribution;
        for (DiscoJavaDistribution distribution : DiscoJavaDistribution.values())
            if (distribution.isSupport(platform))
                return distribution;
        return null;
    }

    /// Picks the newest vendor build for `majorVersion`, preferring a JRE over a JDK (a server
    /// does not need the compiler, and the download is much smaller).
    ///
    /// <p>When the exact major version is unavailable the closest build above it is used, and
    /// failing that the newest build below it — a slightly different Java is far better than
    /// refusing to help.</p>
    static @Nullable DiscoJavaRemoteVersion pick(
            Map<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>> versions, int majorVersion) {
        for (JavaPackageType packageType : new JavaPackageType[]{JavaPackageType.JRE, JavaPackageType.JDK}) {
            TreeMap<Integer, DiscoJavaRemoteVersion> byMajor = versions.get(packageType);
            if (byMajor == null || byMajor.isEmpty())
                continue;

            DiscoJavaRemoteVersion exact = byMajor.get(majorVersion);
            if (exact != null)
                return exact;

            Map.Entry<Integer, DiscoJavaRemoteVersion> above = byMajor.ceilingEntry(majorVersion);
            if (above != null)
                return above.getValue();

            Map.Entry<Integer, DiscoJavaRemoteVersion> below = byMajor.floorEntry(majorVersion);
            if (below != null)
                return below.getValue();
        }
        return null;
    }

    /// Human-readable description of what will be downloaded, for the confirmation dialog.
    public static String describe(int majorVersion) {
        Platform platform = Platform.SYSTEM_PLATFORM;
        DiscoJavaDistribution distribution = chooseDistribution(platform);
        String vendor = distribution == null ? "Java" : distribution.getDisplayName();
        return vendor + " Java " + majorVersion + "（" + platform.getOperatingSystem().getCheckedName()
                + " / " + platform.getArchitecture().getDisplayName() + "）";
    }

    /// A future that has already failed, used where a [Task] is required but the failure was
    /// detected before any stage could be built.
    private static <T> java.util.concurrent.CompletableFuture<T> failedFuture(Throwable error) {
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}

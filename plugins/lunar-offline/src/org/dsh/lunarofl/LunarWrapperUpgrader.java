package org.dsh.lunarofl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/// 把新版入口包装 jar 推送到所有已安装的 Lunar 版本。
///
/// <p>Lunar 版本的启动链是「HMCL → 版本 json 里登记的包装 jar → Genesis」。包装 jar 以
/// `org.dsh.lunar-wrapper:lunar-wrapper:1.0` 装在每个游戏目录的 `libraries/` 下，老版本
/// 安装后不会自动更新。插件升级时在这里逐个游戏目录扫描出我们生成的版本（mainClass 是
/// 包装类），把插件资源里最新的包装 jar 覆盖进去，顺带把旧版写死的
/// `--installationId INSTALLATION-ID` 占位符换成真实 id。</p>
public final class LunarWrapperUpgrader {

    private static final String WRAPPER_RESOURCE = "/wrapper/lunar-wrapper.jar";
    private static final String WRAPPER_MAIN_CLASS = LunarVersionInstaller.WRAPPER_MAIN_CLASS;
    private static final String WRAPPER_LIBRARY_PATH = LunarVersionInstaller.WRAPPER_LIBRARY_PATH;

    private LunarWrapperUpgrader() {
    }

    /// 升级所有游戏目录里的 Lunar 版本；返回升级（或确认已是最新）的版本 id 列表。
    public static List<String> upgradeAll() {
        return upgradeAll(false);
    }

    /// @param retryWhenLocked 为 true 时，遇到"文件被占用"会在后台重试若干次
    ///                        （游戏正在运行时包装 jar 被游戏进程的 classpath 锁住，
    ///                        等它退出后就能覆盖，不必让用户重启两次启动器）
    public static List<String> upgradeAll(boolean retryWhenLocked) {
        List<String> upgraded = new ArrayList<>();
        byte[] wrapper;
        try (InputStream in = LunarWrapperUpgrader.class.getResourceAsStream(WRAPPER_RESOURCE)) {
            if (in == null) {
                Logger.LOG.warning("Lunar 包装器资源缺失，跳过升级：" + WRAPPER_RESOURCE);
                return upgraded;
            }
            wrapper = in.readAllBytes();
        } catch (IOException e) {
            Logger.LOG.warning("读取 Lunar 包装器资源失败：" + e);
            return upgraded;
        }

        List<Retry> lockedRetries = new ArrayList<>();

        for (GameDirectory gameDirectory : GameDirectoryManager.getGameDirectories()) {
            Path base;
            try {
                base = gameDirectory.getPath().toPath();
            } catch (RuntimeException e) {
                continue;
            }
            if (!Files.isDirectory(base.resolve("versions")))
                continue;
            try (Stream<Path> versions = Files.list(base.resolve("versions"))) {
                versions.filter(Files::isDirectory).forEach(versionDir -> {
                    String versionId = versionDir.getFileName().toString();
                    Path versionJson = versionDir.resolve(versionId + ".json");
                    if (!Files.isRegularFile(versionJson))
                        return;
                    if (!isLunarVersion(versionJson))
                        return;
                    try {
                        installWrapper(base.resolve("libraries"), wrapper);
                        patchInstallationId(versionJson);
                        upgraded.add(versionId);
                    } catch (IOException e) {
                        Logger.LOG.warning("升级 Lunar 版本 " + versionId + " 失败：" + e);
                        if (isSharingViolation(e)) {
                            // 游戏正在用这个 jar：记下来，等它退出后补上
                            lockedRetries.add(new Retry(versionId, base.resolve("libraries"),
                                    versionJson, wrapper));
                        }
                    }
                });
            } catch (IOException e) {
                Logger.LOG.warning("扫描游戏目录 " + base + " 失败：" + e);
            }
        }

        if (retryWhenLocked && !lockedRetries.isEmpty())
            scheduleLockedRetries(lockedRetries);

        return upgraded;
    }

    /// 判断异常是不是"文件被另一个进程占用"。
    ///
    /// <p>Windows 抛 `FileSystemException`（消息里带「另一个程序正在使用此文件」/ `being used
    /// by another process`），个别情况是 `AccessDeniedException`。其他 IOException 不做重试。</p>
    private static boolean isSharingViolation(IOException e) {
        String message = e.getMessage();
        if (message == null)
            return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("another process")
                || lower.contains("正被另一进程使用")
                || message.contains("另一个程序正在使用")
                || e instanceof java.nio.file.AccessDeniedException;
    }

    /// 后台重试：每 15 秒试一次，最多 40 次（10 分钟），够覆盖一局游戏。
    private static void scheduleLockedRetries(List<Retry> retries) {
        Thread thread = new Thread(() -> {
            Logger.LOG.info("有 " + retries.size() + " 个 Lunar 版本的包装器被占用，"
                    + "将在游戏退出后自动更新");
            for (int attempt = 0; attempt < 40 && !retries.isEmpty(); attempt++) {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                retries.removeIf(retry -> retry.attempt());
            }
            if (!retries.isEmpty())
                Logger.LOG.warning("仍有 " + retries.size()
                        + " 个 Lunar 版本的包装器未能更新（游戏一直在运行？下次启动启动器时会再试）");
        }, "lunar-wrapper-upgrade-retry");
        thread.setDaemon(true);
        thread.start();
    }

    /// 一次"等游戏退出后再写"的重试动作。
    ///
    /// @param versionId 版本 id（日志用）
    /// @param libraries 目标 libraries 目录
    /// @param versionJson 版本 json（顺带修 installationId 占位符）
    /// @param wrapper 新的包装 jar 字节
    private record Retry(String versionId, Path libraries, Path versionJson, byte[] wrapper) {

        /// 试一次；成功返回 true（可以从待重试列表里移除）。
        boolean attempt() {
            try {
                installWrapper(libraries, wrapper);
                patchInstallationId(versionJson);
                Logger.LOG.info("Lunar 包装器已在游戏退出后更新：" + versionId);
                return true;
            } catch (IOException stillLocked) {
                return false;
            }
        }
    }

    /// 判断版本 json 是不是本插件生成的 Lunar 版本（mainClass 是我们的包装类）。
    private static boolean isLunarVersion(Path versionJson) {
        try {
            JsonObject version = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return version.has("mainClass")
                    && WRAPPER_MAIN_CLASS.equals(version.get("mainClass").getAsString());
        } catch (Exception e) {
            return false;
        }
    }

    /// 包装 jar 内容与插件资源不一致时覆盖写入。
    private static void installWrapper(Path librariesDir, byte[] wrapper) throws IOException {
        Path target = librariesDir.resolve(WRAPPER_LIBRARY_PATH);
        if (Files.isRegularFile(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (java.util.Arrays.equals(existing, wrapper))
                return;
        }
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".dsh-tmp");
        Files.write(temp, wrapper);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        Logger.LOG.info("已更新 Lunar 入口包装：" + target);
    }

    /// 把版本 json 里 `--installationId` 的占位符换成稳定 id（游戏 gameipc 握手要用它）。
    private static void patchInstallationId(Path versionJson) throws IOException {
        JsonObject version = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (!version.has("arguments") || !version.getAsJsonObject("arguments").has("game"))
            return;
        com.google.gson.JsonArray game = version.getAsJsonObject("arguments").getAsJsonArray("game");
        String installationId = stableInstallationId();
        boolean changed = false;
        for (int i = 0; i + 1 < game.size(); i++) {
            if (game.get(i).isJsonPrimitive() && "--installationId".equals(game.get(i).getAsString())
                    && game.get(i + 1).isJsonPrimitive()
                    && "INSTALLATION-ID".equals(game.get(i + 1).getAsString())) {
                game.set(i + 1, new com.google.gson.JsonPrimitive(installationId));
                changed = true;
            }
        }
        if (changed)
            Files.writeString(versionJson, version.toString(), StandardCharsets.UTF_8);
    }

    /// 稳定的 installation id：优先用官方启动器的（同一台机器，与官方行为一致），
    /// 否则用插件数据目录里已经生成的那个。
    static String stableInstallationId() {
        Path official = Path.of(System.getProperty("user.home"), ".lunarclient",
                "launcher-cache", "installation-id");
        try {
            if (Files.isRegularFile(official)) {
                String value = Files.readString(official, StandardCharsets.UTF_8).trim();
                if (!value.isEmpty())
                    return value;
            }
        } catch (IOException ignored) {
        }
        Path dataDirectory = LunarOfflinePlugin.dataDirectory();
        if (dataDirectory == null)
            return java.util.UUID.randomUUID().toString();
        try {
            return new LunarApi(dataDirectory).installationId();
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString();
        }
    }
}

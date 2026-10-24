package org.dsh.lunarofl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/// 插件内置的 Celestial 核心：随插件一起分发，首次使用时释放到插件数据目录，
/// 因此用户**不需要另外下载 Celestial** 就能启动 Lunar。
///
/// <p>内置内容在插件 jar 的 `celestial-core/` 下（由 `core.json` 描述），包含
/// Celestial 的 fatjar、配套的 BrowserOffline java agent，以及它们的许可证/声明
/// （Celestial 采用 Apache-2.0，随附原文）。释放时逐个文件复制，已存在且大小一致则跳过。</p>
public final class BundledCore {

    /// 插件 jar 内的资源目录。
    private static final String RESOURCE_DIR = "celestial-core";
    private static final String MANIFEST = RESOURCE_DIR + "/core.json";

    private BundledCore() {
    }

    /// 内置核心的描述。
    public record Core(String version, String jar, List<String> extra, String origin, String license) {
    }

    /// 读取内置核心描述；插件包损坏或未内置时返回 null。
    public static Core describe() {
        try (InputStream input = BundledCore.class.getResourceAsStream("/" + MANIFEST)) {
            if (input == null)
                return null;
            JsonObject object = JsonParser.parseString(
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> extra = new ArrayList<>();
            if (object.has("extra") && object.get("extra").isJsonArray()) {
                JsonArray array = object.getAsJsonArray("extra");
                for (int i = 0; i < array.size(); i++)
                    extra.add(array.get(i).getAsString());
            }
            return new Core(
                    object.has("version") ? object.get("version").getAsString() : "",
                    object.has("jar") ? object.get("jar").getAsString() : "",
                    List.copyOf(extra),
                    object.has("origin") ? object.get("origin").getAsString() : "",
                    object.has("license") ? object.get("license").getAsString() : "");
        } catch (Exception e) {
            Logger.LOG.warning("Failed to read bundled celestial core manifest", e);
            return null;
        }
    }

    /// 内置核心是否可用（插件包里带了文件）。
    public static boolean available() {
        Core core = describe();
        return core != null && !core.jar().isBlank()
                && BundledCore.class.getResource("/" + RESOURCE_DIR + "/" + core.jar()) != null;
    }

    /// 释放内置核心到 `dataDirectory/celestial-core/`，返回可直接启动的 jar 路径。
    ///
    /// @throws IOException 内置核心缺失或写入失败
    public static Path install(Path dataDirectory) throws IOException {
        Core core = describe();
        if (core == null || core.jar().isBlank())
            throw new IOException("插件包内没有内置 Celestial 核心");

        Path target = dataDirectory.resolve(RESOURCE_DIR);
        Files.createDirectories(target);
        extract(core.jar(), target);
        for (String name : core.extra())
            extract(name, target);
        // 许可证与声明随核心一起释放，满足 Apache-2.0 的随附要求
        extract("LICENSE-celestial-Apache-2.0.md", target);
        extract("NOTICE-celestial.md", target);
        extract("CREDITS.md", target);

        Path jar = target.resolve(core.jar());
        if (!Files.isRegularFile(jar))
            throw new IOException("内置核心释放失败：" + jar);
        return jar;
    }

    /// 复制单个资源；目标已存在且大小一致时跳过（避免每次都重写 24MB）。
    private static void extract(String name, Path targetDirectory) throws IOException {
        if (name == null || name.isBlank())
            return;
        Path destination = targetDirectory.resolve(name);
        try (InputStream input = BundledCore.class.getResourceAsStream("/" + RESOURCE_DIR + "/" + name)) {
            if (input == null)
                throw new IOException("插件包内缺少 " + name);
            byte[] bytes = input.readAllBytes();
            if (Files.isRegularFile(destination) && Files.size(destination) == bytes.length)
                return;
            Files.copy(new java.io.ByteArrayInputStream(bytes), destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

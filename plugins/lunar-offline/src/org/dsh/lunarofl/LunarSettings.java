package org.dsh.lunarofl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// 插件的持久化设置：Celestial jar、Java 可执行文件、附加 JVM 参数。
///
/// <p>默认值会根据机器上的实际情况自动探测（例如作者的客户端目录），用户随时可以在页面上改。</p>
public final class LunarSettings {

    /// Celestial 安装目录的常见位置，用于开箱即用的自动探测。
    private static final String[] DEFAULT_DIRECTORIES = {
            "E:\\game\\game\\minecraft\\pvp客户端\\离线lunar",
            "D:\\game\\game\\minecraft\\pvp客户端\\离线lunar",
            "C:\\game\\game\\minecraft\\pvp客户端\\离线lunar",
            System.getProperty("user.home", "") + "\\minecraft\\pvp客户端\\离线lunar"
    };

    private Path settingsFile;
    private String jarPath = "";
    private String javaPath = "";
    private String jvmArgs = "";
    /// 是否使用插件内置的 Celestial 核心（默认 true：开箱即用，无需另装 Celestial）
    private boolean useBundledCore = true;

    private LunarSettings() {
    }

    public static LunarSettings load(Path dataDirectory) {
        LunarSettings settings = new LunarSettings();
        settings.settingsFile = dataDirectory.resolve("settings.json");
        if (Files.isRegularFile(settings.settingsFile)) {
            try {
                JsonObject object = JsonParser.parseString(
                        Files.readString(settings.settingsFile, StandardCharsets.UTF_8)).getAsJsonObject();
                settings.jarPath = string(object, "jarPath");
                settings.javaPath = string(object, "javaPath");
                settings.jvmArgs = string(object, "jvmArgs");
                if (object.has("useBundledCore") && object.get("useBundledCore").isJsonPrimitive())
                    settings.useBundledCore = object.get("useBundledCore").getAsBoolean();
            } catch (Exception e) {
                Logger.LOG.warning("Failed to read lunar settings", e);
            }
        }
        if (settings.jarPath.isBlank())
            settings.jarPath = detectJar();
        if (settings.javaPath.isBlank())
            settings.javaPath = detectJava();
        return settings;
    }

    public void save() {
        JsonObject object = new JsonObject();
        object.addProperty("jarPath", jarPath);
        object.addProperty("javaPath", javaPath);
        object.addProperty("jvmArgs", jvmArgs);
        object.addProperty("useBundledCore", useBundledCore);
        try {
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, object.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.LOG.warning("Failed to save lunar settings", e);
        }
    }

    public String getJarPath() {
        return jarPath;
    }

    public void setJarPath(String jarPath) {
        this.jarPath = jarPath == null ? "" : jarPath.trim();
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath == null ? "" : javaPath.trim();
    }

    public String getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs == null ? "" : jvmArgs.trim();
    }

    public boolean isUseBundledCore() {
        return useBundledCore;
    }

    public void setUseBundledCore(boolean useBundledCore) {
        this.useBundledCore = useBundledCore;
    }

    /// 实际用于启动的 jar：内置核心（按需释放到插件数据目录）或用户指定的外部 jar。
    public Path resolveJar(Path dataDirectory) throws IOException {
        if (useBundledCore)
            return BundledCore.install(dataDirectory);
        Path jar = jar();
        if (jar == null)
            throw new IOException("找不到指定的 Celestial jar，请重新选择或改用内置核心。");
        return jar;
    }

    /// 内置核心释放后的目录（工作目录也用它，Celestial 需要同目录的配套文件）。
    public Path bundledDirectory(Path dataDirectory) {
        return dataDirectory.resolve("celestial-core");
    }

    /// 已配置的 Celestial jar（不存在时返回 null）。
    public Path jar() {
        if (jarPath.isBlank())
            return null;
        Path path = Path.of(jarPath);
        return Files.isRegularFile(path) ? path : null;
    }

    /// jar 所在目录；启动时作为工作目录（Celestial 依赖它找到自己的文件）。
    public Path workingDirectory() {
        Path jar = jar();
        if (jar != null && jar.getParent() != null)
            return jar.getParent();
        if (!jarPath.isBlank()) {
            Path path = Path.of(jarPath);
            return path.getParent() == null ? path : path.getParent();
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    /// 在常见位置里找一个 `*celestial*.jar`。
    public static String detectJar() {
        for (String directory : DEFAULT_DIRECTORIES) {
            Path dir = Path.of(directory);
            if (!Files.isDirectory(dir))
                continue;
            try (var stream = Files.list(dir)) {
                var found = stream
                        .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains("celestial"))
                        .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .findFirst();
                if (found.isPresent())
                    return found.get().toAbsolutePath().toString();
            } catch (IOException ignored) {
                // 目录不可读：换下一个
            }
        }
        return "";
    }

    /// 默认 Java：优先 21 版的 JDK，其次环境里的任意 Java。
    public static String detectJava() {
        for (JavaLocator.JavaEntry entry : JavaLocator.scan()) {
            if (entry.isJdk21())
                return entry.path();
        }
        String home = System.getProperty("java.home", "");
        if (!home.isBlank()) {
            Path executable = Path.of(home, "bin", "java.exe");
            return Files.isRegularFile(executable) ? executable.toString() : "";
        }
        return "";
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}

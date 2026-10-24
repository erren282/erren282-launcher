package org.dsh.lunarofl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Lunar Client 官方 API 的最小客户端。
///
/// <p>这里用到的两个接口都是 Lunar 启动器自己在用的公开接口，不需要账号：
/// <ul>
///   <li>`GET /launcher/metadata/versions/lunar` —— 版本目录（哪些游戏版本、各自的模块）；</li>
///   <li>`POST /launcher/launch` —— 某个「游戏版本 + 模块」的完整工件清单（classpath、
///       外部文件、native、贴图索引、UI、许可证、JVM 参数）。</li>
/// </ul>
/// 有了这两份数据，插件就能自己把客户端下载下来，不必再经过 Celestial。</p>
///
/// <p>请求参数是实测得到的：`os` 用 Node 风格的 `win32`（不是 `windows`，否则服务端会报
/// `NO_JRE_FOUND`），`arch` 用 `x64`；`POST` 的 JSON 体只接受
/// `installation_id/os/os_release/arch/branch/version/module/launcher_version/hwid`
/// 这几个键，多一个都会被严格的 schema 拒绝。</p>
public class LunarApi {

    public static final String ENDPOINT = "https://api.lunarclientprod.com";
    public static final String BRANCH = "master";
    /// 以 Lunar 启动器的版本号自称，服务端用 SemVer 校验它。
    public static final String LAUNCHER_VERSION = "3.5.2";

    private static final String USER_AGENT = "LunarClient/3.5.2 (HMCL lunar-offline plugin)";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String installationId;
    private final String os;
    private final String arch;
    private final String osRelease;

    /// @param dataDirectory 插件数据目录，用来保存一个稳定的 installation id
    public LunarApi(Path dataDirectory) {
        this.installationId = installationId(dataDirectory);
        String[] platform = platform();
        this.os = platform[0];
        this.arch = platform[1];
        this.osRelease = System.getProperty("os.version", "10.0");
    }

    /// 版本目录里的一个可选模块（lunar / lunar-noOF / fabric / forge …）。
    public record Module(String id, String name, boolean isDefault) {
    }

    /// 版本目录里的一个可安装版本，例如 `1.8.9`（父项 `1.8`）。
    public record SubVersion(String id, String parentId, String parentName, boolean isDefault,
                             String assetIndex, List<Module> modules) {

        /// 默认模块：服务端标了 default 的那个，否则第一个。
        public Module defaultModule() {
            if (modules.isEmpty())
                return new Module("lunar", "Lunar", true);
            for (Module module : modules)
                if (module.isDefault())
                    return module;
            return modules.get(0);
        }

        /// 下拉框里的显示文本。
        public String label() {
            return parentName == null || parentName.isBlank() ? id : id + "  ·  " + parentName;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    /// 清单里的一个文件。`type` 是 `CLASS_PATH` / `EXTERNAL_FILE` / `NATIVES` 之一。
    public record Artifact(String name, String sha1, String url, String type, long size) {
    }

    /// 一份完整的工件清单。
    public record Manifest(String gameVersion, String module, String mainClass,
                           List<Artifact> artifacts, List<String> vmArgs,
                           String texturesIndexUrl, String texturesBaseUrl,
                           String uiSourceUrl, String uiSourceSha1,
                           String uiAssetsIndexUrl, String uiAssetsBaseUrl,
                           List<Artifact> licenses, String canaryToken) {

        public List<Artifact> ofType(String type) {
            List<Artifact> result = new ArrayList<>();
            for (Artifact artifact : artifacts)
                if (type.equals(artifact.type()))
                    result.add(artifact);
            return result;
        }

        /// 会被放进 `--ichorClassPath` 的 jar。
        public List<Artifact> classpath() {
            return ofType("CLASS_PATH");
        }

        /// 会被放进 `--ichorExternalFiles` 的文件（OptiFine、配置 json 等）。
        public List<Artifact> externalFiles() {
            return ofType("EXTERNAL_FILE");
        }

        /// native 压缩包，需要解压到 `natives/`。
        public List<Artifact> natives() {
            return ofType("NATIVES");
        }

        /// 清单给出的 JVM 参数 + Lua 客户端要求的 library path。
        public List<String> jvmArgs() {
            List<String> result = new ArrayList<>(vmArgs);
            result.add("-Djava.library.path=natives");
            return result;
        }
    }

    /// 拉取版本目录（顺序即官方启动器里的顺序）。
    public List<SubVersion> versions() throws IOException, InterruptedException {
        String url = ENDPOINT + "/launcher/metadata/versions/lunar"
                + "?installation_id=" + installationId
                + "&os=" + os
                + "&arch=" + arch
                + "&launcher_version=" + LAUNCHER_VERSION
                + "&branch=" + BRANCH
                + "&branch_changed=true&private=true"
                + "&os_release=" + urlEncode(osRelease);
        JsonObject root = getJson(url);
        List<SubVersion> result = new ArrayList<>();
        for (JsonElement element : array(root, "versions")) {
            JsonObject version = element.getAsJsonObject();
            String parentId = string(version, "id", "");
            String parentName = string(version, "name", "");
            for (JsonElement subElement : array(version, "subversions")) {
                JsonObject sub = subElement.getAsJsonObject();
                List<Module> modules = new ArrayList<>();
                for (JsonElement moduleElement : array(sub, "modules")) {
                    JsonObject module = moduleElement.getAsJsonObject();
                    modules.add(new Module(
                            string(module, "id", ""),
                            string(module, "name", string(module, "id", "")),
                            bool(module, "default")));
                }
                String assetIndex = sub.has("assets") && sub.get("assets").isJsonObject()
                        ? string(sub.getAsJsonObject("assets"), "id", "")
                        : "";
                result.add(new SubVersion(
                        string(sub, "id", ""),
                        parentId,
                        parentName,
                        bool(sub, "default"),
                        assetIndex,
                        modules));
            }
        }
        return result;
    }

    /// 拉取某个「游戏版本 + 模块」的完整工件清单。
    ///
    /// <p>【测试缝隙】不是 final：进度行为的自动化测试用本地 HTTP 服务器派生一个
    /// 子类覆盖它，避免真的去连 Lunar 的服务端。生产路径不受影响。</p>
    public Manifest manifest(String gameVersion, String module) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("installation_id", installationId);
        body.addProperty("os", os);
        body.addProperty("os_release", osRelease);
        body.addProperty("arch", arch);
        body.addProperty("branch", BRANCH);
        body.addProperty("version", gameVersion);
        body.addProperty("module", module);
        body.addProperty("launcher_version", LAUNCHER_VERSION);
        body.addProperty("hwid", "PUBLIC-HWID");

        JsonObject root = postJson(ENDPOINT + "/launcher/launch", body);
        if (root.has("success") && !root.get("success").getAsBoolean()) {
            JsonObject error = root.has("error") && root.get("error").isJsonObject()
                    ? root.getAsJsonObject("error") : new JsonObject();
            String message = string(error, "message", string(error, "short", "Lunar 服务端拒绝了请求"));
            throw new IOException(message);
        }

        JsonObject launchType = object(root, "launchTypeData");
        List<Artifact> artifacts = new ArrayList<>();
        for (JsonElement element : array(launchType, "artifacts"))
            artifacts.add(readArtifact(element.getAsJsonObject()));

        List<Artifact> licenses = new ArrayList<>();
        for (JsonElement element : array(root, "licenses")) {
            JsonObject license = element.getAsJsonObject();
            licenses.add(new Artifact(
                    string(license, "file", "license.txt"),
                    string(license, "sha1", ""),
                    string(license, "url", ""),
                    "LICENSE",
                    number(license, "size")));
        }

        JsonObject jre = object(root, "jre");
        List<String> vmArgs = new ArrayList<>();
        for (JsonElement element : array(jre, "extraArguments"))
            if (element.isJsonPrimitive())
                vmArgs.add(element.getAsString());

        JsonObject textures = object(root, "textures");
        JsonObject ui = object(root, "ui");
        JsonObject uiAssets = object(ui, "assets");

        return new Manifest(
                gameVersion,
                module,
                string(launchType, "mainClass", "com.moonsworth.lunar.genesis.Genesis"),
                artifacts,
                vmArgs,
                string(textures, "indexUrl", ""),
                string(textures, "baseUrl", ""),
                string(ui, "sourceUrl", ""),
                string(ui, "sourceSha1", ""),
                string(uiAssets, "indexUrl", ""),
                string(uiAssets, "baseUrl", ""),
                licenses,
                string(root, "canaryToken", ""));
    }

    public String installationId() {
        return installationId;
    }

    public String os() {
        return os;
    }

    public String arch() {
        return arch;
    }

    /// 打开一个下载流；失败时重试若干次（Lunar 的 CDN 偶尔会断）。
    public static InputStream openStream(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0)
                Thread.sleep(500L * attempt);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 == 2)
                return response.body();
            response.body().close();
            last = new IOException("HTTP " + response.statusCode() + "：" + url);
        }
        throw last != null ? last : new IOException(url);
    }

    /// 下载一个小文件到内存（贴图 / UI 的索引）。
    public static String readText(String url) throws IOException, InterruptedException {
        try (InputStream in = openStream(url)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Artifact readArtifact(JsonObject object) {
        return new Artifact(
                string(object, "name", ""),
                string(object, "sha1", ""),
                string(object, "url", ""),
                string(object, "type", ""),
                number(object, "size"));
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return send(request);
    }

    private JsonObject postJson(String url, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonObject send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2)
            throw new IOException("Lunar 接口返回 HTTP " + response.statusCode() + "：" + request.uri().getPath());
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject())
            throw new IOException("Lunar 接口返回了非预期的内容");
        return parsed.getAsJsonObject();
    }

    /// 稳定的 installation id（每个插件数据目录一个，存文件里）。
    private static String installationId(Path dataDirectory) {
        Path file = dataDirectory.resolve("installation-id.txt");
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty())
                    return existing;
            }
            String generated = java.util.UUID.randomUUID().toString();
            Files.createDirectories(dataDirectory);
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            return generated;
        } catch (IOException e) {
            return java.util.UUID.randomUUID().toString();
        }
    }

    /// 服务端按 Node 的 `process.platform` / `process.arch` 取值。
    private static String[] platform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform;
        if (osName.contains("win"))
            platform = "win32";
        else if (osName.contains("mac") || osName.contains("darwin"))
            platform = "darwin";
        else
            platform = "linux";

        String arch;
        if (archName.contains("aarch64") || archName.contains("arm64"))
            arch = "arm64";
        else if (archName.equals("x86") || archName.contains("i386") || archName.contains("i686"))
            arch = "ia32";
        else
            arch = "x64";
        return new String[]{platform, arch};
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object != null && object.has(key) && object.get(key).isJsonArray())
            return object.getAsJsonArray(key);
        return new JsonArray();
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object != null && object.has(key) && object.get(key).isJsonObject())
            return object.getAsJsonObject(key);
        return new JsonObject();
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive())
            return object.get(key).getAsString();
        return fallback;
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsBoolean();
    }

    private static long number(JsonObject object, String key) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive())
            return object.get(key).getAsLong();
        return 0L;
    }
}

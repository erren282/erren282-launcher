package org.dsh.servermanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Resolves and downloads server jars.
///
/// <p>Vanilla jars are resolved through the Mojang version manifest; every request falls
/// back to launchermeta and then to the BMCLAPI mirror, because the official endpoints
/// are unreliable from mainland China. Paper jars are resolved through the PaperMC Fill
/// v3 API (the legacy v2 API was retired).</p>
public final class ServerVersions {

    /// A version listed in the vanilla version manifest.
    public static final class VersionEntry {
        private final String id;
        private final String type; // release / snapshot / old_beta / old_alpha
        private final String url;

        public VersionEntry(String id, String type, String url) {
            this.id = id;
            this.type = type;
            this.url = url;
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public String getUrl() { return url; }
    }

    /// Download progress callback; invoked from the downloading thread.
    public interface Progress {
        void onProgress(long doneBytes, long totalBytes);
    }

    private static final String[] MANIFEST_URLS = {
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json",
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
    };
    private static final String MIRROR_BASE = "https://bmclapi2.bangbang93.com";
    private static final String PAPER_FILL_API = "https://fill.papermc.io/v3/projects/paper";

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;

    private ServerVersions() {
    }

    // ---------------------------------------------------------- vanilla ---

    /// Fetches the vanilla version manifest, official sources first, then the mirror.
    public static List<VersionEntry> fetchVanillaVersions() throws IOException {
        IOException last = null;
        for (String manifestUrl : MANIFEST_URLS) {
            try {
                JsonObject manifest = jsonRequest(manifestUrl);
                JsonArray versions = manifest.getAsJsonArray("versions");
                List<VersionEntry> result = new ArrayList<>();
                for (JsonElement element : versions) {
                    JsonObject v = element.getAsJsonObject();
                    result.add(new VersionEntry(
                            v.get("id").getAsString(),
                            v.get("type").getAsString(),
                            v.has("url") && !v.get("url").isJsonNull() ? v.get("url").getAsString() : ""));
                }
                if (!result.isEmpty())
                    return result;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No version manifest source available");
    }

    /// Finds a version entry by id in the vanilla manifest.
    public static VersionEntry resolveVanilla(String versionId) throws IOException {
        for (VersionEntry entry : fetchVanillaVersions())
            if (entry.getId().equals(versionId))
                return entry;
        throw new IOException("\u7248\u672c\u6e05\u5355\u4e2d\u6ca1\u6709 " + versionId);
    }

    /// Downloads the vanilla server jar for the given version into `target`.
    public static void downloadVanillaServer(String versionId, String versionUrl, Path target, Progress progress) throws IOException {
        // version json: try the manifest entry url first, then the mirror pattern
        JsonObject versionJson = null;
        String versionJsonError = null;
        try {
            if (versionUrl != null && !versionUrl.isEmpty())
                versionJson = jsonRequest(versionUrl);
        } catch (IOException e) {
            versionJsonError = e.getMessage();
        }
        if (versionJson == null) {
            try {
                versionJson = jsonRequest(MIRROR_BASE + "/version/" + versionId + "/json");
            } catch (IOException e) {
                throw new IOException("无法获取版本信息 " + versionId
                        + (versionJsonError != null ? "（" + versionJsonError + "）" : "")
                        + "；镜像源也失败（" + e.getMessage() + "）");
            }
        }

        String jarUrl = null;
        String sha1 = null;
        if (versionJson.has("downloads") && versionJson.getAsJsonObject("downloads").has("server")) {
            JsonObject server = versionJson.getAsJsonObject("downloads").getAsJsonObject("server");
            if (server.has("url") && !server.get("url").isJsonNull())
                jarUrl = server.get("url").getAsString();
            if (server.has("sha1") && !server.get("sha1").isJsonNull())
                sha1 = server.get("sha1").getAsString();
        }
        if (jarUrl == null)
            throw new IOException("版本 " + versionId + " 未提供服务端下载");

        try {
            downloadToFile(jarUrl, target, "SHA-1", sha1, progress);
            return;
        } catch (IOException officialError) {
            String mirrorUrl = MIRROR_BASE + "/version/" + versionId + "/server.jar";
            try {
                downloadToFile(mirrorUrl, target, "SHA-1", sha1, progress);
            } catch (IOException mirrorError) {
                throw new IOException("服务端下载失败：官方源（" + officialError.getMessage()
                        + "）、镜像源（" + mirrorError.getMessage() + "）");
            }
        }
    }

    // ------------------------------------------------------------ paper ---

    /// Fetches the Paper versions from the Fill v3 API; the JSON groups versions by
    /// major family, so the groups are flattened preserving newest-first order.
    public static List<String> fetchPaperVersions() throws IOException {
        JsonObject project = jsonRequest(PAPER_FILL_API);
        if (!project.has("versions") || !project.getAsJsonObject("versions").isJsonObject())
            throw new IOException("Paper 版本响应格式异常");
        List<String> result = new ArrayList<>();
        for (var family : project.getAsJsonObject("versions").entrySet()) {
            JsonElement versions = family.getValue();
            if (versions.isJsonArray())
                for (JsonElement version : versions.getAsJsonArray())
                    result.add(version.getAsString());
        }
        if (result.isEmpty())
            throw new IOException("Paper 版本列表为空");
        return result;
    }

    public static void downloadPaperServer(String versionId, Path target, Progress progress) throws IOException {
        JsonObject latest = jsonRequest(PAPER_FILL_API + "/versions/" + versionId + "/builds/latest");
        if (!latest.has("downloads") || !latest.getAsJsonObject("downloads").has("server:default"))
            throw new IOException("Paper " + versionId + " 没有可下载的服务端构建");
        JsonObject download = latest.getAsJsonObject("downloads").getAsJsonObject("server:default");
        String url = download.get("url").getAsString();
        String sha256 = download.has("checksums") && download.getAsJsonObject("checksums").has("sha256")
                ? download.getAsJsonObject("checksums").get("sha256").getAsString() : null;
        downloadToFile(url, target, "SHA-256", sha256, progress);
    }

    // ----------------------------------------------------------- shared ---

    private static JsonObject jsonRequest(String url) throws IOException {
        HttpURLConnection connection = openConnection(url);
        int code = connection.getResponseCode();
        if (code != 200)
            throw new IOException("HTTP " + code + " from " + url);
        try (InputStream in = connection.getInputStream()) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", "HMCL-ServerManager-Plugin (https://github.com/HMCL-dev/HMCL)");
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    /// Downloads `url` into `target` (atomically, via a temp file) and verifies the
    /// optional checksum.
    private static void downloadToFile(String url, Path target, String algorithm,
                                       String checksum, Progress progress) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".downloading");
        Files.createDirectories(target.getParent());

        HttpURLConnection connection = openConnection(url);
        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " from " + url);
        }

        long total = connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done += n;
                if (progress != null)
                    progress.onProgress(done, total);
            }
        } finally {
            connection.disconnect();
        }

        if (checksum != null && !checksum.isEmpty()) {
            String actual = hexDigest(Files.readAllBytes(tmp), algorithm);
            if (!actual.toLowerCase(Locale.ROOT).equals(checksum.toLowerCase(Locale.ROOT))) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                throw new IOException(algorithm + " 校验失败（期望 " + checksum + "，实际 " + actual + "）");
            }
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String hexDigest(byte[] data, String algorithm) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}

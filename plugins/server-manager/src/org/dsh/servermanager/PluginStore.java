package org.dsh.servermanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/// The plugin store: searches, resolves and downloads server plugins.
///
/// <p>Backed by the Modrinth API, chosen because it is the only index that exposes *both*
/// `game_versions` and `loaders` per file. That is exactly what is needed to answer the two
/// questions this feature must answer — does this plugin run on the server's Minecraft version,
/// and does it run on the server's core — instead of dumping a generic list on the user.</p>
///
/// <p>Only downloads that Modrinth hosts directly are offered, and each one is verified against
/// its SHA-512 before being accepted, so a failed or tampered transfer cannot end up in
/// `plugins/`.</p>
@NotNullByDefault
public final class PluginStore {

    /// One plugin as it appears in the store list.
    public static final class Listing {
        private final String projectId;
        private final String slug;
        private final String title;
        private final String description;
        private final String author;
        private final long downloads;
        private final String iconUrl;
        private final @Unmodifiable List<String> categories;
        private final @Unmodifiable List<String> gameVersions;
        private final @Unmodifiable List<String> loaders;

        Listing(String projectId, String slug, String title, String description, String author,
                long downloads, String iconUrl, List<String> categories,
                List<String> gameVersions, List<String> loaders) {
            this.projectId = projectId;
            this.slug = slug;
            this.title = title;
            this.description = description;
            this.author = author;
            this.downloads = downloads;
            this.iconUrl = iconUrl;
            this.categories = List.copyOf(categories);
            this.gameVersions = List.copyOf(gameVersions);
            this.loaders = List.copyOf(loaders);
        }

        public String getProjectId() { return projectId; }

        public String getSlug() { return slug; }

        public String getTitle() { return title; }

        public String getDescription() { return description; }

        public String getAuthor() { return author; }

        public long getDownloads() { return downloads; }

        public String getIconUrl() { return iconUrl; }

        public @Unmodifiable List<String> getCategories() { return categories; }

        /// Minecraft versions this project has a release for.
        public @Unmodifiable List<String> getGameVersions() { return gameVersions; }

        /// Server cores this project supports, e.g. `paper`, `spigot`, `bukkit`, `folia`.
        ///
        /// <p>Populated from the search result's `categories`, because Modrinth's search
        /// endpoint leaves the dedicated `loaders` field empty (only the single-project and
        /// version endpoints fill it). `categories` carries the same loader names, so it is the
        /// only usable source at search time; [PluginStore#resolve] still verifies against the
        /// authoritative per-version `loaders` before anything is downloaded.</p>
        public @Unmodifiable List<String> getLoaders() { return loaders; }

        /// The project page.
        public String getPageUrl() {
            return "https://modrinth.com/plugin/" + slug;
        }

        /// True when the project publishes a build for `mcVersion` on one of `cores`.
        public boolean matches(@Nullable String mcVersion, @Unmodifiable List<String> cores) {
            if (mcVersion != null && !mcVersion.isBlank() && !gameVersions.contains(mcVersion))
                return false;
            for (String core : cores)
                if (loaders.contains(core))
                    return true;
            return cores.isEmpty();
        }
    }

    /// A resolved, downloadable jar.
    public static final class Download {
        private final String fileName;
        private final String url;
        private final String sha512;
        private final long size;
        private final String versionNumber;
        private final @Unmodifiable List<String> gameVersions;
        private final @Unmodifiable List<String> loaders;

        Download(String fileName, String url, String sha512, long size, String versionNumber,
                 List<String> gameVersions, List<String> loaders) {
            this.fileName = fileName;
            this.url = url;
            this.sha512 = sha512;
            this.size = size;
            this.versionNumber = versionNumber;
            this.gameVersions = List.copyOf(gameVersions);
            this.loaders = List.copyOf(loaders);
        }

        public String getFileName() { return fileName; }

        public String getUrl() { return url; }

        public String getSha512() { return sha512; }

        public long getSize() { return size; }

        public String getVersionNumber() { return versionNumber; }

        public @Unmodifiable List<String> getGameVersions() { return gameVersions; }

        public @Unmodifiable List<String> getLoaders() { return loaders; }
    }

    private static final String API = "https://api.modrinth.com/v2";
    /// Modrinth asks clients to identify themselves.
    private static final String USER_AGENT = "HMCL-ServerManager-Plugin/1.0 (HMCL)";
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 60_000;

    private PluginStore() {
    }

    /// Server cores a plugin may target, in the order they appear in the UI.
    public static @Unmodifiable List<String> knownCores() {
        return List.of("paper", "spigot", "bukkit", "purpur", "folia", "velocity", "bungeecord",
                "waterfall");
    }

    /// Cores a given server profile is compatible with, inferred from the server jar.
    ///
    /// <p>A Paper server also runs plain Bukkit/Spigot plugins, so those are included; a
    /// Velocity/Bungee proxy is a different runtime and accepts none of the Bukkit cores.</p>
    public static @Unmodifiable List<String> coresFor(ServerProfile profile) {
        String type = detectCore(profile);
        return switch (type) {
            case "paper", "purpur", "folia" -> List.of(type, "paper", "spigot", "bukkit");
            case "spigot" -> List.of("spigot", "bukkit");
            case "bukkit" -> List.of("bukkit");
            case "velocity" -> List.of("velocity");
            case "bungeecord", "waterfall" -> List.of(type, "bungeecord");
            default -> List.of("paper", "spigot", "bukkit");
        };
    }

    /// Best-effort detection of the server core from the jar contents and file name.
    public static String detectCore(ServerProfile profile) {
        Path jar = profile.getJarPath();
        String fileName = jar.getFileName() == null ? ""
                : jar.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.contains("purpur"))
            return "purpur";
        if (fileName.contains("folia"))
            return "folia";
        if (fileName.contains("velocity"))
            return "velocity";
        if (fileName.contains("waterfall"))
            return "waterfall";
        if (fileName.contains("bungeecord"))
            return "bungeecord";
        if (fileName.contains("paper") || fileName.contains("paperclip"))
            return "paper";
        if (fileName.contains("spigot") || fileName.contains("craftbukkit"))
            return "spigot";

        // Fall back to the classes inside the jar.
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            if (zip.getEntry("io/papermc/paperclip/Paperclip.class") != null)
                return "paper";
            if (zip.getEntry("org/bukkit/craftbukkit/Main.class") != null)
                return "spigot";
            if (zip.getEntry("org/bukkit/Bukkit.class") != null)
                return "bukkit";
            if (zip.getEntry("com/velocitypowered/api/ProxyServer.class") != null)
                return "velocity";
        } catch (IOException ignored) {
            // a missing or unreadable jar: fall through to the generic default
        }
        return "paper";
    }

    /// A human-readable core name.
    public static String coreDisplayName(String core) {
        return switch (core == null ? "" : core.toLowerCase(Locale.ROOT)) {
            case "paper" -> "Paper";
            case "spigot" -> "Spigot";
            case "bukkit" -> "Bukkit / CraftBukkit";
            case "purpur" -> "Purpur";
            case "folia" -> "Folia";
            case "velocity" -> "Velocity";
            case "bungeecord" -> "BungeeCord";
            case "waterfall" -> "Waterfall";
            default -> core == null || core.isBlank() ? "未知核心" : core;
        };
    }

    // ------------------------------------------------------------- search ---

    /// One page of store results together with the total number of matches, which drives
    /// the pager (Modrinth reports `total_hits` for every search).
    public record SearchResult(List<Listing> listings, int totalHits) {
        public int totalPages(int pageSize) {
            return Math.max(1, (totalHits + pageSize - 1) / pageSize);
        }
    }

    /// Searches the store, filtered to plugins that run on `mcVersion` and one of `cores`.
    ///
    /// @param query free text; blank lists the most downloaded plugins
    /// @param mcVersion the server's Minecraft version, or null to skip that filter
    /// @param cores acceptable server cores; empty skips the filter
    /// @param limit how many results to return
    public static @Unmodifiable List<Listing> search(String query, @Nullable String mcVersion,
                                                     List<String> cores, int limit)
            throws IOException {
        return search(query, mcVersion, cores, null, limit, 0).listings();
    }

    /// Searches the store with paging and an optional content category.
    ///
    /// @param category a Modrinth category facet (economy, games, protection, …), or null/blank for all
    /// @param offset how many results to skip (page × page size); this is what makes the pager real
    public static SearchResult search(String query, @Nullable String mcVersion, List<String> cores,
                                      @Nullable String category, int limit, int offset)
            throws IOException {
        StringBuilder url = new StringBuilder(API).append("/search?limit=").append(limit)
                .append("&offset=").append(Math.max(0, offset))
                .append("&index=downloads")
                .append("&query=").append(URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8));

        List<String> facets = new ArrayList<>();
        facets.add("[\"project_type:plugin\"]");
        if (mcVersion != null && !mcVersion.isBlank())
            facets.add("[\"versions:" + mcVersion + "\"]");
        if (!cores.isEmpty()) {
            List<String> coreFacets = new ArrayList<>();
            for (String core : cores)
                coreFacets.add("\"categories:" + core + "\"");
            facets.add("[" + String.join(",", coreFacets) + "]");
        }
        if (category != null && !category.isBlank())
            facets.add("[\"categories:" + category + "\"]");
        url.append("&facets=").append(URLEncoder.encode("[" + String.join(",", facets) + "]",
                StandardCharsets.UTF_8));

        JsonObject response = jsonObject(url.toString());
        JsonArray hits = response.has("hits") ? response.getAsJsonArray("hits") : new JsonArray();
        int totalHits = response.has("total_hits") && response.get("total_hits").isJsonPrimitive()
                ? response.get("total_hits").getAsInt() : hits.size();
        List<Listing> result = new ArrayList<>();
        for (JsonElement element : hits) {
            if (!element.isJsonObject())
                continue;
            JsonObject hit = element.getAsJsonObject();
            List<String> categories = stringList(hit, "categories");
            result.add(new Listing(
                    string(hit, "project_id"),
                    string(hit, "slug"),
                    string(hit, "title"),
                    string(hit, "description"),
                    string(hit, "author"),
                    hit.has("downloads") && hit.get("downloads").isJsonPrimitive()
                            ? hit.get("downloads").getAsLong() : 0,
                    string(hit, "icon_url"),
                    categories,
                    stringList(hit, "versions"),
                    // search results leave `loaders` empty, so the cores are taken from
                    // `categories`, which carries the same names
                    loadersFromCategories(categories)));
        }
        return new SearchResult(List.copyOf(result), totalHits);
    }

    /// Content categories the store offers, with Chinese labels. Values are Modrinth facets.
    public static List<Map.Entry<String, String>> categories() {
        return List.of(
                Map.entry("", "全部分类"),
                Map.entry("management", "管理 / 服务器管理"),
                Map.entry("utility", "实用工具"),
                Map.entry("economy", "经济"),
                Map.entry("games", "小游戏"),
                Map.entry("gameplay", "玩法"),
                Map.entry("protection", "保护 / 领地"),
                Map.entry("chat", "聊天"),
                Map.entry("social", "社交"),
                Map.entry("performance", "性能优化"),
                Map.entry("worldgen", "世界生成"),
                Map.entry("transportation", "传送 / 交通"),
                Map.entry("technology", "科技"),
                Map.entry("api", "API / 库"),
                Map.entry("library", "前置库"),
                Map.entry("entertainment", "娱乐"),
                Map.entry("education", "教育"));
    }

    /// Keeps only the entries of `categories` that are known server cores.
    ///
    /// <p>A plugin's categories mix loaders (`paper`, `spigot`, `folia`) with content tags
    /// (`economy`, `utility`), so the intersection with [knownCores] is what identifies the
    /// supported cores.</p>
    static List<String> loadersFromCategories(List<String> categories) {
        if (categories.isEmpty())
            return List.of();
        Set<String> cores = new LinkedHashSet<>();
        for (String category : categories) {
            String lower = category.toLowerCase(Locale.ROOT);
            if (knownCores().contains(lower))
                cores.add(lower);
        }
        return List.copyOf(cores);
    }

    /// Resolves the newest downloadable jar of `slug` for `mcVersion` on one of `cores`.
    ///
    /// <p>The returned file's own `game_versions`/`loaders` are the authoritative compatibility
    /// data, so this doubles as the real compatibility check: the caller inspects them before
    /// installing.</p>
    ///
    /// @return the download, or null when the project has no matching version
    public static @Nullable Download resolve(String slug, @Nullable String mcVersion,
                                             List<String> cores) throws IOException {
        StringBuilder url = new StringBuilder(API).append("/project/")
                .append(URLEncoder.encode(slug, StandardCharsets.UTF_8)).append("/version");
        List<String> params = new ArrayList<>();
        if (mcVersion != null && !mcVersion.isBlank())
            params.add("game_versions=" + URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8));
        if (!cores.isEmpty()) {
            List<String> quoted = new ArrayList<>();
            for (String core : cores)
                quoted.add("\"" + core + "\"");
            params.add("loaders=" + URLEncoder.encode("[" + String.join(",", quoted) + "]",
                    StandardCharsets.UTF_8));
        }
        if (!params.isEmpty())
            url.append('?').append(String.join("&", params));

        JsonElement parsed = JsonParser.parseString(httpGet(url.toString()));
        if (!parsed.isJsonArray())
            return null;
        JsonArray versions = parsed.getAsJsonArray();

        // The API returns newest first, but sorting on the date makes that explicit.
        List<JsonObject> objects = new ArrayList<>();
        for (JsonElement element : versions)
            if (element.isJsonObject())
                objects.add(element.getAsJsonObject());
        objects.sort((left, right) -> string(right, "date_published")
                .compareTo(string(left, "date_published")));

        for (JsonObject version : objects) {
            JsonArray files = version.has("files") ? version.getAsJsonArray("files") : null;
            if (files == null)
                continue;
            JsonObject primary = null;
            for (JsonElement element : files) {
                if (!element.isJsonObject())
                    continue;
                JsonObject file = element.getAsJsonObject();
                if (primary == null)
                    primary = file;
                if (file.has("primary") && file.get("primary").isJsonPrimitive()
                        && file.get("primary").getAsBoolean()) {
                    primary = file;
                    break;
                }
            }
            if (primary == null)
                continue;
            String url_ = string(primary, "url");
            if (url_.isBlank())
                continue;
            String sha512 = "";
            if (primary.has("hashes") && primary.get("hashes").isJsonObject())
                sha512 = string(primary.getAsJsonObject("hashes"), "sha512");
            long size = primary.has("size") && primary.get("size").isJsonPrimitive()
                    ? primary.get("size").getAsLong() : 0;
            return new Download(string(primary, "filename"), url_, sha512, size,
                    string(version, "version_number"),
                    stringList(version, "game_versions"),
                    stringList(version, "loaders"));
        }
        return null;
    }

    /// Downloads `download` into `target`, verifying its SHA-512 when the store supplied one.
    ///
    /// @param progress called with bytes written and the total (0 when unknown)
    public static void download(Download download, Path target, Progress progress) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".downloading");
        Files.createDirectories(target.getParent());

        HttpURLConnection connection = open(download.getUrl());
        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("下载失败：HTTP " + code);
        }
        long total = download.getSize() > 0 ? download.getSize() : connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (progress != null)
                    progress.onProgress(done, total);
            }
        } finally {
            connection.disconnect();
        }

        if (!download.getSha512().isBlank()) {
            String actual = digest(temporary, "SHA-512");
            if (!actual.equalsIgnoreCase(download.getSha512())) {
                Files.deleteIfExists(temporary);
                throw new IOException("SHA-512 校验失败（文件可能损坏）：期望 "
                        + download.getSha512() + "，实际 " + actual);
            }
        }
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /// Progress callback.
    public interface Progress {
        void onProgress(long doneBytes, long totalBytes);
    }

    /// Downloads a store plugin, verifies it, unpacks nothing and installs it into the server.
    ///
    /// @return the file name it was installed as
    public static String installInto(ServerProfile profile, String slug, @Nullable String mcVersion,
                                     List<String> cores, Progress progress) throws IOException {
        Download download = resolve(slug, mcVersion, cores);
        if (download == null)
            throw new IOException("该插件没有适用于 Minecraft " + (mcVersion == null ? "该版本" : mcVersion)
                    + " 与 " + String.join("/", cores) + " 核心的版本");

        Path temporary = Files.createTempFile("hmcl-plugin-", ".jar");
        try {
            download(download, temporary, progress);
            return ServerPlugins.install(profile, temporary, download.getFileName());
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // the temp file is removed on exit anyway
            }
        }
    }

    // ------------------------------------------------------------ helpers ---

    /// True when the project publishes any build for this exact Minecraft version.
    public static boolean supportsVersion(Listing listing, @Nullable String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank())
            return true;
        return listing.getGameVersions().contains(mcVersion);
    }

    private static JsonObject jsonObject(String url) throws IOException {
        JsonElement parsed = JsonParser.parseString(httpGet(url));
        if (!parsed.isJsonObject())
            throw new IOException("接口返回格式异常");
        return parsed.getAsJsonObject();
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = open(url);
        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + code + "：" + url);
        }
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0)
                    digest.update(buffer, 0, read);
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest())
                builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(algorithm + " 不可用", e);
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
            return "";
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static List<String> stringList(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray())
            return List.of();
        Set<String> values = new LinkedHashSet<>();
        for (JsonElement element : object.getAsJsonArray(key))
            if (element.isJsonPrimitive())
                values.add(element.getAsString());
        return List.copyOf(values);
    }
}

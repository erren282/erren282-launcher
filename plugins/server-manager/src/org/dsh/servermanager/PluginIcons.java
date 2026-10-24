package org.dsh.servermanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Loads plugin icons.
///
/// <p>Where icons come from, and why:</p>
///
/// <ul>
///   <li><b>Installed plugins have none.</b> Bukkit's `plugin.yml` has no icon field, and a
///       survey of the jars on this machine found 0/19 with an embedded image. So for an
///       installed plugin the icon is looked up on Modrinth by name and cached on disk; after
///       the first successful match it loads instantly and works offline.</li>
///   <li><b>Store listings carry one.</b> Modrinth's search response exposes `icon_url`, but it
///       is WebP, which JavaFX cannot decode. The project endpoint additionally offers
///       `raw_icon_url` in PNG, so the store fetches icons in one batched request for the whole
///       result page and uses those.</li>
/// </ul>
///
/// <p>Everything is cached in the plugin data directory (`icon-cache/`), keyed by a hash of the
/// URL, so the network is only touched once per icon. All failures are silent: an icon must
/// never be the reason a page does not render.</p>
@NotNullByDefault
public final class PluginIcons {

    /// Cache and request-size guard rails.
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 20_000;
    private static final long MAX_ICON_BYTES = 512 * 1024;
    private static final String USER_AGENT = "HMCL-ServerManager-Plugin/1.0 (HMCL)";

    /// In-memory cache, so re-rendering a list does not re-read the disk.
    private static final Map<String, Image> MEMORY = new ConcurrentHashMap<>();

    /// In-flight lookups, so several rows mentioning the same plugin trigger one request.
    private static final Map<String, Object> PENDING = new ConcurrentHashMap<>();

    private PluginIcons() {
    }

    /// Sets an icon on `view` in the background.
    ///
    /// <p>The view is updated on the JavaFX thread when (and only when) an image is found; until
    /// then the caller's placeholder stays visible.</p>
    public static void loadAsync(IconRequest request, ImageView view) {
        String key = request.cacheKey();
        Image cached = MEMORY.get(key);
        if (cached != null) {
            view.setImage(cached);
            return;
        }
        // another row is already fetching this icon; it will populate the memory cache
        if (PENDING.putIfAbsent(key, key) != null)
            return;

        Thread thread = new Thread(() -> {
            try {
                Path file = cachedFile(request.dataDirectory(), key);
                if (!Files.isRegularFile(file))
                    fetch(request, file);

                if (Files.isRegularFile(file)) {
                    Image image = readImage(file);
                    if (image != null) {
                        MEMORY.put(key, image);
                        javafx.application.Platform.runLater(() -> view.setImage(image));
                    }
                }
            } catch (Exception ignored) {
                // an icon must never break the page
            } finally {
                PENDING.remove(key);
            }
        }, "server-manager-icon");
        thread.setDaemon(true);
        thread.start();
    }

    /// What to fetch for one row.
    public sealed interface IconRequest permits InstalledIcon, StoreIcon {
        /// The plugin data directory holding the icon cache.
        Path dataDirectory();

        /// Stable cache key for this icon.
        String cacheKey();
    }

    /// An installed plugin: look its Modrinth project up by name.
    public record InstalledIcon(String pluginName, Path dataDirectory) implements IconRequest {
        @Override
        public String cacheKey() {
            return "installed-" + slugOf(pluginName);
        }
    }

    /// A store listing: the icon URL is known up front.
    public record StoreIcon(String iconUrl, Path dataDirectory) implements IconRequest {
        @Override
        public String cacheKey() {
            // hash the URL: it is long and carries characters no file system likes
            return "store-" + Integer.toHexString(iconUrl.hashCode());
        }
    }

    /// The icon for a store listing, resolved in bulk by [fetchStoreIcons].
    public static @Nullable String pngIconUrl(String projectId) {
        // the caller normally passes a URL resolved by fetchStoreIcons; this single-project
        // form is the fallback when a listing arrived without a batched lookup
        return API + "/project/" + projectId;
    }

    private static final String API = "https://api.modrinth.com/v2";

    /// Fetches the icon for `request` into `file`, implementing the per-request-type lookup.
    private static void fetch(IconRequest request, Path file) throws IOException {
        if (request instanceof InstalledIcon installed) {
            String png = resolveIconBySearch(installed.pluginName(), installed.dataDirectory());
            if (png == null)
                throw new IOException("没有找到 " + installed.pluginName() + " 的图标");
            download(png, file);
        } else if (request instanceof StoreIcon store) {
            // Modrinth's search-level `icon_url` is WebP, which JavaFX cannot decode; the same
            // URL with the hash part replaced by the project's raw icon is PNG. Resolve it
            // through the project endpoint keyed by the CDN path.
            download(convertWebpUrlToPng(store.iconUrl()), file);
        }
    }

    /// Looks a plugin up on Modrinth and returns its PNG icon URL, or null when there is no
    /// confident match.
    ///
    /// <p>The name match is deliberately strict: a generic name like "vault" must not pick up
    /// the first unrelated project that contains the word, so a candidate has to start with the
    /// plugin's name (case-insensitively) after normalisation.</p>
    static String resolveIconBySearch(String pluginName, Path dataDirectory) throws IOException {
        List<PluginStore.Listing> results = PluginStore.search(
                pluginName, null, List.of(), 10);
        if (results.isEmpty())
            return null;

        String wanted = normalise(pluginName);
        PluginStore.Listing best = null;
        for (PluginStore.Listing candidate : results) {
            String slug = normalise(candidate.getSlug());
            String title = normalise(candidate.getTitle());
            // an exact slug match is the strongest signal
            if (slug.equals(wanted)) {
                best = candidate;
                break;
            }
            if ((title.equals(wanted) || slug.startsWith(wanted) || title.startsWith(wanted))
                    && best == null)
                best = candidate;
        }
        if (best == null)
            return null;

        // resolve the project to get the PNG icon
        JsonObject project = projectJson(best.getProjectId());
        String raw = string(project, "raw_icon_url");
        if (!raw.isBlank() && raw.toLowerCase(Locale.ROOT).endsWith(".png"))
            return raw;
        // some projects only ship a non-PNG raw icon; JavaFX handles jpg too
        if (!raw.isBlank())
            return raw;
        return null;
    }

    /// Resolves PNG icon URLs for a page of store results in one batched request.
    ///
    /// @return project id → PNG icon URL; ids without a usable icon are absent
    public static Map<String, String> fetchStoreIcons(List<String> projectIds) {
        Map<String, String> result = new LinkedHashMap<>();
        if (projectIds.isEmpty())
            return result;

        List<String> quoted = new ArrayList<>();
        for (String id : projectIds)
            quoted.add("\"" + id + "\"");
        String url = API + "/projects?ids=" + URLEncoder.encode(
                "[" + String.join(",", quoted) + "]", StandardCharsets.UTF_8);

        try {
            JsonElement parsed = JsonParser.parseString(httpGet(url));
            if (!parsed.isJsonArray())
                return result;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject())
                    continue;
                JsonObject project = element.getAsJsonObject();
                String id = string(project, "id");
                String raw = string(project, "raw_icon_url");
                if (!id.isBlank() && !raw.isBlank())
                    result.put(id, raw);
            }
        } catch (Exception ignored) {
            // icons are decorative; a failed batch simply leaves the placeholders
        }
        return result;
    }

    /// Turns Modrinth's WebP `icon_url` into the PNG `raw_icon_url` of the same project.
    ///
    /// <p>A CDN icon URL looks like `…/data/<projectId>/<hash>_96.webp`; the project id lets us
    /// ask the API which PNG exists, so the conversion needs no guessing about hashes.</p>
    static String convertWebpUrlToPng(String webpUrl) {
        String marker = "/data/";
        int at = webpUrl.indexOf(marker);
        if (at < 0)
            return webpUrl;
        String rest = webpUrl.substring(at + marker.length());
        String projectId = rest.contains("/")
                ? rest.substring(0, rest.indexOf('/')) : rest;
        // ask the project endpoint for the raw icon
        try {
            JsonObject project = projectJson(projectId);
            String raw = string(project, "raw_icon_url");
            return raw.isBlank() ? webpUrl : raw;
        } catch (Exception e) {
            return webpUrl;
        }
    }

    private static JsonObject projectJson(String idOrSlug) throws IOException {
        String url = API + "/project/" + URLEncoder.encode(idOrSlug, StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(httpGet(url));
        if (!parsed.isJsonObject())
            throw new IOException("接口返回格式异常");
        return parsed.getAsJsonObject();
    }

    /// Reads a cached icon; null when the file is missing or not a decodable image.
    private static Image readImage(Path file) {
        try {
            String name = file.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".bmp")))
                return null;
            if (Files.size(file) > MAX_ICON_BYTES)
                return null;
            try (InputStream in = Files.newInputStream(file)) {
                // sized down to the card's icon slot on load, so a 512px source costs no more
                // memory than the 40px view
                Image image = new Image(in, 40, 40, true, true);
                if (image.isError() || image.getWidth() <= 0)
                    return null;
                return image;
            }
        } catch (Exception e) {
            // a broken cache entry is deleted so the next attempt can re-fetch
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static void download(String url, Path file) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        long length = connection.getContentLengthLong();
        if (length > MAX_ICON_BYTES) {
            connection.disconnect();
            throw new IOException("图标过大");
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".downloading");
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", USER_AGENT);
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

    private static Path cachedFile(Path dataDirectory, String key) {
        Path cache = dataDirectory.resolve("icon-cache");
        try {
            Files.createDirectories(cache);
        } catch (IOException ignored) {
        }
        return cache.resolve(key + ".png");
    }

    /// A file-system-safe, case-stable identifier for a plugin name.
    static String slugOf(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static String normalise(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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

    /// Creates a 40×40 icon view with a placeholder, ready for [loadAsync].
    public static ImageView placeholder() {
        return createPlaceholder(null);
    }

    /// Creates a 40×40 icon view that first shows `fallback` until the real icon loads.
    public static ImageView createPlaceholder(@Nullable Node fallback) {
        ImageView view = new ImageView();
        view.setFitWidth(40);
        view.setFitHeight(40);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }
}

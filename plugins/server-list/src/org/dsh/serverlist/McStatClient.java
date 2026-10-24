package org.dsh.serverlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Client for the mcstat.org server directory API (https://mcstat.org/api-docs).
///
/// <p>The directory carries 6000+ community servers with icons, banners, live player counts
/// and tags — far beyond the bundled catalog. The API requires a free API key; without one
/// every request answers 401, so the UI collects and stores the key first.</p>
public final class McStatClient {

    private static final String API = "https://mcstat.org/api/v1";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /// One directory result.
    public record RemoteServer(String name, String address, String version, String motd,
                               String iconUrl, String bannerUrl, int online, int max,
                               String slug, List<String> tags, boolean reachable) {

        public String pageUrl() {
            return "https://mcstat.org/servers/" + slug;
        }
    }

    /// One page of directory results.
    public record Page(List<RemoteServer> servers, int page, int totalPages, int total) {
    }

    private McStatClient() {
    }

    /// Browses the directory.
    ///
    /// @param sort  players (most players), hot, votes, recent, uptime, new
    /// @param tag   a content tag (economy, games, survival…), or null/blank for all
    public static Page servers(String apiKey, String query, String tag, String sort,
                               int page, int limit) throws IOException {
        if (apiKey == null || apiKey.isBlank())
            throw new IOException("尚未配置 mcstat.org API Key");

        StringBuilder url = new StringBuilder(API).append("/servers")
                .append("?limit=").append(Math.max(1, Math.min(limit, 50)))
                .append("&page=").append(Math.max(1, page))
                .append("&sort=").append(sort == null || sort.isBlank() ? "players" : sort);
        if (query != null && !query.isBlank())
            url.append("&search=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        if (tag != null && !tag.isBlank())
            url.append("&tag=").append(URLEncoder.encode(tag, StandardCharsets.UTF_8));

        JsonObject root = get(url.toString(), apiKey);
        if (!root.has("data") || !root.getAsJsonObject("data").has("servers"))
            throw new IOException("响应缺少 servers 字段");

        JsonObject data = root.getAsJsonObject("data");
        List<RemoteServer> servers = new ArrayList<>();
        for (JsonElement element : data.getAsJsonArray("servers")) {
            JsonObject server = element.getAsJsonObject();
            List<String> tags = new ArrayList<>();
            if (server.has("tags") && server.get("tags").isJsonArray())
                for (JsonElement tagElement : server.getAsJsonArray("tags"))
                    tags.add(tagElement.getAsString());
            servers.add(new RemoteServer(
                    string(server, "name"),
                    string(server, "ip"),
                    string(server, "version"),
                    string(server, "motd"),
                    stringOrNull(server, "iconUrl"),
                    stringOrNull(server, "bannerUrl"),
                    intOf(server, "currentPlayers"),
                    intOf(server, "maxPlayers"),
                    string(server, "slug"),
                    tags,
                    !server.has("isOnline") || server.get("isOnline").getAsBoolean()));
        }

        int total = servers.size(), totalPages = 1, page0 = 1;
        if (data.has("pagination") && data.get("pagination").isJsonObject()) {
            JsonObject pagination = data.getAsJsonObject("pagination");
            total = intOf(pagination, "total");
            totalPages = Math.max(1, intOf(pagination, "totalPages"));
            page0 = Math.max(1, intOf(pagination, "page"));
        }
        return new Page(List.copyOf(servers), page0, totalPages, total);
    }

    private static JsonObject get(String url, String apiKey) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-Key", apiKey)
                .timeout(Duration.ofSeconds(12))
                .GET().build();
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() == 401)
            throw new IOException("API Key 无效或未配置（请到 mcstat.org 免费获取）");
        if (response.statusCode() == 429)
            throw new IOException("请求过于频繁：mcstat.org 限流中，请稍后再试");
        if (response.statusCode() != 200)
            throw new IOException("mcstat.org 返回 HTTP " + response.statusCode()
                    + (body.isBlank() ? "" : "：" + body.substring(0, Math.min(120, body.length()))));
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    private static String stringOrNull(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : null;
    }

    private static int intOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsInt() : 0;
    }

    /// The stored mcstat.org API key for this plugin instance (empty when not configured).
    public static String loadKey(Path dataDirectory) {
        Path file = dataDirectory.resolve("mcstat-settings.json");
        if (!Files.isRegularFile(file))
            return "";
        try {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return object.has("apiKey") && object.get("apiKey").isJsonPrimitive()
                    ? object.get("apiKey").getAsString() : "";
        } catch (Exception e) {
            LOG.warning("Failed to read mcstat-settings.json", e);
            return "";
        }
    }

    /// Persists the mcstat.org API key.
    public static void saveKey(Path dataDirectory, String key) {
        Path file = dataDirectory.resolve("mcstat-settings.json");
        JsonObject object = new JsonObject();
        object.addProperty("apiKey", key == null ? "" : key.trim());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, object.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("Failed to save mcstat-settings.json", e);
        }
    }
}

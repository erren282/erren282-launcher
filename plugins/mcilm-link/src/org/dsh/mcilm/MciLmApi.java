/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.dsh.mcilm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/// Minimal client for the public MciLm-link platform API.
///
/// <p>Only the two endpoints a launcher needs are used: the latest CLI release (to fetch and
/// keep the child process binary up to date) and its download URL. Everything here is
/// login-free; the room itself is created by the child process, which owns the user's token
/// and the billing flow.</p>
@SuppressWarnings("unused")
public final class MciLmApi {

    /// The public API base URL.
    public static final String BASE_URL = "https://link.mcilm.top";

    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /// One downloadable artifact of a release.
    ///
    /// @param platform  `windows` / `linux` / `macos`
    /// @param arch      `x64` / `arm64`
    /// @param buildType `cli` / `gui` / `lib`
    /// @param url       the download URL
    public record Artifact(String platform, String arch, String buildType, String url) {
    }

    /// The latest CLI release.
    ///
    /// @param version   the version string, e.g. `0.2.3`
    /// @param artifacts every artifact of the release
    public record Release(String version, List<Artifact> artifacts) {

        /// Returns the CLI artifact matching the given platform and architecture, or `null`.
        public Artifact cli(String platform, String arch) {
            for (Artifact artifact : artifacts) {
                if ("cli".equals(artifact.buildType())
                        && platform.equals(artifact.platform())
                        && arch.equals(artifact.arch()))
                    return artifact;
            }
            return null;
        }
    }

    private MciLmApi() {
    }

    /// Queries the latest CLI release.
    ///
    /// @return the release
    /// @throws IOException when the API is unreachable or the response is malformed
    public static Release fetchLatestCli() throws IOException {
        String body = get("/api/public/releases/latest?channel=linkc");
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject release = root.has("release") && root.get("release").isJsonObject()
                ? root.getAsJsonObject("release")
                : root;

        String version = release.has("version") ? release.get("version").getAsString() : "";
        List<Artifact> artifacts = new ArrayList<>();
        if (release.has("artifacts") && release.get("artifacts").isJsonArray()) {
            for (var element : release.getAsJsonArray("artifacts")) {
                if (!element.isJsonObject())
                    continue;
                JsonObject object = element.getAsJsonObject();
                if (object.has("enabled") && !object.get("enabled").getAsBoolean())
                    continue;
                artifacts.add(new Artifact(
                        string(object, "platform"),
                        string(object, "arch"),
                        string(object, "build_type"),
                        string(object, "url")));
            }
        }
        return new Release(version, List.copyOf(artifacts));
    }

    /// Signs in with an account name and password.
    ///
    /// <p>The platform validates the password through its central authentication service and
    /// hands back both a short-lived JWT and a long-lived `mci_` token; the latter is what the
    /// CLI client needs for API mode.</p>
    ///
    /// @param account  the account name
    /// @param password the password
    /// @return the session, containing at least the persistent token
    /// @throws IOException when the credentials are rejected or the API is unreachable
    public static Session login(String account, String password) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("account", account);
        payload.addProperty("password", password);

        JsonObject response = post("/api/auth/sso/login", payload.toString());
        return new Session(
                string(response, "token"),
                string(response, "api_token"),
                string(response, "username"),
                string(response, "nickname"));
    }

    /// Reads the persistent token of the signed-in user.
    ///
    /// @param token a JWT or `mci_` token
    /// @return the persistent token
    /// @throws IOException when the token is rejected
    public static String fetchApiToken(String token) throws IOException {
        JsonObject response = getJson("/api/auth/me", token);
        String apiToken = string(response, "api_token");
        if (apiToken.isEmpty())
            throw new IOException("The response did not contain api_token");
        return apiToken;
    }

    /// One signed-in session.
    ///
    /// @param jwt      the short-lived session token
    /// @param apiToken the long-lived persistent token used by the CLI
    /// @param username the account name
    /// @param nickname the display name
    public record Session(String jwt, String apiToken, String username, String nickname) {
    }

    /// Downloads a file into memory.
    ///
    /// @param url the URL to download
    /// @return the bytes
    /// @throws IOException when the download fails
    public static byte[] download(String url) throws IOException {
        String normalized = url.endsWith("#") ? url.substring(0, url.length() - 1) : url;
        HttpRequest request = HttpRequest.newBuilder(URI.create(normalized))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2)
                throw new IOException("HTTP " + response.statusCode() + " while downloading " + normalized);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + normalized, e);
        }
    }

    /// Returns the platform identifier the API uses for the current OS.
    public static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win"))
            return "windows";
        if (os.contains("mac"))
            return "macos";
        return "linux";
    }

    /// Returns the architecture identifier the API uses for the current JVM.
    public static String currentArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
    }

    private static String get(String path) throws IOException {
        return getJson(path, null).toString();
    }

    private static JsonObject getJson(String path, String bearer) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET();
        if (bearer != null && !bearer.isEmpty())
            builder.header("Authorization", "Bearer " + bearer);
        return send(builder.build(), path);
    }

    private static JsonObject post(String path, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        return send(request, path);
    }

    private static JsonObject send(HttpRequest request, String path) throws IOException {
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + path, e);
        }

        String body = response.body() == null ? "" : response.body();
        int status = response.statusCode();
        if (status == 401)
            throw new IOException("账号或密码错误，或登录状态已失效");
        if (status == 403)
            throw new IOException("账号已被封禁，请联系平台管理员");
        if (status == 429)
            throw new IOException("尝试次数过多，请稍后再试");
        if (status / 100 != 2)
            throw new IOException("HTTP " + status + " from " + path + ": " + body);

        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed response from " + path, e);
        }
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }
}

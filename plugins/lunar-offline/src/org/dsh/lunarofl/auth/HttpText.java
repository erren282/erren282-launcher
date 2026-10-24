package org.dsh.lunarofl.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/// 微软 / Xbox / Mojang 认证请求的极薄封装（java.net.http）。
///
/// <p>与官方 Lunar 启动器一致的几个端点常量也放在这里，方便对照：
/// v2 令牌端点、旧版 live 令牌端点、XBL / XSTS、Minecraft 登录。</p>
public final class HttpText {

    // 与官方启动器（app.asar 反混淆）里一致的客户端与端点
    public static final String CLIENT_ID_MOJANG = "00000000402b5328";
    public static final String CLIENT_ID_LUNAR = "4358653d-21f6-4697-96bb-7963ff974196";
    public static final String SCOPE_LEGACY = "service::user.auth.xboxlive.com::MBI_SSL";
    public static final String SCOPE_V2 = "XboxLive.signin offline_access";

    public static final String TOKEN_V2 = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    public static final String TOKEN_LEGACY = "https://login.live.com/oauth20_token.srf";
    public static final String CONNECT_LEGACY = "https://login.live.com/oauth20_connect.srf";
    public static final String XBL_AUTHENTICATE = "https://user.auth.xboxlive.com/user/authenticate";
    public static final String XSTS_AUTHORIZE = "https://xsts.auth.xboxlive.com/xsts/authorize";
    public static final String MC_LOGIN_WITH_XBOX = "https://api.minecraftservices.com/authentication/login_with_xbox";
    public static final String SESSION_JOIN = "https://sessionserver.mojang.com/session/minecraft/join";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpText() {
    }

    /// 一个响应：状态码 + 响应体文本。认证端点的错误也返回 4xx/5xx 的响应体（里面有 error 描述），
    /// 由调用方决定怎么处理。
    public record Response(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /// 把响应体当 JSON 解析（认证端点都返回 JSON）。
        /// 解析失败抛 {@link java.io.UncheckedIOException}（record 访问器不能抛受检异常）。
        public Map<String, Object> json() {
            try {
                return MiniJson.parseObject(body);
            } catch (RuntimeException e) {
                throw new java.io.UncheckedIOException(new IOException(
                        "HTTP " + status + " 返回的不是 JSON：" + abbreviate(body), e));
            }
        }

        private static String abbreviate(String text) {
            return text.length() > 200 ? text.substring(0, 200) + "…" : text;
        }
    }

    /// POST application/x-www-form-urlencoded。
    public static Response postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!first)
                body.append('&');
            first = false;
            body.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    /// POST application/json。
    public static Response postJson(String url, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    /// POST application/json，带 Authorization 头（Minecraft services 用）。
    public static Response postJsonAuthorized(String url, String json, String bearer)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    public static Response getAuthorized(String url, String bearer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .GET()
                .build();
        return send(request);
    }

    private static Response send(HttpRequest request) throws IOException {
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP 请求被中断", e);
        }
        return new Response(response.statusCode(), response.body());
    }

    /// 构造表单参数的小工具。
    public static Map<String, String> form(Object... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
            result.put(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
        return result;
    }
}

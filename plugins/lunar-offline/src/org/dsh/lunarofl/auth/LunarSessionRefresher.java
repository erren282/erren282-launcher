package org.dsh.lunarofl.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Lunar 账户会话刷新：把 `accounts.json` 里过期的 Minecraft 访问令牌续期。
///
/// <p>官方启动器的做法（从 app.asar 反混淆得到，`refreshAccountInternal` / `vAe`）：</p>
/// <ol>
///   <li>拿账户里存的微软 MSA 刷新令牌去换新的 MSA 访问令牌——用哪个客户端、哪个端点
///       由现有 MC 令牌 JWT 的 {@code aid} 声明决定：
///       <ul>
///         <li>{@code aid=4358653d-…}（Lunar 自己的 Azure 应用，弹窗 PKCE 登录）
///             → v2 端点 `login.microsoftonline.com/consumers/oauth2/v2.0/token`，
///             scope `XboxLive.signin offline_access`；</li>
///         <li>{@code aid=00000000402b5328}（Mojang 启动器应用，设备码登录）
///             → 旧版端点 `login.live.com/oauth20_token.srf`，
///             scope `service::user.auth.xboxlive.com::MBI_SSL`。</li>
///       </ul></li>
///   <li>MSA 令牌 → Xbox Live 用户令牌（user.auth.xboxlive.com，RPS 票据）→
///       XSTS 令牌（xsts.auth.xboxlive.com，RP `rp://api.minecraftservices.com/`）；</li>
///   <li>XBL3.0 票据 → `api.minecraftservices.com/authentication/login_with_xbox`
///       → 新的 Minecraft 访问令牌（24 小时有效）。</li>
///   <li>把新令牌、新过期时间、轮换后的 MSA 刷新令牌写回 accounts.json。</li>
/// </ol>
///
/// <p>多人游戏 join 走的是原版 `sessionserver.mojang.com/session/minecraft/join`，
/// 用 refreshAccountInternal 里同一份 accessToken——这就是"Lunar 里微软登录失效、进不了
/// 多人游戏"的直接原因：令牌过期后游戏要把刷新请求发给启动器（gameipc），而我们的启动
/// 方式没有这一环。本类补上启动器侧的那一半。</p>
public final class LunarSessionRefresher {

    /// 日志出口：包装类写到 stderr/文件，插件写到 HMCL 日志。
    public interface Log {
        void line(String message);
    }

    /// 刷新令牌已失效（微软返回 invalid_grant），需要用户重新登录。
    public static final class InvalidSessionException extends IOException {
        public InvalidSessionException(String message) {
            super(message);
        }
    }

    /// 一次 refreshAll 的汇总。
    public record Result(int refreshed, int skipped, int failed, List<String> notes) {
    }

    /// 令牌剩余寿命低于这个值就刷新（游戏启动加载也要几秒，留出余量）。
    private static final long REFRESH_MARGIN_SECONDS = 300;

    private LunarSessionRefresher() {
    }

    // ---------- 批量 ----------

    /// 刷新 accounts.json 里所有需要刷新的账户并原子写回。
    public static Result refreshAll(Log log, boolean force) throws IOException {
        Map<String, Object> root = LunarAccounts.load();
        Map<String, Object> accounts = LunarAccounts.accounts(root);
        int refreshed = 0;
        int skipped = 0;
        int failed = 0;
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : accounts.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                skipped++;
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> account = (Map<String, Object>) entry.getValue();
            String name = LunarAccounts.profileName(account);
            if (name == null || name.isBlank())
                name = entry.getKey() != null ? entry.getKey() : "?";
            if (!force && !needsRefresh(account)) {
                log.line("[会话] " + name + "：令牌仍有效，跳过");
                skipped++;
                continue;
            }
            if (LunarAccounts.refreshToken(account) == null) {
                log.line("[会话] " + name + "：没有 MSA 刷新令牌，无法静默刷新（需要重新登录）");
                notes.add(name + ": 缺少刷新令牌");
                failed++;
                continue;
            }
            try {
                refreshAccount(account, log);
                refreshed++;
            } catch (InvalidSessionException e) {
                log.line("[会话] " + name + "：" + e.getMessage());
                notes.add(name + ": " + e.getMessage());
                failed++;
            }
        }
        if (refreshed > 0)
            LunarAccounts.save(root);
        return new Result(refreshed, skipped, failed, notes);
    }

    /// 账户令牌是否已过期或即将过期（没有令牌/解析不了过期时间时视为需要刷新）。
    public static boolean needsRefresh(Map<String, Object> account) {
        String accessToken = LunarAccounts.accessToken(account);
        if (accessToken == null || accessToken.isBlank())
            return true;
        Long exp = LunarAccounts.exp(account);
        if (exp != null)
            return exp - Instant.now().getEpochSecond() < REFRESH_MARGIN_SECONDS;
        Instant expiresAt = LunarAccounts.expiresAt(account);
        if (expiresAt != null)
            return expiresAt.minusSeconds(REFRESH_MARGIN_SECONDS).isBefore(Instant.now());
        return true;
    }

    // ---------- 单账户 ----------

    /// 刷新单个账户对象（不写文件，由调用方统一 save）。
    @SuppressWarnings("unchecked")
    public static void refreshAccount(Map<String, Object> account, Log log) throws IOException {
        String name = LunarAccounts.profileName(account);
        if (name == null || name.isBlank())
            name = "未知账户";
        String refreshToken = LunarAccounts.refreshToken(account);
        String aid = LunarAccounts.aid(account);
        log.line("[会话] " + name + "：开始刷新（aid=" + (aid == null ? "未知" : aid) + "）");

        MsaTokens msa = refreshMsa(refreshToken, aid, log);

        // XBL 票据前缀与官方一致：v2 流程用 d=，旧版流程用 t=
        String rpsPrefix = HttpText.CLIENT_ID_LUNAR.equals(msa.usedClient()) ? "d" : "t";
        McTokens tokens = mintMcToken(msa, rpsPrefix, log);

        LunarAccounts.setAccessToken(account, tokens.accessToken(), tokens.expiresAt(), msa.refreshToken());
        log.line("[会话] " + name + "：已更新，新令牌有效期至 " + tokens.expiresAt());
    }

    /// 一次完整的 XBL → XSTS → Minecraft 换令牌。
    public record McTokens(String accessToken, Instant expiresAt, String xuid, String msaRefreshToken) {
    }

    /// MSA 刷新结果（用哪个客户端换的，决定后续 XBL 票据前缀）。
    public record MsaTokens(String accessToken, String refreshToken, String usedClient) {
    }

    /// 按账户的 aid 选择端点刷新 MSA 令牌；aid 未知时先旧版后 v2 依次尝试。
    public static MsaTokens refreshMsa(String refreshToken, String aid, Log log) throws IOException {
        IOException last = null;
        String[] order;
        if (HttpText.CLIENT_ID_LUNAR.equals(aid)) {
            order = new String[]{HttpText.CLIENT_ID_LUNAR};
        } else if (HttpText.CLIENT_ID_MOJANG.equals(aid)) {
            order = new String[]{HttpText.CLIENT_ID_MOJANG};
        } else {
            order = new String[]{HttpText.CLIENT_ID_MOJANG, HttpText.CLIENT_ID_LUNAR};
        }
        for (int i = 0; i < order.length; i++) {
            String client = order[i];
            try {
                return refreshMsaWithClient(refreshToken, client, log);
            } catch (InvalidSessionException e) {
                // 刷新令牌本身失效：换端点也没用，直接判死
                throw e;
            } catch (IOException e) {
                last = e;
                if (i + 1 < order.length)
                    log.line("[会话] 用客户端 " + client + " 刷新失败，尝试下一个端点：" + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("MSA 刷新失败");
    }

    private static MsaTokens refreshMsaWithClient(String refreshToken, String clientId, Log log)
            throws IOException {
        boolean v2 = HttpText.CLIENT_ID_LUNAR.equals(clientId);
        String endpoint = v2 ? HttpText.TOKEN_V2 : HttpText.TOKEN_LEGACY;
        String scope = v2 ? HttpText.SCOPE_V2 : HttpText.SCOPE_LEGACY;
        HttpText.Response response;
        try {
            response = HttpText.postForm(endpoint, HttpText.form(
                    "client_id", clientId,
                    "refresh_token", refreshToken,
                    "grant_type", "refresh_token",
                    "scope", scope));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MSA 刷新被中断", e);
        }
        if (!response.ok()) {
            Map<String, Object> body = safeJson(response);
            String error = string(body.get("error"));
            if ("invalid_grant".equals(error))
                throw new InvalidSessionException("微软登录已失效（refresh_token 被拒），需要重新登录微软账户");
            throw new IOException("MSA 刷新失败 HTTP " + response.status() + "：" + errorOrBody(response));
        }
        Map<String, Object> body = response.json();
        String accessToken = string(body.get("access_token"));
        String newRefresh = string(body.get("refresh_token"));
        if (accessToken == null)
            throw new IOException("MSA 刷新响应缺少 access_token");
        return new MsaTokens(accessToken, newRefresh != null ? newRefresh : refreshToken, clientId);
    }

    /// XBL → XSTS → login_with_xbox。票据前缀可重试：先按官方对应流程，401 时换前缀再试一次。
    public static McTokens mintMcToken(MsaTokens msa, String rpsPrefix, Log log) throws IOException {
        IOException last = null;
        for (String prefix : rpsPrefix.equals("t") ? new String[]{"t", "d"} : new String[]{"d", "t"}) {
            try {
                return mintMcTokenOnce(msa, prefix, log);
            } catch (InvalidSessionException | XboxAccountException e) {
                throw e; // XSTS 的 XErr 与前缀无关，不必重试
            } catch (IOException e) {
                last = e;
                log.line("[会话] XBL 票据前缀 " + prefix + "= 失败，尝试另一种：" + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("Xbox 认证失败");
    }

    /// Xbox 侧拒绝（封禁 / 未注册 Xbox / 年龄验证等），不需要重试。
    public static final class XboxAccountException extends IOException {
        public XboxAccountException(String message) {
            super(message);
        }
    }

    private static McTokens mintMcTokenOnce(MsaTokens msa, String rpsPrefix, Log log) throws IOException {
        // 1) XBL 用户令牌
        Map<String, Object> xblBody = new LinkedHashMap<>();
        xblBody.put("RelyingParty", "http://auth.xboxlive.com");
        xblBody.put("TokenType", "JWT");
        Map<String, Object> xblProperties = new LinkedHashMap<>();
        xblProperties.put("AuthMethod", "RPS");
        xblProperties.put("SiteName", "user.auth.xboxlive.com");
        xblProperties.put("RpsTicket", rpsPrefix + "=" + msa.accessToken());
        xblBody.put("Properties", xblProperties);
        HttpText.Response xbl = postJsonChecked(HttpText.XBL_AUTHENTICATE, xblBody, "Xbox Live 认证");
        String userToken = string(xbl.json().get("Token"));
        if (userToken == null)
            throw new IOException("Xbox Live 响应缺少 Token");

        // 2) XSTS 令牌
        Map<String, Object> xstsBody = new LinkedHashMap<>();
        xstsBody.put("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.put("TokenType", "JWT");
        Map<String, Object> xstsProperties = new LinkedHashMap<>();
        xstsProperties.put("SandboxId", "RETAIL");
        xstsProperties.put("UserTokens", List.of(userToken));
        xstsBody.put("Properties", xstsProperties);
        HttpText.Response xsts;
        try {
            xsts = postJsonChecked(HttpText.XSTS_AUTHORIZE, xstsBody, "XSTS 授权");
        } catch (IOException e) {
            throw translateXsts(e);
        }
        String xstsToken = string(xsts.json().get("Token"));
        String uhs = extractUhs(xsts.json());
        if (xstsToken == null || uhs == null)
            throw new IOException("XSTS 响应缺少 Token / uhs");

        // 3) Minecraft 访问令牌
        Map<String, Object> mcBody = new LinkedHashMap<>();
        mcBody.put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        HttpText.Response mc;
        try {
            mc = postJsonChecked(HttpText.MC_LOGIN_WITH_XBOX, mcBody, "Minecraft 登录");
        } catch (IOException e) {
            throw new IOException("Minecraft 登录失败：" + e.getMessage(), e);
        }
        Map<String, Object> mcJson = mc.json();
        String accessToken = string(mcJson.get("access_token"));
        if (accessToken == null)
            throw new IOException("Minecraft 登录响应缺少 access_token");
        long expiresIn = 86400;
        Object expiresInValue = mcJson.get("expires_in");
        if (expiresInValue instanceof Number number)
            expiresIn = number.longValue();
        Instant expiresAt = Instant.now().plusSeconds(expiresIn);
        String xuid = string(LunarAccounts.decodeJwtPayload(accessToken).get("xuid"));
        log.line("[会话] Minecraft 令牌已取得（xuid=" + xuid + "，有效期 " + expiresIn + " 秒）");
        return new McTokens(accessToken, expiresAt, xuid, msa.refreshToken());
    }

    // ---------- 设备码登录（AddAccount / 刷新令牌已死时的兜底） ----------

    /// 设备码流程的交互界面：展示 user code 并打开浏览器。
    ///
    /// <p>实现方应把验证码复制到剪贴板并弹窗显示（浏览器页面会要求输入它）。
    /// 登录成功/失败/取消后调用方会调 [#close()] 收尾。</p>
    public interface DeviceCodeUi {
        void showCode(String userCode, String verificationUrl);

        /// 登录流程结束（无论成败）时关闭界面。
        default void close() {
        }
    }

    /// 旧版设备码流程（与官方启动器 `startDeviceCodeLogin` 一致）：
    /// oauth20_connect.srf 请求设备码 → 浏览器授权 → 轮询 oauth20_token.srf 换令牌。
    /// 成功返回完整令牌链，调用方负责写进 accounts.json。
    public static McTokens loginWithDeviceCode(Log log, DeviceCodeUi ui) throws IOException {
        HttpText.Response codeResponse;
        try {
            codeResponse = HttpText.postForm(HttpText.CONNECT_LEGACY, HttpText.form(
                    "client_id", HttpText.CLIENT_ID_MOJANG,
                    "scope", HttpText.SCOPE_LEGACY,
                    "response_type", "device_code"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("设备码请求被中断", e);
        }
        if (!codeResponse.ok())
            throw new IOException("设备码请求失败 HTTP " + codeResponse.status());
        Map<String, Object> codeJson = codeResponse.json();
        String deviceCode = string(codeJson.get("device_code"));
        String userCode = string(codeJson.get("user_code"));
        String verificationUrl = deviceCodeUrl(codeJson, userCode);
        if (deviceCode == null || userCode == null || verificationUrl == null)
            throw new IOException("设备码响应不完整");
        long expiresIn = number(codeJson.get("expires_in"), 900);
        long interval = Math.max(1, number(codeJson.get("interval"), 5));
        log.line("[会话] 设备码已取得：" + userCode + "（" + expiresIn + " 秒内有效）");
        ui.showCode(userCode, verificationUrl);

        try {
            return pollDeviceCode(log, deviceCode, userCode, expiresIn, interval);
        } finally {
            ui.close();
        }
    }

    /// 构造给用户打开的验证页地址。
    ///
    /// <p>官方启动器：`verificationUriComplete ?? 在 verificationUri 上追加 otc=user_code`
    /// （见 app.asar 里的 `zEe`）。旧版 oauth20_connect.srf 不返回 complete 字段，
    /// 只给 `https://www.microsoft.com/link`——不追加 otc 的话页面会要求用户手动输入验证码；
    /// 追加之后微软页面会自动填入（也是官方 v2 端点的 complete 地址格式）。</p>
    public static String deviceCodeUrl(Map<String, Object> codeJson, String userCode) {
        String complete = string(codeJson.get("verification_uri_complete"));
        if (complete != null && !complete.isBlank())
            return complete;
        String base = string(codeJson.get("verification_uri"));
        if (base == null || base.isBlank() || userCode == null || userCode.isBlank())
            return base;
        try {
            java.net.URI uri = java.net.URI.create(base);
            String query = uri.getRawQuery();
            String separator = query == null || query.isEmpty() ? "?" : "&";
            return base + separator + "otc=" + java.net.URLEncoder.encode(userCode, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return base;
        }
    }

    private static McTokens pollDeviceCode(Log log, String deviceCode, String userCode,
                                           long expiresIn, long interval) throws IOException {
        Instant deadline = Instant.now().plusSeconds(expiresIn);
        long wait = interval;
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(wait * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("设备码轮询被中断", e);
            }
            HttpText.Response poll;
            try {
                poll = HttpText.postForm(HttpText.TOKEN_LEGACY, HttpText.form(
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id", HttpText.CLIENT_ID_MOJANG,
                        "device_code", deviceCode));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("设备码轮询被中断", e);
            }
            Map<String, Object> pollJson = poll.ok() ? poll.json() : safeJson(poll);
            String error = string(pollJson.get("error"));
            if (error == null) {
                String msAccessToken = string(pollJson.get("access_token"));
                String msRefreshToken = string(pollJson.get("refresh_token"));
                if (msAccessToken == null)
                    throw new IOException("设备码响应缺少 access_token");
                log.line("[会话] 微软授权完成（" + userCode + "），正在换取 Xbox / Minecraft 令牌");
                MsaTokens msa = new MsaTokens(msAccessToken,
                        msRefreshToken != null ? msRefreshToken : "", HttpText.CLIENT_ID_MOJANG);
                // 设备码流程拿到的票据用 t= 前缀（与官方 DW 一致）
                return mintMcToken(msa, "t", log);
            }
            switch (error) {
                case "authorization_pending" -> {
                    // 继续等
                }
                case "slow_down" -> wait += 5;
                case "expired_token" -> throw new IOException("设备码已过期，请重试");
                case "authorization_declined", "access_denied" -> throw new IOException("用户取消了微软登录");
                default -> log.line("[会话] 设备码轮询：" + error + "，继续等待");
            }
        }
        throw new IOException("设备码超时，未完成授权");
    }

    /// 把一次成功的令牌链写进 accounts.json（新增或覆盖同 profile 的账户）。
    ///
    /// <p>官方文件里的 profile id 是无横线的紧凑形式，这里保持一致。</p>
    public static void storeAccount(McTokens tokens, String profileId, String profileName) throws IOException {
        String compactId = profileId == null ? "" : profileId.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> root = LunarAccounts.load();
        Map<String, Object> accounts = LunarAccounts.accounts(root);
        Map<String, Object> target = null;
        for (Object value : accounts.values()) {
            if (!(value instanceof Map<?, ?> account))
                continue;
            Object profile = account.get("minecraftProfile");
            if (profile instanceof Map<?, ?>) {
                Object id = ((Map<?, ?>) profile).get("id");
                if (id != null && compactId.equalsIgnoreCase(id.toString().replace("-", ""))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> casted = (Map<String, Object>) value;
                    target = casted;
                    break;
                }
            }
        }
        if (target == null) {
            target = new LinkedHashMap<>();
            target.put("eligibleForMigration", false);
            target.put("hasMultipleProfiles", false);
            target.put("legacy", false);
            target.put("persistent", true);
            target.put("localId", java.util.UUID.randomUUID().toString().replace("-", ""));
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", compactId);
            profile.put("name", profileName);
            target.put("minecraftProfile", profile);
            target.put("type", "Xbox");
            target.put("username", profileName);
            accounts.put(string(target.get("localId")), target);
        }
        // remoteId 是 Xbox 用户 id，load() 会 getAsString() 它 —— 写 null 会让整个账户
        // 在游戏里解析失败（表现为卡片可见但点不动）。拿不到就保留旧值，再不行给空串。
        if (tokens.xuid() != null && !tokens.xuid().isBlank()) {
            target.put("remoteId", tokens.xuid());
        } else if (target.get("remoteId") == null) {
            target.put("remoteId", "");
        }
        if (target.get("type") == null)
            target.put("type", "Xbox");
        if (target.get("username") == null && profileName != null)
            target.put("username", profileName);
        LunarAccounts.setAccessToken(target, tokens.accessToken(), tokens.expiresAt(), tokens.msaRefreshToken());
        // 设为当前账户：这是"启动时自动使用启动器里选中的账户"的落点
        root.put("activeAccountLocalId", LunarAccounts.localId(target));
        LunarAccounts.save(root);
    }

    // ---------- 内部 ----------

    private static HttpText.Response postJsonChecked(String url, Object body, String what)
            throws IOException {
        HttpText.Response response;
        try {
            response = HttpText.postJson(url, MiniJson.write(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(what + "被中断", e);
        }
        if (!response.ok())
            throw new IOException(what + "失败 HTTP " + response.status() + "：" + errorOrBody(response));
        return response;
    }

    private static IOException translateXsts(IOException e) {
        // 401 的响应体里有 XErr；把常见原因翻译成人话
        if (e.getMessage() != null && e.getMessage().contains("HTTP 401")) {
            String message = e.getMessage();
            if (message.contains("2148916233"))
                return new XboxAccountException("该微软账户没有注册 Xbox 账号");
            if (message.contains("2148916238") || message.contains("2148916236") || message.contains("2148916237"))
                return new XboxAccountException("需要完成 Xbox 家长/年龄验证");
            if (message.contains("2148916227"))
                return new XboxAccountException("Xbox 账号被封禁");
            return new XboxAccountException("XSTS 授权被拒绝（" + message + "）");
        }
        return e;
    }

    private static String extractUhs(Map<String, Object> xstsJson) {
        Object displayClaims = xstsJson.get("DisplayClaims");
        if (!(displayClaims instanceof Map))
            return null;
        Object xui = ((Map<?, ?>) displayClaims).get("xui");
        if (!(xui instanceof List) || ((List<?>) xui).isEmpty())
            return null;
        Object first = ((List<?>) xui).get(0);
        if (!(first instanceof Map))
            return null;
        return string(((Map<?, ?>) first).get("uhs"));
    }

    private static Map<String, Object> safeJson(HttpText.Response response) {
        try {
            return response.json();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private static String errorOrBody(HttpText.Response response) {
        Map<String, Object> body = safeJson(response);
        String error = string(body.get("error"));
        if (error != null) {
            String description = string(body.get("error_description"));
            return description != null ? error + "（" + description + "）" : error;
        }
        String text = response.body();
        return text == null ? "" : (text.length() > 200 ? text.substring(0, 200) + "…" : text);
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number n)
            return n.longValue();
        if (value instanceof String s)
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        return fallback;
    }
}

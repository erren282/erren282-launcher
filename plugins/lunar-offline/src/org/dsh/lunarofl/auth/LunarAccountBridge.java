package org.dsh.lunarofl.auth;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/// 把启动器当前选中的正版账户搬进 Lunar 的 `accounts.json`，并把它设为当前账户。
///
/// <h2>为什么需要</h2>
/// Lunar 客户端只认自己的 `~/.lunarclient/settings/game/accounts.json`：里面每个账户带
/// `accessToken`（Minecraft JWT）与 `refreshToken`（微软 MSA 刷新令牌），游戏启动后读它决定
/// "我是谁"。官方启动器负责写这份文件；我们用 HMCL 启动 Lunar 时，如果不写，游戏就会停在
/// 账户选择界面（或者用上一次的旧账户），与启动器里选的那个根本不是一个人。
///
/// <h2>令牌从哪来</h2>
/// [MicrosoftAccount#writePrivateData] 会把整份会话（`accessToken` / `refreshToken` /
/// `profileName` / `notAfter`）写进一个 `JsonObject` —— 这是启动器自己持久化账户用的公开钩子，
/// 所以不需要反射读私有字段。拿到 MSA 刷新令牌后，按 Lunar 的格式换一次令牌即可：
/// 它要求的是 `login_with_xbox` 签发的 JWT（带 `aid`/`exp`），而不是启动器内部那种会话对象。
///
/// <p>换令牌失败（例如网络问题）时不阻断启动：退回到"直接把启动器手里的 MC 访问令牌写进去"，
/// 游戏至少能用正确的身份起起来，过期后由 [LunarGameIpcServer] 的 RefreshAccount 续期。</p>
@NotNullByDefault
public final class LunarAccountBridge {

    /// 一次同步的结果。
    ///
    /// @param profileName 同步过去的角色名
    /// @param profileId   角色 UUID（无横线）
    /// @param switched    是否已在 accounts.json 里设为当前账户
    /// @param refreshed   是否成功用 MSA 刷新令牌换到了新令牌
    public record Result(String profileName, String profileId, boolean switched, boolean refreshed) {
    }

    private LunarAccountBridge() {
    }

    /// 启动器当前选中的是不是可同步的正版账户。
    public static boolean hasGenuineAccount() {
        return selectedMicrosoftAccount() != null;
    }

    /// 选中的微软账户，或 `null`。
    private static @Nullable MicrosoftAccount selectedMicrosoftAccount() {
        try {
            Account account = org.jackhuang.hmcl.setting.Accounts.getSelectedAccount();
            return account instanceof MicrosoftAccount microsoft ? microsoft : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /// 把启动器选中的正版账户同步进 Lunar 的 accounts.json。
    ///
    /// @param log 进度输出
    /// @return 同步结果；没有可同步的正版账户时返回 `null`
    public static @Nullable Result syncSelectedAccount(LunarSessionRefresher.Log log) throws IOException {
        MicrosoftAccount account = selectedMicrosoftAccount();
        if (account == null) {
            log.line("[账户] 启动器当前没有选中正版账户，跳过同步");
            return null;
        }

        String profileName = account.getProfileName();
        java.util.UUID profileUuid = account.getProfileID();
        if (profileUuid == null) {
            log.line("[账户] 启动器账户缺少角色 UUID，跳过同步");
            return null;
        }
        String profileId = profileUuid.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);

        // 从启动器的会话里取令牌（公开钩子，不碰私有字段）
        JsonObject privateData = new JsonObject();
        try {
            account.writePrivateData(privateData);
        } catch (Throwable t) {
            log.line("[账户] 读取启动器账户令牌失败：" + t);
            return null;
        }
        String msaRefreshToken = stringOf(privateData, "refreshToken");
        String accessToken = stringOf(privateData, "accessToken");
        long notAfter = longOf(privateData, "notAfter");

        log.line("[账户] 同步启动器账户 " + profileName + "（" + profileId + "）到 Lunar");

        // 优先用 MSA 刷新令牌换一份 Lunar 格式的令牌：这样 exp/aid 都是 Lunar 认识的样子。
        LunarSessionRefresher.McTokens tokens = null;
        if (msaRefreshToken != null && !msaRefreshToken.isBlank()) {
            try {
                String aid = LunarAccounts.aid(Map.of("accessToken", accessToken == null ? "" : accessToken));
                LunarSessionRefresher.MsaTokens msa =
                        LunarSessionRefresher.refreshMsa(msaRefreshToken, aid, log);
                String rpsPrefix = org.dsh.lunarofl.auth.HttpText.CLIENT_ID_LUNAR.equals(msa.usedClient())
                        ? "d" : "t";
                tokens = LunarSessionRefresher.mintMcToken(msa, rpsPrefix, log);
            } catch (LunarSessionRefresher.InvalidSessionException e) {
                log.line("[账户] MSA 刷新令牌已失效（" + e.getMessage() + "），改用启动器现有的访问令牌");
            } catch (IOException e) {
                log.line("[账户] 换取 Lunar 令牌失败（" + e.getMessage() + "），改用启动器现有的访问令牌");
            }
        } else {
            log.line("[账户] 启动器账户没有 MSA 刷新令牌，只能写入现有访问令牌");
        }

        // 换不到就用启动器手里的访问令牌顶上
        if (tokens == null) {
            if (accessToken == null || accessToken.isBlank()) {
                log.line("[账户] 既换不到新令牌、也没有现成访问令牌，跳过同步");
                return null;
            }
            Instant expiresAt = notAfter > 0
                    ? Instant.ofEpochMilli(notAfter)
                    : Instant.now().plusSeconds(3600);
            tokens = new LunarSessionRefresher.McTokens(accessToken, expiresAt, null, msaRefreshToken);
        }

        // 写进 accounts.json 并设为当前账户
        LunarSessionRefresher.storeAccount(tokens, profileId, profileName);
        log.line("[账户] 已写入 Lunar：当前账户 = " + profileName
                + (tokens.expiresAt() == null ? "" : "（令牌有效至 " + tokens.expiresAt() + "）"));
        return new Result(profileName, profileId, true, tokens != null && msaRefreshToken != null);
    }

    private static @Nullable String stringOf(JsonObject object, String key) {
        try {
            if (object.has(key) && object.get(key).isJsonPrimitive())
                return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /// `Map` 版本（accounts.json 用 MiniJson 解析成 Map 而不是 JsonObject）。
    private static @Nullable String stringOf(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String text ? text : (value == null ? null : String.valueOf(value));
    }

    private static long longOf(JsonObject object, String key) {
        try {
            if (object.has(key) && object.get(key).isJsonPrimitive())
                return object.get(key).getAsLong();
        } catch (RuntimeException ignored) {
        }
        return 0L;
    }

    /// 规范化 accounts.json 里的每个账户，让游戏内「点头像切换账户」能真正生效。
    ///
    /// <h2>为什么需要</h2>
    /// Lunar 的账户卡片由 `load(JsonObject)` 反序列化，随后点击某张卡片时调用
    /// `CIHICOCRHROOCHOHHIHIHICCHOOHHH()`：它用**该账户自己的 `accessToken`** 构造会话
    /// （`getSessionFactory()(name, id, getAccessToken(), "msa")`）。所以只要某个账户的
    /// 必需字段缺失或格式不对，卡片照样能画出来（有名字和头像），但点下去构造出的会话是
    /// `null`，切换就静默失败——表现就是"只能重新登录才能换账户"。
    ///
    /// <p>`load()` 明确要求这些字段：`localId`、`minecraftProfile{id,name}`、`remoteId`、
    /// `type`（且必须是 `MOJANG` / `XBOX`，代码里走 `valueOf(type.toUpperCase())`，写错会直接抛异常）。
    /// 这里把它们补齐，并确保 `accessToken` 非空、`accessTokenExpiresAt` 是合法 ISO 时间。</p>
    ///
    /// @return 修了几个账户（不需要修时为 0）
    public static int normalizeAccounts(LunarSessionRefresher.Log log) throws IOException {
        Map<String, Object> root = LunarAccounts.load();
        Map<String, Object> accounts = LunarAccounts.accounts(root);
        if (accounts.isEmpty())
            return 0;

        int fixed = 0;
        for (Map.Entry<String, Object> entry : accounts.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw))
                continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> account = (Map<String, Object>) raw;
            boolean changed = false;

            // localId：缺失时用 key 补上（游戏把它当账户唯一标识，也是 activeAccountLocalId 的值）
            if (isBlank(LunarAccounts.localId(account))) {
                account.put("localId", entry.getKey() == null
                        ? java.util.UUID.randomUUID().toString().replace("-", "")
                        : entry.getKey());
                changed = true;
            }

            // type：只认 MOJANG / XBOX（大小写不敏感）。旧数据里可能有 "msa"/"microsoft" 之类。
            Object typeValue = account.get("type");
            String type = typeValue instanceof String text ? text.trim() : "";
            if (!type.equalsIgnoreCase("XBOX") && !type.equalsIgnoreCase("MOJANG")) {
                account.put("type", "XBOX");
                changed = true;
            }

            // remoteId：Xbox 账户的远程 id；缺失时给个空串（load() 会对 null 抛 NPE）
            if (account.get("remoteId") == null) {
                account.put("remoteId", "");
                changed = true;
            }

            // username：UI 显示用，缺失时跟随角色名
            if (isBlank(stringOf(account, "username"))) {
                String profileName = LunarAccounts.profileName(account);
                if (!isBlank(profileName)) {
                    account.put("username", profileName);
                    changed = true;
                }
            }

            // minecraftProfile 必须存在且有 id/name（load() 直接 getAsJsonObject()，null 会炸）
            Map<String, Object> profile = LunarAccounts.profile(account);
            if (isBlank(stringOf(profile, "id")) || profile.get("name") == null) {
                log.line("[账户] 账户 " + LunarAccounts.profileName(account) + " 缺少角色信息，跳过");
                continue;
            }

            // accessTokenExpiresAt：必须是能被 Instant.parse 接受的 ISO 时间，否则 load() 抛异常
            String expiresAt = stringOf(account, "accessTokenExpiresAt");
            if (!isBlank(expiresAt)) {
                try {
                    Instant.parse(expiresAt);
                } catch (RuntimeException e) {
                    account.remove("accessTokenExpiresAt");
                    changed = true;
                }
            }

            if (changed)
                fixed++;
        }

        if (fixed > 0) {
            LunarAccounts.save(root);
            log.line("[账户] 已规范化 " + fixed + " 个账户（补齐 localId/type/remoteId/username）");
        } else {
            log.line("[账户] accounts.json 无需规范化");
        }
        return fixed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /// 让启动器把刷新后的令牌落盘（启动器自己那份账户文件）。
    ///
    /// <p>同步过程中可能已经刷新过 MSA 令牌，若只写进 Lunar 那份而启动器手里还是旧的，
    /// 下次启动会拿旧令牌去换、白白多一次失败重试。这里尽力而为，失败不影响主流程。</p>
    static void persistLauncherAccount() {
        try {
            org.jackhuang.hmcl.setting.Accounts.forceOverwriteAccountFiles();
        } catch (Throwable ignored) {
            // 纯持久化，失败不影响同步结果
        }
    }

    /// 当前启动器账户的 AuthInfo（给需要令牌的调用方复用）。
    static @Nullable AuthInfo currentAuthInfo() {
        MicrosoftAccount account = selectedMicrosoftAccount();
        if (account == null)
            return null;
        try {
            return account.logIn();
        } catch (Throwable t) {
            return null;
        }
    }
}

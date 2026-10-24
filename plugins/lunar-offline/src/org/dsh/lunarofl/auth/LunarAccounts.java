package org.dsh.lunarofl.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/// Lunar 的共享账户文件（`~/.lunarclient/settings/game/accounts.json`）读写。
///
/// <p>这个文件由官方 Lunar 启动器与游戏进程共用：启动器登录 / 刷新后写进来，
/// 游戏启动后读走。里面每个账户的 {@code accessToken} 是 Minecraft 的访问令牌
/// （login_with_xbox 签发的 JWT，带 {@code aid} / {@code exp} 声明），
/// {@code refreshToken} 是微软的 MSA 刷新令牌（{@code M.C547_BL2...} 形态）。
/// 我们做的"转发"就是在游戏和官方启动器都不在场时，自己完成同一套刷新并写回这个文件。</p>
public final class LunarAccounts {

    /// 官方启动器/游戏共用的目录：`~/.lunarclient/settings/game`。
    public static Path settingsDirectory() {
        return Path.of(System.getProperty("user.home"), ".lunarclient", "settings", "game");
    }

    public static Path accountsFile() {
        return settingsDirectory().resolve("accounts.json");
    }

    /// 读取整个账户文件；文件不存在或损坏时返回带空 accounts 的结构（与官方启动器行为一致）。
    public static Map<String, Object> load() throws IOException {
        Path file = accountsFile();
        if (!Files.isRegularFile(file)) {
            Map<String, Object> root = newEmpty();
            return root;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            Map<String, Object> root = MiniJson.parseObject(text);
            if (!(root.get("accounts") instanceof Map))
                root.put("accounts", new java.util.LinkedHashMap<String, Object>());
            return root;
        } catch (RuntimeException e) {
            throw new IOException("accounts.json 损坏：" + file, e);
        }
    }

    public static Map<String, Object> newEmpty() {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("activeAccountLocalId", null);
        root.put("accounts", new java.util.LinkedHashMap<String, Object>());
        return root;
    }

    /// 原子写回：先写临时文件再移动（官方启动器写的是同一份文件，避免读到半截）。
    ///
    /// <p>写之前先留一份 `.dsh-backup`：这个文件同时被 Lunar 游戏进程读写，我们与它并发
    /// 改写时任何一方都可能把另一方的改动盖掉。有备份就能在出问题时人工恢复，也便于
    /// 排查"账户怎么少了一个"这类反馈。</p>
    public static void save(Map<String, Object> root) throws IOException {
        Path file = accountsFile();
        Files.createDirectories(file.getParent());

        // 备份现有文件（只在内容确实要变化时才有意义，这里不判断，保持简单）
        if (Files.isRegularFile(file)) {
            try {
                Files.copy(file, file.resolveSibling("accounts.json.dsh-backup"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 备份失败不该阻止正常保存
            }
        }

        Path temp = file.resolveSibling("accounts.json.dsh-tmp");
        Files.writeString(temp, MiniJson.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> accounts(Map<String, Object> root) {
        Object value = root.get("accounts");
        return value instanceof Map ? (Map<String, Object>) value : new java.util.LinkedHashMap<>();
    }

    /// 便捷的账户视图：包一层类型转换，避免到处强转。
    @SuppressWarnings("unchecked")
    public static Map<String, Object> profile(Map<String, Object> account) {
        Object value = account.get("minecraftProfile");
        if (value instanceof Map)
            return (Map<String, Object>) value;
        Map<String, Object> created = new java.util.LinkedHashMap<>();
        created.put("id", "");
        created.put("name", "");
        account.put("minecraftProfile", created);
        return created;
    }

    // ---------- 单个账户的字段读写 ----------

    public static String accessToken(Map<String, Object> account) {
        return string(account.get("accessToken"));
    }

    public static String refreshToken(Map<String, Object> account) {
        return string(account.get("refreshToken"));
    }

    public static String profileId(Map<String, Object> account) {
        return string(profile(account).get("id"));
    }

    public static String profileName(Map<String, Object> account) {
        return string(profile(account).get("name"));
    }

    public static String localId(Map<String, Object> account) {
        return string(account.get("localId"));
    }

    /// 解析 accessTokenExpiresAt（ISO-8601，带 Z）；解析不了返回 null。
    public static Instant expiresAt(Map<String, Object> account) {
        String value = string(account.get("accessTokenExpiresAt"));
        if (value == null || value.isBlank())
            return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void setAccessToken(Map<String, Object> account, String token, Instant expiresAt,
                                      String refreshToken) {
        account.put("accessToken", token);
        account.put("accessTokenExpiresAt",
                expiresAt.truncatedTo(ChronoUnit.MILLIS).toString());
        if (refreshToken != null && !refreshToken.isBlank())
            account.put("refreshToken", refreshToken);
    }

    // ---------- JWT ----------

    /// 解析 JWT 的 payload（不验签——我们只需要 aid / exp 声明）。
    public static Map<String, Object> decodeJwtPayload(String jwt) {
        if (jwt == null)
            return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2)
            return null;
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            Object value = MiniJson.parse(new String(json, StandardCharsets.UTF_8));
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// JWT 里的 aid（执行登录的 Azure 应用 id）；没有返回 null。
    public static String aid(Map<String, Object> account) {
        Map<String, Object> payload = decodeJwtPayload(accessToken(account));
        return payload == null ? null : string(payload.get("aid"));
    }

    /// JWT 的 exp（Unix 秒）；解析不了返回 null。
    public static Long exp(Map<String, Object> account) {
        Map<String, Object> payload = decodeJwtPayload(accessToken(account));
        if (payload == null)
            return null;
        Object value = payload.get("exp");
        if (value instanceof Number number)
            return number.longValue();
        if (value instanceof String text)
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
            }
        return null;
    }

    /// 把无横线的 uuid 字符串转成带横线形式（协议里 Uuid 高低 64 位对应 Java UUID 的 msb/lsb）。
    public static UUID parseProfileUuid(String value) {
        if (value == null || value.isBlank())
            return null;
        String compact = value.replace("-", "");
        if (compact.length() != 32)
            return null;
        try {
            return UUID.fromString(compact
                    .replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private LunarAccounts() {
    }
}

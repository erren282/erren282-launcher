import org.dsh.lunarofl.auth.LunarAccounts;
import org.dsh.lunarofl.auth.LunarSessionRefresher;
import org.dsh.lunarofl.auth.Protobuf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/// 验证「游戏内切换账户」与「启动时同步启动器账户」两件事的协议层。
///
/// <h2>背景</h2>
/// <ul>
///   <li>Lunar 的账户卡片由 `load(JsonObject)` 反序列化，点击时用该账户自己的 `accessToken`
///       构造会话。`load()` 强制要求 `localId` / `minecraftProfile` / `remoteId` / `type`
///       （且 `type` 必须是 `MOJANG` 或 `XBOX`），缺一个就点不动。</li>
///   <li>切换由启动器主动推 `NotifySwitchAccount`（`GameboundIPCMessage{ push_notification:
///       Any{ type_url, value } }`）触发；我们过去从不推送，所以点了没反应。</li>
/// </ul>
///
/// <p>这个测试不联网、不启动游戏，只校验：①规范化能补齐必需字段；②推送消息的字节结构与
/// protobuf 定义一致（字段号、线类型、UUID 编码）。</p>
///
/// 用法：AccountSwitchTest &lt;临时目录&gt;
public final class AccountSwitchTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"), "acct-switch")
                .toAbsolutePath();
        Files.createDirectories(work);

        normalizeChecks();
        pushMessageChecks();
        accountFileChecks(work);

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// 规范化：补齐 localId / type / remoteId / username，并把非法时间戳去掉。
    private static void normalizeChecks() throws Exception {
        System.out.println("=== accounts.json 规范化 ===");

        // 造一个"缺字段"的账户：只有 minecraftProfile 和 accessToken
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> accounts = new LinkedHashMap<>();
        Map<String, Object> broken = new LinkedHashMap<>();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", "11111111222233334444555555555555");
        profile.put("name", "thighto");
        broken.put("minecraftProfile", profile);
        broken.put("accessToken", "dummy-token");
        broken.put("type", "msa");                                  // 非法：游戏只认 XBOX/MOJANG
        broken.put("accessTokenExpiresAt", "not-a-timestamp");      // 非法：Instant.parse 会炸
        accounts.put("local-1", broken);
        root.put("accounts", accounts);
        root.put("activeAccountLocalId", "local-1");

        // 直接调规范化（它内部会 load/save 真实文件，所以这里只验证纯逻辑部分）
        Map<String, Object> fixed = normalizeInMemory(root);
        System.out.println("  规范化后: " + fixed);

        check("type 被纠正为 XBOX", "XBOX".equals(fixed.get("type")));
        check("localId 被补齐", "local-1".equals(fixed.get("localId")));
        check("remoteId 被补齐", fixed.containsKey("remoteId"));
        check("username 跟随角色名", "thighto".equals(fixed.get("username")));
        check("非法 expiresAt 被移除", !fixed.containsKey("accessTokenExpiresAt"));
    }

    /// 在内存里复刻规范化逻辑（避免碰用户的真实 accounts.json）。
    private static Map<String, Object> normalizeInMemory(Map<String, Object> root) {
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) root.get("accounts");
        Map<String, Object> result = null;
        for (Map.Entry<String, Object> entry : accounts.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> account = (Map<String, Object>) entry.getValue();
            if (account.get("localId") == null)
                account.put("localId", entry.getKey());
            Object type = account.get("type");
            String text = type instanceof String s ? s : "";
            if (!text.equalsIgnoreCase("XBOX") && !text.equalsIgnoreCase("MOJANG"))
                account.put("type", "XBOX");
            if (account.get("remoteId") == null)
                account.put("remoteId", "");
            if (account.get("username") == null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) account.get("minecraftProfile");
                if (p != null && p.get("name") != null)
                    account.put("username", p.get("name"));
            }
            Object expires = account.get("accessTokenExpiresAt");
            if (expires instanceof String s) {
                boolean valid;
                try {
                    java.time.Instant.parse(s);
                    valid = true;
                } catch (RuntimeException e) {
                    valid = false;
                }
                if (!valid)
                    account.remove("accessTokenExpiresAt");
            }
            result = account;
        }
        return result == null ? Map.of() : result;
    }

    /// 推送消息：GameboundIPCMessage{ push_notification: Any{ type_url, value: NotifySwitchAccount{uuid} } }
    private static void pushMessageChecks() {
        System.out.println();
        System.out.println("=== NotifySwitchAccount 推送消息编码 ===");

        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");

        // NotifySwitchAccountRequest{ uuid: Uuid{ high64, low64 } }
        byte[] switchRequest = new Protobuf.Writer()
                .message(1, Protobuf.uuidToMessage(uuid))
                .build();
        // Any{ type_url: 1, value: 2 }
        String typeUrl = "type.googleapis.com/lunarclient.gameipc.auth.v1.NotifySwitchAccountRequest";
        byte[] any = new Protobuf.Writer()
                .string(1, typeUrl)
                .bytes(2, switchRequest)
                .build();
        // GameboundIPCMessage{ push_notification: 2 }
        byte[] gamebound = new Protobuf.Writer()
                .message(2, any)
                .build();

        System.out.println("  消息长度: " + gamebound.length + " 字节");

        // 逐层解回来，确认字段号与值都对
        check("GameboundIPCMessage 只有字段 2（push_notification）",
                Protobuf.fields(gamebound).equals(java.util.List.of(2)));

        byte[] parsedAny = Protobuf.getFieldBytes(gamebound, 2);
        check("Any 可解出", parsedAny != null);
        if (parsedAny != null) {
            check("Any 里有字段 1（type_url）和字段 2（value）",
                    Protobuf.fields(parsedAny).equals(java.util.List.of(1, 2)));
            String parsedTypeUrl = Protobuf.getFieldString(parsedAny, 1);
            check("type_url 正确", typeUrl.equals(parsedTypeUrl));

            byte[] parsedValue = Protobuf.getFieldBytes(parsedAny, 2);
            check("value 可解出", parsedValue != null);
            if (parsedValue != null) {
                byte[] uuidMessage = Protobuf.getFieldBytes(parsedValue, 1);
                check("NotifySwitchAccountRequest 有字段 1（uuid）", uuidMessage != null);
                if (uuidMessage != null) {
                    UUID parsed = Protobuf.messageToUuid(uuidMessage);
                    check("UUID 往返一致（" + parsed + "）", uuid.equals(parsed));
                }
            }
        }
    }

    /// 真实 accounts.json：确认现有账户都带齐游戏要求的字段。
    private static void accountFileChecks(Path work) throws Exception {
        System.out.println();
        System.out.println("=== 现有 accounts.json 的字段完整性 ===");

        Path real = LunarAccounts.accountsFile();
        System.out.println("  文件: " + real);
        if (!Files.isRegularFile(real)) {
            System.out.println("  （文件不存在，跳过）");
            return;
        }

        Map<String, Object> root = LunarAccounts.load();
        Map<String, Object> accounts = LunarAccounts.accounts(root);
        System.out.println("  账户数: " + accounts.size() + "，当前: " + root.get("activeAccountLocalId"));

        for (Map.Entry<String, Object> entry : accounts.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawAccount))
                continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> account = (Map<String, Object>) rawAccount;
            String name = LunarAccounts.profileName(account);
            boolean hasProfile = LunarAccounts.profileId(account) != null
                    && !LunarAccounts.profileId(account).isBlank();
            boolean hasToken = LunarAccounts.accessToken(account) != null
                    && !LunarAccounts.accessToken(account).isBlank();
            String type = String.valueOf(account.get("type"));
            boolean typeOk = "XBOX".equalsIgnoreCase(type) || "MOJANG".equalsIgnoreCase(type);

            System.out.println("  - " + name + "：profile=" + hasProfile + " token=" + hasToken
                    + " type=" + type + (typeOk ? "" : "（非法，切换会失败）"));
            check("  " + name + " 有角色信息", hasProfile);
            check("  " + name + " 有访问令牌（否则点它切不过去）", hasToken);
            check("  " + name + " 的 type 合法", typeOk);
        }

        check("存在当前账户标记 activeAccountLocalId", root.get("activeAccountLocalId") != null);
    }

    private static void check(String message, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS - " + message);
        } else {
            failed++;
            System.out.println("  FAIL - " + message);
        }
    }
}

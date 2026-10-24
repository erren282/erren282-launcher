package org.dsh.lunarofl.auth;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/// Lunar「启动器侧」gameipc 服务的纯 JDK 实现。
///
/// <p>游戏进程启动时带上 `--ipcPort <端口>`，然后以 WebSocket 客户端身份连到
/// `ws://127.0.0.1:<端口>`，握手阶段在 HTTP 头 `lc-handshake` 里放一份 base64 的
/// protobuf `Handshake{launchId, processId, installationId}`（installationId 必须与
/// 启动器自己的一致，否则官方启动器直接 4401 拒收）。</p>
///
/// <p>连上之后的所有消息都是 protobuf 二进制帧：</p>
/// <ul>
///   <li>游戏 → 启动器：`LauncherboundIPCMessage{requestId, service, method, input,
///       fullMethod, inputName, inputFullName}`；</li>
///   <li>启动器 → 游戏：`GameboundIPCMessage{rpcResponse:{requestId, output}}`
///       （或 pushNotification，这里用不到）。</li>
/// </ul>
///
/// <p>本实现补的是官方启动器在 `<code>gameipc</code>` 模块里做的那一小部分，
/// 也是“把 Lunar 的微软账户验证请求转发给 HMCL、完成后再授权回去”的通道：</p>
/// <ul>
///   <li>`lunarclient.gameipc.auth.v1.AuthService.RefreshAccount`——游戏发现
///       accounts.json 里的 MC 令牌过期时发来这个请求，我们在这里跑完整刷新链
///       （MSA → XBL → XSTS → login_with_xbox）并写回 accounts.json；</li>
///   <li>`…AuthService.AddAccount`——游戏要求登录新账户，走设备码流程；</li>
///   <li>`lunarclient.gameipc.browser.v1.BrowserService.OpenUrl`——游戏请求打开网页；</li>
///   <li>chat / location 等服务回应空消息；未知请求按官方行为直接忽略。</li>
/// </ul>
public final class LunarGameIpcServer {

    /// 官方启动器的起始端口（占用则向后找 30 个）。
    public static final int DEFAULT_PORT = 28190;
    private static final int MAX_PORT_TRIES = 30;
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final LunarSessionRefresher.Log log;
    private final String expectedInstallationId;
    private final LunarSessionRefresher.DeviceCodeUi deviceCodeUi;
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private volatile ServerSocket serverSocket;
    private volatile int port = -1;
    private volatile boolean closed;
    private Thread acceptThread;

    /// 当前游戏连接的输出流（用于**主动**推送；同一时刻只有一个游戏连着）。
    ///
    /// <p>gameipc 的普通 RPC 都是"游戏问、启动器答"，但账户切换是反过来的：启动器必须主动
    /// 推一条 `NotifySwitchAccount` 告诉游戏"现在换成这个人了"，游戏才会去重读账号并刷新界面。
    /// 官方启动器就是这么做的，我们过去只回应请求、从不推送，于是游戏里点另一个账户没反应。</p>
    private volatile OutputStream pushStream;

    /// 推送用的锁：WebSocket 帧不能交错写。
    private final Object pushLock = new Object();

    public LunarGameIpcServer(LunarSessionRefresher.Log log, String expectedInstallationId,
                              LunarSessionRefresher.DeviceCodeUi deviceCodeUi) {
        this.log = log;
        this.expectedInstallationId = expectedInstallationId;
        this.deviceCodeUi = deviceCodeUi;
    }

    public int port() {
        return port;
    }

    /// 绑定端口并开始接受连接。返回实际端口。
    public int start() throws IOException {
        IOException last = null;
        for (int i = 0; i < MAX_PORT_TRIES; i++) {
            int candidate = DEFAULT_PORT + i;
            try {
                ServerSocket socket = new ServerSocket(candidate, 50,
                        InetAddress.getByName("127.0.0.1"));
                serverSocket = socket;
                port = candidate;
                break;
            } catch (IOException e) {
                last = e;
                log.line("[gameipc] 端口 " + candidate + " 被占用，尝试下一个");
            }
        }
        if (serverSocket == null)
            throw last != null ? last : new IOException("找不到可用的 gameipc 端口");
        acceptThread = new Thread(this::acceptLoop, "dsh-gameipc-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log.line("[gameipc] 服务已启动，监听 127.0.0.1:" + port);
        return port;
    }

    public void stop() {
        closed = true;
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> handle(socket), "dsh-gameipc-conn");
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            } catch (IOException e) {
                if (closed)
                    return;
                log.line("[gameipc] accept 失败：" + e);
            }
        }
    }

    // ---------- 单个连接 ----------

    private void handle(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            Handshake handshake = performUpgrade(socket);
            if (handshake == null)
                return;
            log.line("[gameipc] 游戏已连接：launchId=" + handshake.launchId
                    + " processId=" + handshake.processId);
            OutputStream out = socket.getOutputStream();
            pushStream = out;
            frameLoop(socket.getInputStream(), out);
        } catch (IOException e) {
            // 客户端断开是常态
        } finally {
            pushStream = null;
            log.line("[gameipc] 连接关闭");
        }
    }

    /// 告诉游戏「当前账户已经换成这个角色」。
    ///
    /// <p>这是游戏内切换账户的关键：Lunar 的账户选择界面点另一张卡片时，客户端不会自己去读
    /// `accounts.json`，而是等启动器推一条 `NotifySwitchAccount`（`service.proto` 里定义、
    /// 但**不属于任何 service 方法**的那种单向通知）。官方启动器在用户切换后立刻推它；
    /// 我们过去没有这条推送，游戏就一直认为"当前账户没变"，于是点了没反应、只能靠重新登录。</p>
    ///
    /// @param profileId 目标角色的 UUID（无横线或带横线都可以）
    /// @return 是否成功推送（没有游戏连着时返回 false）
    public boolean notifySwitchAccount(String profileId) {
        java.util.UUID uuid = LunarAccounts.parseProfileUuid(profileId);
        if (uuid == null) {
            log.line("[gameipc] NotifySwitchAccount：角色 UUID 无效（" + profileId + "）");
            return false;
        }
        OutputStream out = pushStream;
        if (out == null) {
            log.line("[gameipc] NotifySwitchAccount：没有游戏连接，跳过推送");
            return false;
        }

        // Any{ type_url, value }：value 是序列化后的 NotifySwitchAccountRequest{uuid}
        byte[] switchRequest = new Protobuf.Writer()
                .message(1, Protobuf.uuidToMessage(uuid))
                .build();
        byte[] any = new Protobuf.Writer()
                .string(1, TYPE_URL_NOTIFY_SWITCH_ACCOUNT)
                .bytes(2, switchRequest)
                .build();
        // GameboundIPCMessage{ push_notification: Any }
        byte[] gamebound = new Protobuf.Writer()
                .message(2, any)
                .build();

        synchronized (pushLock) {
            try {
                sendFrame(out, 0x2, gamebound);
                log.line("[gameipc] 已推送 NotifySwitchAccount → " + uuid);
                return true;
            } catch (IOException e) {
                log.line("[gameipc] 推送 NotifySwitchAccount 失败：" + e);
                return false;
            }
        }
    }

    /// `google.protobuf.Any` 的 type_url；Lunar 用 `type.googleapis.com/<全名>` 的形式。
    private static final String TYPE_URL_NOTIFY_SWITCH_ACCOUNT =
            "type.googleapis.com/lunarclient.gameipc.auth.v1.NotifySwitchAccountRequest";

    private record Handshake(String launchId, String processId, String installationId) {
    }

    /// 读 HTTP 请求、校验 lc-handshake、回 101。失败返回 null（连接已被关闭）。
    @SuppressWarnings("unchecked")
    private Handshake performUpgrade(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        String requestLine = readLine(in);
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0)
                headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                        line.substring(colon + 1).trim());
        }
        if (requestLine == null)
            return null;
        log.line("[gameipc] 升级请求: " + requestLine);
        for (Map.Entry<String, String> entry : headers.entrySet())
            log.line("[gameipc]   " + entry.getKey() + ": " + entry.getValue());

        String handshakeHeader = headers.get("lc-handshake");
        if (handshakeHeader == null || handshakeHeader.isBlank()) {
            log.line("[gameipc] 拒绝连接：没有 lc-handshake 头");
            closeSilently(socket);
            return null;
        }
        Handshake handshake = decodeHandshake(handshakeHeader);
        if (handshake == null) {
            log.line("[gameipc] 拒绝连接：lc-handshake 解析失败");
            closeSilently(socket);
            return null;
        }
        if (expectedInstallationId != null && !expectedInstallationId.isBlank()
                && !expectedInstallationId.equals(handshake.installationId)) {
            log.line("[gameipc] 拒绝连接：installationId 不匹配（" + handshake.installationId + "）");
            closeSilently(socket);
            return null;
        }

        String key = headers.get("sec-websocket-key");
        if (key == null) {
            closeSilently(socket);
            return null;
        }
        String accept = Base64.getEncoder().encodeToString(
                digest(key + WS_MAGIC));
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return handshake;
    }

    private Handshake decodeHandshake(String base64) {
        try {
            byte[] message = Base64.getDecoder().decode(base64.trim());
            Protobuf.Reader reader = new Protobuf.Reader(message);
            String launchId = null;
            String processId = null;
            String installationId = null;
            while (reader.hasNext()) {
                switch (reader.peekField()) {
                    case 1 -> launchId = reader.readString();
                    case 2 -> processId = reader.readString();
                    case 3 -> installationId = reader.readString();
                    default -> reader.skip();
                }
            }
            return new Handshake(launchId, processId, installationId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------- WebSocket 帧循环 ----------

    private void frameLoop(InputStream rawIn, OutputStream out) throws IOException {
        BufferedInputStream in = new BufferedInputStream(rawIn, 16 * 1024);
        ByteArrayOutputStream fragments = null;
        while (!closed) {
            int first = in.read();
            if (first < 0)
                return;
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            int second = in.read();
            if (second < 0)
                return;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                int high = in.read();
                int low = in.read();
                if (high < 0 || low < 0)
                    return;
                length = ((high & 0xFF) << 8) | (low & 0xFF);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    int b = in.read();
                    if (b < 0)
                        return;
                    length = (length << 8) | (b & 0xFF);
                }
            }
            byte[] mask = null;
            if (masked) {
                mask = in.readNBytes(4);
                if (mask.length < 4)
                    return;
            }
            byte[] payload = in.readNBytes((int) length);
            if (payload.length < length)
                return;
            if (masked)
                for (int i = 0; i < payload.length; i++)
                    payload[i] ^= mask[i % 4];

            switch (opcode) {
                case 0x2, 0x0 -> {
                    // binary / continuation
                    if (opcode == 0x2) {
                        fragments = new ByteArrayOutputStream();
                    }
                    if (fragments == null)
                        continue;
                    fragments.write(payload);
                    if (fin) {
                        byte[] message = fragments.toByteArray();
                        fragments = null;
                        onMessage(message, out);
                    }
                }
                case 0x8 -> { // close
                    sendFrame(out, 0x8, payload.length > 0 ? new byte[]{payload[0], 0} : new byte[0]);
                    return;
                }
                case 0x9 -> sendFrame(out, 0xA, payload); // ping → pong
                case 0xA -> { // pong，忽略
                }
                default -> { // 其他 opcode 忽略
                }
            }
        }
    }

    private void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        int header0 = 0x80 | opcode;
        int length = payload.length;
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
        frame.write(header0);
        if (length < 126) {
            frame.write(length);
        } else if (length < 65536) {
            frame.write(126);
            frame.write((length >> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--)
                frame.write((int) ((length >>> (8 * i)) & 0xFF));
        }
        frame.write(payload);
        synchronized (out) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    // ---------- 协议分发 ----------

    private void onMessage(byte[] message, OutputStream out) {
        try {
            Protobuf.Reader reader = new Protobuf.Reader(message);
            byte[] requestId = null;
            String service = null;
            String method = null;
            byte[] input = null;
            while (reader.hasNext()) {
                switch (reader.peekField()) {
                    case 1 -> requestId = reader.readBytes();
                    case 2 -> service = reader.readString();
                    case 3 -> method = reader.readString();
                    case 4 -> input = reader.readBytes();
                    default -> reader.skip();
                }
            }
            if (requestId == null || service == null || method == null) {
                log.line("[gameipc] 收到不完整的请求（service=" + service + " method=" + method + "），忽略");
                return;
            }
            log.line("[gameipc] 请求 " + shortName(service) + "." + method);
            byte[] output = dispatch(service, method, input);
            if (output == null)
                return; // 与官方一致：未知服务/方法不回应
            byte[] response = encodeResponse(requestId, output);
            sendFrame(out, 0x2, response);
        } catch (Exception e) {
            log.line("[gameipc] 处理请求失败：" + e + "；帧内容: "
                    + java.util.HexFormat.of().formatHex(message, 0, Math.min(512, message.length)));
        }
    }

    private static String shortName(String service) {
        int lastDot = service.lastIndexOf('.');
        return lastDot >= 0 ? service.substring(lastDot + 1) : service;
    }

    private byte[] encodeResponse(byte[] requestId, byte[] output) {
        Protobuf.Writer rpcResponse = new Protobuf.Writer()
                .bytes(1, requestId)
                .bytes(2, output);
        return new Protobuf.Writer()
                .message(1, rpcResponse.build())
                .build();
    }

    /// 处理一个请求，返回响应消息字节；返回 null 表示不回应（未知服务/方法）。
    private byte[] dispatch(String service, String method, byte[] input) {
        switch (service) {
            case "lunarclient.gameipc.auth.v1.AuthService" -> {
                return switch (method) {
                    case "RefreshAccount" -> handleRefreshAccount(input);
                    case "AddAccount" -> handleAddAccount();
                    default -> {
                        // 协议里 AuthService 只有这两个方法，但把未知的记下来：
                        // 「游戏内切换账户」是否走别的 RPC 需要实证，日志比猜测有用。
                        log.line("[gameipc] AuthService 未知方法 " + method
                                + "，输入 " + (input == null ? 0 : input.length) + " 字节");
                        yield null;
                    }
                };
            }
            case "lunarclient.gameipc.browser.v1.BrowserService" -> {
                if ("OpenUrl".equals(method)) {
                    handleOpenUrl(input);
                    return new byte[0];
                }
                return null;
            }
            case "lunarclient.gameipc.chat.v1.ChatService" -> {
                if ("ReceiveMessage".equals(method))
                    return new byte[0];
                return null;
            }
            case "lunarclient.gameipc.location.v1.LocationService" -> {
                if ("UpdateFocus".equals(method) || "UpdateLocation".equals(method))
                    return new byte[0];
                return null;
            }
            default -> {
                // 官方启动器实现了但我们不关心的服务（styngr 电台、paynow/tebex 结账、
                // promotion 推广等）：回一条空消息。空 protobuf 解码出来就是全默认值，
                // 游戏拿到后不会像"无响应"那样一直重试。
                if (service.startsWith("lunarclient.gameipc."))
                    return new byte[0];
                return null;
            }
        }
    }

    /// RefreshAccount：游戏要把指定账户的会话续期。
    private byte[] handleRefreshAccount(byte[] input) {
        java.util.UUID uuid = input == null || input.length == 0
                ? null
                : Protobuf.messageToUuid(input);
        try {
            Map<String, Object> root = LunarAccounts.load();
            Map<String, Object> accounts = LunarAccounts.accounts(root);
            Map<String, Object> target = null;
            if (uuid != null) {
                String wanted = compact(uuid.toString());
                for (Object value : accounts.values()) {
                    if (!(value instanceof Map<?, ?> account))
                        continue;
                    Object profile = account.get("minecraftProfile");
                    if (profile instanceof Map<?, ?>
                            && wanted.equals(compact(String.valueOf(((Map<?, ?>) profile).get("id"))))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> casted = (Map<String, Object>) value;
                        target = casted;
                        break;
                    }
                }
            }
            if (target == null && !accounts.isEmpty()) {
                // 游戏没带 uuid（或没匹配上）时回退到当前激活账户
                target = activeAccount(root, accounts);
                if (target != null)
                    log.line("[gameipc] RefreshAccount 未匹配到 " + uuid + "，回退到激活账户 "
                            + LunarAccounts.profileName(target));
            }
            if (target == null) {
                log.line("[gameipc] RefreshAccount：找不到可刷新的账户");
                return refreshResponse(false, 1); // ACCOUNTS_FILE_EMPTY
            }
            if (LunarAccounts.refreshToken(target) == null) {
                log.line("[gameipc] RefreshAccount：" + LunarAccounts.profileName(target)
                        + " 没有 MSA 刷新令牌");
                return refreshResponse(false, 3); // MISSING_REFRESH_TOKEN
            }
            // 游戏只在它认为会话需要续期时才会发 RefreshAccount（官方启动器此时也是
            // 无条件刷新），所以这里不再按 exp 跳过，避免留下"未过期但已失效"的旧令牌。
            LunarSessionRefresher.refreshAccount(target, log);
            LunarAccounts.save(root);
            return refreshResponse(true, 0);
        } catch (LunarSessionRefresher.InvalidSessionException e) {
            log.line("[gameipc] RefreshAccount 失败：" + e.getMessage());
            return refreshResponse(false, 0);
        } catch (IOException e) {
            log.line("[gameipc] RefreshAccount 失败：" + e);
            return refreshResponse(false, 0);
        }
    }

    /// accounts.json 里的 profile id 是无横线形式（11123e5efb21…），统一去掉横线再比较。
    private static String compact(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> activeAccount(Map<String, Object> root, Map<String, Object> accounts) {
        String activeLocalId = String.valueOf(root.get("activeAccountLocalId"));
        for (Map.Entry<String, Object> entry : accounts.entrySet()) {
            if (entry.getKey().equals(activeLocalId) && entry.getValue() instanceof Map)
                return (Map<String, Object>) entry.getValue();
        }
        return null;
    }

    private static byte[] refreshResponse(boolean success, int error) {
        Protobuf.Writer writer = new Protobuf.Writer();
        if (success)
            writer.bool(1, true);
        else
            writer.bool(1, false);
        if (error != 0)
            writer.varint(2, error);
        return writer.build();
    }

    /// AddAccount：游戏要登录一个（新）账户——设备码流程。
    private byte[] handleAddAccount() {
        try {
            // 把设备码交给界面组件（包装类里是 Swing 窗口：自动复制验证码到剪贴板 +
            // 置顶显示 + 可重开浏览器），同时用系统浏览器打开带 otc 的验证页。
            LunarSessionRefresher.DeviceCodeUi ui = deviceCodeUi == null
                    ? (userCode, url) -> {
                    }
                    : deviceCodeUi;
            LunarSessionRefresher.DeviceCodeUi composite = new LunarSessionRefresher.DeviceCodeUi() {
                @Override
                public void showCode(String userCode, String url) {
                    log.line("[gameipc] 设备码登录：验证码 " + userCode + "，验证页 " + url);
                    openBrowser(url);
                    ui.showCode(userCode, url);
                }

                @Override
                public void close() {
                    ui.close();
                }
            };
            LunarSessionRefresher.McTokens tokens = LunarSessionRefresher.loginWithDeviceCode(log, composite);
            String[] profile = fetchProfile(tokens.accessToken());
            LunarSessionRefresher.storeAccount(tokens, profile[0], profile[1]);
            log.line("[gameipc] AddAccount 成功：" + profile[1]);
            // 新账户写进去后必须推一条切换通知：AccountStore 已经把它设成 activeAccountLocalId，
            // 但游戏界面还停在旧账户上，不推的话用户会以为"登录了但没切过去"。
            if (profile[0] != null && !profile[0].isBlank())
                notifySwitchAccount(profile[0]);
            return new Protobuf.Writer().bool(1, true).build();
        } catch (Exception e) {
            log.line("[gameipc] AddAccount 失败：" + e.getMessage());
            return new byte[0]; // success 缺省即 false
        }
    }

    /// 用 MC 访问令牌取 profile（id/name）。
    private static String[] fetchProfile(String accessToken) {
        try {
            HttpText.Response response = HttpText.getAuthorized(
                    "https://api.minecraftservices.com/minecraft/profile", accessToken);
            if (response.ok()) {
                Map<String, Object> json = response.json();
                return new String[]{
                        String.valueOf(json.get("id")),
                        String.valueOf(json.get("name"))};
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        }
        return new String[]{"", ""};
    }

    private void handleOpenUrl(byte[] input) {
        String url = input == null ? null : Protobuf.getFieldString(input, 1);
        if (url != null && !url.isBlank()) {
            log.line("[gameipc] OpenUrl: " + url);
            openBrowser(url);
        }
    }

    private static void openBrowser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(url));
            return;
        } catch (Throwable ignored) {
        }
        try {
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
        } catch (IOException e) {
            System.err.println("[gameipc] 无法打开浏览器：" + e);
        }
    }

    // ---------- IO 小工具 ----------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(80);
        int prev = -1;
        int c;
        while ((c = in.read()) >= 0) {
            if (prev == '\r' && c == '\n')
                break;
            if (prev >= 0)
                buffer.write(prev);
            prev = c;
        }
        if (buffer.size() == 0 && prev < 0)
            return null;
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private static void closeSilently(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

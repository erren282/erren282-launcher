package org.dsh.accountskin;

import com.google.gson.annotations.SerializedName;
import javafx.application.Platform;
import org.glavo.uuid.UUIDs;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.CredentialExpiredException;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.Skin;
import org.jackhuang.hmcl.auth.yggdrasil.TextureModel;
import org.jackhuang.hmcl.ui.DialogController;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpMultipartRequest;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.skin.NormalizedSkin;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * 按账户类型应用皮肤/披风：
 *
 * <ul>
 *   <li>微软账户：Mojang 官方 API（皮肤上传 / 已拥有披风切换），全局生效；</li>
 *   <li>皮肤站账户（authlib-injector）：Yggdrasil 材质上传接口，站内生效；</li>
 *   <li>离线账户：HMCL 本地皮肤源（LocalSkinFile / LittleSkin），游戏内生效。</li>
 * </ul>
 */
public final class SkinApplier {

    private SkinApplier() {
    }

    /// Mojang 披风（Minecraft profile 响应中的 capes 项）。
    public static class CapeDto {
        public String id;
        public String alias;
        public String state;
        public String url;
    }

    /// Mojang 皮肤（Minecraft profile 响应中的 skins 项）。
    public static class SkinDto {
        public String id;
        public String state;
        public String url;
        public String variant; // CLASSIC / SLIM
    }

    /// 微软账户 Minecraft profile 响应（只取披风与皮肤列表，其余字段忽略）。
    public static class MinecraftProfileDto {
        public List<CapeDto> capes;
        public List<SkinDto> skins;
    }

    /// Mojang 披风切换请求体。
    public static class CapeActivation {
        @SerializedName("capeId")
        public String capeId;

        public CapeActivation(String capeId) {
            this.capeId = capeId;
        }
    }

    // ==== 皮肤 ====

    /// Applies a skin texture file (downloaded from LittleSkin or chosen locally) to the account.
    public static void applySkin(Account account, Path file, Runnable onSuccess, Consumer<String> onError) {
        runAsync(() -> {
            if (account instanceof MicrosoftAccount microsoftAccount) {
                validateSkinForUpload(file);
                login(account);
                microsoftAccount.uploadSkin(isSlim(file), file);
                refreshTextures(microsoftAccount);
            } else if (account instanceof AuthlibInjectorAccount injectorAccount) {
                login(account);
                injectorAccount.uploadSkin(isSlim(file), file);
                refreshTextures(injectorAccount);
            } else if (account instanceof OfflineAccount offlineAccount) {
                Skin current = offlineAccount.getSkin();
                String capePath = current != null && current.type() == Skin.Type.LOCAL_FILE
                        ? current.localCapePath() : null;
                offlineAccount.setSkin(new Skin(Skin.Type.LOCAL_FILE, null,
                        isSlim(file) ? TextureModel.SLIM : TextureModel.WIDE,
                        file.toString(), capePath));
            } else {
                throw new UnsupportedOperationException("该账户类型不支持切换皮肤");
            }
        }, onSuccess, onError, "应用皮肤");
    }

    /// Switches the offline account to the LittleSkin skin source: skin and cape both follow
    /// the profile name via the CustomSkinLoader compatible API of LittleSkin.
    public static void applyLittleSkinSource(OfflineAccount account, Runnable onSuccess, Consumer<String> onError) {
        runAsync(() -> account.setSkin(new Skin(Skin.Type.LITTLE_SKIN, null, null, null, null)),
                onSuccess, onError, "应用 LittleSkin 皮肤源");
    }

    // ==== 披风 ====

    /// Applies a cape texture file. Not supported for Microsoft accounts (Mojang does not
    /// accept cape uploads); those use {@link #fetchMojangProfile} / {@link #activateMojangCape}.
    public static void applyCape(Account account, Path file, Runnable onSuccess, Consumer<String> onError) {
        runAsync(() -> {
            if (account instanceof MicrosoftAccount) {
                throw new UnsupportedOperationException("微软账户暂不支持上传自定义披风，请使用“选择已拥有的官方披风”");
            } else if (account instanceof AuthlibInjectorAccount injectorAccount) {
                AuthInfo info = login(account);
                uploadYggdrasilCape(injectorAccount, info.getAccessToken(), file);
                refreshTextures(injectorAccount);
            } else if (account instanceof OfflineAccount offlineAccount) {
                Skin current = offlineAccount.getSkin();
                String skinPath = current != null && current.type() == Skin.Type.LOCAL_FILE
                        ? current.localSkinPath() : null;
                TextureModel model = current != null ? current.textureModel() : TextureModel.WIDE;
                offlineAccount.setSkin(new Skin(Skin.Type.LOCAL_FILE, null, model, skinPath, file.toString()));
            } else {
                throw new UnsupportedOperationException("该账户类型不支持切换披风");
            }
        }, onSuccess, onError, "应用披风");
    }

    // ==== 微软官方披风 ====

    /// Fetches the Minecraft profile of the Microsoft account: owned capes and skins.
    /// There is no dedicated capes endpoint (a GET to /minecraft/profile/capes returns 404);
    /// the owned capes are embedded in the profile response.
    public static MinecraftProfileDto fetchMojangProfile(MicrosoftAccount account) throws Exception {
        String token = login(account).getAccessToken();
        HttpURLConnection connection = NetworkUtils.createHttpConnection(
                URI.create("https://api.minecraftservices.com/minecraft/profile"));
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        String body = NetworkUtils.readFullyAsString(connection);
        if (code / 100 != 2)
            throw new IllegalStateException("获取账户资料失败：HTTP " + code + (body.isEmpty() ? "" : " " + body));
        MinecraftProfileDto profile = JsonUtils.fromJson(body, MinecraftProfileDto.class);
        if (profile == null)
            throw new IllegalStateException("账户资料解析失败");
        return profile;
    }

    /// Activates one of the owned capes of the Microsoft account.
    public static void activateMojangCape(MicrosoftAccount account, CapeDto cape) throws Exception {
        String token = login(account).getAccessToken();
        HttpURLConnection connection = NetworkUtils.createHttpConnection(
                URI.create("https://api.minecraftservices.com/minecraft/profile/capes/active"));
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.getOutputStream().write(JsonUtils.GSON.toJson(new CapeActivation(cape.id))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int code = connection.getResponseCode();
        if (code / 100 != 2)
            throw new IllegalStateException("切换披风失败：HTTP " + code);
        refreshTextures(account);
    }

    /// Removes the active cape (the account still owns it, it is just not worn).
    public static void deactivateMojangCape(MicrosoftAccount account, String capeId) throws Exception {
        String token = login(account).getAccessToken();
        HttpURLConnection connection = NetworkUtils.createHttpConnection(
                URI.create("https://api.minecraftservices.com/minecraft/profile/capes/active"));
        connection.setRequestMethod("DELETE");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.getOutputStream().write(JsonUtils.GSON.toJson(new CapeActivation(capeId))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int code = connection.getResponseCode();
        if (code / 100 != 2)
            throw new IllegalStateException("取消披风失败：HTTP " + code);
        refreshTextures(account);
    }

    // ==== 内部工具 ====

    /// Uploads a cape to an authlib-injector skin station that supports cape uploads,
    /// mirroring the skin upload endpoint with the cape suffix.
    private static void uploadYggdrasilCape(AuthlibInjectorAccount account, String accessToken, Path file) throws Exception {
        String base = account.getServer().getUrl();
        if (!base.endsWith("/"))
            base += "/";
        URI uri = URI.create(base + "api/user/profile/" + UUIDs.toCompactString(account.getProfileID()) + "/cape");
        HttpURLConnection connection = NetworkUtils.createHttpConnection(uri);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setDoOutput(true);
        try (HttpMultipartRequest request = new HttpMultipartRequest(connection)) {
            try (InputStream input = Files.newInputStream(file)) {
                request.file("file", org.jackhuang.hmcl.util.io.FileUtils.getName(file), "image/png", input);
            }
        }
        int code = connection.getResponseCode();
        if (code / 100 != 2) {
            String body = NetworkUtils.readFullyAsString(connection);
            throw new IllegalStateException("上传披风到皮肤站失败：HTTP " + code
                    + (body.isEmpty() ? "" : " " + body));
        }
    }

    /// Refreshes the session of the account and clears the texture cache so the preview
    /// and launcher avatar pick up the new texture.
    private static void refreshTextures(Account account) {
        account.clearCache();
        try {
            account.logIn();
        } catch (Throwable e) {
            LOG.warning("Failed to refresh account after texture change", e);
        }
    }

    /// Logs the account in, falling back to the interactive login dialog when the
    /// stored credentials have expired.
    private static AuthInfo login(Account account) throws Exception {
        try {
            return account.logIn();
        } catch (CredentialExpiredException e) {
            DialogController.logIn(account);
            return account.logIn();
        }
    }

    private static void validateSkinForUpload(Path file) throws Exception {
        javafx.scene.image.Image image;
        try (InputStream input = Files.newInputStream(file)) {
            image = new javafx.scene.image.Image(input);
        }
        if (image.isError())
            throw new IllegalStateException("皮肤文件不是有效的图片");
        if (image.getWidth() != 64 || (image.getHeight() != 32 && image.getHeight() != 64))
            throw new UnsupportedOperationException("HD 皮肤无法上传（仅支持 64x32 / 64x64），本地皮肤不受限制");
    }

    private static boolean isSlim(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return new NormalizedSkin(new javafx.scene.image.Image(input)).isSlim();
        } catch (Throwable e) {
            LOG.warning("Failed to detect skin model, using wide", e);
            return false;
        }
    }

    private static void runAsync(ThrowingRunnable body, Runnable onSuccess, Consumer<String> onError, String what) {
        Lang.thread(() -> {
            try {
                body.run();
                Platform.runLater(onSuccess);
            } catch (Throwable e) {
                LOG.warning("Failed to " + what, e);
                String message = e.getMessage() != null && !e.getMessage().isEmpty()
                        ? e.getMessage() : e.getClass().getSimpleName();
                Platform.runLater(() -> onError.accept(what + "失败：" + message));
            }
        }, "account-skin-apply", true);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

package org.dsh.lunarofl;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

/// 把 Lunar **官方图标**安装为版本图标（`versions/&lt;id&gt;/icon.png`）。
///
/// <p>HMCL 会读取版本目录下的 `icon.png` 作为版本列表里的图标；不写的话默认显示草方块，
/// 对 Lunar 版本有误导性。</p>
///
/// <p>图标直接取自 Lunar 客户端**自己的资源包**（`lunar-client/&lt;版本&gt;/lunar.jar` 里的
/// `assets/lunar/logo/`），也就是官方在马甲菜单、启动画面里用的那套 logo——白色拱门＋月牙＋
/// 星点，透明底。用本地资源而不是网上抓图有两个好处：不依赖网络，且拿到的一定是当前
/// 客户端版本自带的官方素材。安装时若 jar 里找不到，就退回用客户端目录里的图标文件。</p>
public final class LunarIcon {

    /// 版本图标边长。HMCL 的版本列表按 32×32 显示，保留 128 保证高分屏清晰。
    private static final int SIZE = 128;

    /// Lunar 客户端 jar 里官方 logo 的候选路径（按偏好排序：正方形优先，其次拱形原件）。
    private static final String[] LOGO_ENTRIES = {
            "assets/lunar/logo/logo-256x256.png",
            "assets/lunar/logo/logo-128x117.png",
            "assets/lunar/logo/logo-100x100.png",
            "assets/lunar/logo/logo-64x64.png",
    };

    private LunarIcon() {
    }

    /// 把官方图标写到 `target`（png）。
    ///
    /// @param clientDirectory Lunar 客户端目录（含 `lunar.jar`），可为 null
    /// @param cacheDirectory  插件数据目录，用于缓存转换结果
    /// @return 是否写入了文件（无可用来源或目标已是最新时返回 false）
    public static boolean install(Path target, Path clientDirectory, Path cacheDirectory) throws IOException {
        Path cache = cacheDirectory.resolve("lunar-icon.png");
        if (!Files.isRegularFile(cache) || Files.size(cache) == 0) {
            byte[] rendered = renderFromClient(clientDirectory);
            if (rendered == null)
                return false;
            Files.createDirectories(cache.getParent());
            Files.write(cache, rendered);
        }
        if (Files.size(cache) == 0)
            return false;
        if (Files.isRegularFile(target) && Files.size(target) == Files.size(cache))
            return false;
        Files.createDirectories(target.getParent());
        Files.copy(cache, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /// The cached icon as a JavaFX image, for the download list rows.
    ///
    /// <p>Renders and caches on first use, then reads the cache. Returns `null` when no icon is
    /// available, so callers can fall back to another image. Never throws.</p>
    public static @Nullable javafx.scene.image.Image image(Path clientDirectory, Path cacheDirectory) {
        try {
            Path target = cacheDirectory.resolve("lunar-icon.png");
            install(target, clientDirectory, cacheDirectory);
            if (Files.isRegularFile(target)) {
                try (InputStream in = Files.newInputStream(target)) {
                    javafx.scene.image.Image image =
                            new javafx.scene.image.Image(in, SIZE, SIZE, true, true);
                    return image.isError() ? null : image;
                }
            }
        } catch (Throwable ignored) {
            // an icon must never break a page
        }
        return null;
    }

    /// 从客户端 jar 里取官方 logo 并转成 `SIZE`×`SIZE` 的 PNG；找不到返回 null。
    private static byte[] renderFromClient(Path clientDirectory) {
        if (clientDirectory == null || !Files.isDirectory(clientDirectory))
            return null;
        Path jar = clientDirectory.resolve("lunar.jar");
        if (!Files.isRegularFile(jar))
            return null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String entryName : LOGO_ENTRIES) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null)
                    continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    BufferedImage source = ImageIO.read(in);
                    if (source == null)
                        continue;
                    BufferedImage squared = toSquare(source, SIZE);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(squared, "png", out);
                    return out.toByteArray();
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /// 等比缩放并居中放到透明方画布上（保持 logo 原始宽高比，不裁切、不变形），
    /// 同时把图形染成**黑色**。
    ///
    /// <p>官方 logo 是纯白的：它本来画在 Lunar 自己的深色菜单底上，而 HMCL 的版本列表
    /// 在浅色主题下是浅底，白色图标几乎看不见。这里只取它的 alpha 形状、把 RGB 全部置 0，
    /// 于是浅底下清晰可辨；深色主题下 HMCL 会自行反色显示，同样可见。</p>
    private static BufferedImage toSquare(BufferedImage source, int size) {
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double scale = Math.min((double) size / source.getWidth(), (double) size / source.getHeight());
        int width = (int) Math.round(source.getWidth() * scale);
        int height = (int) Math.round(source.getHeight() * scale);
        int x = (size - width) / 2;
        int y = (size - height) / 2;

        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, x, y, width, height, null);
        g.dispose();

        // Recolour: keep each pixel's alpha (the shape) but force its RGB to black. The official
        // logo is a flat white glyph, so its alpha already carries the whole shape.
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int argb = canvas.getRGB(px, py);
                int alpha = argb >>> 24;
                if (alpha != 0)
                    canvas.setRGB(px, py, alpha << 24);
            }
        }
        return canvas;
    }
}
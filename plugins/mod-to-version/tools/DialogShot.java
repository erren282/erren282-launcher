package org.dsh.modtoversion;

import com.jfoenix.controls.JFXDialog;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.ui.construct.JFXDialogPane;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Renders the real version picker at the launcher's minimum window size, so the layout the
 * sizing test asserts on can also be looked at.
 *
 * <p>Writes three PNGs into the directory given as the first argument: twenty versions at
 * the minimum size, one version at the minimum size, and twenty versions in a tall window
 * for comparison. This is a development aid, not a test — it asserts nothing.</p>
 */
public final class DialogShot {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        Platform.startup(() -> { });
        pump();

        shoot(outDir, "min-20-versions.png", 802, 492, 20);
        shoot(outDir, "min-1-version.png", 802, 492, 1);
        shoot(outDir, "tall-20-versions.png", 802, 900, 20);

        Platform.exit();
        System.exit(0);
    }

    private static void shoot(String outDir, String name, double width, double height,
                              int count) throws Exception {
        JFXDialogLayout[] holder = new JFXDialogLayout[1];
        Stage stage = onFxGet(() -> {
            StackPane wrapper = new StackPane();
            Stage window = new Stage();
            window.setScene(new Scene(wrapper, width, height));
            window.show();

            JFXDialog dialog = new JFXDialog();
            JFXDialogPane pane = new JFXDialogPane();
            dialog.setContent(pane);
            dialog.setDialogContainer(wrapper);
            dialog.show();

            JFXDialogLayout content = new TargetVersionDialog(
                    AddonKind.RESOURCE_PACK, release(), installed(count), target -> { }, height);
            holder[0] = content;
            pane.push(content);
            return window;
        });

        pump(20);
        Thread.sleep(500);
        pump(10);

        WritableImage image = onFxGet(() -> holder[0].snapshot(null, null));
        writePng(image, new File(outDir, name));
        System.out.printf("%s  %.0fx%.0f px -> %s%n", name, image.getWidth(),
                image.getHeight(), new File(outDir, name).getAbsolutePath());

        onFx(stage::close);
    }

    /// Writes a JavaFX image to PNG without {@code javafx.swing}.
    ///
    /// <p>{@code SwingFXUtils} lives in the {@code javafx.swing} module, which the launcher
    /// does not ship — only base, controls and graphics. The pixels are therefore copied
    /// out of the {@code PixelReader} by hand and handed to {@link ImageIO}.</p>
    private static void writePng(WritableImage image, File file) throws Exception {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", file);
    }

    private static List<VersionMatcher.Installed> installed(int count) {
        List<VersionMatcher.Installed> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new VersionMatcher.Installed("1.20." + i + "-Fabric", "1.20." + i,
                    Set.of(ModLoaderType.FABRIC)));
        }
        return list;
    }

    private static org.jackhuang.hmcl.addon.RemoteAddon.Version release() {
        return new org.jackhuang.hmcl.addon.RemoteAddon.Version(
                null, "example", "Faithful 32x", "Faithful 32x", "",
                java.time.Instant.parse("2026-05-01T00:00:00Z"),
                org.jackhuang.hmcl.addon.RemoteAddon.VersionType.Release,
                new org.jackhuang.hmcl.addon.RemoteAddon.File(
                        java.util.Map.of(), "https://example.invalid/x.zip", "x.zip"),
                List.of(), List.of("1.20.4"), List.of());
    }

    private static void pump() throws Exception {
        pump(6);
    }

    private static void pump(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            onFx(() -> { });
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action timed out");
        }
    }

    private static <T> T onFxGet(java.util.function.Supplier<T> action) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        onFx(() -> result.set(action.get()));
        return result.get();
    }
}

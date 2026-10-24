import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.dsh.servermanager.JavaCompatibility;
import org.dsh.servermanager.ServerProfile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Renders the fix dialog offscreen and writes a PNG, so the layout can be inspected by eye.
public final class DialogSnapshotTest {

    public static void main(String[] args) throws Exception {
        Path jar = Path.of("E:\\game\\gamea\\mcl\\HMCL-test\\.hmcl\\plugins\\data\\org.dsh.server-manager\\servers\\7b6e7f44-67cc-491f-91d9-163da0999a69\\server.jar");
        if (!Files.isRegularFile(jar)) {
            System.out.println("跳过：找不到测试用服务端 jar");
            return;
        }

        Path home = Files.createTempDirectory("hmcl-shot-test");
        System.setProperty("hmcl.dir", home.toString());
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable ignored) {
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                Path out = Path.of("E:\\game\\gamea\\mcl\\HMCL\\docs\\plugin\\server-manager\\dist");
                Files.createDirectories(out);

                ServerProfile broken = profile(jar,
                        "C:\\Users\\Administrator.DESKTOP-DURGV41\\AppData\\Roaming\\.minecraft\\runtime\\jre-legacy\\windows\\jre-legacy\\bin\\java.exe");
                snapshot(JavaCompatibility.check(broken), null,
                        out.resolve("dialog-blocking-trust.png").toFile());

                ServerProfile high = profile(jar, "E:\\ZULU21\\bin\\java.exe");
                snapshot(JavaCompatibility.check(high), () -> {},
                        out.resolve("dialog-warning-too-new.png").toFile());
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(120, TimeUnit.SECONDS))
            throw new IllegalStateException("超时");
        Platform.exit();
        if (failure.get() != null) {
            failure.get().printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static ServerProfile profile(Path jar, String javaPath) {
        ServerProfile p = new ServerProfile();
        p.setName("54");
        p.setType(ServerProfile.Type.PAPER);
        p.setMcVersion("1.8.8");
        p.setDirectory(jar.getParent().toString());
        p.setJarFile(jar.getFileName().toString());
        p.setJavaPath(javaPath);
        return p;
    }

    private static void snapshot(JavaCompatibility.Verdict verdict, Runnable onContinue, File target)
            throws Exception {
        Class<?> dialogClass = Class.forName("org.dsh.servermanager.JavaFixDialog");
        Constructor<?> ctor = dialogClass.getDeclaredConstructor(
                ServerProfile.class,
                Class.forName("org.dsh.servermanager.ServerManagerService"),
                Class.forName("org.jackhuang.hmcl.plugin.PluginContext"),
                JavaCompatibility.Verdict.class,
                java.util.function.Consumer.class,
                Runnable.class,
                Runnable.class);
        ctor.setAccessible(true);

        ServerProfile p = new ServerProfile();
        p.setName("54");
        p.setJavaPath(verdict.getJavaPath());
        javafx.scene.Node dialog = (javafx.scene.Node) ctor.newInstance(
                p, null, null, verdict,
                (java.util.function.Consumer<ServerProfile>) x -> {}, onContinue, (Runnable) () -> {});

        StackPane root = new StackPane(dialog);
        Scene scene = new Scene(root, 760, 520);
        Stage stage = new Stage();
        stage.setScene(scene);
        root.applyCss();
        root.layout();

        WritableImage image = scene.snapshot(null);
        // javafx.swing is not bundled, so read the pixels straight out of the image.
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                buffered.setRGB(x, y, reader.getArgb(x, y));
        ImageIO.write(buffered, "png", target);
        System.out.println("已写出 " + target + "  (" + width + "x" + height + ")");
        stage.close();
    }
}

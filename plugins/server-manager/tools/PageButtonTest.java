import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.dsh.servermanager.ServerManagerPage;
import org.dsh.servermanager.ServerManagerService;
import org.dsh.servermanager.ServerProfile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/// Presses the plugin page's own "一键开设并广播" button, offscreen, with the user's real
/// profile — the same code path the mouse triggers. It also renders the page to a PNG so the
/// visible state (row present? button present?) can be inspected.
public final class PageButtonTest {

    public static void main(String[] args) throws Exception {
        Path realData = Path.of("E:/game/gamea/mcl/HMCL-test/.hmcl/plugins/data/org.dsh.server-manager");
        Path data = Files.createTempDirectory("page-button-data");
        Files.copy(realData.resolve("servers.json"), data.resolve("servers.json"));

        ServerManagerService service = ServerManagerService.of(data);
        List<ServerProfile> profiles = service.getProfiles();
        System.out.println("profiles: " + profiles.size());
        for (ServerProfile p : profiles)
            System.out.println("  - " + p.getName() + " @ " + p.getDirectory() + " port=" + p.getPort()
                    + " jar=" + p.getJarFile());

        CountDownLatch rendered = new CountDownLatch(1);
        boolean[] started = {false};

        Platform.startup(() -> {
            try {
                ServerManagerPage page = new ServerManagerPage(service);
                StackPane root = new StackPane(page);
                root.setStyle("-fx-background-color: white;");
                Scene scene = new Scene(root, 1000, 700);
                root.applyCss();
                root.layout();

                // select the first profile exactly like clicking its row does
                Method select = ServerManagerPage.class.getDeclaredMethod("selectProfile", ServerProfile.class);
                select.setAccessible(true);
                select.invoke(page, profiles.get(0));
                root.applyCss();
                root.layout();

                snapshot(scene, root, Path.of("E:/game/gamea/mcl/tmp-page-1000x700.png"), 1000, 700);

                // now press "一键开设并广播"
                Method start = ServerManagerPage.class.getDeclaredMethod("startCurrent");
                start.setAccessible(true);
                start.invoke(page);
                System.out.println("startCurrent() 已调用（等于点击按钮）");
                started[0] = true;
            } catch (Throwable t) {
                System.out.println("FAIL - " + t);
                t.printStackTrace();
            } finally {
                rendered.countDown();
            }
        });
        rendered.await();

        if (!started[0])
            return;

        long deadline = System.currentTimeMillis() + 120_000;
        boolean running = false;
        while (System.currentTimeMillis() < deadline && !running) {
            running = service.isRunning(profiles.get(0));
            if (running) break;
            Thread.sleep(1000);
        }
        System.out.println("点击后 120 秒内 isRunning = " + running);

        Platform.runLater(() -> {
            try {
                // stop whatever we started so the machine is left clean
                Method stop = ServerManagerPage.class.getDeclaredMethod("stopCurrent");
                stop.setAccessible(true);
                stop.invoke(new Object[]{null});
            } catch (Throwable ignored) {
            }
        });
        System.exit(0);
    }

    private static void snapshot(Scene scene, StackPane root, Path out, int width, int height) throws Exception {
        WritableImage image = scene.snapshot(null);
        PixelReader reader = image.getPixelReader();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                buffered.setRGB(x, y, reader.getArgb(x, y));
        Files.createDirectories(out.getParent());
        ImageIO.write(buffered, "png", out.toFile());
        System.out.println("截图: " + out);
    }
}

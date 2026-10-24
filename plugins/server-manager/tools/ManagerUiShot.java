import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.dsh.servermanager.ServerProfile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Renders the new pages offscreen to PNG so the layout can be inspected by eye.
public final class ManagerUiShot {

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("shot-home");
        System.setProperty("hmcl.dir", home.toString());
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable ignored) {
        }

        Path serverDir = Files.createTempDirectory("shot-server");
        Files.writeString(serverDir.resolve("usercache.json"),
                "[{\"name\":\"wocao_321\",\"uuid\":\"09099f5a-36cf-3edd-aa3d-2a59ad7177dd\"},"
                        + "{\"name\":\"MC66\",\"uuid\":\"abc\"},{\"name\":\"heisedechuanshuo\",\"uuid\":\"98a5cb93\"}]",
                StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("ops.json"),
                "[{\"uuid\":\"abc\",\"name\":\"MC66\",\"level\":4,\"bypassesPlayerLimit\":false}]",
                StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("whitelist.json"),
                "[{\"uuid\":\"111\",\"name\":\"Friend1\"},{\"uuid\":\"222\",\"name\":\"wocao_321\"}]",
                StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("banned-players.json"), """
                [
                  {"uuid":"333","name":"Griefer","created":"2026-10-04 12:00:00 +0800",
                   "source":"Server","expires":"forever","reason":"破坏建筑"},
                  {"uuid":"444","name":"TempGuy","created":"2026-10-04 12:00:00 +0800",
                   "source":"Server","expires":"2026-11-04 12:00:00 +0800","reason":"测试临时封禁"}
                ]
                """, StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("server.properties"),
                "server-port=25565\nwhite-list=true\nmotd=A Minecraft Server\n", StandardCharsets.UTF_8);
        Files.createDirectories(serverDir.resolve("plugins"));
        writeJar(serverDir.resolve("plugins").resolve("EssentialsX.jar"), """
                name: EssentialsX
                version: 2.21.0
                main: com.earth2me.essentials.Essentials
                api-version: '1.13'
                author: mdcfe
                website: https://essentialsx.net
                description: The essential plugin suite for Paper and Spigot servers.
                """);
        writeJar(serverDir.resolve("plugins").resolve("ViaVersion-5.11.0.jar"), """
                name: ViaVersion
                version: 5.11.0
                main: com.viaversion.viaversion.ViaVersionPlugin
                api-version: '1.13'
                author: ViaVersion
                website: https://viaversion.com
                description: Allows newer clients to connect to older servers.
                """);
        writeJar(serverDir.resolve("plugins").resolve("ProtocolLib.jar"), """
                name: ProtocolLib
                version: 5.3.0
                main: com.comphenix.protocol.ProtocolLib
                author: dmulloy2
                description: Provides protocol support for other plugins.
                """);
        try (ZipOutputStream out = new ZipOutputStream(
                Files.newOutputStream(serverDir.resolve("paper-1.21.4.jar")))) {
            out.putNextEntry(new ZipEntry("patch.properties"));
            out.write("version=1.21.4\npatch=paperMC.patch\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("io/papermc/paperclip/Paperclip.class"));
            out.write(new byte[]{1});
            out.closeEntry();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Path serverDirFinal = serverDir;
        // The static pages are snapshotted during startup. The store search finishes on a
        // background thread and posts its results with Platform.runLater, so its screenshot is
        // taken afterwards from this helper thread — blocking the FX thread with a sleep would
        // stop those callbacks from ever running.
        Platform.startup(() -> {
            // Snapshots create and close short-lived stages; without this the toolkit would shut
            // down as soon as the last one closed, and the deferred store screenshot would never run.
            Platform.setImplicitExit(false);
            try {
                shootStep1(serverDirFinal);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        Thread waiter = new Thread(() -> {
            try {
                Thread.sleep(15000);   // let the background store search complete
                CountDownLatch shot = new CountDownLatch(1);
                Platform.runLater(() -> {
                    try {
                        shootStep2();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        shot.countDown();
                    }
                });
                shot.await(60, TimeUnit.SECONDS);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                latch.countDown();
            }
        }, "shot-wait");
        waiter.setDaemon(true);
        waiter.start();

        if (!latch.await(240, TimeUnit.SECONDS))
            throw new IllegalStateException("超时");
        Platform.exit();
        if (failure.get() != null) {
            failure.get().printStackTrace(System.out);
            System.exit(1);
        }
    }

    /// Snapshot of the static pages, and the plugin page before its store has loaded.
    private static void shootStep1(Path serverDir) throws Exception {
        Path out = Path.of("E:\\game\\gamea\\mcl\\HMCL\\docs\\plugin\\server-manager\\dist");
        Files.createDirectories(out);

        Class<?> serviceClass = Class.forName("org.dsh.servermanager.ServerManagerService");
        Object service = serviceClass.getMethod("of", Path.class).invoke(null, serverDir);

        ServerProfile profile = new ServerProfile();
        profile.setName("布吉岛");
        profile.setDirectory(serverDir.toString());
        profile.setJarFile("paper-1.21.4.jar");
        profile.setMcVersion("1.21.4");

        // 1. the console command page with the completion list open
        Class<?> pageClass = Class.forName("org.dsh.servermanager.ServerManagerPage");
        Object mainPage = pageClass.getConstructor(serviceClass).newInstance(service);
        Field current = pageClass.getDeclaredField("current");
        current.setAccessible(true);
        current.set(mainPage, profile);
        javafx.scene.control.TextField field =
                (javafx.scene.control.TextField) getField(mainPage, "commandField");
        field.setText("gamemode ");
        javafx.event.Event.fireEvent(field, new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                javafx.scene.input.KeyCode.TAB, false, false, false, false));
        snapshot((javafx.scene.Node) mainPage, out.resolve("ui-tab-completion.png").toFile(),
                900, 980);

        // 2. the player manager, mounted in a shell scroll pane like the launcher's own
        Class<?> playerPage = Class.forName("org.dsh.servermanager.PlayerManagerPage");
        Object players = playerPage.getConstructor(serviceClass, ServerProfile.class,
                        java.util.function.Supplier.class)
                .newInstance(service, profile,
                        (java.util.function.Supplier<Object>) () -> null);
        verifyScrollReset((javafx.scene.Node) players, playerPage);
        snapshot((javafx.scene.Node) players, out.resolve("ui-player-manager.png").toFile(),
                900, 760);

        // 3. the plugin manager; its store search starts here and lands later
        Class<?> pluginPageClass = Class.forName("org.dsh.servermanager.PluginManagerPage");
        pluginPage = pluginPageClass
                .getConstructor(serviceClass, ServerProfile.class)
                .newInstance(service, profile);
        snapshot((javafx.scene.Node) pluginPage, out.resolve("ui-plugin-manager.png").toFile(),
                900, 760);
    }

    /// Snapshot of the plugin store once its results have arrived.
    private static void shootStep2() throws Exception {
        Path out = Path.of("E:\\game\\gamea\\mcl\\HMCL\\docs\\plugin\\server-manager\\dist");
        javafx.scene.layout.VBox box =
                (javafx.scene.layout.VBox) getField(pluginPage, "storeResults");
        System.out.println("  商店结果行数：" + (box == null ? -1 : box.getChildren().size()));
        Object status = getField(pluginPage, "storeStatus");
        if (status instanceof javafx.scene.control.Label label)
            System.out.println("  商店状态：" + label.getText());

        // the plugin page mounted in a shell, exercising the same reset before the shot
        verifyScrollReset((javafx.scene.Node) pluginPage,
                Class.forName("org.dsh.servermanager.PluginManagerPage"));

        snapshot((javafx.scene.Node) pluginPage, out.resolve("ui-plugin-store.png").toFile(),
                900, 1500);
    }

    /// Exercises the entry scroll reset in the same shell the launcher uses, and asserts the
    /// shell really ends up at the top.
    ///
    /// <p>The reset and its verification both queue on the FX thread; the wait happens here, on
    /// a caller thread, so nothing is starved. Called before the page is snapshotted, so a
    /// failure aborts before writing a misleading image.</p>
    private static void verifyScrollReset(javafx.scene.Node pageNode, Class<?> pageClass)
            throws Exception {
        // a filler keeps the shell's content taller than the viewport even while the page is
        // still short, so the scroll position is a meaningful quantity
        javafx.scene.layout.VBox filler = new javafx.scene.layout.VBox(20);
        for (int i = 0; i < 40; i++)
            filler.getChildren().add(new javafx.scene.control.Label("占位行 " + i));

        javafx.scene.layout.VBox column = new javafx.scene.layout.VBox(pageNode, filler);
        StackPane root = new StackPane(column);
        javafx.scene.control.ScrollPane shell = new javafx.scene.control.ScrollPane(root);
        shell.setFitToWidth(true);
        Scene scene = new Scene(shell, 900, 700);
        try {
            org.jackhuang.hmcl.setting.StyleSheets.init(scene);
        } catch (Throwable ignored) {
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        // scroll to the middle, then enter the page the way the host page does: mount, onShown
        shell.setVvalue(0.5);
        root.applyCss();
        root.layout();
        double before = shell.getVvalue();

        Method onShown = pageClass.getDeclaredMethod("onShown");
        onShown.setAccessible(true);
        onShown.invoke(pageNode);

        // wait for the reset passes (~1.2s) plus layout slack, reading on the FX thread
        double after = before;
        for (int i = 0; i < 12; i++) {
            Thread.sleep(250);
            CountDownLatch read = new CountDownLatch(1);
            double[] value = new double[1];
            Platform.runLater(() -> {
                value[0] = shell.getVvalue();
                read.countDown();
            });
            read.await(5, TimeUnit.SECONDS);
            after = value[0];
            if (after == 0.0)
                break;
        }
        stage.close();

        System.out.println("  滚动重置: 进入前 vvalue=" + before + "，进入后 vvalue=" + after
                + (after == 0.0 ? "  [PASS]" : "  [FAIL]"));
        if (after != 0.0)
            throw new IllegalStateException("进入页面后外壳未回到顶部: vvalue=" + after);
    }

    private static Object pluginPage;



    private static void snapshot(javafx.scene.Node node, File target, int width, int height)
            throws Exception {
        StackPane root = new StackPane(node);
        Scene scene = new Scene(root, width, height);
        // Apply the launcher's real stylesheet, so the `-monet-*` theme variables the pages use
        // actually resolve and the screenshot reflects what the user sees.
        try {
            org.jackhuang.hmcl.setting.StyleSheets.init(scene);
            System.out.println("  样式表已应用");
        } catch (Throwable t) {
            System.out.println("  (样式表加载失败，颜色可能不准：" + t + ")");
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        // The stage must be shown: an unshown stage skips CSS/layout passes that the popup and
        // the list rows depend on, which silently produced blank screenshots before.
        stage.show();
        root.applyCss();
        root.layout();

        WritableImage image = scene.snapshot(null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                buffered.setRGB(x, y, reader.getArgb(x, y));
        ImageIO.write(buffered, "png", target);
        System.out.println("已写出 " + target.getName() + "  (" + w + "x" + h + ")");
        stage.close();
    }

    private static Object getField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static void writeJar(Path jar, String yml) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("plugin.yml"));
            out.write(yml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}

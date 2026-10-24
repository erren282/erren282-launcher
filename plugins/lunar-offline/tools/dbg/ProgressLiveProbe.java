package org.dsh.lunarofl;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// 真实下载驱动进度条：用 [LunarClientDownloader] 下一份 Lunar 客户端（1.8.9 默认模块），
/// 把 [LunarProgressPane] 摆在窗口里，定时截图，验证「步骤 + 百分比 + 速度 + 剩余时间」在真实
/// 下载里确实在动。
public final class ProgressLiveProbe {

    private static final String OUT_DIR = "E:/game/gamea/mcl/.tmp-shots";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("progress-live");
        Path clientDirectory = root.resolve("lunar-client").resolve("1.8.9");
        Path dataDirectory = root.resolve("data");
        Files.createDirectories(dataDirectory);
        System.out.println("[现场] 客户端目录 " + clientDirectory);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(() -> { });
        AtomicReference<LunarProgressPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        CountDownLatch built = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                LunarProgressPane pane = new LunarProgressPane();
                paneRef.set(pane);
                // 先给它一个初始态，模拟刚点「下载并安装」
                pane.update("正在获取版本清单", 0, -1);
                VBox root2 = new VBox(pane);
                root2.setPadding(new javafx.geometry.Insets(18));
                root2.setStyle("-fx-background-color: white;");
                Scene scene = new Scene(root2, 760, 150);
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(scene);
                stage.show();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                built.countDown();
            }
        });
        built.await(30, TimeUnit.SECONDS);

        new Thread(() -> {
            try {
                LunarApi api = new LunarApi(dataDirectory);
                LunarClientDownloader.Installed installed = LunarClientDownloader.download(
                        clientDirectory, api, "1.8.9", "lunar",
                        (step, done, total) -> Platform.runLater(() -> {
                            LunarProgressPane pane = paneRef.get();
                            if (pane != null)
                                pane.update(step, done, total);
                        }),
                        () -> false);
                System.out.println("[现场] 下载完成：" + installed.files() + " 个文件");
                Platform.runLater(() -> {
                    LunarProgressPane pane = paneRef.get();
                    if (pane != null)
                        pane.complete("已下载完成（" + installed.files() + " 个文件）");
                });
                Thread.sleep(1500);
                shoot(stageRef.get(), OUT_DIR + "/progress-live-done.png", paneRef.get());
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                latch.countDown();
            }
        }, "live-download").start();

        // 中途截两张
        for (Object[] schedule : new Object[][]{{20_000L, "progress-live-1.png"}, {60_000L, "progress-live-2.png"}}) {
            Thread.sleep((Long) schedule[0]);
            shoot(stageRef.get(), OUT_DIR + "/" + schedule[1], paneRef.get());
        }

        latch.await(600, TimeUnit.SECONDS);
        Platform.runLater(() -> {
            Stage stage = stageRef.get();
            if (stage != null)
                stage.close();
        });
        Thread.sleep(500);
        Platform.exit();
    }

    private static void shoot(Stage stage, String path, LunarProgressPane pane) throws Exception {
        if (stage == null)
            return;
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Scene scene = stage.getScene();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                WritableImage image = scene.snapshot(null);
                java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                        (int) image.getWidth(), (int) image.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < image.getHeight(); y++)
                    for (int x = 0; x < image.getWidth(); x++)
                        buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                javax.imageio.ImageIO.write(buffered, "png", new java.io.File(path));
                System.out.println("[现场] 截图 " + path + " → " + pane.detailText());
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await(30, TimeUnit.SECONDS);
    }
}

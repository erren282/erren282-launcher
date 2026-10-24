package org.dsh.lunarofl;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// `LunarProgressPane` 的预览与自检：
/// 1) 打印几组 [LunarProgressPane#detail] 的输出，检查文本格式；
/// 2) 造一个真实的进度条（模拟下载到 42%），截图到 PNG 供人工确认外观。
public final class ProgressPanePreview {

    private static final String OUT = "E:/game/gamea/mcl/.tmp-shots/progress-pane.png";

    public static void main(String[] args) throws Exception {
        // ---- 1) 文本格式自检（不依赖 JavaFX）----
        long total = 179_700_000L;
        System.out.println("[自检] 刚开始     : " + LunarProgressPane.detail(0.0, 0, total, 0, 0));
        System.out.println("[自检] 下载中     : " + LunarProgressPane.detail(0.42, 76_100_000L, total, 3.2e6, 84_000));
        System.out.println("[自检] 快完成     : " + LunarProgressPane.detail(0.97, 174_300_000L, total, 4.1e6, 60_000));
        System.out.println("[自检] 总量未知   : " + LunarProgressPane.detail(-1, 5_200_000L, -1, 1.5e6, 4_000));
        System.out.println("[自检] 纯小文件   : " + LunarProgressPane.detail(0.5, 512_000L, 1_048_576L, 0, 2_000));
        System.out.println("[自检] 分钟级剩余 : " + LunarProgressPane.detail(0.10, 1_000_000L, 100_000_000L, 100_000, 30_000));
        for (String[] sample : new String[][]{{"0 秒", "0"}, {"59 秒", "59000"}, {"61 秒", "61000"}, {"1 小时", "3600000"}}) {
            System.out.println("[自检] duration(" + sample[0] + ") = " + LunarProgressPane.duration(Long.parseLong(sample[1])));
        }

        // ---- 2) 外观截图 ----
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(() -> { });
        new Thread(() -> {
            try {
                LunarProgressPane pane = new LunarProgressPane();
                // 模拟真实回调：每次前进约 1 MB / 320 ms（≈3 MB/s），让速度与剩余时间像真的
                pane.update("正在下载客户端文件", 73_000_000L, total);
                Thread.sleep(320);
                pane.update("正在下载客户端文件", 74_000_000L, total);
                Thread.sleep(320);
                pane.update("正在下载客户端文件", 75_000_000L, total);
                Thread.sleep(320);

                LunarProgressPane installing = new LunarProgressPane();
                installing.update("正在安装 classpath 副本", 132_000_000L, -1);

                LunarProgressPane done = new LunarProgressPane();
                done.update("正在下载客户端文件", total, total);
                done.complete("已安装版本「Lunar-1.12.2」");

                CountDownLatch shot = new CountDownLatch(1);
                Platform.runLater(() -> {
                    try {
                        VBox root = new VBox(14, pane, installing, done);
                        root.setPadding(new javafx.geometry.Insets(18));
                        root.setStyle("-fx-background-color: white;");
                        Scene scene = new Scene(root, 720, 300);
                        try {
                            org.jackhuang.hmcl.setting.StyleSheets.init(scene);
                        } catch (Throwable ignored) {
                            // 预览环境没有完整样式也没关系
                        }
                        Stage stage = new Stage();
                        stage.setScene(scene);
                        stage.show();
                        root.applyCss();
                        root.layout();
                        WritableImage image = scene.snapshot(null);
                        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                (int) image.getWidth(), (int) image.getHeight(),
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < image.getHeight(); y++)
                            for (int x = 0; x < image.getWidth(); x++)
                                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                        javax.imageio.ImageIO.write(buffered, "png", new java.io.File(OUT));
                        System.out.println("[预览] 已截图 " + OUT);
                        System.out.println("[预览] 下载中明细: " + pane.detailText());
                        System.out.println("[预览] 安装中明细: " + installing.detailText());
                        System.out.println("[预览] 完成明细  : " + done.detailText());
                        stage.close();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    } finally {
                        shot.countDown();
                    }
                });
                shot.await(60, TimeUnit.SECONDS);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                latch.countDown();
            }
        }, "progress-preview").start();
        latch.await(180, TimeUnit.SECONDS);
        Platform.exit();
    }
}

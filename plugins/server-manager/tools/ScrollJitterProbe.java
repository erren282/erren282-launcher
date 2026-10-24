import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import org.dsh.servermanager.ServerManagerPage;
import org.dsh.servermanager.ServerManagerService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/// Reproduces the scroll environment of the plugin page (ScrollPane + fitToWidth + smooth
/// scrolling) and watches, second by second, whether the content height or the scroll value
/// moves on its own — the mechanism behind "scrolling jitters".
public final class ScrollJitterProbe {

    private static final List<String> events = new ArrayList<>();
    private static double lastHeight = -1;
    private static double lastV = -1;

    public static void main(String[] args) {
        CountDownLatch shown = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                start(stage0());
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            } finally {
                shown.countDown();
            }
        });
        try { shown.await(); } catch (InterruptedException ignored) {}
    }

    private static Stage stage0() { return new Stage(); }

    private static void start(Stage stage) throws Exception {
        Path realData = Path.of("E:/game/gamea/mcl/HMCL-test/.hmcl/plugins/data/org.dsh.server-manager");
        Path data = Files.createTempDirectory("jitter-probe");
        Files.copy(realData.resolve("servers.json"), data.resolve("servers.json"));
        ServerManagerService service = ServerManagerService.of(data);

        ServerManagerPage page = new ServerManagerPage(service);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        org.jackhuang.hmcl.ui.FXUtils.smoothScrolling(scroll);

        stage.setScene(new Scene(scroll, 900, 600));
        stage.show();

        // sample for 6 seconds: any self-driven height/scroll change is a jitter source
        Thread sampler = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                Platform.runLater(() -> {
                    double h = page.getHeight();
                    double v = scroll.getVvalue();
                    if (lastHeight >= 0 && Math.abs(h - lastHeight) > 1)
                        events.add(String.format("内容高度变化 %.0f -> %.0f", lastHeight, h));
                    if (lastV >= 0 && Math.abs(v - lastV) > 0.001)
                        events.add(String.format("滚动值自己变化 %.3f -> %.3f", lastV, v));
                    lastHeight = h;
                    lastV = v;
                });
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        // after 6.5s: scroll to the bottom, then watch again for 3 seconds
        Thread program = new Thread(() -> {
            sleep(6500);
            Platform.runLater(() -> {
                scroll.setVvalue(1.0);
                events.add(">>> 程序滚动到底部");
            });
            sleep(1500);
            Platform.runLater(() -> {
                events.add(">>> 调用 ManageUi.scrollToTop（子页打开时的行为）");
                org.dsh.servermanager.ManageUi.scrollToTop(page);
            });
            sleep(2500);
            Platform.runLater(() -> {
                System.out.println("========== 采样结果 ==========");
                if (events.isEmpty())
                    System.out.println("6 秒静置 + 程序滚动期间：无任何自发高度/滚动变化");
                for (String event : events)
                    System.out.println(event);
                Platform.exit();
            });
        });
        program.setDaemon(true);
        program.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

}

package org.dsh.serverlist;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import org.jackhuang.hmcl.setting.SettingsManager;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/// 验证 [ServerListPage#back] 的返回语义（与 DecoratorController 一致：false = 本页已处理，true = 交还启动器弹栈）：
///
/// <ul>
///   <li>停在子视图（详情 / 表单 / 目录）时 → 退回列表，返回 false，且不调用 backToModule；</li>
///   <li>停在列表顶层且由汇聚页托管 → 调用 backToModule，返回 false；</li>
///   <li>作为独立侧边栏入口（backToModule 为 null）→ 返回 true，让启动器退出本功能。</li>
/// </ul>
public final class BackSemanticsTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        SettingsManager.init();
        Platform.startup(() -> {
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            run();
        } catch (Throwable t) {
            failure.set(t);
        }

        if (failure.get() != null) {
            System.out.println("FATAL");
            failure.get().printStackTrace();
            Platform.exit();
            System.exit(2);
            return;
        }

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        Platform.exit();
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void run() throws Exception {
        Path work = Files.createTempDirectory("server-list-back-");
        try {
            hosted(work);
            remoteDirectory(work);
            standalone(work);
        } finally {
            deleteTree(work);
        }
    }

    private static void hosted(Path work) throws Exception {
        System.out.println("=== 汇聚页托管 ===");

        AtomicInteger backToModule = new AtomicInteger();
        ServerListPage[] holder = new ServerListPage[1];
        onFx(() -> {
            holder[0] = new ServerListPage(work, backToModule::incrementAndGet);
            return null;
        });
        ServerListPage page = holder[0];

        Method showAddForm = ServerListPage.class.getDeclaredMethod("showAddForm");
        showAddForm.setAccessible(true);

        // 进入一层子视图
        onFx(() -> {
            showAddForm.invoke(page);
            return null;
        });

        boolean handled = onFx(page::back);
        check("子视图返回由本页消化（false）", !handled);
        check("子视图返回不退出到汇聚页", backToModule.get() == 0);

        // 已回到列表顶层
        handled = onFx(page::back);
        check("列表顶层返回仍由本页消化（false）", !handled);
        check("列表顶层返回回到汇聚页一次", backToModule.get() == 1);
    }

    /// 页内已经没有「返回目录」按钮，所以从在线目录的详情退回上一层必须由窗口左上角的
    /// 原生箭头承担。这里验证它退到的是「目录」，而不是一步跳到已保存的服务器列表。
    private static void remoteDirectory(Path work) throws Exception {
        System.out.println();
        System.out.println("=== 在线目录：详情退回目录 ===");

        AtomicInteger backToModule = new AtomicInteger();
        ServerListPage[] holder = new ServerListPage[1];
        onFx(() -> {
            holder[0] = new ServerListPage(work, backToModule::incrementAndGet);
            return null;
        });
        ServerListPage page = holder[0];

        Method showRemoteDetail = ServerListPage.class.getDeclaredMethod(
                "showRemoteDetail", McStatClient.RemoteServer.class);
        showRemoteDetail.setAccessible(true);

        McStatClient.RemoteServer server = new McStatClient.RemoteServer(
                "测试详情服", "127.0.0.1:25565", "1.20.1", "测试用 MOTD", "", "", 0, 0,
                "test-server", List.of("生存"), false);

        onFx(() -> {
            showRemoteDetail.invoke(page, server);
            return null;
        });
        check("目录里的服务器详情已显示", onFx(() -> containsText(page, "测试详情服")));

        boolean handled = onFx(page::back);
        check("详情返回由本页消化（false）", !handled);
        check("详情返回退回在线目录", onFx(() -> containsText(page, "发现更多服务器")));
        check("退回目录时没有退出模块", backToModule.get() == 0);

        handled = onFx(page::back);
        check("目录返回由本页消化（false）", !handled);
        check("目录返回退回已保存的服务器列表", onFx(() -> containsText(page, "服务器列表")));
        check("退回列表时没有退出模块", backToModule.get() == 0);

        handled = onFx(page::back);
        check("列表顶层返回仍由本页消化（false）", !handled);
        check("列表顶层返回回到汇聚页一次", backToModule.get() == 1);
    }

    private static void standalone(Path work) throws Exception {
        System.out.println();
        System.out.println("=== 独立侧边栏入口 ===");

        ServerListPage[] holder = new ServerListPage[1];
        onFx(() -> {
            holder[0] = new ServerListPage(work, null);
            return null;
        });

        boolean handled = onFx(holder[0]::back);
        check("独立入口在列表顶层时交还启动器（true）", handled);
    }

    // ---- helpers ----

    /// 页面上是否出现了这段文字（Label），用来判断当前显示的是哪一层视图。
    private static boolean containsText(Node root, String text) {
        if (root instanceof Label label && text.equals(label.getText()))
            return true;
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable())
                if (containsText(child, text))
                    return true;
        }
        return false;
    }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(call.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(60, TimeUnit.SECONDS))
            throw new AssertionError("FX thread timeout");
        if (error.get() != null)
            throw new AssertionError("FX task failed", error.get());
        return result.get();
    }

    private interface FxCall<T> {
        T call() throws Exception;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path))
            return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}

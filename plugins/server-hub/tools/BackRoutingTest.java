package org.dsh.serverhub;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.PluginSubPage;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/// 验证「服务器」汇聚页的返回路由：
///
/// <ul>
///   <li>停在落地页时，返回必须交还给启动器（{@code false}，弹出导航栈回主界面）；</li>
///   <li>进入子页面后，返回由模块消化（{@code true}）；</li>
///   <li>子页面自己的子视图（详情 / 表单 / 目录）优先被它处理，处理后仍停在子页面；</li>
///   <li>子页面在列表顶层时，返回回到落地页，而不是直接退出模块。</li>
/// </ul>
public final class BackRoutingTest {

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
        PluginSubPage fake = new PluginSubPage("test.back-routing", "测试页面", "仅用于返回路由测试",
                SVG.GLOBE_BOOK, backToHub -> new FakePage(backToHub));

        onFx(() -> {
            PluginMenuService.registerSubPage(fake);
            return null;
        });

        try {
            ServerHubPage hub = onFx(ServerHubPage::new);
            Method handleBack = ServerHubPage.class.getDeclaredMethod("handleBack");
            handleBack.setAccessible(true);
            java.lang.reflect.Field currentField = ServerHubPage.class.getDeclaredField("currentSubPage");
            currentField.setAccessible(true);
            java.util.function.Supplier<Boolean> onSubPage =
                    () -> {
                        try {
                            return currentField.get(hub) != null;
                        } catch (IllegalAccessException e) {
                            throw new AssertionError(e);
                        }
                    };

            // 1) 落地页：交给启动器
            boolean handled = onFx(() -> (Boolean) handleBack.invoke(hub));
            check("落地页不拦截返回（交给启动器弹栈）", !handled);
            check("落地页没有子页面", !onFx(onSubPage::get));

            // 2) 点开卡片进入子页面
            onFx(() -> {
                Node card = findCard(hub, "测试页面");
                if (card == null)
                    throw new AssertionError("汇聚页里找不到「测试页面」卡片");
                ((javafx.scene.control.ButtonBase) card).fire();
                return null;
            });
            check("点开卡片后进入子页面", onFx(onSubPage::get));

            // 3) 子页面还有一层子视图：返回由子页面消化，仍停在子页面
            handled = onFx(() -> (Boolean) handleBack.invoke(hub));
            check("子页面的子视图优先处理返回", handled);
            check("处理子视图时仍停在子页面", onFx(onSubPage::get));

            // 4) 子页面在列表顶层：返回回到落地页
            handled = onFx(() -> (Boolean) handleBack.invoke(hub));
            check("子页面顶层返回被模块消化", handled);
            check("已回到落地页（currentSubPage 清空）", !onFx(onSubPage::get));

            // 5) 已回到落地页，再按返回交还启动器
            handled = onFx(() -> (Boolean) handleBack.invoke(hub));
            check("回到落地页后再次返回交给启动器", !handled);

            // 6) 子页面第二次进入时状态应完全重置（currentSubPage 已清空）
            onFx(() -> {
                Node card = findCard(hub, "测试页面");
                ((javafx.scene.control.ButtonBase) card).fire();
                return null;
            });
            handled = onFx(() -> (Boolean) handleBack.invoke(hub));
            check("重新进入子页面后返回仍然被模块消化", handled);
        } finally {
            onFx(() -> {
                PluginMenuService.unregisterSubPage(fake);
                return null;
            });
        }

        interceptsTitleBarArrow();
    }

    /// 验证窗口标题栏的返回箭头真的被接管：落地页时交还原动作，子页面时由模块消化。
    private static void interceptsTitleBarArrow() throws Exception {
        System.out.println();
        System.out.println("=== 标题栏返回箭头 ===");

        PluginSubPage fake = new PluginSubPage("test.back-arrow", "箭头页", "验证标题栏箭头",
                SVG.GLOBE_BOOK, backToHub -> new FakePage(backToHub));
        onFx(() -> {
            PluginMenuService.registerSubPage(fake);
            return null;
        });

        AtomicInteger originalCalls = new AtomicInteger();
        Object[] holder = new Object[2];
        try {
            onFx(() -> {
                javafx.stage.Stage stage = new javafx.stage.Stage();
                org.jackhuang.hmcl.ui.decorator.Decorator decorator =
                        new org.jackhuang.hmcl.ui.decorator.Decorator(stage);
                decorator.onBackNavButtonActionProperty().set(event -> originalCalls.incrementAndGet());

                ServerHubPage hub = new ServerHubPage();
                decorator.getContent().setAll(hub);
                stage.setScene(new javafx.scene.Scene(decorator, 900, 700));
                stage.show();
                holder[0] = decorator;
                holder[1] = hub;
                return null;
            });
            pump();

            org.jackhuang.hmcl.ui.decorator.Decorator decorator =
                    (org.jackhuang.hmcl.ui.decorator.Decorator) holder[0];
            ServerHubPage hub = (ServerHubPage) holder[1];

            java.lang.reflect.Field currentField = ServerHubPage.class.getDeclaredField("currentSubPage");
            currentField.setAccessible(true);

            // 落地页：箭头应交给启动器原动作
            onFx(() -> {
                decorator.onBackNavButtonActionProperty().get().handle(new javafx.event.ActionEvent());
                return null;
            });
            check("落地页时箭头交还原动作", originalCalls.get() == 1);

            // 进入子页面：第一次按箭头先退回页面内部列表（仍停在子页面，不触达原动作）
            onFx(() -> {
                Node card = findCard(hub, "箭头页");
                if (card == null)
                    throw new AssertionError("找不到「箭头页」卡片");
                ((javafx.scene.control.ButtonBase) card).fire();
                return null;
            });
            onFx(() -> {
                decorator.onBackNavButtonActionProperty().get().handle(new javafx.event.ActionEvent());
                return null;
            });
            check("第一次按箭头先处理子页面内部视图", originalCalls.get() == 1);
            check("处理内部视图时仍停在子页面", onFx(() -> {
                try {
                    return currentField.get(hub) != null;
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }));

            // 第二次按箭头：回到落地页，仍不触达原动作
            onFx(() -> {
                decorator.onBackNavButtonActionProperty().get().handle(new javafx.event.ActionEvent());
                return null;
            });
            check("第二次按箭头回到落地页（原动作未触发）", originalCalls.get() == 1);
            check("箭头把用户带回落地页", onFx(() -> {
                try {
                    return currentField.get(hub) == null;
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }));

            // 回到落地页后，箭头重新交还原动作
            onFx(() -> {
                decorator.onBackNavButtonActionProperty().get().handle(new javafx.event.ActionEvent());
                return null;
            });
            check("回到落地页后箭头再次交还原动作", originalCalls.get() == 2);

            // ---- ESC：与返回箭头同一套分流 ----
            AtomicInteger escReachedAncestor = new AtomicInteger();
            onFx(() -> {
                decorator.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE)
                        escReachedAncestor.incrementAndGet();
                });
                return null;
            });

            fireEsc(hub);
            check("落地页按 ESC 交给启动器", escReachedAncestor.get() == 1);

            onFx(() -> {
                Node card = findCard(hub, "箭头页");
                ((javafx.scene.control.ButtonBase) card).fire();
                return null;
            });
            fireEsc(hub);
            check("子页面按 ESC 被模块消化（未到达启动器）", escReachedAncestor.get() == 1);
        } finally {
            onFx(() -> {
                PluginMenuService.unregisterSubPage(fake);
                return null;
            });
        }
    }

    private static void fireEsc(Node target) throws Exception {
        onFx(() -> {
            javafx.event.Event.fireEvent(target, new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
    }

    private static void pump() throws Exception {
        for (int i = 0; i < 6; i++) {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(latch::countDown);
            latch.await(5, TimeUnit.SECONDS);
        }
    }

    /// 模拟一个插件子页面：默认停在一层子视图上。
    private static final class FakePage extends javafx.scene.layout.VBox implements DecoratorPage {
        private final Runnable backToHub;
        private final AtomicInteger subViews = new AtomicInteger(1);
        private final ReadOnlyObjectWrapper<State> state =
                new ReadOnlyObjectWrapper<>(State.fromTitle("测试页面"));

        FakePage(Runnable backToHub) {
            this.backToHub = backToHub;
        }

        @Override
        public ReadOnlyObjectProperty<State> stateProperty() {
            return state.getReadOnlyProperty();
        }

        @Override
        public boolean back() {
            if (subViews.get() > 0) {
                subViews.decrementAndGet();
                return false;   // 子视图自己处理了
            }
            backToHub.run();    // 回到汇聚页
            return false;
        }
    }

    /// 广度优先找到文案匹配的按钮。
    private static Node findCard(Node root, String title) {
        List<Node> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node node = queue.remove(0);
            if (node instanceof javafx.scene.control.ButtonBase button && containsText(button, title))
                return button;
            if (node instanceof javafx.scene.Parent parent)
                queue.addAll(parent.getChildrenUnmodifiable());
        }
        return null;
    }

    private static boolean containsText(Node root, String text) {
        if (root instanceof javafx.scene.control.Label label && text.equals(label.getText()))
            return true;
        if (root instanceof javafx.scene.control.Labeled labeled
                && labeled.getGraphic() != null && containsText(labeled.getGraphic(), text))
            return true;
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable())
                if (containsText(child, text))
                    return true;
        }
        return false;
    }

    // ---- helpers ----

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
        if (!latch.await(30, TimeUnit.SECONDS))
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
}

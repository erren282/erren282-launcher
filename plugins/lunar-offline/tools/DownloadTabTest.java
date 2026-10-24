import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import org.dsh.lunarofl.LunarDownloadTab;
import org.jackhuang.hmcl.plugin.PluginMenuService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Verifies the Lunar download tab registers and renders, and that the page it builds has the
/// same shape as the launcher's own version download page:
///
/// 1) start the JavaFX toolkit;
/// 2) register the tab and confirm it appears in [PluginMenuService.getDownloadTabs];
/// 3) build the page on the FX thread and walk its node tree;
/// 4) wait (off the FX thread) for the release list to arrive.
public final class DownloadTabTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dataDirectory = Files.createTempDirectory("lunar-tab-test");
        // The page builds RipplerContainer, which reads the launcher's animation settings, so the
        // settings must be loaded first - exactly as they are when the launcher starts.
        Path home = Files.createTempDirectory("lunar-tab-home");
        System.setProperty("hmcl.dir", home.toString());
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable ignored) {
        }
        CountDownLatch toolkit = new CountDownLatch(1);
        Platform.startup(toolkit::countDown);
        check(toolkit.await(30, TimeUnit.SECONDS), "JavaFX 工具包已启动");

        LunarDownloadTab tab = new LunarDownloadTab(dataDirectory);
        check("Lunar 客户端".equals(tab.getTitle()), "标签标题：" + tab.getTitle());
        check(tab.getIcon() != null, "标签图标不为空");

        PluginMenuService.registerDownloadTab(tab);
        check(onFx(() -> PluginMenuService.getDownloadTabs().contains(tab)), "标签已注册到下载页侧边栏");

        AtomicReference<Node> page = new AtomicReference<>();
        check(onFx(() -> {
            page.set(tab.createPage());
            return page.get() != null;
        }), "createPage() 成功返回页面节点");
        if (page.get() == null) {
            System.out.println("FAIL - 页面为空，终止");
            System.exit(1);
        }

        // ComponentList/JFXListView build their content in the skin, which only materialises once
        // the node is attached to a Scene, so a (never shown) Scene is created here.
        onFx(() -> {
            Parent root = (Parent) page.get();
            javafx.scene.Scene scene = new javafx.scene.Scene(root, 1000, 800);
            root.applyCss();
            root.layout();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return true;
        });

        // The list itself only joins the scene once the page leaves its LOADING state, because
        // TransitionPane keeps just the current content. So wait for the release list first, then
        // walk the tree.
        boolean filled = false;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && !filled) {
            Thread.sleep(500);
            filled = onFx(() -> {
                Parent root = (Parent) page.get();
                root.applyCss();
                root.layout();
                return findVersionList(page.get()) != null;
            });
        }
        check(filled, "版本列表已从 Lunar 官方接口载入" + (filled ? "" : "（超时）"));

        // now that the list is in the tree, collect the nodes
        List<Node> nodes = new ArrayList<>();
        onFx(() -> {
            Parent root = (Parent) page.get();
            root.applyCss();
            root.layout();
            collect(page.get(), nodes);
            return true;
        });
        check(!nodes.isEmpty(), "页面节点树：" + nodes.size() + " 个节点");

        List<String> labels = new ArrayList<>();
        List<com.jfoenix.controls.JFXListView<?>> lists = new ArrayList<>();
        List<com.jfoenix.controls.JFXTextField> fields = new ArrayList<>();
        for (Node node : nodes) {
            // FXUtils.newRaisedButton returns JFXButton, a ButtonBase but not a Button, so match
            // the base class here (same for JFXComboBox, which extends ComboBoxBase directly).
            if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null)
                labels.add(labeled.getText());
            if (node instanceof com.jfoenix.controls.JFXListView<?> list)
                lists.add(list);
            if (node instanceof com.jfoenix.controls.JFXTextField field)
                fields.add(field);
        }

        // step 1 mirrors the launcher's version page: a search field, a category combo and a
        // refresh button next to each other in the search card
        check(fields.stream().anyMatch(f -> f.getPromptText() != null
                        && f.getPromptText().contains("搜索")),
                "有搜索输入框：" + fields.stream().map(f -> String.valueOf(f.getPromptText())).toList());
        check(!lists.isEmpty(), "有版本列表视图：" + lists.size());
        check(labels.stream().anyMatch(l -> l.contains("名称")), "搜索栏有「名称」标签");
        check(labels.stream().anyMatch(l -> l.contains("版本类型")), "搜索栏有「版本类型」标签");
        check(labels.stream().anyMatch(l -> l.contains("刷新")), "搜索栏有「刷新」按钮");

        // step 2's controls only exist once a release is chosen, so they must NOT be present yet
        check(labels.stream().noneMatch("下载并安装"::equals),
                "未选版本时看不到第二阶段的「下载并安装」");

        int loaded = onFx(() -> lists.stream().mapToInt(l -> l.getItems().size()).max().orElse(0));
        check(loaded > 5, "版本列表条目数：" + loaded);
        if (loaded > 0) {
            String first = onFx(() -> lists.stream()
                    .filter(l -> !l.getItems().isEmpty())
                    .findFirst()
                    .map(l -> String.valueOf(l.getItems().get(0)))
                    .orElse("?"));
            System.out.println("  第一个条目: " + first);
        }

        PluginMenuService.unregisterDownloadTab(tab);
        check(onFx(() -> !PluginMenuService.getDownloadTabs().contains(tab)), "标签可被注销");

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void collect(Node node, List<Node> out) {
        out.add(node);
        // 没有 Scene 时 ScrollPane 不会创建 skin，内容不在 children 里，要单独取
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null)
            collect(scroll.getContent(), out);
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable())
                collect(child, out);
    }

    /// Finds the version list anywhere under `node`, following ScrollPane content too.
    private static com.jfoenix.controls.JFXListView<?> findVersionList(Node node) {
        if (node instanceof com.jfoenix.controls.JFXListView<?> list)
            return list;
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            var found = findVersionList(scroll.getContent());
            if (found != null)
                return found;
        }
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable()) {
                var found = findVersionList(child);
                if (found != null)
                    return found;
            }
        return null;
    }

    /// 在 FX 线程上取值并等待结果。
    private static <T> T onFx(java.util.concurrent.Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                t.printStackTrace(System.out);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok) failures++;
    }
}

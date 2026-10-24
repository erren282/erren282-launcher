import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.dsh.serverhub.ServerHubPage;
import org.dsh.serverlist.ServerListPage;
import org.dsh.servermanager.PlayerManagerPage;
import org.dsh.servermanager.PluginManagerPage;
import org.dsh.servermanager.ServerDownloadPage;
import org.dsh.servermanager.ServerManagerPage;
import org.dsh.servermanager.ServerManagerService;
import org.dsh.servermanager.ServerProfile;
import org.jackhuang.hmcl.ui.FXUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/// Empirical scroll audit for the three server plugins.
///
/// <p>It does not read the source. It builds every page the way the launcher hosts it
/// (PluginPageProxy = ScrollPane + setFitToWidth(true) + FXUtils.smoothScrolling), walks the real
/// scene graph, and then <em>fires a real wheel event</em> at every scroll container and watches
/// when the scroll position actually moves:</p>
///
/// <ul>
///   <li>smooth installed — HMCL's handler consumes the wheel event and a Timeline advances the
///       position over the following frames, so the position is still unchanged when the event
///       returns and has moved 300 ms later;</li>
///   <li>not installed — JavaFX's own skin moves the position synchronously inside the event, so it
///       has already jumped when the event returns.</li>
/// </ul>
///
/// <p>Two control pages prove the detector separates the two cases. The probe then dumps, per page,
/// the exhaustive list of scroll containers with their nesting depth, flags the ones that scroll but
/// are not smoothed, and marks the ones that are legitimately nested.</p>
public final class SmoothScrollProbe {

    private static int checks;
    private static int failures;
    private static ServerProfile probeProfile;

    /// One scroll-capable node found in a page.
    private record Container(Node node, String kind, String owner, int depth,
                             boolean smooth, double onEvent, double after) {
        Container measured(double onEvent, double after, boolean isSmooth) {
            return new Container(node, kind, owner, depth, isSmooth, onEvent, after);
        }
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("scroll-probe-home");
        System.setProperty("hmcl.dir", home.toString());
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable ignored) {
        }

        Path data = Files.createTempDirectory("scroll-probe-data");
        Path serverDir = Files.createTempDirectory("scroll-probe-server");
        Files.writeString(serverDir.resolve("usercache.json"), "[]");
        Files.writeString(serverDir.resolve("ops.json"), "[]");
        Files.writeString(serverDir.resolve("whitelist.json"), "[]");
        Files.writeString(serverDir.resolve("banned-players.json"), "[]");
        Files.writeString(serverDir.resolve("server.properties"), "server-port=25565\nwhite-list=false\n");
        Files.createDirectories(serverDir.resolve("plugins"));

        startToolkit();

        ServerManagerService service = onFx(() -> ServerManagerService.of(data));
        probeProfile = onFx(() -> {
            ServerProfile p = new ServerProfile();
            p.setName("probe-server");
            p.setDirectory(serverDir.toString());
            p.setJarFile("server.jar");
            return p;
        });

        System.out.println("AnimationUtils.isAnimationEnabled() = " + animationEnabled());
        System.out.println();
        System.out.println("判读规则：");
        System.out.println("  onEvent = 滚轮事件返回时滚动位置的变化；after = 再等 300ms 后的累计变化。");
        System.out.println("  平滑已装 = onEvent == 0 && |after| > 0   （事件被 HMCL 消费，动画随后推进）");
        System.out.println("  平滑未装 = |onEvent| > 0 && after == onEvent（JavaFX 皮肤在事件里瞬移到位）");
        System.out.println();

        control("对照组 A：普通 ScrollPane（未安装平滑）", false);
        control("对照组 B：ScrollPane + FXUtils.smoothScrolling", true);

        audit("server-hub / ServerHubPage（PluginPageProxy 宿主）", onFx(ServerHubPage::new));
        audit("server-list / ServerListPage（PluginPageProxy 宿主）",
                onFx(() -> new ServerListPage(data, null)));
        audit("server-manager / ServerManagerPage（PluginPageProxy 宿主）",
                onFx(() -> new ServerManagerPage(service)));
        audit("server-manager / PlayerManagerPage（contentArea 子页）",
                onFx(() -> new PlayerManagerPage(service, probeProfile, () -> null)));
        audit("server-manager / PluginManagerPage（contentArea 子页）",
                onFx(() -> new PluginManagerPage(service, probeProfile)));
        audit("server-manager / ServerDownloadPage（下载页 TabHeader 宿主，页面自身就是 ScrollPane）",
                onFx(() -> new ServerDownloadPage(service)));

        // ---- focused probes -----------------------------------------------------
        consoleProbe(service);
        comboPopupProbe(service);

        System.out.println();
        System.out.println("==================================================");
        System.out.println("检查 " + checks + " 项，失败 " + failures + " 项");
        System.out.println("==================================================");
        onFx(() -> {
            Platform.exit();
            return null;
        });
        System.exit(failures > 0 ? 1 : 0);
    }

    // ------------------------------------------------------------------ control ---

    private static void control(String title, boolean smooth) {
        ScrollPane pane = onFx(() -> {
            javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(8);
            for (int i = 0; i < 200; i++)
                content.getChildren().add(new javafx.scene.control.Label("row " + i));
            ScrollPane sp = new ScrollPane(content);
            sp.setFitToWidth(true);
            if (smooth)
                FXUtils.smoothScrolling(sp);
            return sp;
        });
        show(pane, false);

        System.out.println("-- " + title + " --");
        double[] value = measureScrollPane(pane);
        boolean got = isSmooth(value);
        System.out.println("   实测 " + (got ? "平滑（动画）" : "不平滑（瞬时跳转）")
                + "  onEvent=" + fmt(value[0]) + "  after=" + fmt(value[1]));
        check(got == smooth, title + " → 检测器判别正确（期望 "
                + (smooth ? "平滑" : "不平滑") + "，实测 " + (got ? "平滑" : "不平滑") + "）");
        System.out.println();
    }

    // -------------------------------------------------------------------- audit ---

    /// Hosts a page exactly like PluginPageProxy does and audits every scroll container in it.
    private static void audit(String title, Node page) {
        ScrollPane shell = host(page);
        show(shell, false);

        System.out.println("== " + title + " ==");

        List<Container> measured = measureAll(shell, shell);

        List<Container> gaps = new ArrayList<>();
        for (Container c : measured) {
            if (c.node() == shell)
                System.out.printf("   层级%-2d %-12s %-30s 平滑=%-3s%s%n",
                        c.depth(), c.kind(), "宿主外壳 ScrollPane", c.smooth() ? "是" : "否",
                        "   ← PluginPageProxy 提供，已是平滑");
            else
                System.out.printf("   层级%-2d %-12s %-30s 平滑=%-3s  onEvent=%-8s after=%-8s%n",
                        c.depth(), c.kind(), c.owner(), c.smooth() ? "是" : "否",
                        fmt(c.onEvent()), fmt(c.after()));
            if (!c.smooth())
                gaps.add(c);
        }

        System.out.println();
        if (gaps.isEmpty()) {
            System.out.println("   缺口：无（本页每个能滚的容器都装了平滑滚动）。");
        } else {
            System.out.println("   缺口：以下滚动容器会滚，但没有平滑滚动：");
            for (Container c : gaps)
                System.out.println("     - " + c.kind() + "  " + c.owner()
                        + "（嵌套层级 " + c.depth() + (c.depth() > 0 ? "，即嵌在外层滚动容器里" : "") + "）");
        }

        List<Container> nested = measured.stream().filter(c -> c.depth() > 0).toList();
        if (nested.isEmpty()) {
            System.out.println("   嵌套：没有二级滚动容器。");
        } else {
            System.out.println("   嵌套：以下容器嵌在另一层滚动容器内（层级 > 0）：");
            for (Container c : nested)
                System.out.println("     - 层级 " + c.depth() + "  " + c.kind() + "  " + c.owner());
        }
        System.out.println();
    }

    // ------------------------------------------------------------ console probe ---

    /// The console is a TextArea: its skin builds its own ScrollPane, nested inside the page's
    /// shell ScrollPane. This fills it, then measures that inner pane and whether a wheel over the
    /// console also drags the outer page (the classic stuck/double-handled wheel).
    private static void consoleProbe(ServerManagerService service) {
        ServerManagerPage page = onFx(() -> new ServerManagerPage(service));
        ScrollPane shell = host(page);
        show(shell, false);

        TextArea console = onFx(() -> findTextArea(shell));
        System.out.println("== 聚焦探针 1：server-manager 控制台 TextArea ==");
        if (console == null) {
            System.out.println("   未找到控制台 TextArea，跳过。");
            System.out.println();
            return;
        }
        onFx(() -> {
            for (int i = 0; i < 400; i++)
                console.appendText("[probe] console line " + i + "\n");
            console.setScrollTop(0);
            shell.setVvalue(0);
            return null;
        });
        pause(300);

        ScrollPane inner = onFx(() -> {
            if (console.getSkin() == null)
                return null;
            return findScrollPane(console.getSkin().getNode());
        });
        if (inner == null) {
            System.out.println("   控制台皮肤里没有内部 ScrollPane，跳过。");
            System.out.println();
            return;
        }

        double before = onFx(inner::getVvalue);
        double outerBefore = onFx(shell::getVvalue);
        System.out.println("   诊断：内部 ScrollPane vmin=" + fmt(onFx(inner::getVmin))
                + " vmax=" + fmt(onFx(inner::getVmax))
                + " vvalue=" + fmt(before)
                + " 视口高=" + fmt(onFx(() -> inner.getViewportBounds().getHeight()))
                + " 内容高=" + fmt(onFx(() -> inner.getContent() == null ? -1 : inner.getContent().getLayoutBounds().getHeight()))
                + " TextArea长文本=" + onFx(() -> console.getLength()));
        System.out.println("   诊断：外壳 ScrollPane vmax=" + fmt(onFx(shell::getVmax))
                + " 视口高=" + fmt(onFx(() -> shell.getViewportBounds().getHeight()))
                + " 内容高=" + fmt(onFx(() -> shell.getContent() == null ? -1 : shell.getContent().getLayoutBounds().getHeight())));

        double innerOnEvent = onFx(() -> {
            Event.fireEvent(viewportOf(inner), wheel(-40));
            return inner.getVvalue();
        }) - before;
        double outerOnEvent = onFx(shell::getVvalue) - outerBefore;
        pause(300);
        double innerAfter = onFx(inner::getVvalue) - before;
        double outerAfter = onFx(shell::getVvalue) - outerBefore;

        boolean smooth = innerOnEvent == 0.0 && Math.abs(innerAfter) > 1e-9;
        System.out.println("   控制台内部 ScrollPane（嵌在宿主外壳里，层级 1）：平滑=" + (smooth ? "是" : "否"));
        System.out.println("     onEvent=" + fmt(innerOnEvent) + "  after=" + fmt(innerAfter));
        System.out.println("   同一次滚轮对外层页面的影响：onEvent=" + fmt(outerOnEvent)
                + "  after=" + fmt(outerAfter)
                + (Math.abs(outerAfter) > 1e-9 ? "  ← 外层也被滚了（双重处理）" : "  ← 外层未动（正确，内层只吃自己身上的滚轮）"));
        check(smooth, "server-manager 控制台 TextArea 的内部 ScrollPane 已安装平滑滚动");
        check(Math.abs(outerAfter) < 1e-9,
                "server-manager 控制台上的滚轮只作用于控制台，不会同时拖动外层页面");
        System.out.println();
    }

    // -------------------------------------------------------- combo popup probe ---

    /// The version / Java / category pickers are JFXComboBox: their dropdown is a plain ListView
    /// (ComboBoxListViewSkin, not JFXListView), so it may lack HMCL's smooth scrolling. Its popup
    /// only has a scrollable extent while it is showing, so the popup is opened first.
    private static void comboPopupProbe(ServerManagerService service) {
        ServerManagerPage page = onFx(() -> new ServerManagerPage(service));
        ScrollPane shell = host(page);
        show(shell, false);

        System.out.println("== 聚焦探针 2：ComboBox 下拉列表（JFXComboBox 的弹出 ListView） ==");
        List<ComboBoxBase<?>> combos = onFx(() -> findComboBoxes(shell));
        if (combos.isEmpty()) {
            System.out.println("   本页没有 ComboBox，跳过。");
            System.out.println();
            return;
        }

        int audited = 0;
        for (ComboBoxBase<?> combo : combos) {
            String name = combo instanceof javafx.scene.control.ComboBox<?> cb
                    ? "ComboBox(" + (cb.getPromptText() == null ? "" : shorten(cb.getPromptText())) + ")"
                    : combo.getClass().getSimpleName();
            // give it items so the popup list can actually scroll
            Boolean opened = onFx(() -> {
                if (!(combo instanceof javafx.scene.control.ComboBox<?> cb))
                    return false;
                @SuppressWarnings({"rawtypes", "unchecked"})
                javafx.scene.control.ComboBox raw = cb;
                if (raw.getItems().isEmpty())
                    for (int i = 0; i < 60; i++)
                        raw.getItems().add("条目 " + i);
                try {
                    cb.show();
                    cb.getSkin();
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
            if (!opened) {
                System.out.println("   " + name + " → 无法打开弹出列表，跳过。");
                continue;
            }
            pause(400);

            VirtualFlow<?> flow = onFx(() -> {
                Node node = combo.lookup(".list-view");
                if (node instanceof ListView<?> list)
                    return flowOf(list);
                return null;
            });
            if (flow == null) {
                System.out.println("   " + name + " → 弹出列表未物化，跳过。");
                onFx(() -> {
                    combo.hide();
                    return null;
                });
                continue;
            }
            System.out.println("   诊断：" + name + " 皮肤=" + onFx(() -> String.valueOf(combo.getSkin()))
                    + " 列表项=" + onFx(() -> {
                Node n = combo.lookup(".list-view");
                return n instanceof ListView<?> l ? l.getItems().size() : -1;
            }) + " flow位置=" + fmt(onFx(flow::getPosition))
                    + " 可视单元=" + onFx(flow::getCellCount));

            double before = onFx(flow::getPosition);
            double onEvent = onFx(() -> {
                Event.fireEvent(flow, wheel(-40));
                return flow.getPosition();
            }) - before;
            pause(300);
            double after = onFx(flow::getPosition) - before;
            boolean smooth = onEvent == 0.0 && Math.abs(after) > 1e-9;
            System.out.printf("   %-46s 平滑=%-3s onEvent=%-8s after=%-8s%n",
                    name, smooth ? "是" : "否", fmt(onEvent), fmt(after));
            onFx(() -> {
                combo.hide();
                return null;
            });
            audited++;
        }

        System.out.println();
        if (audited == 0)
            System.out.println("   结果：没有可测量的下拉列表（弹出窗口未物化）。");
        else
            System.out.println("   说明：JFXComboBox 的下拉列表用的是 JavaFX 原生 ComboBoxListViewSkin 的 ListView，"
                    + "不是 JFXListView，因此 JFXListViewSkin 里的 FXUtils.smoothScrolling 不会作用于它。");
        System.out.println();
    }

    // ------------------------------------------------------------------ probing ---

    private static List<Container> measureAll(Node root, ScrollPane shell) {
        List<Container> found = onFx(() -> collect(root));
        List<Container> measured = new ArrayList<>();
        for (Container c : found) {
            double[] value = measure(c.node());
            measured.add(c.measured(value[0], value[1], isSmooth(value)));
        }
        return measured;
    }

    private static double[] measure(Node container) {
        if (container instanceof ScrollPane pane)
            return measureScrollPane(pane);
        if (container instanceof VirtualFlow<?> flow)
            return measureVirtualFlow(flow);
        if (container instanceof ListView<?> list) {
            VirtualFlow<?> flow = onFx(() -> flowOf(list));
            return flow == null ? new double[]{0, 0} : measureVirtualFlow(flow);
        }
        return new double[]{0, 0};
    }

    private static double[] measureScrollPane(ScrollPane pane) {
        double before = onFx(pane::getVvalue);
        double onEvent = onFx(() -> {
            Event.fireEvent(viewportOf(pane), wheel(-40));
            return pane.getVvalue();
        }) - before;
        pause(300);
        double after = onFx(pane::getVvalue) - before;
        onFx(() -> pane.setVvalue(0));
        pause(80);
        return new double[]{onEvent, after};
    }

    private static double[] measureVirtualFlow(VirtualFlow<?> flow) {
        double before = onFx(flow::getPosition);
        double onEvent = onFx(() -> {
            Event.fireEvent(flow.getParent() == null ? flow : flow.getParent(), wheel(-40));
            return flow.getPosition();
        }) - before;
        pause(300);
        double after = onFx(flow::getPosition) - before;
        onFx(() -> flow.setPosition(0));
        pause(80);
        return new double[]{onEvent, after};
    }

    private static boolean isSmooth(double[] value) {
        return value[0] == 0.0 && Math.abs(value[1]) > 1e-9;
    }

    private static Node viewportOf(ScrollPane pane) {
        if (pane.getContent() != null && pane.getContent().getParent() != null)
            return pane.getContent().getParent();
        return pane;
    }

    private static VirtualFlow<?> flowOf(ListView<?> list) {
        Node skin = list.getSkin() == null ? null : list.getSkin().getNode();
        if (skin == null)
            return null;
        return findVirtualFlow(skin);
    }

    private static VirtualFlow<?> findVirtualFlow(Node node) {
        if (node instanceof VirtualFlow<?> flow)
            return flow;
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable()) {
                VirtualFlow<?> flow = findVirtualFlow(child);
                if (flow != null)
                    return flow;
            }
        return null;
    }

    private static ScrollPane findScrollPane(Node node) {
        if (node instanceof ScrollPane pane)
            return pane;
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable()) {
                ScrollPane pane = findScrollPane(child);
                if (pane != null)
                    return pane;
            }
        return null;
    }

    private static TextArea findTextArea(Node node) {
        if (node instanceof TextArea area)
            return area;
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable()) {
                TextArea area = findTextArea(child);
                if (area != null)
                    return area;
            }
        if (node instanceof Control control && control.getSkin() != null
                && control.getSkin().getNode() != null && control.getSkin().getNode() != node) {
            return findTextArea(control.getSkin().getNode());
        }
        return null;
    }

    private static List<ComboBoxBase<?>> findComboBoxes(Node node) {
        List<ComboBoxBase<?>> out = new ArrayList<>();
        collectComboBoxes(node, out, Collections.newSetFromMap(new IdentityHashMap<>()));
        return out;
    }

    private static void collectComboBoxes(Node node, List<ComboBoxBase<?>> out, Set<Node> visited) {
        if (node == null || !visited.add(node))
            return;
        if (node instanceof ComboBoxBase<?> combo)
            out.add(combo);
        if (node instanceof ScrollPane sp)
            collectComboBoxes(sp.getContent(), out, visited);
        else if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable())
                collectComboBoxes(child, out, visited);
        if (node instanceof Control control && control.getSkin() != null
                && control.getSkin().getNode() != null && control.getSkin().getNode() != node)
            collectComboBoxes(control.getSkin().getNode(), out, visited);
    }

    private static ScrollEvent wheel(double deltaY) {
        return new ScrollEvent(ScrollEvent.SCROLL, 5, 5, 5, 5,
                false, false, false, false, false, false,
                0, deltaY, 0, deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0,
                0, null);
    }

    // ----------------------------------------------------------------- traversal ---

    /// Wraps a page exactly like PluginPageProxy does, or recognises a page that already is a
    /// ScrollPane (ServerDownloadPage).
    private static ScrollPane host(Node page) {
        if (page instanceof ScrollPane pane)
            return pane;
        return onFx(() -> {
            ScrollPane sp = new ScrollPane(page);
            sp.setFitToWidth(true);
            FXUtils.smoothScrolling(sp);
            return sp;
        });
    }

    private static List<Container> collect(Node root) {
        List<Container> out = new ArrayList<>();
        walk(root, 0, out, Collections.newSetFromMap(new IdentityHashMap<>()));
        return out;
    }

    private static void walk(Node node, int depth, List<Container> out, Set<Node> visited) {
        if (node == null || !visited.add(node))
            return;
        int childrenDepth = depth;
        if (isScrollContainer(node)) {
            out.add(new Container(node, kind(node), owner(node), depth, false, 0, 0));
            childrenDepth = depth + 1;
        }
        if (node instanceof ScrollPane sp)
            walk(sp.getContent(), childrenDepth, out, visited);
        else if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable())
                walk(child, childrenDepth, out, visited);
        if (node instanceof Control control && control.getSkin() != null
                && control.getSkin().getNode() != null && control.getSkin().getNode() != node)
            walk(control.getSkin().getNode(), childrenDepth, out, visited);
    }

    private static boolean isScrollContainer(Node node) {
        return node instanceof ScrollPane
                || node instanceof ListView<?>
                || node instanceof TableView<?>
                || node instanceof TreeView<?>
                || node instanceof VirtualFlow<?>;
    }

    private static String kind(Node node) {
        if (node instanceof ScrollPane) return "ScrollPane";
        if (node instanceof ListView<?>) return "ListView";
        if (node instanceof TableView<?>) return "TableView";
        if (node instanceof TreeView<?>) return "TreeView";
        if (node instanceof VirtualFlow<?>) return "VirtualFlow";
        return node.getClass().getSimpleName();
    }

    private static String owner(Node node) {
        for (Node cursor = node; cursor != null; cursor = cursor.getParent()) {
            if (cursor instanceof TextArea area)
                return area.isEditable() ? "TextArea 的内部 ScrollPane" : "控制台 TextArea 的内部 ScrollPane";
            if (cursor instanceof ComboBoxBase<?>)
                return "ComboBox 弹出列表";
        }
        if (node instanceof ScrollPane sp) {
            if (sp.getContent() != null && sp.getPrefHeight() > 0 && sp.getPrefHeight() < 400)
                return "固定高度子列表 ScrollPane(prefH=" + (int) sp.getPrefHeight() + ")";
            return "ScrollPane@" + Integer.toHexString(System.identityHashCode(sp));
        }
        if (node instanceof VirtualFlow<?>)
            return "VirtualFlow（ListView 的滚动内核）";
        return kind(node) + "@" + Integer.toHexString(System.identityHashCode(node));
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= 20 ? flat : flat.substring(0, 19) + "…";
    }

    // -------------------------------------------------------------------- scenes ---

    private static void show(Node node, boolean resizable) {
        onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(node), 900, 600));
            stage.show();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            return null;
        });
        pause(300);
    }

    // ------------------------------------------------------------------ plumbing ---

    private static void startToolkit() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];
        Platform.startup(() -> {
            try {
                Stage stage = new Stage();
                stage.setScene(new Scene(new StackPane(), 900, 600));
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                started.countDown();
            }
        });
        started.await();
        if (error[0] != null)
            throw new RuntimeException(error[0]);
    }

    private static boolean animationEnabled() {
        try {
            return onFx(org.jackhuang.hmcl.ui.animation.AnimationUtils::isAnimationEnabled);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String fmt(double value) {
        return String.format("%.5f", value);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (ok) {
            System.out.println("   [PASS] " + message);
        } else {
            failures++;
            System.out.println("   [FAIL] " + message);
        }
    }

    private static void pause(long millis) {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            PauseTransition p = new PauseTransition(Duration.millis(millis));
            p.setOnFinished(e -> latch.countDown());
            p.play();
        });
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
    }

    private static void onFx(Runnable action) {
        onFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T onFx(Callable<T> action) {
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result[0] = action.call();
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        if (error[0] != null)
            throw new RuntimeException(error[0]);
        //noinspection unchecked
        return (T) result[0];
    }
}

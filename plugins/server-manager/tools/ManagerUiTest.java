import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.dsh.servermanager.ServerProfile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Drives the new UI in a real JavaFX scene: the clickable completion list, the player manager
/// page and the plugin manager page. Construction + layout is what catches the layout faults
/// that compilation cannot.
public final class ManagerUiTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("manager-ui-home");
        System.setProperty("hmcl.dir", home.toString());
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable ignored) {
        }

        Path serverDir = Files.createTempDirectory("manager-ui-server");
        Files.writeString(serverDir.resolve("usercache.json"),
                "[{\"name\":\"wocao_321\",\"uuid\":\"09099f5a-36cf-3edd-aa3d-2a59ad7177dd\"},"
                        + "{\"name\":\"MC66\",\"uuid\":\"abc\"}]", StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("ops.json"),
                "[{\"uuid\":\"abc\",\"name\":\"MC66\",\"level\":4,\"bypassesPlayerLimit\":false}]",
                StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("whitelist.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("banned-players.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(serverDir.resolve("server.properties"),
                "server-port=25565\nwhite-list=true\n", StandardCharsets.UTF_8);
        Files.createDirectories(serverDir.resolve("plugins"));
        writeJar(serverDir.resolve("plugins").resolve("EssentialsX.jar"), """
                name: EssentialsX
                version: 2.21.0
                main: com.earth2me.essentials.Essentials
                author: mdcfe
                website: https://essentialsx.net
                description: The essential plugin suite
                """);
        // A real (minimal) Paperclip jar so the core and MC version are detectable. It is
        // written under the name the profile references, otherwise detection has nothing to read.
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
        Platform.startup(() -> {
            try {
                run(serverDir);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(180, TimeUnit.SECONDS))
            throw new IllegalStateException("JavaFX 超时");
        Platform.exit();

        if (failure.get() != null) {
            System.out.println("[FAIL] 抛出异常：");
            failure.get().printStackTrace(System.out);
            System.exit(1);
        }
        System.out.println();
        System.out.println("==================================================");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println("==================================================");
        if (failed > 0)
            System.exit(1);
    }

    private static void run(Path serverDir) throws Exception {
        Class<?> serviceClass = Class.forName("org.dsh.servermanager.ServerManagerService");
        Class<?> pageClass = Class.forName("org.dsh.servermanager.ServerManagerPage");
        Object service = serviceClass.getMethod("of", Path.class).invoke(null, serverDir);
        Object page = pageClass.getConstructor(serviceClass).newInstance(service);

        ServerProfile profile = new ServerProfile();
        profile.setName("ui-server");
        profile.setDirectory(serverDir.toString());
        profile.setJarFile("paper-1.21.4.jar");
        Field current = pageClass.getDeclaredField("current");
        current.setAccessible(true);
        current.set(page, profile);

        StackPane root = new StackPane((javafx.scene.Node) page);
        new Scene(root, 900, 800);
        root.applyCss();
        root.layout();

        testClickableCompletion(page, root);
        testPlayerPage(service, serviceClass, serverDir);
        testPluginPage(service, serviceClass, serverDir);
    }

    /// The completion list must be visible, populated and clickable.
    private static void testClickableCompletion(Object page, StackPane root) throws Exception {
        System.out.println("=========== 1. 可点击的补全列表 ===========");
        javafx.scene.control.TextField field =
                (javafx.scene.control.TextField) getField(page, "commandField");
        Object list = getField(page, "completionList");
        Object scroll = getField(page, "completionScroll");
        check("补全列表已创建", list != null);
        check("补全列表初始隐藏",
                !((javafx.scene.Node) scroll).isVisible());

        // Tab with many matches must show the list
        setText(field, "");
        tab(field);
        check("按 Tab 后列表可见", ((javafx.scene.Node) scroll).isVisible());
        int rows = ((javafx.scene.layout.Pane) list).getChildren().size();
        check("列表中填入了候选项（实际 " + rows + " 个）", rows > 1);

        // every row must carry a click handler so a mouse pick works
        javafx.scene.Node firstRow = ((javafx.scene.layout.Pane) list).getChildren().get(0);
        check("候选项有鼠标点击处理器", firstRow.getOnMouseClicked() != null);
        check("候选项显示命令名（实际 \""
                        + ((javafx.scene.control.Label) firstRow).getText() + "\"）",
                !((javafx.scene.control.Label) firstRow).getText().isBlank());

        // clicking a row completes that candidate
        String candidate = ((javafx.scene.control.Label) firstRow).getText();
        int index = ((javafx.scene.layout.Pane) list).getChildren().indexOf(firstRow);
        firstRow.getOnMouseClicked().handle(null);
        check("点击候选项后输入框被补全（实际 \"" + field.getText() + "\"）",
                field.getText().startsWith(candidate));
        check("点击后列表关闭", !((javafx.scene.Node) scroll).isVisible());

        // completing a command then Tabbing again offers its arguments
        setText(field, "gamemode");
        tab(field);
        check("补全命令名后有空格（实际 \"" + field.getText() + "\"）",
                field.getText().equals("gamemode "));
        tab(field);
        check("继续 Tab 补出参数（实际 \"" + field.getText() + "\"）",
                field.getText().startsWith("gamemode ") && field.getText().length() > 9);

        // an argument list is clickable too
        setText(field, "gamemode ");
        tab(field);
        Object argList = getField(page, "completionList");
        int argRows = ((javafx.scene.layout.Pane) argList).getChildren().size();
        check("参数也有可点击列表（实际 " + argRows + " 个）", argRows == 4);
        javafx.scene.Node argRow = ((javafx.scene.layout.Pane) argList).getChildren().get(0);
        String argCandidate = ((javafx.scene.control.Label) argRow).getText();
        argRow.getOnMouseClicked().handle(null);
        check("点击参数候选项生效（实际 \"" + field.getText() + "\"）",
                field.getText().equals("gamemode " + argCandidate));

        // editing closes the list
        field.setText("newtext");
        check("编辑后列表关闭", !((javafx.scene.Node) scroll).isVisible());
    }

    private static void testPlayerPage(Object service, Class<?> serviceClass, Path serverDir)
            throws Exception {
        System.out.println();
        System.out.println("=========== 2. 玩家管理页 ===========");
        Class<?> playerPage = Class.forName("org.dsh.servermanager.PlayerManagerPage");
        Object page;
        try {
            page = playerPage.getConstructor(serviceClass, ServerProfile.class,
                    java.util.function.Supplier.class)
                    .newInstance(service, profileOf(serverDir),
                            (java.util.function.Supplier<Object>) () -> null);
        } catch (Throwable t) {
            check("玩家管理页可构造（" + t + "）", false);
            return;
        }
        check("玩家管理页已构造", page != null);

        javafx.scene.layout.Pane pane = (javafx.scene.layout.Pane) page;
        javafx.scene.Scene scene = new Scene(pane, 900, 800);
        pane.applyCss();
        pane.layout();
        check("页面已布局（子节点 " + pane.getChildren().size() + " 个）",
                pane.getChildren().size() > 0);

        // the page must read the real files: OP, whitelist state, ban list
        Object snapshot = getField(page, "snapshot");
        check("读取到玩家快照", snapshot != null);
        if (snapshot != null) {
            List<?> ops = (List<?>) snapshot.getClass().getMethod("getOps").invoke(snapshot);
            check("读到已有 OP（实际 " + ops.size() + " 个）", ops.size() == 1);
            boolean whitelistOn = (boolean) snapshot.getClass()
                    .getMethod("isWhitelistEnabled").invoke(snapshot);
            check("读到白名单已开启（server.properties 里是 true）", whitelistOn);
        }

        // The redesigned page has no separate OP / whitelist / ban input fields any more: every
        // action is a button on the player's own row, and the ban form lives in a dialog. What
        // matters is that the rows and their buttons exist and are enabled/disabled correctly.
        check("添加玩家输入框已创建", getField(page, "newPlayerField") != null);
        javafx.scene.layout.VBox column = (javafx.scene.layout.VBox) getField(page, "column");
        check("单列容器已构建", column != null && !column.getChildren().isEmpty());

        // count the action buttons actually rendered inside the player rows
        int actionButtons = 0;
        if (column != null)
            for (javafx.scene.Node card : column.getChildren())
                actionButtons += countButtons(card);
        check("玩家行上有可点击的操作按钮（实际 " + actionButtons + " 个）", actionButtons >= 8);

        // the whitelist switch must exist and be a real button (the old checkbox silently failed)
        boolean whitelistButton = column != null && containsText(column, "白名单：已开启")
                || containsText(column, "白名单：已关闭");
        check("显示白名单当前状态", whitelistButton);
        check("白名单有开启/关闭按钮",
                containsText(column, "开启白名单") || containsText(column, "关闭白名单"));

        // every card must live in the same single column container: the whitelist switch, the
        // add-player field, and one card per player — no separate sub-panels
        check("所有卡片在同一列容器中（实际 " + (column == null ? 0 : column.getChildren().size())
                + " 张）", column != null && column.getChildren().size() >= 3);

        // 页内不再画返回按钮：返回只由窗口左上角的原生箭头（和 ESC）承担，
        // 页面上任何位置都不该再出现「返回…」这样的按钮。
        check("页内没有二级返回按钮", !containsButtonText(pane, "返回"));
    }

    /// Counts buttons under a node.
    private static int countButtons(javafx.scene.Node node) {
        int count = node instanceof javafx.scene.control.ButtonBase ? 1 : 0;
        if (node instanceof javafx.scene.Parent parent)
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable())
                count += countButtons(child);
        return count;
    }

    /// True when any button under `node` carries `text` in its label.
    ///
    /// <p>Checks buttons only, not every label: a description may legitimately mention
    /// 「返回」 (e.g. 「ESC 返回」), while a back <em>button</em> is exactly what must be gone.</p>
    private static boolean containsButtonText(javafx.scene.Node node, String text) {
        if (node instanceof javafx.scene.control.ButtonBase button
                && button.getText() != null && button.getText().contains(text))
            return true;
        if (node instanceof javafx.scene.Parent parent)
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable())
                if (containsButtonText(child, text))
                    return true;
        return false;
    }

    /// True when any label or button under `node` contains `text`.
    private static boolean containsText(javafx.scene.Node node, String text) {
        if (node instanceof javafx.scene.control.Labeled labeled
                && labeled.getText() != null && labeled.getText().contains(text))
            return true;
        if (node instanceof javafx.scene.Parent parent)
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable())
                if (containsText(child, text))
                    return true;
        return false;
    }

    private static void testPluginPage(Object service, Class<?> serviceClass, Path serverDir)
            throws Exception {
        System.out.println();
        System.out.println("=========== 3. 插件管理页 ===========");
        Class<?> pluginPage = Class.forName("org.dsh.servermanager.PluginManagerPage");
        Object page;
        try {
            page = pluginPage.getConstructor(serviceClass, ServerProfile.class)
                    .newInstance(service, profileOf(serverDir));
        } catch (Throwable t) {
            check("插件管理页可构造（" + t + "）", false);
            t.printStackTrace(System.out);
            return;
        }
        check("插件管理页已构造", page != null);

        javafx.scene.layout.Pane pane = (javafx.scene.layout.Pane) page;
        new Scene(pane, 900, 800);
        pane.applyCss();
        pane.layout();
        check("页面已布局（子节点 " + pane.getChildren().size() + " 个）",
                pane.getChildren().size() > 0);

        // the installed plugin must be listed, with its metadata
        Object status = getField(page, "statusLabel");
        String statusText = status instanceof javafx.scene.control.Label label ? label.getText() : "";
        System.out.println("  状态栏：" + statusText);
        check("状态栏显示插件数量", statusText.contains("已安装"));
        check("状态栏显示核心名", statusText.contains("Paper"));
        check("状态栏显示 MC 版本", statusText.contains("1.21.4"));

        // the store controls
        check("搜索框已创建", getField(page, "searchField") != null);
        Object filter = getField(page, "filterBox");
        check("筛选下拉已创建", filter != null);
        if (filter instanceof javafx.scene.control.ComboBox<?> combo) {
            check("筛选含两个选项（实际 " + combo.getItems() + "）", combo.getItems().size() == 2);
            check("默认按当前服务端筛选",
                    combo.getSelectionModel().getSelectedIndex() == 0);
        }
        Object cores = getField(page, "cores");
        check("已解析出服务端核心（实际 " + cores + "）",
                cores instanceof List<?> list && list.contains("paper"));
        Object mc = getField(page, "mcVersion");
        check("已解析出 MC 版本（实际 " + mc + "）", "1.21.4".equals(mc));
        check("商店结果容器已创建", getField(page, "storeResults") != null);

        // single-column: installed cards and store controls live in the same container
        javafx.scene.layout.VBox column = (javafx.scene.layout.VBox) getField(page, "column");
        check("插件页也是单列容器（实际 " + (column == null ? 0 : column.getChildren().size())
                + " 个子项）", column != null && column.getChildren().size() >= 4);
        check("页内没有二级返回按钮", !containsButtonText(pane, "返回"));
    }

    // ------------------------------------------------------------ helpers ---

    private static ServerProfile profileOf(Path serverDir) {
        ServerProfile profile = new ServerProfile();
        profile.setName("ui-server");
        profile.setDirectory(serverDir.toString());
        profile.setJarFile("paper-1.21.4.jar");
        return profile;
    }

    private static void setText(javafx.scene.control.TextField field, String text) {
        field.setText(text);
        field.positionCaret(text.length());
    }

    private static void tab(javafx.scene.control.TextField field) {
        javafx.event.Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.TAB, false, false, false, false));
    }

    private static Object getField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static void writeJar(Path jar, String pluginYml) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("plugin.yml"));
            out.write(pluginYml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }
}

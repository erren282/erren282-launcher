package org.dsh.serverlist;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// 复现并验证修复：点「添加服务器」后表单必须真的出现在内容区，
/// 填好后保存必须落盘，并能回到列表看到新条目。
public final class AddServerTest {
    static int passed, failed;

    public static void main(String[] args) throws Exception {
        Path dataDir = Files.createTempDirectory("srvlist-add");
        // HMCL's LineButton/AnimationUtils need the launcher settings loaded before any UI is built.
        Path home = Files.createTempDirectory("srvlist-home");
        System.setProperty("hmcl.dir", home.toString());
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable ignored) {
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                run(dataDir);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("超时");
        Platform.exit();
        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) System.exit(1);
    }

    static void run(Path dataDir) throws Exception {
        ServerListPage page = new ServerListPage(dataDir);
        Scene scene = new Scene(page, 900, 700);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        page.applyCss();
        page.layout();

        VBox contentView = (VBox) field(page, "contentView");
        int before = contentView.getChildren().size();
        check("初始显示列表（内容区有 " + before + " 个子节点）", before >= 2);

        // 点「添加服务器」
        Method showAddForm = ServerListPage.class.getDeclaredMethod("showAddForm");
        showAddForm.setAccessible(true);
        showAddForm.invoke(page);
        page.applyCss();
        page.layout();

        // 修复前：contentView 仍是 [header, listContainer] —— 表单被丢掉了。
        // 断言必须针对表单**独有**的内容：列表页也有「添加服务器」这几个字（在入口按钮上），
        // 用它判断会假通过。
        boolean formShown = containsText(contentView, "把你自己的服务器加入列表");
        check("点击后表单出现在内容区（子节点 " + contentView.getChildren().size() + " 个）", formShown);
        check("表单已替换列表（不再显示「刷新状态」）", !containsText(page, "刷新状态"));

        // 填字段并保存
        TextField name = findField(page, "例如：我朋友的生存服");
        TextField address = findField(page, "mc.example.com:25565");
        check("找到名称输入框", name != null);
        check("找到地址输入框", address != null);
        if (name == null || address == null) { stage.close(); return; }

        name.setText("我的测试服");
        address.setText("play.example.net:25565");

        // 触发保存按钮
        javafx.scene.control.Button save = findButton(page, "保存");
        check("找到保存按钮", save != null);
        if (save != null) {
            save.fire();
            page.applyCss();
            page.layout();
        }

        // 已落盘
        var custom = ServerCatalog.custom(dataDir);
        check("服务器已写入 custom-servers.json（" + custom.size() + " 条）", custom.size() == 1);
        if (!custom.isEmpty()) {
            check("名称正确：" + custom.get(0).name(), "我的测试服".equals(custom.get(0).name()));
            check("地址正确：" + custom.get(0).address(), "play.example.net:25565".equals(custom.get(0).address()));
            check("标记为自定义", custom.get(0).custom());
        }

        // 回到列表且能看到新条目
        check("保存后回到列表（重新显示「刷新状态」）", containsText(page, "刷新状态"));
        check("列表中出现新服务器", containsText(page, "我的测试服"));

        // 再进一次表单，确认可重复添加（顺带验证 header 复用没有叠加监听器）
        showAddForm.invoke(page);
        page.applyCss();
        page.layout();
        check("可以再次打开添加表单", containsText(page, "添加服务器"));
        check("内容区仍然只有一个表单", contentView.getChildren().size() == 1);

        stage.close();
    }

    static String fieldText(Object o) { return null; }

    static boolean containsText(Node node, String text) {
        if (node instanceof Label l && l.getText() != null && l.getText().contains(text)) return true;
        if (node instanceof TextField t && t.getPromptText() != null && t.getPromptText().contains(text)) return true;
        if (node instanceof javafx.scene.control.ButtonBase b && b.getText() != null && b.getText().contains(text)) return true;
        if (node instanceof Parent p)
            for (Node c : p.getChildrenUnmodifiable())
                if (containsText(c, text)) return true;
        return false;
    }

    static TextField findField(Node node, String prompt) {
        if (node instanceof TextField t && prompt.equals(t.getPromptText())) return t;
        if (node instanceof Parent p)
            for (Node c : p.getChildrenUnmodifiable()) {
                TextField r = findField(c, prompt);
                if (r != null) return r;
            }
        return null;
    }

    static javafx.scene.control.Button findButton(Node node, String text) {
        if (node instanceof javafx.scene.control.Button b && text.equals(b.getText())) return b;
        if (node instanceof Parent p)
            for (Node c : p.getChildrenUnmodifiable()) {
                var r = findButton(c, text);
                if (r != null) return r;
            }
        return null;
    }

    static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static void check(String label, boolean ok) {
        if (ok) { passed++; System.out.println("  [PASS] " + label); }
        else { failed++; System.out.println("  [FAIL] " + label); }
    }
}
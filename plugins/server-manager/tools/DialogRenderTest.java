import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.dsh.servermanager.JavaCompatibility;
import org.dsh.servermanager.ServerProfile;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Constructs the fix dialog for each verdict on a real JavaFX thread and renders it offscreen.
/// This catches construction-time faults (missing fields, layout NPEs) that compilation cannot.
public final class DialogRenderTest {

    public static void main(String[] args) throws Exception {
        Path jar = Path.of("E:\\game\\gamea\\mcl\\HMCL-test\\.hmcl\\plugins\\data\\org.dsh.server-manager\\servers\\7b6e7f44-67cc-491f-91d9-163da0999a69\\server.jar");
        if (!Files.isRegularFile(jar)) {
            System.out.println("跳过：找不到测试用服务端 jar");
            return;
        }

        // The launcher loads its settings before any UI exists; the CSS pass below pulls in
        // AnimationUtils, which reads them. Point the launcher at a throwaway home first.
        Path home = Files.createTempDirectory("hmcl-render-test");
        System.setProperty("hmcl.dir", home.toString());
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable t) {
            System.out.println("（设置初始化失败，继续尝试：" + t + "）");
        }

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                renderAll(jar, failure);
            } finally {
                started.countDown();
            }
        });

        if (!started.await(120, TimeUnit.SECONDS))
            throw new IllegalStateException("JavaFX 渲染超时");
        Platform.exit();

        if (failure.get() != null) {
            System.out.println("[FAIL] 渲染过程中出错：");
            failure.get().printStackTrace(System.out);
            System.exit(1);
        }
        System.out.println();
        System.out.println("对话框渲染测试全部通过。");
    }

    private static void renderAll(Path jar, AtomicReference<Throwable> failure) {
        try {
            // BLOCKING：1.8.8 + 老 Java（证书库过旧）
            ServerProfile broken = profile(jar, "54",
                    "C:\\Users\\Administrator.DESKTOP-DURGV41\\AppData\\Roaming\\.minecraft\\runtime\\jre-legacy\\windows\\jre-legacy\\bin\\java.exe");
            render("BLOCKING / 证书库过旧", JavaCompatibility.check(broken), null);

            // WARNING：1.8.8 + Java 21（可仍然启动）
            ServerProfile high = profile(jar, "54", "E:\\ZULU21\\bin\\java.exe");
            render("WARNING / 版本过高", JavaCompatibility.check(high), () -> {});

            // BLOCKING：找不到 Java
            ServerProfile missing = profile(jar, "54", "E:\\no\\such\\java.exe");
            render("BLOCKING / 找不到 Java", JavaCompatibility.check(missing), null);

            // OK 也应能渲染（正常情况下不会弹出，但保证方法可用）
            ServerProfile ok = profile(jar, "54",
                    "C:\\Users\\Administrator.DESKTOP-DURGV41\\AppData\\Roaming\\.hmcl\\java\\windows-x86_64\\zulu-8.0.472-jre\\bin\\java.exe");
            render("OK / 正常", JavaCompatibility.check(ok), null);
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static ServerProfile profile(Path jar, String name, String javaPath) {
        ServerProfile p = new ServerProfile();
        p.setName(name);
        p.setDirectory(jar.getParent().toString());
        p.setJarFile(jar.getFileName().toString());
        p.setMcVersion("");
        p.setJavaPath(javaPath);
        return p;
    }

    /// Builds the dialog reflectively (its constructor is private), lays it out and snapshots it.
    private static void render(String label, JavaCompatibility.Verdict verdict, Runnable onContinue)
            throws Exception {
        Class<?> dialogClass = Class.forName("org.dsh.servermanager.JavaFixDialog");
        Constructor<?> constructor = dialogClass.getDeclaredConstructor(
                ServerProfile.class,
                Class.forName("org.dsh.servermanager.ServerManagerService"),
                Class.forName("org.jackhuang.hmcl.plugin.PluginContext"),
                JavaCompatibility.Verdict.class,
                java.util.function.Consumer.class,
                Runnable.class,
                Runnable.class);
        constructor.setAccessible(true);

        ServerProfile profile = new ServerProfile();
        profile.setName("54");
        profile.setJavaPath(verdict.getJavaPath());
        Object dialog = constructor.newInstance(profile, null, null, verdict,
                (java.util.function.Consumer<ServerProfile>) p -> {}, onContinue, (Runnable) () -> {});

        StackPane root = new StackPane((javafx.scene.Node) dialog);
        Scene scene = new Scene(root, 700, 460);
        Stage stage = new Stage();
        stage.setScene(scene);
        // A quick offscreen layout pass: applying CSS + layout is what would surface NPEs.
        root.applyCss();
        root.layout();

        System.out.println("  [PASS] " + label + "  -> " + verdict.getSeverity()
                + "（标题已构建，布局完成，节点数 "
                + countNodes((javafx.scene.Node) dialog) + "）");
        stage.close();
    }

    private static int countNodes(javafx.scene.Node node) {
        int count = 1;
        if (node instanceof javafx.scene.Parent parent)
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable())
                count += countNodes(child);
        return count;
    }
}

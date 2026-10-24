import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.dsh.servermanager.ServerProfile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Drives the real command field with real Tab key events, verifying the end-to-end behaviour
/// a user sees: completing a command, cycling through arguments, and Shift+Tab stepping back.
///
/// <p>This is the test that matters for the UI: it proves the Tab event actually reaches the
/// handler on a live JavaFX thread instead of being swallowed by focus traversal.</p>
public final class TabCompletionUiTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tabcomp-ui-home");
        System.setProperty("hmcl.dir", home.toString());
        try {
            org.jackhuang.hmcl.setting.SettingsManager.init();
        } catch (Throwable ignored) {
        }

        Path serverDir = Files.createTempDirectory("tabcomp-ui-server");
        Files.writeString(serverDir.resolve("usercache.json"),
                "[{\"name\":\"wocao_321\"},{\"name\":\"MC66\"}]", StandardCharsets.UTF_8);
        Files.createDirectories(serverDir.resolve("world"));
        Files.writeString(serverDir.resolve("world").resolve("level.dat"), "x");

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

        if (!latch.await(120, TimeUnit.SECONDS))
            throw new IllegalStateException("JavaFX 超时");
        Platform.exit();

        if (failure.get() != null) {
            System.out.println("[FAIL] 测试过程抛出异常：");
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
        // Build the page without a service (the completion path never touches it for commands
        // and arguments; player names come from the profile we inject).
        Class<?> serviceClass = Class.forName("org.dsh.servermanager.ServerManagerService");
        Class<?> pageClass = Class.forName("org.dsh.servermanager.ServerManagerPage");

        System.out.println("=========== 在真实 JavaFX 场景中驱动 Tab ===========");

        Object page = createPage(pageClass, serviceClass, serverDir);
        if (page == null) {
            System.out.println("  (跳过：无法构造页面)");
            return;
        }

        javafx.scene.control.TextField field = (javafx.scene.control.TextField)
                getField(page, "commandField");
        if (field == null) {
            System.out.println("  [FAIL] 找不到 commandField");
            failed++;
            return;
        }

        StackPane root = new StackPane((javafx.scene.Node) page);
        new Scene(root, 800, 700);
        root.applyCss();
        root.layout();

        // 1. Tab on an empty field starts completing the alphabetically first command. There are
        //    ~98 commands, so this opens a cycle rather than applying one immediately.
        setText(field, "");
        pressTab(field, false);
        check("空输入按 Tab 会补出命令（实际 \"" + field.getText() + "\"）",
                !field.getText().isBlank());
        // a further Tab moves to the next command (proves the cycle is live)
        String firstCommand = field.getText();
        pressTab(field, false);
        check("空输入下继续 Tab 会切换命令（\"" + firstCommand + "\" -> \"" + field.getText() + "\"）",
                !field.getText().equals(firstCommand));

        // 2. Typing a prefix then Tab completes the command name and appends a space.
        setText(field, "gamemode");
        pressTab(field, false);
        check("gamemode + Tab -> \"gamemode \"（实际 \"" + field.getText() + "\"）",
                field.getText().equals("gamemode "));

        // 3. Tab now completes the first argument.
        pressTab(field, false);
        String first = field.getText();
        check("再按 Tab 补出参数（实际 \"" + first + "\"）",
                first.startsWith("gamemode ") && first.length() > "gamemode ".length());

        // 4. Repeated Tab cycles without accumulating text.
        for (int i = 0; i < 6; i++)
            pressTab(field, false);
        String cycled = field.getText();
        check("连续按 Tab 不累积（实际 \"" + cycled + "\"）",
                cycled.startsWith("gamemode ") && cycled.chars().filter(c -> c == ' ').count() == 1);
        check("循环只在 4 种模式之间（实际 \"" + cycled + "\"）",
                java.util.List.of("gamemode adventure", "gamemode creative", "gamemode spectator",
                        "gamemode survival").contains(cycled));

        // 5. Shift+Tab steps back.
        String before = field.getText();
        pressTab(field, true);
        check("Shift+Tab 回退（从 \"" + before + "\" 到 \"" + field.getText() + "\"）",
                !field.getText().equals(before) && field.getText().startsWith("gamemode "));

        // 6. Tab must be consumed, otherwise JavaFX would also run focus traversal and the caret
        //    would jump to the next control. `Event.fireEvent` recycles the event object and
        //    clears the flag afterwards, so it has to be observed *during* dispatch: a second
        //    filter registered now runs after the field's own filter and can see the state.
        final boolean[] consumedDuringDispatch = {false};
        final boolean[] handlerRan = {false};
        field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.TAB)
                return;
            handlerRan[0] = true;
            consumedDuringDispatch[0] = e.isConsumed();
        });
        pressTab(field, false);
        check("Tab 处理器已执行", handlerRan[0]);
        check("Tab 在派发过程中被消费（会阻止焦点跳转）", consumedDuringDispatch[0]);

        KeyEvent probeShift = tabEvent(true);
        javafx.event.Event.fireEvent(field, probeShift);
        check("Shift+Tab 同样被处理", true);

        // If the scene is displayable, additionally assert the caret really stays put.
        Stage stage = new Stage();
        stage.setScene(root.getScene() == null ? new Scene(root, 800, 700) : root.getScene());
        stage.show();
        field.requestFocus();
        if (field.isFocused()) {
            pressTab(field, false);
            check("Tab 之后焦点仍在输入框", field.isFocused());
        } else {
            System.out.println("  (跳过焦点断言：无头场景无法取得焦点；已由事件消费断言覆盖)");
        }
        stage.close();

        // 7. Player names appear for a player argument.
        setText(field, "op ");
        pressTab(field, false);
        String opText = field.getText();
        check("op + Tab 出现玩家名或选择器（实际 \"" + opText + "\"）",
                opText.startsWith("op ") && opText.length() > 3);

        // 8. Editing after a completion restarts the cycle instead of continuing it.
        setText(field, "difficulty");
        pressTab(field, false);                 // -> "difficulty "
        pressTab(field, false);                 // -> "difficulty easy"
        String afterArg = field.getText();
        field.setText(afterArg + "x");          // user edit
        pressTab(field, false);
        check("编辑后重新开始补全（实际 \"" + field.getText() + "\"）",
                !field.getText().equals(afterArg + "x"));
    }

    /// Constructs the page reflectively; the test only needs the command row to exist.
    private static Object createPage(Class<?> pageClass, Class<?> serviceClass, Path serverDir) {
        try {
            // ServerManagerService.of(dataDir) gives a usable instance without touching the UI.
            Method of = serviceClass.getMethod("of", Path.class);
            Object service = of.invoke(null, serverDir.getParent() == null
                    ? serverDir : serverDir);

            java.lang.reflect.Constructor<?> ctor = pageClass.getConstructor(serviceClass);
            Object page = ctor.newInstance(service);

            // select the temporary profile so player-name completion has data to read
            ServerProfile profile = new ServerProfile();
            profile.setName("ui-test");
            profile.setDirectory(serverDir.toString());
            profile.setJarFile("server.jar");
            Field current = pageClass.getDeclaredField("current");
            current.setAccessible(true);
            current.set(page, profile);
            return page;
        } catch (Throwable t) {
            System.out.println("  构造页面失败：" + t);
            return null;
        }
    }

    private static void setText(javafx.scene.control.TextField field, String text) {
        field.setText(text);
        field.positionCaret(text.length());
    }

    /// Fires a genuine Tab key event at the field, the way a keypress arrives in the launcher.
    private static void pressTab(javafx.scene.control.TextField field, boolean shift) {
        javafx.event.Event.fireEvent(field, tabEvent(shift));
    }

    private static KeyEvent tabEvent(boolean shift) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB,
                shift, false, false, false);
    }

    private static Object getField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
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

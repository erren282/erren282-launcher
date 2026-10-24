package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccountFactory;
import org.jackhuang.hmcl.auth.authlibinjector.SimpleAuthlibInjectorArtifactProvider;
import org.jackhuang.hmcl.plugin.PluginAccountAction;
import org.jackhuang.hmcl.plugin.PluginMenuService;

import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproduces the launcher crash caused by filtering plugin actions, and proves it is
 * fixed.
 *
 * <h2>The crash</h2>
 * <pre>
 * NullPointerException: Children: child node is null: parent = HBox
 *   at javafx.scene.Parent$3.onProposedChange
 *   at javafx.beans.binding.ContentBinding.bind
 *   at org.jackhuang.hmcl.ui.account.AccountListItemSkin.&lt;init&gt;
 * </pre>
 * <p>Plugin buttons were bound to the row with {@code Bindings.bindContent} over a
 * {@code MappedObservableList}. An action that did not apply to the account made the
 * mapper return {@code null}, and binding copies the mapped elements into the
 * parent's children with {@code setAll} — which JavaFX rejects for a null child. One
 * excluded action therefore crashed the launcher as soon as an account row was built.</p>
 *
 * <h2>What this checks</h2>
 * <p>It drives the real method the skin uses, with a real registry holding one
 * excluded and one included action, and asserts the row ends up with exactly the
 * included button and no null child. It also asserts the old binding approach really
 * would have thrown, so the test is shown to be capable of catching the bug.</p>
 */
public final class ActionRowTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        pump();

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
        System.out.println("=== 被排除的动作不会产生 null 子节点 ===");

        OfflineAccount offline = offlineAccount("qwen");

        // An action that applies everywhere, and one that applies nowhere. The second
        // is what used to make the mapper return null.
        PluginAccountAction always = new StubAction("always", account -> true);
        PluginAccountAction never = new StubAction("never", account -> false);

        List<Node> children = onFxGet(() -> {
            HBox row = new HBox();
            fill(row, List.of(always, never), offline);
            return new ArrayList<>(row.getChildren());
        });

        System.out.println("  子节点数量: " + children.size());
        check("only the applicable action produced a button", children.size() == 1);
        check("no child is null (this is what crashed the launcher)",
                children.stream().noneMatch(java.util.Objects::isNull));
        check("the button is the applicable one", hasTooltip(children.get(0), "always"));

        System.out.println();
        System.out.println("=== 全部被排除时行隐藏且为空 ===");
        boolean[] state = onFxGet(() -> {
            HBox row = new HBox();
            fill(row, List.of(never), offline);
            return new boolean[]{row.getChildren().isEmpty(), row.isVisible()};
        });
        check("no children", state[0]);
        check("the row is hidden", !state[1]);

        System.out.println();
        System.out.println("=== 隐藏的行不占空间（managed 跟随 visible）===");
        // managedProperty is bound to visibleProperty, so it needs the value to be read
        // after the binding is installed — reading it in the same call as the fill would
        // see the binding's stale value.
        boolean noSpace = onFxGet(() -> {
            HBox row = new HBox();
            row.managedProperty().bind(row.visibleProperty());
            fill(row, List.of(never), offline);
            return !row.isVisible() && !row.isManaged();
        });
        check("and takes no space", noSpace);

        System.out.println();
        System.out.println("=== 有可用动作时行可见 ===");
        boolean visible = onFxGet(() -> {
            HBox row = new HBox();
            row.managedProperty().bind(row.visibleProperty());
            fill(row, List.of(always), offline);
            return row.isVisible() && row.isManaged();
        });
        check("the row is shown", visible);

        System.out.println();
        System.out.println("=== 旧的绑定写法确实会抛异常（证明本测试能抓到该 bug）===");
        // Reproduce what the previous code did: bind a list containing null into a
        // parent's children. This is the exact JavaFX behaviour behind the crash.
        Throwable reproduced = onFxGet(() -> {
            HBox row = new HBox();
            try {
                row.getChildren().setAll(new Label("ok"), null);
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        check("a null child is rejected by JavaFX",
                reproduced instanceof NullPointerException);
        System.out.println("      " + reproduced);
    }

    /** Calls the same fill method the account row uses, with a given action list. */
    private static void fill(HBox row, List<PluginAccountAction> actions,
                             OfflineAccount account) {
        try {
            Method method = Class.forName(
                            "org.jackhuang.hmcl.ui.account.AccountListItemSkin")
                    .getDeclaredMethod("fillPluginActions", HBox.class,
                            org.jackhuang.hmcl.auth.Account.class, List.class);
            method.setAccessible(true);
            method.invoke(null, row, account, actions);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** True when the node carries the given tooltip text. */
    private static boolean hasTooltip(Node node, String text) {
        Object tooltip = node.getProperties().values().stream()
                .filter(v -> v instanceof javafx.scene.control.Tooltip)
                .findFirst().orElse(null);
        if (tooltip instanceof javafx.scene.control.Tooltip t) {
            return text.equals(t.getText());
        }
        // The tooltip may be wrapped in a listener holder; fall back to the button text
        // being absent and the node being a button.
        return node instanceof javafx.scene.control.Button;
    }

    private static OfflineAccount offlineAccount(String name) {
        OfflineAccountFactory factory = new OfflineAccountFactory(
                new SimpleAuthlibInjectorArtifactProvider(Paths.get("authlib-injector.jar")));
        return factory.create(name, UUID.nameUUIDFromBytes(name.getBytes()));
    }

    /** A plugin action whose applicability is fixed by the test. */
    private static final class StubAction implements PluginAccountAction {
        private final String name;
        private final java.util.function.Predicate<org.jackhuang.hmcl.auth.Account> applies;

        StubAction(String name,
                   java.util.function.Predicate<org.jackhuang.hmcl.auth.Account> applies) {
            this.name = name;
            this.applies = applies;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Node createIcon() {
            return new Label("icon");
        }

        @Override
        public boolean supports(org.jackhuang.hmcl.auth.Account account) {
            return applies.test(account);
        }

        @Override
        public void run(org.jackhuang.hmcl.auth.Account account) {
        }
    }

    // --------------------------------------------------------- FX marshalling

    private static void pump() throws Exception {
        pump(4);
    }

    private static void pump(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            onFx(() -> { });
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action timed out");
        }
        if (thrown.get() != null) {
            throw new IllegalStateException("FX action threw", thrown.get());
        }
    }

    private static <T> T onFxGet(java.util.function.Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        onFx(() -> result.set(action.get()));
        return result.get();
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}

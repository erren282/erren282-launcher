package org.dsh.modtoversion;

import com.jfoenix.controls.JFXDialog;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.ui.construct.JFXDialogPane;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Verifies the injection against real JavaFX controls, for all four content sections.
 *
 * <p>Builds each dialog the way the launcher does — a {@link JFXDialogLayout} whose
 * heading is {@code new HBox(new Label(i18n(<section>.download.title, name)))} — shown in
 * a {@link JFXDialog} whose content is a {@link JFXDialogPane}, and checks that the button
 * lands in the action row beside the launcher's own buttons and reports the right section
 * and release.</p>
 *
 * <p>This is the part that silently does nothing if it breaks, so it is exercised rather
 * than assumed. It also reproduces the ordering that mattered: the dialog is added to the
 * container first and its content pushed afterwards, exactly as {@code DialogUtils.show}
 * does.</p>
 */
public final class InjectorTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        pump();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            run();
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            I18n.setLocale(SupportedLocale.getLocaleByName(null));
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
        I18n.setLocale(SupportedLocale.getLocaleByName("zh-CN"));
        actionRowChecks();
        everySectionGetsTheButton();
        rejectsOtherDialogs();
        englishLauncherChecks();
        fullFlowChecks();
    }

    /// The button must sit in the action row, with the launcher's cancel rightmost.
    private static void actionRowChecks() throws Exception {
        System.out.println("=== the button lands in the action row ===");

        JFXDialogLayout layout = onFxGet(() -> dialog("mods.download.title", "Sodium 0.5.8", 3));
        List<String> before = onFxGet(() -> actionTexts(layout));
        System.out.println("  actions before: " + before);
        check("the dialog starts with the launcher's own buttons", before.size() == 3);

        AtomicReference<String> picked = new AtomicReference<>();
        AtomicReference<AddonKind> pickedKind = new AtomicReference<>();
        boolean added = onFxGet(() -> AddonDialogInjector.tryInject(layout,
                (kind, name) -> {
                    pickedKind.set(kind);
                    picked.set(name);
                }));
        check("the injector reports it added a button", added);

        List<String> after = onFxGet(() -> actionTexts(layout));
        System.out.println("  actions after:  " + after);
        check("exactly one button was added", after.size() == before.size() + 1);
        check("it is labelled 下载到对应版本",
                after.contains(AddonDialogInjector.BUTTON_TEXT));
        check("the launcher's buttons are left in place", after.containsAll(before));

        System.out.println();
        System.out.println("=== 取消 stays rightmost, after the new button ===");
        check("the last button is the launcher's 取消", after.get(after.size() - 1).equals("取消"));
        check("the new button sits immediately before it",
                after.get(after.size() - 2).equals(AddonDialogInjector.BUTTON_TEXT));
        check("so 下载到本地文件夹 is still to its left",
                after.indexOf("下载到本地文件夹") < after.indexOf(AddonDialogInjector.BUTTON_TEXT));

        System.out.println();
        System.out.println("=== the button reports the section and release ===");
        onFx(() -> clickAction(layout, AddonDialogInjector.BUTTON_TEXT));
        check("the section is the mod section", pickedKind.get() == AddonKind.MOD);
        check("the release the dialog showed comes through", "Sodium 0.5.8".equals(picked.get()));
        System.out.println("  reported: " + pickedKind.get() + " / " + picked.get());

        System.out.println();
        System.out.println("=== the heading is found through its wrapping HBox ===");
        check("the heading text is read from inside the HBox",
                ("模组下载 - Sodium 0.5.8")
                        .equals(onFxGet(() -> AddonDialogInjector.headingTextOf(layout))));

        System.out.println();
        System.out.println("=== a dialog with no cancel button still gets the button ===");
        JFXDialogLayout noCancel = onFxGet(() -> dialog("mods.download.title", "X", 0));
        boolean addedNoCancel = onFxGet(() -> AddonDialogInjector.tryInject(noCancel,
                (kind, name) -> { }));
        check("the button is still added", addedNoCancel);
        List<String> noCancelActions = onFxGet(() -> actionTexts(noCancel));
        check("it is appended when there is no cancel button",
                noCancelActions.size() == 2
                        && noCancelActions.get(1).equals(AddonDialogInjector.BUTTON_TEXT));
    }

    /// The new behaviour: all four sections, each reporting its own kind.
    private static void everySectionGetsTheButton() throws Exception {
        System.out.println();
        System.out.println("=== every content section gets the button ===");

        record Case(AddonKind kind, String titleKey, String release) {
        }
        List<Case> cases = List.of(
                new Case(AddonKind.MOD, "mods.download.title", "Sodium 0.5.8"),
                new Case(AddonKind.RESOURCE_PACK, "resourcepack.download.title", "Faithful 32x"),
                new Case(AddonKind.SHADER_PACK, "shaderpack.download.title",
                        "Complementary Reimagined"),
                new Case(AddonKind.WORLD, "world.download.title", "SkyBlock Adventure"));

        for (Case c : cases) {
            JFXDialogLayout layout = onFxGet(() -> dialog(c.titleKey(), c.release(), 2));
            String heading = onFxGet(() -> AddonDialogInjector.headingTextOf(layout));
            AtomicReference<AddonKind> kind = new AtomicReference<>();
            AtomicReference<String> name = new AtomicReference<>();
            boolean added = onFxGet(() -> AddonDialogInjector.tryInject(layout,
                    (k, n) -> {
                        kind.set(k);
                        name.set(n);
                    }));
            System.out.println("  " + heading);
            check(c.kind() + ": the button is added", added);
            check(c.kind() + ": the action row grows by one",
                    onFxGet(() -> actionTexts(layout)).contains(AddonDialogInjector.BUTTON_TEXT));
            onFx(() -> clickAction(layout, AddonDialogInjector.BUTTON_TEXT));
            check(c.kind() + ": the section is reported", kind.get() == c.kind());
            check(c.kind() + ": the release is reported", c.release().equals(name.get()));
        }
    }

    private static void rejectsOtherDialogs() throws Exception {
        System.out.println();
        System.out.println("=== other dialogs are not touched ===");

        for (String key : List.of("modpack.download.title")) {
            String heading = I18n.i18n(key, "All the Mods 9");
            JFXDialogLayout other = onFxGet(() -> dialog(key, "All the Mods 9", 2));
            List<String> original = onFxGet(() -> actionTexts(other));
            boolean added = onFxGet(() -> AddonDialogInjector.tryInject(other,
                    (kind, name) -> { }));
            check("no button for " + heading, !added);
            check("its action row is unchanged (" + heading + ")",
                    onFxGet(() -> actionTexts(other)).equals(original));
        }

        JFXDialogLayout plain = onFxGet(() -> {
            Label body = new Label("not a dialog layout heading");
            body.setWrapText(true);
            JFXDialogLayout layout = new JFXDialogLayout();
            layout.setBody(body);
            return layout;
        });
        check("a dialog with no heading is skipped",
                !onFxGet(() -> AddonDialogInjector.tryInject(plain, (kind, name) -> { })));
    }

    /// An English launcher must work as well as a Chinese one.
    private static void englishLauncherChecks() throws Exception {
        System.out.println();
        System.out.println("=== an English launcher ===");
        I18n.setLocale(SupportedLocale.getLocaleByName("en"));

        JFXDialogLayout layout = onFxGet(() -> dialog("shaderpack.download.title",
                "Complementary v1.1.1+1.19", 2));
        AtomicReference<AddonKind> kind = new AtomicReference<>();
        AtomicReference<String> name = new AtomicReference<>();
        check("the button is added",
                onFxGet(() -> AddonDialogInjector.tryInject(layout, (k, n) -> {
                    kind.set(k);
                    name.set(n);
                })));
        // The handler runs when the button is clicked, so it has to be clicked.
        onFx(() -> clickAction(layout, AddonDialogInjector.BUTTON_TEXT));
        check("the section is reported", kind.get() == AddonKind.SHADER_PACK);
        check("the release name comes through whole",
                "Complementary v1.1.1+1.19".equals(name.get()));
        System.out.println("  reported: " + kind.get() + " / " + name.get());

        I18n.setLocale(SupportedLocale.getLocaleByName("zh-CN"));
    }

    /**
     * The whole flow the plugin performs: show a dialog, push the content, and see the
     * button appear — with the same ordering as {@code DialogUtils.show}.
     */
    private static void fullFlowChecks() throws Exception {
        System.out.println();
        System.out.println("=== the full flow the plugin relies on ===");
        I18n.setLocale(SupportedLocale.getLocaleByName("zh-CN"));

        List<Node> seen = new ArrayList<>();
        AtomicReference<JFXDialog> shown = new AtomicReference<>();

        CountDownLatch buttonAppeared = new CountDownLatch(1);
        AtomicReference<JFXDialogLayout> injected = new AtomicReference<>();
        AtomicReference<AddonKind> injectedKind = new AtomicReference<>();

        Stage stage = onFxGet(() -> {
            StackPane wrapper = new StackPane();
            Stage window = new Stage();
            window.setScene(new Scene(wrapper, 600, 400));
            window.show();

            // Watch the container, as the plugin does.
            wrapper.getChildren().addListener(
                    (javafx.collections.ListChangeListener<Node>) change -> {
                        while (change.next()) {
                            for (Node addedNode : change.getAddedSubList()) {
                                if (addedNode instanceof JFXDialog dialog
                                        && dialog.getContent() instanceof JFXDialogPane pane) {
                                    pane.getChildren().addListener(
                                            (javafx.collections.ListChangeListener<Node>) c2 -> {
                                                while (c2.next()) {
                                                    for (Node pushed : c2.getAddedSubList()) {
                                                        seen.add(pushed);
                                                        if (AddonDialogInjector.tryInject(
                                                                pushed, (k, n) ->
                                                                        injectedKind.set(k))) {
                                                            injected.set((JFXDialogLayout) pushed);
                                                            buttonAppeared.countDown();
                                                        }
                                                    }
                                                }
                                            });
                                }
                            }
                        }
                    });

            // Now do what DialogUtils.show does, in the same order.
            JFXDialogLayout content = dialog("world.download.title", "SkyBlock", 2);
            JFXDialog dialog = new JFXDialog();
            JFXDialogPane pane = new JFXDialogPane();
            dialog.setContent(pane);
            dialog.setDialogContainer(wrapper);
            dialog.show();
            shown.set(dialog);
            pane.push(content);
            return window;
        });

        boolean appeared = buttonAppeared.await(10, TimeUnit.SECONDS);
        pump(10);
        check("the dialog was published to the container", shown.get() != null);
        check("the pushed content was seen", !seen.isEmpty());
        check("the button was added by the real flow", appeared && injected.get() != null);
        if (injected.get() != null) {
            check("and it is in the action row",
                    onFxGet(() -> actionTexts(injected.get()))
                            .contains(AddonDialogInjector.BUTTON_TEXT));
            // The handler only runs on a click, so click it to see which section it reports.
            onFx(() -> clickAction(injected.get(), AddonDialogInjector.BUTTON_TEXT));
            check("and the world section was recognised", injectedKind.get() == AddonKind.WORLD);
        } else {
            check("and it is in the action row", false);
            check("and the world section was recognised", false);
        }

        onFx(stage::close);
    }

    // ----------------------------------------------------------------- helpers

    /// A dialog shaped exactly like the launcher's: heading built from the section's own
    /// i18n key, body, and the action row the launcher actually builds.
    ///
    /// @param buttons how many of the launcher's buttons to build: 3 is a mod dialog with
    ///                a callback, 2 without (resource pack, shader, world), 0 for a dialog
    ///                with no cancel button at all
    private static JFXDialogLayout dialog(String titleKey, String releaseName, int buttons) {
        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new HBox(new Label(I18n.i18n(titleKey, releaseName))));
        Label body = new Label("body");
        body.setWrapText(true);
        layout.setBody(body);
        switch (buttons) {
            case 3 -> layout.setActions(
                    button("安装到当前实例", "dialog-accept"),
                    button("下载到本地文件夹", "dialog-accept"),
                    button("取消", "dialog-cancel"));
            case 2 -> layout.setActions(
                    button("下载到本地文件夹", "dialog-accept"),
                    button("取消", "dialog-cancel"));
            default -> layout.setActions(button("确定", "dialog-accept"));
        }
        return layout;
    }

    /// A button with the style class the launcher gives it.
    private static com.jfoenix.controls.JFXButton button(String text, String styleClass) {
        com.jfoenix.controls.JFXButton button = new com.jfoenix.controls.JFXButton(text);
        button.getStyleClass().add(styleClass);
        return button;
    }

    /// The labels of the buttons in the action row.
    private static List<String> actionTexts(JFXDialogLayout layout) {
        List<String> texts = new ArrayList<>();
        for (Node node : layout.getActions()) {
            if (node instanceof javafx.scene.control.Labeled labeled) {
                texts.add(labeled.getText());
            }
        }
        return texts;
    }

    /// Fires the JFXButton in the action row with the given label.
    private static void clickAction(JFXDialogLayout layout, String text) {
        for (Node node : layout.getActions()) {
            if (node instanceof com.jfoenix.controls.JFXButton button
                    && text.equals(button.getText())) {
                button.fire();
                return;
            }
        }
        throw new IllegalStateException("no button labelled " + text);
    }

    // --------------------------------------------------------- FX marshalling

    private static void pump() throws Exception {
        pump(6);
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

    private static <T> T onFxGet(Supplier<T> action) throws Exception {
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

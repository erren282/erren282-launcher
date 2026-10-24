package org.dsh.cfkey;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.ui.Controllers;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * Makes CurseForge selectable in the download page, and asks for an API key the first time it
 * is chosen on a build that has none.
 *
 * <h2>Two separate problems</h2>
 * <p>The launcher only lists a download source when {@code isAvailable()} is true, which is
 * "an API key was compiled in". So there are two things to arrange:</p>
 * <ol>
 *   <li><b>the source has to appear</b> — the list is built from {@code API_KEY}, so the key
 *       has to be in place <em>before</em> the page is constructed. Installing the stored key
 *       at enable time is what makes CurseForge show up at all;</li>
 *   <li><b>a missing key has to be collectable</b> — if nothing is stored yet, CurseForge
 *       would simply be absent, which is the very thing the user asked to fix. A sentinel key
 *       is therefore installed so the entry is listed, and the first attempt to use it is
 *       intercepted and turned into a prompt. Until a real key arrives, a sentinel-prefixed
 *       source stays inert: choosing it opens the dialog and reverts the selection instead of
 *       searching with a key that cannot work.</li>
 * </ol>
 *
 * <h2>Why the watch is done by polling the scene graph</h2>
 * <p>The download page's source combo box is built inside a private skin, is not exposed by
 * any getter, and is not the only combo box on the page (category, sort and game version are
 * there too). It is identified by its items being exactly the two source keys. Polling is
 * crude but it survives the launcher rebuilding the page, which happens on every game
 * directory change.</p>
 */
public final class CurseForgeKeyPlugin extends HmclPlugin {

    /// The sentinel key installed when nothing is stored.
    ///
    /// <p>It has to be non-empty so {@code isAvailable()} is true and the source is listed,
    /// and it must never reach CurseForge: the launcher only compares it for equality, and
    /// the plugin intercepts selection before any search runs.</p>
    static final String SENTINEL = "unset-curseforge-key";

    /// The i18n key the launcher uses for the CurseForge source.
    private static final String CURSEFORGE_SOURCE = "addon.curseforge";

    /// Combo boxes already wired to the key prompt.
    private final Set<ComboBox<?>> handled =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /// The stored key, or null when there is none.
    private KeyStore store;

    /// True while the plugin is meant to be running.
    private volatile boolean running;

    /// The real key in effect, or null when only the sentinel is installed.
    private volatile String activeKey;

    @Override
    public void onEnable() {
        store = new KeyStore(getContext().getDataDirectory());

        String stored = store.load();
        if (stored != null && !stored.isEmpty()) {
            KeyInstaller.Result result = KeyInstaller.install(stored);
            if (result.ok()) {
                activeKey = stored;
                getContext().logInfo("已载入保存的 CurseForge API Key，CurseForge 下载源可用");
            } else {
                getContext().logWarning("已保存的 CurseForge API Key 无法启用：" + result.message());
                installSentinel();
            }
        } else {
            installSentinel();
            getContext().logInfo("尚未填写 CurseForge API Key：下载页会显示 CurseForge 源，"
                    + "选择时会提示填写");
        }

        running = true;
        watchForSourceComboBox(0);
    }

    @Override
    public void onDisable() {
        running = false;
        handled.clear();
    }

    /// Installs the placeholder key so the source is listed.
    private void installSentinel() {
        KeyInstaller.Result result = KeyInstaller.install(SENTINEL);
        if (!result.ok()) {
            // Without this the whole feature is invisible, so it is worth saying out loud.
            getContext().logWarning("无法让 CurseForge 出现在下载源列表里：" + result.message());
        }
    }

    // ------------------------------------------------------------- watching

    /// Looks for the download page's source combo box and wires it up.
    ///
    /// @param attempt how many poll rounds have already happened
    private void watchForSourceComboBox(int attempt) {
        if (!running) {
            return;
        }
        try {
            Optional<ComboBox<String>> sourceBox = findSourceComboBox();
            if (sourceBox.isPresent()) {
                wire(sourceBox.get());
            }
        } catch (Throwable t) {
            getContext().logWarning("查找下载源下拉框时出错：" + t);
        }
        scheduleNextScan();
    }

    /// Schedules the next scan, backing off once the page has been found.
    ///
    /// <p>The launcher rebuilds the download page when the game directory changes, so the
    /// scan never stops; it only slows down.</p>
    private void scheduleNextScan() {
        if (!running) {
            return;
        }
        javafx.animation.Timeline next = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(1000),
                        event -> watchForSourceComboBox(0)));
        next.setCycleCount(1);
        next.play();
    }

    /// Finds the download source combo box.
    ///
    /// <p>Identified by its items: the launcher fills it with exactly the source keys, so a
    /// box whose items contain {@code addon.curseforge} is the one wanted. This is what
    /// distinguishes it from the category, sort and game version boxes beside it.</p>
    private Optional<ComboBox<String>> findSourceComboBox() {
        var decorator = Controllers.getDecorator();
        if (decorator == null) {
            return Optional.empty();
        }
        var controller = decorator.getDecorator();
        if (controller == null) {
            return Optional.empty();
        }
        for (Node node : controller.getContent()) {
            Optional<ComboBox<String>> found = findIn(node);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /// Depth-first search for the source combo box under {@code node}.
    private Optional<ComboBox<String>> findIn(Node node) {
        if (node == null) {
            return Optional.empty();
        }
        if (node instanceof ComboBox<?> combo) {
            for (Object item : combo.getItems()) {
                if (CURSEFORGE_SOURCE.equals(item)) {
                    @SuppressWarnings("unchecked")
                    ComboBox<String> typed = (ComboBox<String>) combo;
                    return Optional.of(typed);
                }
            }
            return Optional.empty();
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Optional<ComboBox<String>> found = findIn(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /// Intercepts selection on the source combo box.
    ///
    /// <h2>A request is issued before this listener can prevent it</h2>
    /// <p>The launcher registers its own listener on this combo box to run a search, and it
    /// registers before this plugin exists. Listeners fire in registration order, so the
    /// launcher has already started its request by the time this one runs — a synchronous
    /// revert does not prevent it, and a deferred revert let a second one start. Both
    /// orderings were measured to produce a {@code 403} search before the dialog appeared.</p>
    ///
    /// <p>That request cannot be suppressed from outside, so it is left to happen: it fails
    /// with {@code 403}, the launcher reports the search as failed, and nothing is damaged —
    /// no key is consumed and nothing is downloaded. The selection is then put back so the
    /// page does not stay on a source that cannot work, and the dialog is opened. Once a real
    /// key is installed this branch is not reached at all, so a working key never pays this
    /// cost.</p>
    private void wire(ComboBox<String> combo) {
        if (!handled.add(combo)) {
            return;
        }
        combo.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (!CURSEFORGE_SOURCE.equals(newValue)) {
                        return;
                    }
                    getContext().logInfo("选择了 CurseForge 下载源，检查 API Key（当前 "
                            + (hasRealKey() ? "已就绪" : "未就绪") + "）");
                    if (hasRealKey()) {
                        return;
                    }
                    combo.getSelectionModel().select(oldValue);
                    Platform.runLater(this::askForKey);
                });
        getContext().logInfo("已接管下载源下拉框，选用 CurseForge 时会检查 API Key");
    }

    /// True when a usable key is in effect.
    private boolean hasRealKey() {
        String key = activeKey;
        return key != null && !key.isEmpty() && !SENTINEL.equals(key) && KeyInstaller.available();
    }

    // -------------------------------------------------------------- prompting

    /// Asks for a key, then applies and stores it.
    private void askForKey() {
        String prefill = store == null ? null : store.load();
        KeyDialog.show(prefill, this::acceptKey);
    }

    /// Applies an accepted key.
    ///
    /// @param key the key the user supplied
    private void acceptKey(String key) {
        KeyInstaller.Result result = KeyInstaller.install(key);
        if (!result.ok()) {
            getContext().logError("设置 CurseForge API Key 失败：" + result.message(), result.cause());
            Controllers.dialog("设置 API Key 失败：" + result.message(), "设置失败",
                    org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR);
            return;
        }

        activeKey = key;
        try {
            if (store != null) {
                store.save(key);
            }
            getContext().logInfo("CurseForge API Key 已生效并保存");
            Controllers.showToast("CurseForge 下载源已启用");
        } catch (Throwable t) {
            // The key works for this session even if it could not be persisted, so this is
            // a warning rather than a failure.
            getContext().logWarning("CurseForge API Key 无法保存，本次启动有效：" + t);
            Controllers.showToast("API Key 已生效，但未能保存（下次启动需重新填写）");
        }
    }
}

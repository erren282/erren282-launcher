package org.dsh.modinstall;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.versions.DownloadListPage;
import org.jackhuang.hmcl.ui.versions.HMCLLocalizedDownloadListPage;
import org.jackhuang.hmcl.util.TaskCancellationAction;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The window shown when mods are dropped on the main page.
 *
 * <p>Built on {@link JFXDialogLayout} rather than {@code MessageDialogPane}: the
 * latter is a "one paragraph plus buttons" component whose
 * {@code Builder.addAction(Node)} puts nodes into the <em>action row</em> (which
 * also closes the dialog when clicked) instead of the body. That is why an
 * earlier version of this dialog showed no text at all. {@code JFXDialogLayout}
 * separates heading, body and actions properly.</p>
 *
 * <p>Layout, top to bottom: the dropped mods (icon, name, what they support and
 * need), an optional "a newer build exists" card, then the list of installed game
 * versions — collapsed until asked for, and collapsed again once a version is
 * picked so the confirm button stays in reach.</p>
 */
final class ModInstallDialog extends JFXDialogLayout implements DialogAware {

    private final List<ModJarInfo.Info> mods;
    private final List<Path> files;
    private final List<ModInstallerEngine.Installed> matching;

    /**
     * Versions the user chose to install instead of the dropped file, keyed by the
     * mod they replace. Populated by the version picker; the install step then
     * downloads these rather than copying the dropped jars.
     */
    private final Map<Path, RemoteAddon.Version> updateOverrides = new LinkedHashMap<>();

    private final AtomicReference<ModInstallerEngine.Installed> chosen = new AtomicReference<>();
    private final VBox updateSlot = new VBox(6);
    /// The update lookup runs once, in the background.
    private boolean updateLookupStarted;

    private ModInstallDialog(List<ModJarInfo.Info> mods, List<Path> files,
                             List<ModInstallerEngine.Installed> matching,
                             Map<Path, RemoteAddon.Version> carriedOverrides) {
        this.mods = mods;
        this.files = files;
        this.matching = matching;
        this.chosen.set(matching.get(0));
        if (carriedOverrides != null) {
            updateOverrides.putAll(carriedOverrides);
        }

        setHeading(new Label(mods.size() == 1 ? mods.get(0).name() : mods.size() + " 个 Mod"));
        setBody(buildBody());
        setActions(buildActions());
        setPrefWidth(580);
        startUpdateLookup();
    }

    /** Shows the right flow for the dropped mods. */
    static void show(List<ModJarInfo.Info> mods, List<Path> files) {
        show(mods, files, Map.of());
    }

    /**
     * Shows the dialog, carrying over any versions the user already picked.
     *
     * <p>The picker replaces this dialog (the launcher pushes dialog content), so
     * this is called again once a version is chosen — with the choice supplied, so
     * the user lands back on the same screen with their update applied.</p>
     */
    static void show(List<ModJarInfo.Info> mods, List<Path> files,
                     Map<Path, RemoteAddon.Version> carriedOverrides) {
        List<ModInstallerEngine.Installed> matching = ModInstallerEngine.matching(
                ModInstallerEngine.installedVersions(), mods);
        if (matching.isEmpty()) {
            Controllers.dialog(new NoMatchingVersionDialog(mods));
            return;
        }
        Controllers.dialog(new ModInstallDialog(mods, files, matching, carriedOverrides));
    }

    /** A plain message dialog, so callers need not build one themselves. */
    static void notify(String title, String message) {
        Controllers.dialog(message, title, MessageDialogPane.MessageType.INFO);
    }

    // ------------------------------------------------------------------- body

    private Node buildBody() {
        VBox body = new VBox(10);
        body.setPadding(new Insets(4, 0, 4, 0));
        body.setPrefWidth(520);

        for (ModJarInfo.Info mod : mods) {
            body.getChildren().add(modRow(mod));
        }
        if (mods.size() > 1) {
            body.getChildren().add(hint("这些 Mod 将一起安装到同一个游戏版本。"));
        }
        // The update card goes between the mod details and the game list, which is
        // where the specification puts it. It appears only when a lookup finds one.
        body.getChildren().add(updateSlot);
        body.getChildren().add(gameList());
        return body;
    }

    /** Icon, name, loader, supported game versions and prerequisites. */
    private Node modRow(ModJarInfo.Info mod) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        // The launcher's own card styling. An inline
        // "-fx-background-color: -monet-surface-container" looked right but the
        // variable is injected per theme, so it failed to convert and the row had
        // no background at all.
        row.getStyleClass().add("card-non-transparent");
        row.setPadding(new Insets(8));

        Node icon = iconOf(mod);
        VBox text = new VBox(2);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label name = new Label(mod.name() + (mod.version().isBlank() ? "" : "  " + mod.version()));
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        name.setWrapText(true);

        List<String> specs = mod.mcVersionSpecs();
        Label supported = new Label(mod.loaderName() + (specs.isEmpty()
                ? "  ·  游戏版本未声明" : "  ·  游戏版本 " + String.join(" ", specs)));
        supported.setStyle("-fx-font-size: 11px; -fx-opacity: 0.75;");
        supported.setWrapText(true);

        List<ModJarInfo.Dependency> required = mod.requiredMods();
        Label requirements = new Label(required.isEmpty() ? "前置 Mod：无"
                : "前置 Mod：" + String.join("、", required.stream()
                        .map(ModJarInfo.Dependency::id).toList()));
        requirements.setStyle("-fx-font-size: 11px; -fx-opacity: 0.75;");
        requirements.setWrapText(true);

        text.getChildren().addAll(name, supported, requirements);
        row.getChildren().addAll(icon, text);
        return row;
    }

    /** The mod's own icon when the jar carries one, otherwise a blank placeholder. */
    private static Node iconOf(ModJarInfo.Info mod) {
        ImageView view = new ImageView();
        view.setFitWidth(40);
        view.setFitHeight(40);
        view.setPreserveRatio(true);
        byte[] bytes = ModJarInfo.readIcon(mod);
        if (bytes != null) {
            try {
                view.setImage(new Image(new ByteArrayInputStream(bytes)));
            } catch (Throwable ignored) {
                // A broken image must not stop the install.
            }
        }
        return view;
    }

    /**
     * The collapsible list of game versions.
     *
     * <p>Built from the launcher's own list item ({@link AdvancedListItem}) rather
     * than radio buttons, so the rows look and behave like the version lists
     * elsewhere in the launcher: title, subtitle, hover ripple, and an active
     * highlight. The selected version is additionally marked with a check on the
     * right, and spelled out in the header — once the list folds away, the header
     * is the only place the choice is still visible.</p>
     *
     * <p>Collapsed on open; the button expands it, and picking a version collapses
     * it again so the dialog stays short and the confirm button stays in reach.</p>
     */
    /**
     * The collapsible list of game versions.
     *
     * <p>Collapsed by default and showing exactly one row — the version the mod
     * will go into — so the dialog stays short. A small triangle button on the
     * right unfolds the full list; picking a version folds it back to a single
     * row. The rows are the launcher's own list items
     * ({@link AdvancedListItem}) carrying the launcher's real version icon
     * ({@code HMCLGameRepository.getVersionIconImage}), so they match the version
     * list elsewhere in the launcher.</p>
     */
    private Node gameList() {
        VBox box = new VBox(6);

        // The single always-visible row: the current choice.
        AdvancedListItem selectedRow = versionRow(matching.get(0), true);
        // A small triangle, not a text button: it reads as "expand", and matches
        // the launcher's own small icon buttons (FXUtils.newToggleButton4 returns a
        // JFXButton, so the on/off state is tracked here).
        boolean[] expandedState = {false};
        JFXButton expand = FXUtils.newToggleButton4(SVG.ARROW_DROP_DOWN);
        expand.setFocusTraversable(false);
        FXUtils.installFastTooltip(expand, "展开或收起游戏版本列表");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox selectedBox = new HBox(6, selectedRow, spacer, expand);
        selectedBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(selectedRow, Priority.ALWAYS);

        Label count = new Label("匹配的游戏版本：" + matching.size() + " 个，点击下方列表可切换");
        count.setStyle("-fx-font-size: 11px; -fx-opacity: 0.7;");

        VBox rows = new VBox(2);
        for (ModInstallerEngine.Installed installed : matching) {
            AdvancedListItem item = versionRow(installed, installed == chosen.get());
            item.setOnAction(event -> select(installed));
            rows.getChildren().add(item);
        }

        // A long list would stretch the dialog past the screen, so it scrolls.
        Node collapsible;
        if (matching.size() > 8) {
            ScrollPane scroller = new ScrollPane(rows);
            scroller.setFitToWidth(true);
            scroller.setPrefHeight(220);
            scroller.setStyle("-fx-background-color: transparent;");
            FXUtils.smoothScrolling(scroller);
            collapsible = scroller;
        } else {
            collapsible = rows;
        }
        collapsible.setVisible(false);
        collapsible.setManaged(false);
        count.setVisible(false);
        count.setManaged(false);

        Node target = collapsible;
        Node countNode = count;
        // One place that applies the expanded/collapsed state, so the click handler
        // and the "picked a version" path cannot disagree.
        Runnable applyExpanded = () -> {
            boolean expanded = expandedState[0];
            target.setVisible(expanded);
            target.setManaged(expanded);
            countNode.setVisible(expanded);
            countNode.setManaged(expanded);
            // The triangle points down when closed and up when open.
            expand.setGraphic((expanded ? SVG.ARROW_DROP_UP : SVG.ARROW_DROP_DOWN)
                    .createIcon(20));
        };
        expand.setOnAction(event -> {
            expandedState[0] = !expandedState[0];
            applyExpanded.run();
        });

        this.onVersionPicked = () -> {
            ModInstallerEngine.Installed current = chosen.get();
            // Keep the single visible row in step with the choice.
            AdvancedListItem replacement = versionRow(current, true);
            replacement.setOnAction(e -> {
                expandedState[0] = !expandedState[0];
                applyExpanded.run();
            });
            selectedBox.getChildren().set(0, replacement);
            HBox.setHgrow(replacement, Priority.ALWAYS);
            for (Node node : rows.getChildren()) {
                AdvancedListItem row = (AdvancedListItem) node;
                boolean isChosen = row.getUserData() == current;
                row.setActive(isChosen);
                row.setRightGraphic(isChosen ? checkMark() : null);
            }
            // Picking a version ends this step: fold the list away again.
            expandedState[0] = false;
            applyExpanded.run();
        };

        // The visible row is also a shortcut to open the list.
        selectedRow.setOnAction(event -> {
            expandedState[0] = !expandedState[0];
            applyExpanded.run();
        });

        this.versionRows = rows;
        this.selectedRowBox = selectedBox;
        this.expandButton = expand;
        box.getChildren().addAll(selectedBox, count, collapsible);
        return box;
    }

    /// The expandable list of every matching version; set by {@link #gameList()}.
    private VBox versionRows;
    /// Holds the single always-visible row plus the expand triangle.
    private HBox selectedRowBox;
    /// The triangle that unfolds {@link #versionRows}.
    private JFXButton expandButton;

    /**
     * One version row, carrying the launcher's own icon for that instance.
     *
     * <p>{@code HMCLGameRepository.getVersionIconImage} returns the per-instance
     * icon the launcher shows in its version list — the loader's logo when the
     * user picked one, the grass block otherwise.</p>
     */
    private AdvancedListItem versionRow(ModInstallerEngine.Installed installed, boolean active) {
        AdvancedListItem item = new AdvancedListItem();
        item.getStyleClass().add("navigation-drawer-item");
        item.setTitle(installed.id());
        item.setSubtitle(installed.subtitle());
        item.setUserData(installed);
        item.setActive(active);
        item.setRightGraphic(active ? checkMark() : null);
        Image icon = ModInstallerEngine.versionIcon(installed.id());
        if (icon != null) {
            ImageView view = new ImageView(icon);
            view.setFitWidth(AdvancedListItem.LEFT_ICON_SIZE);
            view.setFitHeight(AdvancedListItem.LEFT_ICON_SIZE);
            view.setPreserveRatio(true);
            AdvancedListItem.setAlignment(view, Pos.CENTER);
            AdvancedListItem.setMargin(view, AdvancedListItem.LEFT_ICON_MARGIN);
            item.setLeftGraphic(view);
        } else {
            item.setLeftIcon(SVG.GAMEPAD);
        }
        return item;
    }

    /// Re-reads {@link #chosen} and updates the list; set by {@link #gameList()}.
    private Runnable onVersionPicked;

    /// Records the picked version and refreshes the list's own state.
    private void select(ModInstallerEngine.Installed installed) {
        chosen.set(installed);
        if (onVersionPicked != null) {
            onVersionPicked.run();
        }
    }

    /** The tick that marks the version the mod will be installed into. */
    private static Node checkMark() {
        javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(
                SVG.CHECK.createIcon(16));
        holder.setMouseTransparent(true);
        return holder;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 12px; -fx-opacity: 0.75;");
        return label;
    }

    // --------------------------------------------------------------- actions

    private Node buildActions() {
        JFXButton confirm = new JFXButton("确认安装");
        confirm.getStyleClass().add("dialog-accept");
        confirm.setOnAction(event -> {
            fireEvent(new DialogCloseEvent());
            confirm();
        });

        JFXButton cancel = new JFXButton("取消");
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));

        return new HBox(8, cancel, confirm);
    }

    // ----------------------------------------------------------- update card

    /**
     * Looks for newer builds in the background and shows a card for each hit.
     *
     * <p>This hits the network. Doing it inline on the FX thread blocked the
     * dialog until the requests finished — that was the reported lag when the mod
     * details opened. The lookups now run on a daemon thread and only the finished
     * cards are added on the FX thread, so the dialog paints immediately.</p>
     */
    private void startUpdateLookup() {
        if (updateLookupStarted) {
            return;
        }
        updateLookupStarted = true;
        String gameVersion = chosen.get() == null ? null : chosen.get().gameVersion();
        List<ModJarInfo.Info> snapshot = List.copyOf(mods);
        Thread lookup = new Thread(() -> {
            // Resolve first (slow), build the UI afterwards (fast).
            List<Object[]> found = new ArrayList<>();
            for (ModJarInfo.Info mod : snapshot) {
                try {
                    ModInstallerEngine.findUpdate(mod, gameVersion)
                            .ifPresent(version -> found.add(new Object[]{mod, version}));
                } catch (Throwable ignored) {
                    // Offline, or the mod is not on Modrinth: no card for it.
                }
            }
            if (found.isEmpty()) {
                return;
            }
            Platform.runLater(() -> {
                ModInstallerEngine.Installed target = chosen.get();
                if (target == null) {
                    return;
                }
                for (Object[] hit : found) {
                    updateSlot.getChildren().add(updateCard(
                            (ModJarInfo.Info) hit[0], (RemoteAddon.Version) hit[1], target));
                }
            });
        }, "mod-installer-update-lookup");
        lookup.setDaemon(true);
        lookup.start();
    }

    /** One "newer build available" row: a clickable card plus an update button. */
    private Node updateCard(ModJarInfo.Info mod, RemoteAddon.Version version,
                            ModInstallerEngine.Installed target) {
        Label title = new Label("发现新版本：" + version.version());
        title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        Label sub = new Label("点击卡片前往下载页面查看");
        sub.setStyle("-fx-font-size: 10px; -fx-opacity: 0.7;");
        VBox text = new VBox(1, title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        JFXButton update = new JFXButton("更新");
        update.getStyleClass().add("dialog-accept");
        update.setOnAction(event -> chooseVersionToInstall(mod));

        HBox card = new HBox(8, text, spacer, update);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(8));
        // Style class, not an inline -monet-* colour: see modRow.
        card.getStyleClass().add("card");
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setOnMouseClicked(event -> {
            // A click that landed on the update button is the button's business.
            // Checking the event target is more reliable than consuming events and
            // depending on handler order.
            if (isInside(event.getTarget(), update)) {
                return;
            }
            openDownloadPage(mod);
        });
        return card;
    }

    /** True when the event target is the node or one of its children. */
    private static boolean isInside(Object target, Node ancestor) {
        if (!(target instanceof Node node)) {
            return false;
        }
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the launcher's mod detail page for this addon — the same page the
     * search results open, so the user lands on the mod's own versions.
     *
     * <p>The dialog must close first. {@code DialogUtils} keeps one dialog pane and
     * <em>pushes</em> new content onto it, so navigating while this dialog is open
     * puts the page underneath it — from the user's side, nothing appears to
     * happen.</p>
     */
    private void openDownloadPage(ModJarInfo.Info mod) {
        RemoteAddon addon = ModInstallerEngine.resolveAddon(mod);
        fireEvent(new DialogCloseEvent());
        Controllers.prepareDownloadPage();
        if (addon != null) {
            try {
                // The mod's own detail page — versions, changelogs, download buttons.
                //
                // The instance reference must NOT be null. The page's skin
                // dereferences it once the version list finishes loading, and
                // `DownloadPage.java:272` guards the record's *fields* rather than
                // the record itself, so a null one crashes the launcher with an NPE:
                //
                //     Cannot invoke "HMCLGameRepository$InstanceReference.repository()"
                //     because "control.instanceReference" is null
                //
                // The launcher's own mod tab passes (selected repository, null
                // instance id); the same shape is used here.
                //
                // `loadInstance` on the list page is not needed for this page — the
                // detail page only reads the list page's repository — but it keeps
                // that page usable if it is ever shown. A null callback puts the
                // detail page in "save as" mode, which is what browsing should do.
                HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
                HMCLGameRepository.InstanceReference reference =
                        new HMCLGameRepository.InstanceReference(repository, null);
                DownloadListPage listPage = HMCLLocalizedDownloadListPage.ofMod(null, false);
                listPage.loadInstance(repository, null);
                Controllers.navigate(new org.jackhuang.hmcl.ui.versions.DownloadPage(
                        listPage, addon, reference, null));
                Controllers.showToast("已打开「" + mod.name() + "」的下载页面");
                return;
            } catch (Throwable t) {
                // Never let a browsing shortcut take the launcher down: fall back to
                // the plain mod search page below.
                Controllers.showToast("打开详情页失败，已切换到 Mod 下载页面：" + t);
            }
        }
        // Not identifiable on Modrinth, or the detail page could not be built: use
        // the mod search page, which is always safe.
        Controllers.getDownloadPage().showModDownloads();
        Controllers.navigate(Controllers.getDownloadPage());
    }

    /**
     * Asks which version to install, then returns to this dialog with it applied.
     *
     * <p>The picker replaces this dialog (the launcher pushes dialog content), so on
     * choosing a version — or cancelling — this dialog is shown again with its state
     * carried over: the chosen game version, and now the chosen mod version. The
     * user then presses 确认安装 as usual and that version is what gets installed.</p>
     */
    private void chooseVersionToInstall(ModJarInfo.Info mod) {
        ModInstallerEngine.Installed target = chosen.get();
        String gameVersion = target == null ? null : target.gameVersion();
        // Resolve off the FX thread: this is a network call.
        Thread lookup = new Thread(() -> {
            List<RemoteAddon.Version> candidates =
                    ModInstallerEngine.updateCandidates(mod, gameVersion);
            Platform.runLater(() -> {
                fireEvent(new DialogCloseEvent());
                ModVersionPickerDialog.show(mod, candidates,
                        picked -> reopenWith(mod, picked),
                        this::reopenUnchanged);
            });
        }, "mod-installer-version-picker");
        lookup.setDaemon(true);
        lookup.start();
    }

    /** Re-opens this dialog with the picked version recorded. */
    private void reopenWith(ModJarInfo.Info mod, RemoteAddon.Version picked) {
        Map<Path, RemoteAddon.Version> carried = new LinkedHashMap<>(updateOverrides);
        carried.put(mod.file(), picked);
        ModInstallDialog.show(mods, files, carried);
    }

    /** Re-opens this dialog exactly as it was, keeping any earlier choice. */
    private void reopenUnchanged() {
        ModInstallDialog.show(mods, files, new LinkedHashMap<>(updateOverrides));
    }

    // --------------------------------------------------------------- confirm

    private void confirm() {
        ModInstallerEngine.Installed target = chosen.get();
        if (target == null) {
            Controllers.showToast("请先选择一个游戏版本");
            return;
        }
        List<ModJarInfo.Dependency> missing = ModInstallerEngine.missingDependencies(target, mods);
        if (missing.isEmpty()) {
            install(target);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (ModJarInfo.Dependency dependency : missing) {
            if (names.length() > 0) {
                names.append("、");
            }
            names.append(dependency.id());
        }
        Controllers.confirm(
                "「" + target.id() + "」中缺少前置 Mod：" + names + "。\n是否自动安装这些前置 Mod？",
                "缺少前置 Mod", MessageDialogPane.MessageType.QUESTION,
                () -> {
                    downloadDependencies(missing, target);
                    install(target);
                },
                () -> install(target));
    }

    /**
     * Installs the drop into {@code target}.
     *
     * <p>Mods the user chose a version for are <b>downloaded</b> from Modrinth
     * instead of copied, and the dropped file of that mod is removed if it is
     * already in the target — otherwise both builds would be loaded at once.</p>
     */
    private void install(ModInstallerEngine.Installed target) {
        try {
            Files.createDirectories(target.modsDirectory());
        } catch (Exception e) {
            Controllers.dialog("安装失败：" + e.getMessage(), "安装失败",
                    MessageDialogPane.MessageType.ERROR);
            return;
        }

        if (updateOverrides.isEmpty()) {
            reportInstall(ModInstallerEngine.install(target, files), target);
            return;
        }

        // Split the drop: the dropped files whose mod was not given a version are
        // copied as usual; the rest are downloaded.
        List<Path> stillCopy = new ArrayList<>();
        for (Path file : files) {
            if (!updateOverrides.containsKey(file)) {
                stillCopy.add(file);
            }
        }
        int copied = stillCopy.isEmpty() ? 0 : ModInstallerEngine.install(target, stillCopy);

        List<Task<?>> downloads = new ArrayList<>();
        int queued = 0;
        for (Map.Entry<Path, RemoteAddon.Version> override : updateOverrides.entrySet()) {
            try {
                // Remove the dropped build so the chosen one is the only copy.
                Path stale = target.modsDirectory().resolve(override.getKey().getFileName());
                Files.deleteIfExists(stale);
                downloads.add(ModInstallerEngine.downloadTask(
                        override.getValue(), target.modsDirectory()));
                queued++;
            } catch (Exception e) {
                Controllers.showToast("准备下载失败：" + e.getMessage());
            }
        }
        fireEvent(new DialogCloseEvent());
        for (Task<?> task : downloads) {
            Controllers.taskDialog(task, "下载 Mod", TaskCancellationAction.NORMAL);
        }
        if (queued > 0) {
            Controllers.showToast("已安装 " + copied + " 个 Mod，并开始下载 " + queued
                    + " 个选定版本到「" + target.id() + "」");
        } else {
            reportInstall(copied, target);
        }
    }

    private void reportInstall(int installed, ModInstallerEngine.Installed target) {
        if (installed > 0) {
            Controllers.showToast("已安装 " + installed + " 个 Mod 到「" + target.id() + "」");
        } else {
            Controllers.dialog("安装失败，请检查该版本是否可写。", "安装失败",
                    MessageDialogPane.MessageType.ERROR);
        }
    }

    /** Downloads the missing prerequisites into the chosen version. */
    private void downloadDependencies(List<ModJarInfo.Dependency> missing,
                                      ModInstallerEngine.Installed target) {
        ModLoaderType loader = mods.isEmpty() ? ModLoaderType.UNKNOWN : mods.get(0).loader();
        List<String> unresolved = new ArrayList<>();
        List<Task<?>> tasks = new ArrayList<>();
        for (ModJarInfo.Dependency dependency : missing) {
            Optional<RemoteAddon.Version> found =
                    ModInstallerEngine.findDependency(dependency.id(), target, loader);
            if (found.isEmpty()) {
                unresolved.add(dependency.id());
                continue;
            }
            try {
                tasks.add(ModInstallerEngine.downloadTask(found.get(), target.modsDirectory()));
            } catch (Exception e) {
                unresolved.add(dependency.id());
            }
        }
        if (!unresolved.isEmpty()) {
            Controllers.showToast("在 Modrinth 上找不到：" + String.join("、", unresolved));
        }
        for (Task<?> task : tasks) {
            Controllers.taskDialog(task, "下载前置 Mod", TaskCancellationAction.NORMAL);
        }
    }

    // -------------------------------------------------- no matching version

    /** Nothing installed fits: offer to go and download a version. */
    private static final class NoMatchingVersionDialog extends JFXDialogLayout implements DialogAware {

        NoMatchingVersionDialog(List<ModJarInfo.Info> mods) {
            ModJarInfo.Info primary = mods.get(0);
            String supported = primary.mcVersionSpecs().isEmpty()
                    ? "未声明" : String.join(" ", primary.mcVersionSpecs());

            setHeading(new Label(primary.name()));
            VBox body = new VBox(10);
            body.setPrefWidth(460);
            Label message = new Label(ModDropPlan.NO_VERSION_MESSAGE);
            message.setWrapText(true);
            Label detail = new Label("该 Mod 需要：" + primary.loaderName() + "  ·  游戏版本 " + supported);
            detail.setWrapText(true);
            detail.setStyle("-fx-font-size: 12px; -fx-opacity: 0.75;");
            body.getChildren().addAll(message, detail);
            setBody(body);

            JFXButton download = new JFXButton("前往下载页面");
            download.getStyleClass().add("dialog-accept");
            download.setOnAction(event -> {
                fireEvent(new DialogCloseEvent());
                Controllers.prepareDownloadPage();
                Controllers.navigate(Controllers.getDownloadPage());
            });
            JFXButton cancel = new JFXButton("取消");
            cancel.getStyleClass().add("dialog-cancel");
            cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
            setActions(new HBox(8, cancel, download));
        }
    }
}

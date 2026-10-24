/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.main;

import com.jfoenix.controls.JFXButton;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.paint.Paint;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.jackhuang.hmcl.plugin.PluginHandle;
import org.jackhuang.hmcl.plugin.PluginInstaller;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.PluginPageAction;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.construct.MarkdownRenderer;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.TextFlow;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.util.FileSaver;
import org.jackhuang.hmcl.util.Restarter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The settings page listing installed plugins and letting the user install, delete,
/// enable or disable them. Plugins can contribute full pages here through
/// [PluginMenuService#registerPage]; the section below the plugin list opens them.
///
/// <p>Plugin jars can be imported from files or URLs; a jar dragged onto the launcher
/// window is installed as well. The list refreshes through a [TransitionPane]; toggling a
/// plugin updates its row in place so that title and subtitle never change position.</p>
@NotNullByDefault
public final class PluginPage extends ScrollPane implements PageAware {

    /// Wraps the page content so refreshes can animate between the old and new list.
    private final TransitionPane contentPane = new TransitionPane();

    /// Signature of the currently displayed plugin list; unchanged lists are not re-rendered.
    private @Nullable String displayedSignature;

    /// Guards against re-entrant toggle updates while a toggle change is being applied.
    private boolean updatingToggle = false;

    /// True while a plugin-contributed page is shown instead of the plugin list.
    private boolean showingPluginPage = false;

    /// Cached page nodes so plugin pages keep their state across navigations.
    private final Map<PluginPageAction, Node> pageCache = new IdentityHashMap<>();

    public PluginPage() {
        this.setFitToWidth(true);
        this.setContent(contentPane);
        FXUtils.smoothScrolling(this);

        PluginManager.addChangeListener(this::onPluginsChanged);
        PluginMenuService.getPages().addListener(
                (ListChangeListener<? super PluginPageAction>) change -> FXUtils.runInFX(() -> {
                    if (!showingPluginPage)
                        refresh(true);
                }));

        refresh(false);
    }

    /// Rebuilds the page on the JavaFX thread whenever the set of loaded plugins changes.
    private void onPluginsChanged() {
        FXUtils.runInFX(() -> {
            if (!showingPluginPage)
                refresh(true);
        });
    }

    @Override
    public void onPageShown() {
        // 再次进入设置里的插件页时恢复初始样子：回到插件列表并回到顶部
        if (showingPluginPage) {
            showingPluginPage = false;
            displayedSignature = null;
            refresh(false);
        } else {
            refresh(true);
        }
        setVvalue(0);
    }

    /// Rebuilds the page content when the plugin list changed; skipped when nothing changed.
    private void refresh(boolean animated) {
        refresh(animated, ContainerAnimations.FADE);
    }

    private void refresh(boolean animated, ContainerAnimations animation) {
        String signature = buildSignature();
        if (Objects.equals(signature, displayedSignature))
            return;
        displayedSignature = signature;

        pageCache.keySet().retainAll(new HashSet<>(PluginMenuService.getPages()));

        VBox pane = buildContent();
        pane.setMaxWidth(Double.MAX_VALUE);
        contentPane.setContent(pane, animated ? animation : ContainerAnimations.NONE);
    }

    private static String buildSignature() {
        return PluginManager.getPlugins().stream()
                .map(plugin -> plugin.getId() + ':' + plugin.getState())
                .collect(Collectors.joining(","));
    }

    private VBox buildContent() {
        List<PluginHandle> plugins = PluginManager.getPlugins();
        VBox rootPane = new VBox(10);
        rootPane.setPadding(new Insets(10));

        ComponentList actionList = new ComponentList();
        {
            LineButton localImportButton = new LineButton();
            localImportButton.setTitle(i18n("plugin.install.local"));
            localImportButton.setOnAction(e -> onInstallLocal());

            LineButton onlineImportButton = new LineButton();
            onlineImportButton.setTitle(i18n("plugin.install.online"));
            onlineImportButton.setOnAction(e -> Controllers.dialog(new OnlinePluginInstallPane()));

            LineButton openFolderButton = new LineButton();
            openFolderButton.setTitle(i18n("plugin.open.folder"));
            openFolderButton.setOnAction(e -> FXUtils.openFolder(PluginManager.getPluginsDirectory()));

            LineButton reloadButton = new LineButton();
            reloadButton.setTitle(i18n("plugin.reload"));
            reloadButton.setOnAction(e -> PluginManager.reload());

            LineButton restartButton = new LineButton();
            restartButton.setTitle(i18n("plugin.restart"));
            restartButton.setOnAction(e -> Controllers.confirm(
                    i18n("plugin.restart.confirm"), i18n("plugin.restart"), PluginPage::restartLauncher, null));

            actionList.getContent().setAll(
                    localImportButton, onlineImportButton,
                    openFolderButton, reloadButton, restartButton);
        }

        ComponentList docList = new ComponentList();
        {
            LineButton formatDoc = new LineButton();
            formatDoc.setTitle(i18n("plugin.docs.format"));
            formatDoc.setSubtitle(i18n("plugin.docs.format.desc"));
            formatDoc.setOnAction(e -> openDocPage(i18n("plugin.docs.format"), "/assets/docs/plugin/plugin-format.md"));

            LineButton apiDoc = new LineButton();
            apiDoc.setTitle(i18n("plugin.docs.api"));
            apiDoc.setSubtitle(i18n("plugin.docs.api.desc"));
            apiDoc.setOnAction(e -> openDocPage(i18n("plugin.docs.api"), "/assets/docs/plugin/plugin-api.md"));

            docList.getContent().setAll(formatDoc, apiDoc);
        }

        Label hintLabel = new Label(i18n("plugin.hint"));
        hintLabel.setWrapText(true);
        hintLabel.setPadding(new Insets(4, 0, 4, 0));

        if (plugins.isEmpty()) {
            Label emptyLabel = new Label(i18n("plugin.empty"));
            emptyLabel.setWrapText(true);
            emptyLabel.setPadding(new Insets(10, 0, 10, 0));
            rootPane.getChildren().setAll(
                    ComponentList.createComponentListTitle(i18n("plugin.title")),
                    emptyLabel,
                    hintLabel,
                    actionList,
                    ComponentList.createComponentListTitle(i18n("plugin.docs.title")),
                    docList);
        } else {
            ComponentList pluginList = new ComponentList();
            for (PluginHandle plugin : plugins) {
                pluginList.getContent().add(createPluginNode(plugin));
            }

            rootPane.getChildren().setAll(
                    ComponentList.createComponentListTitle(i18n("plugin.title")),
                    pluginList,
                    hintLabel,
                    actionList,
                    ComponentList.createComponentListTitle(i18n("plugin.docs.title")),
                    docList);
        }

        if (!PluginMenuService.getPages().isEmpty()) {
            ComponentList pageList = new ComponentList();
            for (PluginPageAction page : PluginMenuService.getPages()) {
                LineButton pageButton = new LineButton();
                pageButton.setTitle(page.getTitle());
                String description = page.getDescription();
                pageButton.setSubtitle(description == null || description.isEmpty()
                        ? i18n("plugin.pages.open")
                        : description);
                pageButton.setOnAction(e -> openPluginPage(page));
                pageList.getContent().add(pageButton);
            }
            rootPane.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.pages.title")),
                    pageList);
        }
        return rootPane;
    }

    /// Switches the TransitionPane to a plugin-contributed page with a back header.
    /// The page node is cached so its state (logs, selections) survives navigation.
    private void openPluginPage(PluginPageAction page) {
        Node node;
        try {
            node = pageCache.computeIfAbsent(page, PluginPageAction::createPage);
        } catch (Throwable throwable) {
            LOG.warning("Failed to create plugin page of " + page.getTitle(), throwable);
            Controllers.dialog(String.valueOf(throwable), i18n("plugin.error.title"), MessageType.ERROR);
            return;
        }

        // a prominent raised button so leaving a plugin page is always obvious
        JFXButton backButton = FXUtils.newRaisedButton(i18n("plugin.pages.back"));
        backButton.setGraphic(SVG.ARROW_BACK.createIcon(backButton.textFillProperty()));
        backButton.setOnAction(e -> closePluginPage());
        HBox backBar = new HBox(backButton);
        backBar.setPadding(new Insets(0, 0, 2, 0));

        VBox wrapper = new VBox(10, backBar, node);
        wrapper.setPadding(new Insets(10));
        wrapper.setMaxWidth(Double.MAX_VALUE);

        showingPluginPage = true;
        contentPane.setContent(wrapper, ContainerAnimations.FORWARD);
        scrollToTop();
    }

    /// Opens one of the bundled plugin-development documents, rendered as Markdown.
    private void openDocPage(String title, String resource) {
        TextFlow flow = new TextFlow();
        flow.setStyle("-fx-line-spacing: 4; -fx-padding: 8;");
        try (InputStream input = PluginPage.class.getResourceAsStream(resource)) {
            if (input == null)
                throw new IOException("Bundled document missing: " + resource);
            MarkdownRenderer.update(flow, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Controllers.dialog(String.valueOf(e), i18n("plugin.error.title"), MessageType.ERROR);
            return;
        }

        // No inner scroll container: the document is one continuous block and the page itself
        // (PluginPage extends ScrollPane) is the single scroller from top to bottom.
        flow.setMaxWidth(Double.MAX_VALUE);

        JFXButton backButton = FXUtils.newRaisedButton(i18n("plugin.pages.back"));
        backButton.setGraphic(SVG.ARROW_BACK.createIcon(backButton.textFillProperty()));
        backButton.setOnAction(e -> closePluginPage());
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        HBox backBar = new HBox(10, backButton, titleLabel);
        backBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        backBar.setPadding(new Insets(0, 0, 2, 0));

        VBox wrapper = new VBox(10, backBar, flow);
        wrapper.setPadding(new Insets(10));

        showingPluginPage = true;
        contentPane.setContent(wrapper, ContainerAnimations.FORWARD);
        scrollToTop();
    }

    /// Sub-pages open at their own top: the outer scroll kept the depth the plugin list was
    /// scrolled to, which dropped the reader into the middle of a long document.
    private void scrollToTop() {
        setVvalue(0.0);
        // run once more after the new content's layout pass, so the clamped value sticks
        FXUtils.runInFX(() -> setVvalue(0.0));
    }

    /// Returns from a plugin page to the plugin list.
    private void closePluginPage() {
        showingPluginPage = false;
        displayedSignature = null; // the list view was replaced by the page node; force a rebuild
        refresh(true, ContainerAnimations.BACKWARD); // leaving slides back the way we came in
    }

    /// Wraps one plugin row together with its delete button.
    private Node createPluginNode(PluginHandle plugin) {
        Node content = createPluginContent(plugin);

        JFXButton deleteButton = FXUtils.newToggleButton4(SVG.DELETE_FOREVER);
        deleteButton.setOnAction(e -> onDelete(plugin));
        FXUtils.installFastTooltip(deleteButton, i18n("plugin.delete"));

        HBox row = new HBox(content, deleteButton);
        HBox.setHgrow(content, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(0, 8, 0, 0));
        ComponentList.setNoPadding(row);
        return row;
    }

    private Node createPluginContent(PluginHandle plugin) {
        if (plugin.getState() == PluginHandle.State.ENABLED
                || plugin.getState() == PluginHandle.State.DISABLED) {
            LineToggleButton toggle = new LineToggleButton();
            toggle.setTitle(plugin.getDescription().getName());
            toggle.setSubtitle(buildSubtitle(plugin)); // constant text: never moves when toggling
            toggle.setSelected(plugin.getState() == PluginHandle.State.ENABLED);
            updateStateBadge(toggle, plugin.getState() == PluginHandle.State.ENABLED);

            toggle.selectedProperty().addListener((observable, wasSelected, selected) -> {
                if (updatingToggle)
                    return;
                updatingToggle = true;
                try {
                    PluginManager.setEnabled(plugin.getId(), selected);

                    PluginHandle current = PluginManager.getPlugin(plugin.getId());
                    PluginHandle.State state = current != null ? current.getState() : PluginHandle.State.FAILED;
                    if (state == PluginHandle.State.FAILED || state == PluginHandle.State.INCOMPATIBLE) {
                        // enabling failed: rebuild so the row is replaced by the failure entry
                        displayedSignature = null;
                        refresh(true);
                    } else {
                        updateStateBadge(toggle, state == PluginHandle.State.ENABLED);
                        displayedSignature = buildSignature(); // keep the in-place update as rendered
                    }
                } finally {
                    updatingToggle = false;
                }
            });
            return toggle;
        }

        LineButton failedButton = new LineButton();
        failedButton.setTitle(plugin.getId());
        failedButton.setSubtitle(plugin.getState() == PluginHandle.State.INCOMPATIBLE
                ? i18n("plugin.state.incompatible") + (plugin.getError() != null ? " · " + plugin.getError() : "")
                : i18n("plugin.state.failed") + (plugin.getError() != null ? " · " + plugin.getError() : ""));
        failedButton.setOnAction(e -> Controllers.dialog(
                plugin.getError() != null ? plugin.getError() : i18n("plugin.state.failed"),
                i18n("plugin.error.title")));
        return failedButton;
    }

    /// Asks for confirmation and then deletes the plugin jar and its row.
    private void onDelete(PluginHandle plugin) {
        String name = plugin.getDescription() != null ? plugin.getDescription().getName() : plugin.getId();

        Controllers.confirm(i18n("plugin.delete.confirm", name), i18n("plugin.delete"), () -> {
            Task.runAsync(() -> PluginManager.delete(plugin))
                    .whenComplete(Schedulers.javafx(), exception -> {
                        if (exception == null) {
                            Controllers.showToast(i18n("plugin.delete.success", name));
                        } else {
                            LOG.warning("Failed to delete plugin " + plugin.getId(), exception);
                            String message = exception.getMessage();
                            Controllers.dialog(
                                    message != null && !message.isBlank()
                                            ? i18n("plugin.delete.failed") + "\n" + message
                                            : i18n("plugin.delete.failed"),
                                    i18n("plugin.delete.failed"), MessageType.ERROR);
                        }
                    })
                    .start();
        }, null);
    }

    /// Opens a file chooser and installs the selected plugin jars.
    private void onInstallLocal() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("plugin.install.local"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("plugin.install.filter"), "*.jar"));

        List<File> files = chooser.showOpenMultipleDialog(Controllers.getStage());
        if (files == null || files.isEmpty())
            return;

        PluginInstaller.installWithToast(files.stream().map(File::toPath).toList());
    }

    /// Restarts the launcher, mirroring the restart flow used after switching launcher options.
    private static void restartLauncher() {
        Controllers.onApplicationStop();

        try {
            FileSaver.waitForAllSaves();
        } catch (InterruptedException ignored) {
            // ignore
        }

        try {
            Restarter.restartSelf();
        } catch (IOException e) {
            LOG.warning("Failed to restart self", e);
        }

        Platform.exit();
    }

    /// Shows a small disabled badge right of the row title; the title and subtitle stay put.
    private static void updateStateBadge(LineToggleButton toggle, boolean enabled) {
        if (enabled) {
            toggle.setTitleTrailing(null);
            return;
        }

        Label badge = new Label(i18n("plugin.state.disabled"));
        badge.setStyle("-fx-font-size: 11px;");
        badge.setMouseTransparent(true);
        toggle.setTitleTrailing(badge);

        badge.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(140), badge);
        fade.setFromValue(0);
        fade.setToValue(0.65);
        fade.play();
    }

    /// Builds the version/author/access/description subtitle; independent of the enable state.
    private static String buildSubtitle(PluginHandle plugin) {
        var description = plugin.getDescription();
        StringBuilder builder = new StringBuilder();

        if (description.getVersion() != null)
            builder.append('v').append(description.getVersion());
        if (description.getAuthor() != null) {
            if (builder.length() > 0)
                builder.append(" · ");
            builder.append(description.getAuthor());
        }
        if (builder.length() > 0)
            builder.append(" · ");
        builder.append(i18n("plugin.access." + description.getAccess().getId()));
        if (description.getDescription() != null) {
            builder.append(" · ");
            builder.append(description.getDescription());
        }
        return builder.toString();
    }
}

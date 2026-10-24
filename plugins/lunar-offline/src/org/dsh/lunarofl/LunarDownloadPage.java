/*
 * Hello Minecraft! Launcher plugin: lunar-offline
 * Copyright (C) 2026 dsh
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
package org.dsh.lunarofl;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXSpinner;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.setting.VersionIconType;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.PageCloseEvent;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.ui.FXUtils.ignoreEvent;
import static org.jackhuang.hmcl.ui.FXUtils.newToggleButton4;
import static org.jackhuang.hmcl.ui.FXUtils.onChangeAndOperate;
import static org.jackhuang.hmcl.ui.FXUtils.prepareNode;
import static org.jackhuang.hmcl.ui.FXUtils.stringConverter;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The **Lunar 客户端** tab in the launcher's download page.
///
/// <p>Deliberately a structural copy of the launcher's own version download page
/// (`VersionsPage` + its skin): the same search bar with a category filter, the same list view
/// with `TwoLineListItem` rows and `RippleContainer` backgrounds, and the same spinner → list /
/// empty / failed transitions animating with [ContainerAnimations#FADE]. Only the data source
/// differs - Lunar's own release API instead of Mojang's version manifest.</p>
///
/// <p>Clicking a release navigates the whole window to a full-screen install page (exactly how
/// the launcher's own download detail pages work, via the decorator navigator), with a small
/// back arrow in the top-right corner that returns to this version list.</p>
@NotNullByDefault
public final class LunarDownloadPage extends StackPane {

    private final Path dataDirectory;
    private final LunarApi api;

    private final ObservableList<LunarRemoteVersion> versions = FXCollections.observableArrayList();
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.LOADING);

    private final TransitionPane transitionPane = new TransitionPane();
    private final JFXTextField searchField = new JFXTextField();
    private final JFXComboBox<CategoryFilter> categoryField = new JFXComboBox<>();
    private final JFXListView<LunarRemoteVersion> list = new JFXListView<>();
    private final Label statusLabel = new Label(" ");

    private final JFXButton installButton = FXUtils.newRaisedButton("下载并安装");

    private final org.jackhuang.hmcl.ui.construct.RadioChoiceList<LunarApi.Module> moduleList =
            new org.jackhuang.hmcl.ui.construct.RadioChoiceList<>();

    private @Nullable LunarRemoteVersion selected;

    /// Guards against a stale background refresh overwriting a newer one.
    private volatile int refreshToken;

    /// What the version list can be filtered by, mirroring the launcher's own version types.
    private enum CategoryFilter {
        ALL,
        RELEASE,
        PARENT_1_8,
        PARENT_MODERN
    }

    private enum Status {
        LOADING,
        FAILED,
        SUCCESS,
    }

    /// The first step, built once and reused.
    ///
    /// <p>It must not be rebuilt per navigation: building it registers listeners on the shared
    /// search field and category combo, so rebuilding accumulated one more listener each time the
    /// user went back to the list - a single keystroke then triggered several refreshes.</p>
    private @Nullable Region cachedListStep;

    public LunarDownloadPage(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.api = new LunarApi(dataDirectory);

        setPadding(new Insets(0));
        // The page is hosted inside the launcher's download tab, which sizes its content to fill
        // the window; a page that only reported its preferred size would sit squeezed in a corner.
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        getChildren().setAll(buildListStep());

        refresh();
    }

    // ------------------------------------------------------------ step 1: list ---

    /// Builds the first step: search bar plus the version list, laid out exactly like the
    /// launcher's version download page.
    private Region buildListStep() {
        BorderPane root = new BorderPane();

        // ---- search bar (card, same as VersionsPage) ----
        GridPane searchPane = new GridPane();
        root.setTop(searchPane);
        searchPane.getStyleClass().add("card");
        BorderPane.setMargin(searchPane, new Insets(10, 10, 0, 10));

        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints column1 = new ColumnConstraints();
        column1.setHgrow(Priority.ALWAYS);
        ColumnConstraints column2 = new ColumnConstraints();
        column2.setMaxWidth(150);
        ColumnConstraints column3 = new ColumnConstraints();

        searchPane.getColumnConstraints().setAll(nameColumn, column1, nameColumn, column2, column3);
        searchPane.setHgap(16);
        searchPane.setVgap(10);

        searchField.setPromptText(i18n("version.search.prompt"));
        searchField.textProperty().addListener(o -> updateList());

        // Reuse the launcher's own category labels so the column reads exactly like the page next
        // to it ("版本类型 / 全部"), instead of inventing parallel wording.
        categoryField.getItems().setAll(
                CategoryFilter.ALL,
                CategoryFilter.RELEASE,
                CategoryFilter.PARENT_1_8,
                CategoryFilter.PARENT_MODERN);
        categoryField.getSelectionModel().select(CategoryFilter.ALL);
        categoryField.setConverter(stringConverter(this::categoryName));
        categoryField.getSelectionModel().selectedItemProperty().addListener(o -> updateList());

        JFXButton refreshButton = FXUtils.newRaisedButton(i18n("button.refresh"));
        refreshButton.setOnAction(e -> refresh());

        searchPane.addRow(0,
                new Label(i18n("version.search")), searchField,
                new Label(i18n("version.game.type")), categoryField,
                refreshButton);

        // ---- list / loading / failed / empty, animated through a TransitionPane ----
        SpinnerPane spinnerPane = new SpinnerPane();
        root.setCenter(spinnerPane);

        spinnerPane.setContent(transitionPane);

        JFXSpinner spinner = new JFXSpinner();

        StackPane centerWrapper = new StackPane();
        centerWrapper.setStyle("-fx-padding: 10;");
        {
            ComponentList centrePane = new ComponentList();
            centrePane.getStyleClass().add("no-padding");
            {
                list.getStyleClass().add("jfx-list-view-float");

                LunarVersionCell dummyCell = new LunarVersionCell(this);
                dummyCell.twoLineListItem.setTitle("Dummy");
                dummyCell.twoLineListItem.setSubtitle("Dummy");
                dummyCell.imageView.setImage(VersionIconType.GRASS.getIcon());
                prepareNode(dummyCell.pane);
                list.setFixedCellSize(dummyCell.pane.prefHeight(-1));

                VBox.setVgrow(list, Priority.ALWAYS);
                versions.addListener((InvalidationListener) o -> updateList());
                list.setCellFactory(listView -> new LunarVersionCell(this));
                ComponentList.setVgrow(list, Priority.ALWAYS);

                // ListViewBehavior would consume ESC, preventing the page from handling it
                ignoreEvent(list, KeyEvent.KEY_PRESSED, e -> e.getCode() == KeyCode.ESCAPE);

                centrePane.getContent().setAll(list);
            }
            centerWrapper.getChildren().setAll(centrePane);
        }

        StackPane failedPane = new StackPane();
        failedPane.getStyleClass().add("notice-pane");
        {
            Label label = new Label(i18n("download.failed.refresh"));
            FXUtils.onClicked(label, this::refresh);
            failedPane.getChildren().setAll(label);
        }

        StackPane emptyPane = new StackPane();
        emptyPane.getStyleClass().add("notice-pane");
        {
            Label label = new Label(i18n("download.failed.empty"));
            FXUtils.onClicked(label, this::refresh);
            emptyPane.getChildren().setAll(label);
        }

        onChangeAndOperate(status, value -> {
            if (value == Status.LOADING)
                transitionPane.setContent(spinner, ContainerAnimations.FADE);
            else if (value == Status.SUCCESS)
                if (versions.isEmpty())
                    transitionPane.setContent(emptyPane, ContainerAnimations.FADE);
                else
                    transitionPane.setContent(centerWrapper, ContainerAnimations.FADE);
            else
                transitionPane.setContent(failedPane, ContainerAnimations.FADE);
        });

        return root;
    }

    // ------------------------------------------------------------- data loading ---

    /// Fetches the Lunar release list, driving the same LOADING/SUCCESS/FAILED transitions the
    /// launcher's version page uses.
    private void refresh() {
        final int token = ++refreshToken;
        status.set(Status.LOADING);
        statusLabel.setText("正在获取 Lunar 版本列表…");

        new Thread(() -> {
            try {
                List<LunarApi.SubVersion> fetched = api.versions();
                List<LunarRemoteVersion> mapped = new CopyOnWriteArrayList<>();
                for (LunarApi.SubVersion version : fetched)
                    mapped.add(new LunarRemoteVersion(version));
                javafx.application.Platform.runLater(() -> {
                    if (token != refreshToken)
                        return;
                    versions.setAll(mapped);
                    // default to the release the API marks as default, like the launcher preselects
                    // the newest game version on its own download page
                    for (LunarRemoteVersion version : mapped)
                        if (version.isDefaultChoice()) {
                            list.getSelectionModel().select(version);
                            break;
                        }
                    status.set(Status.SUCCESS);
                    statusLabel.setText("共 " + mapped.size() + " 个可下载版本");
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    if (token != refreshToken)
                        return;
                    status.set(Status.FAILED);
                    statusLabel.setText("获取失败：" + message(e));
                });
            }
        }, "lunar-versions").start();
    }

    /// Applies the search text and the category filter, exactly like the launcher's page does.
    private void updateList() {
        Stream<LunarRemoteVersion> stream = versions.stream();

        CategoryFilter filter = categoryField.getSelectionModel().getSelectedItem();
        if (filter != null) {
            stream = stream.filter(it -> switch (filter) {
                case RELEASE -> true;
                case PARENT_1_8 -> "1.8".equals(it.getGameVersion());
                case PARENT_MODERN -> !"1.8".equals(it.getGameVersion());
                // case ALL,
                default -> true;
            });
        }

        String query = searchField.getText();
        if (query != null && !query.isBlank()) {
            if (query.startsWith("regex:")) {
                try {
                    Pattern pattern = Pattern.compile(query.substring("regex:".length()));
                    stream = stream.filter(it -> pattern.matcher(it.getSelfVersion()).find());
                } catch (Throwable ignored) {
                    // an illegal regex simply matches nothing, as on the launcher's own page
                }
            } else {
                String lower = query.toLowerCase(Locale.ROOT);
                stream = stream.filter(it -> it.getSelfVersion().toLowerCase(Locale.ROOT).contains(lower));
            }
        }

        list.getItems().setAll(stream.collect(Collectors.toList()));
        list.scrollTo(0);
    }

    private String categoryName(CategoryFilter filter) {
        return switch (filter) {
            case ALL -> i18n("version.game.all");
            case RELEASE -> i18n("version.game.release");
            // Lunar's own split: the 1.8 line (legacy LWJGL2 client) versus everything newer
            case PARENT_1_8 -> "1.8";
            case PARENT_MODERN -> "1.9+";
        };
    }

    // -------------------------------------------------------- navigation to detail ---

    /// Opens the full-screen install page for the chosen release.
    ///
    /// <p>The detail page is pushed onto the launcher's decorator navigator, the same way the
    /// launcher's own download detail pages are: it fills the whole window, the sidebar gives way,
    /// and the decorator's own back arrow (top-left) returns here. A small arrow button in the
    /// page's top-right corner offers the same trip back.</p>
    private void chooseRelease(LunarRemoteVersion version) {
        selected = version;
        Controllers.navigate(new LunarInstallPage(version));
    }

    // ------------------------------------------------------- full-screen install page ---

    /// The install detail page for one Lunar release, shown full-screen through the decorator
    /// navigator - like the launcher's game download detail pages.
    ///
    /// <p>Module choices render as `InstallerItem`-style cards, arranged in a flow pane exactly
    /// like the launcher's installer page; the only action button is 「下载并安装」. When the
    /// matching vanilla game version is missing, clicking it first asks the user to download the
    /// vanilla version. The top-right arrow closes this page, returning to the Lunar version
    /// list.</p>
    private final class LunarInstallPage extends StackPane implements org.jackhuang.hmcl.ui.decorator.DecoratorPage {

        private final javafx.beans.property.ReadOnlyObjectWrapper<org.jackhuang.hmcl.ui.decorator.DecoratorPage.State> state;
        private final LunarRemoteVersion version;
        private final Label targetLabel = new Label(" ");
        private final Label baseLabel = new Label(" ");
        private final Label javaLabel = new Label(" ");
        private final Label statusLabel = new Label(" ");
        private final FlowPane modulePane = new FlowPane(16, 16);

        LunarInstallPage(LunarRemoteVersion version) {
            this.version = version;
            this.state = new javafx.beans.property.ReadOnlyObjectWrapper<>(
                    org.jackhuang.hmcl.ui.decorator.DecoratorPage.State.fromTitle("Lunar " + version.getSelfVersion()));
            getStyleClass().add("gray-background");
            setMaxWidth(Double.MAX_VALUE);
            setMaxHeight(Double.MAX_VALUE);
            setPadding(new Insets(10));

            // ---- header: what is being installed, where ----
            HBox header = new HBox(8);
            header.getStyleClass().add("card-non-transparent");
            header.setStyle("-fx-padding: 20 8 20 16");
            header.setAlignment(Pos.CENTER_LEFT);

            Label titleLabel = new Label("安装到");
            titleLabel.setStyle("-fx-font-weight: bold;");
            targetLabel.setStyle("-fx-font-weight: bold;");
            header.getChildren().setAll(titleLabel, targetLabel);

            // ---- modules: launcher installer style cards in a flow pane ----
            moduleList.setChoices(version.getLunar().modules().stream()
                    .map(module -> new org.jackhuang.hmcl.ui.construct.RadioChoiceList.Choice<>(
                            module.name(), module))
                    .collect(Collectors.toList()));
            moduleList.setSelectedValue(version.getLunar().defaultModule());

            buildModuleCards();

            javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(modulePane);
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(true);
            BorderPane.setMargin(scroll, new Insets(16, 0, 16, 0));

            // ---- bottom: status lines + the single action button ----
            baseLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
            baseLabel.setWrapText(true);
            javaLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
            javaLabel.setWrapText(true);
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
            statusLabel.setWrapText(true);

            installButton.setPrefWidth(120);
            installButton.setPrefHeight(40);
            installButton.setOnAction(e -> startInstall());

            HBox actions = new HBox(installButton);
            actions.setAlignment(Pos.CENTER_RIGHT);

            // ---- 说明：安装会额外生成一个「基座版本」----
            // 那个 <版本>-lunar-base 现在是隐藏的，但值得说清楚它是什么、占不占地方，
            // 免得用户以后在别处（例如其它插件的版本选择框）见到它时一头雾水。
            Label baseNotice = new Label(
                    "ⓘ 安装时会额外生成一个 Lunar 基座版本（" + baseGameVersion() + "-lunar-base），"
                            + "用于让客户端继承原版依赖。它只是一个几 KB 的配置文件，"
                            + "不会额外占用磁盘空间，也不会显示在版本列表中。");
            baseNotice.setWrapText(true);
            baseNotice.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color; -fx-opacity: 0.85;");
            baseNotice.setPadding(new Insets(0, 8, 2, 0));

            // 下载进度交给启动器原生弹窗显示（见 performLunarInstall），这里只留状态行与动作按钮
            VBox bottom = new VBox(6, baseNotice, baseLabel, javaLabel, statusLabel, actions);
            bottom.setAlignment(Pos.CENTER_RIGHT);

            BorderPane root = new BorderPane();
            root.setTop(header);
            root.setCenter(scroll);
            root.setBottom(bottom);

            getChildren().setAll(root);

            refreshTargetState();
        }

        /// Rebuilds the module cards, one per Lunar module, looking like the launcher's
        /// installer cards and preselecting the module the official API marks as default.
        private void buildModuleCards() {
            modulePane.getChildren().clear();
            LunarApi.Module current = moduleList.getSelectedValue();
            for (LunarApi.Module module : version.getLunar().modules()) {
                modulePane.getChildren().add(new ModuleCard(module, module.equals(current)));
            }
        }

        /// One module card: icon, module name, selection state, and the check / arrow glyph on
        /// the right, modelled after the launcher's own `InstallerItem` cards.
        private final class ModuleCard extends StackPane {

            private final LunarApi.Module module;
            private final VBox body;
            private final StackPane wrapper;

            ModuleCard(LunarApi.Module module, boolean selected) {
                this.module = module;
                this.wrapper = new StackPane();
                this.body = new VBox(8);
                body.getStyleClass().add("installer-item");
                body.setAlignment(Pos.CENTER);
                body.setPadding(new Insets(8, 0, 8, 0));

                wrapper.getStyleClass().add("installer-item-wrapper");
                wrapper.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("card"), true);

                VersionIconType icon = iconFor(module.id());
                if (icon != null) {
                    ImageView imageView = new ImageView(icon.getIcon());
                    imageView.setFitWidth(32);
                    imageView.setFitHeight(32);
                    imageView.setPreserveRatio(true);
                    imageView.setSmooth(true);
                    imageView.setMouseTransparent(true);
                    VBox.setMargin(imageView, new Insets(8, 0, 8, 0));
                    body.getChildren().add(imageView);
                }

                Label nameLabel = new Label(module.name());
                nameLabel.getStyleClass().add("installer-item-name");
                nameLabel.setMouseTransparent(true);
                nameLabel.setWrapText(true);
                nameLabel.setAlignment(Pos.CENTER);
                body.getChildren().add(nameLabel);

                // selected: the launcher's check glyph; unselected: the go arrow
                Node glyph = selected
                        ? SVG.CHECK.createIcon(24)
                        : SVG.ARROW_FORWARD.createIcon();
                HBox buttons = new HBox(glyph);
                buttons.setAlignment(Pos.CENTER);
                body.getChildren().add(buttons);

                RipplerContainer container = new RipplerContainer(body);
                container.setPosition(com.jfoenix.controls.JFXRippler.RipplerPos.BACK);
                wrapper.getChildren().setAll(container);

                if (selected)
                    highlight(wrapper);

                FXUtils.onClicked(container, () -> {
                    moduleList.setSelectedValue(module);
                    refreshTargetState();
                    buildModuleCards();
                });

                getChildren().setAll(wrapper);
                FXUtils.setLimitHeight(this, 120);
            }

            private void highlight(StackPane target) {
                target.setStyle("-fx-border-color: -monet-primary; -fx-border-width: 2; -fx-background-radius: 4;");
            }
        }

        private VersionIconType iconFor(String moduleId) {
            String id = moduleId == null ? "" : moduleId.toLowerCase(Locale.ROOT);
            if (id.contains("fabric"))
                return VersionIconType.FABRIC;
            if (id.contains("forge"))
                return id.contains("neo") ? VersionIconType.NEO_FORGE : VersionIconType.FORGE;
            if (id.contains("quilt"))
                return VersionIconType.QUILT;
            if (id.contains("optifine"))
                return VersionIconType.OPTIFINE;
            return null;
        }

        /// The Minecraft version this Lunar build actually runs on.
        ///
        /// <p>The metadata's `parentId` is only the Minecraft *line* (`1.8`, `1.21`, `26`), while
        /// the build id - the same string the list shows - is the exact Minecraft version
        /// (`1.8.9`, `1.21.11`, `26.2`). The installer builds its inheritance chain from the build
        /// id (it is passed to the launch API as `version` and stored as `dsh.lunar.gameVersion`),
        /// so that is the version whose vanilla files have to exist.</p>
        private String baseGameVersion() {
            return version.getSelfVersion();
        }

        /// Every id that can serve as this release's vanilla base, most exact first.
        private java.util.List<String> baseVersionCandidates() {
            java.util.List<String> candidates = new java.util.ArrayList<>(2);
            if (!baseGameVersion().isBlank())
                candidates.add(baseGameVersion());
            String line = version.getGameVersion();
            if (line != null && !line.isBlank() && !candidates.contains(line))
                candidates.add(line);
            return candidates;
        }

        /// The installed vanilla base version for this release, or `null` when there is none.
        ///
        /// <p>`hasVersion` only consults the launcher's in-memory version map, which is stale
        /// whenever a version was installed outside the launcher's own flow (another launcher, a
        /// manual copy, a modpack import) - the release would then look missing although its files
        /// are right there. So this also refreshes the repository once and finally accepts a
        /// version json on disk: that json is exactly what builds the Lunar inheritance chain, and
        /// [LunarVersionInstaller] repairs or replaces it when it is not a vanilla one.</p>
        private @Nullable String findBaseVersion(org.jackhuang.hmcl.game.HMCLGameRepository repository) {
            java.util.List<String> candidates = baseVersionCandidates();

            for (String id : candidates)
                if (repository.hasVersion(id))
                    return id;

            repository.refreshVersions();
            for (String id : candidates)
                if (repository.hasVersion(id))
                    return id;

            for (String id : candidates)
                if (java.nio.file.Files.isRegularFile(repository.getVersionJson(id)))
                    return id;

            return null;
        }

        /// Updates the header line and the state of the action button.
        private void refreshTargetState() {
            org.jackhuang.hmcl.game.HMCLGameRepository repository = selectedRepository();
            if (repository == null) {
                targetLabel.setText("没有选中游戏目录");
                baseLabel.setText(" ");
                javaLabel.setText(" ");
                installButton.setDisable(true);
                return;
            }

            String versionId = versionId();
            targetLabel.setText(version.getSelfVersion() + " → " + versionId
                    + "（" + repository.getBaseDirectory() + "）");

            String baseVersion = findBaseVersion(repository);
            baseLabel.setText(baseVersion != null
                    ? "✓ 已安装基础版本 " + baseVersion
                    : "⚠ 缺少基础版本 " + baseGameVersion() + "，Lunar 基于原版运行");

            boolean installed = repository.hasVersion(versionId);
            installButton.setDisable(false);
            statusLabel.setText(installed ? "已安装版本「" + versionId + "」" : " ");
        }

        /// The launcher version id this selection installs as.
        private String versionId() {
            LunarApi.Module module = moduleList.getSelectedValue();
            if (module == null || module.isDefault())
                return "Lunar-" + version.getSelfVersion();
            return "Lunar-" + version.getSelfVersion() + "-" + module.id();
        }

        // ---------------------------------------------------------- installing ---

        /// Checks the vanilla base version before downloading: when missing, a dialog tells the
        /// user to download the vanilla game first and offers to open the game download page, or
        /// to download both the vanilla game and the Lunar client in one click.
        private void startInstall() {
            org.jackhuang.hmcl.game.HMCLGameRepository repository = selectedRepository();
            if (repository == null) {
                LunarOfflinePlugin.error("下载 Lunar", "没有选中游戏目录，无法安装。");
                return;
            }
            LunarApi.Module module = moduleList.getSelectedValue();
            if (module == null) {
                LunarOfflinePlugin.error("下载 Lunar", "请先选择一个模块。");
                return;
            }

            if (findBaseVersion(repository) == null) {
                Controllers.dialog(new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                        "缺少基础版本 " + baseGameVersion()
                                + "，Lunar 客户端基于原版游戏运行。请先下载原版 " + baseGameVersion()
                                + "，再回来安装 Lunar。",
                        "无法安装 Lunar",
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING)
                        .addAction("一键全部下载", () -> downloadVanillaThenLunar(repository, module))
                        .addAction("前往下载原版", () -> {
                            Controllers.getDownloadPage().showGameDownloads();
                            Controllers.navigate(Controllers.getDownloadPage());
                        })
                        .ok(null)
                        .build());
                return;
            }

            performLunarInstall(repository, module);
        }

        /// Downloads the vanilla game through the launcher's own install pipeline and, once it
        /// succeeds, continues with the Lunar client automatically.
        ///
        /// <p>The vanilla part reuses the launcher's `GameBuilder` (the same task the game
        /// download page runs), so it shows the launcher's own progress dialog with assets and
        /// libraries; the Lunar part reports progress in this page's status line, exactly like a
        /// plain 「下载并安装」.</p>
        private void downloadVanillaThenLunar(org.jackhuang.hmcl.game.HMCLGameRepository repository,
                                              LunarApi.Module module) {
            String gameVersion = baseGameVersion();
            installButton.setDisable(true);
            statusLabel.setText("正在获取原版版本清单…");

            org.jackhuang.hmcl.download.DefaultDependencyManager dependencyManager = repository.getDependency();
            org.jackhuang.hmcl.download.VersionList<?> gameList = dependencyManager.getVersionList("game");

            // The launcher's default provider is a DownloadProviderWrapper whose version list only
            // implements refreshAsync(gameVersion); the no-argument refreshAsync() throws
            // UnsupportedOperationException. Always refresh the one game version we need.
            org.jackhuang.hmcl.task.Task<?> prepareTask = gameList.refreshAsync(gameVersion)
                    .whenComplete(org.jackhuang.hmcl.task.Schedulers.javafx(), exception -> {
                        if (exception != null) {
                            statusLabel.setText(" ");
                            installButton.setDisable(false);
                            LunarOfflinePlugin.error("下载原版 " + gameVersion + " 失败", message(exception));
                            return;
                        }
                        buildVanilla(repository, dependencyManager, gameVersion, module);
                    });
            prepareTask.start();
        }

        /// Runs the launcher's vanilla install task for `gameVersion` with the launcher's own
        /// progress dialog, then hands over to the Lunar download.
        private void buildVanilla(org.jackhuang.hmcl.game.HMCLGameRepository repository,
                                  org.jackhuang.hmcl.download.DefaultDependencyManager dependencyManager,
                                  String gameVersion, LunarApi.Module module) {
            // Install under the exact id the Lunar installer looks up, so its inheritance chain
            // finds the game right away. A leftover folder without a usable json is no obstacle:
            // the builder simply writes a correct json into it. Only an id the launcher already
            // lists (a race with another install) has to move aside, reusing the `<id>-vanilla`
            // name LunarVersionInstaller itself falls back to.
            String vanillaId = repository.versionIdConflicts(gameVersion)
                    ? gameVersion + "-vanilla"
                    : gameVersion;

            statusLabel.setText("正在下载原版 " + gameVersion + " …");

            org.jackhuang.hmcl.download.GameBuilder builder = dependencyManager.gameBuilder();
            builder.name(vanillaId);
            builder.gameVersion(gameVersion);

            final String installedId = vanillaId;
            org.jackhuang.hmcl.task.Task<?> vanillaTask = builder.buildAsync()
                    .whenComplete(org.jackhuang.hmcl.task.Schedulers.javafx(), installException -> {
                        if (installException == null) {
                            repository.refreshVersions();
                            LunarOfflinePlugin.log("原版 " + installedId + " 安装完成，继续安装 Lunar");
                            performLunarInstall(repository, module);
                        } else {
                            statusLabel.setText(" ");
                            installButton.setDisable(false);
                            LunarOfflinePlugin.error("下载原版 " + gameVersion + " 失败",
                                    message(installException));
                        }
                    });
            Controllers.taskDialog(vanillaTask, "下载原版 " + gameVersion,
                    org.jackhuang.hmcl.util.TaskCancellationAction.NORMAL);
        }

        /// The actual Lunar client download + install, shared by 「下载并安装」 and the
        /// one-click flow.
        ///
        /// <p>Runs as a real launcher task, so the user gets the launcher's own download dialog
        /// (title, overall progress bar, per-step rows, working cancel button) instead of the
        /// plugin's standalone progress bar.</p>
        private void performLunarInstall(org.jackhuang.hmcl.game.HMCLGameRepository repository,
                                         LunarApi.Module module) {
            Path installation = installation(repository, module);
            String versionId = versionId();
            String gameVersion = version.getSelfVersion();
            String moduleId = module.id();

            installButton.setDisable(true);
            statusLabel.setText("正在准备下载…");

            LunarInstallTask task = new LunarInstallTask(
                    repository.getBaseDirectory(), versionId, api, gameVersion, moduleId, installation,
                    step -> javafx.application.Platform.runLater(() -> statusLabel.setText(step)));

            org.jackhuang.hmcl.task.TaskExecutor executor = task.executor();
            // 任务结束（成功/失败/取消）后回到页面：刷新按钮状态，成功时提示并刷新版本列表
            executor.addTaskListener(new org.jackhuang.hmcl.task.TaskListener() {
                @Override
                public void onStop(boolean success, org.jackhuang.hmcl.task.TaskExecutor finished) {
                    javafx.application.Platform.runLater(() -> {
                        installButton.setDisable(false);
                        if (success) {
                            LunarVersionInstaller.Result result = task.result();
                            repository.refreshVersions();
                            statusLabel.setText(result != null
                                    ? "已安装版本「" + result.versionId() + "」（" + result.libraries() + " 个库）"
                                    : "安装完成");
                            refreshTargetState();
                            // 完成提示：正文就一句「Lunar <版本号> 下载完成！」，标题用「下载完成」
                            LunarOfflinePlugin.info("下载完成",
                                    "Lunar " + version.getSelfVersion() + " 下载完成！");
                        } else if (finished.isCancelled()) {
                            statusLabel.setText("已取消下载");
                        } else {
                            Exception failure = finished.getException();
                            String reason = failure == null ? "未知错误" : message(failure);
                            statusLabel.setText("下载失败：" + reason);
                            LunarOfflinePlugin.error("下载 Lunar 失败", reason);
                        }
                    });
                }
            });

            // 启动器原生下载弹窗：与原版游戏下载、Mod 下载用的是同一个窗口。
            // 取消动作里真正中断下载，而不是只关掉窗口。
            Controllers.taskDialog(executor, "下载 Lunar 客户端 " + gameVersion,
                    new org.jackhuang.hmcl.util.TaskCancellationAction(pane -> {
                        task.cancel();
                        executor.cancel();
                    }));
            executor.start();
        }

        private Path installation(org.jackhuang.hmcl.game.HMCLGameRepository repository,
                                  LunarApi.Module module) {
            String folder = version.getSelfVersion()
                    + (module.isDefault() ? "" : "-" + module.id());
            return repository.getBaseDirectory().resolve("lunar-client").resolve(folder);
        }

        @Override
        public javafx.beans.property.ReadOnlyObjectProperty<org.jackhuang.hmcl.ui.decorator.DecoratorPage.State> stateProperty() {
            return state.getReadOnlyProperty();
        }
    }

    // ------------------------------------------------------------------ helpers ---

    /// The selected game directory, or `null` when none is configured.
    private static @Nullable org.jackhuang.hmcl.game.HMCLGameRepository selectedRepository() {
        try {
            return org.jackhuang.hmcl.setting.GameDirectoryManager.getSelectedRepository();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    /// One row of the version list.
    ///
    /// <p>Deliberately the launcher's own cell shape: a 32px icon, a `TwoLineListItem` that grows,
    /// and a single action button - so the Lunar list looks and behaves like the version list
    /// next to it.</p>
    private static final class LunarVersionCell extends ListCell<LunarRemoteVersion> {
        private final LunarDownloadPage page;

        private final TwoLineListItem twoLineListItem = new TwoLineListItem();
        private final ImageView imageView = new ImageView();
        private final StackPane pane = new StackPane();

        LunarVersionCell(LunarDownloadPage page) {
            this.page = page;

            imageView.setMouseTransparent(true);
            // The icon slot is a fixed 32px column, like the launcher's own version list. Without
            // an explicit limit the source image (up to 128px) overflows the row.
            imageView.setFitWidth(32);
            imageView.setFitHeight(32);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);

            HBox hbox = new HBox(16);
            HBox.setHgrow(twoLineListItem, Priority.ALWAYS);
            twoLineListItem.setAlignment(Pos.CENTER);
            hbox.setAlignment(Pos.CENTER);

            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER);
            {
                JFXButton actionButton = newToggleButton4(SVG.ARROW_FORWARD);
                actionButton.setOnAction(e -> onAction());
                actions.getChildren().add(actionButton);
            }

            hbox.getChildren().setAll(imageView, twoLineListItem, actions);

            pane.getStyleClass().add("md-list-cell");
            StackPane.setMargin(hbox, new Insets(10, 16, 10, 16));
            pane.getChildren().setAll(new RipplerContainer(hbox));

            FXUtils.onClicked(this, this::onAction);
        }

        private void onAction() {
            LunarRemoteVersion item = getItem();
            if (item != null)
                page.chooseRelease(item);
        }

        @Override
        public void updateItem(LunarRemoteVersion item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            setGraphic(pane);

            twoLineListItem.setTitle("Lunar " + item.getSelfVersion());
            twoLineListItem.setSubtitle("Minecraft " + item.getGameVersion());

            twoLineListItem.getTags().clear();
            if (item.isDefaultChoice())
                twoLineListItem.addTag("推荐");
            // the module count is the closest analogue of the launcher's "which loaders exist here"
            int modules = item.getLunar().modules().size();
            if (modules > 1)
                twoLineListItem.addTag(modules + " 个模块");

            // the Lunar logo when the client is already on disk, the launcher's own icon otherwise
            javafx.scene.image.Image icon = LunarIcon.image(
                    page.clientDirectoryFor(item), page.dataDirectory);
            imageView.setImage(icon != null ? icon : VersionIconType.COMMAND.getIcon());
        }
    }

    /// Where the client for `version` lives, for icon lookup.
    private Path clientDirectoryFor(LunarRemoteVersion version) {
        org.jackhuang.hmcl.game.HMCLGameRepository repository = selectedRepository();
        if (repository == null)
            return dataDirectory.resolve("lunar-client").resolve(version.getSelfVersion());
        return repository.getBaseDirectory().resolve("lunar-client").resolve(version.getSelfVersion());
    }
}

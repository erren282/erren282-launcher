/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.versions;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXPopup;
import com.jfoenix.controls.JFXTextField;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Skin;
import javafx.scene.control.SkinBase;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.game.GameInstanceCategory;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.*;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.IconedMenuItem;
import org.jackhuang.hmcl.ui.construct.MenuSeparator;
import org.jackhuang.hmcl.ui.construct.PopupMenu;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.download.ModpackInstallWizardProvider;
import org.jackhuang.hmcl.ui.directory.GameDirectoryListItem;
import org.jackhuang.hmcl.ui.directory.GameDirectoryPage;
import org.jackhuang.hmcl.util.FXThread;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.javafx.MappedObservableList;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.jackhuang.hmcl.ui.FXUtils.*;
import static org.jackhuang.hmcl.ui.ToolbarListPageSkin.createToolbarButton2;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class GameListPage extends DecoratorAnimatedPage implements DecoratorPage {
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("version.manage")));
    /// Navigation drawer items for configured game directories.
    @SuppressWarnings("FieldCanBeLocal")
    private final ObservableList<GameDirectoryListItem> gameDirectoryListItems;

    public GameListPage() {
        gameDirectoryListItems = MappedObservableList.create(GameDirectoryManager.getGameDirectories(), gameDirectory -> {
            GameDirectoryListItem item = new GameDirectoryListItem(gameDirectory);
            FXUtils.setLimitWidth(item, 200);
            return item;
        });

        {
            ScrollPane pane = new ScrollPane();
            VBox.setVgrow(pane, Priority.ALWAYS);
            {
                AdvancedListItem addGameDirectoryItem = new AdvancedListItem();
                addGameDirectoryItem.getStyleClass().add("navigation-drawer-item");
                addGameDirectoryItem.setTitle(i18n("game_directory.new"));
                addGameDirectoryItem.setLeftIcon(SVG.ADD_CIRCLE);
                addGameDirectoryItem.setOnAction(e -> Controllers.navigate(new GameDirectoryPage(null)));

                pane.setFitToWidth(true);
                VBox wrapper = new VBox();
                wrapper.getStyleClass().add("advanced-list-box-content");
                VBox box = new VBox();
                box.setFillWidth(true);
                Bindings.bindContent(box.getChildren(), gameDirectoryListItems);
                wrapper.getChildren().setAll(box, addGameDirectoryItem);
                pane.setContent(wrapper);
            }

            AdvancedListBox bottomLeftCornerList = new AdvancedListBox()
                    .addNavigationDrawerItem(i18n("install.new_game"), SVG.ADD_CIRCLE, Versions::addNewGame)
                    .addNavigationDrawerItem(i18n("install.modpack"), SVG.PACKAGE2, Versions::importModpack)
                    .addNavigationDrawerItem(i18n("settings.type.global.manage"), SVG.SETTINGS, this::modifyGlobalGameSettings);
            FXUtils.setLimitHeight(bottomLeftCornerList, 40 * 3 + 12 * 2);
            setLeft(pane, bottomLeftCornerList);
        }

        setCenter(new GameList());

        FXUtils.applyDragListener(this, file -> ModpackHelper.isFileModpackByExtension(file) || "json".equalsIgnoreCase(FileUtils.getNameWithoutExtension(file)), files -> {
            Path file = files.get(0);

            if (ModpackHelper.isFileModpackByExtension(file)) {
                Controllers.getDecorator().startWizard(new ModpackInstallWizardProvider(GameDirectoryManager.getSelectedRepository(), file), i18n("install.modpack"));
            } else if ("json".equalsIgnoreCase(FileUtils.getExtension(file))) {
                Versions.installFromJson(GameDirectoryManager.getSelectedRepository(), file);
            }
        });
    }

    public void modifyGlobalGameSettings() {
        Versions.modifyGlobalSettings(GameDirectoryManager.getSelectedRepository());
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    private static class GameList extends ListPageBase<GameListItem> {
        private final WeakListenerHolder listenerHolder = new WeakListenerHolder();

        private final ObservableList<GameListItem> sourceList = FXCollections.observableArrayList();

        /// The client kind the list is restricted to; `null` means every instance is shown.
        private final ObjectProperty<@Nullable GameInstanceCategory> categoryFilter =
                new SimpleObjectProperty<>(this, "categoryFilter");

        /// Whether the list is grouped by client kind, rendering a header row per group.
        private final BooleanProperty groupingEnabled = new SimpleBooleanProperty(this, "groupingEnabled", false);

        /// The current search text applied on top of the category filter.
        private final StringProperty searchQuery = new SimpleStringProperty(this, "searchQuery", "");

        /// The rows actually shown: instances, optionally interleaved with group headers.
        private final ObservableList<GameListItem> visibleList = FXCollections.observableArrayList();

        /// The categories present in this directory, in enum order.
        private final ObservableList<GameInstanceCategory> availableCategories = FXCollections.observableArrayList();

        /// Kept as a field so the same listener instance can be added and removed per item.
        private final ChangeListener<GameInstanceCategory> categoryListener =
                (observable, oldValue, newValue) -> updateVisibleList();

        public GameList() {
            setItems(visibleList);

            // Re-filter and re-group whenever the filter, the grouping mode or the query changes,
            // and whenever an instance finishes being classified in the background.
            categoryFilter.addListener((o, old, now) -> updateVisibleList());
            groupingEnabled.addListener((o, old, now) -> updateVisibleList());
            searchQuery.addListener((o, old, now) -> updateVisibleList());
            sourceList.addListener((ListChangeListener<GameListItem>) change -> {
                while (change.next()) {
                    if (change.wasAdded())
                        for (GameListItem item : change.getAddedSubList())
                            item.categoryProperty().addListener(categoryListener);
                    if (change.wasRemoved())
                        for (GameListItem item : change.getRemoved())
                            item.categoryProperty().removeListener(categoryListener);
                }
                updateVisibleList();
            });

            // 必须在上面那些监听器**之后**注册：版本已经加载完时 registerVersionsListener 会立刻
            // 回调 loadVersions（用户启动后第一次点进版本列表就是这种情况），而 loadVersions 里
            // `sourceList.setAll(...)` 依赖 sourceList 的变更监听器把内容同步到界面用的 visibleList。
            // 以前这行写在监听器之前，于是首次进入时 visibleList 永远没被填过 —— 列表看起来「没有版本」，
            // 手动点一次「刷新」（那时构造函数早已结束、监听器就位）才正常显示。
            GameDirectoryManager.registerVersionsListener(this::loadVersions);

            setOnFailedAction(e -> Controllers.navigate(Controllers.getDownloadPage()));
        }

        /// The categories currently offered by the filter menu, i.e. those present in this directory.
        public ObservableList<GameInstanceCategory> getAvailableCategories() {
            return availableCategories;
        }

        public ObjectProperty<@Nullable GameInstanceCategory> categoryFilterProperty() {
            return categoryFilter;
        }

        public BooleanProperty groupingEnabledProperty() {
            return groupingEnabled;
        }

        public StringProperty searchQueryProperty() {
            return searchQuery;
        }

        /// Rebuilds [#visibleList] from the source list according to filter, query and grouping.
        private void updateVisibleList() {
            Predicate<GameListItem> queryPredicate = createPredicate(searchQuery.get());
            GameInstanceCategory filter = categoryFilter.get();

            List<GameListItem> matching = new ArrayList<>();
            for (GameListItem item : sourceList) {
                if (filter != null && item.categoryProperty().get() != filter)
                    continue;
                if (queryPredicate.test(item))
                    matching.add(item);
            }

            // Offer only the categories this directory actually has, in enum order so the menu and
            // the group sections keep a stable, meaningful order (loaders before vanilla/unknown).
            List<GameInstanceCategory> present = sourceList.stream()
                    .map(item -> item.categoryProperty().get())
                    .distinct()
                    .sorted(Comparator.comparingInt(Enum::ordinal))
                    .toList();
            if (!availableCategories.equals(present))
                availableCategories.setAll(present);

            List<GameListItem> rows = new ArrayList<>(matching.size());
            if (groupingEnabled.get()) {
                // Iterating the enum (not the data) keeps section order stable as items load.
                for (GameInstanceCategory category : GameInstanceCategory.values()) {
                    List<GameListItem> group = matching.stream()
                            .filter(item -> item.categoryProperty().get() == category)
                            .toList();
                    if (group.isEmpty())
                        continue;
                    rows.add(GameListItem.header(category, group.size()));
                    rows.addAll(group);
                }
            } else {
                rows.addAll(matching);
            }

            visibleList.setAll(rows);

            if (matching.isEmpty()) {
                setFailedReason(sourceList.isEmpty()
                        ? i18n("version.empty.hint")
                        : i18n("version.category.empty"));
            } else {
                setFailedReason(null);
            }
        }

        @FXThread
        private void loadVersions(HMCLGameRepository repository) {
            listenerHolder.clear();
            setLoading(true);
            setFailedReason(null);

            try {
                // 逐个版本构建列表项：单个版本解析失败（例如它的壳版本刚被改名/删除、json 正在被改写）
                // 不能让整张列表崩掉。以前这里是一句 map(...).toList()，只要有一个版本抛
                // VersionNotFoundException，异常就会中断整个 loadVersions，`setLoading(false)` 永远
                // 执行不到 —— 页面就永远停在转圈状态（用户看到「版本列表没有版本」，点一次刷新才好，
                // 因为那时仓库已经重建、坏版本不在列表里了）。现在坏版本只记一条日志，其余照常显示。
                List<GameListItem> versionItems = new ArrayList<>();
                for (Version instance : repository.getDisplayVersions().toList()) {
                    try {
                        versionItems.add(new GameListItem(repository, instance.getId()));
                    } catch (Throwable t) {
                        LOG.warning("Skipping version " + instance.getId() + " in the instance list", t);
                    }
                }

                sourceList.setAll(versionItems);

                // 显式重建一次界面列表：不依赖 sourceList 监听器的注册顺序（构造函数里立刻回调时
                // 监听器可能还没挂上，那种情况下 visibleList 会一直空着）
                updateVisibleList();

                if (versionItems.isEmpty()) {
                    setFailedReason(i18n("version.empty.hint"));
                }
            } finally {
                // 无论上面发生什么，都必须结束加载态，否则界面会一直转圈
                setLoading(false);
            }
        }

        /// Builds the search predicate applied on top of the category filter.
        private Predicate<GameListItem> createPredicate(String searchText) {
            if (searchText == null || searchText.isEmpty()) {
                return item -> true;
            }

            if (searchText.startsWith("regex:")) {
                String regex = searchText.substring("regex:".length());
                try {
                    Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                    return item -> pattern.matcher(item.id).find();
                } catch (PatternSyntaxException e) {
                    return item -> false;
                }
            } else {
                return item -> item.id.toLowerCase(Locale.ROOT).contains(searchText.toLowerCase(Locale.ROOT));
            }
        }

        public void refreshList() {
            GameDirectoryManager.getSelectedRepository().refreshVersionsAsync().start();
        }

        @Override
        protected Skin<?> createDefaultSkin() {
            return new GameListSkin(this);
        }

        private static class GameListSkin extends SkinBase<GameList> {
            private final TransitionPane toolbarPane;
            private final HBox searchBar;
            private final HBox toolbarNormal;

            private final JFXTextField searchField;

            public GameListSkin(GameList skinnable) {
                super(skinnable);

                StackPane pane = new StackPane();
                pane.setPadding(new Insets(10));

                ComponentList root = new ComponentList();
                root.getStyleClass().add("no-padding");
                JFXListView<GameListItem> listView = new JFXListView<>();

                {
                    toolbarPane = new TransitionPane();

                    searchBar = new HBox();
                    toolbarNormal = new HBox();

                    searchBar.setAlignment(Pos.CENTER);
                    searchBar.setPadding(new Insets(0, 5, 0, 5));
                    searchField = new JFXTextField();
                    searchField.setPromptText(i18n("search"));
                    HBox.setHgrow(searchField, Priority.ALWAYS);
                    // The query feeds the control's own filtering pipeline, which already applies
                    // the category filter and grouping, so search and category compose naturally.
                    PauseTransition pause = new PauseTransition(Duration.millis(100));
                    pause.setOnFinished(e -> skinnable.searchQueryProperty().set(searchField.getText()));
                    searchField.textProperty().addListener((observable, oldValue, newValue) -> {
                        pause.setRate(1);
                        pause.playFromStart();
                    });

                    JFXButton closeSearchBar = createToolbarButton2(null, SVG.CLOSE, () -> {
                        changeToolbar(toolbarNormal);
                        searchField.clear();
                    });

                    onEscPressed(searchField, closeSearchBar::fire);

                    searchBar.getChildren().setAll(searchField, closeSearchBar);

                    // The category button opens a popup listing the categories present in this
                    // directory plus the grouping toggle; the menu is rebuilt on every open so the
                    // check marks always reflect the current filter.
                    JFXButton categoryButton = createToolbarButton2(i18n("version.category"), SVG.TUNE, null);
                    categoryButton.setOnAction(e -> {
                        JFXPopup popup = new JFXPopup();
                        popup.setPopupContent(createCategoryMenu(skinnable, popup));
                        popup.show(categoryButton, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.LEFT, 0, 4);
                    });

                    toolbarNormal.getChildren().setAll(
                            createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, skinnable::refreshList),
                            categoryButton,
                            createToolbarButton2(i18n("search"), SVG.SEARCH, () -> changeToolbar(searchBar)));

                    toolbarPane.setContent(toolbarNormal, ContainerAnimations.FADE);

                    FXUtils.setOverflowHidden(toolbarPane, 8);

                    root.getContent().add(toolbarPane);
                }

                {
                    SpinnerPane center = new SpinnerPane();
                    ComponentList.setVgrow(center, Priority.ALWAYS);
                    center.loadingProperty().bind(skinnable.loadingProperty());
                    center.failedReasonProperty().bind(skinnable.failedReasonProperty());

                    listView.setCellFactory(x -> new GameListCell());
                    listView.setItems(skinnable.getItems());

                    ignoreEvent(listView, KeyEvent.KEY_PRESSED, e -> e.getCode() == KeyCode.ESCAPE);

                    center.setContent(listView);
                    root.getContent().add(center);
                }

                pane.getChildren().setAll(root);
                getChildren().setAll(pane);
            }

            /// Builds the category popup: an "all" entry, one entry per present category, and a
            /// toggle for grouping the list by category.
            private PopupMenu createCategoryMenu(GameList skinnable, JFXPopup popup) {
                PopupMenu menu = new PopupMenu();

                List<Node> entries = new ArrayList<>();

                entries.add(createCategoryEntry(skinnable, popup, null, i18n("version.category.all")));
                entries.add(new MenuSeparator());

                for (GameInstanceCategory category : skinnable.getAvailableCategories()) {
                    entries.add(createCategoryEntry(skinnable, popup, category, i18n(category.getTranslationKey())));
                }

                entries.add(new MenuSeparator());

                BooleanProperty grouping = skinnable.groupingEnabledProperty();
                JFXCheckBox groupToggle = new JFXCheckBox(i18n("version.category.group"));
                groupToggle.setSelected(grouping.get());
                groupToggle.selectedProperty().bindBidirectional(grouping);
                HBox groupBox = new HBox(groupToggle);
                groupBox.setPadding(new Insets(8, 12, 8, 12));
                groupBox.setAlignment(Pos.CENTER_LEFT);
                entries.add(groupBox);

                menu.getContent().setAll(entries);
                return menu;
            }

            /// A single entry row in the category menu, marked with a check when it is selected.
            private Node createCategoryEntry(GameList skinnable, JFXPopup popup,
                                             @Nullable GameInstanceCategory category, String text) {
                boolean selected = Objects.equals(skinnable.categoryFilterProperty().get(), category);
                IconedMenuItem item = new IconedMenuItem(selected ? SVG.CHECK : null, text,
                        () -> skinnable.categoryFilterProperty().set(category), popup);
                if (selected)
                    item.setStyle("-fx-font-weight: bold;");
                return item;
            }

            private void changeToolbar(HBox newToolbar) {
                Node oldToolbar = toolbarPane.getCurrentNode();
                if (newToolbar != oldToolbar) {
                    toolbarPane.setContent(newToolbar, ContainerAnimations.FADE);
                    if (newToolbar == searchBar) {
                        runInFX(searchField::requestFocus);
                    }
                }
            }
        }
    }
}
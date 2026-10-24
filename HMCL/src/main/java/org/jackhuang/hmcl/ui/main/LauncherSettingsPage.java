/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.PluginSettingsTab;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.game.GameSettingsPage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class LauncherSettingsPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("settings")));
    private final TabHeader tab;
    private final TabHeader.Tab<GameSettingsPage<GameSettings.Preset>> gameTab = new TabHeader.Tab<>("versionSettingsPage");
    private final TabControl.Tab<JavaManagementPage> javaManagementTab = new TabControl.Tab<>("javaManagementPage");
    private final TabHeader.Tab<SettingsPage> settingsTab = new TabHeader.Tab<>("settingsPage");
    private final TabHeader.Tab<PersonalizationPage> personalizationTab = new TabHeader.Tab<>("personalizationPage");
    private final TabHeader.Tab<DownloadSettingsPage> downloadTab = new TabHeader.Tab<>("downloadSettingsPage");
    private final TabHeader.Tab<PluginPage> pluginTab = new TabHeader.Tab<>("pluginPage");
    private final TabHeader.Tab<AboutPage> aboutTab = new TabHeader.Tab<>("aboutPage");
    private final TransitionPane transitionPane = new TransitionPane();

    /// The settings sidebar; rebuilt whenever the contributed tabs change.
    private AdvancedListBox sideBar;

    public LauncherSettingsPage() {
        gameTab.setNodeSupplier(() -> new GameSettingsPage<>(GameSettings.Preset.class));
        javaManagementTab.setNodeSupplier(JavaManagementPage::new);
        settingsTab.setNodeSupplier(SettingsPage::new);
        personalizationTab.setNodeSupplier(PersonalizationPage::new);
        downloadTab.setNodeSupplier(DownloadSettingsPage::new);
        pluginTab.setNodeSupplier(PluginPage::new);
        aboutTab.setNodeSupplier(AboutPage::new);
        tab = new TabHeader(transitionPane, gameTab, javaManagementTab, settingsTab, personalizationTab, downloadTab, pluginTab, aboutTab);

        tab.select(gameTab);
        addEventHandler(Navigator.NavigationEvent.NAVIGATED, event -> gameTab.getNode().loadInstance(GameDirectoryManager.getSelectedRepository(), null));

        AdvancedListBox bar = new AdvancedListBox();
        FXUtils.setLimitWidth(bar, 200);
        this.sideBar = bar;
        rebuildSideBar();
        setLeft(bar);

        // Mirror PluginPage's behaviour: pick up tabs registered (or removed) while
        // the settings window is already open, e.g. when plugins are reloaded.
        PluginMenuService.getSettingsTabs().addListener((ListChangeListener<PluginSettingsTab>) change -> {
            while (change.next()) {
                for (PluginSettingsTab removed : change.getRemoved()) {
                    TabHeader.Tab<Node> node = pluginSettingsTabNodes.remove(removed);
                    // Leave the tab before dropping it, otherwise the content area
                    // keeps showing a page whose sidebar entry is gone.
                    if (node != null) {
                        if (tab.getSelectionModel().getSelectedItem() == node)
                            tab.select(gameTab, false);
                        tab.getTabs().remove(node);
                    }
                }
            }
            rebuildSideBar();
        });

        setCenter(transitionPane);
    }

    /// Maps a contributed settings tab onto the launcher's own tab machinery.
    private final Map<PluginSettingsTab, TabHeader.Tab<Node>> pluginSettingsTabNodes = new LinkedHashMap<>();

    /// (Re)builds the settings sidebar in one place, so plugin-contributed tabs
    /// always land in the launcher group right after the built-in 插件 entry and
    /// before the 关于 group. Rebuilding is cheap (a dozen items) and happens only
    /// when the set of contributed tabs changes.
    private void rebuildSideBar() {
        sideBar.clear();
        sideBar.addNavigationDrawerTab(tab, gameTab, i18n("settings.type.global.manage"), SVG.STADIA_CONTROLLER, SVG.STADIA_CONTROLLER_FILL)
                .addNavigationDrawerTab(tab, javaManagementTab, i18n("java.management"), SVG.LOCAL_CAFE, SVG.LOCAL_CAFE_FILL)
                .startCategory(i18n("launcher").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, settingsTab, i18n("settings.launcher.general"), SVG.TUNE)
                .addNavigationDrawerTab(tab, personalizationTab, i18n("settings.launcher.appearance"), SVG.STYLE, SVG.STYLE_FILL)
                .addNavigationDrawerTab(tab, downloadTab, i18n("download"), SVG.DOWNLOAD)
                .addNavigationDrawerTab(tab, pluginTab, i18n("plugin.title"), SVG.EXTENSION, SVG.EXTENSION_FILL);
        for (PluginSettingsTab pluginSettingsTab : PluginMenuService.getSettingsTabs())
            addPluginSettingsTab(pluginSettingsTab);
        sideBar.startCategory(i18n("about").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, aboutTab, i18n("about"), SVG.INFO, SVG.INFO_FILL);
    }

    /// Appends one plugin-contributed tab to the sidebar, registering a matching
    /// {@link TabHeader.Tab} the first time so selecting the entry has content.
    private void addPluginSettingsTab(PluginSettingsTab pluginSettingsTab) {
        TabHeader.Tab<Node> node = pluginSettingsTabNodes.get(pluginSettingsTab);
        if (node == null) {
            node = new TabHeader.Tab<>("pluginSettingsTab:" + System.identityHashCode(pluginSettingsTab));
            node.setNodeSupplier(pluginSettingsTab::createPage);
            pluginSettingsTabNodes.put(pluginSettingsTab, node);
            tab.getTabs().add(node);
        }
        // Captured by the action below, which needs an effectively final reference.
        final TabHeader.Tab<Node> tabNode = node;

        AdvancedListItem item = new AdvancedListItem();
        item.getStyleClass().add("navigation-drawer-item");
        item.setTitle(pluginSettingsTab.getTitle());
        item.setLeftIcon(pluginSettingsTab.getIcon());
        item.activeProperty().bind(tab.getSelectionModel().selectedItemProperty().isEqualTo(tabNode));
        item.setOnAction(e -> tab.select(tabNode));
        sideBar.add(item);
    }

    @Override
    public void onPageShown() {
        tab.onPageShown();
    }

    @Override
    public void onPageHidden() {
        tab.onPageHidden();
    }

    public void showGameSettings(HMCLGameRepository repository) {
        gameTab.getNode().loadInstance(repository, null);
        tab.select(gameTab, false);
    }

    /// Opens the Java management tab, where runtimes can also be downloaded.
    public void showJavaManagement() {
        tab.select(javaManagementTab, false);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}

/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.terracotta;

import com.jfoenix.controls.JFXPopup;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.setting.*;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.PluginMultiplayerTab;
import org.jackhuang.hmcl.terracotta.TerracottaMetadata;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.account.AccountAdvancedListItem;
import org.jackhuang.hmcl.ui.account.AccountListPopupMenu;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.main.MainPage;
import org.jackhuang.hmcl.ui.versions.GameListPopupMenu;
import org.jackhuang.hmcl.ui.versions.Versions;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.StringUtils;

import static org.jackhuang.hmcl.setting.SettingsManager.userState;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class TerracottaPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    private static final int TERRACOTTA_AGREEMENT_VERSION = 2;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("terracotta.terracotta")));
    private final TabHeader tab;
    private final TabHeader.Tab<TerracottaControllerPage> statusPage = new TabHeader.Tab<>("statusPage");
    private final TransitionPane transitionPane = new TransitionPane();

    /// The multiplayer sidebar; rebuilt whenever plugin-contributed tabs change.
    private AdvancedListBox sideBar;

    /// Maps a plugin-contributed tab onto the launcher's own tab machinery.
    private final java.util.Map<PluginMultiplayerTab, TabHeader.Tab<javafx.scene.Node>> pluginTabNodes = new java.util.LinkedHashMap<>();

    @SuppressWarnings("unused")
    private ChangeListener<String> instanceChangeListenerHolder;

    public TerracottaPage() {
        statusPage.setNodeSupplier(TerracottaControllerPage::new);
        tab = new TabHeader(transitionPane, statusPage);
        tab.select(statusPage);

        BorderPane left = new BorderPane();
        FXUtils.setLimitWidth(left, 200);
        VBox.setVgrow(left, Priority.ALWAYS);
        setLeft(left);

        sideBar = new AdvancedListBox();
        rebuildSideBar();
        left.setTop(sideBar);

        // Pick up tabs registered (or removed) while the multiplayer page is already open,
        // e.g. when plugins are reloaded from the plugin settings page.
        PluginMenuService.getMultiplayerTabs().addListener((javafx.collections.ListChangeListener<PluginMultiplayerTab>) change -> {
            while (change.next()) {
                for (PluginMultiplayerTab removed : change.getRemoved()) {
                    TabHeader.Tab<javafx.scene.Node> node = pluginTabNodes.remove(removed);
                    if (node != null) {
                        if (tab.getSelectionModel().getSelectedItem() == node)
                            tab.select(statusPage, false);
                        tab.getTabs().remove(node);
                    }
                }
            }
            rebuildSideBar();
        });

        AccountAdvancedListItem accountListItem = new AccountAdvancedListItem();
        accountListItem.setOnAction(e -> Controllers.navigate(Controllers.getAccountListPage()));
        accountListItem.accountProperty().bind(Accounts.selectedAccountProperty());
        FXUtils.onSecondaryButtonClicked(accountListItem, () -> AccountListPopupMenu.show(accountListItem, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.LEFT, accountListItem.getWidth(), 0));

        AdvancedListBox toolbar = new AdvancedListBox()
                .add(accountListItem)
                .addNavigationDrawerItem(i18n("version.launch"), SVG.ROCKET_LAUNCH, () -> {
                    var repository = GameDirectoryManager.getSelectedRepository();
                    Versions.launch(repository, repository.getSelectedInstance(), launcherHelper -> {
                        launcherHelper.setKeep();
                        launcherHelper.setDisableOfflineSkin();
                    });
                }, item -> {
                    instanceChangeListenerHolder = FXUtils.onWeakChangeAndOperate(GameDirectoryManager.selectedInstanceProperty(),
                            instanceName -> item.setSubtitle(StringUtils.isNotBlank(instanceName) ? instanceName : i18n("version.empty"))
                    );

                    MainPage mainPage = Controllers.getRootPage().getMainPage();
                    FXUtils.onScroll(item, mainPage.getVersions(), list -> {
                        String currentId = mainPage.getCurrentGame();
                        return Lang.indexWhere(list, instance -> instance.getId().equals(currentId));
                    }, it -> mainPage.getRepository().setSelectedInstance(it.getId()));

                    FXUtils.onSecondaryButtonClicked(item, () -> GameListPopupMenu.show(item,
                            JFXPopup.PopupVPosition.BOTTOM,
                            JFXPopup.PopupHPosition.LEFT,
                            item.getWidth(),
                            0,
                            mainPage.getRepository(), mainPage.getVersions()));
                })
                .addNavigationDrawerItem(i18n("terracotta.feedback.title"), SVG.FEEDBACK, () -> FXUtils.openLink(TerracottaMetadata.FEEDBACK_LINK));
        BorderPane.setMargin(toolbar, new Insets(0, 0, 12, 0));
        left.setBottom(toolbar);

        setCenter(transitionPane);
    }

    /// (Re)builds the multiplayer sidebar in one place, so plugin-contributed tabs always
    /// land right after the built-in 联机大厅 entry. Rebuilding is cheap and only happens
    /// when the set of contributed tabs changes.
    private void rebuildSideBar() {
        sideBar.clear();
        sideBar.addNavigationDrawerTab(tab, statusPage, i18n("terracotta.status"), SVG.TUNE);
        for (PluginMultiplayerTab pluginTab : PluginMenuService.getMultiplayerTabs())
            addPluginTab(pluginTab);
    }

    /// Appends one plugin-contributed tab to the sidebar, registering a matching
    /// {@link TabHeader.Tab} the first time so selecting the entry has content.
    private void addPluginTab(PluginMultiplayerTab pluginTab) {
        TabHeader.Tab<javafx.scene.Node> node = pluginTabNodes.get(pluginTab);
        if (node == null) {
            node = new TabHeader.Tab<>("pluginMultiplayerTab:" + System.identityHashCode(pluginTab));
            node.setNodeSupplier(pluginTab::createPage);
            pluginTabNodes.put(pluginTab, node);
            tab.getTabs().add(node);
        }
        // Captured by the action below, which needs an effectively final reference.
        final TabHeader.Tab<javafx.scene.Node> tabNode = node;

        AdvancedListItem item = new AdvancedListItem();
        item.getStyleClass().add("navigation-drawer-item");
        item.setTitle(pluginTab.getTitle());
        item.setLeftIcon(pluginTab.getIcon());
        item.activeProperty().bind(tab.getSelectionModel().selectedItemProperty().isEqualTo(tabNode));
        item.setOnAction(e -> tab.select(tabNode));
        sideBar.add(item);
    }

    @Override
    public void onPageShown() {
        tab.onPageShown();

        if (SettingsManager.userState().terracottaAgreementVersionProperty().get() < TERRACOTTA_AGREEMENT_VERSION) {
            Controllers.confirmWithCountdown(i18n("terracotta.confirm.desc"), i18n("terracotta.confirm.title"), 5, MessageDialogPane.MessageType.INFO, () -> {
                UserState userState = userState();
                userState.terracottaAgreementVersionProperty().set(TERRACOTTA_AGREEMENT_VERSION);
            }, () -> fireEvent(new PageCloseEvent()));
        }
    }

    @Override
    public void onPageHidden() {
        tab.onPageHidden();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}

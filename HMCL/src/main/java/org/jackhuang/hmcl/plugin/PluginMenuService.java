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
package org.jackhuang.hmcl.plugin;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Registry of extension points plugins can contribute to the launcher UI.
///
/// <p>Both registries are backed by observable lists mutated on the JavaFX application
/// thread, so account entries and the plugin settings page can render the registered
/// contributions reactively.</p>
@NotNullByDefault
public final class PluginMenuService {

    private static final ObservableList<PluginAccountAction> accountActions = FXCollections.observableArrayList();
    private static final ObservableList<PluginPageAction> pages = FXCollections.observableArrayList();
    private static final ObservableList<PluginLauncherPage> launcherPages = FXCollections.observableArrayList();

    /// Named sub-pages published by plugins; shown individually only while no group page exists.
    private static final ObservableList<PluginSubPage> subPages = FXCollections.observableArrayList();

    /// Sidebar wrappers for sub-pages, created once per sub-page so navigation stays stable.
    private static final java.util.Map<PluginSubPage, PluginLauncherPage> subPageWrappers = new java.util.IdentityHashMap<>();
    private static final ObservableList<PluginDownloadTab> downloadTabs = FXCollections.observableArrayList();

    /// Tabs contributed to the launcher's multiplayer page sidebar.
    private static final ObservableList<PluginMultiplayerTab> multiplayerTabs = FXCollections.observableArrayList();

    /// Tabs contributed to the launcher's own settings sidebar.
    private static final ObservableList<PluginSettingsTab> settingsTabs = FXCollections.observableArrayList();
    private static final ObservableList<PluginCrashAction> crashActions = FXCollections.observableArrayList();
    private static final ObservableList<PluginCrashListener> crashListeners = FXCollections.observableArrayList();

    /// Listeners receiving download lifecycle events, keyed by the wrapper registered on the bus.
    ///
    /// <p>Subscribed directly to the launcher's event bus rather than polled, so a plugin learns
    /// about a download the moment it starts instead of on its next refresh. The map keeps the bus
    /// wrapper so unregistering can remove exactly what was added.</p>
    private static final java.util.Map<java.util.function.Consumer<org.jackhuang.hmcl.event.DownloadEvent>,
            java.util.function.Consumer<org.jackhuang.hmcl.event.DownloadEvent>> downloadListeners =
            new java.util.concurrent.ConcurrentHashMap<>();

    /// Listeners receiving game launch state changes.
    private static final java.util.Map<
            java.util.function.Consumer<org.jackhuang.hmcl.event.GameLaunchStateEvent>,
            java.util.function.Consumer<org.jackhuang.hmcl.event.GameLaunchStateEvent>> launchListeners =
            new java.util.concurrent.ConcurrentHashMap<>();

    private PluginMenuService() {
    }

    /// Registers an action shown on the account entries of the account list.
    /// Thread-safe; the underlying list is mutated on the JavaFX application thread.
    ///
    /// @param action the action to register; registering the same instance twice is a no-op
    public static void registerAccountAction(PluginAccountAction action) {
        Objects.requireNonNull(action);
        Platform.runLater(() -> {
            if (!accountActions.contains(action))
                accountActions.add(action);
        });
    }

    /// Unregisters a previously registered account action. Thread-safe.
    ///
    /// @param action the action to remove
    public static void unregisterAccountAction(PluginAccountAction action) {
        Platform.runLater(() -> accountActions.remove(action));
    }

    /// Returns the registered account actions; read on the JavaFX application thread.
    public static ObservableList<PluginAccountAction> getAccountActions() {
        return accountActions;
    }

    /// Whether the launcher's own upload/edit-skin button is shown on account entries.
    ///
    /// <p>Lets a plugin that provides its own skin handling remove the built-in entry,
    /// so the account row does not offer two ways to do the same thing. On by default:
    /// with no plugin involved the launcher behaves exactly as before.</p>
    private static volatile boolean accountSkinUploadVisible = true;

    /// Shows or hides the launcher's own upload/edit-skin button.
    ///
    /// <p>Applies to account entries created afterwards; already-visible rows keep their
    /// button until the list is rebuilt, which the launcher does on its own when the
    /// account list is reopened.</p>
    ///
    /// @param visible false to remove the button
    public static void setAccountSkinUploadVisible(boolean visible) {
        accountSkinUploadVisible = visible;
    }

    /// Whether the launcher's own upload/edit-skin button should be shown.
    public static boolean isAccountSkinUploadVisible() {
        return accountSkinUploadVisible;
    }

    /// Registers a full page shown on the plugin section of the launcher settings.
    /// Thread-safe; the underlying list is mutated on the JavaFX application thread.
    ///
    /// @param page the page to register; registering the same instance twice is a no-op
    public static void registerPage(PluginPageAction page) {
        Objects.requireNonNull(page);
        Platform.runLater(() -> {
            if (!pages.contains(page))
                pages.add(page);
        });
    }

    /// Unregisters a previously registered page. Thread-safe.
    ///
    /// @param page the page to remove
    public static void unregisterPage(PluginPageAction page) {
        Platform.runLater(() -> pages.remove(page));
    }

    /// Returns the registered plugin pages; read on the JavaFX application thread.
    public static ObservableList<PluginPageAction> getPages() {
        return pages;
    }

    /// Registers a page shown in the game section of the main window sidebar.
    /// Thread-safe; the underlying list is mutated on the JavaFX application thread.
    ///
    /// @param page the page to register; registering the same instance twice is a no-op
    public static void registerLauncherPage(PluginLauncherPage page) {
        Objects.requireNonNull(page);
        Platform.runLater(() -> {
            if (!launcherPages.contains(page))
                launcherPages.add(page);
        });
    }

    /// Unregisters a previously registered main-window page. Thread-safe.
    ///
    /// @param page the page to remove
    public static void unregisterLauncherPage(PluginLauncherPage page) {
        Platform.runLater(() -> launcherPages.remove(page));
    }

    /// Returns the registered main-window pages; read on the JavaFX application thread.
    public static ObservableList<PluginLauncherPage> getLauncherPages() {
        return launcherPages;
    }

    /// Publishes a sub-page (see [PluginSubPage]).
    public static void registerSubPage(PluginSubPage page) {
        Platform.runLater(() -> {
            if (!subPages.contains(page))
                subPages.add(page);
        });
    }

    public static void unregisterSubPage(PluginSubPage page) {
        Platform.runLater(() -> {
            subPages.remove(page);
            subPageWrappers.remove(page);
        });
    }

    public static ObservableList<PluginSubPage> getSubPages() {
        return subPages;
    }

    /// True when some installed page is a group (hub) page for sub-pages.
    public static boolean hasPageGroup() {
        for (PluginLauncherPage page : launcherPages)
            if (page.isPageGroup())
                return true;
        return false;
    }

    /// The sidebar entry representing a sub-page (used when no group page exists).
    public static PluginLauncherPage wrapperOf(PluginSubPage subPage) {
        synchronized (subPageWrappers) {
            return subPageWrappers.computeIfAbsent(subPage, page -> new PluginLauncherPage() {
                @Override
                public String getTitle() {
                    return page.title();
                }

                @Override
                public SVG getIcon() {
                    return page.icon();
                }

                @Override
                public javafx.scene.Node createPage() {
                    // 作为独立侧边栏入口显示：没有“宿主”，页面不画返回按钮
                    return page.pageFactory().apply(null);
                }
            });
        }
    }

    /// Navigates to a sub-page by key: through its group page when one is installed, else
    /// straight to the sub-page's own sidebar entry.
    public static void showSubPage(String key) {
        PluginSubPage target = null;
        for (PluginSubPage page : subPages) {
            if (page.key().equals(key)) {
                target = page;
                break;
            }
        }
        if (target == null)
            return;
        for (PluginLauncherPage page : launcherPages) {
            if (page.isPageGroup()) {
                showLauncherPage(page);
                return;
            }
        }
        showLauncherPage(wrapperOf(target));
    }

    /// Registers a tab in the sidebar of the launcher's download page.
    /// Thread-safe; the underlying list is mutated on the JavaFX application thread.
    ///
    /// @param tab the tab to register; registering the same instance twice is a no-op
    public static void registerDownloadTab(PluginDownloadTab tab) {
        Objects.requireNonNull(tab);
        Platform.runLater(() -> {
            if (!downloadTabs.contains(tab))
                downloadTabs.add(tab);
        });
    }

    /// Unregisters a previously registered download tab. Thread-safe.
    ///
    /// @param tab the tab to remove
    public static void unregisterDownloadTab(PluginDownloadTab tab) {
        Platform.runLater(() -> downloadTabs.remove(tab));
    }

    /// Returns the registered download tabs; read on the JavaFX application thread.
    public static ObservableList<PluginDownloadTab> getDownloadTabs() {
        return downloadTabs;
    }

    /// Registers a tab in the sidebar of the launcher's multiplayer page.
    /// Thread-safe; the underlying list is mutated on the JavaFX application thread.
    ///
    /// <p>Plugin tabs are appended to the multiplayer sidebar right after the built-in
    /// 联机大厅 entry, so an alternative way to play together sits next to the built-in one.</p>
    ///
    /// @param tab the tab to register; registering the same instance twice is a no-op
    public static void registerMultiplayerTab(PluginMultiplayerTab tab) {
        Objects.requireNonNull(tab);
        Platform.runLater(() -> {
            if (!multiplayerTabs.contains(tab))
                multiplayerTabs.add(tab);
        });
    }

    /// Unregisters a previously registered multiplayer tab. Thread-safe.
    ///
    /// @param tab the tab to remove
    public static void unregisterMultiplayerTab(PluginMultiplayerTab tab) {
        Platform.runLater(() -> multiplayerTabs.remove(tab));
    }

    /// Returns the registered multiplayer tabs; read on the JavaFX application thread.
    public static ObservableList<PluginMultiplayerTab> getMultiplayerTabs() {
        return multiplayerTabs;
    }

    /// Navigates to the multiplayer page and selects the given plugin tab. Thread-safe.
    ///
    /// @param tab a registered multiplayer tab
    public static void showMultiplayerTab(PluginMultiplayerTab tab) {
        Objects.requireNonNull(tab);
        Platform.runLater(() -> {
            if (!multiplayerTabs.contains(tab))
                return;
            org.jackhuang.hmcl.ui.Controllers.navigate(org.jackhuang.hmcl.ui.Controllers.getTerracottaPage());
        });
    }

    /// Registers a tab in the sidebar of the launcher's own settings window.
    /// Thread-safe; the underlying list is mutated on the JavaFX application thread.
    ///
    /// <p>Plugin tabs are appended to the launcher group of the settings sidebar,
    /// right after the built-in 插件 entry and before the 帮助 group, so they sit
    /// with the other launcher settings rather than inside the plugin section.</p>
    ///
    /// @param tab the tab to register; registering the same instance twice is a no-op
    public static void registerSettingsTab(PluginSettingsTab tab) {
        Objects.requireNonNull(tab);
        Platform.runLater(() -> {
            if (!settingsTabs.contains(tab))
                settingsTabs.add(tab);
        });
    }

    /// Unregisters a previously registered settings tab. Thread-safe.
    ///
    /// @param tab the tab to remove
    public static void unregisterSettingsTab(PluginSettingsTab tab) {
        Platform.runLater(() -> settingsTabs.remove(tab));
    }

    /// Returns the registered settings tabs; read on the JavaFX application thread.
    public static ObservableList<PluginSettingsTab> getSettingsTabs() {
        return settingsTabs;
    }

    /// Registers a button shown on the game crash window. Thread-safe.
    ///
    /// @param action the action to register; registering the same instance twice is a no-op
    public static void registerCrashAction(PluginCrashAction action) {
        Objects.requireNonNull(action);
        Platform.runLater(() -> {
            if (!crashActions.contains(action))
                crashActions.add(action);
        });
    }

    /// Unregisters a previously registered crash action. Thread-safe.
    ///
    /// @param action the action to remove
    public static void unregisterCrashAction(PluginCrashAction action) {
        Platform.runLater(() -> crashActions.remove(action));
    }

    /// Returns the registered crash actions; read on the JavaFX application thread.
    public static ObservableList<PluginCrashAction> getCrashActions() {
        return crashActions;
    }

    /// Registers a callback fired when a game crash window opens. Thread-safe.
    ///
    /// @param listener the listener to register; registering the same instance twice is a no-op
    public static void registerCrashListener(PluginCrashListener listener) {
        Objects.requireNonNull(listener);
        Platform.runLater(() -> {
            if (!crashListeners.contains(listener))
                crashListeners.add(listener);
        });
    }

    /// Unregisters a previously registered crash listener. Thread-safe.
    ///
    /// @param listener the listener to remove
    public static void unregisterCrashListener(PluginCrashListener listener) {
        Platform.runLater(() -> crashListeners.remove(listener));
    }

    /// Returns the registered crash listeners; read on the JavaFX application thread.
    public static ObservableList<PluginCrashListener> getCrashListeners() {
        return crashListeners;
    }

    /// Registers a listener for game launch start and end. Thread-safe.
    ///
    /// <p>Events arrive on the thread that drives the launch — usually the JavaFX thread — so a
    /// listener may touch the UI directly, but should still be quick.</p>
    ///
    /// @param listener the listener
    public static void registerLaunchListener(
            java.util.function.Consumer<org.jackhuang.hmcl.event.GameLaunchStateEvent> listener) {
        Objects.requireNonNull(listener);
        if (launchListeners.putIfAbsent(listener, listener) != null)
            return;

        java.util.function.Consumer<org.jackhuang.hmcl.event.GameLaunchStateEvent> wrapper = event -> {
            try {
                listener.accept(event);
            } catch (Throwable ignored) {
                // A failing plugin listener must not disturb the launch.
            }
        };
        org.jackhuang.hmcl.event.EventBus.EVENT_BUS
                .channel(org.jackhuang.hmcl.event.GameLaunchStateEvent.class)
                .register(wrapper);
    }

    /// Removes a launch listener. Thread-safe.
    ///
    /// @param listener the listener to remove
    public static void unregisterLaunchListener(
            java.util.function.Consumer<org.jackhuang.hmcl.event.GameLaunchStateEvent> listener) {
        Objects.requireNonNull(listener);
        launchListeners.remove(listener);
    }

    /// Registers a listener for download lifecycle events. Thread-safe.
    ///
    /// <p>Events are delivered on the downloading thread, so a listener that touches the UI must
    /// marshal to the JavaFX thread itself.</p>
    ///
    /// <p>The subscription is weak: once a plugin drops its reference to the listener — which
    /// happens when it is disabled or reloaded — the bus stops calling it. That avoids needing an
    /// unregister call, which [org.jackhuang.hmcl.event.EventManager] does not offer.</p>
    ///
    /// @param listener the listener
    public static void registerDownloadListener(
            java.util.function.Consumer<org.jackhuang.hmcl.event.DownloadEvent> listener) {
        Objects.requireNonNull(listener);
        if (downloadListeners.putIfAbsent(listener, listener) != null)
            return;

        java.util.function.Consumer<org.jackhuang.hmcl.event.DownloadEvent> wrapper = event -> {
            try {
                listener.accept(event);
            } catch (Throwable ignored) {
                // A failing plugin listener must not disturb the download.
            }
        };
        // The wrapper is held by the map so it is not collected while the plugin is alive.
        org.jackhuang.hmcl.event.EventBus.EVENT_BUS
                .channel(org.jackhuang.hmcl.event.DownloadEvent.class)
                .register(wrapper);
    }

    /// Removes a previously registered download listener. Thread-safe.
    ///
    /// @param listener the listener to remove
    public static void unregisterDownloadListener(
            java.util.function.Consumer<org.jackhuang.hmcl.event.DownloadEvent> listener) {
        Objects.requireNonNull(listener);
        downloadListeners.remove(listener);
    }

    /// Navigates to the given main-window plugin page. Thread-safe.
    ///
    /// @param page a registered launcher page
    public static void showLauncherPage(PluginLauncherPage page) {
        Objects.requireNonNull(page);
        Platform.runLater(() -> {
            if (!launcherPages.contains(page))
                return;
            org.jackhuang.hmcl.ui.Controllers.navigate(
                    org.jackhuang.hmcl.ui.main.PluginPageProxy.of(page));
        });
    }

    /// Navigates to the download page and selects the given plugin tab. Thread-safe.
    ///
    /// @param tab a registered download tab
    public static void showDownloadTab(PluginDownloadTab tab) {
        Objects.requireNonNull(tab);
        Platform.runLater(() -> {
            if (!downloadTabs.contains(tab))
                return;
            org.jackhuang.hmcl.ui.download.DownloadPage downloadPage
                    = org.jackhuang.hmcl.ui.Controllers.getDownloadPage();
            downloadPage.showPluginTab(tab);
            org.jackhuang.hmcl.ui.Controllers.navigate(downloadPage);
        });
    }
}

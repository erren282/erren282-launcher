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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginLauncherPage;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage.DecoratorAnimatedPageSkin;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

import java.util.IdentityHashMap;
import java.util.Map;

/// Wraps a [PluginLauncherPage] into a decorated page so it can be navigated to
/// from the main window sidebar. One proxy instance exists per plugin page; the
/// page node is created once and cached for the whole session.
@NotNullByDefault
public final class PluginPageProxy extends DecoratorAnimatedPage implements DecoratorPage {

    private static final Map<PluginLauncherPage, PluginPageProxy> proxies = new IdentityHashMap<>();

    /// Returns the (cached) proxy navigating to the given plugin page.
    public static PluginPageProxy of(PluginLauncherPage page) {
        synchronized (proxies) {
            return proxies.computeIfAbsent(page, PluginPageProxy::new);
        }
    }

    private final PluginLauncherPage pluginPage;
    private final ReadOnlyObjectWrapper<State> state;

    private PluginPageProxy(PluginLauncherPage pluginPage) {
        this.pluginPage = pluginPage;
        this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(pluginPage.getTitle()));

        // the decorated page skin always reserves a 200px left column; plugin pages
        // have no sidebar, so drop the empty spacer to keep the content left-aligned
        getLeft().setManaged(false);
        getLeft().setVisible(false);

        Node node;
        try {
            node = pluginPage.createPage();
        } catch (Throwable throwable) {
            Label error = new Label(String.valueOf(throwable));
            error.setWrapText(true);
            VBox box = new VBox(10, error);
            box.setPadding(new javafx.geometry.Insets(10));
            node = box;
        }

        ScrollPane scrollPane = new ScrollPane(node);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);
        setCenter(scrollPane);

        // 每次从主页面再次进入插件页面都重建内容：分页、滚动位置、子页面等一律恢复初始样子
        addEventHandler(org.jackhuang.hmcl.ui.construct.Navigator.NavigationEvent.NAVIGATED, event -> {
            try {
                Node fresh = pluginPage.createPage();
                scrollPane.setContent(fresh);
                scrollPane.setVvalue(0);
                scrollPane.setHvalue(0);
            } catch (Throwable throwable) {
                LOG.warning("Failed to recreate plugin page " + pluginPage.getTitle(), throwable);
            }
        });
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    protected DecoratorAnimatedPageSkin<PluginPageProxy> createDefaultSkin() {
        return new Skin(this);
    }

    private static class Skin extends DecoratorAnimatedPageSkin<PluginPageProxy> {
        Skin(PluginPageProxy control) {
            super(control);
        }
    }
}

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

import javafx.scene.Node;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

/// A tab contributed by a plugin to the launcher's own settings window.
///
/// <p>The tab is listed in the settings sidebar next to the built-in entries
/// (全局游戏设置 / Java 管理 / 通用 / 外观 / 下载 / 插件), and selecting it shows
/// the page node in the settings content area. Unlike a
/// [PluginPageAction][PluginPageAction] — which is listed inside the plugin
/// section and opens with a back button — a settings tab behaves like a native
/// settings page: it is one click away and shares the settings window's own
/// sidebar and scroll container.</p>
///
/// <p>[createPage][PluginSettingsTab.createPage] is invoked lazily on the JavaFX
/// application thread whenever the tab is selected, exactly like the built-in
/// settings tabs.</p>
///
/// @see PluginMenuService#registerSettingsTab(PluginSettingsTab)
@NotNullByDefault
public interface PluginSettingsTab {

    /// Returns the display name of the tab, shown in the settings sidebar.
    String getTitle();

    /// Returns the sidebar icon. Called on the JavaFX thread.
    default SVG getIcon() {
        return SVG.EXTENSION;
    }

    /// Creates the tab content. Called lazily on the JavaFX application thread.
    Node createPage();
}

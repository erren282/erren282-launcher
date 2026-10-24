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

/// A download tab contributed by a plugin to the launcher's download page.
///
/// <p>The tab appears in the download page sidebar; clicking it shows the page node.
/// [createPage][PluginDownloadTab.createPage] is created lazily on the JavaFX
/// application thread and cached, like the built-in download tabs.</p>
///
/// @see PluginMenuService#registerDownloadTab(PluginDownloadTab)
/// @see PluginMenuService#showDownloadTab(PluginDownloadTab)
@NotNullByDefault
public interface PluginDownloadTab {

    /// Returns the display name of the tab.
    String getTitle();

    /// Returns the sidebar icon. Called on the JavaFX thread.
    default SVG getIcon() {
        return SVG.EXTENSION;
    }

    /// Creates the tab content. Called lazily on the JavaFX application thread.
    Node createPage();
}

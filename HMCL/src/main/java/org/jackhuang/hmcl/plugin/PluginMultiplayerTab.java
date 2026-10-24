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

/// A tab contributed by a plugin to the launcher's multiplayer (Terracotta) page.
///
/// <p>The tab appears in the multiplayer page sidebar next to the built-in 联机大厅 entry;
/// selecting it shows the page node in the multiplayer content area. This lets a plugin
/// provide an alternative or additional way to play together — for example a different
/// tunnelling service that is driven as a child process.</p>
///
/// <p>[createPage][PluginMultiplayerTab.createPage] is invoked lazily on the JavaFX
/// application thread the first time the tab is selected and the resulting node is cached
/// for the rest of the session, exactly like the built-in tabs.</p>
///
/// @see PluginMenuService#registerMultiplayerTab(PluginMultiplayerTab)
/// @see PluginMenuService#showMultiplayerTab(PluginMultiplayerTab)
@NotNullByDefault
public interface PluginMultiplayerTab {

    /// Returns the display name of the tab, shown in the multiplayer sidebar.
    String getTitle();

    /// Returns the sidebar icon. Called on the JavaFX thread.
    default SVG getIcon() {
        return SVG.EXTENSION;
    }

    /// Creates the tab content. Called lazily on the JavaFX application thread.
    Node createPage();
}

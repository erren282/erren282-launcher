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

/// A page contributed by a plugin to the main window sidebar (game section).
///
/// <p>The entry is inserted after the download entry; clicking it navigates to the
/// page node with a decorated title bar. [createPage][PluginLauncherPage.createPage]
/// is created once on the JavaFX application thread and cached for the session, so
/// plugin pages can keep their state across navigations.</p>
///
/// @see PluginMenuService#registerLauncherPage(PluginLauncherPage)
@NotNullByDefault
public interface PluginLauncherPage {

    /// Returns the display name of the sidebar entry and page title.
    String getTitle();

    /// Returns the sidebar icon; rendered exactly like the built-in sidebar entries.
    /// True when this page is a hub that presents [PluginMenuService#getSubPages] itself.
    /// While such a page is installed, sub-pages are not listed as separate sidebar entries.
    default boolean isPageGroup() {
        return false;
    }

    default SVG getIcon() {
        return SVG.EXTENSION;
    }

    /// Creates the page content. Called once on the JavaFX application thread; the
    /// launcher caches the node for the whole session.
    Node createPage();
}

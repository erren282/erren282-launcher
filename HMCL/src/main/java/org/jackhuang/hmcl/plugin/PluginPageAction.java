/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huanghongxun2008 <huanghongxun2008@126.com> and contributors
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
import org.jetbrains.annotations.NotNullByDefault;

/// A full page contributed by a plugin to the plugin section of the launcher settings.
///
/// <p>Pages are listed on the plugin settings page; clicking an entry shows the page
/// node with a back button. The node returned by [createPage][PluginPageAction.createPage]
/// is created and invoked on the JavaFX application thread and cached by the launcher for
/// the lifetime of the session, so plugin pages can keep their state (logs, selections)
/// across navigations.</p>
///
/// @see PluginMenuService#registerPage(PluginPageAction)
@NotNullByDefault
public interface PluginPageAction {

    /// Returns the display name of the page, shown as the row title.
    String getTitle();

    /// Returns a short description, shown as the row subtitle.
    default String getDescription() {
        return "";
    }

    /// Creates the page content. Called on the JavaFX application thread.
    Node createPage();
}

/*
 * Hello Minecraft! Launcher plugin: lunar-offline
 * Copyright (C) 2026 dsh
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
package org.dsh.lunarofl;

import javafx.scene.Node;
import org.jackhuang.hmcl.plugin.PluginDownloadTab;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// The **Lunar 客户端** entry in the launcher's download sidebar.
///
/// <p>Only the sidebar registration lives here; the page itself is [LunarDownloadPage], which is
/// built to match the launcher's own version download page.</p>
@NotNullByDefault
public final class LunarDownloadTab implements PluginDownloadTab {

    private final Path dataDirectory;

    public LunarDownloadTab(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    @Override
    public String getTitle() {
        return "Lunar 客户端";
    }

    @Override
    public SVG getIcon() {
        return SVG.STADIA_CONTROLLER;
    }

    @Override
    public Node createPage() {
        return new LunarDownloadPage(dataDirectory);
    }
}

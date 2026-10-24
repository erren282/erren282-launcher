/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.dsh.mcilm;

import javafx.scene.Node;
import org.jackhuang.hmcl.plugin.PluginMultiplayerTab;
import org.jackhuang.hmcl.ui.SVG;

/// The multiplayer-page tab contributed by the MciLm-link plugin.
///
/// <p>Thin wrapper so the page node is created lazily by the launcher and the tab can be
/// unregistered without touching the node.</p>
final class MciLmTab implements PluginMultiplayerTab {

    private final MciLmPage page;

    MciLmTab(MciLmPage page) {
        this.page = page;
    }

    @Override
    public String getTitle() {
        return "MciLm 联机";
    }

    @Override
    public SVG getIcon() {
        return SVG.PUBLIC;
    }

    @Override
    public Node createPage() {
        return page.create();
    }
}


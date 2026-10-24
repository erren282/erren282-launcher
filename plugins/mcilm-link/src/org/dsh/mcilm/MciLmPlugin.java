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

import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginMenuService;

/// HMCL plugin that adds MciLm-link as a second way to play together.
///
/// <p>MciLm-link is a cross-network tunnelling tool with a P2P + relay architecture. Its
/// command line client exposes an NDJSON event stream (`--json`) specifically for launchers,
/// so the plugin drives it as a child process: the host mode creates a room and prints the
/// room ID that friends join with, the guest mode joins a room and opens a local port that
/// the game connects to.</p>
///
/// <p>The plugin contributes one tab to the launcher's multiplayer page, next to the
/// built-in 联机大厅 entry.</p>
public class MciLmPlugin extends HmclPlugin {

    private MciLmPage page;
    private MciLmTab tab;
    private UiPatcher uiPatcher;

    @Override
    public void onEnable() {
        page = new MciLmPage(getContext());
        tab = new MciLmTab(page);
        PluginMenuService.registerMultiplayerTab(tab);

        uiPatcher = new UiPatcher(getContext());
        uiPatcher.start();

        getContext().logInfo("MciLm-link plugin enabled; executable: " + page.getExecutable());
    }

    @Override
    public void onDisable() {
        if (uiPatcher != null) {
            uiPatcher.stop();
            uiPatcher = null;
        }
        if (tab != null) {
            PluginMenuService.unregisterMultiplayerTab(tab);
            tab = null;
        }
        if (page != null) {
            page.shutdown();
            page = null;
        }
        getContext().logInfo("MciLm-link plugin disabled");
    }
}

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

import org.jetbrains.annotations.NotNullByDefault;

/// Notified when a game crash window opens, so plugins can react automatically —
/// for example scanning the log for missing mods and offering to download them.
///
/// <p>Listeners run on the JavaFX application thread; heavy work must be moved to a
/// background thread. Exceptions from listeners are logged and do not affect the
/// launcher or other plugins.</p>
///
/// @see PluginMenuService#registerCrashListener(PluginCrashListener)
/// @see GameCrashContext
@NotNullByDefault
public interface PluginCrashListener {

    /// Called when a game crash window opens.
    void onCrash(GameCrashContext context);
}

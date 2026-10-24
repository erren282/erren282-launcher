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

/// An extra button contributed by a plugin to the game crash window.
///
/// <p>Actions are rendered next to the built-in export/log/help buttons; clicking
/// one hands the crash context (logs, instance, loaders) to [run][PluginCrashAction.run]
/// on the JavaFX application thread.</p>
///
/// @see PluginMenuService#registerCrashAction(PluginCrashAction)
/// @see GameCrashContext
@NotNullByDefault
public interface PluginCrashAction {

    /// Returns the button text shown on the crash window.
    String getName();

    /// Runs the action for the given crash. Called on the JavaFX application thread;
    /// long work must be moved to a background thread.
    void run(GameCrashContext context);
}

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

/// Receives addon download events fired by the launcher.
///
/// <p>Listeners are invoked on the JavaFX application thread after a download started from
/// the download pages finishes successfully. Long-running work must be dispatched to a
/// background thread; exceptions thrown by a listener are logged and ignored.</p>
///
/// @see PluginAddonService#registerListener(PluginAddonListener)
@FunctionalInterface
@NotNullByDefault
public interface PluginAddonListener {

    /// Invoked after an addon download has completed.
    ///
    /// @param event information about the downloaded addon
    void onAddonDownloaded(PluginAddonEvent event);
}

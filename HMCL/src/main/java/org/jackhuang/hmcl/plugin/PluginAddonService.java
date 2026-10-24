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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Registry of listeners notified when the launcher installs an addon downloaded from a
/// remote source.
///
/// <p>The registry is thread-safe. [PluginAddonService#fireAddonDownloaded] is called by the
/// launcher on the JavaFX application thread; listener failures are caught and logged so that
/// one plugin cannot break the launcher or other plugins.</p>
@NotNullByDefault
public final class PluginAddonService {

    private static final List<PluginAddonListener> listeners = new CopyOnWriteArrayList<>();

    private PluginAddonService() {
    }

    /// Registers a listener. Thread-safe; registering the same instance twice is a no-op.
    ///
    /// @param listener the listener to register
    public static void registerListener(PluginAddonListener listener) {
        Objects.requireNonNull(listener);
        if (!listeners.contains(listener))
            listeners.add(listener);
    }

    /// Unregisters a previously registered listener. Thread-safe.
    ///
    /// @param listener the listener to unregister
    public static void unregisterListener(PluginAddonListener listener) {
        listeners.remove(listener);
    }

    /// Notifies all registered listeners about a completed addon download.
    ///
    /// <p>Called by the launcher on the JavaFX application thread. Exceptions thrown by a
    /// listener are logged and do not prevent the remaining listeners from running.</p>
    ///
    /// @param event information about the downloaded addon
    public static void fireAddonDownloaded(PluginAddonEvent event) {
        Objects.requireNonNull(event);
        for (PluginAddonListener listener : listeners) {
            try {
                listener.onAddonDownloaded(event);
            } catch (Throwable e) {
                LOG.warning("Plugin addon download listener failed", e);
            }
        }
    }
}

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
import org.jetbrains.annotations.Nullable;

/// The privilege level a plugin runs with inside the launcher process.
///
/// <p>HMCL plugins are not sandboxed: a loaded jar is trusted code and can always call any
/// public launcher class directly. The access level exists to make that trust explicit and
/// to gate the [LauncherRoot] service object, which exposes the launcher internals (settings,
/// accounts, game directories, launching, window control, plugin management and shutdown) in
/// one place.</p>
///
/// <p>The level is declared by the `access` field of `hmcl.plugin.json`. When the field is
/// absent the plugin gets [PluginAccess#ROOT], i.e. the highest privileges, so a plugin can
/// use the full launcher API without any extra configuration.</p>
@NotNullByDefault
public enum PluginAccess {

    /// Only the documented, low-risk services of [PluginContext] (logging, dialogs, data
    /// directory, links). [PluginContext#getRoot] is refused.
    STANDARD("standard"),

    /// Everything [PluginAccess#STANDARD] offers plus read-only launcher state
    /// (settings, accounts, game directories) and the ability to run code on the JavaFX
    /// thread. Destructive operations of [LauncherRoot] stay disabled.
    ELEVATED("elevated"),

    /// Unrestricted access to the launcher process: settings, accounts, game directories,
    /// launching and killing game processes, navigation, plugin installation/reloading,
    /// launcher restart and shutdown. This is the default for plugins without an explicit
    /// `access` field.
    ROOT("root");

    private final String id;

    PluginAccess(String id) {
        this.id = id;
    }

    /// Returns the identifier used in the plugin manifest, e.g. `root`.
    public String getId() {
        return id;
    }

    /// Resolves the manifest identifier of an access level.
    ///
    /// @param id the identifier, case-insensitive; `null` or blank yields [PluginAccess#ROOT]
    /// @return the matching level
    /// @throws IllegalArgumentException when the identifier is not a known level
    public static PluginAccess fromId(@Nullable String id) {
        if (id == null || id.isBlank())
            return ROOT;

        String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
        for (PluginAccess access : values()) {
            if (access.id.equals(normalized))
                return access;
        }
        throw new IllegalArgumentException("Unknown plugin access level \"" + id
                + "\": expected one of standard, elevated, root");
    }

    /// Whether this level grants the [LauncherRoot] service object.
    public boolean isAtLeast(PluginAccess other) {
        return ordinal() >= other.ordinal();
    }

    @Override
    public String toString() {
        return id;
    }
}

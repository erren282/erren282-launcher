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

import java.net.URLClassLoader;
import java.nio.file.Path;

/// One plugin jar known to the [PluginManager], together with its runtime state.
@NotNullByDefault
public final class PluginHandle {

    /// The lifecycle state of a plugin.
    public enum State {
        /// The plugin is loaded and currently enabled.
        ENABLED,
        /// The plugin is loaded but currently disabled by the user.
        DISABLED,
        /// The plugin could not be loaded, instantiated or enabled; see [PluginHandle.getError].
        FAILED,
        /// The plugin jar declares a launcher version newer than the running one.
        INCOMPATIBLE,
    }

    private final Path jarPath;
    private final @Nullable PluginDescription description;
    private final @Nullable HmclPlugin instance;
    private final @Nullable String error;
    private final @Nullable URLClassLoader classLoader;
    private State state;

    /// Creates a handle for a plugin jar.
    ///
    /// @param jarPath     the path of the plugin jar
    /// @param description the parsed manifest, or `null` when the jar has no valid manifest
    /// @param instance    the plugin instance, or `null` when it failed to load or is incompatible
    /// @param state       the initial lifecycle state
    /// @param error       the reason the plugin failed, or `null` when it did not fail
    /// @param classLoader the class loader of the plugin jar, or `null` when the jar was not loaded
    public PluginHandle(Path jarPath, @Nullable PluginDescription description,
                        @Nullable HmclPlugin instance, State state, @Nullable String error,
                        @Nullable URLClassLoader classLoader) {
        this.jarPath = jarPath;
        this.description = description;
        this.instance = instance;
        this.state = state;
        this.error = error;
        this.classLoader = classLoader;
    }

    /// Returns the path of the plugin jar.
    public Path getJarPath() {
        return jarPath;
    }

    /// Returns the parsed manifest of the plugin, or `null` when the jar has no valid manifest.
    public @Nullable PluginDescription getDescription() {
        return description;
    }

    /// Returns the plugin identifier, falling back to the jar file name when the manifest is unreadable.
    public String getId() {
        return description != null ? description.getId() : jarPath.getFileName().toString();
    }

    /// Returns the plugin instance, or `null` when it failed to load or is incompatible.
    public @Nullable HmclPlugin getInstance() {
        return instance;
    }

    /// Returns the current lifecycle state of the plugin.
    public State getState() {
        return state;
    }

    /// Returns the reason the plugin failed, or `null` when it did not fail.
    public @Nullable String getError() {
        return error;
    }

    /// Updates the lifecycle state; package-private, managed by the plugin manager.
    void setState(State state) {
        this.state = state;
    }

    /// Closes the plugin class loader so the jar file can be modified or deleted;
    /// package-private, managed by the plugin manager.
    void close() {
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (java.io.IOException e) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "Failed to close class loader of plugin " + getId(), e);
            }
        }
    }
}

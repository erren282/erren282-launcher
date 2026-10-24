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

import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// The base class every HMCL plugin must extend.
///
/// <p>The entry class declared in `hmcl.plugin.json` must be public, have a public no-argument
/// constructor and extend this class. The launcher instantiates it once per plugin jar and calls
/// the lifecycle methods below.</p>
///
/// <p>Lifecycle methods are invoked on launcher-owned background threads. Plugin code must not
/// block them for long and must not touch JavaFX directly; use [PluginContext] helpers or
/// `Platform.runLater` for UI work.</p>
///
/// <p>Plugins are trusted code and run with [PluginAccess#ROOT] unless the manifest declares a
/// lower level, so [PluginContext#getRoot] gives direct access to the launcher internals.</p>
@NotNullByDefault
public abstract class HmclPlugin {

    private @Nullable PluginContext context;

    /// Called once when the plugin is enabled, either at launcher startup or when the user
    /// toggles it on. Plugins should initialize resources here.
    ///
    /// @throws Exception if initialization fails; the plugin is marked as failed
    public abstract void onEnable() throws Exception;

    /// Called once when the plugin is disabled, reloaded or when the launcher exits.
    /// Plugins should release resources here.
    public void onDisable() {
    }

    /// Returns the root service object of the launcher, a shortcut for `getContext().getRoot()`.
    ///
    /// @throws IllegalStateException if the plugin has not been attached or runs with
    ///                               [PluginAccess#STANDARD]
    public final LauncherRoot getRoot() {
        return getContext().getRoot();
    }

    /// Called before the game process of one launch is spawned.
    ///
    /// @param launchContext information about the pending launch
    public void onBeforeLaunch(LaunchContext launchContext) {
    }

    /// Called after the game process of one launch has been spawned successfully.
    ///
    /// @param launchContext information about the launch
    /// @param process       the managed game process
    public void onAfterLaunch(LaunchContext launchContext, ManagedProcess process) {
    }

    /// Returns the context of this plugin, granting access to launcher services.
    ///
    /// @throws IllegalStateException if called before the plugin has been attached by the plugin manager
    public final PluginContext getContext() {
        if (context == null)
            throw new IllegalStateException("Plugin is not attached to a context yet; attach happens when the plugin jar is loaded");
        return context;
    }

    /// Invoked by the plugin manager right after instantiation; not part of the plugin API.
    final void attach(PluginContext context) {
        this.context = context;
    }
}

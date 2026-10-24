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

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Loads, installs, enables, disables, deletes and reloads HMCL plugins, and dispatches
/// launch events to them.
///
/// <p>Plugins live in `plugins/` under the launcher config directory ([Metadata#HMCL_LOCAL_HOME]).
/// Each plugin is a jar with a `hmcl.plugin.json` manifest at its root; see the plugin development
/// documentation shipped in the launcher repository for the full format.</p>
///
/// <p>Plugin jars run with full permissions inside the launcher process: they are trusted code,
/// just like mods installed into a game instance.</p>
@NotNullByDefault
public final class PluginManager {

    /// The name of the state file storing the disabled plugin identifiers.
    private static final String STATE_FILE_NAME = "plugins.json";

    private static final Object LOCK = new Object();

    // guarded by LOCK
    private static final List<PluginHandle> plugins = new ArrayList<>();
    // guarded by LOCK; the directory the current plugins were loaded from
    private static @Nullable Path loadedDirectory;
    // guarded by LOCK
    private static boolean loaded = false;
    // guarded by LOCK; notified when the set of loaded plugins changes
    private static final List<Runnable> changeListeners = new ArrayList<>();

    private PluginManager() {
    }

    /// Returns the directory plugin jars are loaded from.
    public static Path getPluginsDirectory() {
        return Metadata.HMCL_LOCAL_HOME.resolve("plugins");
    }

    /// Loads all plugin jars from [PluginManager#getPluginsDirectory] and enables the ones
    /// the user has not disabled. Safe to call again; an existing load is replaced.
    public static void load() {
        load(getPluginsDirectory());
    }

    /// Loads all plugin jars from the given directory and enables the ones
    /// the user has not disabled. Safe to call again; an existing load is replaced.
    ///
    /// @param directory the directory to scan for plugin jars
    public static void load(Path directory) {
        synchronized (LOCK) {
            discardAll();
            plugins.clear();
            loadedDirectory = directory;

            if (!Files.isDirectory(directory)) {
                LOG.info("Plugin directory " + directory + " does not exist; no plugins loaded");
            } else {
                Set<String> disabledIds = readDisabledIds(directory);

                try (Stream<Path> stream = Files.list(directory)) {
                    List<Path> jars = stream
                            .filter(file -> Files.isRegularFile(file) && file.getFileName() != null
                                    && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                            .toList();

                    for (Path jar : jars)
                        loadJar(jar, disabledIds);
                } catch (IOException e) {
                    LOG.warning("Failed to list plugin directory " + directory, e);
                }

                LOG.info("Loaded " + plugins.size() + " plugin(s): "
                        + plugins.stream().map(PluginHandle::getId).toList());
            }

            loaded = true;
        }

        firePluginsChanged();
    }

    // guarded by LOCK
    private static void loadJar(Path jar, Set<String> disabledIds) {
        PluginDescription description = null;
        HmclPlugin instance = null;
        String error = null;
        PluginHandle.State state = PluginHandle.State.FAILED;
        URLClassLoader classLoader = null;

        try {
            description = PluginDescription.readFromJar(jar);

            if (duplicateIdExists(description.getId())) {
                error = "Duplicate plugin id " + description.getId();
                description = null;
            } else {
                String minVersion = description.getMinLauncherVersion();
                if (minVersion != null && VersionNumber.compare(Metadata.VERSION, minVersion) < 0) {
                    state = PluginHandle.State.INCOMPATIBLE;
                    error = "Requires launcher " + minVersion + " or newer";
                } else {
                    classLoader = new URLClassLoader("hmcl-plugin-" + description.getId(),
                            new URL[] { jar.toUri().toURL() }, PluginManager.class.getClassLoader());
                    instance = instantiate(classLoader, description, jar);
                    state = PluginHandle.State.DISABLED;
                }
            }
        } catch (PluginFormatException e) {
            error = e.getMessage();
        } catch (Throwable e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warning("Failed to load plugin " + jar.getFileName(), e);
        }

        PluginHandle handle = new PluginHandle(jar, description, instance, state, error, classLoader);
        plugins.add(handle);

        if (handle.getState() == PluginHandle.State.DISABLED
                && !disabledIds.contains(handle.getId()))
            enableHandle(handle);
    }

    // guarded by LOCK
    private static boolean duplicateIdExists(String id) {
        return plugins.stream().anyMatch(existing -> existing.getId().equals(id));
    }

    // guarded by LOCK
    private static HmclPlugin instantiate(URLClassLoader classLoader, PluginDescription description, Path jar) throws Throwable {
        Class<?> entryClass = Class.forName(description.getEntry(), true, classLoader);
        if (!HmclPlugin.class.isAssignableFrom(entryClass))
            throw new PluginFormatException(
                    "Entry class " + description.getEntry() + " does not extend HmclPlugin");

        Constructor<?> constructor = entryClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        HmclPlugin plugin = (HmclPlugin) constructor.newInstance();
        Path pluginDirectory = jar.getParent() != null ? jar.getParent() : Path.of(".");
        // Plugins are trusted code, so the declared access level defaults to ROOT (see PluginAccess).
        plugin.attach(new PluginContext(description.getId(),
                pluginDirectory.resolve("data").resolve(description.getId()),
                description.getAccess()));
        return plugin;
    }

    /// Enables or disables the plugin with the given identifier and persists the choice.
    ///
    /// <p>Both transitions take effect immediately. Newly installed plugin jars are picked up
    /// by the next [PluginManager#reload].</p>
    ///
    /// @param id      the plugin identifier
    /// @param enabled `true` to enable the plugin, `false` to disable it
    /// @return `true` when the state was changed
    public static boolean setEnabled(String id, boolean enabled) {
        synchronized (LOCK) {
            for (PluginHandle handle : plugins) {
                if (!handle.getId().equals(id))
                    continue;

                boolean changed = enabled ? enableHandle(handle) : disableHandle(handle);
                if (changed && loadedDirectory != null)
                    saveDisabledIds(loadedDirectory);
                return changed;
            }
            return false;
        }
    }

    // guarded by LOCK
    private static boolean enableHandle(PluginHandle handle) {
        if (handle.getState() == PluginHandle.State.ENABLED)
            return false;
        HmclPlugin instance = handle.getInstance();
        if (instance == null)
            return false;

        try {
            instance.onEnable();
            handle.setState(PluginHandle.State.ENABLED);
            return true;
        } catch (Throwable e) {
            handle.setState(PluginHandle.State.FAILED);
            LOG.warning("Failed to enable plugin " + handle.getId(), e);
            return false;
        }
    }

    // guarded by LOCK
    private static boolean disableHandle(PluginHandle handle) {
        if (handle.getState() != PluginHandle.State.ENABLED)
            return false;

        HmclPlugin instance = handle.getInstance();
        if (instance != null) {
            try {
                instance.onDisable();
            } catch (Throwable e) {
                LOG.warning("Failed to disable plugin " + handle.getId(), e);
            }
        }
        handle.setState(PluginHandle.State.DISABLED);
        return true;
    }

    // guarded by LOCK
    private static void disableAll() {
        for (PluginHandle handle : plugins)
            disableHandle(handle);
    }

    // guarded by LOCK; disables every plugin and releases their jar file handles
    private static void discardAll() {
        disableAll();
        for (PluginHandle handle : plugins)
            handle.close();
    }

    /// Disables every plugin, reloads all plugin jars from the directory they were loaded
    /// from and enables the ones the user has not disabled.
    public static void reload() {
        Path directory;
        synchronized (LOCK) {
            directory = loadedDirectory != null ? loadedDirectory : getPluginsDirectory();
        }
        load(directory);
    }

    /// Installs a plugin jar into the plugin directory.
    ///
    /// <p>The manifest is validated first; a plugin whose identifier is already provided by
    /// another installed jar is rejected with a [PluginFormatException]. When the target file
    /// is currently loaded, that copy is unloaded so the file can be replaced even on Windows,
    /// where a loaded jar is locked. The change does not take effect until
    /// [PluginManager#reload] is called.</p>
    ///
    /// @param source the plugin jar to install
    /// @return the parsed description of the installed plugin
    /// @throws IOException when the jar is not a valid plugin or cannot be copied
    public static PluginDescription install(Path source) throws IOException {
        PluginDescription description = PluginDescription.readFromJar(source);

        Path sourceName = source.getFileName();
        String fileName = sourceName != null ? sourceName.toString() : "";
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new PluginFormatException("Not a plugin jar: " + fileName);

        synchronized (LOCK) {
            Path directory = loadedDirectory != null ? loadedDirectory : getPluginsDirectory();
            Path target = directory.resolve(fileName).toAbsolutePath().normalize();

            for (PluginHandle handle : plugins) {
                Path installedJar = handle.getJarPath().toAbsolutePath().normalize();
                if (!installedJar.equals(target) && handle.getId().equals(description.getId()))
                    throw new PluginFormatException("Plugin id " + description.getId()
                            + " is already provided by " + handle.getJarPath().getFileName());
            }

            Files.createDirectories(directory);

            // dropping the jar that is already installed is a no-op; a reload picks up any change
            if (!target.equals(source.toAbsolutePath().normalize())) {
                // unload the copy being replaced so Windows releases the file lock
                for (PluginHandle handle : plugins) {
                    if (handle.getJarPath().toAbsolutePath().normalize().equals(target)) {
                        disableHandle(handle);
                        handle.close();
                        plugins.remove(handle);
                        break;
                    }
                }

                try {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IOException("Failed to install plugin " + fileName + ": " + e.getMessage(), e);
                }

                saveDisabledIds(directory);
            }
        }

        firePluginsChanged();
        return description;
    }

    /// Deletes the jar file of the given plugin and unloads it.
    ///
    /// <p>The private data directory of the plugin (`plugins/data/<id>`) is kept, so
    /// reinstalling the plugin restores its saved state. The plugin is unloaded even when
    /// the file itself cannot be deleted.</p>
    ///
    /// @param handle the plugin to delete
    /// @throws IOException              when the jar file cannot be deleted
    /// @throws IllegalArgumentException when the plugin is not currently loaded
    public static void delete(PluginHandle handle) throws IOException {
        synchronized (LOCK) {
            if (!plugins.remove(handle))
                throw new IllegalArgumentException("Plugin is not loaded: " + handle.getId());

            disableHandle(handle);
            handle.close();

            if (loadedDirectory != null)
                saveDisabledIds(loadedDirectory);
        }

        firePluginsChanged();

        Files.deleteIfExists(handle.getJarPath());
    }

    /// Returns the currently known plugins sorted by identifier.
    public static List<PluginHandle> getPlugins() {
        synchronized (LOCK) {
            return plugins.stream()
                    .sorted(Comparator.comparing(PluginHandle::getId))
                    .toList();
        }
    }

    /// Returns the plugin with the given identifier, or `null` when it is not loaded.
    public static @Nullable PluginHandle getPlugin(String id) {
        synchronized (LOCK) {
            for (PluginHandle handle : plugins)
                if (handle.getId().equals(id))
                    return handle;
            return null;
        }
    }

    /// Returns `true` when [PluginManager#load] has been called at least once.
    public static boolean isLoaded() {
        synchronized (LOCK) {
            return loaded;
        }
    }

    /// Disables every plugin and releases their resources; called when the launcher exits.
    public static void shutdown() {
        synchronized (LOCK) {
            discardAll();
        }
    }

    /// Registers a listener invoked whenever the set of loaded plugins changes, i.e. after
    /// [PluginManager#load] finishes and after [PluginManager#install] or
    /// [PluginManager#delete]. Enabling or disabling a single plugin is not a structural
    /// change and does not notify listeners.
    ///
    /// <p>Listeners run on the thread performing the change; user interface code must
    /// marshal to the JavaFX thread itself.</p>
    public static void addChangeListener(Runnable listener) {
        synchronized (LOCK) {
            changeListeners.add(listener);
        }
    }

    /// Unregisters a listener previously passed to [PluginManager#addChangeListener].
    public static void removeChangeListener(Runnable listener) {
        synchronized (LOCK) {
            changeListeners.remove(listener);
        }
    }

    // invoked outside LOCK; a failing listener must not break the plugin manager
    private static void firePluginsChanged() {
        List<Runnable> listeners;
        synchronized (LOCK) {
            listeners = List.copyOf(changeListeners);
        }

        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable e) {
                LOG.warning("Plugin change listener failed", e);
            }
        }
    }

    /// Dispatches [HmclPlugin#onBeforeLaunch] to all enabled plugins.
    ///
    /// @param launchContext information about the pending launch
    public static void fireBeforeLaunch(LaunchContext launchContext) {
        for (PluginHandle handle : snapshotEnabled()) {
            try {
                // enabled handles always carry an instance
                handle.getInstance().onBeforeLaunch(launchContext);
            } catch (Throwable e) {
                LOG.warning("Plugin " + handle.getId() + " failed in onBeforeLaunch", e);
            }
        }
    }

    /// Dispatches [HmclPlugin#onAfterLaunch] to all enabled plugins.
    ///
    /// @param launchContext information about the launch
    /// @param process       the spawned game process
    public static void fireAfterLaunch(LaunchContext launchContext, ManagedProcess process) {
        for (PluginHandle handle : snapshotEnabled()) {
            try {
                // enabled handles always carry an instance
                handle.getInstance().onAfterLaunch(launchContext, process);
            } catch (Throwable e) {
                LOG.warning("Plugin " + handle.getId() + " failed in onAfterLaunch", e);
            }
        }
    }

    private static List<PluginHandle> snapshotEnabled() {
        synchronized (LOCK) {
            return plugins.stream()
                    .filter(handle -> handle.getState() == PluginHandle.State.ENABLED)
                    .toList();
        }
    }

    // guarded by LOCK
    private static Set<String> readDisabledIds(Path directory) {
        Path stateFile = directory.resolve(STATE_FILE_NAME);
        if (!Files.isRegularFile(stateFile))
            return Set.of();

        try {
            DisabledState state = JsonUtils.fromJson(Files.readString(stateFile), DisabledState.class);
            return state != null ? new HashSet<>(state.disabled) : Set.of();
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to read plugin state file " + stateFile, e);
            return Set.of();
        }
    }

    // guarded by LOCK
    private static void saveDisabledIds(Path directory) {
        List<String> disabled = plugins.stream()
                .filter(handle -> handle.getState() == PluginHandle.State.DISABLED)
                .map(PluginHandle::getId)
                .sorted()
                .toList();

        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(STATE_FILE_NAME),
                    JsonUtils.GSON.toJson(new DisabledState(disabled)));
        } catch (IOException e) {
            LOG.warning("Failed to write plugin state file in " + directory, e);
        }
    }

    /// The JSON model of the plugin state file.
    private static final class DisabledState {
        public List<String> disabled = new ArrayList<>();

        public DisabledState() {
        }

        public DisabledState(List<String> disabled) {
            this.disabled = new ArrayList<>(disabled);
        }
    }
}

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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Materialises the plugins shipped inside the launcher jar.
///
/// <h2>Why built-in plugins are jars on disk</h2>
/// <p>The plugin loader scans a directory and loads each jar through its own class loader.
/// Running a plugin straight out of the launcher jar would mean loading its classes on the
/// launcher's own loader, where they would clash with the launcher's own packages and could
/// never be unloaded or updated independently. Extracting the bundled jar into the plugin
/// directory keeps one code path: a built-in plugin is then indistinguishable from one the
/// user installed, and can be disabled, replaced or removed like any other.</p>
///
/// <h2>When they are (re)written</h2>
/// <p>Every launch, but only when the on-disk copy differs from the bundled one. Comparing
/// the bytes is what makes an update of the launcher also update its built-in plugin, while
/// a plain restart does no work. A user who deleted the jar gets it back, which is the
/// intended behaviour for something the launcher considers part of itself.</p>
@NotNullByDefault
public final class BundledPlugins {

    /// Classpath directory holding the bundled plugin jars.
    private static final String RESOURCE_DIRECTORY = "/assets/plugins/";

    /// The jar file names bundled with this build.
    ///
    /// <p>Listed explicitly rather than discovered: the classpath cannot be enumerated
    /// reliably from inside a jar, and a fixed list is also a compile-time reminder that
    /// adding a bundled plugin means adding its jar to the build.</p>
    private static final List<String> FILES = List.of(
            "org.dsh.download-hub_1.9.0.jar"
    );

    private BundledPlugins() {
    }

    /// Extracts every bundled plugin whose on-disk copy is missing or different.
    ///
    /// <p>Runs before [PluginManager#load] so the newly written jars are picked up in the
    /// same launch. Failures are logged and skipped: a bundled plugin that cannot be written
    /// must not stop the launcher from starting.</p>
    ///
    /// @param directory the plugin directory to write into
    public static void install(Path directory) {
        for (String fileName : FILES) {
            try {
                installOne(directory, fileName);
            } catch (Throwable e) {
                LOG.warning("Failed to install bundled plugin " + fileName, e);
            }
        }
    }

    private static void installOne(Path directory, String fileName) throws IOException {
        String resource = RESOURCE_DIRECTORY + fileName;
        try (InputStream bundled = BundledPlugins.class.getResourceAsStream(resource)) {
            if (bundled == null) {
                // The jar was built without its bundled plugins. Not fatal: the launcher works,
                // it just lacks them.
                LOG.warning("Bundled plugin resource is missing: " + resource);
                return;
            }

            byte[] content = bundled.readAllBytes();
            Path target = directory.resolve(fileName);
            if (isUpToDate(target, content))
                return;

            Files.createDirectories(directory);

            // Write beside the target and move it into place: a launcher that is killed while
            // writing would otherwise leave a truncated jar that fails to load on every start.
            Path temp = directory.resolve(fileName + ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

            LOG.info("Installed bundled plugin " + fileName);
        } finally {
            // Nothing to clean up beyond what the try-with-resources already closed.
        }
    }

    /// Whether the file on disk already holds exactly these bytes.
    ///
    /// <p>Size first, then content: the sizes differ in the common case where the launcher was
    /// updated, so the full comparison is only paid when it could matter.</p>
    private static boolean isUpToDate(Path target, byte[] content) throws IOException {
        if (!Files.isRegularFile(target))
            return false;
        if (Files.size(target) != content.length)
            return false;
        return java.util.Arrays.equals(Files.readAllBytes(target), content);
    }

    /// Names of the plugins bundled with this build, for the diagnostics log.
    public static List<String> bundledFileNames() {
        return FILES.stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
    }
}

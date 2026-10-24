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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end test of the plugin loader: compiles plugin jars at runtime, loads them through
/// [PluginManager] and verifies the lifecycle, the install/delete API and launch-event dispatch.
public class PluginManagerTest {

    private static final String MANIFEST = """
            {
              "id": "test-plugin",
              "name": "Test Plugin",
              "version": "1.0.0",
              "author": "HMCL",
              "description": "test",
              "entry": "org.hmcl.test.TestPlugin"
            }
            """;

    private static final String PLUGIN_SOURCE = """
            package org.hmcl.test;

            import org.jackhuang.hmcl.plugin.HmclPlugin;
            import org.jackhuang.hmcl.plugin.LaunchContext;

            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            public class TestPlugin extends HmclPlugin {
                @Override
                public void onEnable() throws Exception {
                    append("enabled");
                }

                @Override
                public void onDisable() {
                    append("disabled");
                }

                @Override
                public void onBeforeLaunch(LaunchContext context) {
                    append("beforeLaunch:" + context.getVersionId());
                }

                private void append(String line) {
                    try {
                        Path log = getContext().getDataDirectory().resolve("events.txt");
                        Files.createDirectories(log.getParent());
                        Files.writeString(log, line + "\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            """;

    @Test
    public void testPluginLifecycle(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        buildPluginJar(pluginsDir.resolve("test-plugin.jar"), MANIFEST, true);
        buildPluginJar(pluginsDir.resolve("broken-plugin.jar"), null, false); // no manifest

        PluginManager.load(pluginsDir);

        List<PluginHandle> plugins = PluginManager.getPlugins();
        assertEquals(2, plugins.size());

        PluginHandle plugin = PluginManager.getPlugin("test-plugin");
        assertNotNull(plugin);
        assertEquals(PluginHandle.State.ENABLED, plugin.getState());
        assertNotNull(plugin.getInstance());
        assertEquals("Test Plugin", plugin.getDescription().getName());

        PluginHandle broken = PluginManager.getPlugin("broken-plugin.jar");
        assertNotNull(broken);
        assertEquals(PluginHandle.State.FAILED, broken.getState());
        assertNull(broken.getDescription());
        assertNotNull(broken.getError());

        // onEnable side effect: the plugin appended to its private data directory
        Path events = pluginsDir.resolve("data").resolve("test-plugin").resolve("events.txt");
        assertTrue(Files.exists(events), "onEnable should have created the event file");
        assertEquals("enabled", firstLine(events));

        // launch event dispatch
        PluginManager.fireBeforeLaunch(new LaunchContext(null, "1.21.4", "1.21.4", tempDir, null));
        assertEquals("beforeLaunch:1.21.4", lastLine(events));

        // runtime disable persists the choice
        assertTrue(PluginManager.setEnabled("test-plugin", false));
        assertEquals(PluginHandle.State.DISABLED, PluginManager.getPlugin("test-plugin").getState());
        assertEquals("disabled", lastLine(events));
        assertTrue(Files.readString(pluginsDir.resolve("plugins.json")).contains("test-plugin"));

        // runtime enable works again
        assertTrue(PluginManager.setEnabled("test-plugin", true));
        assertEquals(PluginHandle.State.ENABLED, PluginManager.getPlugin("test-plugin").getState());
        assertEquals("enabled", lastLine(events));

        PluginManager.shutdown();
    }

    @Test
    public void testPluginInstallAndDelete(@TempDir Path tempDir) throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        PluginManager.load(pluginsDir);

        List<String> changes = new ArrayList<>();
        Runnable listener = () -> changes.add("changed");
        PluginManager.addChangeListener(listener);

        try {
            // an invalid jar is rejected without touching the plugin directory
            Path invalidJar = tempDir.resolve("invalid.jar");
            Files.writeString(invalidJar, "not a jar");
            assertThrows(IOException.class, () -> PluginManager.install(invalidJar));

            Path noManifestJar = tempDir.resolve("no-manifest.jar");
            buildPluginJar(noManifestJar, null, false); // valid zip, but no manifest
            assertThrows(PluginFormatException.class, () -> PluginManager.install(noManifestJar));

            Path wrongExtensionJar = tempDir.resolve("plugin.txt");
            buildPluginJar(wrongExtensionJar, MANIFEST, true);
            assertThrows(PluginFormatException.class, () -> PluginManager.install(wrongExtensionJar));

            // a valid jar is copied, but not loaded until reload
            Path sourceJar = tempDir.resolve("downloaded.jar");
            buildPluginJar(sourceJar, MANIFEST, true);

            PluginDescription description = PluginManager.install(sourceJar);
            assertEquals("test-plugin", description.getId());
            assertTrue(Files.exists(pluginsDir.resolve("downloaded.jar")));
            assertNull(PluginManager.getPlugin("test-plugin"));

            PluginManager.reload();
            PluginHandle plugin = PluginManager.getPlugin("test-plugin");
            assertNotNull(plugin);
            assertEquals(PluginHandle.State.ENABLED, plugin.getState());

            // a second jar providing the same id is refused
            Path duplicateJar = tempDir.resolve("duplicate.jar");
            buildPluginJar(duplicateJar, MANIFEST, true);
            assertThrows(PluginFormatException.class, () -> PluginManager.install(duplicateJar));
            assertFalse(Files.exists(pluginsDir.resolve("duplicate.jar")));

            // deleting unloads the plugin and removes the jar
            PluginManager.delete(plugin);
            assertFalse(Files.exists(pluginsDir.resolve("downloaded.jar")));
            assertNull(PluginManager.getPlugin("test-plugin"));
            assertTrue(PluginManager.getPlugins().isEmpty());

            // one notification for install(), reload() and delete() each; failed installs stay silent
            assertEquals(3, changes.size());
        } finally {
            PluginManager.removeChangeListener(listener);
            PluginManager.shutdown();
        }
    }

    private static String firstLine(Path file) throws Exception {
        try (Stream<String> lines = Files.readAllLines(file).stream()) {
            return lines.findFirst().orElse("");
        }
    }

    private static String lastLine(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        return lines.get(lines.size() - 1);
    }

    /// Compiles [PLUGIN_SOURCE] with the current test classpath and packs the classes
    /// (and optionally the manifest) into a plugin jar.
    private static void buildPluginJar(Path jarFile, String manifest, boolean withClasses) throws Exception {
        Path classesDir = jarFile.getParent().resolve("compiled-" + jarFile.getFileName());
        Files.createDirectories(classesDir);

        Path sourceFile = classesDir.resolve("TestPlugin.java");
        Files.writeString(sourceFile, PLUGIN_SOURCE);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test must run on a full JDK");

        Path hmclClasses = Path.of(HmclPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int result = compiler.run(null, null, null,
                "-encoding", "UTF-8", "-cp", hmclClasses.toString(),
                "-d", classesDir.toString(), sourceFile.toString());
        assertEquals(0, result, "test plugin must compile");

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            if (manifest != null) {
                zip.putNextEntry(new ZipEntry(PluginDescription.MANIFEST_FILE_NAME));
                zip.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (withClasses) {
                try (Stream<Path> stream = Files.walk(classesDir)) {
                    for (Path classFile : stream.filter(Files::isRegularFile)
                            .filter(file -> file.toString().endsWith(".class"))
                            .collect(Collectors.toList())) {
                        zip.putNextEntry(new ZipEntry(classesDir.relativize(classFile).toString().replace('\\', '/')));
                        zip.write(Files.readAllBytes(classFile));
                        zip.closeEntry();
                    }
                }
            }
        }
    }
}

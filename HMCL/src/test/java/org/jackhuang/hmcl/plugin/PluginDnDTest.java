package org.jackhuang.hmcl.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// The global drop handler routes by content: jars with a plugin manifest are plugins,
/// everything else (game mods above all) must reach the mod installer instead of dying in
/// "not a valid plugin".
public final class PluginDnDTest {

    @Test
    public void jarWithManifestIsAPlugin(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("demo-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("hmcl.plugin.json"));
            out.write("{\"id\":\"demo\",\"entry\":\"d.D\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("org/demo/D.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
        }
        assertTrue(PluginDnD.isPluginJar(jar), "a manifest makes it a plugin jar");
    }

    @Test
    public void gameModJarIsNotAPlugin(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("some-mod.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("mcmod.info"));
            out.write("[{\"modid\":\"demo\",\"name\":\"Demo\"}]".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("org/demo/Mod.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
        }
        assertFalse(PluginDnD.isPluginJar(jar), "a game mod must not be treated as a plugin");
    }

    @Test
    public void unreadableFileIsNotAPlugin(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("broken.jar");
        Files.writeString(file, "this is not a zip");
        assertFalse(PluginDnD.isPluginJar(file));
    }
}

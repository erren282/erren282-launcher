package org.jackhuang.hmcl.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// A manifest written on Windows often carries a UTF-8 BOM; Gson used to reject the whole
/// document, which made valid plugins "unrecognizable" on install.
public final class PluginDescriptionTest {

    private static final String MANIFEST = """
            {
              "id": "demo",
              "entry": "org.demo.DemoPlugin",
              "name": "演示插件",
              "version": "1.0.0"
            }
            """;

    @Test
    public void manifestWithUtf8BomIsAccepted(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("demo-plugin.jar");
        writePluginJar(jar, concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                MANIFEST.getBytes(StandardCharsets.UTF_8)));

        PluginDescription description = PluginDescription.readFromJar(jar);
        assertEquals("demo", description.getId());
        assertEquals("org.demo.DemoPlugin", description.getEntry());
        assertEquals("演示插件", description.getName());
    }

    @Test
    public void plainManifestIsAccepted(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("demo-plugin.jar");
        writePluginJar(jar, MANIFEST.getBytes(StandardCharsets.UTF_8));

        assertEquals("demo", PluginDescription.readFromJar(jar).getId());
    }

    @Test
    public void missingManifestIsRejected(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("empty.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("something.txt"));
            out.write("hi".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertThrows(PluginFormatException.class, () -> PluginDescription.readFromJar(jar));
    }

    private static void writePluginJar(Path jar, byte[] manifestBytes) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("hmcl.plugin.json"));
            out.write(manifestBytes);
            out.closeEntry();
            out.putNextEntry(new JarEntry("org/demo/DemoPlugin.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = new byte[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}

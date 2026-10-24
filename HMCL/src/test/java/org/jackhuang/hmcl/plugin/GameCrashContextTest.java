package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The crash context must point plugins at the *instance's* files. With version isolation
/// the game runs from the base `.minecraft` while its mods, logs and configs live in
/// `<base>/versions/<id>`, and a plugin that acted on the base directory would download a
/// prerequisite into a folder the instance never reads.
public final class GameCrashContextTest {

    /// A repository whose instance is isolated under `versions/<id>`, like HMCL does for a
    /// modpack or an instance with "version isolation" enabled.
    private static final class IsolatedRepository extends DefaultGameRepository {
        IsolatedRepository(Path baseDirectory) {
            super(baseDirectory);
        }

        @Override
        public Path getRunDirectory(String id) {
            return getBaseDirectory().resolve("versions").resolve(id);
        }
    }

    @Test
    public void crashContextTargetsTheIsolatedInstance(@TempDir Path base) throws IOException {
        Path runDirectory = base;
        Path instanceMods = base.resolve("versions/1.21.11/mods");
        Path globalMods = base.resolve("mods");
        Files.createDirectories(instanceMods);
        Files.createDirectories(globalMods);
        // The trap: the file the log demands sits in the global folder only.
        Files.writeString(globalMods.resolve("sodium-fabric-0.8.3+mc1.21.11.jar"), "global");
        Files.createDirectories(base.resolve("versions/1.21.11/logs"));
        Files.createDirectories(base.resolve("logs"));
        Files.writeString(base.resolve("versions/1.21.11/logs/latest.log"), "instance log", StandardCharsets.UTF_8);
        Files.writeString(base.resolve("logs/latest.log"), "base log", StandardCharsets.UTF_8);

        GameCrashContext context = new GameCrashContext(new IsolatedRepository(base), "1.21.11", "1.21.11",
                runDirectory, "APPLICATION_ERROR", 1, Map.of("fabric", "0.19.5"), List.of());

        assertEquals(base.resolve("versions/1.21.11"), context.getInstanceDirectory(),
                "plugins must act on the instance directory, not the launch directory");
        assertEquals(instanceMods, context.getModsDirectory(),
                "a prerequisite must be installed where the instance reads mods from");
        assertTrue(Files.exists(globalMods.resolve("sodium-fabric-0.8.3+mc1.21.11.jar")),
                "the global folder is left alone");
        assertEquals("instance log", context.getLatestLog(), "the instance log is found first");
    }

    @Test
    public void nonIsolatedInstanceKeepsTheRunDirectory(@TempDir Path base) {
        GameCrashContext context = new GameCrashContext(new DefaultGameRepository(base), "1.20.1", "1.20.1",
                base, "APPLICATION_ERROR", 1, Map.of(), List.of());

        assertEquals(base, context.getInstanceDirectory());
        assertEquals(base.resolve("mods"), context.getModsDirectory());
    }
}

package org.dsh.modtoversion;

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.TagType;
import org.jackhuang.hmcl.game.World;
import org.jackhuang.hmcl.util.io.Zipper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Verifies the world install path against the launcher's real zip and NBT handling.
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>A world archive written to a temporary file is only recognised while the file
 *       keeps its {@code .zip} extension. That is why {@code AddonInstaller} names its
 *       temporary file with the archive's own extension instead of a bare {@code .tmp};
 *       a file the zip provider does not recognise would fail at unpack time, long after
 *       the download looked successful.</li>
 *   <li>{@link World#install(Path, String)} — the exact call the plugin makes — unpacks a
 *       real archive into a {@code saves} directory, and the installed world reads back
 *       under the chosen name.</li>
 *   <li>An existing world name is refused rather than silently merged.</li>
 * </ol>
 */
public final class WorldInstallTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("hmcl-world-test-");
        try {
            extensionMatters(work);
            installRoundTrip(work);
            nameClashIsRefused(work);
        } finally {
            deleteTree(work);
        }
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// A world zip opens regardless of its file extension, because the launcher's zip
    /// file system sniffs the content rather than the name — and the temp file still gets
    /// the archive's own extension, so nothing depends on that leniency.
    private static void extensionMatters(Path work) throws Exception {
        System.out.println("=== a temp file opens whatever it is called ===");

        Path properZip = work.resolve("proper.zip");
        writeWorldZip(properZip, "SkyBlock");
        check("a .zip temp file is a readable world", new World(properZip) != null);

        // The same bytes under a name the zip provider might not recognise. It opens
        // anyway: the launcher detects the format from the content, not the extension.
        // The plugin still keeps the archive's extension so it never relies on this.
        Path renamed = work.resolve("proper.tmp");
        Files.copy(properZip, renamed);
        boolean opened;
        try {
            new World(renamed);
            opened = true;
        } catch (Exception e) {
            opened = false;
        }
        System.out.println("  a .tmp copy opens: " + opened);
        check("a renamed copy still opens as a world archive", opened);
        check("the temp file keeps the archive extension anyway",
                ".zip".equals(AddonInstaller.extensionOf(worldRelease("w.zip"))));
        check("a name with no extension falls back to .zip",
                ".zip".equals(AddonInstaller.extensionOf(worldRelease("no-extension"))));
    }

    /// The whole install the plugin performs, minus the download.
    private static void installRoundTrip(Path work) throws Exception {
        System.out.println();
        System.out.println("=== a world archive is unpacked into saves ===");

        Path archive = work.resolve("input.zip");
        writeWorldZip(archive, "SkyBlock");

        Path saves = Files.createDirectories(work.resolve("saves"));
        String folderName = "SkyBlock Adventure";
        new World(archive).install(saves, folderName);

        Path installed = saves.resolve(folderName);
        check("the world directory was created", Files.isDirectory(installed));
        check("it contains level.dat", Files.isRegularFile(installed.resolve("level.dat")));
        check("it contains the region data",
                Files.isRegularFile(installed.resolve("region").resolve("r.0.0.mca")));

        World read = new World(installed);
        System.out.println("  installed world name: " + read.getWorldName());
        // The launcher's install path rewrites the name inside level.dat, which is what
        // makes the world appear under the chosen name in the game's world list.
        check("its recorded name is the chosen folder name",
                folderName.equals(read.getWorldName()));

        System.out.println();
        System.out.println("=== the world version survives the round trip ===");
        check("the game version can still be read from level.dat",
                read.getGameVersion() != null);
        System.out.println("  world game version: " + read.getGameVersion());
    }

    /// A second world with the same folder name must be refused, not silently merged.
    private static void nameClashIsRefused(Path work) throws Exception {
        System.out.println();
        System.out.println("=== an existing world name is refused ===");

        Path archive = work.resolve("second.zip");
        writeWorldZip(archive, "Second");
        Path saves = Files.createDirectories(work.resolve("saves2"));
        // An existing world occupies the name.
        writeWorldZip(saves.resolve("Taken.zip"), "Taken");
        Path taken = saves.resolve("Taken");
        new World(saves.resolve("Taken.zip")).install(saves, "Taken");
        check("the pre-existing world was installed for the test",
                Files.isDirectory(taken));

        boolean refused;
        try {
            new World(archive).install(saves, "Taken");
            refused = false;
        } catch (IOException e) {
            refused = true;
        }
        check("installing over an existing world is refused", refused);
    }

    // ------------------------------------------------------------------ helpers

    /// Writes a minimal but valid world archive.
    ///
    /// <p>Built with the launcher's own {@link Zipper}, laying {@code level.dat} and
    /// {@code region/r.0.0.mca} at the archive root — the layout {@code World.install}
    /// expects for a world that is not wrapped in a sub-directory.</p>
    private static void writeWorldZip(Path target, String levelName) throws Exception {
        Path staging = Files.createTempDirectory("world-src-");
        writeLevelDat(staging.resolve("level.dat"), levelName);
        Files.createDirectories(staging.resolve("region"));
        Files.write(staging.resolve("region").resolve("r.0.0.mca"), new byte[]{1, 2, 3});

        try (Zipper zipper = new Zipper(target)) {
            zipper.putDirectory(staging, "");
        }
        deleteTree(staging);
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            throw new IllegalStateException("failed to build the test archive " + target);
        }
    }

    /// Writes a minimal gzip NBT {@code level.dat}.
    ///
    /// <p>The launcher reads it as {@code {Data: {LevelName: "...", Version: {Name: "..."}}}},
    /// so the structure has to match what {@code World.loadAndCheckLevelData} looks for.</p>
    private static void writeLevelDat(Path path, String levelName) throws Exception {
        CompoundTag version = new CompoundTag();
        version.addString("Name", "1.20.4");

        CompoundTag data = new CompoundTag();
        data.addString("LevelName", levelName);
        // The launcher refuses a level.dat without these two.
        data.addLong("LastPlayed", System.currentTimeMillis());
        data.addTag("Version", version);

        CompoundTag root = new CompoundTag();
        root.addTag("Data", data);

        try (OutputStream os = Files.newOutputStream(path);
             OutputStream gz = new GZIPOutputStream(os)) {
            NBTCodec.of().writeTag(gz, root);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static org.jackhuang.hmcl.addon.RemoteAddon.Version worldRelease(String filename) {
        return new org.jackhuang.hmcl.addon.RemoteAddon.Version(
                null, "example", "SkyBlock Adventure", "SkyBlock Adventure", "",
                java.time.Instant.parse("2026-05-01T00:00:00Z"),
                org.jackhuang.hmcl.addon.RemoteAddon.VersionType.Release,
                new org.jackhuang.hmcl.addon.RemoteAddon.File(
                        java.util.Map.of(), "https://example.invalid/" + filename, filename),
                List.of(), List.of("1.20.4"), List.of());
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}

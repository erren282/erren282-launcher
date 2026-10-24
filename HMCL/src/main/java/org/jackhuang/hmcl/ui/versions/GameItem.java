/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.versions;

import javafx.beans.property.*;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.GameInstanceCategory;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.modpack.ModpackConfiguration;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.download.LibraryAnalyzer.LibraryType.MINECRAFT;
import static org.jackhuang.hmcl.util.Lang.threadPool;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class GameItem {
    private static final ThreadPoolExecutor POOL_VERSION_RESOLVE = threadPool("VersionResolve", true, 1, 10, TimeUnit.SECONDS);

    protected final HMCLGameRepository repository;
    protected final String id;

    private boolean initialized = false;
    private StringProperty title;
    private StringProperty tag;
    private StringProperty subtitle;
    private ObjectProperty<Image> image;
    private ObjectProperty<GameInstanceCategory> category;

    public GameItem(HMCLGameRepository repository, String id) {
        this.repository = repository;
        this.id = id;
    }

    public GameDirectory getGameDirectory() {
        return repository.getGameDirectory();
    }

    public HMCLGameRepository getRepository() {
        return repository;
    }

    public String getId() {
        return id;
    }

    private void init() {
        if (initialized)
            return;

        initialized = true;
        title = new SimpleStringProperty();
        tag = new SimpleStringProperty();
        subtitle = new SimpleStringProperty();
        image = new SimpleObjectProperty<>();
        category = new SimpleObjectProperty<>(GameInstanceCategory.UNKNOWN);

        record Result(@Nullable String gameVersion, @Nullable String tag, GameInstanceCategory category) {
        }

        CompletableFuture.supplyAsync(() -> {
            // GameVersion.minecraftVersion() is a time-costing job (up to ~200 ms)
            Optional<String> gameVersion = repository.getGameVersion(id);
            String modPackVersion = null;
            try {
                ModpackConfiguration<?> config = repository.readModpackConfiguration(id);
                modPackVersion = config != null ? config.getVersion() : null;
            } catch (IOException e) {
                LOG.warning("Failed to read modpack configuration from " + id, e);
            }

            // Classification needs the resolved version too, which is similarly time-costing, so
            // it happens here on the background thread rather than on the JavaFX thread.
            GameInstanceCategory instanceCategory;
            try {
                Version withPatches = repository.getResolvedPreservingPatchesVersion(id);
                // The main class must come from the fully merged version: with patches preserved
                // the root carries no main class (it moved into the patch), which would make
                // instances identified by their main class (Lunar) look vanilla.
                String mainClass = repository.getResolvedVersion(id).getMainClass();
                instanceCategory = GameInstanceCategory.from(mainClass,
                        LibraryAnalyzer.analyze(withPatches, gameVersion.orElse(null)));
            } catch (Exception e) {
                LOG.warning("Failed to classify version " + id, e);
                instanceCategory = GameInstanceCategory.UNKNOWN;
            }

            return new Result(gameVersion.orElse(null), modPackVersion, instanceCategory);
        }, POOL_VERSION_RESOLVE).whenCompleteAsync((result, exception) -> {
            if (exception == null) {
                if (result.tag != null) {
                    tag.set(result.tag);
                }
                category.set(result.category);

                StringBuilder libraries = new StringBuilder(Objects.requireNonNullElse(result.gameVersion, i18n("message.unknown")));
                LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(repository.getResolvedPreservingPatchesVersion(id), result.gameVersion);
                for (LibraryAnalyzer.LibraryMark mark : analyzer) {
                    String libraryId = mark.getLibraryId();
                    String libraryVersion = mark.getLibraryVersion();
                    if (libraryId.equals(MINECRAFT.getPatchId())) continue;
                    if (I18n.hasKey("install.installer." + libraryId)) {
                        libraries.append(", ").append(i18n("install.installer." + libraryId));
                        if (libraryVersion != null)
                            libraries.append(": ").append(libraryVersion.replaceAll("(?i)" + libraryId, ""));
                    }
                }

                subtitle.set(libraries.toString());
            } else {
                LOG.warning("Failed to read version info from " + id, exception);
            }
        }, Schedulers.javafx());

        title.set(id);
        image.set(repository.getVersionIconImage(id));
    }

    public ReadOnlyStringProperty titleProperty() {
        init();
        return title;
    }

    public ReadOnlyStringProperty tagProperty() {
        init();
        return tag;
    }

    public ReadOnlyStringProperty subtitleProperty() {
        init();
        return subtitle;
    }

    public ReadOnlyObjectProperty<Image> imageProperty() {
        init();
        return image;
    }

    /// The client kind of this instance (mod loader, Lunar, vanilla), classified in the background.
    ///
    /// <p>It starts as [#GameInstanceCategory.UNKNOWN] and is replaced once classification finishes;
    /// listeners must tolerate that transition.</p>
    public ReadOnlyObjectProperty<GameInstanceCategory> categoryProperty() {
        init();
        return category;
    }
}

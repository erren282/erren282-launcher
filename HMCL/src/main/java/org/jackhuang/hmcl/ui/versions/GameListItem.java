/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import org.jackhuang.hmcl.game.GameInstanceCategory;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class GameListItem extends GameItem {
    private final boolean isModpack;
    private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected");

    /// Non-null when this row is a group header rather than an actual instance.
    private final @Nullable GameInstanceCategory headerCategory;
    private final ReadOnlyStringProperty headerTitle;

    public GameListItem(HMCLGameRepository repository, String id) {
        super(repository, id);
        this.isModpack = repository.isModpack(id);
        this.headerCategory = null;
        this.headerTitle = new SimpleStringProperty(this, "headerTitle", "");
        selected.bind(Bindings.createBooleanBinding(
                () -> repository.getGameDirectory() == GameDirectoryManager.getSelectedGameDirectory() && Objects.equals(repository.getSelectedInstance(), id),
                GameDirectoryManager.selectedGameDirectoryProperty(),
                GameDirectoryManager.selectedInstanceProperty()));
    }

    /// Creates a non-selectable header row introducing a group of `count` instances.
    private GameListItem(GameInstanceCategory category, int count) {
        super(null, "");
        this.isModpack = false;
        this.headerCategory = category;
        this.headerTitle = new SimpleStringProperty(this, "headerTitle",
                i18n(category.getTranslationKey()) + " (" + count + ")");
    }

    /// Creates a header row for the given category, shown when the list is grouped.
    public static GameListItem header(GameInstanceCategory category, int count) {
        return new GameListItem(category, count);
    }

    /// Whether this row is a group header instead of an actual game instance.
    public boolean isHeader() {
        return headerCategory != null;
    }

    /// The category this header introduces; only meaningful when [#isHeader()] is true.
    public @Nullable GameInstanceCategory getHeaderCategory() {
        return headerCategory;
    }

    /// The text of a group header row, including the instance count.
    public ReadOnlyStringProperty headerTitleProperty() {
        return headerTitle;
    }

    public ReadOnlyBooleanProperty selectedProperty() {
        return selected;
    }

    public void rename() {
        Versions.renameVersion(repository, id);
    }

    public void duplicate() {
        Versions.duplicateVersion(repository, id);
    }

    public void remove() {
        Versions.deleteVersion(repository, id);
    }

    public void export() {
        Versions.exportVersion(repository, id);
    }

    public void browse() {
        Versions.openFolder(repository, id);
    }

    public void testGame() {
        Versions.testGame(repository, id);
    }

    public void launch() {
        Versions.launch(repository, id);
    }

    public void modifyGameSettings() {
        Versions.modifyGameSettings(repository, id);
    }

    public void generateLaunchScript() {
        Versions.generateLaunchScript(repository, id);
    }

    public boolean canUpdate() {
        return isModpack;
    }

    public void update() {
        Versions.updateVersion(repository, id);
    }
}

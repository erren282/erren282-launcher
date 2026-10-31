/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.download;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.download.*;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.plugin.PluginAddonEvent;
import org.jackhuang.hmcl.plugin.PluginAddonService;
import org.jackhuang.hmcl.plugin.PluginDownloadTab;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.WeakListenerHolder;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.PromptDialogPane;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jackhuang.hmcl.ui.construct.Validator;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.versions.DownloadListPage;
import org.jackhuang.hmcl.ui.versions.HMCLLocalizedDownloadListPage;
import org.jackhuang.hmcl.ui.versions.VersionPage;
import org.jackhuang.hmcl.ui.versions.Versions;
import org.jackhuang.hmcl.ui.wizard.Navigation;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardProvider;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class DownloadPage extends DecoratorAnimatedPage implements DecoratorPage {
    public static final org.jackhuang.hmcl.ui.versions.DownloadPage.DownloadCallback FOR_MOD =
            (downloadProvider, repository, version, mod, file) -> download(downloadProvider, repository, version, file, "mods");
    public static final org.jackhuang.hmcl.ui.versions.DownloadPage.DownloadCallback FOR_RESOURCE_PACK =
            (downloadProvider, repository, version, pack, file) -> download(downloadProvider, repository, version, file, "resourcepacks");
    public static final org.jackhuang.hmcl.ui.versions.DownloadPage.DownloadCallback FOR_SHADER =
            (downloadProvider, repository, version, shader, file) -> download(downloadProvider, repository, version, file, "shaderpacks");
    /// Data packs, which go into a chosen world rather than into the instance directory.
    ///
    /// <p>A data pack is loaded per world: the game reads `<world>/datapacks/`, so dropping one
    /// into the instance folder does nothing. That is why this callback asks which world to
    /// install into instead of reusing the generic file download.</p>
    public static final org.jackhuang.hmcl.ui.versions.DownloadPage.DownloadCallback FOR_DATA_PACK =
            (downloadProvider, repository, version, pack, file) -> downloadDataPack(downloadProvider, repository, version, file);

    private final ReadOnlyObjectWrapper<DecoratorPage.State> state = new ReadOnlyObjectWrapper<>(DecoratorPage.State.fromTitle(i18n("download"), -1));
    private final TabHeader tab;
    private final TabHeader.Tab<VersionsPage> newGameTab = new TabHeader.Tab<>("newGameTab");
    private final TabHeader.Tab<DownloadListPage> modTab = new TabHeader.Tab<>("modTab");
    private final TabHeader.Tab<DownloadListPage> modpackTab = new TabHeader.Tab<>("modpackTab");
    private final TabHeader.Tab<DownloadListPage> resourcePackTab = new TabHeader.Tab<>("resourcePackTab");
    private final TabHeader.Tab<DownloadListPage> dataPackTab = new TabHeader.Tab<>("dataPackTab");
    private final TabHeader.Tab<DownloadListPage> shaderTab = new TabHeader.Tab<>("shaderTab");
    private final TabHeader.Tab<DownloadListPage> worldTab = new TabHeader.Tab<>("worldTab");
    private final TransitionPane transitionPane = new TransitionPane();
    private final DownloadNavigator versionPageNavigator = new DownloadNavigator();

    private WeakListenerHolder listenerHolder;

    public DownloadPage() {
        this(null);
    }

    public DownloadPage(String uploadVersion) {
        newGameTab.setNodeSupplier(loadVersionFor(() -> new VersionsPage(versionPageNavigator, i18n("install.installer.choose", i18n("install.installer.game")), "", DownloadProviders.getDownloadProvider(),
                "game", versionPageNavigator::onGameSelected)));
        modpackTab.setNodeSupplier(loadVersionFor(() -> {
            DownloadListPage page = HMCLLocalizedDownloadListPage.ofModPack((downloadProvider, repository, __, modpack, file) -> {
                Versions.downloadModpackImpl(downloadProvider, repository, uploadVersion, modpack, file);
            }, false);

            JFXButton installLocalModpackButton = FXUtils.newRaisedButton(i18n("install.modpack"));
            installLocalModpackButton.setOnAction(e -> Versions.importModpack());

            page.getActions().add(installLocalModpackButton);
            return page;
        }));
        modTab.setNodeSupplier(loadVersionFor(() -> HMCLLocalizedDownloadListPage.ofMod(FOR_MOD, true)));
        resourcePackTab.setNodeSupplier(loadVersionFor(() -> HMCLLocalizedDownloadListPage.ofResourcePack(FOR_RESOURCE_PACK, true)));
        dataPackTab.setNodeSupplier(loadVersionFor(() -> HMCLLocalizedDownloadListPage.ofDataPack(FOR_DATA_PACK, true)));
        shaderTab.setNodeSupplier(loadVersionFor(() -> HMCLLocalizedDownloadListPage.ofShaderPack(FOR_SHADER, true)));
        worldTab.setNodeSupplier(loadVersionFor(() -> new DownloadListPage(CurseForgeRemoteAddonRepository.WORLDS)));
        tab = new TabHeader(transitionPane, newGameTab, modpackTab, modTab, resourcePackTab, dataPackTab, shaderTab, worldTab);

        GameDirectoryManager.registerVersionsListener(this::loadVersions);

        tab.select(newGameTab);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("download.game").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, newGameTab, i18n("game"), SVG.STADIA_CONTROLLER, SVG.STADIA_CONTROLLER_FILL)
                .addNavigationDrawerTab(tab, modpackTab, i18n("modpack"), SVG.PACKAGE2, SVG.PACKAGE2_FILL)
                .startCategory(i18n("download.content").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, modTab, i18n("mods"), SVG.EXTENSION, SVG.EXTENSION_FILL)
                .addNavigationDrawerTab(tab, resourcePackTab, i18n("resourcepack"), SVG.TEXTURE)
                .addNavigationDrawerTab(tab, dataPackTab, i18n("datapack"), SVG.ARCHIVE, SVG.ARCHIVE_FILL)
                .addNavigationDrawerTab(tab, shaderTab, i18n("download.shader"), SVG.WB_SUNNY, SVG.WB_SUNNY_FILL)
                .addNavigationDrawerTab(tab, worldTab, i18n("world"), SVG.PUBLIC);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);

        // plugin-contributed tabs (e.g. server downloads) join the sidebar below the
        // built-in content entries and disappear when their plugin is disabled
        for (PluginDownloadTab pluginTab : PluginMenuService.getDownloadTabs())
            addPluginTab(pluginTab, sideBar);
        PluginMenuService.getDownloadTabs().addListener((ListChangeListener<PluginDownloadTab>) change -> {
            for (PluginDownloadTab pluginTab : PluginMenuService.getDownloadTabs())
                addPluginTab(pluginTab, sideBar);
            for (PluginDownloadTab staleTab : List.copyOf(pluginTabItems.keySet()))
                if (!PluginMenuService.getDownloadTabs().contains(staleTab))
                    removePluginTab(staleTab, sideBar);
        });

        setCenter(transitionPane);
    }

    private final Map<PluginDownloadTab, PluginTabEntry> pluginTabItems = new LinkedHashMap<>();

    /// Appends a plugin tab to the header and the sidebar unless it is already there.
    private void addPluginTab(PluginDownloadTab pluginTab, AdvancedListBox sideBar) {
        if (pluginTabItems.containsKey(pluginTab))
            return;

        TabHeader.Tab<Node> headerTab = new TabHeader.Tab<>("pluginTab:" + System.identityHashCode(pluginTab));
        headerTab.setNodeSupplier(pluginTab::createPage);
        tab.getTabs().add(headerTab);

        AdvancedListItem item = new AdvancedListItem();
        item.getStyleClass().add("navigation-drawer-item");
        item.setUserData(pluginTab);
        item.setTitle(pluginTab.getTitle());
        item.setLeftIcon(pluginTab.getIcon());
        item.activeProperty().bind(tab.getSelectionModel().selectedItemProperty().isEqualTo(headerTab));
        item.setOnAction(e -> tab.select(headerTab));
        sideBar.add(item);

        pluginTabItems.put(pluginTab, new PluginTabEntry(headerTab, item));
    }

    /// Removes the sidebar entry and header tab of a stale plugin tab.
    private void removePluginTab(PluginDownloadTab pluginTab, AdvancedListBox sideBar) {
        PluginTabEntry entry = pluginTabItems.remove(pluginTab);
        if (entry != null) {
            tab.getTabs().remove(entry.headerTab);
            sideBar.remove(entry.item);
        }
    }

    /// Selects the given plugin tab if it is registered; used by plugins that want
    /// to lead the user here (e.g. a "download a server" button on a plugin page).
    public void showPluginTab(PluginDownloadTab pluginTab) {
        PluginTabEntry entry = pluginTabItems.get(pluginTab);
        if (entry != null)
            tab.select(entry.headerTab, false);
    }

    private static final class PluginTabEntry {
        final TabHeader.Tab<Node> headerTab;
        final AdvancedListItem item;

        PluginTabEntry(TabHeader.Tab<Node> headerTab, AdvancedListItem item) {
            this.headerTab = headerTab;
            this.item = item;
        }
    }

    private static <T extends Node> Supplier<T> loadVersionFor(Supplier<T> nodeSupplier) {
        return () -> {
            T node = nodeSupplier.get();
            if (node instanceof VersionPage.GameInstanceLoadable loadable) {
                loadable.loadInstance(GameDirectoryManager.getSelectedRepository(), null);
            }
            return node;
        };
    }

    public static void download(DownloadProvider downloadProvider, HMCLGameRepository repository, @Nullable String version, RemoteAddon.Version file, String subdirectoryName) {
        if (version == null) version = repository.getSelectedInstance();

        // kept final so the download completion callback can report the target instance
        String instanceId = version;

        Path runDirectory = repository.hasVersion(version) ? repository.getRunDirectory(version) : repository.getBaseDirectory();

        Set<String> existingFiles;

        try (var list = Files.list(runDirectory.resolve(subdirectoryName))) {
            existingFiles = list.map(Path::getFileName)
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            LOG.warning("Failed to list files in " + runDirectory.resolve(subdirectoryName), e);
            existingFiles = Set.of();
        }

        Set<String> finalExistingFiles = existingFiles;

        Controllers.prompt(i18n("archive.file.name"), (result, handler) -> {
            Path dest = runDirectory.resolve(subdirectoryName).resolve(result);

            Controllers.taskDialog(Task.composeAsync(() -> {
                var task = new FileDownloadTask(downloadProvider.injectURLWithCandidates(file.file().url()), dest);
                task.setName(file.name());
                return task;
            }).whenComplete(Schedulers.javafx(), exception -> {
                if (exception != null) {
                    if (exception instanceof CancellationException) {
                        Controllers.showToast(i18n("message.cancelled"));
                    } else {
                        Controllers.dialog(DownloadProviders.localizeErrorMessage(exception), i18n("install.failed.downloading"), MessageDialogPane.MessageType.ERROR);
                    }
                } else {
                    Controllers.showToast(i18n("install.success"));
                    PluginAddonService.fireAddonDownloaded(new PluginAddonEvent(
                            downloadProvider, repository, instanceId, file, runDirectory.resolve(subdirectoryName)));
                }
            }), i18n("message.downloading"), TaskCancellationAction.NORMAL);
            handler.resolve();
        }, file.file().filename(), new Validator(i18n("install.new_game.malformed"), FileUtils::isNameValid), new Validator(i18n("game_directory.already_exists"), (it) -> !finalExistingFiles.contains(it)));

    }

    /// Installs a downloaded data pack into a world the user picks.
    ///
    /// <p>Unlike mods and resource packs, a data pack has no instance-level home: the game reads
    /// `<world>/datapacks/`, so it only takes effect once a world is chosen. Asking here is what
    /// makes the download actually do something; writing it to the instance directory instead
    /// would leave a file the game never looks at.</p>
    private static void downloadDataPack(DownloadProvider downloadProvider, HMCLGameRepository repository,
                                        @Nullable String requestedVersion, RemoteAddon.Version pack) {
        // Kept in its own final variable so the callback below can capture it: the parameter
        // itself is reassigned when null, which disqualifies it as effectively final.
        final String version = requestedVersion != null ? requestedVersion : repository.getSelectedInstance();

        Path runDirectory = repository.hasVersion(version)
                ? repository.getRunDirectory(version)
                : repository.getBaseDirectory();
        Path savesDirectory = runDirectory.resolve("saves");

        List<Path> worlds;
        try (var list = Files.list(savesDirectory)) {
            worlds = list.filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.warning("Failed to list worlds in " + savesDirectory, e);
            worlds = List.of();
        }

        if (worlds.isEmpty()) {
            Controllers.dialog(i18n("datapack.no_worlds"), i18n("datapack.download.title", pack.name()),
                    MessageDialogPane.MessageType.WARNING);
            return;
        }

        // `worlds` is reassigned in the failure branch above, so it cannot be captured directly;
        // the list the dialog works with is fixed at this point.
        final List<Path> worldList = worlds;

        // Folder names are what the launcher shows as world names in this context, and they are
        // what the install step needs anyway.
        String[] candidates = worldList.stream()
                .map(path -> path.getFileName().toString())
                .toArray(String[]::new);

        PromptDialogPane.Builder.CandidatesQuestion question =
                new PromptDialogPane.Builder.CandidatesQuestion(i18n("datapack.choose_world"), candidates);

        Controllers.prompt(new PromptDialogPane.Builder(i18n("datapack.download.title", pack.name()),
                (result, handler) -> {
                    Integer index = question.getValue();
                    if (index == null || index < 0 || index >= worldList.size()) {
                        handler.reject(i18n("datapack.choose_world.required"));
                        return;
                    }
                    handler.resolve();
                    startDataPackDownload(downloadProvider, repository, version, pack,
                            worldList.get(index).resolve("datapacks"));
                })
                .addQuestion(new PromptDialogPane.Builder.HintQuestion(i18n("datapack.choose_world.hint")))
                .addQuestion(question));
    }

    /// Downloads the pack into the chosen world's `datapacks` folder.
    ///
    /// <p>Split from the chooser so the task holds no dialog state: the prompt is modal and has
    /// already closed by the time `handler.resolve()` returns.</p>
    private static void startDataPackDownload(DownloadProvider downloadProvider, HMCLGameRepository repository,
                                              String version, RemoteAddon.Version pack, Path targetDirectory) {
        try {
            Files.createDirectories(targetDirectory);
        } catch (IOException e) {
            Controllers.dialog(i18n("datapack.failed.create_folder") + ": " + e.getMessage(),
                    i18n("datapack.download.title", pack.name()), MessageDialogPane.MessageType.ERROR);
            return;
        }

        // The pack keeps the file name the catalogue gave it: data pack zips are recognised by
        // their contents, but keeping the publisher's name makes the folder readable.
        String fileName = Path.of(pack.file().filename()).getFileName().toString();
        Path dest = targetDirectory.resolve(fileName);

        Controllers.taskDialog(Task.composeAsync(() -> {
            var task = new FileDownloadTask(downloadProvider.injectURLWithCandidates(pack.file().url()), dest);
            task.setName(pack.name());
            return task;
        }).whenComplete(Schedulers.javafx(), exception -> {
            if (exception != null) {
                if (exception instanceof CancellationException) {
                    Controllers.showToast(i18n("message.cancelled"));
                } else {
                    Controllers.dialog(DownloadProviders.localizeErrorMessage(exception),
                            i18n("install.failed.downloading"), MessageDialogPane.MessageType.ERROR);
                }
            } else {
                Controllers.showToast(i18n("install.success"));
                PluginAddonService.fireAddonDownloaded(new PluginAddonEvent(
                        downloadProvider, repository, version, pack, targetDirectory));
            }
        }), i18n("message.downloading"), TaskCancellationAction.NORMAL);
    }

    private void loadVersions(HMCLGameRepository repository) {
        listenerHolder = new WeakListenerHolder();
        runInFX(() -> {
            if (repository.getGameDirectory() == GameDirectoryManager.getSelectedGameDirectory()) {
                listenerHolder.add(FXUtils.onWeakChangeAndOperate(GameDirectoryManager.selectedInstanceProperty(), version -> {
                    if (modTab.isInitialized()) {
                        modTab.getNode().loadInstance(repository, null);
                    }
                    if (modpackTab.isInitialized()) {
                        modpackTab.getNode().loadInstance(repository, null);
                    }
                    if (resourcePackTab.isInitialized()) {
                        resourcePackTab.getNode().loadInstance(repository, null);
                    }
                    if (dataPackTab.isInitialized()) {
                        dataPackTab.getNode().loadInstance(repository, null);
                    }
                    if (shaderTab.isInitialized()) {
                        shaderTab.getNode().loadInstance(repository, null);
                    }
                    if (worldTab.isInitialized()) {
                        worldTab.getNode().loadInstance(repository, null);
                    }
                }));
            }
        });
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    public void showGameDownloads() {
        tab.select(newGameTab, false);
    }

    public void showModpackDownloads() {
        tab.select(modpackTab, false);
    }

    public DownloadListPage showResourcePackDownloads() {
        tab.select(resourcePackTab, false);
        return resourcePackTab.getNode();
    }

    public DownloadListPage showModDownloads() {
        tab.select(modTab, false);
        return modTab.getNode();
    }

    public void showWorldDownloads() {
        tab.select(worldTab, false);
    }

    private static final class DownloadNavigator implements Navigation {
        private final SettingsMap settings = new SettingsMap();

        @Override
        public void onStart() {

        }

        @Override
        public void onNext() {

        }

        @Override
        public void onPrev(boolean cleanUp) {
        }

        @Override
        public boolean canPrev() {
            return false;
        }

        @Override
        public void onFinish() {

        }

        @Override
        public void onEnd() {

        }

        @Override
        public void onCancel() {

        }

        @Override
        public SettingsMap getSettings() {
            return settings;
        }

        public void onGameSelected() {
            HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            if (repository.isLoaded()) {
                Controllers.getDecorator().startWizard(new VanillaInstallWizardProvider(repository, (GameRemoteVersion) settings.get("game")), i18n("install.new_game"));
            }
        }

    }

    private static class VanillaInstallWizardProvider implements WizardProvider {
        private final HMCLGameRepository repository;
        private final DefaultDependencyManager dependencyManager;
        private final DownloadProvider downloadProvider;
        private final GameRemoteVersion gameVersion;

        public VanillaInstallWizardProvider(HMCLGameRepository repository, GameRemoteVersion gameVersion) {
            this.repository = repository;
            this.gameVersion = gameVersion;
            this.downloadProvider = DownloadProviders.getDownloadProvider();
            this.dependencyManager = repository.getDependency(downloadProvider);
        }

        @Override
        public void start(SettingsMap settings) {
            settings.put(ModpackPage.GAME_DIRECTORY, repository.getGameDirectory());
            settings.put(ModpackPage.REPOSITORY, repository);
            settings.put(LibraryAnalyzer.LibraryType.MINECRAFT.getPatchId(), gameVersion);
        }

        private Task<Void> finishVersionDownloadingAsync(SettingsMap settings) {
            GameBuilder builder = dependencyManager.gameBuilder();

            String name = (String) settings.get("name");
            builder.name(name);
            builder.gameVersion(((RemoteVersion) settings.get(LibraryAnalyzer.LibraryType.MINECRAFT.getPatchId())).getGameVersion());

            settings.asStringMap().forEach((key, value) -> {
                if (!LibraryAnalyzer.LibraryType.MINECRAFT.getPatchId().equals(key)
                        && value instanceof RemoteVersion remoteVersion)
                    builder.version(remoteVersion);
            });

            repository.applyDefaultIsolationSettingForNewInstance(name, settings.isInstallingModdedVersion());
            return builder.buildAsync().whenComplete(any -> {
                repository.refreshVersions();
            }).thenRunAsync(Schedulers.javafx(), () -> repository.setSelectedInstance(name));
        }

        @Override
        public Object finish(SettingsMap settings) {
            settings.put("title", i18n("install.new_game.installation"));
            settings.put("success_message", i18n("install.success"));
            settings.put(FailureCallback.KEY, (settings1, exception, next) -> UpdateInstallerWizardProvider.alertFailureMessage(exception, next));

            return finishVersionDownloadingAsync(settings);
        }

        @Override
        public Node createPage(WizardController controller, int step, SettingsMap settings) {
            switch (step) {
                case 0:
                    return new InstallersPage(controller, repository, ((RemoteVersion) controller.getSettings().get("game")).getGameVersion(), downloadProvider);
                default:
                    throw new IllegalStateException("error step " + step + ", settings: " + settings + ", pages: " + controller.getPages());
            }
        }

        @Override
        public boolean cancel() {
            return true;
        }
    }
}

package org.jackhuang.hmcl.plugin;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.download.ModpackInstallWizardProvider;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.System.Logger.Level;
import java.util.zip.ZipFile;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Drag and drop routing for files dropped onto the launcher window.
///
/// <p>The launcher accepts several kinds of drops, so this handler <em>routes</em> instead
/// of intercepting:</p>
/// <ul>
///   <li>a jar with a plugin manifest ({@code hmcl.plugin.json}) → installed as a launcher
///       plugin;</li>
///   <li>a drop that lands on a page with its own drop handler (the mod list, the theme
///       page, the Java page, the terracotta page…) → passed through untouched;</li>
///   <li>anywhere else → a modpack opens the install wizard, a game mod installs into the
///       selected instance, and anything else is ignored (as it always was).</li>
/// </ul>
///
/// <p>Previously this handler consumed every {@code .jar} drag for plugins, which starved
/// the mod pages: dropping a game mod ended in "not a valid plugin" and installed nothing.</p>
///
/// @see PluginInstaller
@NotNullByDefault
public final class PluginDnD {

    private static final String MANIFEST_FILE_NAME = "hmcl.plugin.json";

    private PluginDnD() {
    }

    /// Returns the jar files carried by the dragboard, ignoring everything else.
    ///
    /// @param dragboard the dragboard of the drag event
    /// @return the dropped jar files; empty when the drag carries no jars
    public static List<Path> parseJarsFromDragboard(Dragboard dragboard) {
        if (!dragboard.hasFiles())
            return List.of();

        List<Path> jars = new ArrayList<>();
        for (File file : dragboard.getFiles()) {
            Path path = file.toPath();
            Path fileName = path.getFileName();
            if (fileName != null
                    && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    && Files.isRegularFile(path)) {
                jars.add(path);
            }
        }
        return jars;
    }

    /// True when the jar carries a plugin manifest ({@code hmcl.plugin.json}).
    public static boolean isPluginJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(MANIFEST_FILE_NAME) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /// True when the drop target (or one of its ancestors) has its own drop handler —
    /// such a page knows better than the global router what to do with these files.
    private static boolean targetHandlesDrops(DragEvent event) {
        Node node = event.getTarget() instanceof Node n ? n : null;
        while (node != null) {
            if (node.getOnDragDropped() != null)
                return true;
            node = node.getParent();
        }
        return false;
    }

    /// Accepts drag gestures carrying files so the cursor offers a copy. The event is
    /// deliberately not consumed: page-level drop handlers keep seeing the gesture.
    public static EventHandler<DragEvent> dragOverHandler() {
        return event -> {
            Dragboard board = event.getDragboard();
            if (event.getGestureSource() != event.getTarget() && board.hasFiles())
                event.acceptTransferModes(TransferMode.COPY);
        };
    }

    /// Routes the dropped files as described in the class documentation.
    public static EventHandler<DragEvent> dragDroppedHandler() {
        return event -> {
            List<Path> jars = parseJarsFromDragboard(event.getDragboard());
            if (jars.isEmpty())
                return;

            List<Path> plugins = new ArrayList<>();
            List<Path> others = new ArrayList<>();
            for (Path jar : jars) {
                if (isPluginJar(jar))
                    plugins.add(jar);
                else
                    others.add(jar);
            }

            // Non-plugin files dropped onto a page that handles drops itself (the mod list,
            // the theme page, the Java page…) belong to that page; hand the event back.
            if (plugins.isEmpty() && targetHandlesDrops(event))
                return;

            if (!plugins.isEmpty())
                PluginInstaller.installWithToast(plugins);

            if (!others.isEmpty())
                installLooseFiles(others);

            event.setDropCompleted(true);
            event.consume();
        };
    }

    /// Installs files dropped onto the window itself: modpacks through the install wizard,
    /// game mods into the selected instance's mods folder. Files nothing recognizes are
    /// ignored, matching the launcher's behavior before plugins existed.
    private static void installLooseFiles(List<Path> files) {
        List<Path> mods = new ArrayList<>();
        for (Path file : files) {
            if (ModpackHelper.isFileModpackByExtension(file)) {
                Controllers.getDecorator().startWizard(
                        new ModpackInstallWizardProvider(GameDirectoryManager.getSelectedRepository(), file),
                        i18n("install.modpack"));
            } else if (ModManager.MOD_EXTENSIONS.contains(
                    FileUtils.getExtension(file.getFileName().toString()).toLowerCase(Locale.ROOT))) {
                mods.add(file);
            }
        }
        if (mods.isEmpty())
            return;

        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        String instance = GameDirectoryManager.getSelectedInstance();
        if (repository == null || instance == null || instance.isBlank()) {
            Controllers.dialog(i18n("plugin.drop.no.instance"), i18n("message.error"), MessageType.WARNING);
            return;
        }

        new Thread(() -> {
            int count = 0;
            ModManager modManager = new ModManager(repository, instance);
            for (Path mod : mods) {
                try {
                    modManager.addMod(mod);
                    count++;
                } catch (IOException | IllegalArgumentException e) {
                    LOG.log(Level.WARNING, "Unable to install dropped mod " + mod, e);
                }
            }
            final int installed = count;
            if (installed > 0) {
                Platform.runLater(() -> Controllers.showToast(
                        i18n("plugin.drop.mods.installed", installed, instance)));
            } else {
                Platform.runLater(() -> Controllers.dialog(
                        i18n("plugin.drop.mods.failed"), i18n("message.error"), MessageType.ERROR));
            }
        }, "plugin-dnd-mods").start();
    }
}

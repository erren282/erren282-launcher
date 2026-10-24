package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.World;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.Validator;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Installs one release into one installed game version.
 *
 * <h2>Two different kinds of addon</h2>
 * <p>A mod, resource pack or shader pack is a single file that belongs in a directory
 * of the instance, so the launcher's own
 * {@code DownloadPage.download(..., subdirectoryName)} is reused unchanged: the file
 * lands where the launcher would put it, it asks for a file name the same way, and its
 * post-download handling (including the plugin addon-downloaded event) still runs.</p>
 *
 * <p>A world is not a file in a folder — the game only sees a world stored as
 * {@code saves/<world name>/}, so the downloaded archive has to be unpacked and
 * renamed. That is what the launcher does when a world archive is dragged in, and this
 * mirrors it: download to a temporary file, then install it through
 * {@link World#install(Path, String)}.</p>
 */
final class AddonInstaller {

    private AddonInstaller() {
    }

    /// Installs {@code release} into the instance {@code instanceId} of {@code repository}.
    ///
    /// @param kind         the download section, deciding how the addon is installed
    /// @param downloadProvider the provider the launcher is configured to use
    /// @param repository   the repository the target instance belongs to
    /// @param instanceId   the target instance
    /// @param release      the release to install
    /// @param log          where to report what happened
    static void install(AddonKind kind, DownloadProvider downloadProvider,
                        HMCLGameRepository repository, String instanceId,
                        RemoteAddon.Version release, Reporter log) {
        if (kind.isWorld()) {
            installWorld(downloadProvider, repository, instanceId, release, log);
        } else {
            installFile(kind, downloadProvider, repository, instanceId, release, log);
        }
    }

    /// Receives progress messages so the plugin can log them with its own prefix.
    interface Reporter {
        /// A step completed or is starting.
        void info(String message);

        /// Something went wrong; the user has already been told.
        void error(String message, Throwable throwable);
    }

    // --------------------------------------------------------------- file addons

    /// Installs a mod, resource pack or shader pack through the launcher's downloader.
    private static void installFile(AddonKind kind, DownloadProvider downloadProvider,
                                    HMCLGameRepository repository, String instanceId,
                                    RemoteAddon.Version release, Reporter log) {
        String subdirectory = kind.directoryName();
        if (subdirectory == null) {
            // Only the world path should reach here; guard rather than fail obscurely.
            log.error("分区 " + kind.name() + " 没有对应的安装目录", null);
            return;
        }
        try {
            org.jackhuang.hmcl.ui.download.DownloadPage.download(
                    downloadProvider, repository, instanceId, release, subdirectory);
            log.info("正在把「" + release.name() + "」下载到「" + instanceId + "」的 "
                    + subdirectory + " 目录");
        } catch (Throwable t) {
            log.error("下载到指定版本失败", t);
            Controllers.dialog("下载失败：" + t, "下载失败",
                    MessageDialogPane.MessageType.ERROR);
        }
    }

    // -------------------------------------------------------------------- worlds

    /// Downloads a world archive and unpacks it into the instance's {@code saves}.
    ///
    /// <p>The world's folder name becomes its name in the game's world list, so it is
    /// asked for up front — the same question the launcher asks when a world archive is
    /// added by hand. Asking first also lets a clash with an existing world be reported
    /// before anything is downloaded.</p>
    private static void installWorld(DownloadProvider downloadProvider,
                                     HMCLGameRepository repository, String instanceId,
                                     RemoteAddon.Version release, Reporter log) {
        Path savesDirectory;
        try {
            savesDirectory = repository.getSavesDirectory(instanceId);
        } catch (Throwable t) {
            log.error("无法确定「" + instanceId + "」的 saves 目录", t);
            Controllers.dialog("无法确定这个版本的 saves 目录。", "无法下载",
                    MessageDialogPane.MessageType.ERROR);
            return;
        }

        Controllers.prompt(i18n("world.name.enter"), (name, handler) -> {
            Path archive;
            try {
                archive = Files.createTempFile("hmcl-world-", extensionOf(release));
            } catch (IOException e) {
                handler.reject(i18n("world.add.failed", e.getLocalizedMessage()));
                return;
            }

            Task<Void> task = Task.composeAsync(() -> {
                FileDownloadTask download = new FileDownloadTask(
                        downloadProvider.injectURLWithCandidates(release.file().url()),
                        archive, release.file().getIntegrityCheck());
                download.setName(release.name());
                return download;
            }).thenRunAsync(Schedulers.io(), () -> {
                // Unpacking reads the archive and writes a whole directory tree, so it
                // must not run on the JavaFX thread.
                try {
                    new World(archive).install(savesDirectory, name);
                } finally {
                    deleteQuietly(archive);
                }
            }).whenComplete(Schedulers.javafx(), exception -> {
                if (exception == null) {
                    Controllers.showToast("已把「" + release.name() + "」安装到「" + instanceId + "」");
                    log.info("已把世界「" + release.name() + "」解压安装到 " + instanceId
                            + " 的 saves/" + name);
                } else {
                    log.error("安装世界到指定版本失败", exception);
                    Controllers.dialog("安装失败：" + exception, "安装失败",
                            MessageDialogPane.MessageType.ERROR);
                }
            });

            Controllers.taskDialog(task, i18n("message.downloading"),
                    TaskCancellationAction.NORMAL);
            handler.resolve();
            log.info("正在把世界「" + release.name() + "」下载到「" + instanceId
                    + "」的 saves/" + name);
        }, suggestedWorldName(release),
                new Validator(i18n("install.new_game.malformed"), FileUtils::isNameValid),
                new Validator(i18n("world.add.already_exists"),
                        candidate -> !Files.exists(savesDirectory.resolve(candidate))));
    }

    /// The name to pre-fill the rename prompt with: the release name, stripped of
    /// characters that cannot appear in a folder name.
    ///
    /// <p>World names become folder names, so {@code / \ : * ? " < > |} have to go; the
    /// user can still edit the result.</p>
    static String suggestedWorldName(RemoteAddon.Version release) {
        if (release == null || release.name() == null) {
            return "";
        }
        String cleaned = release.name().replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        // Collapse the runs of spaces the replacement can leave behind.
        cleaned = cleaned.replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? "world" : cleaned;
    }

    /// The extension of the release's file, dot included, defaulting to {@code .zip}.
    ///
    /// <p>The temporary file has to keep the archive's extension: the zip file system
    /// provider opens by path, and world archives are zips whatever they are named.</p>
    static String extensionOf(RemoteAddon.Version release) {
        if (release == null || release.file() == null || release.file().filename() == null) {
            return ".zip";
        }
        String filename = release.file().filename();
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".zip";
        }
        String extension = filename.substring(dot);
        // Guard against a nonsense "extension" that would break the temp file name.
        return extension.length() > 10 || extension.indexOf('/') >= 0 ? ".zip" : extension;
    }

    /// Deletes a temporary file, ignoring failures.
    ///
    /// <p>Failing to delete a temp file is not worth bothering the user about, but it is
    /// logged so a leak can be diagnosed.</p>
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warning("Failed to delete temporary file " + file, e);
        }
    }
}

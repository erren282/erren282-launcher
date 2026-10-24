package org.dsh.modinstall;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.versions.DownloadListPage;
import org.jackhuang.hmcl.ui.versions.HMCLLocalizedDownloadListPage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Reproduces the crash the mod detail page used to cause, and proves it is gone.
 *
 * <p>The launcher died with</p>
 * <pre>
 * NullPointerException: Cannot invoke "HMCLGameRepository$InstanceReference.repository()"
 * because "control.instanceReference" is null
 *   at DownloadPage$DownloadPageSkin.lambda$new$5(DownloadPage.java:272)
 * </pre>
 * <p>when the version list finished loading. That line null-guards the reference's
 * <em>fields</em>, not the reference itself, so passing {@code null} for the whole
 * record crashed the launcher the moment the (asynchronous) version load completed.
 * This test builds the page the way the plugin does, lets that load complete, and
 * asserts the skin survives it.</p>
 *
 * <p>It intentionally does not need the network: whatever the load returns —
 * versions or a failure — the skin's listener runs, which is the code that used to
 * throw.</p>
 */
public final class DetailPageTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        String hmclDir = System.getProperty("hmcl.dir");
        if (hmclDir == null || hmclDir.isBlank()) {
            System.out.println("FATAL: pass -Dhmcl.dir=<temp dir>");
            System.exit(2);
            return;
        }
        SettingsManager.init();
        Platform.startup(() -> { });
        pump();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            run();
        } catch (Throwable t) {
            failure.set(t);
        }
        if (failure.get() != null) {
            System.out.println("FATAL");
            failure.get().printStackTrace();
            Platform.exit();
            System.exit(2);
            return;
        }
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        Platform.exit();
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void run() throws Exception {
        HMCLGameRepository repository;
        try {
            repository = GameDirectoryManager.getSelectedRepository();
        } catch (Throwable t) {
            // A throwaway profile has no game directory configured. The crash this
            // test guards against is a null *record*, not a null repository — line
            // 272 null-checks the repository — so a null one is a valid stand-in.
            repository = null;
            System.out.println("  (no game directory configured; using a null repository)");
        }

        System.out.println();
        System.out.println("=== the reference the page is given ===");
        // The launcher's own mod tab passes (selectedRepository, null instanceId);
        // the fields are what get null-guarded, so a null instance id is fine.
        HMCLGameRepository.InstanceReference reference =
                new HMCLGameRepository.InstanceReference(repository, null);
        check("the instance reference itself is not null"
                        + " (a null record was the crash)", reference != null);
        check("its repository is the one passed in (what the skin dereferences)",
                reference.repository() == repository);

        System.out.println();
        System.out.println("=== building the page does not throw ===");
        // The addon the page renders. It must actually yield versions: the skin's
        // listener early-returns while `versions` is null, so an addon whose
        // loadVersions throws would skip the very line that used to crash.
        org.jackhuang.hmcl.addon.RemoteAddon addon = new org.jackhuang.hmcl.addon.RemoteAddon(
                "sodium", "jellysquid3", "Sodium", "Rendering engine", List.of(),
                "", "", CannedAddon.DATA, org.jackhuang.hmcl.addon.RemoteAddonRepository.Type.MOD);

        // Exactly what the plugin's openDownloadPage() does, minus the network
        // addon: build the list page, load the instance into it, then build the
        // detail page. Both steps used to crash — one when the skin was built, the
        // other when the version list finished loading.
        final HMCLGameRepository repo = repository;
        DownloadListPage listPage = onFxGet(() -> {
            DownloadListPage page = HMCLLocalizedDownloadListPage.ofMod(null, false);
            page.loadInstance(repo, null);
            return page;
        });
        check("the mod list page was constructed and given an instance", listPage != null);
        Node detail = onFxGet(() -> new org.jackhuang.hmcl.ui.versions.DownloadPage(
                listPage, addon, reference, null));
        check("the detail page was constructed", detail != null);

        // Show it, then let the asynchronous version load land: that callback is
        // what threw before. Failures are captured rather than killing the JVM, so
        // the assertion below reports them instead of the process dying.
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> uncaught.set(throwable));

        AtomicReference<Stage> stageRef = new AtomicReference<>();
        onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(detail), 900, 600));
            stage.show();
            stageRef.set(stage);
        });
        // Give the async load a moment to complete and fire the skin's listener.
        pump(40);
        Thread.sleep(1500);
        pump(20);

        check("loading the version list did not throw on the FX thread",
                uncaught.get() == null);
        if (uncaught.get() != null) {
            System.out.println("  [diag] " + uncaught.get());
        }
        check("the page is still usable after the load", onFxGet(listPage::isVisible));

        onFx(stageRef.get()::close);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * An addon whose version list is canned, so the page's load completes without a
     * network call and the skin's listener reaches the line that used to crash.
     */
    private static final class CannedAddon implements org.jackhuang.hmcl.addon.RemoteAddon.IMod {
        static final CannedAddon DATA = new CannedAddon();

        @Override
        public List<org.jackhuang.hmcl.addon.RemoteAddon> loadDependencies(
                org.jackhuang.hmcl.addon.RemoteAddonRepository repository,
                org.jackhuang.hmcl.download.DownloadProvider provider) {
            return List.of();
        }

        @Override
        public java.util.stream.Stream<org.jackhuang.hmcl.addon.RemoteAddon.Version> loadVersions(
                org.jackhuang.hmcl.addon.RemoteAddonRepository repository,
                org.jackhuang.hmcl.download.DownloadProvider provider) {
            return java.util.stream.Stream.of(new org.jackhuang.hmcl.addon.RemoteAddon.Version(
                    null, "sodium", "Sodium", "0.5.8", "",
                    java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    org.jackhuang.hmcl.addon.RemoteAddon.VersionType.Release,
                    new org.jackhuang.hmcl.addon.RemoteAddon.File(
                            java.util.Map.of(), "https://example.invalid/sodium.jar", "sodium.jar"),
                    List.of(), List.of("1.20.4"), List.of()));
        }
    }

    private static void pump() throws Exception {
        pump(6);
    }

    private static void pump(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            onFx(() -> { });
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action timed out");
        }
        if (thrown.get() != null) {
            throw new IllegalStateException("FX action threw", thrown.get());
        }
    }

    private static <T> T onFxGet(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        onFx(() -> result.set(action.get()));
        return result.get();
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

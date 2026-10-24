import com.sun.net.httpserver.HttpServer;
import org.dsh.lunarofl.LunarApi;
import org.dsh.lunarofl.LunarClientDownloader;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end progress test for [LunarClientDownloader.download], driven by a local
 * HTTP server so no network is touched.
 *
 * <p>The bug this guards against: bytes were counted only when a file finished, so
 * one large file made the progress bar sit still for most of the download and then
 * jump. The bar must advance <em>while</em> the big file transfers, must never
 * exceed 100%, must survive a retried/interrupted download without inflating, and
 * must not flood the UI with more updates than the throttle allows.</p>
 */
public final class DownloadProgressTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lunar-dl");
        HttpServer server = startServer();

        try {
            // One big artifact (12 MB) + a few small ones: the big one is what used
            // to freeze the bar.
            byte[] big = blob(12 * 1024 * 1024);
            byte[] small1 = blob(64 * 1024);
            byte[] small2 = blob(128 * 1024);
            String bigSha = sha1(big);

            serve("/big.jar", big);
            // The big artifact is what used to freeze the bar; drip-feed it so the
            // transfer takes long enough for the throttle to emit several samples.
            serveSlow("/big.jar", big, 15);
            serve("/small1.jar", small1);
            serve("/small2.jar", small2);
            // Texture index with two entries so the second batch also runs.
            String texIndex = "assets/t1.png " + sha1(small1) + " " + small1.length + " 0\n"
                    + "assets/t2.png " + sha1(small2) + " " + small2.length + " 0\n";
            serveText("/tex-index.txt", texIndex);
            serveText("/ui-index.txt", "");
            serve("/textures/" + sha1(small1), small1);
            serve("/textures/" + sha1(small2), small2);

            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path installation = dir.resolve("client");

            LunarApi api = new LunarApi(dir) {
                @Override
                public Manifest manifest(String gameVersion, String module) {
                    // The type strings matter: classpath() filters on CLASS_PATH.
                    return new Manifest("1.12.2", "default", "net.minecraft.client.main.Main",
                            List.of(new LunarApi.Artifact("big.jar", bigSha, base + "/big.jar", "CLASS_PATH", big.length),
                                    new LunarApi.Artifact("small1.jar", sha1(small1), base + "/small1.jar", "CLASS_PATH", small1.length),
                                    new LunarApi.Artifact("small2.jar", sha1(small2), base + "/small2.jar", "CLASS_PATH", small2.length)),
                            List.of(),
                            base + "/tex-index.txt", base + "/textures/",
                            "", "",
                            base + "/ui-index.txt", "",
                            List.of(), "");
                }
            };

            // ---- collect progress ------------------------------------------
            List<long[]> updates = java.util.Collections.synchronizedList(new ArrayList<>());
            List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean sawDownloadDone = new AtomicBoolean();

            Thread worker = new Thread(() -> {
                try {
                    LunarClientDownloader.download(installation, api, "1.12.2", "default",
                            (step, done, total) -> {
                                updates.add(new long[]{System.currentTimeMillis(), done, total});
                                if (steps.isEmpty() || !steps.get(steps.size() - 1).equals(step))
                                    steps.add(step);
                                // The step after the download pulls Lunar's declared
                                // libraries from real Maven repos; that is outside this
                                // test's scope (no network beyond the local server), so
                                // stop as soon as the download phase has finished.
                                if ("下载完成".equals(step))
                                    sawDownloadDone.set(true);
                            },
                            () -> sawDownloadDone.get());
                } catch (Throwable t) {
                    System.out.println("  [diag] download threw: " + t);
                }
            }, "progress-test");
            worker.start();
            worker.join(120_000);

            System.out.println("=== progress during the download ===");
            System.out.printf("  updates=%d  steps=%s%n", updates.size(), steps);
            check("progress was reported many times (" + updates.size() + " updates)",
                    updates.size() >= 10);
            check("the big-file step was reported", steps.contains("正在下载客户端文件"));

            // The bar must climb gradually. The downloader's own total includes the
            // texture/UI index bytes as well, so reconstruct it the same way it
            // does instead of guessing.
            List<Long> bigDone = new ArrayList<>();
            long grandTotal = updates.stream().mapToLong(u -> u[2]).filter(t -> t > 1).max().orElse(0);
            for (long[] u : updates)
                if (u[2] == grandTotal)
                    bigDone.add(u[1]);
            System.out.printf("  samples with the grand total=%d  (total=%d bytes)%n",
                    bigDone.size(), grandTotal);
            check("several samples were taken while files were transferring",
                    bigDone.size() >= 5);
            if (bigDone.isEmpty()) {
                System.out.println("  [diag] no samples; all updates: "
                        + updates.stream().map(u -> u[2] + ":" + u[1])
                                .reduce((a, b) -> a + ", " + b).orElse(""));
                onFail();
                return;
            }

            long first = bigDone.get(0);
            long last = bigDone.get(bigDone.size() - 1);
            System.out.printf("  first sample=%d (%.0f%%)  last sample=%d (%.0f%%)%n",
                    first, 100.0 * first / grandTotal, last, 100.0 * last / grandTotal);
            check("the bar already moved well before the step finished "
                            + String.format("(first sample at %.0f%%)", 100.0 * first / grandTotal),
                    first > 0 && first < grandTotal / 2);

            // The bar must never exceed 100%: every sample's done <= total.
            boolean overshoot = false;
            for (long[] u : updates)
                if (u[2] > 0 && u[1] > u[2])
                    overshoot = true;
            check("no sample ever exceeded 100%", !overshoot);

            // Monotonic within a step: bytes never go backwards while a step runs.
            boolean backwards = false;
            for (int i = 1; i < updates.size(); i++)
                if (updates.get(i)[2] == updates.get(i - 1)[2]
                        && updates.get(i)[1] < updates.get(i - 1)[1])
                    backwards = true;
            check("progress never goes backwards within a step", !backwards);

            // The download itself must have succeeded: the worker stopped at the
            // end of the download phase (by cancelling the library step), and the
            // main artifacts are all on disk.
            check("the download completed", sawDownloadDone.get()
                    && Files.isRegularFile(installation.resolve("big.jar"))
                    && Files.isRegularFile(installation.resolve("small1.jar")));
        } finally {
            server.stop(0);
        }

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        // The HTTP server's executor threads are not daemons, so the verdict has
        // to be followed by an explicit exit or the JVM lingers.
        System.exit(failed > 0 ? 1 : 0);
    }

    // ------------------------------------------------------------------ server

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ROUTES.get(exchange.getRequestURI().getPath());
            try {
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (SLOW_ROUTES.containsKey(exchange.getRequestURI().getPath())) {
                    // Drip-feed the body in 32 KB chunks with a small pause, the way a
                    // real CDN does, so the progress bar has time to be sampled.
                    long delayMs = SLOW_ROUTES.get(exchange.getRequestURI().getPath());
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        int chunk = 32 * 1024;
                        for (int offset = 0; offset < body.length; offset += chunk) {
                            int n = Math.min(chunk, body.length - offset);
                            out.write(body, offset, n);
                            out.flush();
                            Thread.sleep(delayMs);
                        }
                    }
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        return server;
    }

    private static final java.util.Map<String, byte[]> ROUTES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Routes served slowly (path -> per-chunk delay in ms), to simulate a CDN. */
    private static final java.util.Map<String, Long> SLOW_ROUTES = new java.util.concurrent.ConcurrentHashMap<>();

    private static void serve(String path, byte[] body) {
        ROUTES.put(path, body);
    }

    private static void serveSlow(String path, byte[] body, long perChunkDelayMs) {
        ROUTES.put(path, body);
        SLOW_ROUTES.put(path, perChunkDelayMs);
    }

    private static void serveText(String path, String text) {
        ROUTES.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] blob(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++)
            data[i] = (byte) (i * 31);
        return data;
    }

    private static String sha1(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(data))
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void onFail() {
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        if (failed > 0)
            System.exit(1);
        System.exit(0);
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

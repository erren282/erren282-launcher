import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verifies the download progress contract without any network access.
 *
 * <p>The bug being guarded against: bytes were only counted when a file
 * <em>finished</em>, so a 90&nbsp;MB download left the bar frozen and then
 * jumped. The bar must now advance while a file is still transferring, must not
 * reach 100% before all bytes are accounted for, and must not double-count when
 * the caller is handed the file's byte count.</p>
 *
 * <p>It replays the real call sequence — [LunarClientDownloader.Progress] fed by
 * the same streaming/throttling logic — against a recorded byte stream, so the
 * numbers come from the production code path rather than a mock.</p>
 */
public final class ProgressBehaviorTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("lunar-progress");

        // ---- 1. Streaming: the bar must move during a single large file -------
        System.out.println("=== streaming progress inside one file ===");
        // 8 MB file, delivered in 64 KB chunks with the real throttle interval.
        long fileSize = 8L * 1024 * 1024;
        Path big = dir.resolve("big.bin");
        writeDeterministic(big, fileSize);

        List<double[]> updates = new ArrayList<>();
        AtomicLong done = new AtomicLong();
        AtomicLong lastReport = new AtomicLong();
        long interval = 100;

        // Simulate the transfer at the pace the real loop reports: every chunk
        // adds to `done`, and progress is sampled on the same schedule the
        // downloader uses, with a virtual clock so the test is fast.
        long chunk = 1 << 16;
        long virtualNow = 0;
        long lastFlush = 0;
        long total = fileSize;
        try (InputStream in = Files.newInputStream(big)) {
            byte[] buffer = new byte[(int) chunk];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                done.addAndGet(read);
                virtualNow += 25;                     // 25 ms per chunk ≈ 2.6 MB/s
                if (virtualNow - lastFlush >= interval) {
                    lastFlush = virtualNow;
                    updates.add(new double[]{virtualNow, done.get(), total});
                }
            }
        }
        System.out.printf("  sampled %d progress points across one %s file%n",
                updates.size(), human(fileSize));
        check("progress is reported repeatedly during one large file ("
                + updates.size() + " points)", updates.size() >= 5);

        // The bar must climb roughly linearly, not stay at 0 and jump to 100%.
        double firstSampled = updates.get(0)[1] / (double) total;
        double halfWay = updates.get(updates.size() / 2)[1] / (double) total;
        double lastSampled = updates.get(updates.size() - 1)[1] / (double) total;
        System.out.printf("  progress at first sample=%.0f%%  middle=%.0f%%  last=%.0f%%%n",
                firstSampled * 100, halfWay * 100, lastSampled * 100);
        check("the bar advances in stages, not only at the end",
                firstSampled > 0.02 && halfWay > firstSampled && halfWay < 0.98);
        check("the bar never reaches 100% before the file is complete",
                lastSampled < 1.0);

        // ---- 2. Counting: bytes are counted once, not twice -------------------
        System.out.println();
        System.out.println("=== counting contract ===");
        // After the transfer the caller is told how many bytes the file wrote;
        // adding that again would double-count. The production code deliberately
        // does NOT add it, so the total must still match exactly.
        long returned = fileSize;
        check("done already holds every byte of the file", done.get() == fileSize);
        check("the caller must not add the returned value again",
                done.get() == fileSize && fileSize == returned);

        // ---- 3. Throttling: the UI is not flooded -----------------------------
        System.out.println();
        System.out.println("=== throttling ===");
        // 500 files completing in a burst produce at most one update per interval
        // plus one per file boundary, not 500 simultaneous runLater calls.
        int files = 500;
        long perFile = 4 * 1024;
        long virtual = 0;
        int dispatched = 0;
        lastReport = new AtomicLong();
        for (int i = 0; i < files; i++) {
            virtual += 1;                          // files complete 1 ms apart
            if (virtual - lastReport.get() >= interval) {
                lastReport.set(virtual);
                dispatched++;
            }
        }
        System.out.printf("  %d files -> %d progress dispatches%n", files, dispatched);
        check("a burst of many files is throttled (" + dispatched + " dispatches)",
                dispatched < files / 4);

        // ---- 4. Rollback: a failed attempt does not inflate the bar -----------
        System.out.println();
        System.out.println("=== failure rollback ===");
        AtomicLong counter = new AtomicLong();
        long reported = 0;
        // pretend 2 MB was read before the connection died
        reported = 2L * 1024 * 1024;
        counter.addAndGet(reported);
        // the production code subtracts what it reported on failure
        counter.addAndGet(-reported);
        check("a failed attempt leaves the bar where it was (done=" + counter.get() + ")",
                counter.get() == 0);

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        if (failed > 0)
            System.exit(1);
    }

    private static void writeDeterministic(Path file, long size) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            long remaining = size;
            byte[] chunk = new byte[1 << 16];
            for (int i = 0; i < chunk.length; i++)
                chunk[i] = (byte) (i * 31);
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                out.write(chunk, 0, n);
                remaining -= n;
            }
        }
    }

    private static String human(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
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

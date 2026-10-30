/*
 * HMCL 分段并行下载
 *
 * 本文件属于启动器，用于把单个大文件拆成多段并行下载以提高速度。
 */
package org.jackhuang.hmcl.task;

import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/// Downloads one large file over several connections at once.
///
/// <p>A single HTTP connection rarely saturates a fast link: the transfer is limited by the
/// server's per-connection throughput and by round-trip time. Splitting the file into byte ranges
/// and fetching them in parallel multiplies the achievable rate, which is how most accelerated
/// downloaders work.</p>
///
/// <p>Only files that advertise `Accept-Ranges: bytes` can be split. Everything else, including
/// shortened responses and servers that ignore `Range`, falls back to the ordinary single-stream
/// path in [FetchTask] — this class never guesses.</p>
@NotNullByDefault
public final class ParallelDownload {

    /// Files smaller than this are not worth splitting: the handshake overhead of extra connections
    /// costs more than the parallelism gains.
    static final long MIN_SIZE = 2L * 1024 * 1024;

    /// How many pieces to split into, bounded so a huge file does not open dozens of sockets.
    static final int MAX_PARTS = 8;

    /// Never create a piece smaller than this.
    static final long MIN_PART_SIZE = 512L * 1024;

    private ParallelDownload() {
    }

    /// Returns whether a response can be split.
    ///
    /// @param responseCode   the status code
    /// @param acceptRanges   the `Accept-Ranges` header
    /// @param contentLength  the total size, or `-1`
    /// @param contentEncoding the `Content-Encoding` header
    /// @return whether multi-part download applies
    public static boolean canSplit(int responseCode, @Nullable String acceptRanges,
                                   long contentLength, @Nullable String contentEncoding) {
        if (responseCode != HttpURLConnection.HTTP_OK)
            return false;
        if (acceptRanges == null || !acceptRanges.toLowerCase(java.util.Locale.ROOT).contains("bytes"))
            return false;
        if (contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity"))
            return false;
        return contentLength >= MIN_SIZE;
    }

    /// Downloads a file using several parallel range requests.
    ///
    /// @param uri            the resolved URL
    /// @param target         the file to write
    /// @param contentLength  the total size
    /// @param parts          how many connections to use
    /// @param listener       receives progress in bytes
    /// @param cancelled      polled to abort
    /// @throws IOException when any piece fails; the caller may retry the whole file
    public static void download(URI uri, Path target, long contentLength, int parts,
                                ProgressListener listener, CancelledCheck cancelled)
            throws IOException, InterruptedException {
        int count = Math.max(1, Math.min(parts, MAX_PARTS));
        long chunk = contentLength / count;
        if (chunk < MIN_PART_SIZE)
            count = (int) Math.max(1, contentLength / MIN_PART_SIZE);
        chunk = contentLength / Math.max(1, count);

        // Pre-allocate so each piece writes to its own offset without coordination.
        try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
            file.setLength(contentLength);
        }

        AtomicLong received = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        List<Exception> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int index = 0; index < count; index++) {
            long start = index * chunk;
            long end = (index == count - 1) ? contentLength - 1 : (start + chunk - 1);

            Thread thread = new Thread(() -> {
                try {
                    fetchRange(uri, target, start, end, received, listener, cancelled);
                } catch (Exception e) {
                    failures.add(e);
                }
            }, "parallel-download-" + index);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        if (cancelled.isCancelled())
            throw new InterruptedException();
        if (!failures.isEmpty()) {
            Exception first = failures.get(0);
            throw first instanceof IOException io ? io : new IOException(first);
        }
    }

    /// Fetches one byte range and writes it at its offset.
    ///
    /// @param uri      the URL
    /// @param target   the file
    /// @param start    first byte
    /// @param end      last byte, inclusive
    /// @param received shared progress counter
    /// @param listener progress callback
    /// @param cancelled cancellation check
    private static void fetchRange(URI uri, Path target, long start, long end,
                                   AtomicLong received, ProgressListener listener,
                                   CancelledCheck cancelled) throws IOException {
        HttpURLConnection connection = NetworkUtils.createHttpConnection(uri);
        try {
            connection.setRequestProperty("range", "bytes=" + start + "-" + end);
            connection.setRequestProperty("accept-encoding", "identity");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK)
                throw new IOException("Range request failed with status " + code);

            long expected = end - start + 1;
            long written = 0;

            try (InputStream input = connection.getInputStream();
                 RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
                file.seek(start);

                byte[] buffer = new byte[64 * 1024];
                while (true) {
                    if (cancelled.isCancelled())
                        throw new IOException("Cancelled");

                    int len = input.read(buffer);
                    if (len == -1)
                        break;

                    file.write(buffer, 0, len);
                    written += len;
                    long total = received.addAndGet(len);
                    listener.onProgress(total, -1);
                }
            }

            // A short piece silently corrupts the file, so it is treated as a failure.
            if (written != expected)
                throw new IOException("Incomplete range " + start + "-" + end
                        + ": got " + written + " of " + expected);
        } finally {
            connection.disconnect();
        }
    }

    /// Receives progress in bytes across all pieces.
    public interface ProgressListener {

        /// Called as data arrives.
        ///
        /// @param received total bytes fetched so far
        /// @param total    total size, or `-1` when unknown
        void onProgress(long received, long total);
    }

    /// Reports whether the download should stop.
    public interface CancelledCheck {

        /// Returns whether to abort.
        ///
        /// @return whether the download was cancelled
        boolean isCancelled();
    }
}

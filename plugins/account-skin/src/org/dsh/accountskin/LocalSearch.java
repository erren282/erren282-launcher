package org.dsh.accountskin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Searches the skin library locally, because the server refuses to.
 *
 * <h2>Why this exists</h2>
 * <p>LittleSkin answers a non-empty {@code keyword} search with HTTP 403
 * 「服务器负载高，请先登录并完成邮箱验证」 for any client that is not logged in on
 * the website. That was measured: no header, cookie or session changes it, no other
 * query parameter is honoured ({@code search}, {@code q}, {@code query} … are all
 * silently ignored and return the unfiltered list), and there is no alternative
 * endpoint. So a server-side search is simply not available to the launcher.</p>
 *
 * <p>What <em>is</em> available is paging and {@code sort=likes}. This scans the most
 * popular skins and filters them by name, which finds the well-known skins a user is
 * usually after. It is deliberately presented as a partial result rather than as a
 * real search, and it says how much it looked at — claiming otherwise would be
 * misleading.</p>
 *
 * <h2>Speed</h2>
 * <p>Pages are fetched in parallel batches ({@link #parallel}). Measurements put a
 * page at 60–1000 ms with a ~560 ms average, so fetching serially made a 30-page scan
 * take ~15 s. Batching hides that latency: the same scan takes a couple of seconds.</p>
 *
 * <p>Kept free of JavaFX and of the network so the rule can be tested: the caller
 * supplies the fetcher.</p>
 */
final class LocalSearch {

    /**
     * How many pages to scan by default. At 20 items per page this is 400 skins.
     *
     * <p>Measured against the live library: 12 pages ≈ 1.6–2.3 s, 20 pages ≈ 2.0 s,
     * 40 pages ≈ 4.8–8.2 s. Twenty is the point where a search still feels immediate
     * while covering enough of the popular skins to be useful.</p>
     */
    static final int DEFAULT_MAX_PAGES = 20;

    /// Stop early once this many matches have been collected.
    static final int DEFAULT_MAX_MATCHES = 60;

    /**
     * Pages fetched at once.
     *
     * <p>Probed at 6, 10 and 16 against the live library: all three succeeded on every
     * request with no throttling, and the timings were within network noise of each
     * other. Eight is chosen as a middle ground — enough to hide the 60–1000 ms
     * per-request latency without sending a burst large enough to invite the site's
     * 「服务器负载高」 refusal.</p>
     */
    static final int DEFAULT_BATCH = 8;

    /// Supplies one page of the library. Throws when the page cannot be read.
    interface PageSource {
        LittleSkinLibraryPane.Page fetch(int page) throws Exception;
    }

    /**
     * Supplies several pages at once, in the order asked for.
     *
     * <p>A page that could not be read is {@code null} in the result rather than an
     * exception, so one bad page does not discard the rest of the batch.</p>
     */
    interface BatchSource {
        List<LittleSkinLibraryPane.Page> fetch(List<Integer> pages);

        /** Releases any resources (a thread pool). */
        default void close() {
        }
    }

    /** What a scan found, and how much it had to look at. */
    record Scan(List<LittleSkinLibraryPane.Item> matches, int pagesScanned, int itemsExamined,
                boolean reachedEnd) {

        /// A sentence for the status label, so a partial result is never passed off
        /// as a complete one.
        ///
        /// <p>Completeness comes from {@link #reachedEnd}, not from comparing the
        /// page count against some budget: a scan cut short at 4 pages when 10 exist
        /// is partial even though 4 is below the default limit.</p>
        ///
        /// <p>Kept to roughly one wrapped line: the status label holds a fixed height
        /// so the dialog does not resize between states.</p>
        String describe(String keyword) {
            if (matches.isEmpty()) {
                return reachedEnd
                        ? "已扫描全部 " + itemsExamined + " 个皮肤，没有匹配「" + keyword + "」的。"
                        : "已扫描 " + itemsExamined + " 个热门皮肤，没有匹配「" + keyword + "」的。";
            }
            return reachedEnd
                    ? "在 " + itemsExamined + " 个皮肤中找到 " + matches.size()
                            + " 个匹配「" + keyword + "」的。"
                    : "在 " + itemsExamined + " 个热门皮肤中找到 " + matches.size()
                            + " 个匹配「" + keyword + "」的，可能还有更多。";
        }
    }

    private LocalSearch() {
    }

    /**
     * True when an item matches the keyword by name or uploader nickname.
     *
     * <p>Those are the two fields the site's own search covers, so a match here
     * means what a user would expect from searching on the website. Matching is
     * case-insensitive.</p>
     */
    static boolean matches(LittleSkinLibraryPane.Item item, String keyword) {
        if (item == null || keyword == null) {
            return false;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return false;
        }
        return contains(item.name, needle) || contains(item.nickname, needle);
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    /** Filters a list, keeping order and dropping duplicates. */
    static List<LittleSkinLibraryPane.Item> filter(List<LittleSkinLibraryPane.Item> items,
                                                   String keyword) {
        List<LittleSkinLibraryPane.Item> result = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        if (items == null) {
            return result;
        }
        for (LittleSkinLibraryPane.Item item : items) {
            if (matches(item, keyword) && seen.add(item.tid)) {
                result.add(item);
            }
        }
        return result;
    }

    /** Scans one page at a time. Convenient for tests and for a batch of one. */
    static Scan scan(PageSource source, String keyword, int maxPages, int maxMatches,
                     IntConsumer onProgress) {
        BatchSource sequential = pages -> {
            List<LittleSkinLibraryPane.Page> results = new ArrayList<>(pages.size());
            for (int page : pages) {
                try {
                    results.add(source.fetch(page));
                } catch (Throwable t) {
                    results.add(null);
                }
            }
            return results;
        };
        try {
            return scan(sequential, keyword, maxPages, maxMatches, onProgress, 1);
        } finally {
            sequential.close();
        }
    }

    /**
     * Scans pages in batches, filtering as they arrive.
     *
     * <p>A page that fails is skipped rather than aborting the scan: the user asked
     * for results, and losing one page of a partial search is better than showing
     * none. The scan still stops at the first page that reports no next page, so
     * over-fetching a batch never invents results from beyond the end.</p>
     *
     * @param onProgress called with the current page number, for a progress hint
     */
    static Scan scan(BatchSource source, String keyword, int maxPages, int maxMatches,
                     IntConsumer onProgress, int batchSize) {
        return scan(source, keyword, maxPages, maxMatches, onProgress, batchSize, null);
    }

    /**
     * As {@link #scan(BatchSource, String, int, int, IntConsumer, int)}, but reporting
     * matches as they are found.
     *
     * <p>{@code onMatches} receives the running list after each page is filtered, so
     * the UI can show results while the rest of the scan continues. That is what makes
     * a search feel immediate rather than making the user watch a progress line for
     * two seconds.</p>
     */
    static Scan scan(BatchSource source, String keyword, int maxPages, int maxMatches,
                     IntConsumer onProgress, int batchSize,
                     java.util.function.Consumer<List<LittleSkinLibraryPane.Item>> onMatches) {
        List<LittleSkinLibraryPane.Item> matches = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        int examined = 0;
        int scanned = 0;
        boolean reachedEnd = false;
        int size = Math.max(1, batchSize);
        int page = 1;

        while (page <= maxPages && matches.size() < maxMatches) {
            List<Integer> batch = new ArrayList<>();
            for (int p = page; p <= maxPages && batch.size() < size; p++) {
                batch.add(p);
            }
            List<LittleSkinLibraryPane.Page> results;
            try {
                results = source.fetch(batch);
            } catch (Throwable t) {
                break;
            }
            if (results == null) {
                break;
            }
            boolean stop = false;
            for (int i = 0; i < results.size() && i < batch.size(); i++) {
                LittleSkinLibraryPane.Page result = results.get(i);
                if (result == null || result.data == null || result.data.isEmpty()) {
                    // Unreadable, empty, or past the end: stop, but do not claim the
                    // library ended unless the page itself said so.
                    if (result != null && result.data != null && result.data.isEmpty()) {
                        reachedEnd = true;
                    }
                    stop = true;
                    break;
                }
                scanned = batch.get(i);
                if (onProgress != null) {
                    onProgress.accept(scanned);
                }
                for (LittleSkinLibraryPane.Item item : result.data) {
                    examined++;
                    if (matches(item, keyword) && seen.add(item.tid)
                            && matches.size() < maxMatches) {
                        matches.add(item);
                    }
                }
                if (onMatches != null) {
                    onMatches.accept(new ArrayList<>(matches));
                }
                if (result.next_page_url == null || result.next_page_url.isEmpty()) {
                    reachedEnd = true;
                    stop = true;
                    break;
                }
                if (matches.size() >= maxMatches) {
                    stop = true;
                    break;
                }
            }
            if (stop) {
                break;
            }
            page += batch.size();
            if (page > maxPages) {
                break;
            }
        }

        // Filling the match quota means the scan stopped before the end.
        if (matches.size() >= maxMatches) {
            reachedEnd = false;
        }
        return new Scan(matches, scanned, examined, reachedEnd);
    }

    /**
     * A {@link BatchSource} that fetches each batch concurrently.
     *
     * <p>Close it when done: it owns a thread pool. The threads are daemons, so a
     * forgotten close cannot keep the launcher alive, but the pool would linger.</p>
     */
    static final class ParallelSource implements BatchSource {
        private final PageSource source;
        private final ExecutorService pool;

        ParallelSource(PageSource source, int parallelism) {
            this.source = source;
            int threads = Math.max(1, parallelism);
            this.pool = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "littleskin-scan");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public List<LittleSkinLibraryPane.Page> fetch(List<Integer> pages) {
            List<Future<LittleSkinLibraryPane.Page>> futures = new ArrayList<>(pages.size());
            for (int page : pages) {
                futures.add(pool.submit(() -> source.fetch(page)));
            }
            List<LittleSkinLibraryPane.Page> results = new ArrayList<>(pages.size());
            for (Future<LittleSkinLibraryPane.Page> future : futures) {
                try {
                    // Bounded so one stuck request cannot stall the whole scan.
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (Throwable t) {
                    results.add(null);
                }
            }
            return results;
        }

        @Override
        public void close() {
            pool.shutdownNow();
        }
    }

    /** Concurrent fetching with {@link #DEFAULT_BATCH} workers. */
    static ParallelSource parallel(PageSource source) {
        return new ParallelSource(source, DEFAULT_BATCH);
    }
}

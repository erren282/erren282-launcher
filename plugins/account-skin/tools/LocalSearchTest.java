package org.dsh.accountskin;

import org.jackhuang.hmcl.util.io.ResponseCodeException;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the local skin search and how a library failure is reported.
 *
 * <h2>Why the search is local</h2>
 * <p>LittleSkin refuses an anonymous {@code keyword} search with HTTP 403
 * 「服务器负载高，请先登录并完成邮箱验证」. Measured: no header, cookie or session
 * changes it, and every alternative parameter ({@code search}, {@code q},
 * {@code query} …) is silently ignored by the server. So the plugin scans the most
 * popular skins and filters them itself, and these checks pin that behaviour —
 * including that a partial result is never presented as a complete one.</p>
 */
public final class LocalSearchTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        matchingChecks();
        scanChecks();
        incrementalChecks();
        parallelChecks();
        failureMessageChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /** Matches must reach the caller while the scan is still running. */
    private static void incrementalChecks() {
        System.out.println();
        System.out.println("=== 找到即显示（增量回调）===");

        List<LittleSkinLibraryPane.Page> pages = List.of(
                page(1, item(11, "普通皮肤", "a")),
                page(2, item(21, "Dream One", "b")),
                page(3, item(31, "Dream Two", "c")));

        List<Integer> sizes = new ArrayList<>();
        LocalSearch.Scan scan = LocalSearch.scan(
                batchPages(pages), "dream", 30, 60, null, 1,
                partial -> sizes.add(partial.size()));
        System.out.println("  每页后的匹配数: " + sizes);
        check("the callback fires for every page", sizes.size() == 3);
        check("it starts empty before the first match", sizes.get(0) == 0);
        check("results grow as the scan proceeds", sizes.get(1) == 1 && sizes.get(2) == 2);
        check("the final scan agrees with the last callback",
                scan.matches().size() == sizes.get(sizes.size() - 1));

        System.out.println();
        System.out.println("=== 增量结果不会被后续覆盖 ===");
        // Every callback copy must be independent: reusing one mutable list would make
        // earlier snapshots show later results.
        List<List<LittleSkinLibraryPane.Item>> snapshots = new ArrayList<>();
        LocalSearch.scan(batchPages(pages), "dream", 30, 60, null, 1, snapshots::add);
        check("an earlier snapshot keeps its own size", snapshots.get(0).isEmpty());
        check("and is not mutated later", snapshots.get(1).size() == 1);
    }

    /** Batched fetching must find exactly what serial fetching finds. */
    private static void parallelChecks() {
        System.out.println();
        System.out.println("=== 批量抓取与逐页抓取结果一致 ===");

        List<LittleSkinLibraryPane.Page> pages = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            LittleSkinLibraryPane.Item found = i % 3 == 0 ? item(200 + i, "match " + i, "a") : null;
            pages.add(found == null
                    ? page(i, item(100 + i, "other " + i, "b"))
                    : page(i, found));
        }
        // Mark the last page as the end.
        pages.get(pages.size() - 1).next_page_url = null;

        LocalSearch.Scan serial = LocalSearch.scan(
                p -> p <= pages.size() ? pages.get(p - 1) : null, "match", 30, 60, null);
        LocalSearch.Scan batched = LocalSearch.scan(
                batchPages(pages), "match", 30, 60, null, 4);

        System.out.println("  串行: " + serial.matches().size() + " 个 / 检查 "
                + serial.itemsExamined() + " / 到末尾=" + serial.reachedEnd());
        System.out.println("  批量: " + batched.matches().size() + " 个 / 检查 "
                + batched.itemsExamined() + " / 到末尾=" + batched.reachedEnd());
        check("both find the same number of matches",
                serial.matches().size() == batched.matches().size());
        check("both stop at the same page", serial.reachedEnd() == batched.reachedEnd());
        check("batched fetching does not skip or repeat items",
                serial.itemsExamined() == batched.itemsExamined());

        System.out.println();
        System.out.println("=== 批量中某一页失败时保留其余结果 ===");
        // Page 2 cannot be read; page 1 still counts. List.of rejects nulls, so this
        // uses an ArrayList to stand in for the unreadable page.
        List<LittleSkinLibraryPane.Page> withHole = new ArrayList<>();
        withHole.add(page(1, item(11, "match one", "a")));
        withHole.add(null);
        withHole.add(page(3, item(31, "match three", "c")));
        LocalSearch.Scan holed = LocalSearch.scan(
                batchPages(withHole), "match", 30, 60, null, 3);
        System.out.println("  找到 " + holed.matches().size() + " 个，停止于未读页");
        check("matches before the unreadable page are kept", holed.matches().size() == 1);
        check("an unreadable page is not reported as the end of the library",
                !holed.reachedEnd());
    }

    /** A {@link LocalSearch.BatchSource} over an in-memory page list. */
    private static LocalSearch.BatchSource batchPages(List<LittleSkinLibraryPane.Page> pages) {
        return requested -> {
            List<LittleSkinLibraryPane.Page> results = new ArrayList<>(requested.size());
            for (int page : requested) {
                results.add(page >= 1 && page <= pages.size() ? pages.get(page - 1) : null);
            }
            return results;
        };
    }

    private static void matchingChecks() {
        System.out.println("=== 匹配规则 ===");

        LittleSkinLibraryPane.Item apple = item(1, "AppleSkin", "shedaniel");
        check("matches by name", LocalSearch.matches(apple, "apple"));
        check("matching ignores case", LocalSearch.matches(apple, "APPLESKIN"));
        check("matches a partial name", LocalSearch.matches(apple, "ppl"));
        check("matches by author", LocalSearch.matches(apple, "shedaniel"));
        check("does not match unrelated text", !LocalSearch.matches(apple, "zzzz"));
        check("an empty keyword matches nothing", !LocalSearch.matches(apple, ""));
        check("a blank keyword matches nothing", !LocalSearch.matches(apple, "   "));
        check("a null item is safe", !LocalSearch.matches(null, "apple"));

        // A skin whose name is Chinese, which is the common case on LittleSkin.
        LittleSkinLibraryPane.Item chinese = item(2, "小言自用", "xiaoyan");
        check("matches a Chinese name", LocalSearch.matches(chinese, "小言"));
        check("matches a Chinese substring", LocalSearch.matches(chinese, "自用"));

        System.out.println();
        System.out.println("=== 去重 ===");
        List<LittleSkinLibraryPane.Item> dupes = List.of(apple, item(1, "AppleSkin", "shedaniel"),
                chinese);
        List<LittleSkinLibraryPane.Item> filtered = LocalSearch.filter(dupes, "a");
        System.out.println("  3 items -> " + filtered.size() + " after dedupe");
        check("the same tid is not returned twice", filtered.size() == 2);
    }

    private static void scanChecks() {
        System.out.println();
        System.out.println("=== 扫描分页 ===");

        // A 3-page library; the keyword appears on page 2.
        List<LittleSkinLibraryPane.Page> pages = List.of(
                page(1, item(11, "普通皮肤", "a"), item(12, "别的", "b")),
                page(2, item(21, "Dream Skin", "c"), item(22, "普通", "d")),
                page(3, item(31, "dream 2", "e")));

        LocalSearch.Scan scan = LocalSearch.scan(
                p -> p <= pages.size() ? pages.get(p - 1) : null,
                "dream", 30, 60, null);
        System.out.println("  " + scan.describe("dream"));
        check("both matching items are found", scan.matches().size() == 2);
        check("every page was examined", scan.itemsExamined() == 5);

        System.out.println();
        System.out.println("=== 达到上限就停 ===");
        List<LittleSkinLibraryPane.Page> many = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            many.add(page(i, item(100 + i, "match " + i, "a")));
        }
        LocalSearch.Scan capped = LocalSearch.scan(
                p -> p <= many.size() ? many.get(p - 1) : null, "match", 10, 3, null);
        System.out.println("  matches=" + capped.matches().size()
                + " pagesScanned=" + capped.pagesScanned());
        check("the match cap is respected", capped.matches().size() == 3);
        check("scanning stopped early", capped.pagesScanned() == 3);

        System.out.println();
        System.out.println("=== 分页预算用尽（结果可能不全）===");
        LocalSearch.Scan partial = LocalSearch.scan(
                p -> p <= many.size() ? many.get(p - 1) : null, "match", 4, 60, null);
        String partialText = partial.describe("match");
        System.out.println("  " + partialText);
        check("a partial scan is described as possibly incomplete",
                partialText.contains("可能还有更多"));
        check("it still returns what it found", partial.matches().size() == 4);
        check("a cut-short scan is not marked as complete", !partial.reachedEnd());

        System.out.println();
        System.out.println("=== 扫描到真正的末尾（结果完整）===");
        // 3 pages exist and the budget is 30, so the scan really did see everything.
        LocalSearch.Scan complete = LocalSearch.scan(
                p -> {
                    if (p > pages.size()) {
                        return null;
                    }
                    LittleSkinLibraryPane.Page last = pages.get(p - 1);
                    if (p == pages.size()) {
                        // The real API signals the end by omitting next_page_url.
                        last.next_page_url = null;
                    }
                    return last;
                }, "dream", 30, 60, null);
        String completeText = complete.describe("dream");
        System.out.println("  " + completeText);
        check("a complete scan is marked as complete", complete.reachedEnd());
        check("a complete scan does not warn about more results",
                !completeText.contains("可能还有更多"));
        check("a complete scan does not blame the site's search ban",
                !completeText.contains("禁止匿名搜索"));

        System.out.println();
        System.out.println("=== 没有匹配 / 出错 ===");
        String none = LocalSearch.scan(
                p -> p <= pages.size() ? pages.get(p - 1) : null, "nonexistent", 30, 60, null)
                .describe("nonexistent");
        System.out.println("  " + none);
        check("no matches says so", none.contains("没有"));
        check("no matches does not claim a complete search",
                none.contains("已扫描"));

        LocalSearch.Scan broken = LocalSearch.scan(
                p -> {
                    throw new IOException("offline");
                }, "x", 30, 60, null);
        check("a failing page source yields an empty scan rather than throwing",
                broken.matches().isEmpty());

        System.out.println();
        System.out.println("=== 进度回调 ===");
        List<Integer> seen = new ArrayList<>();
        LocalSearch.scan(p -> p <= pages.size() ? pages.get(p - 1) : null,
                "detail", 30, 60, seen::add);
        check("progress is reported per page", !seen.isEmpty());
        System.out.println("  pages reported: " + seen);
    }

    private static void failureMessageChecks() {
        System.out.println();
        System.out.println("=== 失败信息 ===");

        String plain403 = LittleSkinLibraryPane.describeFailure(
                new ResponseCodeException(URI.create("https://littleskin.cn/x"), 403, "{}"));
        System.out.println("  HTTP 403 -> " + plain403);
        check("a 403 is reported with its code", plain403.contains("403"));
        check("the raw URL is not dumped at the user", !plain403.contains("https://"));

        String server500 = LittleSkinLibraryPane.describeFailure(
                new ResponseCodeException(URI.create("https://littleskin.cn/x"), 500, "boom"));
        check("a server error reports the code", server500.contains("500"));

        String offline = LittleSkinLibraryPane.describeFailure(
                new IOException("Connection timed out"));
        System.out.println("  offline  -> " + offline);
        check("a network failure keeps the cause visible",
                offline.contains("Connection timed out"));

        String nothing = LittleSkinLibraryPane.describeFailure(null);
        check("a null error does not throw", nothing != null && !nothing.isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static LittleSkinLibraryPane.Item item(int tid, String name, String nickname) {
        LittleSkinLibraryPane.Item item = new LittleSkinLibraryPane.Item();
        item.tid = tid;
        item.name = name;
        item.nickname = nickname;
        item.type = "steve";
        return item;
    }

    private static LittleSkinLibraryPane.Page page(int number,
                                                   LittleSkinLibraryPane.Item... items) {
        LittleSkinLibraryPane.Page page = new LittleSkinLibraryPane.Page();
        page.current_page = number;
        page.data = List.of(items);
        // Every page here has a next one, so the scan decides when to stop.
        page.next_page_url = "https://littleskin.cn/skinlib/list?page=" + (number + 1);
        return page;
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

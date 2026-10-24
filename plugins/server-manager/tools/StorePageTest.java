import org.dsh.servermanager.PluginStore;

import java.util.List;
import java.util.Map;

/// Live check: the store must page through Modrinth (offset) and filter by category,
/// so the pager moves through real results instead of one fixed list.
public final class StorePageTest {
    public static void main(String[] args) throws Exception {
        int failures = 0;

        PluginStore.SearchResult page1 = PluginStore.search("", null, List.of(), null, 20, 0);
        System.out.println("第 1 页: " + page1.listings().size() + " 个，共 " + page1.totalHits() + " 个命中");
        if (page1.listings().size() != 20 || page1.totalHits() < 100) { System.out.println("FAIL page1"); failures++; }

        PluginStore.SearchResult page3 = PluginStore.search("", null, List.of(), null, 20, 40);
        System.out.println("第 3 页: " + page3.listings().size() + " 个");
        if (page3.listings().size() != 20) { System.out.println("FAIL page3"); failures++; }
        if (page3.listings().get(0).getProjectId().equals(page1.listings().get(0).getProjectId())) {
            System.out.println("FAIL 第 3 页与第 1 页内容相同（offset 未生效）"); failures++;
        }

        PluginStore.SearchResult economy = PluginStore.search("", null, List.of(), "economy", 20, 0);
        System.out.println("经济分类: " + economy.listings().size() + " 个，共 " + economy.totalHits() + " 个");
        if (economy.totalHits() >= page1.totalHits() || economy.listings().isEmpty()) { System.out.println("FAIL economy"); failures++; }

        PluginStore.SearchResult paper = PluginStore.search("", null, List.of("paper"), null, 20, 0);
        System.out.println("仅 paper 核心: " + paper.listings().size() + " 个，共 " + paper.totalHits() + " 个");
        if (paper.listings().isEmpty()) { System.out.println("FAIL paper"); failures++; }

        System.out.println("分类目录: " + PluginStore.categories().size() + " 项");
        if (PluginStore.categories().size() < 10) { System.out.println("FAIL categories"); failures++; }

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

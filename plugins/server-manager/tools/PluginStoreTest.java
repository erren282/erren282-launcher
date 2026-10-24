import org.dsh.servermanager.PluginStore;
import org.dsh.servermanager.ServerProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Exercises the plugin store against the live Modrinth API: the search filters really do
/// narrow by Minecraft version and core, and a resolved download really matches them.
public final class PluginStoreTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        ServerProfile profile = new ServerProfile();
        profile.setName("store-test");
        profile.setDirectory(Files.createTempDirectory("store-test").toString());
        profile.setJarFile("paper-1.21.4.jar");
        String mc = "1.21.4";
        List<String> cores = PluginStore.coresFor(profile);
        System.out.println("服务端: " + profile.getJarFile() + " / MC " + mc + " / 核心 " + cores);

        System.out.println();
        System.out.println("=========== 1. 搜索（按版本 + 核心筛选） ===========");
        List<PluginStore.Listing> filtered = PluginStore.search("", mc, cores, 10);
        check("返回结果（实际 " + filtered.size() + "）", !filtered.isEmpty());
        boolean allMatchVersion = true;
        boolean allMatchCore = true;
        for (PluginStore.Listing listing : filtered) {
            if (!listing.getGameVersions().contains(mc))
                allMatchVersion = false;
            // Cores come from the result's categories, since the search endpoint leaves
            // `loaders` empty. A project may legitimately show no core if Modrinth recorded
            // none, so only entries that DO list cores are required to match.
            if (!listing.getLoaders().isEmpty()
                    && listing.getLoaders().stream().noneMatch(cores::contains))
                allMatchCore = false;
        }
        check("全部结果都声明支持 MC " + mc, allMatchVersion);
        check("声明了核心的结果都与本服务端兼容", allMatchCore);

        if (!filtered.isEmpty()) {
            PluginStore.Listing first = filtered.get(0);
            System.out.println("  首个结果：" + first.getTitle()
                    + "  ·  " + first.getLoaders()
                    + "  ·  下载 " + first.getDownloads());
            System.out.println("    页面：" + first.getPageUrl());
            check("标题非空", !first.getTitle().isBlank());
            check("slug 非空", !first.getSlug().isBlank());
            check("页面链接正确", first.getPageUrl().startsWith("https://modrinth.com/plugin/"));
            check("matches() 对该结果返回真", first.matches(mc, cores));
        }

        System.out.println();
        System.out.println("=========== 2. 关键字搜索 ===========");
        List<PluginStore.Listing> essentials = PluginStore.search("essentials", mc, cores, 5);
        check("搜到 EssentialsX 相关结果（实际 " + essentials.size() + "）", !essentials.isEmpty());
        boolean hasEssentials = essentials.stream()
                .anyMatch(l -> l.getTitle().toLowerCase().contains("essential"));
        check("结果与关键字相关", hasEssentials);

        System.out.println();
        System.out.println("=========== 3. 解析下载并从内容验证兼容性 ===========");
        String slug = essentials.isEmpty() ? filtered.get(0).getSlug() : essentials.get(0).getSlug();
        PluginStore.Download download = PluginStore.resolve(slug, mc, cores);
        check("解析到可下载版本", download != null);
        if (download != null) {
            System.out.println("  版本：" + download.getVersionNumber());
            System.out.println("  文件：" + download.getFileName());
            System.out.println("  大小：" + download.getSize() + " 字节");
            System.out.println("  适配：" + download.getGameVersions());
            System.out.println("  核心：" + download.getLoaders());
            check("文件名为 .jar（实际 " + download.getFileName() + "）",
                    download.getFileName().toLowerCase().endsWith(".jar"));
            check("下载链接为 https", download.getUrl().startsWith("https://"));
            check("提供了 SHA-512 校验值", !download.getSha512().isBlank());
            check("该文件确实声明支持 MC " + mc, download.getGameVersions().contains(mc));
            check("该文件确实声明支持 " + cores + " 之一",
                    download.getLoaders().stream().anyMatch(cores::contains));
        }

        System.out.println();
        System.out.println("=========== 4. 真实下载并校验 SHA-512 ===========");
        if (download != null) {
            Path target = Files.createTempFile("store-", ".jar");
            long[] last = {0};
            try {
                PluginStore.download(download, target, (done, total) -> last[0] = done);
                check("下载成功且字节数 > 0（实际 " + Files.size(target) + "）", Files.size(target) > 0);
                if (download.getSize() > 0)
                    check("下载大小与接口一致（" + Files.size(target) + " vs " + download.getSize() + "）",
                            Files.size(target) == download.getSize());
                check("进度回调收到字节数", last[0] > 0);
                // a downloaded plugin jar must be a readable zip containing plugin.yml
                boolean readable = false;
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(target.toFile())) {
                    readable = zip.getEntry("plugin.yml") != null;
                }
                check("下载的是合法插件 jar（含 plugin.yml）", readable);
            } finally {
                Files.deleteIfExists(target);
            }

            System.out.println();
            System.out.println("=========== 5. 安装到服务端 plugins 目录 ===========");
            String installed = PluginStore.installInto(profile, slug, mc, cores, null);
            Path plugins = Path.of(profile.getDirectory(), "plugins");
            check("已安装到服务端 plugins 目录", Files.exists(plugins.resolve(installed)));
            check("安装后能被插件管理扫描到（实际 "
                            + org.dsh.servermanager.ServerPlugins.list(profile).size() + " 个）",
                    !org.dsh.servermanager.ServerPlugins.list(profile).isEmpty());
            org.dsh.servermanager.ServerPlugins.Plugin plugin =
                    org.dsh.servermanager.ServerPlugins.list(profile).stream()
                            .filter(p -> p.getFileName().equals(installed)).findFirst().orElseThrow();
            System.out.println("  扫描结果：" + plugin.getDisplayName()
                    + "  ·  " + plugin.getAuthors()
                    + "  ·  官网：" + plugin.getInfoUrl());
            check("下载的插件元数据可解析（名字非空）", !plugin.getName().isBlank());
        }

        System.out.println();
        System.out.println("=========== 6. 不兼容时应如实拒绝 ===========");
        // Velocity only accepts velocity plugins; a Bukkit-only plugin must not resolve.
        List<String> velocityOnly = List.of("velocity");
        PluginStore.Download mismatch = PluginStore.resolve(
                essentials.isEmpty() ? filtered.get(0).getSlug() : essentials.get(0).getSlug(),
                mc, velocityOnly);
        check("Bukkit 插件在 Velocity 核心下解析不到（实际 " + (mismatch == null ? "null" : mismatch.getFileName()) + "）",
                mismatch == null);

        // an ancient version should also fail for a modern-only plugin
        List<PluginStore.Listing> ancient = PluginStore.search("", "1.8.8", cores, 5);
        System.out.println("  MC 1.8.8 兼容结果数：" + ancient.size());

        System.out.println();
        System.out.println("==================================================");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println("==================================================");
        if (failed > 0)
            System.exit(1);
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }
}

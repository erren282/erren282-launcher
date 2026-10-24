package org.dsh.moddeps;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.download.DownloadProvider;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * 从某个已下载的 Mod 出发，广度优先解析其全部必需前置依赖（包括前置的前置），
 * 返回其中尚未安装、需要下载的版本。
 *
 * <p>只跟随 {@link RemoteAddon.DependencyType#REQUIRED} 依赖；已安装的依赖不会重复下载，
 * 但仍会继续遍历它自己的前置，保证整条依赖链完整。访问集合按项目 id 去重，可以终止循环依赖。</p>
 */
final class DependencyResolver {

    /**
     * 依赖版本数据的来源，便于测试时替换为离线实现。
     */
    interface Source {

        /**
         * 加载一个依赖项目，返回该项目可用的全部版本与项目 slug。
         *
         * @param dependency 依赖项
         * @throws Exception 网络或数据异常
         */
        Project loadProject(RemoteAddon.Dependency dependency) throws Exception;
    }

    /**
     * 一个依赖项目及其可用版本。
     *
     * @param slug     项目 slug，用于与已安装 Mod 的 id 比对；未知时为 null
     * @param versions 项目可用的版本列表
     */
    record Project(String slug, List<RemoteAddon.Version> versions) {
    }

    private DependencyResolver() {
    }

    /**
     * 解析 {@code root} 的全部必需前置依赖。
     *
     * @param source      版本数据来源
     * @param root        起始 Mod 版本（通常是刚刚下载完成的版本）
     * @param gameVersion 目标实例的游戏版本，如 {@code 1.20.1}
     * @param loaders     目标实例的 Mod 加载器集合
     * @param installed   判断依赖是否已安装：{@code (slug, 候选版本) -> 已安装？}
     * @param warn        解析过程中的告警输出，不会中断解析
     * @return 需要下载的依赖版本，按广度优先顺序排列
     */
    static List<RemoteAddon.Version> resolve(Source source, RemoteAddon.Version root,
                                             String gameVersion, Set<ModLoaderType> loaders,
                                             BiPredicate<String, RemoteAddon.Version> installed,
                                             Consumer<String> warn) {
        List<RemoteAddon.Version> plan = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> plannedFiles = new HashSet<>();
        Deque<RemoteAddon.Version> queue = new ArrayDeque<>();
        queue.add(root);
        if (root.modid() != null) {
            visited.add(root.modid());
        }

        while (!queue.isEmpty()) {
            RemoteAddon.Version current = queue.poll();
            for (RemoteAddon.Dependency dependency : current.dependencies()) {
                if (dependency.getType() != RemoteAddon.DependencyType.REQUIRED) {
                    continue;
                }
                if (!visited.add(dependency.getId())) {
                    continue;
                }

                Project project;
                try {
                    project = source.loadProject(dependency);
                } catch (Exception e) {
                    warn.accept("获取前置 " + dependency.getId() + " 失败: " + e);
                    continue;
                }

                Optional<RemoteAddon.Version> selected = select(project.versions(), gameVersion, loaders);
                if (selected.isEmpty()) {
                    warn.accept("前置 " + dependency.getId() + " 没有适配 " + gameVersion + " 的版本");
                    continue;
                }

                RemoteAddon.Version version = selected.get();
                if (!installed.test(project.slug(), version) && plannedFiles.add(version.file().filename())) {
                    plan.add(version);
                }
                queue.add(version);
            }
        }

        return plan;
    }

    /**
     * 选择一个符合实例环境的版本：游戏版本与加载器均匹配，取发布日期最新的一个；
     * 候选版本未声明游戏版本或加载器时视为通配匹配。
     */
    private static Optional<RemoteAddon.Version> select(List<RemoteAddon.Version> versions,
                                                        String gameVersion, Set<ModLoaderType> loaders) {
        return versions.stream()
                .filter(version -> version.gameVersions().isEmpty() || version.gameVersions().contains(gameVersion))
                .filter(version -> version.loaders().isEmpty()
                        || version.loaders().stream().anyMatch(loaders::contains))
                .max(Comparator.comparing(RemoteAddon.Version::datePublished));
    }

    /**
     * 基于 HMCL 下载服务与远程仓库的默认数据来源。
     *
     * @param downloadProvider 启动器当前的下载服务提供者
     */
    static Source remoteSource(DownloadProvider downloadProvider) {
        return dependency -> {
            RemoteAddon addon = dependency.load(downloadProvider);
            if (addon == RemoteAddon.BROKEN) {
                throw new IOException("前置项目不存在或已失效");
            }
            return new Project(addon.slug(),
                    addon.data().loadVersions(dependency.getRemoteModRepository(), downloadProvider).toList());
        };
    }
}

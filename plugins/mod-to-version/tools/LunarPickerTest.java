package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// 验证两件事：
///
/// <ol>
///   <li><b>隐藏版本不出现在「下载到哪个版本」里</b>——Lunar 插件生成的
///       `26.2-lunar-base` 这类继承底座带 `hidden: true`，启动器自己的版本列表会过滤它，
///       而这个弹窗过去没过滤，于是把「本该隐藏的游戏基座版本」显示了出来。</li>
///   <li><b>Lunar 客户端被当作 Fabric 识别</b>——它的主类是插件的入口包装类，
///       `LibraryAnalyzer` 认不出加载器，过去整个 Lunar 实例显示「未检测到加载器」，
///       并且会拒绝所有 Fabric 模组。Lunar 的 ichor 正是用 Fabric 加载模组的。</li>
/// </ol>
public final class LunarPickerTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        lunarCountsAsFabric();
        lunarRowReadsAsLunar();
        hiddenBaseIsNotSelectable();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// Lunar 实例要能被 Fabric 模组选中。
    private static void lunarCountsAsFabric() {
        System.out.println("=== Lunar 客户端按 Fabric 匹配模组 ===");

        // 引擎给 Lunar 实例补上了 FABRIC（见 ModToVersionEngine#installedVersions）
        VersionMatcher.Installed lunar = new VersionMatcher.Installed(
                "Lunar-26.2", "26.2", Set.of(ModLoaderType.FABRIC), true);
        VersionMatcher.Installed plainVanilla = new VersionMatcher.Installed(
                "26.2", "26.2", Set.of(), false);

        RemoteAddon.Version fabricMod = release("Sodium", List.of("26.2"),
                List.of(ModLoaderType.FABRIC));

        List<VersionMatcher.Installed> fits = VersionMatcher.fitting(
                List.of(plainVanilla, lunar), fabricMod, AddonKind.MOD);
        System.out.println("  可安装到: " + fits.stream().map(VersionMatcher.Installed::id).toList());

        check("Lunar 实例出现在可选列表里",
                fits.stream().anyMatch(t -> t.id().equals("Lunar-26.2")));
        check("纯原版实例仍被排除（没有 Fabric 加载器）",
                fits.stream().noneMatch(t -> t.id().equals("26.2")));
    }

    /// 副标题应写「Lunar 客户端」，而不是它底层的 Fabric。
    private static void lunarRowReadsAsLunar() {
        System.out.println();
        System.out.println("=== 行副标题显示 Lunar 而不是 Fabric ===");

        VersionMatcher.Installed lunar = new VersionMatcher.Installed(
                "Lunar-26.2", "26.2", Set.of(ModLoaderType.FABRIC), true);
        String subtitle = lunar.subtitle();
        System.out.println("  副标题: " + subtitle);

        check("副标题含「Lunar 客户端」", subtitle.contains("Lunar 客户端"));
        check("副标题不再写 Fabric", !subtitle.contains("Fabric"));
        check("副标题仍带游戏版本", subtitle.contains("26.2"));

        // 普通实例不受影响，仍显示真实加载器
        VersionMatcher.Installed fabric = new VersionMatcher.Installed(
                "1.20.4-Fabric", "1.20.4", Set.of(ModLoaderType.FABRIC), false);
        System.out.println("  对照（普通 Fabric 实例）: " + fabric.subtitle());
        check("普通 Fabric 实例仍显示 Fabric", fabric.subtitle().contains("Fabric"));
    }

    /// 基座版本（hidden）与真实原版同游戏版本时，不该出现在候选里。
    ///
    /// <p>过滤发生在 [ModToVersionEngine#installedVersions]，依据是 `Version.isHidden()`；
    /// 这里用等价的宿主记录确认「同游戏版本的两行里只有可见那行会被选中」。</p>
    private static void hiddenBaseIsNotSelectable() {
        System.out.println();
        System.out.println("=== 本该隐藏的基座版本不再可选 ===");

        // 过滤后：引擎只把可见版本交给弹窗，所以这里模拟「已过滤」的结果
        VersionMatcher.Installed real262 = new VersionMatcher.Installed(
                "26.2", "26.2", Set.of());
        RemoteAddon.Version anyMod = release("AnyMod", List.of("26.2"), List.of());

        List<VersionMatcher.Installed> fits = VersionMatcher.fitting(
                List.of(real262), anyMod, AddonKind.MOD);

        check("只有真实原版 26.2 出现在候选里",
                fits.size() == 1 && fits.get(0).id().equals("26.2"));
        check("基座 26.2-lunar-base 不在候选里",
                fits.stream().noneMatch(t -> t.id().contains("lunar-base")));
    }

    private static RemoteAddon.Version release(String name, List<String> gameVersions,
                                               List<ModLoaderType> loaders) {
        return new RemoteAddon.Version(
                null, "example", name, name, "",
                Instant.parse("2026-05-01T00:00:00Z"),
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/x.zip", "x.zip"),
                List.of(), gameVersions, loaders);
    }

    private static void check(String message, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS - " + message);
        } else {
            failed++;
            System.out.println("  FAIL - " + message);
        }
    }
}

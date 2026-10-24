package org.dsh.modtoversion;

import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;

import java.util.List;
import java.util.Optional;

/**
 * Verifies that each of the launcher's four content download dialogs is recognised, that
 * the release name is read out of its heading, and that the recognition follows the
 * launcher's language rather than a hard-coded Chinese prefix.
 *
 * <p>The heading is the only handle on the dialog: the dialog class is private, has no id
 * and no style class, and does not store the release it shows. If this parsing breaks,
 * the button silently never appears — so it is tested directly.</p>
 */
public final class DialogHeaderTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        try {
            recognisesEverySection();
            rejectsOtherDialogs();
            followsTheLauncherLanguage();
            identifiesTheSection();
        } finally {
            // Leave the launcher's locale as it was found.
            I18n.setLocale(SupportedLocale.getLocaleByName(null));
        }
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// Chinese, the launcher's default for a Chinese system: exactly what it builds with
    /// `i18n(<section>.download.title, version.name())`.
    private static void recognisesEverySection() {
        System.out.println("=== every content section is recognised (zh_CN) ===");
        I18n.setLocale(SupportedLocale.getLocaleByName("zh-CN"));

        record Case(AddonKind kind, String heading, String name) {
        }
        List<Case> cases = List.of(
                new Case(AddonKind.MOD, "模组下载 - Sodium 0.5.8", "Sodium 0.5.8"),
                new Case(AddonKind.RESOURCE_PACK, "资源包下载 - Faithful 32x", "Faithful 32x"),
                new Case(AddonKind.SHADER_PACK, "光影下载 - Complementary Reimagined",
                        "Complementary Reimagined"),
                new Case(AddonKind.WORLD, "世界下载 - SkyBlock Adventure", "SkyBlock Adventure"));

        for (Case c : cases) {
            System.out.println("  " + c.heading());
            check(c.kind() + " is recognised", DialogHeader.isSupported(c.heading()));
            check(c.kind() + " is identified as the section",
                    DialogHeader.kindOf(c.heading()).orElse(null) == c.kind());
            check(c.kind() + " release name is read out",
                    DialogHeader.releaseNameOf(c.heading()).orElse("").equals(c.name()));
        }

        System.out.println();
        System.out.println("=== a name containing dashes survives ===");
        // Real version names contain dashes and plus signs; only the prefix and the
        // suffix are stripped, so the rest must come through untouched.
        check("a name with dashes is kept whole",
                DialogHeader.releaseNameOf("模组下载 - v1.1.1+1.19")
                        .orElse("").equals("v1.1.1+1.19"));
        check("a name with a spaced dash is kept whole",
                DialogHeader.releaseNameOf("光影下载 - Complementary 1.0 - hotfix")
                        .orElse("").equals("Complementary 1.0 - hotfix"));

        System.out.println();
        System.out.println("=== surrounding whitespace ===");
        check("a padded heading is still recognised",
                DialogHeader.releaseNameOf("  资源包下载 - Faithful 32x  ")
                        .orElse("").equals("Faithful 32x"));
    }

    private static void rejectsOtherDialogs() {
        System.out.println();
        System.out.println("=== other dialogs are left alone ===");
        I18n.setLocale(SupportedLocale.getLocaleByName("zh-CN"));

        // A modpack is installed by a completely different path, so it must not get the
        // button even though it has the same dialog shape.
        check("the modpack dialog is not handled",
                !DialogHeader.isSupported("整合包下载 - All the Mods 9"));
        check("its section is not identified either",
                DialogHeader.kindOf("整合包下载 - All the Mods 9").isEmpty());
        check("a missing heading is not handled", !DialogHeader.isSupported(null));
        check("an empty heading is not handled", !DialogHeader.isSupported(""));
        check("a bare prefix with no name is not handled",
                !DialogHeader.isSupported("模组下载 - "));
        check("a bare prefix with only spaces is not handled",
                DialogHeader.releaseNameOf("世界下载 -  ").isEmpty());
        check("unrelated text is not handled",
                !DialogHeader.isSupported("确定要删除这个版本吗？"));
        check("the version-install dialog is not handled",
                !DialogHeader.isSupported("安装 Minecraft 1.20.4"));

        System.out.println();
        System.out.println("=== sections are not confused with each other ===");
        check("a resource pack heading is not read as a mod",
                DialogHeader.kindOf("资源包下载 - Faithful 32x")
                        .orElse(null) == AddonKind.RESOURCE_PACK);
        check("a world heading is not read as a shader",
                DialogHeader.kindOf("世界下载 - SkyBlock").orElse(null) == AddonKind.WORLD);
    }

    /// The point of reading the templates from the launcher's bundle: the button must
    /// appear for users who do not run a Chinese or English launcher.
    private static void followsTheLauncherLanguage() {
        System.out.println();
        System.out.println("=== the headings follow the launcher language ===");

        // Each entry: language tag, the mod heading, the expected release name.
        record LocaleCase(String tag, String heading, String name) {
        }
        List<LocaleCase> cases = List.of(
                new LocaleCase("en", "Download Mod - Sodium 0.5.8", "Sodium 0.5.8"),
                new LocaleCase("zh-TW", "模組下載 - Sodium 0.5.8", "Sodium 0.5.8"),
                new LocaleCase("ja", "Modダウンロード- Sodium 0.5.8", "Sodium 0.5.8"),
                new LocaleCase("ru", "Скачать мод - Sodium 0.5.8", "Sodium 0.5.8"),
                new LocaleCase("es", "Descargar mod - Sodium 0.5.8", "Sodium 0.5.8"),
                new LocaleCase("uk", "Завантажити мод - Sodium 0.5.8", "Sodium 0.5.8"),
                new LocaleCase("ar", "تنزيل مود - Sodium 0.5.8", "Sodium 0.5.8"),
                new LocaleCase("lzh", "引改囊 - Sodium 0.5.8", "Sodium 0.5.8"));

        for (LocaleCase c : cases) {
            SupportedLocale locale = SupportedLocale.getLocaleByName(c.tag());
            I18n.setLocale(locale);
            DialogHeader.Heading shape = DialogHeader.headingOf(AddonKind.MOD);
            String prefix = shape == null ? "<none>" : shape.prefix();
            System.out.println("  " + c.tag() + ": mod prefix = [" + prefix + "]");
            check(c.tag() + ": the mod heading is recognised",
                    DialogHeader.isSupported(c.heading()));
            check(c.tag() + ": the release name is read out",
                    DialogHeader.releaseNameOf(c.heading()).orElse("<none>").equals(c.name()));
        }

        System.out.println();
        System.out.println("=== the other sections follow the language too ===");
        I18n.setLocale(SupportedLocale.getLocaleByName("ru"));
        check("a Russian resource pack heading is recognised",
                DialogHeader.kindOf("Скачать пакет ресурсов - Faithful")
                        .orElse(null) == AddonKind.RESOURCE_PACK);
        check("a Russian world heading is recognised",
                DialogHeader.kindOf("Скачать мир - SkyBlock").orElse(null) == AddonKind.WORLD);

        I18n.setLocale(SupportedLocale.getLocaleByName("zh-CN"));
    }

    /// The section decides where the addon is installed, so getting it wrong would put a
    /// shader into the mods folder.
    private static void identifiesTheSection() {
        System.out.println();
        System.out.println("=== the section decides the install target ===");
        check("mods go into mods",
                "mods".equals(AddonKind.MOD.directoryName()));
        check("resource packs go into resourcepacks",
                "resourcepacks".equals(AddonKind.RESOURCE_PACK.directoryName()));
        check("shaders go into shaderpacks",
                "shaderpacks".equals(AddonKind.SHADER_PACK.directoryName()));
        check("a world is not a plain file drop",
                AddonKind.WORLD.directoryName() == null && AddonKind.WORLD.isWorld());
        check("only the mod section is loader-sensitive",
                AddonKind.MOD.loaderSensitive()
                        && !AddonKind.RESOURCE_PACK.loaderSensitive()
                        && !AddonKind.SHADER_PACK.loaderSensitive()
                        && !AddonKind.WORLD.loaderSensitive());
        check("no other section is mistaken for a world",
                !AddonKind.MOD.isWorld() && !AddonKind.RESOURCE_PACK.isWorld()
                        && !AddonKind.SHADER_PACK.isWorld());

        System.out.println();
        System.out.println("=== an unknown key still yields a usable shape ===");
        // Every section must expose a prefix, otherwise the plugin would silently do
        // nothing on a launcher whose language file omits the key.
        for (AddonKind kind : AddonKind.values()) {
            Optional<DialogHeader.Heading> shape =
                    Optional.ofNullable(DialogHeader.headingOf(kind));
            check(kind + " has a prefix", shape.isPresent() && !shape.get().prefix().isEmpty());
        }
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

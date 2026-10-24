package org.dsh.modtoversion;

/**
 * The launcher download sections this plugin can install into.
 *
 * <p>HMCL's download page offers four content sections that all show the same
 * "download this version" dialog — mods, resource packs, shader packs and worlds.
 * They differ in three ways that matter here:</p>
 *
 * <ul>
 *   <li><b>the dialog heading</b> — each section has its own i18n key, which is the
 *       only handle on the dialog (see {@link DialogHeader});</li>
 *   <li><b>the target directory</b> — {@code mods}, {@code resourcepacks},
 *       {@code shaderpacks}; a world is not a file at all but a directory under
 *       {@code saves}, so it has no target directory name and is unpacked instead
 *       (see {@link AddonInstaller});</li>
 *   <li><b>loader matching</b> — only a mod's release declares game loaders that
 *       have to be present in the instance. A resource pack or shader lists loaders
 *       too, but only ever as things the launcher cannot detect ({@code minecraft},
 *       {@code optifine}, {@code iris}), so requiring a match would hide every row.
 *       A world declares none at all.</li>
 * </ul>
 */
enum AddonKind {

    /// The 「模组」 section: installed into {@code mods}, loader-sensitive.
    MOD("mods.download.title", "mods", "模组", "mods 目录", true,
            "模组下载 - %1s"),

    /// The 「资源包」 section: installed into {@code resourcepacks}.
    RESOURCE_PACK("resourcepack.download.title", "resourcepacks", "资源包",
            "resourcepacks 目录", false, "资源包下载 - %1s"),

    /// The 「光影」 section: installed into {@code shaderpacks}.
    SHADER_PACK("shaderpack.download.title", "shaderpacks", "光影", "shaderpacks 目录",
            false, "光影下载 - %1s"),

    /// The 「世界」 section: unpacked into {@code saves/<world name>}.
    WORLD("world.download.title", null, "世界", "saves 目录", false,
            "世界下载 - %1s");

    /// The i18n key whose formatted value is the dialog heading.
    private final String titleKey;

    /// The game directory a downloaded file belongs in, or null when the addon is not
    /// a plain file drop (worlds).
    private final String directoryName;

    /// How the section is called inside this plugin's own dialogs, e.g. 「资源包」.
    private final String noun;

    /// Where the addon ends up, phrased for the picker, e.g. “resourcepacks 目录”.
    private final String targetDescription;

    /// True when a release's declared game loaders have to match the instance.
    private final boolean loaderSensitive;

    /// The {@code zh_CN} heading template, used only if the launcher's own resource
    /// bundle cannot be read at all.
    private final String fallbackTemplate;

    AddonKind(String titleKey, String directoryName, String noun, String targetDescription,
              boolean loaderSensitive, String fallbackTemplate) {
        this.titleKey = titleKey;
        this.directoryName = directoryName;
        this.noun = noun;
        this.targetDescription = targetDescription;
        this.loaderSensitive = loaderSensitive;
        this.fallbackTemplate = fallbackTemplate;
    }

    /// The i18n key of this section's download dialog heading.
    String titleKey() {
        return titleKey;
    }

    /// The directory under the instance this addon is installed into, or null when it
    /// is not installed as a single file.
    ///
    /// @return {@code "mods"}, {@code "resourcepacks"}, {@code "shaderpacks"}, or null
    ///         for [AddonKind#WORLD]
    String directoryName() {
        return directoryName;
    }

    /// This section's name as used in this plugin's own sentences.
    String noun() {
        return noun;
    }

    /// Where the addon is installed, phrased to follow “下载到哪个版本的”.
    String targetDescription() {
        return targetDescription;
    }

    /// True when the release's declared loaders must match the instance's.
    ///
    /// <p>Only mods: a resource pack or shader advertises loaders such as
    /// {@code minecraft} or {@code optifine} that the launcher does not report, and a
    /// world advertises none.</p>
    boolean loaderSensitive() {
        return loaderSensitive;
    }

    /// True when the addon has to be unpacked into {@code saves} rather than dropped
    /// into a directory as a file.
    boolean isWorld() {
        return this == WORLD;
    }

    /// The heading template to use when the launcher's resource bundle is unavailable.
    String fallbackTemplate() {
        return fallbackTemplate;
    }
}

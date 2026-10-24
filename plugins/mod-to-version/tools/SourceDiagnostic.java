package org.dsh.modtoversion;

import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Shows which remote addon sources this launcher build can actually use, and why.
 *
 * <p>Not a test of the plugin: a diagnostic that prints the state the download page derives
 * its source list from, so "only Modrinth is listed" can be explained rather than guessed
 * at. {@code HMCLLocalizedDownloadListPage} adds CurseForge to the list only when
 * {@code CurseForgeRemoteAddonRepository.isAvailable()} is true, which is nothing more than
 * "an API key was compiled in".</p>
 */
public final class SourceDiagnostic {

    public static void main(String[] args) {
        System.out.println("=== CurseForge ===");
        String apiKey = CurseForgeRemoteAddonRepository.API_KEY;
        System.out.println("  API_KEY compiled in : " + (apiKey.isEmpty() ? "<empty>" : "<set>"));
        System.out.println("  isAvailable()       : " + CurseForgeRemoteAddonRepository.isAvailable());
        System.out.println("  endpoint            : " + prefixOf(CurseForgeRemoteAddonRepository.class));

        System.out.println();
        System.out.println("=== Modrinth ===");
        // Modrinth needs no key, which is why it is always offered.
        System.out.println("  requires an API key : no");
        System.out.println("  endpoint            : " + prefixOf(ModrinthRemoteAddonRepository.class));

        System.out.println();
        System.out.println("=== what each download section can therefore offer ===");
        record Section(String name, boolean modrinth, boolean curseForge) {
        }
        List<Section> sections = List.of(
                new Section("mods (模组)", true, true),
                new Section("resource packs (资源包)", true, true),
                new Section("shaders (光影)", true, true),
                new Section("modpacks (整合包)", true, true),
                new Section("worlds (世界)", false, true));
        for (Section section : sections) {
            boolean cf = section.curseForge() && CurseForgeRemoteAddonRepository.isAvailable();
            StringBuilder sources = new StringBuilder();
            if (section.modrinth()) {
                sources.append("Modrinth");
            }
            if (cf) {
                if (sources.length() > 0) {
                    sources.append(" + ");
                }
                sources.append("CurseForge");
            }
            System.out.printf("  %-26s -> %s%s%n", section.name(),
                    sources.length() == 0 ? "<none>" : sources,
                    section.curseForge() && !cf ? "  (CurseForge dropped: no API key)" : "");
        }

        System.out.println();
        System.out.println("=== conclusion ===");
        if (!CurseForgeRemoteAddonRepository.isAvailable()) {
            System.out.println("  This build has no hmcl.curseforge.apikey, so CurseForge is not");
            System.out.println("  offered anywhere and every section shows only Modrinth.");
            System.out.println("  The worlds section has no Modrinth backend at all, so it ends up");
            System.out.println("  with no selectable source.");
        } else {
            System.out.println("  Both sources are compiled in and should be offered.");
        }
    }

    /// The API prefix a repository talks to, read reflectively for display.
    private static String prefixOf(Class<?> type) {
        try {
            Field field = type.getDeclaredField("PREFIX");
            field.setAccessible(true);
            return String.valueOf(field.get(null));
        } catch (Throwable t) {
            return "<unknown>";
        }
    }
}

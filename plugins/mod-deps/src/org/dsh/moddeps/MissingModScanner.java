package org.dsh.moddeps;

import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Scans a crash log for "missing mod" evidence and extracts the mod ids.
///
/// <p>Priority is given to Fabric Loader's machine-readable lines, which survive
/// localization and even carry the exact fix:</p>
/// <ul>
///   <li>{@code Fix: add [add:sodium 0.9.2+mc26.3 ...]} — mod id + pinned version;</li>
///   <li>{@code Immediate reason/Reason: [... {depends sodium @ [...]} ...]}</li>
/// </ul>
/// <p>Then the human-readable shapes, English and localized (Chinese) alike:</p>
/// <ul>
///   <li>{@code Mod 'x' requires 'y'} / {@code mod "x" requires mod "y"} /
///       {@code "y" is missing}</li>
///   <li>Chinese: {@code 模组 'BBE' (betterblockentities) ... 需要 sodium 的 ... 但没有安装它！}</li>
///   <li>Forge: {@code Missing or unsupported mandatory dependencies:} block lines
///       {@code - modid:version}, and {@code ... is missing mods.toml file} naming.</li>
/// </ul>
///
/// <p>Pure and headless-safe: no launcher classes, no I/O — the caller passes the log text.</p>
public final class MissingModScanner {

    /// A mod the crash says is unusable: its id, the exact version to install when the log
    /// pins one, and the version currently installed when the log names it (a version switch
    /// rather than a plain install).
    public record MissingMod(String id, String pinnedVersion, String installedVersion) {

        public MissingMod(String id, String pinnedVersion) {
            this(id, pinnedVersion, null);
        }
    }

    // Fabric Fix line: Fix: add [add:sodium 0.9.2+mc26.3 ([[0.9.2+mc26.3,0.9.2+mc26.3]])], ...
    private static final Pattern FABRIC_FIX = Pattern.compile("add:([\\w\\-.]+)\\s+([\\w\\-.+]+)");
    // Fabric Fix line: Fix: update [update:sodium 0.9.2+mc26.3] in Mods
    private static final Pattern FABRIC_FIX_UPDATE = Pattern.compile("update:([\\w\\-.]+)\\s+([\\w\\-.+]+)");
    // Fabric Fix line (real 1.21.11 output):
    // Fix: add [], remove [], replace [[sodium 0.8.14+mc1.21.11] -> add:sodium 0.8.3 ([[0.8.3,0.9-)])]
    private static final Pattern FABRIC_FIX_REPLACE_ARROW = Pattern.compile(
            "replace\\s*\\[\\[\\s*([\\w\\-.]+)\\s+([\\w\\-.+]+)\\s*\\]\\s*->\\s*add:([\\w\\-.]+)\\s+([\\w\\-.+]+)");
    // Fabric Fix line: Fix: replace [remove:sodium 0.9.3-alpha.1] with [add:sodium 0.9.2+mc26.3]
    private static final Pattern FABRIC_FIX_REPLACE = Pattern.compile(
            "replace\\s*\\[remove:([\\w\\-.]+)(?:\\s+([\\w\\-.+]+))?\\]\\s*with\\s*\\[add:([\\w\\-.]+)\\s+([\\w\\-.+]+)\\]");
    // Fabric human-readable: Replace mod 'Sodium' (sodium) 0.9.3-alpha.1 with version 0.9.2+mc26.3 of 'sodium'
    private static final Pattern FABRIC_REPLACE_PROSE = Pattern.compile(
            "[Rr]eplace mod ['\"][^'\"]+['\"] \\(([\\w\\-.]+)\\) ([\\w\\-.+]+) with version ([\\w\\-.+]+)");
    // Fabric: "requires version 0.6.0 or later of sodium, which is missing!"
    private static final Pattern FABRIC_REQUIRES_VERSION = Pattern.compile(
            "requires version ([\\w\\-.+]+)(?:\\s+or\\s+(?:later|newer))? of (?:mod )?['\"]?([\\w\\-.]+)['\"]?");
    // Fabric Reason lines: {depends sodium @ [>=0.9.2-beta.2]}
    private static final Pattern FABRIC_DEPENDS = Pattern.compile("depends ([\\w\\-.]+) @");
    // Fabric (English): Mod 'sodium-extra' requires 'sodium' ...
    private static final Pattern[] FABRIC_REQUIRES = {
            Pattern.compile("Mod ['\"]([\\w\\-.]+)['\"](?: \\([\\w\\-.]+\\))? requires (?:any version of )?['\"]?([\\w\\-.]+)['\"]?"),
            Pattern.compile("mod \"([\\w\\-.]+)\"(?: \\([\\w\\-.]+\\))? requires (?:any version of )?mod \"([\\w\\-.]+)\""),
    };
    // Fabric missing (English): missing: "y" / mod "y" is missing
    private static final Pattern[] FABRIC_MISSING = {
            Pattern.compile("(?:missing|Missing)[:,]?\\s*(?:mod )?['\"]([\\w\\-.]+)['\"]"),
            // The gap is capped so a long sentence cannot attribute "is missing" to an
            // unrelated mod quoted earlier in the same line.
            Pattern.compile("['\"]([\\w\\-.]+)['\"][^'\\n]{0,40}? is missing"),
    };
    // Fabric localized (Chinese): 需要 sodium 的 ... 但没有安装它！
    private static final Pattern CHINESE_NEEDS = Pattern.compile(
            "需要\\s*([\\w\\-.]+)\\s*的");
    private static final Pattern CHINESE_INSTALL = Pattern.compile(
            "安装\\s*([\\w\\-.]+)，\\s*([\\w\\-.+]+)\\s*版本");
    // Forge: "- modid:[version] ..." lines inside the mandatory-dependencies block
    private static final Pattern FORGE_DEPENDENCY_LINE = Pattern.compile("^\\s*-\\s*([\\w\\-.]+)\\s*:");
    // Forge: "Mod File ... missing mods.toml file" names the file; extract a slug-ish stem
    private static final Pattern FORGE_MISSING_FILE = Pattern.compile(
            "Mod File: ([\\w\\-.]+?)_?(\\d[\\w\\-.]*)?\\.jar[^\\n]* is missing mods.toml");

    private MissingModScanner() {
    }

    /// Returns the missing mods deduplicated by id, in order of first appearance;
    /// a later mention carrying a pinned version wins over one without.
    public static List<MissingMod> scan(String logText, Set<ModLoaderType> loaders) {
        Map<String, MissingMod> found = new LinkedHashMap<>();
        if (logText == null || logText.isEmpty())
            return List.of();

        boolean forge = loaders.isEmpty() || loaders.stream()
                .anyMatch(loader -> loader == ModLoaderType.FORGE || loader == ModLoaderType.NEO_FORGE);

        for (String line : logText.split("\\r?\\n")) {
            // Fabric machine-readable fix: add:<id> <version>
            Matcher fix = FABRIC_FIX.matcher(line);
            while (fix.find()) {
                put(found, new MissingMod(fix.group(1), fix.group(2)));
            }
            // version switches: the log names both the installed and the required version
            Matcher fixReplaceArrow = FABRIC_FIX_REPLACE_ARROW.matcher(line);
            while (fixReplaceArrow.find()) {
                put(found, new MissingMod(fixReplaceArrow.group(3), fixReplaceArrow.group(4),
                        fixReplaceArrow.group(2)));
            }
            Matcher fixReplace = FABRIC_FIX_REPLACE.matcher(line);
            while (fixReplace.find()) {
                put(found, new MissingMod(fixReplace.group(3), fixReplace.group(4), fixReplace.group(2)));
            }
            Matcher fixUpdate = FABRIC_FIX_UPDATE.matcher(line);
            while (fixUpdate.find()) {
                put(found, new MissingMod(fixUpdate.group(1), fixUpdate.group(2)));
            }
            Matcher replaceProse = FABRIC_REPLACE_PROSE.matcher(line);
            while (replaceProse.find()) {
                put(found, new MissingMod(replaceProse.group(1), replaceProse.group(3), replaceProse.group(2)));
            }
            Matcher requiresVersion = FABRIC_REQUIRES_VERSION.matcher(line);
            while (requiresVersion.find()) {
                put(found, new MissingMod(requiresVersion.group(2), requiresVersion.group(1)));
            }
            // Fabric machine-readable reason: {depends <id> @ ...}
            Matcher depends = FABRIC_DEPENDS.matcher(line);
            while (depends.find()) {
                put(found, new MissingMod(depends.group(1), null));
            }
            // English requires
            for (Pattern pattern : FABRIC_REQUIRES) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    String required = matcher.group(2);
                    put(found, new MissingMod(required, null));
                }
            }
            // English missing
            for (Pattern missingPattern : FABRIC_MISSING) {
                Matcher missing = missingPattern.matcher(line);
                while (missing.find()) {
                    put(found, new MissingMod(missing.group(1), null));
                }
            }
            // Chinese localized lines
            Matcher needs = CHINESE_NEEDS.matcher(line);
            while (needs.find()) {
                put(found, new MissingMod(needs.group(1), null));
            }
            Matcher install = CHINESE_INSTALL.matcher(line);
            while (install.find()) {
                put(found, new MissingMod(install.group(1), install.group(2)));
            }
            // Forge mandatory-dependencies block
            if (forge && line.contains("Missing or unsupported mandatory dependencies")) {
                // the following indented lines carry "- modid:..." entries
                String[] allLines = logText.split("\\r?\\n", -1);
                int lineIndex = java.util.Arrays.asList(allLines).indexOf(line);
                for (int i = lineIndex + 1; i < allLines.length; i++) {
                    String follow = allLines[i];
                    if (follow.isBlank())
                        continue;
                    Matcher entry = FORGE_DEPENDENCY_LINE.matcher(follow);
                    if (entry.find() && isPlausibleId(entry.group(1))) {
                        put(found, new MissingMod(entry.group(1), null));
                    } else {
                        break;
                    }
                }
            }
            // Forge missing mods.toml file
            if (forge) {
                Matcher file = FORGE_MISSING_FILE.matcher(line);
                if (file.find()) {
                    String stem = file.group(1).toLowerCase(Locale.ROOT)
                            .replace('-', '_')
                            .replaceAll("_(forge|fabric|neoforge)$", "");
                    put(found, new MissingMod(stem, null));
                }
            }
        }

        List<MissingMod> result = new ArrayList<>();
        for (MissingMod missingMod : found.values())
            if (isPlausibleId(missingMod.id()))
                result.add(missingMod);
        return result;
    }

    /// Keeps the richest mention per id: a later line may carry the pinned or the installed
    /// version the earlier one lacked, and both are needed to switch the mod's version.
    private static void put(Map<String, MissingMod> found, MissingMod candidate) {
        if (!isPlausibleId(candidate.id()))
            return;
        MissingMod existing = found.get(candidate.id());
        if (existing == null) {
            found.put(candidate.id(), candidate);
            return;
        }
        found.put(candidate.id(), new MissingMod(candidate.id(),
                existing.pinnedVersion() != null ? existing.pinnedVersion() : candidate.pinnedVersion(),
                existing.installedVersion() != null ? existing.installedVersion() : candidate.installedVersion()));
    }

    /// Cheap plausibility gate: mod ids are lowercase-ish tokens with at least two
    /// characters; filters "minecraft" and pure noise.
    static boolean isPlausibleId(String id) {
        if (id == null || id.length() < 2 || id.length() > 64)
            return false;
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.equals("minecraft") || lower.equals("java") || lower.equals("forge")
                || lower.equals("neoforge") || lower.equals("fml")
                || lower.equals("fabricloader") || lower.equals("mixinextras"))
            return false;
        return lower.matches("[a-z0-9_\\-.]+");
    }
}

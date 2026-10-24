import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.dsh.moddeps.ModDepsPlugin;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Resolves the prerequisite from the live Modrinth API with the real crash pin, proving the
/// version chosen is the build the log demanded rather than the newest alpha.
public final class LiveResolveTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/sodium/version"))
                .header("User-Agent", "hmcl-mod-deps-test/1.0")
                .timeout(java.time.Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "Modrinth API reachable (HTTP " + response.statusCode() + ")");

        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
        List<RemoteAddon.Version> versions = new ArrayList<>();
        for (var element : array) {
            JsonObject v = element.getAsJsonObject();
            JsonObject file = v.getAsJsonArray("files").get(0).getAsJsonObject();
            List<String> games = new ArrayList<>();
            v.getAsJsonArray("game_versions").forEach(g -> games.add(g.getAsString()));
            // Same mapping HMCL's Modrinth repository applies, so the test sees the loaders the
            // launcher would see.
            List<ModLoaderType> loaders = new ArrayList<>();
            v.getAsJsonArray("loaders").forEach(l -> {
                switch (l.getAsString().toLowerCase(java.util.Locale.ROOT)) {
                    case "fabric" -> loaders.add(ModLoaderType.FABRIC);
                    case "forge" -> loaders.add(ModLoaderType.FORGE);
                    case "neoforge" -> loaders.add(ModLoaderType.NEO_FORGE);
                    case "quilt" -> loaders.add(ModLoaderType.QUILT);
                    case "liteloader" -> loaders.add(ModLoaderType.LITE_LOADER);
                    default -> { }
                }
            });
            versions.add(new RemoteAddon.Version(
                    null, v.get("project_id").getAsString(), v.get("name").getAsString(),
                    v.get("version_number").getAsString(), "",
                    Instant.parse(v.get("date_published").getAsString()), null,
                    new RemoteAddon.File(Map.of(), file.get("url").getAsString(), file.get("filename").getAsString()),
                    List.of(), games, loaders));
        }
        System.out.println("   fetched " + versions.size() + " sodium releases");

        var picked = ModDepsPlugin.pickVersion(versions, "0.9.2+mc26.3", "26.3", Set.of(ModLoaderType.FABRIC));
        check(picked.isPresent(), "a build is resolved for the pinned version");
        String filename = picked.map(v -> v.file().filename()).orElse("");
        System.out.println("   picked: " + filename + "  (" + picked.map(RemoteAddon.Version::version).orElse("") + ")");
        check("sodium-fabric-0.9.2+mc26.3.jar".equals(filename),
                "the pinned 0.9.2 build for 26.3 is chosen, not the 0.9.3 alpha or another game version");

        var newest = ModDepsPlugin.pickVersion(versions, null, "26.3", Set.of(ModLoaderType.FABRIC));
        check(newest.isPresent() && newest.get().gameVersions().contains("26.3"),
                "without a pin the newest build for the running game version is chosen");

        // End-to-end version switch with live data: the crash names both versions, the alpha is
        // installed, and the plan must switch to the required build and retire the alpha.
        java.nio.file.Path mods = java.nio.file.Files.createTempDirectory("live-switch");
        java.nio.file.Files.writeString(mods.resolve("sodium-fabric-0.9.3-alpha.1+mc26.3.jar"), "installed");
        java.nio.file.Files.writeString(mods.resolve("iris-fabric-1.11.7+mc26.3.jar"), "unrelated");
        var planned = ModDepsPlugin.planForCrash(
                org.dsh.moddeps.MissingModScanner.scan(
                        "Fix: replace [remove:sodium 0.9.3-alpha.1] with [add:sodium 0.9.2+mc26.3] in Mods",
                        Set.of(ModLoaderType.FABRIC)),
                "26.3", Set.of(ModLoaderType.FABRIC), mods, id -> versions,
                line -> System.out.println("   " + line));
        check(planned.downloadable().size() == 1 && "sodium-fabric-0.9.2+mc26.3.jar"
                        .equals(planned.downloadable().get(0).version().file().filename()),
                "the version switch targets the required build: "
                        + planned.downloadable().stream().map(p -> p.version().file().filename()).toList());
        var stale = ModDepsPlugin.outdatedByFilename(mods, planned.downloadable(), List.of());
        check(stale.size() == 1 && "sodium-fabric-0.9.3-alpha.1+mc26.3.jar"
                        .equals(stale.get(0).getFileName().toString()),
                "only the outdated build is switched out: " + stale);

        // The user's actual 1.21.11 crash: iris needs sodium 0.8.x, sodium 0.8.14 declares it
        // breaks iris, and Fabric asks to replace 0.8.14 with 0.8.3.
        java.nio.file.Path mods1211 = java.nio.file.Files.createTempDirectory("live-1211");
        java.nio.file.Files.writeString(mods1211.resolve("sodium-fabric-0.8.14+mc1.21.11.jar"), "installed");
        java.nio.file.Files.writeString(mods1211.resolve("sodium-extra-fabric-0.8.3+mc1.21.11.jar"), "unrelated");
        java.nio.file.Files.writeString(mods1211.resolve("iris-fabric-1.10.5+mc1.21.11.jar"), "unrelated");
        var plan1211 = ModDepsPlugin.planForCrash(
                org.dsh.moddeps.MissingModScanner.scan(
                        "Immediate reason: [HARD_DEP iris 1.10.5+mc1.21.11 {depends sodium @ [0.8.x]}, "
                                + "NEG_HARD_DEP sodium 0.8.14+mc1.21.11 {breaks iris @ [<=1.10.7]}, "
                                + "ROOT_FORCELOAD_SINGLE iris 1.10.5+mc1.21.11]\n"
                                + "Fix: add [], remove [], replace [[sodium 0.8.14+mc1.21.11] -> add:sodium 0.8.3 ([[0.8.3,0.9-)])]",
                        Set.of(ModLoaderType.FABRIC)),
                "1.21.11", Set.of(ModLoaderType.FABRIC), mods1211, id -> versions,
                line -> System.out.println("   " + line));
        check(plan1211.downloadable().size() == 1 && "sodium-fabric-0.8.3+mc1.21.11.jar"
                        .equals(plan1211.downloadable().get(0).version().file().filename()),
                "1.21.11: the required 0.8.3 build is selected: "
                        + plan1211.downloadable().stream().map(p -> p.version().file().filename()).toList());
        var stale1211 = ModDepsPlugin.outdatedByFilename(mods1211, plan1211.downloadable(), List.of());
        check(stale1211.size() == 1 && "sodium-fabric-0.8.14+mc1.21.11.jar"
                        .equals(stale1211.get(0).getFileName().toString()),
                "1.21.11: only the 0.8.14 build is switched out, sodium-extra is untouched: " + stale1211);

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + " - " + label);
        if (!ok) failures++;
    }
}

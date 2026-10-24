import org.dsh.moddeps.MissingModScanner;
import org.dsh.moddeps.MissingModScanner.MissingMod;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/// Runs the scanner over the REAL Fabric 0.19.5 crash log captured on this machine.
public final class RealLogTest {
    public static void main(String[] args) throws Exception {
        Path log = Path.of("E:/game/game/minecraft/.minecraft/versions/Fabulously Optimized/logs/latest.log");
        String text = Files.readString(log, java.nio.charset.Charset.forName("GB18030"));
        List<MissingMod> found = MissingModScanner.scan(text, Set.of(ModLoaderType.FABRIC));
        System.out.println("found: " + found);
        boolean ok = found.stream().anyMatch(m -> m.id().equals("sodium")
                && "0.9.2+mc26.3".equals(m.pinnedVersion()));
        System.out.println(ok ? "PASS - sodium detected with pinned version 0.9.2+mc26.3"
                              : "FAIL - expected sodium pinned to 0.9.2+mc26.3");
        System.exit(ok ? 0 : 1);
    }
}

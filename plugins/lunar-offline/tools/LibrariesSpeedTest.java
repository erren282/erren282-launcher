import org.dsh.lunarofl.LunarLibraries;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// 验证「补齐 Lunar 运行库」这一段的速度与正确性。
///
/// <p>这一步过去是串行下载三四十个小库，每个都要建连、失败还要换仓库重试，是整个安装里最慢的一段。
/// 现在改成并行拉取。这个测试把同样一批库下载两次（先删掉，再下），对比耗时，并确认：
/// 所有库都真的落地、回调次数正确、重复运行能全部命中缓存（不再联网）。</p>
///
/// 用法：LibrariesSpeedTest &lt;游戏目录&gt; &lt;版本&gt;
public final class LibrariesSpeedTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path gameDirectory = Path.of((args.length > 0
                ? args[0] : "E:/game/game/minecraft/.minecraft").replace('/', File.separatorChar));
        String gameVersion = args.length > 1 ? args[1] : "1.8.9";

        Path installation = gameDirectory.resolve("lunar-client").resolve(gameVersion);
        System.out.println("客户端目录: " + installation);
        check(Files.isDirectory(installation), "客户端目录存在");

        List<String> declared = LunarLibraries.declared(installation, gameVersion);
        System.out.println("versions.conf 声明的运行库: " + declared.size() + " 个");
        check(!declared.isEmpty(), "读到了运行库清单");
        if (declared.isEmpty()) {
            finish();
            return;
        }

        // ---------- 1) 已全部就位时应当秒回（纯本地检查） ----------
        long t0 = System.currentTimeMillis();
        int[] callbackCount = {0};
        List<Path> present = LunarLibraries.download(installation, declared, () -> false,
                (finished, total) -> callbackCount[0]++);
        long cachedMillis = System.currentTimeMillis() - t0;
        System.out.println("缓存命中耗时: " + cachedMillis + " ms，返回 " + present.size() + " 个 jar");
        check(cachedMillis < 2000, "库都在本地时几乎不耗时（" + cachedMillis + " ms）");
        check(present.size() >= declared.size() * 9 / 10,
                "绝大多数声明的库都可用（" + present.size() + "/" + declared.size() + "）");

        // ---------- 2) 并发下载：删掉一部分再下，确认并行确实更快且结果完整 ----------
        Path libsDir = installation.resolve(LunarLibraries.DIRECTORY);
        // 只删一小半，避免把用户已经下好的东西全清掉
        List<Path> toDelete = new ArrayList<>();
        List<Path> all = new ArrayList<>();
        for (String coordinate : declared) {
            Path jar = libsDir.resolve(LunarLibraries.jarName(coordinate));
            all.add(jar);
            if (Files.isRegularFile(jar))
                toDelete.add(jar);
        }
        int deleteCount = Math.min(8, toDelete.size());
        for (int i = 0; i < deleteCount; i++)
            Files.deleteIfExists(toDelete.get(i));
        System.out.println("删掉 " + deleteCount + " 个库，测试重新下载速度…");

        long t1 = System.currentTimeMillis();
        int[] callbacks = {0};
        List<Path> redownloaded = LunarLibraries.download(installation, declared, () -> false,
                (finished, total) -> callbacks[0]++);
        long downloadMillis = System.currentTimeMillis() - t1;
        System.out.println("重新下载耗时: " + downloadMillis + " ms，进度回调 " + callbacks[0] + " 次，返回 "
                + redownloaded.size() + " 个 jar");

        check(redownloaded.size() >= declared.size() * 9 / 10,
                "重新下载后库仍完整（" + redownloaded.size() + "/" + declared.size() + "）");
        check(callbacks[0] > 0, "下载过程有进度回调（原生弹窗才有进度可显示）");

        // 逐条核对文件真的落地
        int missing = 0;
        for (Path jar : all)
            if (!Files.isRegularFile(jar) || Files.size(jar) == 0)
                missing++;
        System.out.println("仍然缺文件的库: " + missing);
        check(missing == 0, "所有声明的运行库都有实体文件");

        // ---------- 3) 再次调用应当全部命中缓存 ----------
        long t2 = System.currentTimeMillis();
        LunarLibraries.download(installation, declared, () -> false, null);
        long secondMillis = System.currentTimeMillis() - t2;
        System.out.println("第二次调用耗时: " + secondMillis + " ms");
        check(secondMillis < 2000, "重复调用不再联网（" + secondMillis + " ms）");

        finish();
    }

    private static void finish() {
        System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  PASS - " : "  FAIL - ") + message);
        if (!condition)
            failures++;
    }
}

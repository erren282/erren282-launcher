import org.dsh.lunarofl.LunarLibraries;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// 量化「补齐 Lunar 运行库」并行化前后的差距：把同一批库真的删掉，分别用「串行」和
/// 当前的并行实现各下载一次，比较耗时。
///
/// <p>串行版本在这里就地模拟（逐条调 `download`，每次只传一个坐标），这样对比的是同一网络
/// 条件下的真实差异，而不是拍脑袋的估计。</p>
///
/// 用法：ParallelBenchmark &lt;游戏目录&gt; &lt;版本&gt;
public final class ParallelBenchmark {

    public static void main(String[] args) throws Exception {
        Path gameDirectory = Path.of((args.length > 0
                ? args[0] : "E:/game/game/minecraft/.minecraft").replace('/', File.separatorChar));
        String gameVersion = args.length > 1 ? args[1] : "1.8.9";

        Path installation = gameDirectory.resolve("lunar-client").resolve(gameVersion);
        Path libsDir = installation.resolve(LunarLibraries.DIRECTORY);
        List<String> declared = LunarLibraries.declared(installation, gameVersion);

        // 挑一批体积较小、下载快的库做样本，避免测试太久
        List<String> sample = new ArrayList<>();
        for (String coordinate : declared) {
            Path jar = libsDir.resolve(LunarLibraries.jarName(coordinate));
            if (Files.isRegularFile(jar) && Files.size(jar) < 400 * 1024)
                sample.add(coordinate);
            if (sample.size() >= 12)
                break;
        }
        System.out.println("样本库数量: " + sample.size() + "（都小于 400 KB，贴近真实的小库场景）");
        if (sample.size() < 4) {
            System.out.println("样本太少，跳过基准");
            return;
        }

        // ---------- 串行：每次只传一个坐标 ----------
        deleteAll(libsDir, sample);
        long serialStart = System.currentTimeMillis();
        for (String coordinate : sample)
            LunarLibraries.download(installation, List.of(coordinate), () -> false, null);
        long serialMillis = System.currentTimeMillis() - serialStart;
        int serialOk = countPresent(libsDir, sample);

        // ---------- 并行：一次传全部 ----------
        deleteAll(libsDir, sample);
        long parallelStart = System.currentTimeMillis();
        LunarLibraries.download(installation, sample, () -> false, null);
        long parallelMillis = System.currentTimeMillis() - parallelStart;
        int parallelOk = countPresent(libsDir, sample);

        System.out.println();
        System.out.printf("串行下载 %d 个库: %d ms（成功 %d）%n", sample.size(), serialMillis, serialOk);
        System.out.printf("并行下载 %d 个库: %d ms（成功 %d）%n", sample.size(), parallelMillis, parallelOk);
        if (parallelMillis > 0) {
            double speedup = (double) serialMillis / parallelMillis;
            System.out.printf("提速: %.2fx%n", speedup);
            if (speedup > 1.3)
                System.out.println("结论: 并行明显更快");
            else
                System.out.println("结论: 两者接近（本机网络可能已经是瓶颈，或样本太小）");
        }
        if (parallelOk < serialOk)
            System.out.println("注意: 并行结果少于串行，需要检查");
        else
            System.out.println("结论: 并行没有丢文件");
    }

    private static void deleteAll(Path libsDir, List<String> coordinates) throws Exception {
        for (String coordinate : coordinates)
            Files.deleteIfExists(libsDir.resolve(LunarLibraries.jarName(coordinate)));
    }

    private static int countPresent(Path libsDir, List<String> coordinates) throws Exception {
        int count = 0;
        for (String coordinate : coordinates) {
            Path jar = libsDir.resolve(LunarLibraries.jarName(coordinate));
            if (Files.isRegularFile(jar) && Files.size(jar) > 0)
                count++;
        }
        return count;
    }
}

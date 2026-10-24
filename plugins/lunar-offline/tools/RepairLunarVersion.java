import org.dsh.lunarofl.LunarVersionInstaller;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/// 修好已经装好的 Lunar 版本，让 HMCL 能真正把它启动起来。
///
/// <p>早期插件版本生成的 `Lunar-<版本>.json` 有三个坑，症状各不相同：</p>
/// <ol>
///   <li>缺 `javaVersion` → 启动器按基础版本（例如 1.8.9）推断出 Java 8，而 Lunar 的参数里有
///       `--add-modules`，Java 8 直接起不来（崩溃窗口显示“未知原因”）；</li>
///   <li>缺 `-cp ${classpath}` → 启动器认为 json 自带完整 JVM 参数、不再补默认的 `-cp`，
///       JVM 拿到空 classpath → `ClassNotFoundException: com.moonsworth.lunar.genesis.Genesis`；</li>
///   <li>`arguments.game` 里重复了启动器会补的基础版本参数（`--version` 等）→ Genesis 用 joptsimple
///       解析，`--version` 出现两次就抛 `MultipleArgumentsForOptionException`。</li>
/// </ol>
///
/// <p>前两个可以直接打补丁；第三个需要按新方案重新生成 json。工具会优先「用安装目录里已有的
/// 客户端文件重建版本 json」（**不重新下载**），失败时退回到打补丁。</p>
///
/// <p>用法：`RepairLunarVersion <版本json路径> [更多路径...]`</p>
public final class RepairLunarVersion {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: RepairLunarVersion <版本json路径> [更多路径...]");
            System.out.println("例如: RepairLunarVersion E:/game/game/minecraft/.minecraft/versions/Lunar-1.21.11/Lunar-1.21.11.json");
            System.exit(1);
        }
        int repaired = 0;
        for (String argument : args) {
            Path json = Path.of(argument.replace('/', File.separatorChar));
            if (!Files.isRegularFile(json)) {
                System.out.println("跳过（不存在）: " + json);
                continue;
            }
            try {
                LunarVersionInstaller.Result result = LunarVersionInstaller.regenerate(json);
                System.out.println("已重建版本 json: " + result.versionId() + "（库 " + result.libraries()
                        + " 个）→ " + json);
                repaired++;
            } catch (Exception regenerateFailed) {
                System.out.println("无法重建（" + regenerateFailed.getMessage() + "），改为打补丁");
                boolean changed = LunarVersionInstaller.ensureLaunchRequirements(json);
                System.out.println((changed ? "已补上 Java 要求与 classpath: " : "无需改动: ") + json);
                if (changed)
                    repaired++;
            }
        }
        System.out.println("共处理 " + args.length + " 个文件，修改 " + repaired + " 个。");
    }
}

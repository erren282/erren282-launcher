import org.jackhuang.hmcl.plugin.PluginDescription;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/// Validates the packaged plugin jar the same way the launcher's loader does:
/// reads the manifest, then loads and instantiates the entry class.
public final class PluginLoadTest {

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        System.out.println("插件包: " + jar);

        PluginDescription description = PluginDescription.readFromJar(jar);
        System.out.println("  id                  = " + description.getId());
        System.out.println("  name                = " + description.getName());
        System.out.println("  version             = " + description.getVersion());
        System.out.println("  author              = " + description.getAuthor());
        System.out.println("  entry               = " + description.getEntry());
        System.out.println("  minLauncherVersion  = " + description.getMinLauncherVersion());

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, PluginLoadTest.class.getClassLoader())) {
            Class<?> entry = Class.forName(description.getEntry(), true, loader);
            System.out.println("  entry 类已加载: " + entry.getName());
            Object instance = entry.getDeclaredConstructor().newInstance();
            System.out.println("  实例化成功: " + instance.getClass().getName());
            if (!(instance instanceof org.jackhuang.hmcl.plugin.HmclPlugin))
                throw new IllegalStateException("入口类没有继承 HmclPlugin");
            System.out.println("  继承 HmclPlugin: 是");
        }

        System.out.println();
        System.out.println("插件包校验通过。");
    }
}

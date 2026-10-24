import org.dsh.servermanager.PluginStore;
import org.dsh.servermanager.ServerPlayers;
import org.dsh.servermanager.ServerPlugins;
import org.dsh.servermanager.ServerProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Verifies player management and plugin management against real files: the JSON list formats
/// the server itself writes, real `plugin.yml` syntax, and the real enable/disable/delete
/// operations on disk.
public final class ServerContentTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("content-test");
        ServerProfile profile = new ServerProfile();
        profile.setName("test");
        profile.setDirectory(dir.toString());
        profile.setJarFile("server.jar");

        testPlayerLists(dir, profile);
        testWhitelistToggle(dir, profile);
        testBanExpiry(profile);
        testOfflineUuid();
        testPluginYaml(dir, profile);
        testPluginOperations(dir, profile);
        testCoreDetection(dir, profile);

        System.out.println();
        System.out.println("==================================================");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println("==================================================");
        if (failed > 0)
            System.exit(1);
    }

    // ------------------------------------------------------------ players ---

    private static void testPlayerLists(Path dir, ServerProfile profile) throws Exception {
        System.out.println("=========== 1. 玩家名单读写 ===========");
        // the exact shapes the server writes
        Files.writeString(dir.resolve("ops.json"), """
                [
                  {
                    "uuid": "c5a8bb44-8a7a-4def-84e5-3da5ba8cf706",
                    "name": "heisedechuanshuo",
                    "level": 4,
                    "bypassesPlayerLimit": false
                  }
                ]
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("whitelist.json"),
                "[\n  {\n    \"name\": \"Friend1\",\n    \"uuid\": \"aaaa-bbbb\"\n  }\n]\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("banned-players.json"), """
                [
                  {
                    "uuid": "1111",
                    "name": "Griefer",
                    "created": "2026-10-04 12:00:00 +0800",
                    "source": "Server",
                    "expires": "forever",
                    "reason": "破坏建筑"
                  },
                  {
                    "uuid": "2222",
                    "name": "TempGuy",
                    "created": "2026-10-04 12:00:00 +0800",
                    "source": "Server",
                    "expires": "2026-11-04 12:00:00 +0800",
                    "reason": "测试临时封禁"
                  }
                ]
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("usercache.json"),
                "[{\"name\":\"wocao_321\",\"uuid\":\"09099f5a\"},{\"name\":\"MC66\",\"uuid\":\"abc\"}]",
                StandardCharsets.UTF_8);

        ServerPlayers.Snapshot snapshot = ServerPlayers.read(profile);
        check("读到 1 个 OP（实际 " + snapshot.getOps().size() + "）", snapshot.getOps().size() == 1);
        check("OP 名字正确", snapshot.getOps().get(0).name().equals("heisedechuanshuo"));
        check("OP 等级正确（4）", snapshot.getOps().get(0).level() == 4);
        check("读到 1 个白名单（实际 " + snapshot.getWhitelist().size() + "）",
                snapshot.getWhitelist().size() == 1);
        check("读到 2 个封禁（实际 " + snapshot.getBanned().size() + "）",
                snapshot.getBanned().size() == 2);

        ServerPlayers.Ban permanent = snapshot.getBanned().stream()
                .filter(b -> b.name().equals("Griefer")).findFirst().orElseThrow();
        ServerPlayers.Ban temporary = snapshot.getBanned().stream()
                .filter(b -> b.name().equals("TempGuy")).findFirst().orElseThrow();
        check("forever 被识别为永久封禁", permanent.permanent());
        check("永久封禁显示「永久」（实际 " + permanent.expiryText() + "）",
                permanent.expiryText().equals("永久"));
        check("带时间的封禁不算永久", !temporary.permanent());
        check("临时封禁显示到期时间（实际 " + temporary.expiryText() + "）",
                temporary.expiryText().contains("2026-11-04"));

        check("isOp 大小写不敏感", ServerPlayers.isOp(snapshot, "HEISEDECHUANSHUO"));
        check("isWhitelisted 命中", ServerPlayers.isWhitelisted(snapshot, "friend1"));
        check("isBanned 命中", ServerPlayers.isBanned(snapshot, "griefer"));
        check("未知玩家不在 OP 中", !ServerPlayers.isOp(snapshot, "nobody"));

        List<String> all = ServerPlayers.allNames(snapshot);
        check("合并名单含 usercache + 各列表（实际 " + all + "）",
                all.containsAll(List.of("wocao_321", "MC66", "Friend1", "Griefer", "TempGuy")));

        System.out.println();
        System.out.println("=========== 2. 写入操作 ===========");
        ServerPlayers.writeOp(profile, "NewAdmin", 4);
        check("写入 OP 后可读回", ServerPlayers.isOp(ServerPlayers.read(profile), "NewAdmin"));
        ServerPlayers.writeOp(profile, "newadmin", 4);   // same player, different case
        long count = ServerPlayers.read(profile).getOps().stream()
                .filter(op -> op.name().equalsIgnoreCase("newadmin")).count();
        check("重复授予不会产生两条记录（实际 " + count + "）", count == 1);
        ServerPlayers.removeOp(profile, "NewAdmin");
        check("取消 OP 生效", !ServerPlayers.isOp(ServerPlayers.read(profile), "NewAdmin"));

        ServerPlayers.addToWhitelist(profile, "NewFriend");
        check("加入白名单生效", ServerPlayers.isWhitelisted(ServerPlayers.read(profile), "NewFriend"));
        ServerPlayers.removeFromWhitelist(profile, "newfriend");
        check("移出白名单生效（大小写不敏感）",
                !ServerPlayers.isWhitelisted(ServerPlayers.read(profile), "NewFriend"));

        // a temporary ban written for a stopped server
        ServerPlayers.writeBan(profile, "BadGuy", "作弊", ServerPlayers.expiresAfter(7, "day"));
        ServerPlayers.Ban ban = ServerPlayers.read(profile).getBanned().stream()
                .filter(b -> b.name().equals("BadGuy")).findFirst().orElseThrow();
        check("封禁写入成功且非永久", !ban.permanent());
        check("封禁原因正确", ban.reason().equals("作弊"));
        ServerPlayers.removeBan(profile, "badguy");
        check("解禁生效", !ServerPlayers.isBanned(ServerPlayers.read(profile), "BadGuy"));

        ServerPlayers.writeBan(profile, "PermGuy", "永久封禁", null);
        ServerPlayers.Ban perm = ServerPlayers.read(profile).getBanned().stream()
                .filter(b -> b.name().equals("PermGuy")).findFirst().orElseThrow();
        check("传 null 时长即为永久封禁", perm.permanent());
        ServerPlayers.removeBan(profile, "PermGuy");

        // the original entries must survive all of this
        check("原有 OP 未被破坏", ServerPlayers.isOp(ServerPlayers.read(profile), "heisedechuanshuo"));
        check("原有封禁未被破坏", ServerPlayers.isBanned(ServerPlayers.read(profile), "Griefer"));
    }

    private static void testWhitelistToggle(Path dir, ServerProfile profile) throws Exception {
        System.out.println();
        System.out.println("=========== 3. 白名单开关（读 server.properties） ===========");
        check("无 properties 时白名单视为关闭", !ServerPlayers.isWhitelistEnabled(dir));
        Files.writeString(dir.resolve("server.properties"),
                "server-port=25565\nwhite-list=true\nmotd=test\n", StandardCharsets.UTF_8);
        check("读到 white-list=true", ServerPlayers.isWhitelistEnabled(dir));
        Files.writeString(dir.resolve("server.properties"),
                "#white-list=true\nwhite-list=false\n", StandardCharsets.UTF_8);
        check("注释行不影响，读到 false", !ServerPlayers.isWhitelistEnabled(dir));
        Files.writeString(dir.resolve("server.properties"),
                "white-list=true\n", StandardCharsets.UTF_8);
        check("开启状态可读", ServerPlayers.isWhitelistEnabled(dir));

        // Regression: the old page only set the profile flag, so the UI (which reads
        // server.properties) sprang back. The switch must write the file itself.
        System.out.println("---- 开关立即写盘 ----");
        // motd is here to prove a rewrite does not drop the other keys
        Files.writeString(dir.resolve("server.properties"),
                "server-port=25565\nwhite-list=false\nmotd=keepme\n", StandardCharsets.UTF_8);
        org.dsh.servermanager.ServerManagerService.writeWhitelistFlag(profile, true);
        check("关闭后立即写入 server.properties",
                ServerPlayers.isWhitelistEnabled(dir));
        check("重写不破坏其它配置项（实际 "
                        + Files.readString(dir.resolve("server.properties")).replace("\n", " | ").trim() + "）",
                Files.readString(dir.resolve("server.properties")).contains("motd=keepme")
                        && Files.readString(dir.resolve("server.properties")).contains("server-port=25565"));
        org.dsh.servermanager.ServerManagerService.writeWhitelistFlag(profile, false);
        check("再关闭也立即生效", !ServerPlayers.isWhitelistEnabled(dir));
        check("再次重写仍保留其它配置项",
                Files.readString(dir.resolve("server.properties")).contains("motd=keepme"));
    }

    private static void testBanExpiry(ServerProfile profile) {
        System.out.println();
        System.out.println("=========== 4. 封禁时长换算 ===========");
        String forever = ServerPlayers.expiresAfter(0, "day");
        check("0 天 = 永久（实际 " + forever + "）", forever.equals("forever"));
        check("单位 forever = 永久", ServerPlayers.expiresAfter(5, "forever").equals("forever"));

        String in7Days = ServerPlayers.expiresAfter(7, "day");
        check("7 天是合法时间戳（实际 " + in7Days + "）", in7Days.matches("\\d{4}-\\d{2}-\\d{2} .*"));
        String in30Min = ServerPlayers.expiresAfter(30, "minute");
        check("分钟单位可用（实际 " + in30Min + "）", in30Min.matches("\\d{4}-\\d{2}-\\d{2} .*"));
        String in2Years = ServerPlayers.expiresAfter(2, "year");
        check("年单位可用（实际 " + in2Years + "）", in2Years.matches("\\d{4}-\\d{2}-\\d{2} .*"));
        check("未知单位退化为永久", ServerPlayers.expiresAfter(3, "fortnight").equals("forever"));

        // a later amount must produce a later timestamp
        check("7 天晚于 1 天",
                ServerPlayers.expiresAfter(7, "day").compareTo(ServerPlayers.expiresAfter(1, "day")) > 0);
    }

    private static void testOfflineUuid() {
        System.out.println();
        System.out.println("=========== 5. 离线模式 UUID ===========");
        // the value the server itself computes for offline mode
        String notch = ServerPlayers.offlineUuid("Notch");
        check("Notch 的离线 UUID 正确（实际 " + notch + "）",
                notch.equals("b50ad385-829d-3141-a216-7e7d7539ba7f"));
        String other = ServerPlayers.offlineUuid("wocao_321");
        check("UUID 为 36 位标准格式（实际 " + other + "）",
                other.matches("[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        check("不同名字得到不同 UUID",
                !ServerPlayers.offlineUuid("a").equals(ServerPlayers.offlineUuid("b")));
    }

    // ------------------------------------------------------------ plugins ---

    private static void testPluginYaml(Path dir, ServerProfile profile) throws Exception {
        System.out.println();
        System.out.println("=========== 6. plugin.yml 解析 ===========");
        Path plugins = dir.resolve("plugins");
        Files.createDirectories(plugins);

        // a realistic plugin.yml, including the nested `commands:` block that must not be
        // mistaken for top-level keys
        writeJar(plugins.resolve("EssentialsX.jar"), """
                name: EssentialsX
                version: 2.21.0
                main: com.earth2me.essentials.Essentials
                api-version: '1.13'
                description: The essential plugin suite
                author: mdcfe
                website: https://essentialsx.net
                depend: [Vault]
                softdepend:
                  - PlaceholderAPI
                  - ProtocolLib
                commands:
                  balance:
                    description: Show your balance
                    usage: /balance
                """);

        writeJar(plugins.resolve("Quoted.jar"), """
                name: "Quoted Plugin"
                version: "3.0"
                main: "com.x.Y"
                author: "Some One"
                website: "www.example.com"
                """);

        // a jar without plugin.yml still has to be listable so it can be removed
        Path broken = plugins.resolve("NoMeta.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(broken))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        List<ServerPlugins.Plugin> list = ServerPlugins.list(profile);
        check("列出 3 个 jar（实际 " + list.size() + "）", list.size() == 3);

        ServerPlugins.Plugin essentials = list.stream()
                .filter(p -> p.getFileName().equals("EssentialsX.jar")).findFirst().orElseThrow();
        check("name 解析正确", essentials.getName().equals("EssentialsX"));
        check("version 解析正确", essentials.getVersion().equals("2.21.0"));
        check("main 解析正确", essentials.getMainClass().equals("com.earth2me.essentials.Essentials"));
        check("api-version 解析正确", essentials.getApiVersion().equals("1.13"));
        check("author 解析正确", essentials.getAuthors().equals("mdcfe"));
        check("website 解析正确", essentials.getWebsite().equals("https://essentialsx.net"));
        check("description 未被 commands 覆盖",
                essentials.getDescription().equals("The essential plugin suite"));
        check("depend 内联列表解析正确（实际 " + essentials.getDepends() + "）",
                essentials.getDepends().equals(List.of("Vault")));
        check("softdepend 块列表解析正确（实际 " + essentials.getSoftDepends() + "）",
                essentials.getSoftDepends().equals(List.of("PlaceholderAPI", "ProtocolLib")));
        check("有官网时 hasWebsite 为真", essentials.hasWebsite());
        check("官网 URL 直接可用", essentials.getInfoUrl().equals("https://essentialsx.net"));
        check("显示名含版本（实际 " + essentials.getDisplayName() + "）",
                essentials.getDisplayName().equals("EssentialsX 2.21.0"));

        ServerPlugins.Plugin quoted = list.stream()
                .filter(p -> p.getFileName().equals("Quoted.jar")).findFirst().orElseThrow();
        check("双引号 name 去引号（实际 " + quoted.getName() + "）",
                quoted.getName().equals("Quoted Plugin"));
        check("双引号 version 去引号", quoted.getVersion().equals("3.0"));
        check("裸域名补 https（实际 " + quoted.getInfoUrl() + "）",
                quoted.getInfoUrl().equals("https://www.example.com"));

        ServerPlugins.Plugin noMeta = list.stream()
                .filter(p -> p.getFileName().equals("NoMeta.jar")).findFirst().orElseThrow();
        check("无 plugin.yml 时回退为文件名（实际 " + noMeta.getName() + "）",
                noMeta.getName().equals("NoMeta"));
        check("无官网时回退为商店搜索链接",
                noMeta.getInfoUrl().startsWith("https://modrinth.com/plugins?q="));
        check("无官网时 hasWebsite 为假", !noMeta.hasWebsite());
    }

    private static void testPluginOperations(Path dir, ServerProfile profile) throws Exception {
        System.out.println();
        System.out.println("=========== 7. 插件启停与删除 ===========");
        Path plugins = dir.resolve("plugins");
        check("开始时都是启用状态",
                ServerPlugins.list(profile).stream().allMatch(ServerPlugins.Plugin::isEnabled));

        ServerPlugins.disable(profile, "EssentialsX.jar");
        check("停用后 jar 不在 plugins 目录",
                !Files.exists(plugins.resolve("EssentialsX.jar")));
        check("停用后 jar 在 .disabled 目录",
                Files.exists(ServerPlugins.disabledDirectory(profile).resolve("EssentialsX.jar")));
        ServerPlugins.Plugin disabled = ServerPlugins.list(profile).stream()
                .filter(p -> p.getFileName().equals("EssentialsX.jar")).findFirst().orElseThrow();
        check("停用的插件仍被列出", !disabled.isEnabled());
        check("停用的插件元数据仍可读", disabled.getName().equals("EssentialsX"));

        ServerPlugins.enable(profile, "EssentialsX.jar");
        check("启用后 jar 回到 plugins 目录",
                Files.exists(plugins.resolve("EssentialsX.jar")));
        check("启用后不再在 .disabled 目录",
                !Files.exists(ServerPlugins.disabledDirectory(profile).resolve("EssentialsX.jar")));
        check("启用状态恢复",
                ServerPlugins.list(profile).stream()
                        .filter(p -> p.getFileName().equals("EssentialsX.jar"))
                        .allMatch(ServerPlugins.Plugin::isEnabled));

        ServerPlugins.delete(profile, "Quoted.jar");
        check("删除生效", !Files.exists(plugins.resolve("Quoted.jar")));
        check("删除后不再列出（实际 " + ServerPlugins.list(profile).size() + " 个）",
                ServerPlugins.list(profile).size() == 2);

        // installing a downloaded jar, including a name clash
        Path download = Files.createTempFile("dl-", ".jar");
        Files.writeString(download, "jar-bytes", StandardCharsets.UTF_8);
        String installed = ServerPlugins.install(profile, download, "ViaVersion-5.11.0.jar");
        check("安装的文件名正确（实际 " + installed + "）", installed.equals("ViaVersion-5.11.0.jar"));
        check("安装后文件存在", Files.exists(plugins.resolve(installed)));

        String again = ServerPlugins.install(profile, download, "ViaVersion-5.11.0.jar");
        check("重名不会覆盖旧文件（实际 " + again + "）", !again.equals(installed));
        check("重名时新文件确实存在", Files.exists(plugins.resolve(again)));

        String illegal = ServerPlugins.install(profile, download, "bad:name?.jar");
        check("非法字符被替换（实际 " + illegal + "）",
                !illegal.contains(":") && !illegal.contains("?") && illegal.endsWith(".jar"));
    }

    private static void testCoreDetection(Path dir, ServerProfile profile) throws Exception {
        System.out.println();
        System.out.println("=========== 8. 服务端核心识别 ===========");
        // from the file name
        profile.setJarFile("paper-1.21.4.jar");
        check("paper-*.jar -> paper（实际 " + PluginStore.detectCore(profile) + "）",
                PluginStore.detectCore(profile).equals("paper"));
        profile.setJarFile("spigot-1.8.8.jar");
        check("spigot-*.jar -> spigot", PluginStore.detectCore(profile).equals("spigot"));
        profile.setJarFile("purpur-1.20.4.jar");
        check("purpur-*.jar -> purpur", PluginStore.detectCore(profile).equals("purpur"));
        profile.setJarFile("folia-1.21.jar");
        check("folia-*.jar -> folia", PluginStore.detectCore(profile).equals("folia"));
        profile.setJarFile("velocity-3.3.0.jar");
        check("velocity-*.jar -> velocity", PluginStore.detectCore(profile).equals("velocity"));

        // from the jar contents when the name says nothing
        Path mystery = dir.resolve("mystery.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(mystery))) {
            out.putNextEntry(new ZipEntry("io/papermc/paperclip/Paperclip.class"));
            out.write(new byte[]{1});
            out.closeEntry();
        }
        profile.setJarFile("mystery.jar");
        check("含 Paperclip 类 -> paper（实际 " + PluginStore.detectCore(profile) + "）",
                PluginStore.detectCore(profile).equals("paper"));

        profile.setJarFile("server.jar");   // does not exist -> generic default
        check("无法判断时回退为 paper",
                PluginStore.detectCore(profile).equals("paper"));

        // compatible core sets
        profile.setJarFile("paper-1.21.4.jar");
        List<String> paperCores = PluginStore.coresFor(profile);
        check("Paper 兼容 spigot/bukkit 插件（实际 " + paperCores + "）",
                paperCores.containsAll(List.of("paper", "spigot", "bukkit")));
        profile.setJarFile("spigot-1.8.8.jar");
        check("Spigot 不声明 paper 兼容（实际 " + PluginStore.coresFor(profile) + "）",
                !PluginStore.coresFor(profile).contains("paper"));
        profile.setJarFile("velocity-3.3.0.jar");
        check("Velocity 不接受 bukkit 插件（实际 " + PluginStore.coresFor(profile) + "）",
                !PluginStore.coresFor(profile).contains("bukkit"));

        check("核心显示名可读（实际 " + PluginStore.coreDisplayName("paper") + "）",
                PluginStore.coreDisplayName("paper").equals("Paper"));
        check("未知核心显示原值", PluginStore.coreDisplayName("weird").equals("weird"));
    }

    // ------------------------------------------------------------ helpers ---

    /// Writes a single-entry jar containing the given plugin.yml.
    private static void writeJar(Path jar, String pluginYml) throws Exception {
        Path temp = Files.createTempFile("yml-", ".txt");
        Files.writeString(temp, pluginYml, StandardCharsets.UTF_8);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("plugin.yml"));
            out.write(Files.readAllBytes(temp));
            out.closeEntry();
        }
        Files.deleteIfExists(temp);
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }
}

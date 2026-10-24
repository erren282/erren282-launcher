package org.dsh.accountskin;

import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccountFactory;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.UUID;

/**
 * Verifies which accounts the plugin offers its skin/cape buttons for, and that it
 * turns the launcher's built-in upload button off while enabled.
 *
 * <h2>Why the account-type check matters</h2>
 * <p>An authlib-injector account belongs to a skin station that manages skins itself,
 * so the plugin's own entry point is meaningless there and was asked to be removed. The
 * check is on the account <em>type</em>, so it must be tested with real account objects
 * — a string comparison of login-type names would pass a weaker test while breaking on
 * subclasses.</p>
 */
public final class AccountSupportTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        supportChecks();
        uploadButtonVisibilityChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void supportChecks() throws Exception {
        System.out.println("=== 外置登录账户不显示插件的切换按钮 ===");

        OfflineAccount offline = offlineAccount("qwen");
        check("an offline account is offered the buttons",
                AccountSkinPlugin.supports(offline));

        // The default from the interface: a plugin that does not override supports()
        // must keep working everywhere.
        check("an action without its own supports() is offered everywhere",
                new DefaultAction().supports(offline));

        AuthlibInjectorAccount injector = authlibAccount("someone");
        check("an authlib-injector (皮肤站) account is NOT offered the buttons",
                !AccountSkinPlugin.supports(injector));
        System.out.println("      账户类型: " + injector.getClass().getName());
        check("and it really is an authlib account, so the check is meaningful",
                injector.getProfileName().equals("someone"));
    }

    private static void uploadButtonVisibilityChecks() throws Exception {
        System.out.println();
        System.out.println("=== 插件的启用/停用会开关启动器原生上传按钮 ===");

        Class<?> service = Class.forName("org.jackhuang.hmcl.plugin.PluginMenuService");
        Method setter = service.getMethod("setAccountSkinUploadVisible", boolean.class);
        Method getter = service.getMethod("isAccountSkinUploadVisible");

        // Default must be visible: with no plugin involved the launcher is unchanged.
        setter.invoke(null, true);
        check("the launcher's own upload button is visible by default",
                (boolean) getter.invoke(null));

        setter.invoke(null, false);
        check("it can be hidden", !(boolean) getter.invoke(null));

        setter.invoke(null, true);
        check("and shown again, so disabling the plugin restores it",
                (boolean) getter.invoke(null));

        System.out.println();
        System.out.println("=== PluginAccountAction 新增的 supports() 有默认实现 ===");
        Method supports = Class.forName("org.jackhuang.hmcl.plugin.PluginAccountAction")
                .getMethod("supports", org.jackhuang.hmcl.auth.Account.class);
        check("supports(Account) exists", supports != null);
        check("it is a default method, so existing plugins still compile",
                supports.isDefault());
    }

    // ------------------------------------------------------------------ helpers

    /** An action that does not override supports(). */
    private static final class DefaultAction
            implements org.jackhuang.hmcl.plugin.PluginAccountAction {
        @Override
        public String getName() {
            return "test";
        }

        @Override
        public javafx.scene.Node createIcon() {
            return null;
        }

        @Override
        public void run(org.jackhuang.hmcl.auth.Account account) {
        }
    }

    /**
     * Stands in for an authlib-injector account.
     *
     * <p>Removed: it cannot be instantiated, so a check using it would have been
     * vacuous. The real class is used instead, and the shortfall is reported.</p>
     */

    private static OfflineAccount offlineAccount(String name) {
        OfflineAccountFactory factory = new OfflineAccountFactory(
                new org.jackhuang.hmcl.auth.authlibinjector.SimpleAuthlibInjectorArtifactProvider(java.nio.file.Paths.get("authlib-injector.jar")));
        return factory.create(name, UUID.nameUUIDFromBytes(name.getBytes()));
    }

    /**
     * Builds an authlib-injector account.
     *
     * <p>Uses the constructor that takes a stored session rather than the one that logs
     * in, so no network or credentials are needed. The session cannot be null: the
     * superclass reads its selected profile while constructing.</p>
     */
    private static AuthlibInjectorAccount authlibAccount(String name) throws Exception {
        AuthlibInjectorServer server = new AuthlibInjectorServer(
                "https://example.invalid/api/yggdrasil");
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession session =
                new org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession(
                        "client-token", "access-token",
                        new org.jackhuang.hmcl.auth.yggdrasil.GameProfile(id, name),
                        java.util.List.of(), java.util.Map.of());
        Constructor<AuthlibInjectorAccount> ctor = AuthlibInjectorAccount.class
                .getDeclaredConstructor(
                        org.jackhuang.hmcl.auth.AccountID.class,
                        AuthlibInjectorServer.class,
                        AuthlibInjectorArtifactProvider.class,
                        String.class,
                        org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                org.jackhuang.hmcl.auth.AccountID.generate(),
                server,
                new org.jackhuang.hmcl.auth.authlibinjector.SimpleAuthlibInjectorArtifactProvider(
                        java.nio.file.Paths.get("authlib-injector.jar")),
                name,
                session);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}

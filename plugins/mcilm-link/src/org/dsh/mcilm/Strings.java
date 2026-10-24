/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.dsh.mcilm;

import java.util.HashMap;
import java.util.Map;

/// Locale-aware text lookup for the plugin.
///
/// <p>The launcher language bundle is consulted first so the page follows the launcher
/// language when the strings are present; otherwise the built-in Chinese text is used, so the
/// page never shows raw resource keys. The bundle is probed with `I18n.hasKey` because calling
/// `I18n.i18n` for a missing key logs a full `MissingResourceException` stack trace.</p>
final class Strings {

    /// Built-in Chinese defaults, used when the launcher bundle has no entry for the key.
    private static final Map<String, String> FALLBACK = new HashMap<>();

    static {
        FALLBACK.put("mcilm.title", "MciLm 联机");
        FALLBACK.put("mcilm.tab", "MciLm 联机");

        // Sections on the main page.
        FALLBACK.put("mcilm.section.status", "联机");

        // Status line.
        FALLBACK.put("mcilm.status.core.missing", "尚未安装联机核心");
        FALLBACK.put("mcilm.status.downloading", "正在下载联机核心…");
        FALLBACK.put("mcilm.status.logging_in", "正在登录…");
        FALLBACK.put("mcilm.status.ready", "已就绪");
        FALLBACK.put("mcilm.status.signed_out", "尚未登录");
        FALLBACK.put("mcilm.status.starting.host", "正在创建房间…");
        FALLBACK.put("mcilm.status.starting.guest", "正在加入房间…");
        FALLBACK.put("mcilm.status.stopping", "正在断开…");
        FALLBACK.put("mcilm.status.hosting", "房间已创建，把房间 ID 和密码发给朋友");
        FALLBACK.put("mcilm.status.guest", "已连接到房间，进入游戏即可");
        FALLBACK.put("mcilm.status.failed", "启动失败");
        FALLBACK.put("mcilm.status.failed.detail", "联机核心异常退出，请查看日志");
        FALLBACK.put("mcilm.status.room.closed", "房间已关闭");
        FALLBACK.put("mcilm.status.left", "已离开房间");

        // Hints.
        FALLBACK.put("mcilm.setup.hint", "首次使用需要下载 MciLm 联机核心（约 13 MB）。");
        FALLBACK.put("mcilm.ready.hint", "选择当房主或加入朋友的房间。房间默认私密，5 人以内免费。");
        FALLBACK.put("mcilm.ready.hint.signed_out", "登录 MciLm 平台账号后即可开房或加入房间。");
        FALLBACK.put("mcilm.connecting.hint", "正在建立连接，请稍候…");
        FALLBACK.put("mcilm.connecting.cancel", "取消并返回");
        FALLBACK.put("mcilm.hosting.hint", "点击房间 ID 或密码即可复制，发送给朋友后让他们填进「加入房间」。");
        FALLBACK.put("mcilm.guest.hint", "在游戏里连接 <b>localhost:%s</b>，或点击下方按钮直接添加服务器。");

        // Rows.
        FALLBACK.put("mcilm.host", "创建房间");
        FALLBACK.put("mcilm.host.desc", "启动联机核心并生成房间 ID");
        FALLBACK.put("mcilm.join", "加入房间");
        FALLBACK.put("mcilm.join.desc", "连接到朋友的房间并打开本地端口");
        FALLBACK.put("mcilm.stop", "断开联机");
        FALLBACK.put("mcilm.stop.desc", "关闭房间或离开当前房间");
        FALLBACK.put("mcilm.settings", "设置");
        FALLBACK.put("mcilm.settings.desc", "账号、端口、联机核心");
        FALLBACK.put("mcilm.settings.desc.signed_out", "登录账号，或改用直连节点");
        FALLBACK.put("mcilm.guest.enter", "进入游戏");
        FALLBACK.put("mcilm.guest.enter.desc", "添加 localhost:%s 并启动游戏");
        FALLBACK.put("mcilm.room.id", "房间 ID");
        FALLBACK.put("mcilm.room.password", "房间密码");
        FALLBACK.put("mcilm.copied", "已复制到剪贴板");

        // Dialogs.
        FALLBACK.put("mcilm.room.name", "房间名称");
        FALLBACK.put("mcilm.room.name.default", "生存服:创造");
        FALLBACK.put("mcilm.room.name.prompt", "名称:类型，如 生存服:创造");
        FALLBACK.put("mcilm.room.name.required", "请先填写房间名称");
        FALLBACK.put("mcilm.room.name.invalid", "房间名必须包含冒号，例如 生存服:创造");
        FALLBACK.put("mcilm.room.password.prompt", "留空会自动生成；不设密码等于公开房");
        FALLBACK.put("mcilm.port", "本地端口");
        FALLBACK.put("mcilm.port.invalid", "端口必须是 1-65535 之间的整数");
        FALLBACK.put("mcilm.join.code", "房间 ID");
        FALLBACK.put("mcilm.join.code.required", "请先填写房主给你的房间 ID");
        FALLBACK.put("mcilm.join.password.prompt", "房主设置的密码，公开房可留空");
        FALLBACK.put("mcilm.host.prompt", "创建房间");
        FALLBACK.put("mcilm.join.prompt", "加入房间");
        FALLBACK.put("mcilm.host.hint", "房间名必须写成「名称:类型」，例如 生存服:创造。密码留空会自动生成，设了密码就是私密房（不需要 VIP）。");

        // Login.
        FALLBACK.put("mcilm.account", "账号");
        FALLBACK.put("mcilm.account.prompt", "MciLm 平台账号");
        FALLBACK.put("mcilm.password", "密码");
        FALLBACK.put("mcilm.password.prompt", "平台密码，只用于换取 Token");
        FALLBACK.put("mcilm.token", "Token");
        FALLBACK.put("mcilm.token.prompt", "已登录官网可直接粘贴 mci_ 开头的 Token");
        FALLBACK.put("mcilm.token.saved", "Token 已保存");
        FALLBACK.put("mcilm.login", "登录");
        FALLBACK.put("mcilm.login.desc", "用平台账号登录，或直接粘贴 Token");
        FALLBACK.put("mcilm.login.prompt", "登录 MciLm 平台");
        FALLBACK.put("mcilm.login.required", "请填写账号密码，或直接粘贴 Token");
        FALLBACK.put("mcilm.login.success", "登录成功，Token 已保存");
        FALLBACK.put("mcilm.login.failed", "登录失败：%s");
        FALLBACK.put("mcilm.login.hint", "两种方式任选其一：填账号密码自动换取 Token，或直接粘贴官网的 Token。还没有账号？点下面的「注册账号」到官网注册。");
        FALLBACK.put("mcilm.register", "注册账号 / 获取 Token");
        FALLBACK.put("mcilm.register.desc", "在浏览器打开 MciLm 官网注册并复制 Token");
        FALLBACK.put("mcilm.logout", "退出登录");
        FALLBACK.put("mcilm.logout.desc", "清除本机保存的 Token");
        FALLBACK.put("mcilm.logout.done", "已退出登录");

        // Settings page.
        FALLBACK.put("mcilm.settings.prompt", "MciLm 联机设置");
        FALLBACK.put("mcilm.settings.section.account", "账号");
        FALLBACK.put("mcilm.settings.section.connection", "连接");
        FALLBACK.put("mcilm.settings.section.about", "关于");
        FALLBACK.put("mcilm.settings.account.signed_in", "切换账号");
        FALLBACK.put("mcilm.settings.account.signed_in.desc", "已登录，点击可切换账号或更换 Token");
        FALLBACK.put("mcilm.settings.get_token", "打开官网获取 Token");
        FALLBACK.put("mcilm.settings.get_token.desc", "在浏览器打开发放 Token 的页面");
        FALLBACK.put("mcilm.settings.node", "节点地址");
        FALLBACK.put("mcilm.settings.port", "本地端口");
        FALLBACK.put("mcilm.settings.port.desc", "游戏里连接这个端口；访客会自动同步房主的端口");
        FALLBACK.put("mcilm.settings.roommax", "房间人数上限");
        FALLBACK.put("mcilm.settings.roommax.desc", "普通账号上限 5 人，更少人数可降低被扫描概率");
        FALLBACK.put("mcilm.settings.roommax.value", "%s 人");

        // Core maintenance.
        FALLBACK.put("mcilm.download.core", "下载 / 更新联机核心");
        FALLBACK.put("mcilm.download.core.desc", "从官方接口获取最新版 MciLm-linkc");
        FALLBACK.put("mcilm.open.folder", "打开插件数据目录");
        FALLBACK.put("mcilm.docs", "MciLm-link 文档");
        FALLBACK.put("mcilm.docs.desc", "使用说明与常见问题");

        // Node settings.
        FALLBACK.put("mcilm.node.prompt", "节点地址（IP:PORT[:KEY]，留空走官方自动选优）");
        FALLBACK.put("mcilm.node.auto", "官方自动");

        // Download feedback.
        FALLBACK.put("mcilm.download.success", "联机核心已更新到 %s");
        FALLBACK.put("mcilm.download.failed", "下载联机核心失败：%s");
        FALLBACK.put("mcilm.start.failed", "启动联机核心失败：%s");
    }

    private Strings() {
    }

    /// Looks a key up in the launcher bundle, falling back to the built-in text.
    ///
    /// @param key the resource key
    /// @return the localized text
    static String get(String key) {
        try {
            if (org.jackhuang.hmcl.util.i18n.I18n.hasKey(key)) {
                String value = org.jackhuang.hmcl.util.i18n.I18n.i18n(key);
                if (value != null && !value.isEmpty() && !value.equals(key))
                    return value;
            }
        } catch (Throwable ignored) {
            // fall through to the built-in text
        }
        return FALLBACK.getOrDefault(key, key);
    }

    /// Looks a key up and formats it with [String#format].
    ///
    /// @param key  the resource key
    /// @param args the format arguments
    /// @return the formatted text
    static String format(String key, Object... args) {
        try {
            return String.format(get(key), args);
        } catch (Throwable e) {
            return get(key) + " " + java.util.Arrays.toString(args);
        }
    }
}

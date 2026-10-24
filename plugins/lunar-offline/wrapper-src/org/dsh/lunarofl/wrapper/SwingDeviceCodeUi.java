package org.dsh.lunarofl.wrapper;

import org.dsh.lunarofl.auth.LunarSessionRefresher;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/// 设备码登录界面（游戏进程内的 Swing 窗口）。
///
/// <p>Lunar 请求"添加账户"时，我们走微软的设备码流程；微软的旧版接口只给
/// `https://www.microsoft.com/link` 和一个单独的验证码，浏览器页面会要求输入它。
/// 官方启动器有自己的窗口来展示验证码，而包装类跑在游戏进程里，所以这里：</p>
/// <ul>
///   <li>把验证码**自动复制到剪贴板**（打开页面后直接 Ctrl+V 即可）；</li>
///   <li>弹一个置顶小窗口，大字号显示验证码，并提供「复制验证码」「重新打开浏览器」按钮；</li>
///   <li>登录完成或失败时自动关闭。</li>
/// </ul>
final class SwingDeviceCodeUi implements LunarSessionRefresher.DeviceCodeUi {

    private volatile JFrame frame;

    @Override
    public void showCode(String userCode, String verificationUrl) {
        boolean copied = copyToClipboard(userCode);
        System.err.println("[dsh-launcher] 设备码登录：验证码 " + userCode
                + (copied ? "（已复制到剪贴板）" : "") + "，验证页 " + verificationUrl);
        if (GraphicsEnvironment.isHeadless())
            return;
        SwingUtilities.invokeLater(() -> {
            try {
                frame = buildFrame(userCode, verificationUrl, copied);
                frame.setVisible(true);
            } catch (Throwable t) {
                System.err.println("[dsh-launcher] 验证码窗口创建失败：" + t);
            }
        });
    }

    @Override
    public void close() {
        JFrame current = frame;
        if (current == null)
            return;
        SwingUtilities.invokeLater(() -> {
            current.setVisible(false);
            current.dispose();
        });
    }

    private static JFrame buildFrame(String userCode, String verificationUrl, boolean copied) {
        JFrame window = new JFrame("Lunar 微软账户登录");
        window.setAlwaysOnTop(true);
        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JLabel title = new JLabel("在浏览器中完成微软登录");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(0.5f);
        content.add(title);
        content.add(Box.createVerticalStrut(10));

        JLabel code = new JLabel(userCode);
        code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 30));
        code.setForeground(new Color(0x1B5E20));
        code.setAlignmentX(0.5f);
        code.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xBDBDBD), 1),
                BorderFactory.createEmptyBorder(8, 24, 8, 24)));
        content.add(code);
        content.add(Box.createVerticalStrut(8));

        JLabel hint = new JLabel(copied
                ? "<html><div style='width:360px'>验证码已复制到剪贴板。浏览器已经打开，"
                        + "页面若没有自动填入，直接 <b>Ctrl+V</b> 粘贴即可。<br>"
                        + "登录成功后本窗口会自动关闭。</div></html>"
                : "<html><div style='width:360px'>请把上面的验证码填入浏览器页面"
                        + "（点下面的按钮可复制）。登录成功后本窗口会自动关闭。</div></html>");
        hint.setAlignmentX(0.5f);
        content.add(hint);
        content.add(Box.createVerticalStrut(12));

        JButton copyButton = new JButton("复制验证码");
        copyButton.addActionListener(e -> {
            copyToClipboard(userCode);
            copyButton.setText("已复制");
        });
        JButton openButton = new JButton("重新打开浏览器");
        openButton.addActionListener(e -> openBrowser(verificationUrl));
        JPanel buttons = new JPanel();
        buttons.add(copyButton);
        buttons.add(openButton);
        buttons.setAlignmentX(0.5f);
        content.add(buttons);

        window.setLayout(new BorderLayout());
        window.add(content, BorderLayout.CENTER);
        window.pack();
        window.setMinimumSize(new Dimension(420, window.getHeight()));
        window.setLocationRelativeTo(null);
        return window;
    }

    /// 复制到剪贴板：AWT 优先，失败时退回 Windows 的 clip.exe。
    static boolean copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Process process = new ProcessBuilder("cmd", "/c", "clip")
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().close();
            return process.waitFor() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void openBrowser(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
            return;
        } catch (Throwable ignored) {
        }
        try {
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
        } catch (Throwable ignored) {
        }
    }
}

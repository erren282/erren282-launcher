package org.dsh.modtoversion;

import org.jackhuang.hmcl.util.i18n.I18n;

import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Recognises one of the launcher's content download dialogs and reads the release it
 * shows.
 *
 * <h2>Why the heading is the only handle</h2>
 * <p>The dialog is a private class ({@code DownloadPage$AddonVersion}) with no id, no
 * style class and no field holding the release, so it cannot be identified by type.
 * What it <em>does</em> expose is its heading text, which the launcher builds as
 * {@code i18n(<section>.download.title, version.name())} — in Chinese, for mods,
 * {@code 模组下载 - <版本名>}. That prefix identifies the section, and the rest is
 * exactly the release name, which is all that is needed to find the release again
 * among the addon's versions.</p>
 *
 * <h2>Why the prefix is read from the launcher's own bundle</h2>
 * <p>Hard-coding {@code 模组下载 - } would make the button disappear for every user not
 * running a Chinese or English launcher — the launcher also ships Traditional Chinese,
 * Japanese, Russian, Spanish, Ukrainian, Arabic and Literary Chinese. Instead each
 * section's template is fetched with {@code I18n.i18n(key)} and cut at its
 * {@code %1s} placeholder, which yields the right prefix and suffix for whatever
 * locale is active. The Simplified Chinese templates are kept only as a last resort,
 * for the case where the bundle cannot be read at all.</p>
 *
 * <p>Kept free of JavaFX so the parsing can be tested directly.</p>
 */
final class DialogHeader {

    /// The placeholder the launcher's headings use for the release name.
    private static final String PLACEHOLDER = "%1s";

    /// The same placeholder written without an argument index, tolerated so a
    /// translation that uses {@code %s} still works.
    private static final String PLAIN_PLACEHOLDER = "%s";

    private DialogHeader() {
    }

    /// A dialog heading split around the release name it carries.
    ///
    /// @param kind   the download section the heading belongs to
    /// @param prefix the fixed text before the release name
    /// @param suffix the fixed text after it, usually empty
    record Heading(AddonKind kind, String prefix, String suffix) {

        /// True when {@code text} is this heading with a non-empty name in the middle.
        boolean matches(String text) {
            return text.length() > prefix.length() + suffix.length()
                    && text.startsWith(prefix)
                    && text.endsWith(suffix);
        }

        /// The release name {@code text} carries; only meaningful when it matches.
        String releaseNameOf(String text) {
            return text.substring(prefix.length(), text.length() - suffix.length()).trim();
        }
    }

    /// Recognises a download dialog heading.
    ///
    /// @param heading the dialog's heading text, or null
    /// @return the parsed heading, or empty when the text is not a supported download
    ///         dialog heading or carries no release name
    static Optional<Heading> parse(String heading) {
        if (heading == null) {
            return Optional.empty();
        }
        String text = heading.trim();
        for (AddonKind kind : AddonKind.values()) {
            Heading candidate = headingOf(kind);
            if (candidate != null && candidate.matches(text)
                    && !candidate.releaseNameOf(text).isEmpty()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /// The download section a heading belongs to.
    ///
    /// @param heading the dialog's heading text, or null
    static Optional<AddonKind> kindOf(String heading) {
        return parse(heading).map(Heading::kind);
    }

    /// The release name a heading carries.
    ///
    /// @param heading the dialog's heading text, or null
    static Optional<String> releaseNameOf(String heading) {
        return parse(heading).map(parsed -> parsed.releaseNameOf(heading.trim()));
    }

    /// True when the heading belongs to one of the sections this plugin handles.
    static boolean isSupported(String heading) {
        return parse(heading).isPresent();
    }

    /// The heading shape of one section in the active locale.
    ///
    /// @return the split template, or null when this section has no usable template
    static Heading headingOf(AddonKind kind) {
        String template = templateOf(kind);
        if (template == null) {
            return null;
        }
        int at = template.indexOf(PLACEHOLDER);
        int length = PLACEHOLDER.length();
        if (at < 0) {
            at = template.indexOf(PLAIN_PLACEHOLDER);
            length = PLAIN_PLACEHOLDER.length();
        }
        if (at < 0) {
            return null;
        }
        return new Heading(kind,
                template.substring(0, at),
                template.substring(at + length));
    }

    /// The section's heading template in the active locale.
    ///
    /// <p>{@code I18n.i18n(key)} with no arguments returns the raw value including its
    /// placeholder; when the key is missing, {@code I18n} returns the key itself, which
    /// is detected here so the built-in template takes over.</p>
    private static String templateOf(AddonKind kind) {
        try {
            String template = I18n.i18n(kind.titleKey());
            if (template != null && !template.isBlank() && !template.equals(kind.titleKey())) {
                return template;
            }
        } catch (Throwable ignored) {
            // Fall through to the built-in Chinese template.
        }
        return kind.fallbackTemplate();
    }

    /// The resource bundle the launcher is currently using, or null when it cannot be
    /// read. Used by the tests to show which locale the templates came from.
    static ResourceBundle bundle() {
        try {
            return I18n.getResourceBundle();
        } catch (Throwable t) {
            return null;
        }
    }
}

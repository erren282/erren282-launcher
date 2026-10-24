package org.jackhuang.hmcl.plugin;

import javafx.scene.Node;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Function;

/// A named launcher sub-page published by a plugin.
///
/// <p>[pageFactory] receives the action that returns to the host page (a hub that presents
/// this sub-page), or {@code null} when the sub-page is shown as a standalone sidebar entry
/// — so a page can draw exactly one back button in every hosting situation.</p>
///
/// <p>Sub-pages are the building blocks for grouping: a plugin that only offers one feature
/// publishes a sub-page instead of a sidebar entry. When no [PluginLauncherPage#isPageGroup]
/// page is installed, every sub-page is shown as its own sidebar entry; as soon as a group
/// page (for example a "服务器" hub) is installed, the sub-pages are no longer listed
/// separately and the hub presents them instead. The launcher decides from what is actually
/// installed, so no plugin has to know about the others and the order they load in.</p>
@NotNullByDefault
public record PluginSubPage(String key, String title, String description, SVG icon,
                            Function<Runnable, Node> pageFactory) {
}

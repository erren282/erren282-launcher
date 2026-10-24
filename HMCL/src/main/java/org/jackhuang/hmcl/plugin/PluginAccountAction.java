/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.plugin;

import javafx.scene.Node;
import org.jackhuang.hmcl.auth.Account;
import org.jetbrains.annotations.NotNullByDefault;

/// An action contributed by a plugin for the account entries of the account list.
///
/// <p>Implementations are invoked on the JavaFX application thread.</p>
///
/// @see PluginMenuService#registerAccountAction(PluginAccountAction)
@NotNullByDefault
public interface PluginAccountAction {

    /// Returns the display name of the action, shown as the button tooltip.
    String getName();

    /// Returns the icon of the action button; a new instance is created per account entry.
    Node createIcon();

    /// Runs the action for the given account.
    ///
    /// @param account the account entry the user clicked
    void run(Account account);

    /// Whether this action's button should appear for the given account.
    ///
    /// <p>Lets an action that only makes sense for some kinds of account stay off the
    /// others, instead of being shown and then refusing to work. Defaults to being
    /// shown everywhere, so existing plugins are unaffected.</p>
    ///
    /// @param account the account entry to be shown
    /// @return true to add the button to that account's row
    default boolean supports(Account account) {
        return true;
    }
}

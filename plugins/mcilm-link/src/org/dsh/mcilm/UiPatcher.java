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

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;

import java.lang.reflect.Field;

/// Relabels two built-in strings on the launcher's multiplayer page at runtime.
///
/// <p>The launcher ships the page title as <em>Terracotta | 陶瓦联机</em> and calls its first
/// sidebar entry <em>联机大厅</em>. This class swaps them so the page title reads
/// <em>联机大厅</em> and the built-in entry reads <em>陶瓦联机</em>, leaving the
/// <em>MciLm 联机</em> entry as the clearly named second option.</p>
///
/// <p>The launcher jar is not touched: the labels are replaced on the live JavaFX nodes. The
/// page title is a [DecoratorPage.State] record, so it is replaced with an equal record that
/// only differs in its title; the sidebar entry is an [AdvancedListItem] whose title is a
/// property, so it is set directly and re-applied whenever the sidebar is rebuilt.</p>
final class UiPatcher {

    /// The caption the page title becomes.
    private static final String PAGE_TITLE = "联机大厅";

    /// The caption the built-in first sidebar entry becomes.
    private static final String SIDEBAR_TITLE = "陶瓦联机";

    /// How often the patcher looks for the lazily created page.
    private static final Duration POLL_INTERVAL = Duration.millis(500);

    /// Give up polling after this many attempts so a session that never opens the multiplayer
    /// page does not keep a timeline alive forever.
    private static final int MAX_POLL_ATTEMPTS = 2400;

    private final PluginContext context;

    private Timeline pollTimeline;
    private int pollAttempts;

    /// Whether the page title was already replaced; avoids replaying a title transition.
    private boolean pageTitlePatched;

    /// The sidebar container that was patched, so the listener is attached only once.
    private VBox patchedContainer;

    UiPatcher(PluginContext context) {
        this.context = context;
    }

    /// Starts looking for the multiplayer page and patches it as soon as it exists.
    void start() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::start);
            return;
        }

        Object page = peekPage();
        if (page != null) {
            patch(page);
            return;
        }

        Timeline timeline = new Timeline(new KeyFrame(POLL_INTERVAL, event -> {
            Object found = peekPage();
            if (found != null) {
                patch(found);
            } else if (++pollAttempts > MAX_POLL_ATTEMPTS) {
                stop();
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        pollTimeline = timeline;
        timeline.play();
    }

    /// Stops polling and forgets the patched container; called when the plugin is disabled.
    void stop() {
        if (pollTimeline != null) {
            pollTimeline.stop();
            pollTimeline = null;
        }
        patchedContainer = null;
        pageTitlePatched = false;
    }

    /// Reads the launcher's lazily created multiplayer page without forcing its creation.
    ///
    /// @return the page, or `null` when it has not been created yet
    private Object peekPage() {
        try {
            Field lazyField = Controllers.class.getDeclaredField("terracottaPage");
            lazyField.setAccessible(true);
            Object lazy = lazyField.get(null);
            if (lazy == null)
                return null;

            Field valueField = lazy.getClass().getDeclaredField("value");
            valueField.setAccessible(true);
            return valueField.get(lazy);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /// Applies both relabellings to the page.
    ///
    /// @param page the multiplayer page
    private void patch(Object page) {
        stopPolling();
        patchPageTitle(page);
        patchSideBar(page);
    }

    private void stopPolling() {
        if (pollTimeline != null) {
            pollTimeline.stop();
            pollTimeline = null;
        }
    }

    /// Replaces the page title shown in the title bar.
    ///
    /// @param page the multiplayer page
    private void patchPageTitle(Object page) {
        if (pageTitlePatched)
            return;

        try {
            Field stateField = page.getClass().getDeclaredField("state");
            stateField.setAccessible(true);
            Object wrapper = stateField.get(page);
            if (!(wrapper instanceof ObjectProperty<?> property))
                return;

            Object current = ((ObservableValue<?>) wrapper).getValue();
            if (!(current instanceof DecoratorPage.State state))
                return;

            if (PAGE_TITLE.equals(state.title())) {
                pageTitlePatched = true;
                return;
            }

            DecoratorPage.State replacement = new DecoratorPage.State(
                    PAGE_TITLE,
                    state.titleNode(),
                    state.backable(),
                    state.refreshable(),
                    false,
                    state.leftPaneWidth());

            @SuppressWarnings("unchecked")
            ObjectProperty<DecoratorPage.State> typed = (ObjectProperty<DecoratorPage.State>) property;
            typed.set(replacement);
            pageTitlePatched = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            context.logWarning("Failed to relabel the multiplayer page title: " + e);
        }
    }

    /// Renames the built-in first sidebar entry and keeps it renamed across rebuilds.
    ///
    /// <p>The sidebar is rebuilt whenever a plugin tab is registered or removed, which would
    /// otherwise bring the original caption back, so the container is watched.</p>
    ///
    /// @param page the multiplayer page
    private void patchSideBar(Object page) {
        try {
            Field sideBarField = page.getClass().getDeclaredField("sideBar");
            sideBarField.setAccessible(true);
            Object sideBar = sideBarField.get(page);
            if (sideBar == null)
                return;

            Field containerField = sideBar.getClass().getDeclaredField("container");
            containerField.setAccessible(true);
            Object container = containerField.get(sideBar);
            if (!(container instanceof VBox box))
                return;

            applySideBarTitles(box);

            if (patchedContainer != box) {
                patchedContainer = box;
                box.getChildren().addListener((ListChangeListener<Node>) change -> applySideBarTitles(box));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            context.logWarning("Failed to relabel the multiplayer sidebar: " + e);
        }
    }

    /// Renames the first navigation entry, leaving every other entry alone.
    ///
    /// @param container the sidebar item container
    private void applySideBarTitles(VBox container) {
        for (Node child : container.getChildren()) {
            if (!(child instanceof AdvancedListItem item))
                continue;

            // Only the first entry is the built-in lobby; plugin entries follow it and must keep
            // their own captions.
            String title = item.getTitle();
            if (title == null || title.equals(SIDEBAR_TITLE))
                return;
            item.setTitle(SIDEBAR_TITLE);
            return;
        }
    }
}

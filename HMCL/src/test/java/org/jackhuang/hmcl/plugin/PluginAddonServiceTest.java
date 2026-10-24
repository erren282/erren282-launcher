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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies the addon download listener registry of [PluginAddonService].
public class PluginAddonServiceTest {

    private static PluginAddonEvent event(String instanceId) {
        return new PluginAddonEvent(null, null, instanceId, null, Path.of("mods"));
    }

    @Test
    public void testDispatchUnregisterAndDuplicateRegistration() {
        List<String> received = new ArrayList<>();
        PluginAddonListener first = it -> received.add("first:" + it.directory());
        PluginAddonListener second = it -> received.add("second:" + it.instanceId());

        try {
            PluginAddonService.registerListener(first);
            PluginAddonService.registerListener(second);
            PluginAddonService.registerListener(first);
            PluginAddonService.fireAddonDownloaded(event("1.20.1"));
            assertEquals(List.of("first:mods", "second:1.20.1"), received);

            received.clear();
            PluginAddonService.unregisterListener(first);
            PluginAddonService.fireAddonDownloaded(event("1.21.1"));
            assertEquals(List.of("second:1.21.1"), received);
        } finally {
            PluginAddonService.unregisterListener(first);
            PluginAddonService.unregisterListener(second);
        }
    }

    @Test
    public void testFailingListenerDoesNotBlockOthers() {
        List<PluginAddonEvent> received = new ArrayList<>();
        PluginAddonListener failing = it -> {
            throw new IllegalStateException("boom");
        };
        PluginAddonListener working = received::add;

        try {
            PluginAddonService.registerListener(failing);
            PluginAddonService.registerListener(working);
            PluginAddonEvent event = event(null);
            PluginAddonService.fireAddonDownloaded(event);
            assertEquals(1, received.size());
            assertSame(event, received.get(0));
        } finally {
            PluginAddonService.unregisterListener(failing);
            PluginAddonService.unregisterListener(working);
        }
    }
}

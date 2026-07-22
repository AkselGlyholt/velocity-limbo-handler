package com.akselglyholt.velocitylimbohandler.api;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class VelocityLimboApiTest {
    @Test
    void getReturnsApiExposedByLoadedPluginInstance() {
        ProxyServer proxy = mock(ProxyServer.class);
        PluginManager plugins = mock(PluginManager.class);
        PluginContainer container = mock(PluginContainer.class);
        VelocityLimboApi expected = mock(VelocityLimboApi.class);
        VelocityLimboApi.Provider provider = () -> expected;

        when(proxy.getPluginManager()).thenReturn(plugins);
        when(plugins.getPlugin(VelocityLimboApi.PLUGIN_ID)).thenReturn(Optional.of(container));
        doReturn(Optional.of(provider)).when(container).getInstance();

        assertSame(expected, VelocityLimboApi.get(proxy));
    }

    @Test
    void getRejectsPluginWithoutV1Provider() {
        ProxyServer proxy = mock(ProxyServer.class);
        PluginManager plugins = mock(PluginManager.class);
        PluginContainer container = mock(PluginContainer.class);
        when(proxy.getPluginManager()).thenReturn(plugins);
        when(plugins.getPlugin(VelocityLimboApi.PLUGIN_ID)).thenReturn(Optional.of(container));
        doReturn(Optional.of(new Object())).when(container).getInstance();

        assertThrows(IllegalStateException.class, () -> VelocityLimboApi.get(proxy));
    }

    @Test
    void queueSnapshotsDefensivelyCopyTheirEntries() {
        var mutable = new ArrayList<QueuedPlayerSnapshot>();
        QueueSnapshot snapshot = new QueueSnapshot("survival", mutable, List.of(), 1);
        mutable.add(null);

        assertTrue(snapshot.players().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.players().add(null));
    }
}

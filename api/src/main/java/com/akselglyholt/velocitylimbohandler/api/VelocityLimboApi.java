package com.akselglyholt.velocitylimbohandler.api;

import com.akselglyholt.velocitylimbohandler.api.lifecycle.Availability;
import com.akselglyholt.velocitylimbohandler.api.player.ManagedPlayerSnapshot;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueSnapshot;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueSummary;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stable v1 API exposed by the Velocity Limbo Handler plugin instance. */
public interface VelocityLimboApi {
    String PLUGIN_ID = "velocity-limbo-handler";

    /**
     * Locates VLH through Velocity's plugin manager. Consumers must declare a required
     * {@code @Dependency(id = "velocity-limbo-handler")} before calling this method.
     */
    static VelocityLimboApi get(ProxyServer proxyServer) {
        Objects.requireNonNull(proxyServer, "proxyServer");
        PluginContainer container = proxyServer.getPluginManager().getPlugin(PLUGIN_ID)
                .orElseThrow(() -> new IllegalStateException("Velocity Limbo Handler is not loaded"));
        Object instance = container.getInstance()
                .orElseThrow(() -> new IllegalStateException("Velocity Limbo Handler instance is unavailable"));
        if (!(instance instanceof Provider provider)) {
            throw new IllegalStateException("Loaded Velocity Limbo Handler does not expose API v1");
        }
        return provider.velocityLimboApi();
    }

    Availability availability();

    LimboController controllerFor(Object pluginInstance);

    Optional<ManagedPlayerSnapshot> player(UUID playerId);

    QueueSnapshot queue(String serverName);

    List<QueueSummary> queues();

    /** Implemented by the VLH plugin entry point; not intended for consumers. */
    interface Provider {
        VelocityLimboApi velocityLimboApi();
    }
}

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

/**
 * Stable v1 API exposed by the Velocity Limbo Handler plugin instance.
 *
 * <p>Read methods return immutable point-in-time snapshots. API events are immutable observations and,
 * except for the documented limbo-entry admission barrier, are dispatched without waiting for
 * consumers and without a delivery order.</p>
 *
 * <p>Snapshots and events carry a {@code revision} from one proxy-wide counter that only increases.
 * Use it to discard stale data: ignore a {@code PlayerLimboStateChangedEvent} whose
 * {@code current().revision()} is not greater than the last revision you processed for that player,
 * and likewise per server for {@code ServerHoldChangedEvent}.</p>
 */
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

    /** Returns whether VLH can currently accept mutations. Snapshot reads remain safe while stopping. */
    Availability availability();

    /**
     * Creates an owner-scoped controller for a registered plugin instance.
     * Call this during or after {@code ProxyInitializeEvent}, not from the plugin constructor.
     *
     * @param pluginInstance the same object registered by Velocity as the consuming plugin
     * @return a controller whose leases are owned by that plugin's ID
     * @throws NullPointerException when {@code pluginInstance} is {@code null}
     * @throws IllegalArgumentException when Velocity does not recognize the instance
     */
    LimboController controllerFor(Object pluginInstance);

    /**
     * Returns the current state of a managed player.
     *
     * @param playerId player to query
     * @return an empty optional when the player is not currently managed by VLH
     * @throws NullPointerException when {@code playerId} is {@code null}
     */
    Optional<ManagedPlayerSnapshot> player(UUID playerId);

    /**
     * Returns an ordered snapshot for a destination name. Unknown or temporarily unregistered names
     * are valid and return an empty queue unless VLH retains queue or hold state for that name.
     *
     * @param serverName destination name, compared case-insensitively
     * @return immutable queue snapshot
     * @throws NullPointerException when {@code serverName} is {@code null}
     * @throws IllegalArgumentException when {@code serverName} is blank
     */
    QueueSnapshot queue(String serverName);

    /** Returns summaries for destinations that currently have queued players or server holds. */
    List<QueueSummary> queues();

    /** Implemented by the VLH plugin entry point; not intended for consumers. */
    interface Provider {
        VelocityLimboApi velocityLimboApi();
    }
}

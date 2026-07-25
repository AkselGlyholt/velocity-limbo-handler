package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.api.VelocityLimboApiImpl;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.List;

public class ConnectionListener {
    private final VelocityLimboApiImpl api;
    private final PlayerManager playerManager;
    private final ReconnectBlocker reconnectBlocker;

    public ConnectionListener(VelocityLimboApiImpl api, PlayerManager playerManager,
                              ReconnectBlocker reconnectBlocker) {
        this.api = api;
        this.playerManager = playerManager;
        this.reconnectBlocker = reconnectBlocker;
    }

    @Subscribe
    public void onPlayerPreConnect(@NotNull ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer intendedServer = event.getOriginalServer();
        RegisteredServer limbo = VelocityLimboHandler.getLimboServer();
        if (limbo == null) return;

        // Don't reroute if they are already going to Limbo
        if (Utility.doServerNamesMatch(intendedServer, limbo)) {
            return;
        }

        // If the plugin is the one moving the player, let them pass!
        if (api.isConnectionClaimed(player) || playerManager.isPlayerConnecting(player)) {
            return;
        }

        // Holds block the affected player or destination, including queue-bypass players.
        if (api.isPlayerHeld(player.getUniqueId())
                || api.isServerHeld(intendedServer.getServerInfo().getName())
                || playerManager.hasQueuedPlayers(intendedServer)) {
            api.recordRerouteIntent(player, intendedServer);
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo));

            Utility.logDebug(() -> String.format("Rerouting %s to Limbo (Server %s is queued)",
                    player.getUsername(), intendedServer.getServerInfo().getName()));
        }
    }

    @Subscribe
    public void onPlayerPostConnect(@NotNull ServerPostConnectEvent event) {
        Player player = event.getPlayer();

        RegisteredServer currentServer = player.getCurrentServer().map(ServerConnection::getServer).orElse(null);
        RegisteredServer previousServer = event.getPreviousServer();

        if (currentServer == null) {
            VelocityLimboHandler.getLogger().severe(String.format("Current server was null for %s.", player.getUsername()));
            return;
        }

        // Remove player from queue if they left Limbo and joined another server
        if (previousServer != null && isLimbo(previousServer)) {
            api.onPlayerLeft(player, currentServer);
            reconnectBlocker.unblock(player.getUniqueId());
            return;
        }

        // Handle players who just joined Limbo
        if (isLimbo(currentServer)) {
            // Determine intended server from forced host if available
            String virtualHost = player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null);

            Utility.logDebug(() -> String.format("%s landed on limbo (previousServer: %s, virtualHost: %s)",
                    player.getUsername(),
                    previousServer != null ? previousServer.getServerInfo().getName() : "none",
                    virtualHost != null ? virtualHost : "none"));

            String intendedTarget = null;

            if (virtualHost != null) {
                List<String> forcedServers = VelocityLimboHandler.getProxyServer()
                        .getConfiguration()
                        .getForcedHosts()
                        .get(virtualHost);

                Utility.logDebug(() -> String.format("%s forced-host lookup for '%s': %s",
                        player.getUsername(), virtualHost,
                        forcedServers != null ? forcedServers.toString() : "no match"));

                if (forcedServers != null && !forcedServers.isEmpty()) {
                    intendedTarget = forcedServers.get(0);
                    if (VelocityLimboHandler.getProxyServer().getServer(intendedTarget).isEmpty()) {
                        VelocityLimboHandler.getLogger().warning(String.format(
                                "Forced-host '%s' for %s targets currently unavailable server '%s'; preserving destination",
                                virtualHost, player.getUsername(), intendedTarget));
                    }
                }
            }

            // Fallback to previous server or default
            if (intendedTarget == null) {
                if (previousServer != null) {
                    intendedTarget = previousServer.getServerInfo().getName();
                } else {
                    intendedTarget = VelocityLimboHandler.getConfigManager().getDirectConnectServerName();
                }
                String fallbackTarget = intendedTarget;
                Utility.logDebug(() -> String.format("%s no forced-host target resolved — falling back to '%s'",
                        player.getUsername(), fallbackTarget));
            }

            String queuedTarget = intendedTarget;
            Utility.logDebug(() -> String.format("%s will be queued for '%s'",
                    player.getUsername(), queuedTarget));

            api.onPlayerArrived(player, intendedTarget);
        }
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        Player player = event.getPlayer();
        api.onPlayerDisconnected(player);
        reconnectBlocker.unblock(player.getUniqueId());
    }

    private boolean isLimbo(RegisteredServer server) {
        return server.getServerInfo().getName()
                .equalsIgnoreCase(VelocityLimboHandler.getConfigManager().getLimboName());
    }
}

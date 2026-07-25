package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.api.VelocityLimboApiImpl;
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

    public ConnectionListener(VelocityLimboApiImpl api, PlayerManager playerManager) {
        this.api = api;
        this.playerManager = playerManager;
    }

    @Subscribe
    public void onPlayerPreConnect(@NotNull ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer intendedServer = event.getOriginalServer();
        RegisteredServer limbo = VelocityLimboHandler.getLimboServer();

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

        RegisteredServer limbo = VelocityLimboHandler.getLimboServer();
        RegisteredServer currentServer = player.getCurrentServer().map(ServerConnection::getServer).orElse(null);
        RegisteredServer previousServer = event.getPreviousServer();

        if (currentServer == null) {
            VelocityLimboHandler.getLogger().severe(String.format("Current server was null for %s.", player.getUsername()));
            return;
        }

        // Remove player from queue if they left Limbo and joined another server
        if (previousServer != null && Utility.doServerNamesMatch(previousServer, limbo)) {
            api.onPlayerLeft(player, currentServer);
            VelocityLimboHandler.getReconnectBlocker().unblock(player.getUniqueId());
            return;
        }

        // Handle players who just joined Limbo
        if (Utility.doServerNamesMatch(currentServer, limbo)) {
            // Determine intended server from forced host if available
            String virtualHost = player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null);

            Utility.logDebug(() -> String.format("%s landed on limbo (previousServer: %s, virtualHost: %s)",
                    player.getUsername(),
                    previousServer != null ? previousServer.getServerInfo().getName() : "none",
                    virtualHost != null ? virtualHost : "none"));

            RegisteredServer intendedTarget = null;

            if (virtualHost != null) {
                List<String> forcedServers = VelocityLimboHandler.getProxyServer()
                        .getConfiguration()
                        .getForcedHosts()
                        .get(virtualHost);

                Utility.logDebug(() -> String.format("%s forced-host lookup for '%s': %s",
                        player.getUsername(), virtualHost,
                        forcedServers != null ? forcedServers.toString() : "no match"));

                if (forcedServers != null && !forcedServers.isEmpty()) {
                    String targetName = forcedServers.get(0);
                    intendedTarget = VelocityLimboHandler.getProxyServer()
                            .getServer(targetName)
                            .orElse(null);

                    if (intendedTarget == null) {
                        VelocityLimboHandler.getLogger().warning(String.format(
                                "Forced-host '%s' for %s maps to unknown server '%s' — check velocity.toml",
                                virtualHost, player.getUsername(), targetName));
                    }
                }
            }

            // Fallback to previous server or default
            if (intendedTarget == null) {
                if (previousServer != null) {
                    intendedTarget = previousServer;
                } else {
                    intendedTarget = VelocityLimboHandler.getDirectConnectServer();
                }
                RegisteredServer fallbackTarget = intendedTarget;
                Utility.logDebug(() -> String.format("%s no forced-host target resolved — falling back to '%s'",
                        player.getUsername(), fallbackTarget.getServerInfo().getName()));
            }

            RegisteredServer queuedTarget = intendedTarget;
            Utility.logDebug(() -> String.format("%s will be queued for '%s'",
                    player.getUsername(), queuedTarget.getServerInfo().getName()));

            api.onPlayerArrived(player, intendedTarget);
        }
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        Player player = event.getPlayer();
        api.onPlayerDisconnected(player);
        VelocityLimboHandler.getReconnectBlocker().unblock(player.getUniqueId());
    }
}

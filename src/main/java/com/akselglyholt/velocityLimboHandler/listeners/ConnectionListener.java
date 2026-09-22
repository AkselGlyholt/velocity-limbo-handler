package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.List;

public class ConnectionListener {

    /**
     * Recovers players who lose the backend they are currently on without affecting normal joins
     * or failed server-switch attempts.
     */
    @Subscribe
    public void onKickedFromServer(@NotNull KickedFromServerEvent event) {
        RegisteredServer limbo = VelocityLimboHandler.getLimboServer();

        if (event.kickedDuringServerConnect()
                || !event.getPlayer().isActive()
                || Utility.doServerNamesMatch(event.getServer(), limbo)) {
            return;
        }

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(limbo));
        Utility.logDebug(() -> String.format("Redirecting %s to Limbo after losing %s",
                event.getPlayer().getUsername(), event.getServer().getServerInfo().getName()));
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
        if (VelocityLimboHandler.getPlayerManager().isPlayerConnecting(player)) {
            return;
        }

        // Check if the server has a queue (for incidental joins)
        if (VelocityLimboHandler.getPlayerManager().hasQueuedPlayers(intendedServer)) {
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
            VelocityLimboHandler.getPlayerManager().removePlayer(player);
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

            // A backend loss has an authoritative recovery target. Forced hosts only determine
            // where a player who joins the proxy without a previous backend should be queued.
            RegisteredServer intendedTarget = previousServer;

            if (intendedTarget == null && virtualHost != null) {
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

            // Fallback to the direct-connect server when neither a previous server nor forced host exists.
            if (intendedTarget == null) {
                intendedTarget = VelocityLimboHandler.getDirectConnectServer();
                RegisteredServer fallbackTarget = intendedTarget;
                Utility.logDebug(() -> String.format("%s no forced-host target resolved — falling back to '%s'",
                        player.getUsername(), fallbackTarget.getServerInfo().getName()));
            }

            RegisteredServer queuedTarget = intendedTarget;
            Utility.logDebug(() -> String.format("%s will be queued for '%s'",
                    player.getUsername(), queuedTarget.getServerInfo().getName()));

            VelocityLimboHandler.getPlayerManager().addPlayer(player, intendedTarget);
        }
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        Player player = event.getPlayer();
        VelocityLimboHandler.getPlayerManager().removePlayer(player);
        VelocityLimboHandler.getPlayerManager().removePlayerIssue(player);
        VelocityLimboHandler.getReconnectBlocker().unblock(player.getUniqueId());
    }
}

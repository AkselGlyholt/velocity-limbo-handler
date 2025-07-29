package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.List;

public class ConnectionListener {

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
            String virtualHost = player.getVirtualHost().map(InetSocketAddress::getHostName).orElse(null);

            RegisteredServer intendedTarget = null;

            if (virtualHost != null) {
                List<String> forcedServers = VelocityLimboHandler.getProxyServer()
                        .getConfiguration()
                        .getForcedHosts()
                        .get(virtualHost);

                if (forcedServers != null && !forcedServers.isEmpty()) {
                    intendedTarget = VelocityLimboHandler.getProxyServer()
                            .getServer(forcedServers.get(0))
                            .orElse(null);
                }
            }

            // Fallback to previous server or default
            if (intendedTarget == null) {
                if (previousServer != null) {
                    intendedTarget = previousServer;
                } else {
                    intendedTarget = VelocityLimboHandler.getDirectConnectServer();
                }
            }

            VelocityLimboHandler.getPlayerManager().addPlayer(player, intendedTarget);
        }
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        Player player = event.getPlayer();
        VelocityLimboHandler.getPlayerManager().removePlayer(player);
        VelocityLimboHandler.getPlayerManager().removePlayerIssue(player);
    }
}

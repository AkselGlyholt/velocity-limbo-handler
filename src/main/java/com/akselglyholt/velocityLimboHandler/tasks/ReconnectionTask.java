package com.akselglyholt.velocityLimboHandler.tasks;

import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.managers.ReconnectHandler;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Collection;

public class ReconnectionTask implements Runnable {
    private final ProxyServer proxyServer;
    private final RegisteredServer limboServer;
    private final PlayerManager playerManager;
    private final AuthManager authManager;
    private final ConfigManager configManager;
    private final ReconnectHandler reconnectHandler;

    public ReconnectionTask(ProxyServer proxyServer, RegisteredServer limboServer, PlayerManager playerManager,
                            AuthManager authManager, ConfigManager configManager, ReconnectHandler reconnectHandler) {
        this.proxyServer = proxyServer;
        this.limboServer = limboServer;
        this.playerManager = playerManager;
        this.authManager = authManager;
        this.configManager = configManager;
        this.reconnectHandler = reconnectHandler;
    }

    @Override
    public void run() {
        // Prevent unnecessary processing when no players are connected
        Collection<Player> connectedPlayers = limboServer.getPlayersConnected();
        if (connectedPlayers.isEmpty()) return;

        // Prune all in-active members
        playerManager.pruneInactivePlayers();

        // Loop through all servers, if queue is enabled
        if (configManager.isQueueEnabled()) {
            for (RegisteredServer server : proxyServer.getAllServers()) {
                // Queue mode – get the next player from the queue
                if (!playerManager.hasQueuedPlayers(server)) continue;

                // Check if the server is in Maintenance mode
                if (Utility.isServerInMaintenance(server.getServerInfo().getName())) {
                    // Is in Maintenance mode, so find first player in queue that can join
                    Player whitelistedPlayer = PlayerManager.findFirstMaintenanceAllowedPlayer(server);

                    if (whitelistedPlayer != null && whitelistedPlayer.isActive()) {
                        reconnectHandler.reconnectPlayer(whitelistedPlayer);
                    }
                } else {
                    // Is not in Maintenance mode, so carry on with normal queue.
                    Player nextPlayer = playerManager.getNextQueuedPlayer(server);
                    reconnectHandler.reconnectPlayer(nextPlayer);
                }
            }
        } else {
            for (Player player : connectedPlayers) {
                if (!playerManager.hasConnectionIssue(player) && player.isActive()) {
                    // Check if the server is in maintenance mode
                    RegisteredServer previousServer = playerManager.getPreviousServer(player);

                    if (Utility.isServerInMaintenance(previousServer.getServerInfo().getName())) {
                        // Check if the player has whitelist or another bypass to join, or continue to next player
                        if (player.hasPermission("maintenance.admin")
                                || player.hasPermission("maintenance.bypass")
                                || player.hasPermission("maintenance.singleserver.bypass." + previousServer.getServerInfo().getName())
                                || Utility.playerMaintenanceWhitelisted(player)
                                || authManager.isAuthBlocked(player)) {
                            // Can't join server whilst in Maintenance, or player is Auth Blocked so continue to next
                            continue;
                        }
                    }

                    reconnectHandler.reconnectPlayer(player);
                    break;
                }
            }
        }
    }
}

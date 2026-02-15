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
import java.util.HashMap;
import java.util.Map;

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
        Map<String, Boolean> maintenanceCache = new HashMap<>();

        if (configManager.isQueueEnabled()) {
            for (RegisteredServer server : proxyServer.getAllServers()) {
                // Check if the server is in Maintenance mode
                if (isServerInMaintenance(server, maintenanceCache)) {
                    // Is in Maintenance mode, so find first player in queue that can join
                    Player whitelistedPlayer = PlayerManager.findFirstMaintenanceAllowedPlayer(server);

                    if (whitelistedPlayer != null && whitelistedPlayer.isActive()) {
                        reconnectHandler.reconnectPlayer(whitelistedPlayer);
                    }
                } else {
                    // Is not in Maintenance mode, so carry on with normal queue.
                    Player nextPlayer = playerManager.getNextQueuedPlayer(server);
                    if (nextPlayer == null) {
                        continue;
                    }

                    reconnectHandler.reconnectPlayer(nextPlayer);
                }
            }
        } else {
            for (Player player : connectedPlayers) {
                if (!playerManager.hasConnectionIssue(player) && player.isActive()) {
                    // Check if the server is in maintenance mode
                    RegisteredServer previousServer = playerManager.getPreviousServer(player);

                    if (isServerInMaintenance(previousServer, maintenanceCache)) {
                        // Continue only if player does NOT have a maintenance bypass/whitelist entry
                        boolean canBypassMaintenance = player.hasPermission("maintenance.admin")
                                || player.hasPermission("maintenance.bypass")
                                || player.hasPermission("maintenance.singleserver.bypass." + previousServer.getServerInfo().getName())
                                || Utility.playerMaintenanceWhitelisted(player);

                        if (!canBypassMaintenance) {
                            continue;
                        }
                    }

                    boolean reconnectAttempted = reconnectHandler.reconnectPlayer(player);
                    if (reconnectAttempted) {
                        break;
                    }
                }
            }
        }
    }

    private boolean isServerInMaintenance(RegisteredServer server, Map<String, Boolean> maintenanceCache) {
        String serverName = server.getServerInfo().getName();
        return maintenanceCache.computeIfAbsent(serverName, Utility::isServerInMaintenance);
    }
}

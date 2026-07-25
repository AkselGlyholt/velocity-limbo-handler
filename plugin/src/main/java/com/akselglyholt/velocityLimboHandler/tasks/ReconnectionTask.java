package com.akselglyholt.velocityLimboHandler.tasks;

import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.api.VelocityLimboApiImpl;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.managers.ReconnectHandler;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class ReconnectionTask implements Runnable {
    private final ProxyServer proxyServer;
    private final RegisteredServer limboServer;
    private final PlayerManager playerManager;
    private final AuthManager authManager;
    private final ConfigManager configManager;
    private final ReconnectHandler reconnectHandler;
    private final VelocityLimboApiImpl api;
    private int serverCursor;

    public ReconnectionTask(ProxyServer proxyServer, RegisteredServer limboServer, PlayerManager playerManager,
                            AuthManager authManager, ConfigManager configManager, ReconnectHandler reconnectHandler,
                            VelocityLimboApiImpl api) {
        this.proxyServer = proxyServer;
        this.limboServer = limboServer;
        this.playerManager = playerManager;
        this.authManager = authManager;
        this.configManager = configManager;
        this.reconnectHandler = reconnectHandler;
        this.api = api;
    }

    @Override
    public void run() {
        // Prevent unnecessary processing when no players are connected
        Collection<Player> connectedPlayers = limboServer.getPlayersConnected();
        if (connectedPlayers.isEmpty()) return;

        // Disconnect events normally clean state immediately; this is only a periodic safety net.
        playerManager.pruneInactivePlayersIfDue();

        if (configManager.isQueueEnabled()) {
            processQueueEnabled();
        } else {
            processQueueDisabled(connectedPlayers);
        }
    }

    private void processQueueEnabled() {
        List<String> queuedServerNames = new ArrayList<>(playerManager.getQueuedServerNames());
        Collections.sort(queuedServerNames);
        int serverCount = queuedServerNames.size();
        if (serverCount == 0) {
            return;
        }

        int startIndex = Math.floorMod(serverCursor, serverCount);
        int batchSize = Math.min(serverCount, Math.max(1, configManager.getReconnectBatchSize()));
        for (int offset = 0; offset < batchSize; offset++) {
            String serverName = queuedServerNames.get((startIndex + offset) % serverCount);
            RegisteredServer server = proxyServer.getServer(serverName).orElse(null);
            if (server == null || api.isServerHeld(serverName)) {
                continue;
            }

            if (Utility.isServerInMaintenance(serverName)) {
                Player whitelistedPlayer = PlayerManager.findFirstMaintenanceAllowedPlayer(server);
                if (whitelistedPlayer != null && whitelistedPlayer.isActive()) {
                    reconnectHandler.reconnectPlayer(whitelistedPlayer);
                }
                continue;
            }

            Player nextPlayer = playerManager.getNextQueuedPlayer(server);
            if (nextPlayer != null) {
                reconnectHandler.reconnectPlayer(nextPlayer);
            }
        }
        serverCursor = (startIndex + batchSize) % serverCount;
    }

    private void processQueueDisabled(Collection<Player> connectedPlayers) {
        Map<String, Boolean> maintenanceCache = new HashMap<>();
        for (Player player : connectedPlayers) {
            if (playerManager.hasConnectionIssue(player) || api.isPlayerHeld(player.getUniqueId())
                    || !player.isActive()) {
                continue;
            }

            RegisteredServer previousServer = playerManager.getPreviousServer(player);
            if (previousServer == null || api.isServerHeld(previousServer.getServerInfo().getName())) {
                continue;
            }

            if (isServerInMaintenance(previousServer, maintenanceCache)
                    && !canBypassMaintenance(player, previousServer)) {
                continue;
            }

            if (reconnectHandler.reconnectPlayer(player)) {
                break;
            }
        }
    }

    private boolean canBypassMaintenance(Player player, RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        return player.hasPermission("maintenance.admin")
                || player.hasPermission("maintenance.bypass")
                || player.hasPermission("maintenance.singleserver.bypass." + serverName)
                || Utility.playerMaintenanceWhitelisted(player);
    }

    private boolean isServerInMaintenance(RegisteredServer server, Map<String, Boolean> maintenanceCache) {
        String serverName = server.getServerInfo().getName();
        return maintenanceCache.computeIfAbsent(serverName, Utility::isServerInMaintenance);
    }
}

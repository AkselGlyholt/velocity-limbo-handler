package com.akselglyholt.velocityLimboHandler.tasks;

import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class QueueNotifierTask implements Runnable {
    private final ProxyServer proxyServer;
    private final RegisteredServer limboServer;
    private final PlayerManager playerManager;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final ArrayDeque<UUID> pendingPlayers = new ArrayDeque<>();
    private long nextCycleAtNanos;
    private long nextDispatchAtNanos;
    private long dispatchSpacingNanos;

    public QueueNotifierTask(ProxyServer proxyServer, RegisteredServer limboServer, PlayerManager playerManager,
                             ConfigManager configManager) {
        this.proxyServer = proxyServer;
        this.limboServer = limboServer;
        this.playerManager = playerManager;
        this.configManager = configManager;
    }

    @Override
    public void run() {
        long now = System.nanoTime();
        if (pendingPlayers.isEmpty()) {
            if (now < nextCycleAtNanos) {
                return;
            }
            startNotificationCycle(now);
        }

        Map<String, Boolean> maintenanceCache = new HashMap<>();
        int batchLimit = Math.max(1, configManager.getQueueNotifyBatchSize());
        int processed = 0;
        while (!pendingPlayers.isEmpty() && now >= nextDispatchAtNanos && processed < batchLimit) {
            UUID playerId = pendingPlayers.removeFirst();
            proxyServer.getPlayer(playerId)
                    .filter(Player::isActive)
                    .filter(this::isInLimbo)
                    .ifPresent(player -> notifyPlayer(player, maintenanceCache));
            nextDispatchAtNanos += dispatchSpacingNanos;
            processed++;
        }
    }

    private void startNotificationCycle(long now) {
        for (Player player : limboServer.getPlayersConnected()) {
            pendingPlayers.addLast(player.getUniqueId());
        }

        long intervalNanos = TimeUnit.SECONDS.toNanos(Math.max(1, configManager.getQueueNotifyInterval()));
        nextCycleAtNanos = now + intervalNanos;
        nextDispatchAtNanos = now;
        dispatchSpacingNanos = pendingPlayers.isEmpty() ? intervalNanos : Math.max(1L, intervalNanos / pendingPlayers.size());
    }

    private boolean isInLimbo(Player player) {
        return player.getCurrentServer()
                .map(connection -> Utility.doServerNamesMatch(connection.getServer(), limboServer))
                .orElse(false);
    }

    private void notifyPlayer(Player player, Map<String, Boolean> maintenanceCache) {
        String issue = playerManager.getConnectionIssue(player);
        if (issue != null) {

            if ("banned".equals(issue)) {
                String formatedMsg = MessageFormatter.formatMessage(configManager.getBannedMsg(), player);

                player.sendMessage(miniMessage.deserialize(formatedMsg));
            } else if ("not_whitelisted".equals(issue)) {
                String formatedMsg = MessageFormatter.formatMessage(configManager.getWhitelistedMsg(), player);

                player.sendMessage(miniMessage.deserialize(formatedMsg));
            }
            return;
        }

        RegisteredServer previousServer = playerManager.getPreviousServer(player);
        String serverName = previousServer.getServerInfo().getName();

        if (maintenanceCache.computeIfAbsent(serverName, Utility::isServerInMaintenance)) {
            String formatedMsg = MessageFormatter.formatMessage(configManager.getMaintenanceModeMsg(), player);

            player.sendMessage(miniMessage.deserialize(formatedMsg));
            return;
        }

        if (!configManager.isQueueEnabled()) return;

        int position = playerManager.getQueuePosition(player);
        if (position == -1) return;
        String formatedQueuePositionMsg = MessageFormatter.formatMessage(configManager.getQueuePositionMsg(), player);

        player.sendMessage(miniMessage.deserialize(formatedQueuePositionMsg));
    }
}

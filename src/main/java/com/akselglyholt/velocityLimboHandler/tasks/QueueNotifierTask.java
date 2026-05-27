package com.akselglyholt.velocityLimboHandler.tasks;

import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class QueueNotifierTask implements Runnable {
    private final RegisteredServer limboServer;
    private final PlayerManager playerManager;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public QueueNotifierTask(RegisteredServer limboServer, PlayerManager playerManager, ConfigManager configManager) {
        this.limboServer = limboServer;
        this.playerManager = playerManager;
        this.configManager = configManager;
    }

    @Override
    public void run() {
        for (Player player : limboServer.getPlayersConnected()) {
            // Always show connection issue messages regardless of queue status
            if (playerManager.hasConnectionIssue(player)) {
                String issue = playerManager.getConnectionIssue(player);

                if ("banned".equals(issue)) {
                    String formatedMsg = MessageFormatter.formatMessage(configManager.getBannedMsg(), player);

                    player.sendMessage(miniMessage.deserialize(formatedMsg));
                } else if ("not_whitelisted".equals(issue)) {
                    String formatedMsg = MessageFormatter.formatMessage(configManager.getWhitelistedMsg(), player);

                    player.sendMessage(miniMessage.deserialize(formatedMsg));
                }
                continue;
            }

            RegisteredServer previousServer = playerManager.getPreviousServer(player);

            if (Utility.isServerInMaintenance(previousServer.getServerInfo().getName())) {
                String formatedMsg = MessageFormatter.formatMessage(configManager.getMaintenanceModeMsg(), player);

                player.sendMessage(miniMessage.deserialize(formatedMsg));
                continue;
            }

            // Only show queue position if queue is enabled
            if (!configManager.isQueueEnabled()) continue;

            int position = playerManager.getQueuePosition(player);
            if (position == -1) continue;
            String formatedQueuePositionMsg = MessageFormatter.formatMessage(configManager.getQueuePositionMsg(), player);

            player.sendMessage(miniMessage.deserialize(formatedQueuePositionMsg));
        }
    }
}

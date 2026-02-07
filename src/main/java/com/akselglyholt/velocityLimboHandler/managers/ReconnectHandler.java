package com.akselglyholt.velocityLimboHandler.managers;

import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Optional;
import java.util.logging.Logger;

public class ReconnectHandler {
    private final PlayerManager playerManager;
    private final AuthManager authManager;
    private final ConfigManager configManager;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ReconnectHandler(PlayerManager playerManager, AuthManager authManager, ConfigManager configManager, Logger logger) {
        this.playerManager = playerManager;
        this.authManager = authManager;
        this.configManager = configManager;
        this.logger = logger;
    }

    public boolean reconnectPlayer(Player player) {
        if (player == null || !player.isActive()) return false;
        if (authManager.isAuthBlocked(player)) return false;

        RegisteredServer previousServer = playerManager.getPreviousServer(player);
        if (previousServer == null) return false;

        if (playerManager.isPlayerConnecting(player)) return false;

        playerManager.setPlayerConnecting(player, true);

        // If enabled, check if a server responds to pings before connecting, asynchronously
        previousServer.ping().whenComplete((ping, throwable) -> {
            if (throwable != null || ping == null) {
                playerManager.setPlayerConnecting(player, false);
                return; // Server offline
            }

            // Check if the server is full
            if (ping.getPlayers().isEmpty()) {
                playerManager.setPlayerConnecting(player, false);
                return;
            }

            ServerPing.Players serverPlayers = ping.getPlayers().get();
            int maxPlayers = serverPlayers.getMax();
            int onlinePlayers = serverPlayers.getOnline();

            if (maxPlayers <= onlinePlayers) {
                playerManager.setPlayerConnecting(player, false);
                return;
            }

            // Check if maintenance mode is enabled on Backend Server
            if (Utility.isServerInMaintenance(previousServer.getServerInfo().getName())) {
                // Check if the user has bypass permission for Maintenance or is admin
                if (player.hasPermission("maintenance.admin")
                        || player.hasPermission("maintenance.bypass")
                        || player.hasPermission("maintenance.singleserver.bypass." + previousServer.getServerInfo().getName())
                        || Utility.playerMaintenanceWhitelisted(player)) {
                    logger.info("[Maintenance Bypass] " + player.getUsername() + " bypassed queue to join " + previousServer.getServerInfo().getName());
                } else {
                    playerManager.setPlayerConnecting(player, false);
                    return;
                }
            }

            Utility.logInformational(String.format("Connecting %s to %s", player.getUsername(), previousServer.getServerInfo().getName()));

            player.createConnectionRequest(previousServer).connect().whenComplete(((result, connectionThrowable) -> {
                playerManager.setPlayerConnecting(player, false);

                if (result.isSuccessful()) {
                    Utility.logInformational(String.format("Successfully reconnected %s to %s", player.getUsername(), previousServer.getServerInfo().getName()));
                    playerManager.removePlayerIssue(player);
                    return;
                }

                if (result.getStatus() == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS) return;

                Utility.logInformational(String.format("Connection failed for %s to %s. Result status: %s",
                        player.getUsername(),
                        previousServer.getServerInfo().getName(),
                        result.getStatus()));

                if (connectionThrowable != null) {
                    // Get the error message from throwable
                    String errorMessage = connectionThrowable.getMessage();
                    if (errorMessage == null) errorMessage = "";

                    // Also check the result component if available
                    String reasonFromComponent = "";
                    if (result.getReasonComponent().isPresent()) {
                        reasonFromComponent = PlainTextComponentSerializer.plainText().serialize(result.getReasonComponent().get());
                    }

                    // Check both the throwable message and the component reason
                    String combinedErrorMessage = (errorMessage + " " + reasonFromComponent).toLowerCase();

                    // Notify user of their issue, and them to issue list
                    if (playerConnectIssue(player, combinedErrorMessage)) return;

                    // Handle any other connection errors
                    player.sendMessage(miniMessage.deserialize("<red>❌ Failed to connect: " + (errorMessage.isEmpty() ? reasonFromComponent : errorMessage) + "</red>"));
                } else {
                    // Handle case where we have a result but no throwable
                    Optional<Component> reasonComponent = result.getReasonComponent();

                    if (reasonComponent.isPresent()) {
                        String reason = PlainTextComponentSerializer.plainText().serialize(reasonComponent.get()).toLowerCase();

                        // Notify user of their issue, and them to issue list
                        if (playerConnectIssue(player, reason)) return;

                        // Handle any other connection errors
                        player.sendMessage(miniMessage.deserialize("<red>❌ Failed to connect: " + reason + "</red>"));
                    }
                }
            }));
        });

        return true;
    }

    private boolean playerConnectIssue(Player player, String reason) {
        if (reason.contains("ban") || reason.contains("banned")) {
            String formattedMsg = MessageFormatter.formatMessage(configManager.getBannedMsg(), player);

            player.sendMessage(miniMessage.deserialize(formattedMsg));

            // Mark them with an issue instead of kicking
            playerManager.addPlayerWithIssue(player, "banned");

            // Remove them from the reconnection queue to avoid blocking others
            playerManager.removePlayerFromQueue(player);
            return true;
        }

        if (reason.contains("whitelist") || reason.contains("not whitelisted")) {
            String formattedMsg = MessageFormatter.formatMessage(configManager.getWhitelistedMsg(), player);

            player.sendMessage(miniMessage.deserialize(formattedMsg));

            // Mark them with an issue instead of kicking
            playerManager.addPlayerWithIssue(player, "not_whitelisted");

            // Remove them from the reconnection queue to avoid blocking others
            playerManager.removePlayerFromQueue(player);
            return true;
        }

        return false;
    }
}

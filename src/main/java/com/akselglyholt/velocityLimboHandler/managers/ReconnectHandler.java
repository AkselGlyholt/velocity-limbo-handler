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
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
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

                if (configManager.isConnectionWarningsEnabled()) {
                    Utility.logInformational(String.format("Connection failed for %s to %s. Result status: %s",
                            player.getUsername(),
                            previousServer.getServerInfo().getName(),
                            result.getStatus()));
                }

                if (result.getStatus() == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) {
                    Optional<Component> reasonOpt = result.getReasonComponent();
                    if (reasonOpt.isPresent()) {
                        Component reason = reasonOpt.get();
                        if (!playerConnectIssue(player, reason)) {
                            String plainReason = PlainTextComponentSerializer.plainText().serialize(reason);
                            player.sendMessage(miniMessage.deserialize("<red>❌ Failed to connect: " + plainReason + "</red>"));
                        }
                    }
                    return;
                }

                if (connectionThrowable != null) {
                    String errorMessage = connectionThrowable.getMessage();
                    if (errorMessage != null && !errorMessage.isEmpty()) {
                        player.sendMessage(miniMessage.deserialize("<red>❌ Failed to connect: " + errorMessage + "</red>"));
                    }
                }
            }));
        });

        return true;
    }

    private Optional<Component> extractBanReason(TranslatableComponent component) {
        List<TranslationArgument> args = component.arguments();
        if (args.isEmpty()) return Optional.empty();
        Component reasonComponent = args.get(0).asComponent();
        if (reasonComponent instanceof TranslatableComponent) return Optional.empty();
        String plain = PlainTextComponentSerializer.plainText().serialize(reasonComponent).trim();
        return plain.isEmpty() ? Optional.empty() : Optional.of(reasonComponent);
    }

    private boolean playerConnectIssue(Player player, Component reason) {
        if (reason instanceof TranslatableComponent translatable) {
            String key = translatable.key();
            if (key.contains("banned")) {
                Component message = miniMessage.deserialize(MessageFormatter.formatMessage(configManager.getBannedMsg(), player));
                Optional<Component> banReason = extractBanReason(translatable);
                if (banReason.isPresent()) {
                    message = message.append(Component.newline())
                            .append(Component.text("Reason: ", NamedTextColor.GRAY))
                            .append(banReason.get());
                }
                player.sendMessage(message);
                playerManager.addPlayerWithIssue(player, "banned");
                playerManager.removePlayerFromQueue(player);
                return true;
            }
            if (key.contains("not_whitelisted")) {
                player.sendMessage(miniMessage.deserialize(MessageFormatter.formatMessage(configManager.getWhitelistedMsg(), player)));
                playerManager.addPlayerWithIssue(player, "not_whitelisted");
                playerManager.removePlayerFromQueue(player);
                return true;
            }
        }

        if (reason instanceof TextComponent textComponent && textComponent.content().toLowerCase().contains("whitelist")) {
            player.sendMessage(miniMessage.deserialize(MessageFormatter.formatMessage(configManager.getWhitelistedMsg(), player)));
            playerManager.addPlayerWithIssue(player, "not_whitelisted");
            playerManager.removePlayerFromQueue(player);
            return true;
        }

        return false;
    }
}

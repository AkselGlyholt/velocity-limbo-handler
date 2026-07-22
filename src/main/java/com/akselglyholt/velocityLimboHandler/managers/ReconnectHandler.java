package com.akselglyholt.velocityLimboHandler.managers;

import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

public class ReconnectHandler implements AutoCloseable {
    private final PlayerManager playerManager;
    private final AuthManager authManager;
    private final ConfigManager configManager;
    private final Logger logger;
    private final BackendHealthTracker healthTracker;

    public ReconnectHandler(PlayerManager playerManager, AuthManager authManager, ConfigManager configManager, Logger logger) {
        this(playerManager, authManager, configManager, logger, new BackendHealthTracker());
    }

    ReconnectHandler(PlayerManager playerManager, AuthManager authManager, ConfigManager configManager, Logger logger,
                     BackendHealthTracker healthTracker) {
        this.playerManager = playerManager;
        this.authManager = authManager;
        this.configManager = configManager;
        this.logger = logger;
        this.healthTracker = healthTracker;
    }

    public boolean reconnectPlayer(Player player) {
        if (player == null || !player.isActive()) return false;
        if (authManager.isAuthBlocked(player)) return false;

        RegisteredServer previousServer = playerManager.getPreviousServer(player);
        if (previousServer == null) return false;

        if (playerManager.isPlayerConnecting(player)) return false;

        playerManager.setPlayerConnecting(player, true);

        Utility.logDebug(() -> String.format("Pinging %s for %s", previousServer.getServerInfo().getName(), player.getUsername()));
        healthTracker.probe(previousServer).whenComplete((availability, probeThrowable) ->
                handleProbeResult(player, previousServer, availability, probeThrowable)
        );

        return true;
    }

    private void handleProbeResult(Player player, RegisteredServer server,
                                   BackendHealthTracker.Availability availability, Throwable throwable) {
        boolean connectionStarted = false;
        try {
            if (throwable != null || availability == null || !availability.reachable()) {
                Utility.logDebug(() -> String.format("Ping failed for %s — server likely offline",
                        server.getServerInfo().getName()));
                return;
            }
            if (availability.full()) {
                Utility.logDebug(() -> String.format("Skipping reconnect for %s — %s reports no free slots",
                        player.getUsername(), server.getServerInfo().getName()));
                return;
            }
            if (!player.isActive()) {
                return;
            }

            Utility.logDebug(() -> String.format("Connecting %s to %s",
                    player.getUsername(), server.getServerInfo().getName()));
            var connectionFuture = player.createConnectionRequest(server).connect();
            connectionStarted = true;
            connectionFuture.whenComplete((result, connectionThrowable) ->
                    handleConnectionResult(player, server, result, connectionThrowable)
            );
        } catch (RuntimeException exception) {
            healthTracker.invalidate(server.getServerInfo().getName());
            logger.warning("Failed to start reconnect for " + player.getUsername() + ": " + exception.getMessage());
        } finally {
            if (!connectionStarted) {
                playerManager.setPlayerConnecting(player, false);
            }
        }
    }

    private void handleConnectionResult(Player player, RegisteredServer server,
                                        ConnectionRequestBuilder.Result result, Throwable throwable) {
        try {
            if (throwable != null || result == null) {
                healthTracker.invalidate(server.getServerInfo().getName());
                if (configManager.isConnectionWarningsEnabled()) {
                    logger.warning("Connection failed for " + player.getUsername() + " to "
                            + server.getServerInfo().getName() + ": "
                            + (throwable == null ? "empty result" : throwable.getMessage()));
                }
                return;
            }

            if (result.isSuccessful()) {
                Utility.logDebug(() -> String.format("Successfully reconnected %s to %s",
                        player.getUsername(), server.getServerInfo().getName()));
                playerManager.removePlayerIssue(player);
                return;
            }

            if (result.getStatus() == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS) {
                return;
            }

            if (configManager.isConnectionWarningsEnabled()) {
                Utility.logInformational(String.format("Connection failed for %s to %s. Result status: %s",
                        player.getUsername(), server.getServerInfo().getName(), result.getStatus()));
            }

            if (result.getStatus() == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) {
                Optional<Component> reasonOpt = result.getReasonComponent();
                if (reasonOpt.isPresent()) {
                    Component reason = reasonOpt.get();
                    if (!playerConnectIssue(player, reason)) {
                        String plainReason = PlainTextComponentSerializer.plainText().serialize(reason);
                        player.sendMessage(Component.text("❌ Failed to connect: " + plainReason, NamedTextColor.RED));
                    }
                }
            }
        } catch (RuntimeException exception) {
            logger.warning("Failed to process reconnect result for " + player.getUsername() + ": " + exception.getMessage());
        } finally {
            playerManager.setPlayerConnecting(player, false);
        }
    }

    @Override
    public void close() {
        healthTracker.clear();
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
                Component message = MessageFormatter.formatComponent(configManager.getBannedMsg(), player);
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
                player.sendMessage(MessageFormatter.formatComponent(configManager.getWhitelistedMsg(), player));
                playerManager.addPlayerWithIssue(player, "not_whitelisted");
                playerManager.removePlayerFromQueue(player);
                return true;
            }
        }

        if (reason instanceof TextComponent textComponent
                && textComponent.content().toLowerCase(Locale.ROOT).contains("whitelist")) {
            player.sendMessage(MessageFormatter.formatComponent(configManager.getWhitelistedMsg(), player));
            playerManager.addPlayerWithIssue(player, "not_whitelisted");
            playerManager.removePlayerFromQueue(player);
            return true;
        }

        return false;
    }
}

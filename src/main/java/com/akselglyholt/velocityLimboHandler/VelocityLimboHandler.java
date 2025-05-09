package com.akselglyholt.velocityLimboHandler;

import com.akselglyholt.velocityLimboHandler.listeners.ConnectionListener;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(id = "velocity-limbo-handler", name = "VelocityLimboHandler", version = "1.0")
public class VelocityLimboHandler {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(VelocityLimboHandler.class);
    private static ProxyServer proxyServer;
    private static Logger logger;
    private static RegisteredServer limboServer;
    private static RegisteredServer directConnectServer;

    private static PlayerManager playerManager;

    private static YamlDocument config;
    private static boolean queueEnabled;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Inject
    public VelocityLimboHandler(ProxyServer server, Logger loggerInstance, @DataDirectory Path dataDirectory) {
        proxyServer = server;
        logger = loggerInstance;

        try {
            config = YamlDocument.create(new File(dataDirectory.toFile(), "config.yml"),
                    Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("file-version"))
                            .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build()
            );

            config.update();
            config.save();
        } catch (IOException e) {
            logger.severe("Something went wrong while trying to update/create config: " + e);
            logger.severe("Plugin will now shut down!");
            Optional<PluginContainer> container = proxyServer.getPluginManager().getPlugin("velocity-limbo-handler");
            container.ifPresent(pluginContainer -> pluginContainer.getExecutorService().shutdown());
        }

        playerManager = new PlayerManager();
    }

    public static RegisteredServer getLimboServer() {
        return limboServer;
    }

    public static RegisteredServer getDirectConnectServer() {
        return directConnectServer;
    }

    public static ProxyServer getProxyServer() {
        return proxyServer;
    }

    public static Logger getLogger() {
        return logger;
    }

    public static PlayerManager getPlayerManager() {
        return playerManager;
    }

    public static YamlDocument getConfig() {
        return config;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        logger.info("Loading Limbo Handler!");

        EventManager eventManger = proxyServer.getEventManager();

        String limboName = config.getString(Route.from("limbo-name"));
        String directConnectName = config.getString(Route.from("direct-connect-server"));

        limboServer = Utility.getServerByName(limboName);
        directConnectServer = Utility.getServerByName(directConnectName);

        // If either server is null, "self-destruct"
        if (limboServer == null || directConnectServer == null) {
            eventManger.unregisterListeners(this);
            return;
        }

        eventManger.register(this, new ConnectionListener());

        int reconnectInterval = config.getInt(Route.from("task-interval"));
        int queueInterval = config.getInt(Route.from("queue-notify-interval"));
        queueEnabled = config.getBoolean(Route.from("queue-enabled"), true);
        getLogger().info("Queue Enabled: " + queueEnabled);

        // Schedule the reconnection task
        proxyServer.getScheduler().buildTask(this, () -> {
            // Prevent unnecessary processing when no players are connected
            Collection<Player> connectedPlayers = limboServer.getPlayersConnected();
            if (connectedPlayers.isEmpty()) return;

            Player nextPlayer;

            if (queueEnabled) {
                // Queue mode - get the next player from the queue
                if (!playerManager.hasQueuedPlayers()) return;
                nextPlayer = playerManager.getNextQueuedPlayer();
            } else {
                // Non-queue mode - get the first connected player that doesn't have issues
                nextPlayer = null;
                for (Player player : connectedPlayers) {
                    if (!playerManager.hasConnectionIssue(player)) {
                        nextPlayer = player;
                        break;
                    }
                }

                // If all players have issues, just return
                if (nextPlayer == null) return;
            }

            if (nextPlayer == null || !nextPlayer.isActive()) return;

            // Skip players with connection issues
            if (playerManager.hasConnectionIssue(nextPlayer)) return;

            RegisteredServer previousServer = playerManager.getPreviousServer(nextPlayer);

            // If enabled, check if a server responds to pings before connecting
            try {
                try {
                    previousServer.ping().join(); // Check if the server is online
                } catch (CompletionException completionException) {
                    // Server failed to respond to ping request, return to prevent spam
                    return;
                }

                Utility.logInformational(String.format("Connecting %s to %s.", nextPlayer.getUsername(), previousServer.getServerInfo().getName()));

                Player finalNextPlayer = nextPlayer;

                nextPlayer.createConnectionRequest(previousServer).connect().whenComplete((result, throwable) -> {
                    if (result.isSuccessful()) {
                        Utility.logInformational(String.format("Successfully reconnected %s to %s.", finalNextPlayer.getUsername(), previousServer.getServerInfo().getName()));
                        playerManager.removePlayerIssue(finalNextPlayer);
                        return;
                    }

                    Utility.logInformational(String.format("Connection failed for %s to %s. Result status: %s",
                            finalNextPlayer.getUsername(), previousServer.getServerInfo().getName(), result.getStatus()));

                    if (throwable != null) {
                        // Get the error message from throwable
                        String errorMessage = throwable.getMessage();
                        if (errorMessage == null) {
                            errorMessage = "";
                        }

                        // Also check the result component if available
                        String reasonFromComponent = "";
                        if (result.getReasonComponent().isPresent()) {
                            reasonFromComponent = PlainTextComponentSerializer.plainText().serialize(result.getReasonComponent().get());
                        }

                        // Check both the throwable message and the component reason
                        String combinedErrorMessage = (errorMessage + " " + reasonFromComponent).toLowerCase();

                        if (combinedErrorMessage.contains("ban") || combinedErrorMessage.contains("banned")) {
                            finalNextPlayer.sendMessage(miniMessage.deserialize("<red>⛔ You are banned from that server.</red>"));
                            // Mark them with an issue instead of kicking
                            playerManager.addPlayerWithIssue(finalNextPlayer, "banned");
                            return;
                        }

                        if (combinedErrorMessage.contains("whitelist") || combinedErrorMessage.contains("not whitelisted")) {
                            finalNextPlayer.sendMessage(miniMessage.deserialize("<red>⚠ You are not whitelisted on that server.</red>"));
                            // Mark them with an issue instead of kicking
                            playerManager.addPlayerWithIssue(finalNextPlayer, "not_whitelisted");
                            return;
                        }

                        // Handle any other connection errors
                        finalNextPlayer.sendMessage(miniMessage.deserialize("<red>❌ Failed to connect: " + (errorMessage.isEmpty() ? reasonFromComponent : errorMessage) + "</red>"));
                    } else {
                        // Handle case where we have a result but no throwable
                        Optional<Component> reasonComponent = result.getReasonComponent();
                        if (reasonComponent.isPresent()) {
                            String reason = PlainTextComponentSerializer.plainText().serialize(reasonComponent.get()).toLowerCase();

                            if (reason.contains("whitelist") || reason.contains("not whitelisted")) {
                                finalNextPlayer.sendMessage(miniMessage.deserialize("<red>⚠ You are not whitelisted on that server.</red>"));
                                // Instead of kicking and removing the player, mark them with an issue
                                playerManager.addPlayerWithIssue(finalNextPlayer, "not_whitelisted");
                                // Remove them from the reconnection queue but keep them in limbo
                                playerManager.removePlayer(finalNextPlayer);
                                return;
                            }

                            if (reason.contains("ban") || reason.contains("banned")) {
                                finalNextPlayer.sendMessage(miniMessage.deserialize("<red>⛔ You are banned from that server.</red>"));
                                // Instead of kicking and removing the player, mark them with an issue
                                playerManager.addPlayerWithIssue(finalNextPlayer, "banned");
                                // Remove them from the reconnection queue but keep them in limbo
                                playerManager.removePlayer(finalNextPlayer);
                                return;
                            }


                            finalNextPlayer.sendMessage(miniMessage.deserialize("<red>❌ Failed to connect: " + reason + "</red>"));
                        }
                    }
                });

                //playerManager.removePlayer(nextPlayer);
            } catch (CompletionException exception) {
                // Prevent console from being spammed when a server is offline and ping-check is disabled
            }
        }).repeat(reconnectInterval, TimeUnit.SECONDS).schedule();

        // Schedule queue position notifier
        proxyServer.getScheduler().buildTask(this, () -> {
            for (Player player : limboServer.getPlayersConnected()) {
                // Always show connection issue messages regardless of queue status
                if (playerManager.hasConnectionIssue(player)) {
                    String issue = playerManager.getConnectionIssue(player);

                    if ("banned".equals(issue)) {
                        player.sendMessage(miniMessage.deserialize("<red>⛔ You are banned from the server you were trying to connect to.</red>"));
                    } else if ("not_whitelisted".equals(issue)) {
                        player.sendMessage(miniMessage.deserialize("<red>⚠ You are not whitelisted on the server you were trying to connect to.</red>"));
                    }
                    continue;
                }

                // Only show queue position if queue is enabled
                if (!queueEnabled) continue;

                int position = playerManager.getQueuePosition(player);
                if (position == -1) continue;

                player.sendMessage(miniMessage.deserialize("<yellow>Queue position: " + position));
            }
        }).repeat(queueInterval, TimeUnit.SECONDS).schedule();
    }

    public static boolean isQueueEnabled() {
        return queueEnabled;
    }
}

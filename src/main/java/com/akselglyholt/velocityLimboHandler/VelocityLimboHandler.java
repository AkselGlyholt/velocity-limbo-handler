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

        logger.info("Intervals " + reconnectInterval + " " + queueInterval);

        // Schedule the reconnection task
        proxyServer.getScheduler().buildTask(this, () -> {
            // Prevent NullPointerException when Queue is empty
            if (!playerManager.hasQueuedPlayers()) return;
            Collection<Player> connectedPlayers = limboServer.getPlayersConnected();

            if (connectedPlayers.isEmpty()) return;

            Player nextPlayer;

            if (queueEnabled) {
                nextPlayer = playerManager.getNextQueuedPlayer();
            } else {
                nextPlayer = null;
                connectedPlayers.iterator().next();
            }

            if (nextPlayer == null || !nextPlayer.isActive()) return;

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
                nextPlayer.createConnectionRequest(previousServer).connect().whenComplete((result, throwable) -> {
                    if (result.isSuccessful()) {
                        Utility.logInformational(String.format("Successfully reconnected %s to %s.", nextPlayer.getUsername(), previousServer.getServerInfo().getName()));
                        return;
                    }

                    Utility.logInformational(String.format("Connection failed for %s to %s. Result status: %s",
                            nextPlayer.getUsername(), previousServer.getServerInfo().getName(), result.getStatus()));

                    if (throwable != null) {
                        log.error("Connection Message Error: " + throwable.getMessage());

                        // Get the error message from throwable
                        String errorMessage = throwable.getMessage();

                        if (errorMessage == null) {
                            errorMessage = "";
                        }

                        log.info("Disconnect reason (throwable): " + errorMessage);

                        // Also check the result component if available
                        String reasonFromComponent = "";
                        if (result.getReasonComponent().isPresent()) {
                            reasonFromComponent = PlainTextComponentSerializer.plainText().serialize(result.getReasonComponent().get());
                            log.info("Disconnect reason (component): " + reasonFromComponent);
                        }

                        // Check both the throwable message and the component reason
                        String combinedErrorMessage = (errorMessage + " " + reasonFromComponent).toLowerCase();

                        if (combinedErrorMessage.contains("ban") || combinedErrorMessage.contains("banned")) {
                            nextPlayer.sendMessage(miniMessage.deserialize("<red>⛔ You are banned from that server.</red>"));
                            playerManager.removePlayer(nextPlayer);
                            nextPlayer.disconnect(miniMessage.deserialize("<red>You're banned from that server!</red>"));
                            return;
                        }

                        if (combinedErrorMessage.contains("whitelist") || combinedErrorMessage.contains("not whitelisted")) {
                            nextPlayer.sendMessage(miniMessage.deserialize("<red>⚠ You are not whitelisted on that server.</red>"));
                            playerManager.removePlayer(nextPlayer);
                            nextPlayer.disconnect(miniMessage.deserialize("<red>You're not whitelisted on the server you're trying to connect to!</red>"));
                            return;
                        }

                        // Handle any other connection errors
                        nextPlayer.sendMessage(miniMessage.deserialize("<red>❌ Failed to connect: " + (errorMessage.isEmpty() ? reasonFromComponent : errorMessage) + "</red>"));
                        // Uncomment the next line if you want to remove players from queue on any error
                        // playerManager.removePlayer(nextPlayer);
                    } else {
                        // Handle case where we have a result but no throwable
                        Optional<Component> reasonComponent = result.getReasonComponent();
                        if (reasonComponent.isPresent()) {
                            String reason = PlainTextComponentSerializer.plainText().serialize(reasonComponent.get()).toLowerCase();
                            log.info("Disconnect reason (from result): " + reason);

                            if (reason.contains("whitelist") || reason.contains("not whitelisted")) {
                                nextPlayer.sendMessage(miniMessage.deserialize("<red>⚠ You are not whitelisted on that server.</red>"));
                                // Instead of kicking and removing the player, mark them with an issue
                                playerManager.addPlayerWithIssue(nextPlayer, "not_whitelisted");
                                // Remove them from the reconnection queue but keep them in limbo
                                playerManager.removePlayer(nextPlayer);
                                return;
                            }

                            if (reason.contains("ban") || reason.contains("banned")) {
                                nextPlayer.sendMessage(miniMessage.deserialize("<red>⛔ You are banned from that server.</red>"));
                                // Instead of kicking and removing the player, mark them with an issue
                                playerManager.addPlayerWithIssue(nextPlayer, "banned");
                                // Remove them from the reconnection queue but keep them in limbo
                                playerManager.removePlayer(nextPlayer);
                                return;
                            }


                            nextPlayer.sendMessage(miniMessage.deserialize("<red>❌ Failed to connect: " + reason + "</red>"));
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
                // Check if player has a connection issue
                if (playerManager.hasConnectionIssue(player)) {
                    String issue = playerManager.getConnectionIssue(player);

                    if ("banned".equals(issue)) {
                        player.sendMessage(miniMessage.deserialize("<red>⛔ You are banned from the server you were trying to connect to.</red>"));
                    } else if ("not_whitelisted".equals(issue)) {
                        player.sendMessage(miniMessage.deserialize("<red>⚠ You are not whitelisted on the server you were trying to connect to.</red>"));
                    }
                    continue;
                }

                if (!queueEnabled) continue;

                // Original queue position code for players without issues
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

package com.akselglyholt.velocityLimboHandler;

import com.akselglyholt.velocityLimboHandler.commands.CommandBlockRule;
import com.akselglyholt.velocityLimboHandler.commands.CommandBlocker;
import com.akselglyholt.velocityLimboHandler.listeners.CommandExecuteEventListener;
import com.akselglyholt.velocityLimboHandler.listeners.ConnectionListener;
import com.akselglyholt.velocityLimboHandler.listeners.PreConnectEventListener;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormater;
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
import org.bstats.charts.SingleLineChart;
import org.bstats.velocity.Metrics;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(id = "velocity-limbo-handler", name = "VelocityLimboHandler", authors = "Aksel Glyholt", version = "${project.version}")
public class VelocityLimboHandler {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(VelocityLimboHandler.class);
    private static ProxyServer proxyServer;
    private static Logger logger;
    private static RegisteredServer limboServer;
    private static RegisteredServer directConnectServer;

    private static PlayerManager playerManager;
    private static CommandBlocker commandBlocker;

    private static YamlDocument config;
    private static YamlDocument messageConfig;
    private static boolean queueEnabled;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private static boolean maintenancePluginPresent = false;
    private static Object maintenanceAPI = null;

    private final Metrics.Factory metricsFactory;
    private Metrics metrics;

    // Message caching
    private static String bannedMsg;
    private static String whitelistedMsg;
    private static String maintenanceModeMsg;
    private static String queuePositionMsg;

    @Inject
    public VelocityLimboHandler(ProxyServer server, Logger loggerInstance, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactoryInstance) {
        proxyServer = server;
        logger = loggerInstance;

        try {
            config = YamlDocument.create(new File(dataDirectory.toFile(), "config.yml"), Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), GeneralSettings.DEFAULT, LoaderSettings.builder().setAutoUpdate(true).build(), DumperSettings.DEFAULT, UpdaterSettings.builder().setVersioning(new BasicVersioning("file-version")).setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build());
            messageConfig = YamlDocument.create(new File(dataDirectory.toFile(), "messages.yml"), Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml")), GeneralSettings.DEFAULT, LoaderSettings.builder().setAutoUpdate(true).build(), DumperSettings.DEFAULT, UpdaterSettings.builder().setVersioning(new BasicVersioning("file-version")).setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build());

            config.update();
            config.save();

            messageConfig.update();
            messageConfig.save();

            bannedMsg = messageConfig.getString(Route.from("bannedMessage"));
            whitelistedMsg = messageConfig.getString(Route.from("notWhitelisted"));
            maintenanceModeMsg = messageConfig.getString(Route.from("maintenanceMode"));
            queuePositionMsg = messageConfig.getString(Route.from("queuePosition"));
        } catch (IOException e) {
            logger.severe("Something went wrong while trying to update/create config: " + e);
            logger.severe("Plugin will now shut down!");
            Optional<PluginContainer> container = proxyServer.getPluginManager().getPlugin("velocity-limbo-handler");
            container.ifPresent(pluginContainer -> pluginContainer.getExecutorService().shutdown());
        }

        playerManager = new PlayerManager();
        commandBlocker = new CommandBlocker();
        metricsFactory = metricsFactoryInstance;

        initializeMaintenanceIntegration();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Loading Limbo Handler!");

        int pluginId = 26682;
        metrics = metricsFactory.make(this, pluginId);

        // Metric for players inside the limbo
        metrics.addCustomChart(new SingleLineChart("players_in_limbo", new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return limboServer != null ? limboServer.getPlayersConnected().size() : 0;
            }
        }));
    }

    private void initializeMaintenanceIntegration() {
        Optional<PluginContainer> maintenancePlugin = proxyServer.getPluginManager().getPlugin("maintenance");
        if (maintenancePlugin.isPresent()) {
            try {
                // Load the MaintenanceProvider class
                Class<?> providerClass = Class.forName("eu.kennytv.maintenance.api.MaintenanceProvider");

                // Call MaintenanceProvider.get() - this directly returns the API instance
                maintenanceAPI = providerClass.getMethod("get").invoke(null);

                maintenancePluginPresent = true;
                logger.info("Maintenance plugin detected and integrated successfully.");

            } catch (Exception e) {
                logger.warning("Failed to integrate with Maintenance plugin: " + e.getMessage());
                maintenancePluginPresent = false;
                maintenanceAPI = null;
            }
        } else {
            logger.info("Maintenance plugin not detected - maintenance checks disabled.");
        }
    }

    // Add getter methods for the maintenance API
    public static boolean hasMaintenancePlugin() {
        return maintenancePluginPresent;
    }

    public static Object getMaintenanceAPI() {
        return maintenanceAPI;
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

    public static YamlDocument getMessageConfig() {
        return messageConfig;
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
        eventManger.register(this, new PreConnectEventListener());
        eventManger.register(this, new CommandExecuteEventListener(commandBlocker));

        int reconnectInterval = config.getInt(Route.from("task-interval"));
        int queueInterval = config.getInt(Route.from("queue-notify-interval"));
        queueEnabled = config.getBoolean(Route.from("queue-enabled"), true);
        getLogger().info("Queue Enabled: " + queueEnabled);

        // Disabled commands
        List<String> disabledCommands = config.getStringList("disabled-commands");
        for (String cmd : disabledCommands) {
            commandBlocker.blockCommand(cmd, CommandBlockRule.onServer(limboName));
        }

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

                // Check if maintenance mode is enabled on Backend server
                if (Utility.isServerInMaintenance(previousServer.getServerInfo().getName())) {
                    // Check if the user has bypass permission for Maintenance or is admin

                    if (nextPlayer.hasPermission("maintenance.admin")
                            || nextPlayer.hasPermission("maintenance.bypass")
                            || nextPlayer.hasPermission("maintenance.singleserver.bypass." + limboName)
                            || Utility.playerMaintenanceWhitelisted(nextPlayer)) {
                        logger.info(nextPlayer.getUsername() + " bypassed maintenance to " + previousServer.getServerInfo().getName() + " from " + limboName + " Server");
                    } else {
                        return;
                    }
                }

                Utility.logInformational(String.format("Connecting %s to %s.", nextPlayer.getUsername(), previousServer.getServerInfo().getName()));

                Player finalNextPlayer = nextPlayer;

                nextPlayer.createConnectionRequest(previousServer).connect().whenComplete((result, throwable) -> {
                    if (result.isSuccessful()) {
                        Utility.logInformational(String.format("Successfully reconnected %s to %s.", finalNextPlayer.getUsername(), previousServer.getServerInfo().getName()));
                        playerManager.removePlayerIssue(finalNextPlayer);
                        return;
                    }

                    Utility.logInformational(String.format("Connection failed for %s to %s. Result status: %s", finalNextPlayer.getUsername(), previousServer.getServerInfo().getName(), result.getStatus()));

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
                            String formatedMsg = MessageFormater.formatMessage(bannedMsg, finalNextPlayer);

                            finalNextPlayer.sendMessage(miniMessage.deserialize(formatedMsg));
                            // Mark them with an issue instead of kicking
                            playerManager.addPlayerWithIssue(finalNextPlayer, "banned");
                            return;
                        }

                        if (combinedErrorMessage.contains("whitelist") || combinedErrorMessage.contains("not whitelisted")) {
                            String formatedMsg = MessageFormater.formatMessage(whitelistedMsg, finalNextPlayer);

                            finalNextPlayer.sendMessage(miniMessage.deserialize(formatedMsg));
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
                                String formatedMsg = MessageFormater.formatMessage(whitelistedMsg, finalNextPlayer);

                                finalNextPlayer.sendMessage(miniMessage.deserialize(formatedMsg));
                                // Instead of kicking and removing the player, mark them with an issue
                                playerManager.addPlayerWithIssue(finalNextPlayer, "not_whitelisted");
                                // Remove them from the reconnection queue but keep them in limbo
                                playerManager.removePlayer(finalNextPlayer);
                                return;
                            }

                            if (reason.contains("ban") || reason.contains("banned")) {
                                String formatedMsg = MessageFormater.formatMessage(bannedMsg, finalNextPlayer);

                                finalNextPlayer.sendMessage(miniMessage.deserialize(formatedMsg));
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
                        String formatedMsg = MessageFormater.formatMessage(bannedMsg, player);

                        player.sendMessage(miniMessage.deserialize(formatedMsg));
                    } else if ("not_whitelisted".equals(issue)) {
                        String formatedMsg = MessageFormater.formatMessage(whitelistedMsg, player);

                        player.sendMessage(miniMessage.deserialize(formatedMsg));
                    }
                    continue;
                }

                RegisteredServer previousServer = playerManager.getPreviousServer(player);

                if (Utility.isServerInMaintenance(previousServer.getServerInfo().getName())) {
                    String formatedMsg = MessageFormater.formatMessage(maintenanceModeMsg, player);

                    player.sendMessage(miniMessage.deserialize(formatedMsg));
                    return;
                }

                // Only show queue position if queue is enabled
                if (!queueEnabled) continue;

                int position = playerManager.getQueuePosition(player);
                if (position == -1) continue;
                String formatedQueuePositionMsg = MessageFormater.formatMessage(queuePositionMsg, player);

                player.sendMessage(miniMessage.deserialize(formatedQueuePositionMsg));
            }
        }).repeat(queueInterval, TimeUnit.SECONDS).schedule();
    }

    public static boolean isQueueEnabled() {
        return queueEnabled;
    }
}

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
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(id = "velocity-limbo-handler", name = "VelocityLimboHandler", version = "1.0")
public class VelocityLimboHandler {
    private static ProxyServer proxyServer;
    private static Logger logger;
    private static RegisteredServer limboServer;
    private static RegisteredServer directConnectServer;

    private static PlayerManager playerManager;

    private static YamlDocument config;

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

        // TODO: make these respect config when config is added with .getServerFromProperty()
        limboServer = Utility.getServerByName("limbo");
        directConnectServer = Utility.getServerByName("lobby");

        // If either server is null, "self-destruct"
        if (limboServer == null || directConnectServer == null) {
            eventManger.unregisterListeners(this);
            return;
        }

        // TODO: Add listeners here!
        eventManger.register(this, new ConnectionListener());

        int reconnectInterval = config.getInt(Route.from("task-interval"));
        int queueInterval = config.getInt(Route.from("queue-notify-interval"));

        logger.info("Intervals " + reconnectInterval + " " + queueInterval);

        // Schedule the reconnection task
        proxyServer.getScheduler().buildTask(this, () -> {
            // Prevent NullPointerException when Queue is empty
            if (!playerManager.hasQueuedPlayers()) return;

            Player nextPlayer = playerManager.getNextQueuedPlayer();

            if (nextPlayer == null || !nextPlayer.isActive()) return;

            RegisteredServer previousServer = playerManager.getPreviousServer(nextPlayer);

            // If enabled, check if a server responds to pings before connecting
            try {
                // TODO: Add config check for pinging servers for now just ping
                try {
                    previousServer.ping().join(); // Check if the server is online
                } catch (CompletionException completionException) {
                    // Server failed to respond to ping request, return to prevent spam
                    return;
                }

                Utility.logInformational(String.format("Connecting %s to %s.", nextPlayer.getUsername(), previousServer.getServerInfo().getName()));
                nextPlayer.createConnectionRequest(previousServer).connect();

                //playerManager.removePlayer(nextPlayer);
            } catch (CompletionException exception) {
                // Prevent console from being spammed when a server is offline and ping-check is disabled
            }
        }).repeat(reconnectInterval, TimeUnit.SECONDS).schedule();

        // Schedule queue position notifier
        proxyServer.getScheduler().buildTask(this, () -> {
            for (Player player : playerManager.getReconnectQueue()) {

                if (!player.isActive()) {
                    playerManager.removePlayer(player);
                    continue;
                }

                int position = playerManager.getQueuePosition(player);

                if (position == -1) continue;

                player.sendMessage(miniMessage.deserialize("<yellow>Queue position: " + position));
            }
        }).repeat(queueInterval, TimeUnit.SECONDS).schedule();
    }
}

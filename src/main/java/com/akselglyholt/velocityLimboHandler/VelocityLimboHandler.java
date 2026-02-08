package com.akselglyholt.velocityLimboHandler;

import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.commands.CommandBlockRule;
import com.akselglyholt.velocityLimboHandler.commands.CommandBlocker;
import com.akselglyholt.velocityLimboHandler.commands.VlhAdminCommand;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.listeners.CommandExecuteEventListener;
import com.akselglyholt.velocityLimboHandler.listeners.ConnectionListener;
import com.akselglyholt.velocityLimboHandler.managers.ReconnectHandler;
import com.akselglyholt.velocityLimboHandler.misc.InMemoryReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.akselglyholt.velocityLimboHandler.tasks.QueueNotifierTask;
import com.akselglyholt.velocityLimboHandler.tasks.ReconnectionTask;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bstats.charts.SingleLineChart;
import org.bstats.velocity.Metrics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(id = "velocity-limbo-handler", name = "VelocityLimboHandler", authors = "Aksel Glyholt", version = VersionInfo.VERSION)
public class VelocityLimboHandler {
    private static VelocityLimboHandler instance;
    private static ProxyServer proxyServer;
    private static final Logger logger = Logger.getLogger("Limbo Handler");
    private static RegisteredServer limboServer;
    private static RegisteredServer directConnectServer;

    private static PlayerManager playerManager;
    private static CommandBlocker commandBlocker;
    private static ReconnectBlocker reconnectBlocker;
    private static AuthManager authManager;

    private ConfigManager configManager;
    private ReconnectHandler reconnectHandler;
    private ScheduledTask reconnectionTask;
    private ScheduledTask queueNotifierTask;

    private static boolean maintenancePluginPresent = false;
    private static Object maintenanceAPI = null;

    private final Metrics.Factory metricsFactory;

    @Inject
    public VelocityLimboHandler(ProxyServer server, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactoryInstance) {
        proxyServer = server;
        instance = this;
        metricsFactory = metricsFactoryInstance;

        // Initialize ConfigManager
        configManager = new ConfigManager(dataDirectory, logger);
        try {
            configManager.load();
        } catch (IOException e) {
            logger.severe("Something went wrong while trying to update/create config: " + e);
            logger.severe("Plugin will now shut down!");
            Optional<PluginContainer> container = proxyServer.getPluginManager().getPlugin("velocity-limbo-handler");
            container.ifPresent(pluginContainer -> pluginContainer.getExecutorService().shutdown());
        }

        playerManager = new PlayerManager();
        commandBlocker = new CommandBlocker();
        reconnectBlocker = new InMemoryReconnectBlocker();

        initializeMaintenanceIntegration();
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        // Initialize Metrics
        int pluginId = 26682;
        Metrics metrics = metricsFactory.make(this, pluginId);

        // Metric for players inside the limbo
        metrics.addCustomChart(new SingleLineChart("players_in_limbo", new Callable<Integer>() {
            @Override
            public Integer call() {
                return limboServer != null ? limboServer.getPlayersConnected().size() : 0;
            }
        }));

        // Initialize Managers
        authManager = new AuthManager(this, proxyServer, reconnectBlocker);
        reconnectHandler = new ReconnectHandler(playerManager, authManager, configManager, logger);

        logger.info("Loading Limbo Handler!");

        EventManager eventManger = proxyServer.getEventManager();

        String limboName = configManager.getLimboName();
        String directConnectName = configManager.getDirectConnectServerName();

        limboServer = Utility.getServerByName(limboName);
        directConnectServer = Utility.getServerByName(directConnectName);

        // If either server is null, "self-destruct"
        if (limboServer == null || directConnectServer == null) {
            eventManger.unregisterListeners(this);
            return;
        }

        eventManger.register(this, new ConnectionListener());
        eventManger.register(this, new CommandExecuteEventListener(commandBlocker));

        proxyServer.getCommandManager().register(
                proxyServer.getCommandManager().metaBuilder("vlh").plugin(this).build(),
                new VlhAdminCommand()
        );

        getLogger().info("Queue Enabled: " + configManager.isQueueEnabled());

        // Disabled commands
        List<String> disabledCommands = configManager.getDisabledCommands();
        for (String cmd : disabledCommands) {
            commandBlocker.blockCommand(cmd, CommandBlockRule.onServer(limboName));
        }

        reloadTasks();
    }

    public synchronized void reloadTasks() {
        if (reconnectionTask != null) {
            reconnectionTask.cancel();
            reconnectionTask = null;
        }

        if (queueNotifierTask != null) {
            queueNotifierTask.cancel();
            queueNotifierTask = null;
        }

        String limboName = configManager.getLimboName();
        String directConnectName = configManager.getDirectConnectServerName();

        limboServer = Utility.getServerByName(limboName);
        directConnectServer = Utility.getServerByName(directConnectName);

        if (limboServer == null || directConnectServer == null) {
            logger.warning("Skipping task scheduling: limbo or direct connect server is missing.");
            return;
        }

        reconnectionTask = proxyServer.getScheduler().buildTask(this,
                new ReconnectionTask(proxyServer, limboServer, playerManager, authManager, configManager, reconnectHandler)
        ).repeat(configManager.getTaskInterval(), TimeUnit.MILLISECONDS).schedule();

        queueNotifierTask = proxyServer.getScheduler().buildTask(this,
                new QueueNotifierTask(limboServer, playerManager, configManager)
        ).repeat(configManager.getQueueNotifyInterval(), TimeUnit.SECONDS).schedule();
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

    public static YamlDocument getMessageConfig() {
        return instance.configManager.getMessageConfig();
    }

    public static AuthManager getAuthManager() {
        return authManager;
    }

    public static ConfigManager getConfigManager() {
        return instance.configManager;
    }

    public static boolean isQueueEnabled() {
        return instance.configManager.isQueueEnabled();
    }

    public static VelocityLimboHandler getInstance() {
        return instance;
    }

    public static ReconnectBlocker getReconnectBlocker() {
        return reconnectBlocker;
    }
}

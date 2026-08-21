package com.akselglyholt.velocityLimboHandler;

import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.commands.CommandBlockRule;
import com.akselglyholt.velocityLimboHandler.commands.CommandBlocker;
import com.akselglyholt.velocityLimboHandler.commands.VlhAdminCommand;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.listeners.CommandExecuteEventListener;
import com.akselglyholt.velocityLimboHandler.listeners.ConnectionListener;
import com.akselglyholt.velocityLimboHandler.listeners.KickListener;
import com.akselglyholt.velocityLimboHandler.managers.ReconnectHandler;
import com.akselglyholt.velocityLimboHandler.misc.InMemoryReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.misc.ReleaseVersionChecker;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.akselglyholt.velocityLimboHandler.tasks.QueueNotifierTask;
import com.akselglyholt.velocityLimboHandler.tasks.ReconnectionTask;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bstats.charts.SingleLineChart;
import org.bstats.velocity.Metrics;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Plugin(id = "velocity-limbo-handler", name = "VelocityLimboHandler", authors = "Aksel Glyholt", version = VersionInfo.VERSION, dependencies = {
        @Dependency(id = "librelogin", optional = true),
        @Dependency(id = "libreloginnext", optional = true),
        @Dependency(id = "nlogin", optional = true),
        @Dependency(id = "maintenance", optional = true)
})
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

    private @Nullable Metrics bstatsMetrics = null;

    @Inject
    public VelocityLimboHandler(ProxyServer server, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactoryInstance) {
        proxyServer = server;
        instance = this;
        metricsFactory = metricsFactoryInstance;

        // Initialize ConfigManager
        configManager = new ConfigManager(dataDirectory, logger);
        try {
            configManager.load((limboName, directConnectName) ->
                    server.getServer(limboName).isPresent() && server.getServer(directConnectName).isPresent()
            );
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to load VelocityLimboHandler configuration", e);
            throw new IllegalStateException("VelocityLimboHandler cannot start without a valid configuration", e);
        }

        playerManager = new PlayerManager();
        commandBlocker = new CommandBlocker();
        reconnectBlocker = new InMemoryReconnectBlocker();

        initializeMaintenanceIntegration();
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        logger.info("Loading Limbo Handler!");
        proxyServer.getScheduler()
                .buildTask(this, () -> ReleaseVersionChecker.checkForUpdate(VersionInfo.VERSION, logger))
                .schedule();

        EventManager eventManger = proxyServer.getEventManager();

        String limboName = configManager.getLimboName();
        String directConnectName = configManager.getDirectConnectServerName();

        limboServer = Utility.getServerByName(limboName);
        directConnectServer = Utility.getServerByName(directConnectName);

        // A server can disappear after constructor-time validation if Velocity's registry changes.
        if (limboServer == null || directConnectServer == null) {
            return;
        }

        // Initialize metrics and managers only after all required runtime dependencies exist.
        int pluginId = 26682;
        bstatsMetrics = metricsFactory.make(this, pluginId);
        bstatsMetrics.addCustomChart(new SingleLineChart("players_in_limbo", new Callable<Integer>() {
            @Override
            public Integer call() {
                return limboServer.getPlayersConnected().size();
            }
        }));

        authManager = new AuthManager(this, proxyServer, reconnectBlocker);
        reconnectHandler = new ReconnectHandler(playerManager, authManager, configManager, logger);

        eventManger.register(this, new ConnectionListener());
        eventManger.register(this, new KickListener());
        eventManger.register(this, new CommandExecuteEventListener(commandBlocker, configManager));

        proxyServer.getCommandManager().register(proxyServer.getCommandManager().metaBuilder("vlh").plugin(this).build(), new VlhAdminCommand());

        getLogger().info("Queue Enabled: " + configManager.isQueueEnabled());

        // Disabled commands
        commandBlocker.replaceCommands(configManager.getDisabledCommands(), CommandBlockRule.onServer(limboName));

        reloadTasks();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        cancelScheduledTasks();
        if (bstatsMetrics != null) bstatsMetrics.shutdown();
        if (reconnectHandler != null) reconnectHandler.close();
        if (authManager != null) authManager.close();
        Utility.clearMaintenanceAdapter();
    }

    public synchronized void reloadTasks() {
        cancelScheduledTasks();

        String limboName = configManager.getLimboName();
        String directConnectName = configManager.getDirectConnectServerName();

        limboServer = Utility.getServerByName(limboName);
        directConnectServer = Utility.getServerByName(directConnectName);

        if (limboServer == null || directConnectServer == null) {
            logger.warning("Skipping task scheduling: limbo or direct connect server is missing.");
            return;
        }

        reconnectionTask = proxyServer.getScheduler().buildTask(this, new ReconnectionTask(proxyServer, limboServer, playerManager, authManager, configManager, reconnectHandler)).repeat(configManager.getTaskInterval(), TimeUnit.MILLISECONDS).schedule();

        queueNotifierTask = proxyServer.getScheduler()
                .buildTask(this, new QueueNotifierTask(proxyServer, limboServer, playerManager, configManager))
                .repeat(1, TimeUnit.SECONDS)
                .schedule();
    }

    private synchronized void cancelScheduledTasks() {
        if (reconnectionTask != null) {
            reconnectionTask.cancel();
            reconnectionTask = null;
        }

        if (queueNotifierTask != null) {
            queueNotifierTask.cancel();
            queueNotifierTask = null;
        }
    }

    public synchronized void applyReloadedConfiguration() {
        playerManager.reloadMessages();
        commandBlocker.replaceCommands(
                configManager.getDisabledCommands(),
                CommandBlockRule.onServer(configManager.getLimboName())
        );
        reloadTasks();
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

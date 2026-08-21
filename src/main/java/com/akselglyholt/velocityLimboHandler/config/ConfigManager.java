package com.akselglyholt.velocityLimboHandler.config;

import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

public class ConfigManager {
    private static final int MIN_TASK_INTERVAL_MILLIS = 250;
    private static final int MAX_TASK_INTERVAL_MILLIS = 60_000;
    private static final int MAX_NOTIFY_INTERVAL_SECONDS = 3_600;
    private static final int MAX_RECONNECT_BATCH_SIZE = 1_024;
    private static final int MAX_NOTIFY_BATCH_SIZE = 4_096;

    private final Path dataDirectory;
    private final Logger logger;
    private volatile Snapshot snapshot;

    public ConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public void load() throws IOException {
        load((limboName, directConnectName) -> true);
    }

    public synchronized void load(BiPredicate<String, String> serverValidator) throws IOException {
        Objects.requireNonNull(serverValidator, "serverValidator");
        YamlDocument loadedConfig = YamlDocument.create(
                new File(dataDirectory.toFile(), "config.yml"),
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setAutoUpdate(true).build(),
                DumperSettings.DEFAULT,
                UpdaterSettings.builder()
                        .setVersioning(new BasicVersioning("file-version"))
                        .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS)
                        .build()
        );

        YamlDocument loadedMessages = YamlDocument.create(
                new File(dataDirectory.toFile(), "messages.yml"),
                Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml")),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setAutoUpdate(true).build(),
                DumperSettings.DEFAULT,
                UpdaterSettings.builder()
                        .setVersioning(new BasicVersioning("file-version"))
                        .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS)
                        .build()
        );

        loadedConfig.update();
        loadedConfig.save();
        loadedMessages.update();
        loadedMessages.save();

        String limboName = loadedConfig.getString(Route.from("limbo-name"));
        String directConnectServerName = loadedConfig.getString(Route.from("direct-connect-server"));
        if (limboName == null || limboName.isBlank()
                || directConnectServerName == null || directConnectServerName.isBlank()
                || !serverValidator.test(limboName, directConnectServerName)) {
            throw new IOException("Configured limbo or direct-connect server is not registered in Velocity");
        }

        Snapshot freshSnapshot = new Snapshot(
                loadedConfig,
                loadedMessages,
                loadedMessages.getString(Route.from("bannedMessage")),
                loadedMessages.getString(Route.from("notWhitelisted")),
                loadedMessages.getString(Route.from("maintenanceMode")),
                loadedMessages.getString(Route.from("queuePosition")),
                loadedMessages.getString(Route.from("queuePositionJoin")),
                loadedMessages.getString(Route.from("welcomeMessage")),
                loadedMessages.getString(Route.from("commandBlocked")),
                limboName,
                directConnectServerName,
                bounded("task-interval", loadedConfig.getInt("task-interval", 3_000),
                        MIN_TASK_INTERVAL_MILLIS, MAX_TASK_INTERVAL_MILLIS),
                bounded("reconnect-batch-size", loadedConfig.getInt("reconnect-batch-size", 8),
                        1, MAX_RECONNECT_BATCH_SIZE),
                bounded("queue-notify-interval", loadedConfig.getInt("queue-notify-interval", 30),
                        1, MAX_NOTIFY_INTERVAL_SECONDS),
                bounded("queue-notify-batch-size", loadedConfig.getInt("queue-notify-batch-size", 64),
                        1, MAX_NOTIFY_BATCH_SIZE),
                loadedConfig.getBoolean("queue-enabled", true),
                List.copyOf(loadedConfig.getStringList("disabled-commands")),
                normalizeExcludedKickReasons(loadedConfig.getStringList("excluded-kick-reasons")),
                loadedConfig.getBoolean("connection-warnings", false),
                loadedConfig.getBoolean("debug", false)
        );

        snapshot = freshSnapshot;
        MessageFormatter.clearCache();
    }

    private List<String> normalizeExcludedKickReasons(List<String> reasons) {
        return reasons.stream()
                .map(reason -> reason.trim().toLowerCase(Locale.ROOT))
                .filter(reason -> !reason.isEmpty())
                .toList();
    }

    private int bounded(String option, int value, int minimum, int maximum) {
        int bounded = Math.max(minimum, Math.min(value, maximum));
        if (bounded != value) {
            logger.warning(option + " must be between " + minimum + " and " + maximum
                    + "; using " + bounded + " instead of " + value + '.');
        }
        return bounded;
    }

    private Snapshot current() {
        Snapshot current = snapshot;
        if (current == null) {
            throw new IllegalStateException("Configuration has not been loaded");
        }
        return current;
    }

    public YamlDocument getConfig() {
        return current().config();
    }

    public YamlDocument getMessageConfig() {
        return current().messages();
    }

    public String getBannedMsg() {
        return current().bannedMsg();
    }

    public String getWhitelistedMsg() {
        return current().whitelistedMsg();
    }

    public String getMaintenanceModeMsg() {
        return current().maintenanceModeMsg();
    }

    public String getQueuePositionMsg() {
        return current().queuePositionMsg();
    }

    public String getQueuePositionJoinMsg() {
        return current().queuePositionJoinMsg();
    }

    public String getWelcomeMsg() {
        return current().welcomeMsg();
    }

    public String getCommandBlockedMsg() {
        return current().commandBlockedMsg();
    }

    public String getLimboName() {
        return current().limboName();
    }

    public String getDirectConnectServerName() {
        return current().directConnectServerName();
    }

    public int getTaskInterval() {
        return current().taskInterval();
    }

    public int getQueueNotifyInterval() {
        return current().queueNotifyInterval();
    }

    public int getReconnectBatchSize() {
        return current().reconnectBatchSize();
    }

    public int getQueueNotifyBatchSize() {
        return current().queueNotifyBatchSize();
    }

    public boolean isQueueEnabled() {
        return current().queueEnabled();
    }

    public List<String> getDisabledCommands() {
        return current().disabledCommands();
    }

    public List<String> getExcludedKickReasons() {
        return current().excludedKickReasons();
    }

    public boolean isConnectionWarningsEnabled() {
        return current().connectionWarnings();
    }

    public boolean isDebugEnabled() {
        return current().debug();
    }

    private record Snapshot(
            YamlDocument config,
            YamlDocument messages,
            String bannedMsg,
            String whitelistedMsg,
            String maintenanceModeMsg,
            String queuePositionMsg,
            String queuePositionJoinMsg,
            String welcomeMsg,
            String commandBlockedMsg,
            String limboName,
            String directConnectServerName,
            int taskInterval,
            int reconnectBatchSize,
            int queueNotifyInterval,
            int queueNotifyBatchSize,
            boolean queueEnabled,
            List<String> disabledCommands,
            List<String> excludedKickReasons,
            boolean connectionWarnings,
            boolean debug
    ) {
    }
}

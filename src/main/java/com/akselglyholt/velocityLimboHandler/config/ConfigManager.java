package com.akselglyholt.velocityLimboHandler.config;

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
import java.util.Objects;
import java.util.logging.Logger;

public class ConfigManager {
    private final Path dataDirectory;
    private final Logger logger;
    private YamlDocument config;
    private YamlDocument messageConfig;

    // Cached values
    private String bannedMsg;
    private String whitelistedMsg;
    private String maintenanceModeMsg;
    private String queuePositionMsg;
    private String queuePositionJoinMsg;

    private String limboName;
    private String directConnectServerName;
    private int taskInterval;
    private int queueNotifyInterval;
    private boolean queueEnabled;
    private List<String> disabledCommands;

    public ConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public void load() throws IOException {
        config = YamlDocument.create(
                new File(dataDirectory.toFile(), "config.yml"),
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setAutoUpdate(true).build(),
                DumperSettings.DEFAULT,
                UpdaterSettings.builder().setVersioning(new BasicVersioning("file-version")).setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build()
        );

        messageConfig = YamlDocument.create(
                new File(dataDirectory.toFile(), "messages.yml"),
                Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml")),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setAutoUpdate(true).build(),
                DumperSettings.DEFAULT,
                UpdaterSettings.builder().setVersioning(new BasicVersioning("file-version")).setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build()
        );

        config.update();
        config.save();

        messageConfig.update();
        messageConfig.save();

        cacheValues();
    }

    private void cacheValues() {
        bannedMsg = messageConfig.getString(Route.from("bannedMessage"));
        whitelistedMsg = messageConfig.getString(Route.from("notWhitelisted"));
        maintenanceModeMsg = messageConfig.getString(Route.from("maintenanceMode"));
        queuePositionMsg = messageConfig.getString(Route.from("queuePosition"));
        queuePositionJoinMsg = messageConfig.getString(Route.from("queuePositionJoin"));

        limboName = config.getString(Route.from("limbo-name"));
        directConnectServerName = config.getString(Route.from("direct-connect-server"));
        taskInterval = config.getInt(Route.from("task-interval"));
        queueNotifyInterval = config.getInt(Route.from("queue-notify-interval"));
        queueEnabled = config.getBoolean(Route.from("queue-enabled"), true);
        disabledCommands = config.getStringList("disabled-commands");
    }

    public YamlDocument getConfig() {
        return config;
    }

    public YamlDocument getMessageConfig() {
        return messageConfig;
    }

    public String getBannedMsg() {
        return bannedMsg;
    }

    public String getWhitelistedMsg() {
        return whitelistedMsg;
    }

    public String getMaintenanceModeMsg() {
        return maintenanceModeMsg;
    }

    public String getQueuePositionMsg() {
        return queuePositionMsg;
    }

    public String getQueuePositionJoinMsg() {
        return queuePositionJoinMsg;
    }

    public String getLimboName() {
        return limboName;
    }

    public String getDirectConnectServerName() {
        return directConnectServerName;
    }

    public int getTaskInterval() {
        return taskInterval;
    }

    public int getQueueNotifyInterval() {
        return queueNotifyInterval;
    }

    public boolean isQueueEnabled() {
        return queueEnabled;
    }

    public List<String> getDisabledCommands() {
        return disabledCommands;
    }
}

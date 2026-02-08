package com.akselglyholt.velocityLimboHandler.commands;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class VlhAdminCommand implements SimpleCommand {
    private static final String ADMIN_PERMISSION = "vlh.admin";
    private static final String RELOAD_PERMISSION = "vlh.admin.reload";
    private static final String STATUS_PERMISSION = "vlh.admin.status";

    private final MiniMessage miniMessage;

    public VlhAdminCommand() {
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        if (!source.hasPermission(ADMIN_PERMISSION)) {
            source.sendMessage(miniMessage.deserialize("<red>You do not have permission to use this command.</red>"));
            return;
        }

        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            sendUsage(source);
            return;
        }

        String subcommand = arguments[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "reload" -> handleReload(source);
            case "status" -> handleStatus(source);
            default -> sendUsage(source);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission(ADMIN_PERMISSION)) {
            return Collections.emptyList();
        }

        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            return Arrays.asList("reload", "status");
        }

        if (arguments.length == 1) {
            String input = arguments[0].toLowerCase(Locale.ROOT);
            return Arrays.asList("reload", "status").stream()
                    .filter(subcommand -> subcommand.startsWith(input))
                    .toList();
        }

        return Collections.emptyList();
    }

    private void handleReload(CommandSource source) {
        if (!source.hasPermission(RELOAD_PERMISSION)) {
            source.sendMessage(miniMessage.deserialize("<red>You do not have permission to reload VelocityLimboHandler.</red>"));
            return;
        }

        ConfigManager configManager = VelocityLimboHandler.getConfigManager();

        try {
            configManager.load();
            VelocityLimboHandler.getPlayerManager().reloadMessages();
            source.sendMessage(miniMessage.deserialize("<green>VelocityLimboHandler configuration and messages reloaded.</green>"));
        } catch (IOException exception) {
            source.sendMessage(miniMessage.deserialize("<red>Failed to reload configuration. Check console for details.</red>"));
            VelocityLimboHandler.getLogger().severe("Failed to reload configuration: " + exception.getMessage());
        }
    }

    private void handleStatus(CommandSource source) {
        if (!source.hasPermission(STATUS_PERMISSION)) {
            source.sendMessage(miniMessage.deserialize("<red>You do not have permission to view status.</red>"));
            return;
        }

        PlayerManager playerManager = VelocityLimboHandler.getPlayerManager();
        RegisteredServer limboServer = VelocityLimboHandler.getLimboServer();

        String limboName = limboServer != null ? limboServer.getServerInfo().getName() : "Not configured";
        String queueEnabled = VelocityLimboHandler.isQueueEnabled() ? "<green>Enabled</green>" : "<red>Disabled</red>";
        int queuedServers = playerManager.getQueuedServerCount();
        int queuedPlayers = playerManager.getQueuedPlayerCount();

        source.sendMessage(miniMessage.deserialize("<gray>----- <aqua>VelocityLimboHandler Status</aqua> -----</gray>"));
        source.sendMessage(miniMessage.deserialize("<yellow>Limbo server:</yellow> <white>" + limboName + "</white>"));
        source.sendMessage(miniMessage.deserialize("<yellow>Queue:</yellow> " + queueEnabled));
        source.sendMessage(miniMessage.deserialize("<yellow>Queued servers:</yellow> <white>" + queuedServers + "</white>"));
        source.sendMessage(miniMessage.deserialize("<yellow>Queued players:</yellow> <white>" + queuedPlayers + "</white>"));
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(miniMessage.deserialize("<yellow>Usage:</yellow> <white>/vlh &lt;reload|status&gt;</white>"));
    }
}

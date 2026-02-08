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

    private static final String PREFIX = "<dark_gray>[</dark_gray><aqua>VLH</aqua><dark_gray>]</dark_gray> ";
    private static final String BORDER = "<dark_gray><strikethrough>------------------------------</strikethrough></dark_gray>";

    private final MiniMessage miniMessage;

    public VlhAdminCommand() {
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        if (!source.hasPermission(ADMIN_PERMISSION)) {
            send(source, "<red>You do not have permission to use this command.</red>");
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
            send(source, "<red>You do not have permission to reload VelocityLimboHandler.</red>");
            return;
        }

        ConfigManager configManager = VelocityLimboHandler.getConfigManager();

        try {
            configManager.load();
            VelocityLimboHandler.getPlayerManager().reloadMessages();
            send(source, "<green>✔ Reload complete.</green> <gray>Configuration and message files were refreshed.</gray>");
        } catch (IOException exception) {
            send(source, "<red>✖ Reload failed.</red> <gray>Could not reload configuration. Check console for details.</gray>");
            VelocityLimboHandler.getLogger().severe("Failed to reload configuration: " + exception.getMessage());
        }
    }

    private void handleStatus(CommandSource source) {
        if (!source.hasPermission(STATUS_PERMISSION)) {
            send(source, "<red>You do not have permission to view status.</red>");
            return;
        }

        PlayerManager playerManager = VelocityLimboHandler.getPlayerManager();
        RegisteredServer limboServer = VelocityLimboHandler.getLimboServer();

        String limboName = limboServer != null ? "<white>" + limboServer.getServerInfo().getName() + "</white>" : "<red>Not configured</red>";
        String queueEnabled = VelocityLimboHandler.isQueueEnabled() ? "<green>Enabled</green>" : "<red>Disabled</red>";
        int queuedServers = playerManager.getQueuedServerCount();
        int queuedPlayers = playerManager.getQueuedPlayerCount();

        source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
        send(source, "<gradient:#00D4FF:#7AF7C5><bold>Velocity Limbo Handler Status</bold></gradient>");
        send(source, "<gray>•</gray> <yellow>Limbo Server:</yellow> " + limboName);
        send(source, "<gray>•</gray> <yellow>Queue System:</yellow> " + queueEnabled);
        send(source, "<gray>•</gray> <yellow>Queued Servers:</yellow> <white>" + queuedServers + "</white>");
        send(source, "<gray>•</gray> <yellow>Queued Players:</yellow> <white>" + queuedPlayers + "</white>");
        source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(miniMessage.deserialize(PREFIX + "<yellow>Usage:</yellow> <white>/vlh &lt;reload|status&gt;</white>"));
        source.sendMessage(miniMessage.deserialize(
                PREFIX + "<gray>Commands:</gray> "
                        + "<aqua><hover:show_text:'<gray>Reload VLH configuration and messages</gray>'><click:run_command:'/vlh reload'>reload</click></hover></aqua>"
                        + "<gray> • </gray>"
                        + "<aqua><hover:show_text:'<gray>View plugin runtime status</gray>'><click:run_command:'/vlh status'>status</click></hover></aqua>"
        ));
    }

    private void send(CommandSource source, String body) {
        source.sendMessage(miniMessage.deserialize(PREFIX + body));
    }
}

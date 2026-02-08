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
import java.util.Map;

public class VlhAdminCommand implements SimpleCommand {
    private static final String ADMIN_PERMISSION = "vlh.admin";
    private static final String RELOAD_PERMISSION = "vlh.admin.reload";
    private static final String STATUS_PERMISSION = "vlh.admin.status";
    private static final String QUEUE_PERMISSION = "vlh.admin.queue";

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
            case "queue" -> handleQueue(source, arguments);
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
            return Arrays.asList("reload", "status", "queue");
        }

        if (arguments.length == 1) {
            String input = arguments[0].toLowerCase(Locale.ROOT);
            return Arrays.asList("reload", "status", "queue").stream()
                    .filter(subcommand -> subcommand.startsWith(input))
                    .toList();
        }

        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("queue")
                && invocation.source().hasPermission(QUEUE_PERMISSION)) {
            String input = arguments[1].toLowerCase(Locale.ROOT);
            return VelocityLimboHandler.getProxyServer().getAllServers().stream()
                    .map(registeredServer -> registeredServer.getServerInfo().getName())
                    .sorted()
                    .filter(serverName -> serverName.toLowerCase(Locale.ROOT).startsWith(input))
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

    private void handleQueue(CommandSource source, String[] arguments) {
        if (!source.hasPermission(QUEUE_PERMISSION)) {
            send(source, "<red>You do not have permission to view queue status.</red>");
            return;
        }

        PlayerManager playerManager = VelocityLimboHandler.getPlayerManager();

        if (arguments.length < 2) {
            Map<String, Integer> queueCounts = playerManager.getQueuedServerCounts();
            if (queueCounts.isEmpty()) {
                send(source, "<yellow>No servers currently have queued players.</yellow>");
                return;
            }

            source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
            send(source, "<gradient:#00D4FF:#7AF7C5><bold>Queue Overview</bold></gradient>");
            queueCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> send(source,
                            "<gray>•</gray> <aqua>" + entry.getKey() + "</aqua>: <white>" + entry.getValue() + "</white>"
                                    + " <gray>player" + (entry.getValue() == 1 ? "" : "s") + "</gray>"));
            source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
            return;
        }

        String serverName = arguments[1];
        List<PlayerManager.QueuedPlayer> queue = playerManager.getQueueForServer(serverName);

        if (queue.isEmpty()) {
            send(source, "<yellow>No queued players for server:</yellow> <white>" + serverName + "</white>");
            return;
        }

        source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
        send(source, "<gradient:#00D4FF:#7AF7C5><bold>Queue for " + serverName + "</bold></gradient>");

        for (int i = 0; i < queue.size(); i++) {
            PlayerManager.QueuedPlayer queuedPlayer = queue.get(i);
            int position = i + 1;
            send(source,
                    "<gray>" + position + ".</gray> "
                            + "<aqua><hover:show_text:'<gray>UUID: <white>" + queuedPlayer.uuid() + "</white></gray>'>"
                            + queuedPlayer.name() + "</hover></aqua>");
        }

        source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(miniMessage.deserialize(PREFIX + "<yellow>Usage:</yellow> <white>/vlh &lt;reload|status|queue [server]&gt;</white>"));
        source.sendMessage(miniMessage.deserialize(
                PREFIX + "<gray>Commands:</gray> "
                        + "<aqua><hover:show_text:'<gray>Reload VLH configuration and messages</gray>'><click:run_command:'/vlh reload'>reload</click></hover></aqua>"
                        + "<gray> • </gray>"
                        + "<aqua><hover:show_text:'<gray>View plugin runtime status</gray>'><click:run_command:'/vlh status'>status</click></hover></aqua>"
                        + "<gray> • </gray>"
                        + "<aqua><hover:show_text:'<gray>Show queue status for all servers or one server</gray>'><click:run_command:'/vlh queue'>queue</click></hover></aqua>"
        ));
    }

    private void send(CommandSource source, String body) {
        source.sendMessage(miniMessage.deserialize(PREFIX + body));
    }
}

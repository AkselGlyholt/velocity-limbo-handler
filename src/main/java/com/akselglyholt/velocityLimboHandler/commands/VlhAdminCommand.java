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
    private static final int QUEUE_PAGE_SIZE = 10;

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

        // /vlh queue
        if (arguments.length == 1) {
            showQueueOverview(source, 1);
            return;
        }

        // /vlh queue [server] [page]
        // Compatibility: /vlh queue [page] still works when first arg is numeric.
        String serverOrPage = arguments[1];
        if (isPositiveInteger(serverOrPage)) {
            showQueueOverview(source, Integer.parseInt(serverOrPage));
            return;
        }

        String serverName = serverOrPage;
        int page = arguments.length >= 3 ? parsePositiveInteger(arguments[2], 1) : 1;
        showServerQueue(source, serverName, page);
    }

    private void showQueueOverview(CommandSource source, int requestedPage) {
        PlayerManager playerManager = VelocityLimboHandler.getPlayerManager();
        List<Map.Entry<String, Integer>> serverQueues = playerManager.getQueuedServerCounts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        if (serverQueues.isEmpty()) {
            send(source, "<yellow>No servers currently have queued players.</yellow>");
            return;
        }

        int totalPages = getTotalPages(serverQueues.size(), QUEUE_PAGE_SIZE);
        int currentPage = clampPage(requestedPage, totalPages);
        int startIndex = (currentPage - 1) * QUEUE_PAGE_SIZE;
        int endIndex = Math.min(startIndex + QUEUE_PAGE_SIZE, serverQueues.size());

        source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
        send(source, "<gradient:#00D4FF:#7AF7C5><bold>Queue Overview</bold></gradient>");

        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<String, Integer> entry = serverQueues.get(i);
            send(source,
                    "<gray>•</gray> <aqua>" + entry.getKey() + "</aqua>: <white>" + entry.getValue() + "</white>"
                            + " <gray>player" + (entry.getValue() == 1 ? "" : "s") + "</gray>");
        }

        sendPagination(source, currentPage, totalPages, "/vlh queue " + (currentPage - 1), "/vlh queue " + (currentPage + 1));
        source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
    }

    private void showServerQueue(CommandSource source, String serverName, int requestedPage) {
        PlayerManager playerManager = VelocityLimboHandler.getPlayerManager();
        List<PlayerManager.QueuedPlayer> queue = playerManager.getQueueForServer(serverName);

        if (queue.isEmpty()) {
            send(source, "<yellow>No queued players for server:</yellow> <white>" + serverName + "</white>");
            return;
        }

        int totalPages = getTotalPages(queue.size(), QUEUE_PAGE_SIZE);
        int currentPage = clampPage(requestedPage, totalPages);
        int startIndex = (currentPage - 1) * QUEUE_PAGE_SIZE;
        int endIndex = Math.min(startIndex + QUEUE_PAGE_SIZE, queue.size());

        source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
        send(source, "<gradient:#00D4FF:#7AF7C5><bold>Queue for " + serverName + "</bold></gradient>");

        for (int i = startIndex; i < endIndex; i++) {
            PlayerManager.QueuedPlayer queuedPlayer = queue.get(i);
            int position = i + 1;
            send(source,
                    "<gray>" + position + ".</gray> "
                            + "<aqua><hover:show_text:'<gray>UUID: <white>" + queuedPlayer.uuid() + "</white></gray>'>"
                            + queuedPlayer.name() + "</hover></aqua>");
        }

        sendPagination(
                source,
                currentPage,
                totalPages,
                "/vlh queue " + serverName + " " + (currentPage - 1),
                "/vlh queue " + serverName + " " + (currentPage + 1)
        );
        source.sendMessage(miniMessage.deserialize(PREFIX + BORDER));
    }

    private void sendPagination(CommandSource source, int currentPage, int totalPages, String previousCommand, String nextCommand) {
        boolean hasPrevious = currentPage > 1;
        boolean hasNext = currentPage < totalPages;

        String previous = hasPrevious
                ? "<aqua><hover:show_text:'<gray>Go to previous page</gray>'><click:run_command:'" + previousCommand + "'>[Previous]</click></hover></aqua>"
                : "<dark_gray>[Previous]</dark_gray>";

        String next = hasNext
                ? "<aqua><hover:show_text:'<gray>Go to next page</gray>'><click:run_command:'" + nextCommand + "'>[Next]</click></hover></aqua>"
                : "<dark_gray>[Next]</dark_gray>";

        send(source,
                previous
                        + " <dark_gray>|</dark_gray> "
                        + "<gray>Page <white>" + currentPage + "</white> <gray>of</gray> <white>" + totalPages + "</white></gray>"
                        + " <dark_gray>|</dark_gray> "
                        + next);
    }

    private int getTotalPages(int itemCount, int pageSize) {
        return Math.max(1, (int) Math.ceil((double) itemCount / pageSize));
    }

    private int clampPage(int requestedPage, int totalPages) {
        return Math.max(1, Math.min(requestedPage, totalPages));
    }

    private boolean isPositiveInteger(String input) {
        try {
            return Integer.parseInt(input) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private int parsePositiveInteger(String input, int fallback) {
        try {
            int parsed = Integer.parseInt(input);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(miniMessage.deserialize(PREFIX + "<yellow>Usage:</yellow> <white>/vlh &lt;reload|status|queue [server] [page]&gt;</white>"));
        source.sendMessage(miniMessage.deserialize(
                PREFIX + "<gray>Commands:</gray> "
                        + "<aqua><hover:show_text:'<gray>Reload VLH configuration and messages</gray>'><click:run_command:'/vlh reload'>reload</click></hover></aqua>"
                        + "<gray> • </gray>"
                        + "<aqua><hover:show_text:'<gray>View plugin runtime status</gray>'><click:run_command:'/vlh status'>status</click></hover></aqua>"
                        + "<gray> • </gray>"
                        + "<aqua><hover:show_text:'<gray>Show queue status (supports pagination)</gray>'><click:run_command:'/vlh queue'>queue</click></hover></aqua>"
        ));
    }

    private void send(CommandSource source, String body) {
        source.sendMessage(miniMessage.deserialize(PREFIX + body));
    }
}

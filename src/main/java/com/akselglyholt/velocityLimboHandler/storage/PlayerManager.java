package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerManager {
    private final Map<Player, RegisteredServer> playerData;
    private final Queue<Player> reconnectQueue = new LinkedList<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, String> playerConnectionIssues = new ConcurrentHashMap<>();

    public PlayerManager() {
        this.playerData = new LinkedHashMap<>();
        VelocityLimboHandler.getLogger().info("⚠️ New PlayerManager instance created!");
    }

    public void addPlayer(Player player, RegisteredServer registeredServer) {
        // Don't override if the player is already registered
        if (!this.playerData.containsKey(player)) {
            this.playerData.put(player, registeredServer);
        }

        // Only maintain a reconnect queue when queue mode is enabled
        if (VelocityLimboHandler.isQueueEnabled() && !this.reconnectQueue.contains(player)) {
            this.reconnectQueue.add(player);

            int position = getQueuePosition(player);
            player.sendMessage(miniMessage.deserialize("<yellow>You are in the queue. Position: " + position));
        }
    }


    public void removePlayer(Player player) {
        this.playerData.remove(player);
        this.reconnectQueue.remove(player);
    }

    public RegisteredServer getPreviousServer(Player player) {
        return this.playerData.getOrDefault(player, VelocityLimboHandler.getDirectConnectServer());
    }

    public boolean isPlayerRegistered(Player player) {
        return playerData.containsKey(player);
    }

    public Player getNextQueuedPlayer() {
        return reconnectQueue.peek();
    }

    public boolean hasQueuedPlayers() {
        return !reconnectQueue.isEmpty();
    }

    public int getQueuePosition(Player player) {
        int position = 1;
        for (Player p : reconnectQueue) {
            if (p.equals(player)) {
                return position;
            }
            position++;
        }
        return -1; // Player is not in the queue
    }

    public Queue<Player> getReconnectQueue() {
        return reconnectQueue;
    }

    public void addPlayerWithIssue(Player player, String issue) {
        playerConnectionIssues.put(player.getUniqueId(), issue);
    }

    public boolean hasConnectionIssue(Player player) {
        return playerConnectionIssues.containsKey(player.getUniqueId());
    }

    public String getConnectionIssue(Player player) {
        return playerConnectionIssues.get(player.getUniqueId());
    }

    public void removePlayerIssue(Player player) {
        playerConnectionIssues.remove(player.getUniqueId());
    }
}

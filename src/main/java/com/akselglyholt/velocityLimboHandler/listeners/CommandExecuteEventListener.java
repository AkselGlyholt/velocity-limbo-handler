package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.commands.CommandBlockRule;
import com.akselglyholt.velocityLimboHandler.commands.CommandBlocker;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Map;
import java.util.Optional;

public class CommandExecuteEventListener {
    private final CommandBlocker commandBlocker;

    public CommandExecuteEventListener(CommandBlocker commandBlocker) {
        this.commandBlocker = commandBlocker;
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }

        Optional<ServerConnection> serverConnection = player.getCurrentServer();

        if (serverConnection.isPresent()) {
            String serverName = serverConnection.get().getServerInfo().getName();
            String command = event.getCommand();
            String commandName = command.split(" ")[0].toLowerCase(); // Extract command name

            // Get the rule for this specific command
            CommandBlockRule rule = commandBlocker.getCommandRules().get(commandName);

            if (rule != null && rule.shouldBlock(player)) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                player.sendMessage(Component.text("Commands are disabled on this server!")
                        .color(NamedTextColor.RED));
            }
        }
    }
}

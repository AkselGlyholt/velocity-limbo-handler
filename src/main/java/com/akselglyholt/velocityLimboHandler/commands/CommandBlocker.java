package com.akselglyholt.velocityLimboHandler.commands;

import java.util.HashMap;
import java.util.Collection;
import java.util.Map;
import java.util.Locale;

public class CommandBlocker {
    private volatile Map<String, CommandBlockRule> commandRules = Map.of();

    public synchronized void blockCommand(String command, CommandBlockRule rule) {
        Map<String, CommandBlockRule> updatedRules = new HashMap<>(commandRules);
        updatedRules.put(command.toLowerCase(Locale.ROOT), rule);
        commandRules = Map.copyOf(updatedRules);
    }

    public void replaceCommands(Collection<String> commands, CommandBlockRule rule) {
        Map<String, CommandBlockRule> updatedRules = new HashMap<>();
        for (String command : commands) {
            updatedRules.put(command.toLowerCase(Locale.ROOT), rule);
        }
        commandRules = Map.copyOf(updatedRules);
    }

    public CommandBlockRule getRule(String command) {
        return commandRules.get(command);
    }
}

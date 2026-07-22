package com.akselglyholt.velocityLimboHandler.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigManagerTest {

    @Test
    void rejectedServerValidationPreservesPublishedSnapshot(@TempDir Path dataDirectory) throws IOException {
        ConfigManager configManager = new ConfigManager(dataDirectory, Logger.getAnonymousLogger());
        configManager.load();
        String publishedLimboName = configManager.getLimboName();

        Files.writeString(dataDirectory.resolve("config.yml"), """
                file-version: 9
                limbo-name: missing
                direct-connect-server: lobby
                """);

        assertThrows(IOException.class, () -> configManager.load(
                (limboName, directConnectName) -> !"missing".equals(limboName)
        ));
        assertEquals(publishedLimboName, configManager.getLimboName());
    }
}
